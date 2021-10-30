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
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
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
import org.apache.axis2.transport.MessageFormatter;
import org.apache.axis2.transport.TransportUtils;
import org.apache.axis2.util.MessageProcessorSelector;
import org.apache.http.protocol.HTTP;
import org.apache.log4j.Logger;
import org.apache.synapse.transport.netty.BridgeConstants;
import org.apache.synapse.transport.nhttp.NhttpConstants;
import org.apache.synapse.transport.nhttp.util.MessageFormatterDecoratorFactory;
import org.apache.synapse.transport.nhttp.util.NhttpUtil;
import org.apache.synapse.transport.passthru.HttpGetRequestProcessor;
import org.apache.synapse.transport.passthru.PassThroughConstants;
import org.apache.synapse.transport.passthru.Pipe;
import org.apache.synapse.transport.passthru.config.PassThroughConfiguration;
import org.apache.synapse.transport.passthru.util.PassThroughTransportUtils;
import org.apache.synapse.transport.passthru.util.RelayUtils;
import org.wso2.transport.http.netty.message.BlockingEntityCollector;
import org.wso2.transport.http.netty.message.EntityCollector;
import org.wso2.transport.http.netty.message.HttpCarbonMessage;
import org.wso2.transport.http.netty.message.HttpMessageDataStreamer;
import org.wso2.transport.http.netty.message.PooledDataStreamerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * {@code RequestUtils} contains utilities used in request message flow.
 */
public class RequestUtils {
    private static final Logger LOG = Logger.getLogger(RequestUtils.class);

    public static MessageContext convertCarbonMsgToAxis2MsgCtx(ConfigurationContext axis2ConfigurationCtx,
                                                               HttpCarbonMessage incomingCarbonMsg) {
        MessageContext msgCtx = new MessageContext();
        msgCtx.setMessageID(UIDGenerator.generateURNString());
        msgCtx.setProperty(MessageContext.CLIENT_API_NON_BLOCKING,
                Boolean.FALSE);
        msgCtx.setConfigurationContext(axis2ConfigurationCtx);

        // TODO: Check the validity of this block
        msgCtx.setTransportOut(axis2ConfigurationCtx.getAxisConfiguration()
                .getTransportOut(Constants.TRANSPORT_HTTP));
        msgCtx.setTransportIn(axis2ConfigurationCtx.getAxisConfiguration()
                .getTransportIn(Constants.TRANSPORT_HTTP));
        msgCtx.setIncomingTransportName(Constants.TRANSPORT_HTTP);

        //TODO: propagate correlation logging related properties
        //TODO: propagate transaction property

        //TODO: SSL check

        msgCtx.setServerSide(true);
        msgCtx.setProperty(Constants.Configuration.TRANSPORT_IN_URL,
                incomingCarbonMsg.getProperty("TO"));

        // Following section is required for throttling to work
        msgCtx.setProperty(MessageContext.REMOTE_ADDR, incomingCarbonMsg.getProperty(
                org.wso2.transport.http.netty.contract.Constants.REMOTE_ADDRESS).toString());
        msgCtx.setProperty(BridgeConstants.REMOTE_HOST,
                incomingCarbonMsg.getProperty(org.wso2.transport.http.netty.contract.Constants.ORIGIN_HOST));

        // http transport header names are case insensitive
        Map<String, String> headers = new TreeMap<>(String::compareToIgnoreCase);
        incomingCarbonMsg.getHeaders().forEach(entry -> headers.put(entry.getKey(), entry.getValue()));
        msgCtx.setProperty(MessageContext.TRANSPORT_HEADERS, headers);
        //TODO: check setting of EXCESS_TRANSPORT_HEADERS property

        // TODO: check what TRANSPORT_CONTROL property does

        // Set the original incoming carbon message as a property
        msgCtx.setProperty(BridgeConstants.HTTP_CARBON_MESSAGE, incomingCarbonMsg);
        msgCtx.setProperty(BridgeConstants.HTTP_CLIENT_REQUEST_CARBON_MESSAGE, incomingCarbonMsg);
        return msgCtx;
    }

    public static HttpCarbonMessage convertAxis2MsgCtxToCarbonMsg(MessageContext msgCtx) {
        boolean isRequest = isRequest(msgCtx);
        Object httpMethodProperty = msgCtx.getProperty(BridgeConstants.HTTP_METHOD);

        if (Objects.isNull(httpMethodProperty)) {
            LOG.warn(BridgeConstants.BRIDGE_LOG_PREFIX + "HttpMethod not found in Axis2MessageContext");
            isRequest = false;
        }

        HttpCarbonMessage outboundHttpCarbonMessage;
        if (isRequest) {
            // Request
            LOG.info(BridgeConstants.BRIDGE_LOG_PREFIX + "Request");
            HttpMethod httpMethod = new HttpMethod((String) httpMethodProperty);
            outboundHttpCarbonMessage = new HttpCarbonMessage(
                    new DefaultHttpRequest(HttpVersion.HTTP_1_1, httpMethod, ""));
        } else {
            // Response
            LOG.info(BridgeConstants.BRIDGE_LOG_PREFIX + "Response");
            outboundHttpCarbonMessage = new HttpCarbonMessage(
                    new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
        }

        Map<String, String> headers = (Map<String, String>) msgCtx.getProperty(MessageContext.TRANSPORT_HEADERS);

        headers.forEach(outboundHttpCarbonMessage::setHeader);

        HttpCarbonMessage originalHttpCarbonMessage =
                (HttpCarbonMessage) msgCtx.getProperty(BridgeConstants.HTTP_CARBON_MESSAGE);

//         Map<String, Object> originalProperties = originalHttpCarbonMessage.getProperties();
//         originalProperties.forEach(outboundHttpCarbonMessage::setProperty);

        return outboundHttpCarbonMessage;
    }

    public static HttpCarbonMessage createOutboundHttpResponseCarbonMsg() {
        HttpCarbonMessage outboundHttpCarbonMessage = new HttpCarbonMessage(
                new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
        return outboundHttpCarbonMessage;
    }

    public static HttpCarbonMessage createOutboundHttpRequestCarbonMsg(HttpMethod httpMethod) {
        HttpCarbonMessage outboundHttpCarbonMessage = new HttpCarbonMessage(
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, httpMethod, ""));
        return outboundHttpCarbonMessage;
    }

    public static HttpCarbonMessage createOutboundCarbonMsg(HttpCarbonMessage inboundCarbonMsg, MessageContext msgCtx) {
        boolean isRequest = isRequest(msgCtx);
        HttpMethod httpMethod = null;
        if (msgCtx.getProperty(BridgeConstants.HTTP_METHOD) != null) {
            httpMethod = new HttpMethod((String) msgCtx.getProperty(BridgeConstants.HTTP_METHOD));
        } else {
            LOG.warn(BridgeConstants.BRIDGE_LOG_PREFIX + "HttpMethod not found in Axis2MessageContext");
            isRequest = false;
        }

        HttpCarbonMessage outboundHttpCarbonMessage;


        if (isRequest) {
            // Request
            LOG.info(BridgeConstants.BRIDGE_LOG_PREFIX + "Request");
            outboundHttpCarbonMessage = new HttpCarbonMessage(
                    new DefaultHttpRequest(HttpVersion.HTTP_1_1, httpMethod, ""));
        } else {
            // Response
            LOG.info(BridgeConstants.BRIDGE_LOG_PREFIX + "Response");
            outboundHttpCarbonMessage = new HttpCarbonMessage(
                    new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
        }


        Map<String, String> headers = (Map<String, String>) msgCtx.getProperty(MessageContext.TRANSPORT_HEADERS);

        headers.forEach(outboundHttpCarbonMessage::setHeader);

//        EntityCollector blockingEntityCollector = inboundCarbonMsg.getBlockingEntityCollector();
//        outboundHttpCarbonMessage.setBlockingEntityCollector((BlockingEntityCollector) blockingEntityCollector);

        HttpContent httpContent = inboundCarbonMsg.getHttpContent();
        outboundHttpCarbonMessage.addHttpContent(httpContent);

        return outboundHttpCarbonMessage;
    }

    public static void createOutboundResponse(HttpCarbonMessage inboundCarbonMsg,
                                              HttpCarbonMessage outboundResponseCarbonMsg,
                                              MessageContext msgContext) {
        // set http version
        HttpVersion version = HttpVersion.HTTP_1_1;   //TODO: check this validity
        String forceHttp10 = (String) msgContext.getProperty(PassThroughConstants.FORCE_HTTP_1_0);
        if (Constants.VALUE_TRUE.equals(forceHttp10)) {
            version = HttpVersion.HTTP_1_0;
        }

        // set status
        int statusCode = PassThroughTransportUtils.determineHttpStatusCode(msgContext);

        // set status line
        String statusLine = PassThroughTransportUtils.determineHttpStatusLine(msgContext);

        Object originalHttpStatusCodeProperty = msgContext.getProperty(PassThroughConstants.ORIGINAL_HTTP_SC);
        Object originalHttpResponsePhraseProperty =
                msgContext.getProperty(PassThroughConstants.ORIGINAL_HTTP_REASON_PHRASE);

        //
        DefaultHttpResponse httpResponse;
        if (statusCode != ((Integer) originalHttpStatusCodeProperty)
                && Objects.nonNull(originalHttpResponsePhraseProperty)
                && originalHttpResponsePhraseProperty.equals(statusLine)) {
            // create new DefaultHttpResponse with status code only
            httpResponse = new DefaultHttpResponse(version, HttpResponseStatus.valueOf(statusCode));
        } else {
            // new DefaultHttpResponse with status code and status line
            HttpResponseStatus newResponseStatus = new HttpResponseStatus(statusCode, statusLine);
            httpResponse = new DefaultHttpResponse(version, HttpResponseStatus.OK);
        }

        HttpHeaders headers = httpResponse.headers();

        // set content length
        boolean forceContentLength = msgContext.isPropertyTrue(NhttpConstants.FORCE_HTTP_CONTENT_LENGTH);
        boolean forceContentLengthCopy =
                msgContext.isPropertyTrue(PassThroughConstants.COPY_CONTENT_LENGTH_FROM_INCOMING);
        Object originalContentLengthProperty = msgContext.getProperty(PassThroughConstants.ORGINAL_CONTEN_LENGTH);

        if (forceContentLength
                && forceContentLengthCopy
                && originalContentLengthProperty != null
                && !DataHolder.getInstance().isPreserveHttpHeader(HTTP.CONTENT_LEN)) {
            headers.add(HTTP.CONTENT_LEN, (String) originalContentLengthProperty);
        }

        // When invoking http HEAD request esb set content length as 0 to response header. Since there is no message
        // body content length cannot be calculated inside synapse. Hence content length of the backend response is
        // set to sourceResponse.
        if (PassThroughConstants.HTTP_HEAD.equalsIgnoreCase(inboundCarbonMsg.getHttpMethod())
                && originalContentLengthProperty != null
                && !DataHolder.getInstance().isPreserveHttpHeader(PassThroughConstants.ORGINAL_CONTEN_LENGTH)) {
            headers.add(PassThroughConstants.ORGINAL_CONTEN_LENGTH, (String) originalContentLengthProperty);
        }

        // set any transport headers
        Map transportHeaders = (Map) msgContext.getProperty(MessageContext.TRANSPORT_HEADERS);
        if (transportHeaders != null && msgContext.getProperty(Constants.Configuration.MESSAGE_TYPE) != null) {
            if (msgContext.getProperty(org.apache.axis2.Constants.Configuration.CONTENT_TYPE) != null
                    && msgContext.getProperty(Constants.Configuration.CONTENT_TYPE).toString().contains(PassThroughConstants.CONTENT_TYPE_MULTIPART_RELATED)
                    && !sourceConfiguration.isPreserveHttpHeader(org.apache.axis2.Constants.Configuration.MESSAGE_TYPE)) {
                transportHeaders.put(Constants.Configuration.MESSAGE_TYPE, PassThroughConstants.CONTENT_TYPE_MULTIPART_RELATED);
            } else {
                Pipe pipe = (Pipe) msgContext.getProperty(PassThroughConstants.PASS_THROUGH_PIPE);
                if (pipe != null
                        && !Boolean.TRUE.equals(msgContext.getProperty(PassThroughConstants.MESSAGE_BUILDER_INVOKED))
                        && !sourceConfiguration.isPreserveHttpHeader(HTTP.CONTENT_TYPE)) {
                    transportHeaders.put(HTTP.CONTENT_TYPE, msgContext.getProperty(org.apache.axis2.Constants.Configuration.CONTENT_TYPE));
                }
            }
        }

        if (transportHeaders != null) {
            if (Objects.nonNull(msgContext.getProperty(Constants.Configuration.MESSAGE_TYPE))) {
                if (msgContext.getProperty(org.apache.axis2.Constants.Configuration.CONTENT_TYPE) != null
                        && msgContext.getProperty(Constants.Configuration.CONTENT_TYPE).toString()
                        .contains(PassThroughConstants.CONTENT_TYPE_MULTIPART_RELATED)
                        && !DataHolder.getInstance().isPreserveHttpHeader(Constants.Configuration.MESSAGE_TYPE)) {
                    transportHeaders.put(Constants.Configuration.MESSAGE_TYPE,
                            PassThroughConstants.CONTENT_TYPE_MULTIPART_RELATED);
                } else {
                    Pipe pipe = (Pipe) msgContext.getProperty(PassThroughConstants.PASS_THROUGH_PIPE);
                    if (pipe != null
                            && !Boolean.TRUE.equals(msgContext.getProperty(PassThroughConstants.MESSAGE_BUILDER_INVOKED))
                            && !DataHolder.getInstance().isPreserveHttpHeader(HTTP.CONTENT_TYPE)) {
                        transportHeaders.put(HTTP.CONTENT_TYPE,
                                msgContext.getProperty(Constants.Configuration.CONTENT_TYPE));
                    }
                }
            }
        } else {
            Boolean noEntityBody = (Boolean) msgContext.getProperty(NhttpConstants.NO_ENTITY_BODY);
            if (noEntityBody == null || Boolean.FALSE == noEntityBody) {
                OMOutputFormat format = NhttpUtil.getOMOutputFormat(msgContext);
                transportHeaders = new HashMap();
                MessageFormatter messageFormatter =
                        MessageFormatterDecoratorFactory.createMessageFormatterDecorator(msgContext);
                if (Objects.isNull(msgContext.getProperty(org.apache.axis2.Constants.Configuration.MESSAGE_TYPE))
                        && !DataHolder.getInstance().isPreserveHttpHeader(HTTP.CONTENT_TYPE)
                        && Objects.nonNull(messageFormatter)) {
                    transportHeaders.put(HTTP.CONTENT_TYPE,
                            messageFormatter.getContentType(msgContext, format, msgContext.getSoapAction()));
                }
            }
        }

        // set chunking status


        // set Connection keep alive header

    }

    private static HttpMessageDataStreamer getHttpMessageDataStreamer(HttpCarbonMessage outboundRequestMsg) {
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
        return contentType != null &&
                (contentType.contains("application/xml") ||
                        contentType.contains("application/x-www-form-urlencoded") ||
                        contentType.contains("multipart/form-data") ||
                        contentType.contains("application/json") ||
                        contentType.contains("application/jwt"));
    }

    public static boolean isRest(String contentType) {
        return contentType != null &&
                !contentType.contains(SOAP11Constants.SOAP_11_CONTENT_TYPE) &&
                !contentType.contains(SOAP12Constants.SOAP_12_CONTENT_TYPE);
    }

    public static int populateSOAPVersion(MessageContext msgContext, String soapActionHeader, String contentType) {
        int soapVersion = 0;
        if (contentType != null) {
            if (contentType.contains("application/soap+xml")) {
                soapVersion = 2;
                TransportUtils.processContentTypeForAction(contentType, msgContext);
            } else if (contentType.indexOf("text/xml") > -1) {
                soapVersion = 1;
            } else if (isRESTRequest(contentType)) {
                soapVersion = 1;
                msgContext.setDoingREST(true);
            }

            if (soapVersion == 1) {
                Parameter disableREST = msgContext.getParameter("disableREST");
                if (soapActionHeader == null && disableREST != null && "false".equals(disableREST.getValue())) {
                    msgContext.setDoingREST(true);
                }
            }
        }
        return soapVersion;
    }


    private static boolean isRequest(MessageContext msgCtx) {
        EndpointReference epr = getDestinationEPR(msgCtx);
        return epr != null;
    }

    /**
     * Get the EPR for the message passed in.
     * @param msgContext the message context
     * @return the destination EPR
     */
    public static EndpointReference getDestinationEPR(MessageContext msgContext) {

        // Trasnport URL can be different from the WSA-To
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

    public static HttpGetRequestProcessor createHttpGetProcessor(String str) {
        Object obj = null;
        try {
            obj = Class.forName(str).newInstance();
        } catch (ClassNotFoundException e) {
            handleException("Error creating WSDL processor", e);
        } catch (InstantiationException e) {
            handleException("Error creating WSDL processor", e);
        } catch (IllegalAccessException e) {
            handleException("Error creating WSDL processor", e);
        }

        if (obj instanceof HttpGetRequestProcessor) {
            return (HttpGetRequestProcessor) obj;
        } else {
            handleException("Error creating WSDL processor. The HttpProcessor should be of type " +
                    "org.apache.synapse.transport.nhttp.HttpGetRequestProcessor");
        }

        return null;
    }

    public static void handleException(String msg, Exception e) {
        LOG.error(msg, e);
//        throw new AxisFault(msg, e);
    }

    public static void handleException(String msg) {
        LOG.error(msg);
//        throw new AxisFault(msg);
    }

}
