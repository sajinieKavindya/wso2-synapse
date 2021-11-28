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

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.AttributeKey;
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
import org.apache.axis2.transport.MessageFormatter;
import org.apache.axis2.transport.RequestResponseTransport;
import org.apache.axis2.transport.TransportListener;
import org.apache.axis2.transport.TransportUtils;
import org.apache.axis2.transport.base.BaseConstants;
import org.apache.axis2.transport.base.ParamUtils;
import org.apache.axis2.transport.http.HTTPConstants;
import org.apache.axis2.transport.http.SOAPMessageFormatter;
import org.apache.axis2.util.MessageProcessorSelector;
import org.apache.commons.collections.map.MultiValueMap;
import org.apache.http.HttpStatus;
import org.apache.http.protocol.HTTP;
import org.apache.log4j.Logger;
import org.apache.synapse.transport.netty.BridgeConstants;
import org.apache.synapse.transport.netty.config.NettyConfiguration;
import org.apache.synapse.transport.netty.config.SourceConfiguration;
import org.apache.synapse.transport.netty.sender.SourceResponseDelete;
import org.apache.synapse.transport.nhttp.HttpCoreRequestResponseTransport;
import org.apache.synapse.transport.nhttp.NhttpConstants;
import org.apache.synapse.transport.nhttp.util.MessageFormatterDecoratorFactory;
import org.apache.synapse.transport.nhttp.util.NhttpUtil;
import org.apache.synapse.transport.passthru.HttpGetRequestProcessor;
import org.apache.synapse.transport.passthru.PassThroughConstants;
import org.apache.synapse.transport.passthru.Pipe;
import org.apache.synapse.transport.passthru.config.PassThroughConfiguration;
import org.apache.synapse.transport.passthru.util.PassThroughTransportUtils;
import org.apache.synapse.transport.passthru.util.RelayUtils;
import org.wso2.transport.http.netty.contract.config.InboundMsgSizeValidationConfig;
import org.wso2.transport.http.netty.contract.config.KeepAliveConfig;
import org.wso2.transport.http.netty.contract.config.ListenerConfiguration;
import org.wso2.transport.http.netty.message.HttpCarbonMessage;
import org.wso2.transport.http.netty.message.HttpMessageDataStreamer;
import org.wso2.transport.http.netty.message.PooledDataStreamerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * {@code RequestUtils} contains utilities used in request and response message flow.
 */
public class RequestResponseUtils {

    private static final Logger LOGGER = Logger.getLogger(RequestResponseUtils.class);

    /**
     * Create an Axis2 message context for the given HttpCarbonMessage. The carbon message may be in the
     * process of being streamed.
     *
     * @param incomingCarbonMsg the http carbon message to be used to create the corresponding Axis2 message context
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

        if (sourceConfiguration.getScheme().isSSL()) {
            msgCtx.setTransportOut(configurationContext.getAxisConfiguration()
                    .getTransportOut(Constants.TRANSPORT_HTTPS));
            msgCtx.setTransportIn(configurationContext.getAxisConfiguration()
                    .getTransportIn(Constants.TRANSPORT_HTTPS));
            msgCtx.setIncomingTransportName(Constants.TRANSPORT_HTTPS);
        } else {
            msgCtx.setTransportOut(configurationContext.getAxisConfiguration()
                    .getTransportOut(Constants.TRANSPORT_HTTP));
            msgCtx.setTransportIn(configurationContext.getAxisConfiguration()
                    .getTransportIn(Constants.TRANSPORT_HTTP));
            msgCtx.setIncomingTransportName(Constants.TRANSPORT_HTTP);
        }

        msgCtx.setServerSide(true);
        msgCtx.setProperty(Constants.Configuration.TRANSPORT_IN_URL, incomingCarbonMsg.getProperty("TO"));
        msgCtx.setProperty(MessageContext.CLIENT_API_NON_BLOCKING, Boolean.FALSE);

        // Following section is required for throttling to work
        msgCtx.setProperty(MessageContext.REMOTE_ADDR, incomingCarbonMsg.getProperty(
                org.wso2.transport.http.netty.contract.Constants.REMOTE_ADDRESS).toString());
        msgCtx.setProperty(BridgeConstants.REMOTE_HOST,
                incomingCarbonMsg.getProperty(org.wso2.transport.http.netty.contract.Constants.ORIGIN_HOST));

        // http transport header names are case insensitive
        Map<String, String> headers = new TreeMap<>(String::compareToIgnoreCase);
        Map excessHeaders = new MultiValueMap();
        incomingCarbonMsg.getHeaders().forEach(entry -> {
            if (headers.containsKey(entry.getKey())) {
                excessHeaders.put(entry.getKey(), entry.getValue());
            } else {
                headers.put(entry.getKey(), entry.getValue());
            }
        });
        msgCtx.setProperty(MessageContext.TRANSPORT_HEADERS, headers);
        msgCtx.setProperty(NhttpConstants.EXCESS_TRANSPORT_HEADERS, excessHeaders);

        msgCtx.setProperty(RequestResponseTransport.TRANSPORT_CONTROL, new HttpCoreRequestResponseTransport(msgCtx));

        // Set the original incoming carbon message as a property
        msgCtx.setProperty(BridgeConstants.HTTP_CARBON_MESSAGE, incomingCarbonMsg);
        // This property is used when responding back to the client
        msgCtx.setProperty(BridgeConstants.HTTP_CLIENT_REQUEST_CARBON_MESSAGE, incomingCarbonMsg);
        return msgCtx;
    }

    /**
     * Set content type headers along with the charactor encoding if content type header is not preserved.
     *
     * @param msgContext     message context
     * @param sourceResponseDelete source response
     * @param formatter      response formatter
     * @param format         response format
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

    public static HttpMessageDataStreamer getHttpMessageDataStreamer(HttpCarbonMessage outboundRequestMsg) {

        final HttpMessageDataStreamer outboundMsgDataStreamer;
        final PooledDataStreamerFactory pooledDataStreamerFactory = (PooledDataStreamerFactory)
                outboundRequestMsg.getProperty(BridgeConstants.POOLED_BYTE_BUFFER_FACTORY);
        if (pooledDataStreamerFactory != null) {
            outboundMsgDataStreamer = pooledDataStreamerFactory.createHttpDataStreamer(outboundRequestMsg);
        } else {
            outboundMsgDataStreamer = new HttpMessageDataStreamer(outboundRequestMsg);
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

    // If the HTTP method is GET or DELETE with no body, we need to write down the HEADER information to the wire
    // and need to ignore any entity enclosed methods available.
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

    public static boolean isRESTRequest(String contentType) {

        return contentType != null && (contentType.contains("application/xml")
                || contentType.contains("application/x-www-form-urlencoded")
                || contentType.contains("multipart/form-data")
                || contentType.contains("application/json")
                || contentType.contains("application/jwt")
                || (!contentType.contains(SOAP11Constants.SOAP_11_CONTENT_TYPE)
                && !contentType.contains(SOAP12Constants.SOAP_12_CONTENT_TYPE)));
    }

//    public static boolean isRest(String contentType) {
//
//        return contentType != null &&
//                !contentType.contains(SOAP11Constants.SOAP_11_CONTENT_TYPE) &&
//                !contentType.contains(SOAP12Constants.SOAP_12_CONTENT_TYPE);
//    }

    public static int populateSOAPVersion(MessageContext msgContext, String soapActionHeader, String contentType) {

        int soapVersion = 0;
        if (contentType != null) {
            if (contentType.contains("application/soap+xml")) {
                soapVersion = 2;
                TransportUtils.processContentTypeForAction(contentType, msgContext);
            } else if (contentType.contains("text/xml")) {
                soapVersion = 1;
            } else if (isRESTRequest(contentType)) {
                soapVersion = 1;
            }
        }
        return soapVersion;
    }

    public static boolean isDoingREST(MessageContext msgContext, String contentType, int soapVersion,
                                      String soapActionHeader) {
        if (isRESTRequest(contentType)) {
            return true;
        }
        if (soapVersion == 1) {
            Parameter disableREST = msgContext.getParameter("disableREST");
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

    public static void handleException(String msg, Exception e) {

        LOGGER.error(msg, e);
//        throw new AxisFault(msg, e);
    }

    public static void handleException(String msg) {

        LOGGER.error(msg);
//        throw new AxisFault(msg);
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
            for (Iterator iterator = excessHeaders.keySet().iterator(); iterator.hasNext();) {
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
     * Returns Listener configuration instance populated with transport in description.
     *
     * @param inDescription    transport in description.
     * @return                 transport listener configuration instance.
     */
    public static ListenerConfiguration getListenerConfig(TransportInDescription inDescription, boolean sslEnabled)
            throws AxisFault {

        ListenerConfiguration listenerConfiguration = new ListenerConfiguration();

        int port = ParamUtils.getRequiredParamInt(inDescription, TransportListener.PARAM_PORT);
        if (port == 0) {
            throw new AxisFault("Listener port is not defined!");
        }
        listenerConfiguration.setPort(port);

        String host;
        Parameter hostParameter = inDescription.getParameter(TransportListener.HOST_ADDRESS);
        if (hostParameter != null) {
            host = ((String) hostParameter.getValue()).trim();
        } else {
            try {
                host = java.net.InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException e) {
                LOGGER.warn("Unable to lookup local host name. Hence, using 'localhost'");
                host = BridgeConstants.HTTP_DEFAULT_HOST;
            }
        }
        listenerConfiguration.setHost(host);

        String httpVersion = "1.1";
        Parameter httpVersionParameter = inDescription.getParameter("PROTOCOL");
        if (httpVersionParameter != null) {
            httpVersion = ((String) httpVersionParameter.getValue()).trim();
            if (!httpVersion.isEmpty()) {
                listenerConfiguration.setVersion(httpVersion);
            }

//            listenerConfiguration.setPipeliningLimit(http1Settings
//            .getIntValue(HttpConstants.PIPELINING_REQUEST_LIMIT));
        }

        Parameter keepAliveParam = inDescription.getParameter("keepAlive");
        if (BridgeConstants.HTTP_1_1_VERSION.equals(httpVersion) && keepAliveParam != null) {
            listenerConfiguration.setKeepAliveConfig(RequestResponseUtils
                    .getKeepAliveConfig(((String) keepAliveParam.getValue()).trim()));
        }

        // Set Request validation limits.
        NettyConfiguration configuration = NettyConfiguration.getInstance();
        boolean isRequestLimitsValidationEnabled = configuration.isRequestLimitsValidationEnabled();
        if (isRequestLimitsValidationEnabled) {
            setInboundMgsSizeValidationConfig(configuration.getMaxStatusLineLength(), configuration.getMaxHeaderSize(),
                    configuration.getMaxEntityBodySize(), listenerConfiguration.getMsgSizeValidationConfig());
        }

        int idleTimeout = NettyConfiguration.getInstance().getSocketTimeout();
        if (idleTimeout < 0) {
            throw new AxisFault("Idle timeout cannot be negative. If you want to disable the " +
                    "timeout please use value 0");
        }
        listenerConfiguration.setSocketIdleTimeout(idleTimeout);

//        if (endpointConfig.getType().getName().equalsIgnoreCase(HttpConstants.LISTENER_CONFIGURATION)) {
//            BString serverName = endpointConfig.getStringValue(HttpConstants.SERVER_NAME);
//            listenerConfiguration.setServerHeader(serverName != null ? serverName.getValue() : getServerName());
//        } else {
//            listenerConfiguration.setServerHeader(getServerName());
//        }

//        BMap<BString, Object> sslConfig = endpointConfig.getMapValue(HttpConstants.ENDPOINT_CONFIG_SECURESOCKET);
//        if (sslConfig != null) {
//            return setSslConfig(sslConfig, listenerConfiguration);
//        }

        listenerConfiguration.setPipeliningEnabled(true); //Pipelining is enabled all the time

        if (isHTTPTraceLoggerEnabled()) {
            listenerConfiguration.setHttpTraceLogEnabled(true);
        }

        if (isHTTPAccessLoggerEnabled()) {
            listenerConfiguration.setHttpAccessLogEnabled(true);
        }

        return listenerConfiguration;
    }

    public static boolean isHTTPTraceLoggerEnabled() {
        return Boolean.parseBoolean(System.getProperty(BridgeConstants.HTTP_TRACE_LOG_ENABLED));
    }

    private static boolean isHTTPAccessLoggerEnabled() {
        return Boolean.parseBoolean(System.getProperty(BridgeConstants.HTTP_ACCESS_LOG_ENABLED));
    }

    private static ListenerConfiguration setSslConfig(TransportInDescription transportIn,
                                                      ListenerConfiguration listenerConfiguration) {
//        List<Parameter> serverParamList = new ArrayList<>();
//        listenerConfiguration.setScheme(BridgeConstants.PROTOCOL_HTTPS);
//
//        Parameter keyParam = transportIn.getParameter("keystore");
//        Parameter trustParam = transportIn.getParameter("truststore");
//        Parameter clientAuthParam = transportIn.getParameter("SSLVerifyClient");
//        Parameter httpsProtocolsParam = transportIn.getParameter("HttpsProtocols");
//        final Parameter sslpParameter = transportIn.getParameter("SSLProtocol");
//        Parameter preferredCiphersParam = transportIn.getParameter(NhttpConstants.PREFERRED_CIPHERS);
//        final String sslProtocol = sslpParameter != null ? sslpParameter.getValue().toString() : "TLS";
//        OMElement keyStoreEl = keyParam != null ? keyParam.getParameterElement().getFirstElement() : null;
//        OMElement trustStoreEl = trustParam != null ? trustParam.getParameterElement().getFirstElement() : null;
//        OMElement clientAuthEl = clientAuthParam != null ? clientAuthParam.getParameterElement() : null;
//        OMElement httpsProtocolsEl = httpsProtocolsParam != null ? httpsProtocolsParam.getParameterElement() : null;
//        OMElement preferredCiphersEl = preferredCiphersParam != null ?
//                preferredCiphersParam.getParameterElement() : null;
//        final Parameter cvp = transportIn.getParameter("CertificateRevocationVerifier");
//
//
//
//
//        BMap<BString, Object> key = getBMapValueIfPresent(secureSocket, HttpConstants.SECURESOCKET_CONFIG_KEY);
//        assert key != null; // This validation happens at Ballerina level
//        evaluateKeyField(key, listenerConfiguration);
//        BMap<BString, Object> mutualSsl = getBMapValueIfPresent(secureSocket, SECURESOCKET_CONFIG_MUTUAL_SSL);
//        if (mutualSsl != null) {
//            String verifyClient = mutualSsl.getStringValue(HttpConstants.SECURESOCKET_CONFIG_VERIFY_CLIENT)
//                    .getValue();
//            listenerConfiguration.setVerifyClient(verifyClient);
//            Object cert = mutualSsl.get(HttpConstants.SECURESOCKET_CONFIG_CERT);
//            evaluateCertField(cert, listenerConfiguration);
//        }
//        BMap<BString, Object> protocol = getBMapValueIfPresent(secureSocket, SECURESOCKET_CONFIG_PROTOCOL);
//        if (protocol != null) {
//            evaluateProtocolField(protocol, listenerConfiguration, serverParamList);
//        }
//        BMap<BString, Object> certValidation =
//                getBMapValueIfPresent(secureSocket, SECURESOCKET_CONFIG_CERT_VALIDATION);
//        if (certValidation != null) {
//            evaluateCertValidationField(certValidation, listenerConfiguration);
//        }
//        BArray ciphers = secureSocket.containsKey(HttpConstants.SECURESOCKET_CONFIG_CIPHERS) ?
//                secureSocket.getArrayValue(HttpConstants.SECURESOCKET_CONFIG_CIPHERS) : null;
//        if (ciphers != null) {
//            evaluateCiphersField(ciphers, serverParamList);
//        }
//        evaluateCommonFields(secureSocket, listenerConfiguration, serverParamList);
//
//        listenerConfiguration.setTLSStoreType(HttpConstants.PKCS_STORE_TYPE);
//        if (!serverParamList.isEmpty()) {
//            listenerConfiguration.setParameters(serverParamList);
//        }
//        listenerConfiguration.setId(HttpUtil.getListenerInterface(listenerConfiguration.getHost(),
//                listenerConfiguration.getPort()));
        return listenerConfiguration;
    }

    public static void setInboundMgsSizeValidationConfig(int maxInitialLineLength, int maxHeaderSize,
                                                         int maxEntityBodySize,
                                                         InboundMsgSizeValidationConfig sizeValidationConfig)
            throws AxisFault {
        if (maxInitialLineLength >= 0) {
            sizeValidationConfig.setMaxInitialLineLength(Math.toIntExact(maxInitialLineLength));
        } else {
            throw new AxisFault(
                    "Invalid configuration found for max initial line length : " + maxInitialLineLength);
        }

        if (maxHeaderSize >= 0) {
            sizeValidationConfig.setMaxHeaderSize(Math.toIntExact(maxHeaderSize));
        } else {
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

//    private static void evaluateCertValidationField(BMap<BString, Object> certValidation,
//                                                    SslConfiguration sslConfiguration) {
//        String type = certValidation.getStringValue(HttpConstants.SECURESOCKET_CONFIG_CERT_VALIDATION_TYPE)
//        .getValue();
//        if (type.equals(HttpConstants.SECURESOCKET_CONFIG_CERT_VALIDATION_TYPE_OCSP_STAPLING.getValue())) {
//            sslConfiguration.setOcspStaplingEnabled(true);
//        } else {
//            sslConfiguration.setValidateCertEnabled(true);
//        }
//        long cacheSize = certValidation.getIntValue(SECURESOCKET_CONFIG_CERT_VALIDATION_CACHE_SIZE).intValue();
//        long cacheValidityPeriod = ((BDecimal) certValidation.get(
//                HttpConstants.SECURESOCKET_CONFIG_CERT_VALIDATION_CACHE_VALIDITY_PERIOD)).intValue();
//        if (cacheValidityPeriod != 0) {
//            sslConfiguration.setCacheValidityPeriod(Math.toIntExact(cacheValidityPeriod));
//        }
//        if (cacheSize != 0) {
//            sslConfiguration.setCacheSize(Math.toIntExact(cacheSize));
//        }
//    }
}
