/*
 *  Copyright (c) 2021, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.apache.synapse.transport.netty.util;

import io.netty.util.AttributeKey;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMOutputFormat;
import org.apache.axiom.soap.SOAP11Constants;
import org.apache.axiom.soap.SOAP12Constants;
import org.apache.axiom.util.UIDGenerator;
import org.apache.axis2.AxisFault;
import org.apache.axis2.Constants;
import org.apache.axis2.addressing.EndpointReference;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.description.Parameter;
import org.apache.axis2.description.TransportInDescription;
import org.apache.axis2.description.java2wsdl.Java2WSDLConstants;
import org.apache.axis2.transport.MessageFormatter;
import org.apache.axis2.transport.RequestResponseTransport;
import org.apache.axis2.transport.TransportUtils;
import org.apache.axis2.transport.base.BaseConstants;
import org.apache.axis2.transport.http.HTTPConstants;
import org.apache.axis2.transport.http.SOAPMessageFormatter;
import org.apache.axis2.util.MessageProcessorSelector;
import org.apache.http.protocol.HTTP;
import org.apache.log4j.Logger;
import org.apache.synapse.transport.netty.BridgeConstants;
import org.apache.synapse.transport.netty.config.NettyConfiguration;
import org.apache.synapse.transport.netty.config.SourceConfiguration;
import org.apache.synapse.transport.netty.sender.SourceResponseDelete;
import org.apache.synapse.transport.nhttp.HttpCoreRequestResponseTransport;
import org.apache.synapse.transport.nhttp.NhttpConstants;
import org.apache.synapse.transport.nhttp.util.SecureVaultValueReader;
import org.apache.synapse.transport.passthru.PassThroughConstants;
import org.apache.synapse.transport.passthru.util.PassThroughTransportUtils;
import org.apache.synapse.transport.passthru.util.RelayUtils;
import org.wso2.securevault.SecretResolver;
import org.wso2.securevault.SecretResolverFactory;
import org.wso2.transport.http.netty.contract.config.InboundMsgSizeValidationConfig;
import org.wso2.transport.http.netty.contract.config.KeepAliveConfig;
import org.wso2.transport.http.netty.contract.config.ListenerConfiguration;
import org.wso2.transport.http.netty.contract.config.SslConfiguration;
import org.wso2.transport.http.netty.message.HttpCarbonMessage;
import org.wso2.transport.http.netty.message.HttpMessageDataStreamer;
import org.wso2.transport.http.netty.message.PooledDataStreamerFactory;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.xml.namespace.QName;

/**
 * {@code RequestResponseUtils} contains utilities used in request and response message flow.
 */
public class RequestResponseUtils {

    private static final Logger LOGGER = Logger.getLogger(RequestResponseUtils.class);

    /**
     * Create an Axis2 message context for the given HttpCarbonMessage. The carbon message may be in the
     * process of being streamed.
     *
     * @param incomingCarbonMsg   the http carbon message to be used to create the corresponding Axis2 message context
     * @param sourceConfiguration source configuration
     * @return the Axis2 message context created
     */
    public static MessageContext convertCarbonMsgToAxis2MsgCtx(HttpCarbonMessage incomingCarbonMsg,
                                                               SourceConfiguration sourceConfiguration) {

        MessageContext msgCtx = new MessageContext();

        //TODO: once the correlation id support is brought, the correlationID should be set as the messageID.
        // Refer to https://github.com/wso2-support/wso2-synapse/commit/2c86e14151d48ae3bb814be19b874800bd7468e5
        msgCtx.setMessageID(UIDGenerator.generateURNString());

        //TODO: set correlation id here
        msgCtx.setProperty(BaseConstants.INTERNAL_TRANSACTION_COUNTED, incomingCarbonMsg.getSourceContext().channel()
                .attr(AttributeKey.valueOf(BaseConstants.INTERNAL_TRANSACTION_COUNTED)).get());

        ConfigurationContext configurationContext = sourceConfiguration.getConfigurationContext();
        msgCtx.setConfigurationContext(configurationContext);

        String transportName;
        if (sourceConfiguration.getScheme().isSSL()) {
            transportName = BridgeConstants.PROTOCOL_HTTPS;
        } else {
            transportName = BridgeConstants.PROTOCOL_HTTP;
        }
        msgCtx.setTransportOut(configurationContext.getAxisConfiguration().getTransportOut(transportName));
        msgCtx.setTransportIn(configurationContext.getAxisConfiguration().getTransportIn(transportName));
        msgCtx.setIncomingTransportName(transportName);

        msgCtx.setServerSide(true);
        msgCtx.setProperty(Constants.Configuration.TRANSPORT_IN_URL,
                incomingCarbonMsg.getProperty(BridgeConstants.TO));
        msgCtx.setProperty(MessageContext.CLIENT_API_NON_BLOCKING, Boolean.FALSE);

        // Following section is required for throttling to work
        msgCtx.setProperty(MessageContext.REMOTE_ADDR, incomingCarbonMsg.getProperty(
                org.wso2.transport.http.netty.contract.Constants.REMOTE_ADDRESS).toString());
        msgCtx.setProperty(BridgeConstants.REMOTE_HOST,
                incomingCarbonMsg.getProperty(org.wso2.transport.http.netty.contract.Constants.ORIGIN_HOST));

        // http transport header names are case insensitive
        Map<String, String> headers = new TreeMap<>(String::compareToIgnoreCase);
        msgCtx.setProperty(MessageContext.TRANSPORT_HEADERS, headers);

        msgCtx.setProperty(RequestResponseTransport.TRANSPORT_CONTROL, new HttpCoreRequestResponseTransport(msgCtx));

        // Set the original incoming carbon message as a property
        msgCtx.setProperty(BridgeConstants.HTTP_CARBON_MESSAGE, incomingCarbonMsg);
        // This property is used when responding back to the client
        msgCtx.setProperty(BridgeConstants.HTTP_CLIENT_REQUEST_CARBON_MESSAGE, incomingCarbonMsg);
        return msgCtx;
    }

    /**
     * Set content type headers along with the character encoding if content type header is not preserved.
     *
     * @param msgContext           message context
     * @param sourceResponseDelete source response
     * @param formatter            response formatter
     * @param format               response format
     */
    public static void setContentType(MessageContext msgContext, SourceResponseDelete sourceResponseDelete,
                                      MessageFormatter formatter, OMOutputFormat format) {

        if (DataHolder.getInstance().isPreserveHttpHeader(HTTP.CONTENT_TYPE)) {
            return;
        }
        Object contentTypeInMsgCtx =
                msgContext.getProperty(org.apache.axis2.Constants.Configuration.CONTENT_TYPE);
        boolean isContentTypeSetFromMsgCtx = false;

        // If ContentType header is set in the axis2 message context, use it.
        if (contentTypeInMsgCtx != null) {
            String contentTypeValueInMsgCtx = contentTypeInMsgCtx.toString();
            // Skip multipart/related as it should be taken from formatter.
            if (!(contentTypeValueInMsgCtx.contains(
                    PassThroughConstants.CONTENT_TYPE_MULTIPART_RELATED) ||
                    contentTypeValueInMsgCtx.contains(PassThroughConstants.CONTENT_TYPE_MULTIPART_FORM_DATA))) {

                // adding charset only if charset is not available,
                if (format != null && contentTypeValueInMsgCtx.indexOf(HTTPConstants.CHAR_SET_ENCODING) == -1 &&
                        !"false".equals(msgContext.getProperty(PassThroughConstants.SET_CHARACTER_ENCODING))) {
                    String encoding = format.getCharSetEncoding();
                    if (encoding != null) {
                        contentTypeValueInMsgCtx += "; charset=" + encoding;
                    }
                }
                sourceResponseDelete.removeHeader(HTTP.CONTENT_TYPE);
                sourceResponseDelete.addHeader(HTTP.CONTENT_TYPE, contentTypeValueInMsgCtx);
                isContentTypeSetFromMsgCtx = true;
            }
        }

        // If ContentType is not set from msg context, get the formatter ContentType
        if (!isContentTypeSetFromMsgCtx) {
            sourceResponseDelete.removeHeader(HTTP.CONTENT_TYPE);
            sourceResponseDelete.addHeader(HTTP.CONTENT_TYPE,
                    formatter.getContentType(msgContext, format, msgContext.getSoapAction()));
        }
    }

    /**
     * Gets an HttpMessageDataStreamer instance that provides input and output stream by taking the HttpCarbonMessage.
     *
     * @param httpCarbonMessage HttpCarbonMessage from which we need a HttpMessageDataStreamer
     * @return HttpMessageDataStreamer instance
     */
    public static HttpMessageDataStreamer getHttpMessageDataStreamer(HttpCarbonMessage httpCarbonMessage) {

        final HttpMessageDataStreamer outboundMsgDataStreamer;
        final PooledDataStreamerFactory pooledDataStreamerFactory = (PooledDataStreamerFactory)
                httpCarbonMessage.getProperty(BridgeConstants.POOLED_BYTE_BUFFER_FACTORY);
        if (pooledDataStreamerFactory != null) {
            outboundMsgDataStreamer = pooledDataStreamerFactory.createHttpDataStreamer(httpCarbonMessage);
        } else {
            outboundMsgDataStreamer = new HttpMessageDataStreamer(httpCarbonMessage);
        }
        return outboundMsgDataStreamer;
    }

    /**
     * Remove unwanted headers from the http response of outgoing request. These are headers which
     * should be dictated by the transport and not the user. We remove these as these may get
     * copied from the request messages.
     *
     * @param msgContext the Axis2 Message context from which these headers should be removed
     */
    public static void removeUnwantedHeaders(MessageContext msgContext) {

        Map transportHeaders = (Map) msgContext.getProperty(MessageContext.TRANSPORT_HEADERS);
        Map excessHeaders = (Map) msgContext.getProperty(NhttpConstants.EXCESS_TRANSPORT_HEADERS);

        if (transportHeaders != null && !transportHeaders.isEmpty()) {
            //a hack which takes the original content header
            if (transportHeaders.get(HTTP.CONTENT_LEN) != null) {
                msgContext.setProperty(PassThroughConstants.ORGINAL_CONTEN_LENGTH,
                        transportHeaders.get(HTTP.CONTENT_LEN));
            }
            removeUnwantedHeadersFromHeaderMap(transportHeaders);
        }

        if (excessHeaders != null && !excessHeaders.isEmpty()) {
            removeUnwantedHeadersFromHeaderMap(excessHeaders);
        }
    }

    /**
     * Remove unwanted headers from the given header map.
     *
     * @param headers http header map
     */
    private static void removeUnwantedHeadersFromHeaderMap(Map headers) {

        Iterator iter = headers.keySet().iterator();
        while (iter.hasNext()) {
            String headerName = (String) iter.next();
            if (HTTP.TRANSFER_ENCODING.equalsIgnoreCase(headerName)) {
                iter.remove();
            }

            if (HTTP.CONN_DIRECTIVE.equalsIgnoreCase(headerName)
                    && !DataHolder.getInstance().isPreserveHttpHeader(HTTP.CONN_DIRECTIVE)) {
                iter.remove();
            }

            if (HTTP.CONN_KEEP_ALIVE.equalsIgnoreCase(headerName)
                    && !DataHolder.getInstance().isPreserveHttpHeader(HTTP.CONN_KEEP_ALIVE)) {
                iter.remove();
            }

            if (HTTP.CONTENT_LEN.equalsIgnoreCase(headerName)
                    && !DataHolder.getInstance().isPreserveHttpHeader(HTTP.CONTENT_LEN)) {
                iter.remove();
            }

            if (HTTP.DATE_HEADER.equalsIgnoreCase(headerName)
                    && !DataHolder.getInstance().isPreserveHttpHeader(HTTP.DATE_HEADER)) {
                iter.remove();
            }

            if (HTTP.SERVER_HEADER.equalsIgnoreCase(headerName)
                    && !DataHolder.getInstance().isPreserveHttpHeader(HTTP.SERVER_HEADER)) {
                iter.remove();
            }

            if (HTTP.USER_AGENT.equalsIgnoreCase(headerName)
                    && !DataHolder.getInstance().isPreserveHttpHeader(HTTP.USER_AGENT)) {
                iter.remove();
            }
        }
    }

    public static String getContentType(MessageContext msgCtx, boolean isContentTypePreservedHeader,
                                        Map trpHeaders) throws AxisFault {

        String setEncoding = (String) msgCtx.getProperty(PassThroughConstants.SET_CHARACTER_ENCODING);

        //If incoming transport isn't HTTP, transport headers can be null. Therefore null check is required
        // and if headers not null check whether request comes with Content-Type header before preserving Content-Type
        //Need to avoid this for multipart headers, need to add MIME Boundary property
        if (trpHeaders != null
                && (trpHeaders).get(HTTPConstants.HEADER_CONTENT_TYPE) != null
                && (isContentTypePreservedHeader || PassThroughConstants.VALUE_FALSE.equals(setEncoding))
                && !isMultipartContent((trpHeaders).get(HTTPConstants.HEADER_CONTENT_TYPE).toString())) {
            if (msgCtx.getProperty(Constants.Configuration.CONTENT_TYPE) != null) {
                return (String) msgCtx.getProperty(Constants.Configuration.CONTENT_TYPE);
            } else if (msgCtx.getProperty(Constants.Configuration.MESSAGE_TYPE) != null) {
                return (String) msgCtx.getProperty(Constants.Configuration.MESSAGE_TYPE);
            }
        }

        MessageFormatter formatter = MessageProcessorSelector.getMessageFormatter(msgCtx);
        OMOutputFormat format = PassThroughTransportUtils.getOMOutputFormat(msgCtx);

        if (formatter != null) {
            return formatter.getContentType(msgCtx, format, msgCtx.getSoapAction());

        } else {
            String contentType = (String) msgCtx.getProperty(Constants.Configuration.CONTENT_TYPE);
            if (contentType != null) {
                return contentType;
            } else {
                return new SOAPMessageFormatter().getContentType(
                        msgCtx, format, msgCtx.getSoapAction());
            }
        }
    }

    /**
     * Check whether the content type is multipart or not.
     *
     * @param contentType value of the content type
     * @return true for multipart content types
     */
    public static boolean isMultipartContent(String contentType) {

        return contentType.contains(HTTPConstants.MEDIA_TYPE_MULTIPART_FORM_DATA)
                || contentType.contains(HTTPConstants.HEADER_ACCEPT_MULTIPART_RELATED);
    }

    /**
     * Checks if we need to the ignore the message body.
     *
     * @param msgContext axis2 message context
     * @return whether we can ignore the message body
     */
    public static boolean ignoreMessageBody(MessageContext msgContext) {

        return HTTPConstants.HTTP_METHOD_GET.equals(msgContext.getProperty(Constants.Configuration.HTTP_METHOD))
                || RelayUtils.isDeleteRequestWithoutPayload(msgContext);
    }

    /**
     * Generate the REST_URL_POSTFIX from the request URI.
     *
     * @param uri         the Request URI as a string
     * @param servicePath service path
     * @return REST_URL_POSTFIX as a string
     */
    public static String getRestUrlPostfix(String uri, String servicePath) {

        String contextServicePath = "/" + servicePath;
        if (uri.startsWith(contextServicePath)) {
            // discard upto servicePath
            uri = uri.substring(uri.indexOf(contextServicePath) +
                    contextServicePath.length());
            // discard [proxy] service name if any
            int pos = uri.indexOf("/", 1);
            if (pos > 0) {
                uri = uri.substring(pos);
            } else {
                pos = uri.indexOf("?");
                if (pos != -1) {
                    uri = uri.substring(pos);
                } else {
                    uri = "";
                }
            }
        } else {
            // remove any absolute prefix if any
            int pos = uri.indexOf("://");
            //compute index of beginning of Query Parameter
            int indexOfQueryStart = uri.indexOf("?");

            //Check if there exist a absolute prefix '://' and it is before query parameters
            //To allow query parameters with URLs. ex: /test?a=http://asddd
            if (pos != -1 && ((indexOfQueryStart == -1 || pos < indexOfQueryStart))) {
                uri = uri.substring(pos + 3);
            }
            pos = uri.indexOf("/");
            if (pos != -1) {
                uri = uri.substring(pos + 1);
            }
            // Remove the service prefix
            if (uri.startsWith(servicePath)) {
                // discard upto servicePath
                uri = uri.substring(uri.indexOf(contextServicePath)
                        + contextServicePath.length());
                // discard [proxy] service name if any
                pos = uri.indexOf("/", 1);
                if (pos > 0) {
                    uri = uri.substring(pos);
                } else {
                    pos = uri.indexOf("?");
                    if (pos != -1) {
                        uri = uri.substring(pos);
                    } else {
                        uri = "";
                    }
                }
            }
        }

        return uri;
    }

    /**
     * Checks if the HTTP request is REST based on the given Content-Type header.
     *
     * @param contentType Content-Type header
     * @return whether the HTTP request is REST or not
     */
    public static boolean isRESTRequest(String contentType) {

        return contentType != null && (contentType.contains(HTTPConstants.MEDIA_TYPE_APPLICATION_XML)
                || contentType.contains(HTTPConstants.MEDIA_TYPE_X_WWW_FORM)
                || contentType.contains(HTTPConstants.MEDIA_TYPE_MULTIPART_FORM_DATA)
                || contentType.contains(HTTPConstants.MEDIA_TYPE_APPLICATION_JSON)
                || contentType.contains(HTTPConstants.MEDIA_TYPE_APPLICATION_JWT)
                || (!contentType.contains(SOAP11Constants.SOAP_11_CONTENT_TYPE)
                && !contentType.contains(SOAP12Constants.SOAP_12_CONTENT_TYPE)));
    }

    /**
     * Populates the SOAP version based on the given Content-Type.
     *
     * @param msgContext axis2 message context
     * @param contentType Content type of the request.
     * @return 0 if the content type is null. return 2 if the content type is application/soap+xml. Otherwise 1
     */
    public static int populateSOAPVersion(MessageContext msgContext, String contentType) {

        int soapVersion = 0;
        if (contentType != null) {
            if (contentType.contains(SOAP12Constants.SOAP_12_CONTENT_TYPE)) {
                soapVersion = 2;
                TransportUtils.processContentTypeForAction(contentType, msgContext);
            } else if (contentType.contains(SOAP11Constants.SOAP_11_CONTENT_TYPE)) {
                soapVersion = 1;
            } else if (isRESTRequest(contentType)) {
                soapVersion = 1;
            }
        }
        return soapVersion;
    }

    /**
     * Checks if the request should be considered as REST.
     *
     * @param msgContext axis2 message context
     * @param contentType content-type of the request
     * @param soapVersion SOAP version
     * @param soapActionHeader SOAPAction header
     * @return whether the request should be considered as REST
     */
    public static boolean isDoingREST(MessageContext msgContext, String contentType, int soapVersion,
                                      String soapActionHeader) {

        if (isRESTRequest(contentType)) {
            return true;
        }
        if (soapVersion == 1) {
            Parameter disableREST = msgContext.getParameter(Java2WSDLConstants.DISABLE_BINDING_REST);
            return soapActionHeader == null && disableREST != null && "false".equals(disableREST.getValue());
        }
        return false;
    }

    /**
     * Get the EPR for the message passed in.
     *
     * @param msgContext the message context
     * @return the destination EPR
     */
    public static EndpointReference getDestinationEPR(MessageContext msgContext) {

        // Transport URL can be different from the WSA-To
        String transportURL = (String) msgContext.getProperty(
                Constants.Configuration.TRANSPORT_URL);

        if (transportURL != null) {
            return new EndpointReference(transportURL);
        } else if (
                (msgContext.getTo() != null) && !msgContext.getTo().hasAnonymousAddress()) {
            return msgContext.getTo();
        }
        return null;
    }

    private URL getDestinationURL(MessageContext msgContext) throws AxisFault {

        EndpointReference endpointReference = getDestinationEPR(msgContext);
        if (Objects.isNull(endpointReference)) {
            return null;
        }
        try {
            return new URL(endpointReference.getAddress());
        } catch (MalformedURLException e) {
            throw new AxisFault("Malformed Endpoint url found in the target EPR", e);
        }
    }

    public static void addTransportHeaders(MessageContext msgCtx, HttpCarbonMessage outboundRequest) {

        Map headers = (Map) msgCtx.getProperty(MessageContext.TRANSPORT_HEADERS);
        if (headers != null) {
            for (Object entryObj : headers.entrySet()) {
                Map.Entry entry = (Map.Entry) entryObj;
                if (entry.getValue() != null && entry.getKey() instanceof String &&
                        entry.getValue() instanceof String) {
                    outboundRequest.setHeader((String) entry.getKey(), (String) entry.getValue());
                }
            }
        }
    }

    public static void addExcessHeaders(MessageContext msgCtx, HttpCarbonMessage outboundHttpCarbonMessage) {

        Map excessHeaders = (Map) msgCtx.getProperty(NhttpConstants.EXCESS_TRANSPORT_HEADERS);
        if (excessHeaders != null) {
            for (Iterator iterator = excessHeaders.keySet().iterator(); iterator.hasNext(); ) {
                String key = (String) iterator.next();
                for (String excessVal : (Collection<String>) excessHeaders.get(key)) {
                    outboundHttpCarbonMessage.setHeader(key, excessVal);
                }
            }
        }
    }

    public static boolean enableChunking(MessageContext msgContext) {

        if (msgContext.isPropertyTrue(NhttpConstants.FORCE_HTTP_CONTENT_LENGTH)) {
            return false;
        } else {
            String disableChunking = (String) msgContext.getProperty(PassThroughConstants.DISABLE_CHUNKING);
            return !Constants.VALUE_TRUE.equals(disableChunking)
                    && !Constants.VALUE_TRUE.equals(msgContext.getProperty(PassThroughConstants.FORCE_HTTP_1_0));
        }
    }

    public static KeepAliveConfig getKeepAliveConfig(boolean keepAlive) throws AxisFault {

        if (keepAlive) {
            return getKeepAliveConfig(BridgeConstants.ALWAYS);
        } else {
            return getKeepAliveConfig(BridgeConstants.NEVER);
        }
    }

    public static KeepAliveConfig getKeepAliveConfig(String keepAliveConfig) throws AxisFault {

        switch (keepAliveConfig) {
            case BridgeConstants.AUTO:
                return KeepAliveConfig.AUTO;
            case BridgeConstants.ALWAYS:
                return KeepAliveConfig.ALWAYS;
            case BridgeConstants.NEVER:
                return KeepAliveConfig.NEVER;
            default:
                throw new AxisFault(
                        "Invalid configuration found for Keep-Alive: " + keepAliveConfig);
        }
    }

    /**
     * Returns Listener configuration instance populated with source configuration.
     *
     * @param sourceConfiguration source configuration
     * @return transport listener configuration instance
     */
    public static ListenerConfiguration getListenerConfig(SourceConfiguration sourceConfiguration, boolean sslEnabled)
            throws AxisFault {

        ListenerConfiguration listenerConfiguration = new ListenerConfiguration();

        listenerConfiguration.setPort(sourceConfiguration.getPort());
        listenerConfiguration.setHost(sourceConfiguration.getHost());
        listenerConfiguration.setVersion(sourceConfiguration.getProtocol());

        NettyConfiguration globalConfig = NettyConfiguration.getInstance();

        // Set Request validation limits.
        boolean isRequestLimitsValidationEnabled = globalConfig.isRequestLimitsValidationEnabled();
        if (isRequestLimitsValidationEnabled) {
            setInboundMgsSizeValidationConfig(globalConfig.getMaxStatusLineLength(), globalConfig.getMaxHeaderSize(),
                    globalConfig.getMaxEntityBodySize(), listenerConfiguration.getMsgSizeValidationConfig());
        }

        int idleTimeout = globalConfig.getSocketTimeout();
        if (idleTimeout < 0) {
            throw new AxisFault("Idle timeout cannot be negative. If you want to disable the " +
                    "timeout please use value 0");
        }
        listenerConfiguration.setSocketIdleTimeout(idleTimeout);

        listenerConfiguration.setPipeliningEnabled(false); //Pipelining is disabled all the time

        if (isHTTPTraceLoggerEnabled()) {
            listenerConfiguration.setHttpTraceLogEnabled(true);
        }

        if (isHTTPAccessLoggerEnabled()) {
            listenerConfiguration.setHttpAccessLogEnabled(true);
        }

        if (sslEnabled) {
            return setSslConfig(sourceConfiguration.getInDescription(), listenerConfiguration, sourceConfiguration);
        }

        return listenerConfiguration;
    }

    public static boolean isHTTPTraceLoggerEnabled() {

        return Boolean.parseBoolean(System.getProperty(BridgeConstants.HTTP_TRACE_LOG_ENABLED));
    }

    public static boolean isHTTPAccessLoggerEnabled() {

        return Boolean.parseBoolean(System.getProperty(BridgeConstants.HTTP_ACCESS_LOG_ENABLED));
    }

    public static ListenerConfiguration setSslConfig(TransportInDescription transportIn,
                                                     ListenerConfiguration listenerConfiguration,
                                                     SourceConfiguration sourceConfiguration) throws AxisFault {

        listenerConfiguration.setScheme(BridgeConstants.PROTOCOL_HTTPS);

        Parameter keyParam = transportIn.getParameter(BridgeConstants.KEY_STORE);
        Parameter trustParam = transportIn.getParameter(BridgeConstants.TRUST_STORE);
        Parameter clientAuthParam = transportIn.getParameter(BridgeConstants.SSL_VERIFY_CLIENT);
        Parameter httpsProtocolsParam = transportIn.getParameter(BridgeConstants.HTTPS_PROTOCOL);
        Parameter sslParameter = transportIn.getParameter(BridgeConstants.SSL_PROTOCOL);
        Parameter preferredCiphersParam = transportIn.getParameter(BridgeConstants.PREFERRED_CIPHERS);
        Parameter cvpParam = transportIn.getParameter(BridgeConstants.CLIENT_REVOCATION);
        Parameter sessionTimeoutParam = transportIn.getParameter(BridgeConstants.SSL_SESSION_TIMEOUT);
        Parameter handshakeTimeoutParam = transportIn.getParameter(BridgeConstants.SSL_HANDSHAKE_TIMEOUT);
        // TODO: evaluate shareSession param. Bhashinee

        OMElement keyStoreEl = keyParam != null ? keyParam.getParameterElement().getFirstElement() : null;
        OMElement trustStoreEl = trustParam != null ? trustParam.getParameterElement().getFirstElement() : null;
        OMElement clientAuthEl = clientAuthParam != null ? clientAuthParam.getParameterElement() : null;
        OMElement httpsProtocolsEl = httpsProtocolsParam != null ? httpsProtocolsParam.getParameterElement() : null;
        String sslProtocol = sslParameter != null ? sslParameter.getValue().toString() : "TLS";
        OMElement preferredCiphersEl = preferredCiphersParam != null
                ? preferredCiphersParam.getParameterElement() : null;
        OMElement cvpEl = cvpParam != null ? cvpParam.getParameterElement() : null;
        String sessionTimeoutEl = sessionTimeoutParam != null ? sessionTimeoutParam.getValue().toString() : null;
        String handshakeTimeoutEl = handshakeTimeoutParam != null
                ? handshakeTimeoutParam.getParameterElement().toString() : null;

        SecretResolver secretResolver;
        ConfigurationContext configurationContext = sourceConfiguration.getConfigurationContext();
        if (configurationContext != null && configurationContext.getAxisConfiguration() != null) {
            secretResolver = configurationContext.getAxisConfiguration().getSecretResolver();
        } else {
            secretResolver = SecretResolverFactory.create(keyStoreEl, false);
        }

        List<org.wso2.transport.http.netty.contract.config.Parameter> serverParamList = new ArrayList<>();

        evaluateKeyStoreField(keyStoreEl, listenerConfiguration, secretResolver);

        evaluateTrustStoreField(trustStoreEl, listenerConfiguration, secretResolver);

        final String s = clientAuthEl != null ? clientAuthEl.getText() : "";
        listenerConfiguration.setVerifyClient(s);

        evaluateProtocolField(httpsProtocolsEl, sslProtocol, listenerConfiguration, serverParamList);

        evaluateCertValidationField(cvpEl, listenerConfiguration);

        evaluateCiphersField(preferredCiphersEl, serverParamList);

        evaluateCommonFields(sessionTimeoutEl, handshakeTimeoutEl, listenerConfiguration);

        if (!serverParamList.isEmpty()) {
            listenerConfiguration.setParameters(serverParamList);
        }

        listenerConfiguration.setId(getListenerInterface(listenerConfiguration.getHost(),
                listenerConfiguration.getPort()));
        return listenerConfiguration;
    }

    private static void evaluateKeyStoreField(OMElement keyStoreEl, SslConfiguration sslConfiguration,
                                              SecretResolver secretResolver) throws AxisFault {

        if (keyStoreEl != null) {
            String location = getValueOfElementWithLocalName(keyStoreEl, BridgeConstants.STORE_LOCATION);
            String type = getValueOfElementWithLocalName(keyStoreEl, BridgeConstants.TYPE);
            OMElement storePasswordEl = keyStoreEl.getFirstChildWithName(new QName(BridgeConstants.PASSWORD));
            OMElement keyPasswordEl = keyStoreEl.getFirstChildWithName(new QName(BridgeConstants.KEY_PASSWORD));

            if (Objects.isNull(location) || location.isEmpty()) {
                throw new AxisFault("KeyStore file location must be provided for secure connection");
            }

            if (storePasswordEl == null) {
                throw new AxisFault("KeyStore password must be provided for secure connection");
            }
            if (keyPasswordEl == null) {
                throw new AxisFault("Cannot proceed because KeyPassword element is missing in KeyStore");
            }
            String storePassword = SecureVaultValueReader.getSecureVaultValue(secretResolver, storePasswordEl);
            String keyPassword = SecureVaultValueReader.getSecureVaultValue(secretResolver, keyPasswordEl);
            // TODO: verify this

            sslConfiguration.setKeyStoreFile(location);
            sslConfiguration.setKeyStorePass(storePassword);
            sslConfiguration.setTLSStoreType(type);
        }
    }

    private static void evaluateTrustStoreField(OMElement trustStoreEl, SslConfiguration sslConfiguration,
                                                SecretResolver secretResolver) throws AxisFault {

        String location = getValueOfElementWithLocalName(trustStoreEl, BridgeConstants.STORE_LOCATION);
        // TODO: verify this
        String type = getValueOfElementWithLocalName(trustStoreEl, BridgeConstants.TYPE);
        OMElement storePasswordEl = trustStoreEl.getFirstChildWithName(new QName(BridgeConstants.PASSWORD));
        if (storePasswordEl == null) {
            throw new AxisFault("Cannot proceed because Password element is missing in TrustStore");
        }
        String storePassword = SecureVaultValueReader.getSecureVaultValue(secretResolver, storePasswordEl);

        sslConfiguration.setTrustStoreFile(location);
        sslConfiguration.setTrustStorePass(storePassword);
    }

    /**
     * Sets the configuration for the inbound request and response size validation.
     *
     * @param maxInitialLineLength The maximum length of the initial line (e.g. {@code "GET / HTTP/1.0"}
     *                             or {@code "HTTP/1.0 200 OK"})
     * @param maxHeaderSize        The maximum length of all headers.
     * @param maxEntityBodySize    The maximum length of the content or each chunk.
     * @param sizeValidationConfig instance that represents the configuration for the inbound request and
     *                             response size validation.
     * @throws AxisFault when the given values are invalid.
     */
    public static void setInboundMgsSizeValidationConfig(int maxInitialLineLength, int maxHeaderSize,
                                                         int maxEntityBodySize,
                                                         InboundMsgSizeValidationConfig sizeValidationConfig)
            throws AxisFault {

        if (maxInitialLineLength >= 0) {
            sizeValidationConfig.setMaxInitialLineLength(Math.toIntExact(maxInitialLineLength));
        } else {
            // TODO: disable this
            throw new AxisFault(
                    "Invalid configuration found for max initial line length : " + maxInitialLineLength);
        }

        if (maxHeaderSize >= 0) {
            sizeValidationConfig.setMaxHeaderSize(Math.toIntExact(maxHeaderSize));
        } else {
            // TODO: disable this
            throw new AxisFault("Invalid configuration found for maxHeaderSize : " + maxHeaderSize);
        }

        if (maxEntityBodySize != -1) {
            if (maxEntityBodySize >= 0) {
                sizeValidationConfig.setMaxEntityBodySize(maxEntityBodySize);
            } else {
                throw new AxisFault(
                        "Invalid configuration found for maxEntityBodySize : " + maxEntityBodySize);
            }
        }
    }

    public static String getValueOfElementWithLocalName(OMElement element, String localName) {

        Iterator iterator = element.getChildrenWithLocalName(localName);
        String value = null;
        Object obj = iterator.next();
        if (obj instanceof OMElement) {
            value = ((OMElement) obj).getText();
        }
        return value;
    }

    public static void evaluateCertValidationField(OMElement cvpEl, SslConfiguration sslConfiguration) {

        final String cvEnable = cvpEl != null ?
                cvpEl.getAttribute(new QName("enable")).getAttributeValue() : null;

        if ("true".equalsIgnoreCase(cvEnable)) {
            sslConfiguration.setValidateCertEnabled(true);
            String cacheSizeString = cvpEl.getFirstChildWithName(new QName("CacheSize")).getText();
            String cacheDelayString = cvpEl.getFirstChildWithName(new QName("CacheDelay")).getText();
            Integer cacheSize = null;
            Integer cacheDelay = null;
            try {
                cacheSize = new Integer(cacheSizeString);
                cacheDelay = new Integer(cacheDelayString);
            } catch (NumberFormatException e) {
                //
            }

            if (Objects.nonNull(cacheDelay) && cacheDelay != 0) {
                sslConfiguration.setCacheValidityPeriod(Math.toIntExact(cacheDelay));
            }
            if (Objects.nonNull(cacheSize) && cacheSize != 0) {
                sslConfiguration.setCacheSize(Math.toIntExact(cacheSize));
            }
        }
    }

    public static void evaluateCommonFields(String sessionTimeout, String handshakeTimeout,
                                            SslConfiguration sslConfiguration) throws AxisFault {

        if (Objects.nonNull(sessionTimeout) && !sessionTimeout.isEmpty()) {
            try {
                sslConfiguration.setSslSessionTimeOut(new Integer(sessionTimeout));
            } catch (NumberFormatException e) {
                throw new AxisFault("Invalid number found for ssl sessionTimeout : " + sessionTimeout);
            }
        } else {
            // default value
        }

        if (Objects.nonNull(handshakeTimeout) && !handshakeTimeout.isEmpty()) {
            try {
                sslConfiguration.setSslHandshakeTimeOut(new Integer(handshakeTimeout));
            } catch (NumberFormatException e) {
                throw new AxisFault("Invalid number found for ssl handshakeTimeout : " + handshakeTimeout);
            }
        } else {
            // default value
        }
    }

    private static void evaluateProtocolField(OMElement httpsProtocolsEl, String sslProtocol,
                                              SslConfiguration sslConfiguration,
                                              List<org.wso2.transport.http.netty.contract.config.Parameter> paramList) {

        if (httpsProtocolsEl != null) {
            String configuredHttpsProtocols = httpsProtocolsEl.getText().replaceAll("\\s", "");

            if (!configuredHttpsProtocols.isEmpty()) {
                org.wso2.transport.http.netty.contract.config.Parameter serverProtocols
                        = new org.wso2.transport.http.netty.contract.config.Parameter("sslEnabledProtocols",
                        configuredHttpsProtocols);
                paramList.add(serverProtocols);
            }
        }

        if (Objects.isNull(sslProtocol) || sslProtocol.isEmpty()) {
            sslProtocol = "TLS";
        }
        sslConfiguration.setSSLProtocol(sslProtocol);
    }

    private static void evaluateCiphersField(OMElement preferredCiphersEl,
                                             List<org.wso2.transport.http.netty.contract.config.Parameter> paramList) {

        if (preferredCiphersEl != null) {
            String preferredCiphers = preferredCiphersEl.getText().replaceAll("\\s", "");

            if (!preferredCiphers.isEmpty()) {
                org.wso2.transport.http.netty.contract.config.Parameter serverParameters
                        = new org.wso2.transport.http.netty.contract.config.Parameter("ciphers", preferredCiphers);
                paramList.add(serverParameters);
            }
        }
    }

    public static String getListenerInterface(String host, int port) {

        host = host != null ? host : "0.0.0.0";
        return host + ":" + port;
    }
}
