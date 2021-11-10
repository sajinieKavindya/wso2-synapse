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
import org.apache.axis2.transport.RequestResponseTransport;
import org.apache.axis2.transport.TransportUtils;
import org.apache.axis2.transport.http.HTTPConstants;
import org.apache.axis2.transport.http.SOAPMessageFormatter;
import org.apache.axis2.util.MessageProcessorSelector;
import org.apache.http.HttpStatus;
import org.apache.http.protocol.HTTP;
import org.apache.log4j.Logger;
import org.apache.synapse.transport.netty.BridgeConstants;
import org.apache.synapse.transport.netty.sender.SourceResponse;
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
import org.wso2.transport.http.netty.message.HttpCarbonMessage;
import org.wso2.transport.http.netty.message.HttpMessageDataStreamer;
import org.wso2.transport.http.netty.message.PooledDataStreamerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * {@code RequestUtils} contains utilities used in request message flow.
 */
public class RequestUtils {

    private static final Logger LOG = Logger.getLogger(RequestUtils.class);

    public static MessageContext convertCarbonMsgToAxis2MsgCtx(ConfigurationContext axis2ConfigurationCtx,
                                                               HttpCarbonMessage incomingCarbonMsg) {

        MessageContext msgCtx = new MessageContext();
        msgCtx.setMessageID(UIDGenerator.generateURNString());
        msgCtx.setProperty(MessageContext.CLIENT_API_NON_BLOCKING, Boolean.FALSE);
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

        msgCtx.setProperty(RequestResponseTransport.TRANSPORT_CONTROL,
                new HttpCoreRequestResponseTransport(msgCtx));

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

    public static HttpCarbonMessage createOutboundResponse(HttpCarbonMessage inboundCarbonMsg,
                                                           MessageContext msgContext) throws AxisFault {
        // set http version
        HttpVersion version = determineHttpVersion(msgContext);

        // set status
        int statusCode = determineHttpStatusCode(msgContext);

        // set status line
        String statusLine = determineHttpStatusLine(msgContext, statusCode);

        SourceResponse sourceResponse = new SourceResponse(version, statusCode, statusLine);
        Boolean noEntityBody = (Boolean) msgContext.getProperty(NhttpConstants.NO_ENTITY_BODY);

        // set any transport headers
        Map transportHeaders = (Map) msgContext.getProperty(MessageContext.TRANSPORT_HEADERS);
        if (transportHeaders != null) {
            if (Objects.nonNull(msgContext.getProperty(Constants.Configuration.MESSAGE_TYPE))) {
                if (msgContext.getProperty(Constants.Configuration.CONTENT_TYPE) != null
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
        } else if (noEntityBody == null || Boolean.FALSE == noEntityBody) {
            OMOutputFormat format = NhttpUtil.getOMOutputFormat(msgContext);
            transportHeaders = new HashMap();
            MessageFormatter messageFormatter =
                    MessageFormatterDecoratorFactory.createMessageFormatterDecorator(msgContext);
            if (Objects.isNull(msgContext.getProperty(Constants.Configuration.MESSAGE_TYPE))
                    && !DataHolder.getInstance().isPreserveHttpHeader(HTTP.CONTENT_TYPE)
                    && Objects.nonNull(messageFormatter)) {
                transportHeaders.put(HTTP.CONTENT_TYPE,
                        messageFormatter.getContentType(msgContext, format, msgContext.getSoapAction()));
            }
        }

        addTransportHeadersToResponse(sourceResponse, transportHeaders);

        // Add excess transport headers.
        Map excessHeaders = (Map) msgContext.getProperty(NhttpConstants.EXCESS_TRANSPORT_HEADERS);
        addExcessHeadersToResponse(sourceResponse, excessHeaders);

//        Boolean noEntityBody = (Boolean) msgContext.getProperty(PassThroughConstants.NO_ENTITY_BODY);
//
//        if (noEntityBody != null && Boolean.TRUE == noEntityBody) {
//            sourceResponse.removeHeader(HTTP.CONTENT_TYPE);
//            return;
//        }

        // set content length
//        if (canResponseHaveBody(inboundCarbonMsg.getHttpMethod(), statusCode)) {
//            setContentLengthHeader(msgContext, inboundCarbonMsg.getHttpMethod(), sourceResponse);
//        }
        try {
            setContentLengthHeader(msgContext, inboundCarbonMsg.getHttpMethod(), sourceResponse);
        } catch (IOException e) {
            String msg = "Failed to submit the response";
            LOG.error(msg, e);
            throw new AxisFault(msg, e);
        }

        // set chunking status
        if (canResponseHaveBody(inboundCarbonMsg.getHttpMethod(), statusCode)) {
            long contentLength = -1;

            String contentLengthHeader;
            for (String header : sourceResponse.getHeaders().keySet()) {
                if (HTTP.CONTENT_LEN.equalsIgnoreCase(header)) {
                    contentLengthHeader = sourceResponse.getHeader(header).first();
                    contentLength = Long.parseLong(contentLengthHeader);
                    sourceResponse.removeHeader(header);
                    break;
                }
            }

            if (contentLength != -1) {
                sourceResponse.setChunked(false);
                sourceResponse.addHeader(BridgeConstants.CONTENT_LEN, String.valueOf(contentLength));
            } else {
                sourceResponse.setChunked(true);
            }
        }

        // set Connection keep alive header
        boolean keepAlive = true;
        String noKeepAlive = (String) msgContext.getProperty(PassThroughConstants.NO_KEEPALIVE);
        if ("true".equals(noKeepAlive) || PassThroughConfiguration.getInstance().isKeepAliveDisabled()) {
            keepAlive = false;
        } else if (inboundCarbonMsg.getHttpMethod() != null
                && isPayloadOptionalMethod(inboundCarbonMsg.getHttpMethod().toUpperCase())
                && (inboundCarbonMsg.getHeaders().contains(HTTP.CONTENT_LEN)
                || inboundCarbonMsg.getHeaders().contains(HTTP.TRANSFER_ENCODING))) {
            // If the payload is delayed for GET/HEAD/DELETE, http-core-nio will start processing request, without
            // waiting for the payload. Therefore the delayed payload will be appended to the next request. To avoid
            // that, disable keep-alive to avoid re-using the existing connection by client for the next request.
            if (LOG.isDebugEnabled()) {
                LOG.debug("Disable keep-alive in the client connection : Content-length/Transfer-encoding " +
                        "headers present for GET/HEAD/DELETE request");
            }
            keepAlive = false;
        }
        if (!keepAlive) {
            sourceResponse.addHeader(HTTP.CONN_DIRECTIVE, HTTP.CONN_CLOSE);
        }

        if (!Boolean.TRUE.equals(msgContext.getProperty(NhttpConstants.NO_ENTITY_BODY))
                && Boolean.TRUE.equals(msgContext.isPropertyTrue(PassThroughConstants.MESSAGE_BUILDER_INVOKED))) {

            if (Constants.VALUE_TRUE.equals(msgContext.getProperty(Constants.Configuration.ENABLE_MTOM)) ||
                    Constants.VALUE_TRUE.equals(msgContext.getProperty(Constants.Configuration.ENABLE_SWA))) {
                Object contentType = msgContext.getProperty(Constants.Configuration.CONTENT_TYPE);
                if (Objects.isNull(contentType) ||
                        !((String) contentType).trim()
                                .startsWith(PassThroughConstants.CONTENT_TYPE_MULTIPART_RELATED)) {
                    msgContext.setProperty(Constants.Configuration.CONTENT_TYPE,
                            PassThroughConstants.CONTENT_TYPE_MULTIPART_RELATED);
                }
                msgContext.setProperty(Constants.Configuration.MESSAGE_TYPE,
                        PassThroughConstants.CONTENT_TYPE_MULTIPART_RELATED);
            }

            MessageFormatter formatter = MessageFormatterDecoratorFactory.createMessageFormatterDecorator(msgContext);
            OMOutputFormat format = PassThroughTransportUtils.getOMOutputFormat(msgContext);
            // set Content-Type
            setContentType(msgContext, sourceResponse, formatter, format);
        }

        return sourceResponse.getResponse();
    }

    private static boolean isPayloadOptionalMethod(String httpMethod) {

        return (PassThroughConstants.HTTP_GET.equals(httpMethod) ||
                PassThroughConstants.HTTP_HEAD.equals(httpMethod) ||
                PassThroughConstants.HTTP_DELETE.equals(httpMethod));
    }

    private static boolean canResponseHaveBody(final String httpMethod, final int statusCode) {

        if ("HEAD".equalsIgnoreCase(httpMethod)) {
            return false;
        }
        return statusCode >= HttpStatus.SC_OK
                && statusCode != HttpStatus.SC_NO_CONTENT
                && statusCode != HttpStatus.SC_NOT_MODIFIED
                && statusCode != HttpStatus.SC_RESET_CONTENT;
    }

    private static void addTransportHeadersToResponse(SourceResponse sourceResponse, Map transportHeaders) {

        if (transportHeaders != null) {
            for (Object entryObj : transportHeaders.entrySet()) {
                Map.Entry entry = (Map.Entry) entryObj;
                if (entry.getValue() != null && entry.getKey() instanceof String &&
                        entry.getValue() instanceof String) {
                    sourceResponse.addHeader((String) entry.getKey(), (String) entry.getValue());
                }
            }
        }
    }

    private static void addExcessHeadersToResponse(SourceResponse sourceResponse, Map excessHeaders) {

        if (excessHeaders != null) {
            for (Iterator iterator = excessHeaders.keySet().iterator(); iterator.hasNext(); ) {
                String key = (String) iterator.next();
                for (String excessVal : (Collection<String>) excessHeaders.get(key)) {
                    sourceResponse.addHeader(key, (String) excessVal);
                }
            }
        }
    }

    /**
     * Determine the Http Status Code depending on the message type processed <br>
     * (normal response versus fault response) as well as Axis2 message context properties set
     * via Synapse configuration or MessageBuilders.
     *
     * @param msgContext the Axis2 message context
     * @return the HTTP status code to set in the HTTP response object
     * @see PassThroughConstants#FAULTS_AS_HTTP_200
     * @see PassThroughConstants#HTTP_SC
     */
    public static int determineHttpStatusCode(MessageContext msgContext) {

        int httpStatus = HttpStatus.SC_OK;

        Integer errorCode = (Integer) msgContext.getProperty(PassThroughConstants.ERROR_CODE);
        if (errorCode != null) {
            return HttpStatus.SC_BAD_GATEWAY;
        }

        // if this is a dummy message to handle http 202 case with non-blocking IO
        // set the status code to 202
        if (msgContext.isPropertyTrue(PassThroughConstants.SC_ACCEPTED)) {
            return HttpStatus.SC_ACCEPTED;
        } else {
            Object statusCode = msgContext.getProperty(PassThroughConstants.HTTP_SC);
            if (statusCode != null) {
                try {
                    httpStatus = Integer.parseInt(
                            msgContext.getProperty(PassThroughConstants.HTTP_SC).toString());
                    return httpStatus;
                } catch (NumberFormatException e) {
                    LOG.warn("Unable to set the HTTP status code from the property "
                            + PassThroughConstants.HTTP_SC + " with value: " + statusCode);
                }
            }

            // Is this a fault message?
            boolean handleFault = msgContext.getEnvelope() != null ?
                    (msgContext.getEnvelope().getBody().hasFault() || msgContext.isProcessingFault()) : false;
            boolean faultsAsHttp200 = false;
            if (msgContext.getProperty(PassThroughConstants.FAULTS_AS_HTTP_200) != null) {
                // shall faults be transmitted with HTTP 200
                faultsAsHttp200 =
                        PassThroughConstants.TRUE.equals(
                                msgContext.getProperty(PassThroughConstants.FAULTS_AS_HTTP_200).toString().toUpperCase());
            }
            // Set HTTP status code to 500 if this is a fault case and we shall not use HTTP 200
            if (handleFault && !faultsAsHttp200) {
                httpStatus = HttpStatus.SC_INTERNAL_SERVER_ERROR;
            }
        }
        return httpStatus;
    }

    /**
     * Determine the Http Status Message depending on the message type processed <br>
     * (normal response versus fault response) as well as Axis2 message context properties set
     * via Synapse configuration or MessageBuilders.
     *
     * @param msgContext the Axis2 message context
     * @return the HTTP status message string or null
     * @see PassThroughConstants#FAULTS_AS_HTTP_200
     * @see PassThroughConstants#HTTP_SC
     */
    public static String determineHttpStatusLine(MessageContext msgContext, int statusCode) {

        String statusLine = null;
        Object statusLineProperty = msgContext.getProperty(PassThroughConstants.HTTP_SC_DESC);
        if (statusLineProperty != null) {
            statusLine = (String) statusLineProperty;
        }

        Object originalHttpReasonPhraseProperty =
                msgContext.getProperty(PassThroughConstants.ORIGINAL_HTTP_REASON_PHRASE);
        Object originalHttpStatusCodeProperty = msgContext.getProperty(PassThroughConstants.ORIGINAL_HTTP_SC);
        if ((Objects.isNull(originalHttpStatusCodeProperty) || statusCode != ((Integer) originalHttpStatusCodeProperty))
                && Objects.nonNull(originalHttpReasonPhraseProperty)
                && originalHttpReasonPhraseProperty.equals(statusLine)) {
            // make the statusLine null so that the proper status code will be by the Netty server.
            statusLine = null;
        }
        return statusLine;
    }

    public static HttpVersion determineHttpVersion(MessageContext msgContext) {

        HttpVersion version = HttpVersion.HTTP_1_1;   //TODO: check this validity
        String forceHttp10 = (String) msgContext.getProperty(PassThroughConstants.FORCE_HTTP_1_0);
        if (Constants.VALUE_TRUE.equals(forceHttp10)) {
            version = HttpVersion.HTTP_1_0;
        }
        return version;
    }

    public static void setContentLengthHeader(MessageContext msgContext, String httpMethod,
                                              SourceResponse sourceResponse) throws IOException {

        boolean forceContentLength = msgContext.isPropertyTrue(NhttpConstants.FORCE_HTTP_CONTENT_LENGTH);
        boolean forceContentLengthCopy =
                msgContext.isPropertyTrue(PassThroughConstants.COPY_CONTENT_LENGTH_FROM_INCOMING);
        Object originalContentLengthProperty = msgContext.getProperty(PassThroughConstants.ORGINAL_CONTEN_LENGTH);

        if (forceContentLength
                && forceContentLengthCopy
                && originalContentLengthProperty != null
                && !DataHolder.getInstance().isPreserveHttpHeader(HTTP.CONTENT_LEN)) {
            sourceResponse.addHeader(HTTP.CONTENT_LEN, (String) originalContentLengthProperty);
        }

        // When invoking http HEAD request esb set content length as 0 to response header. Since there is no message
        // body content length cannot be calculated inside synapse. Hence content length of the backend response is
        // set to sourceResponse.
        if (PassThroughConstants.HTTP_HEAD.equalsIgnoreCase(httpMethod)
                && originalContentLengthProperty != null
                && !DataHolder.getInstance().isPreserveHttpHeader(PassThroughConstants.ORGINAL_CONTEN_LENGTH)) {
            sourceResponse.addHeader(PassThroughConstants.ORGINAL_CONTEN_LENGTH,
                    (String) originalContentLengthProperty);
        }

        if (Objects.isNull(msgContext.getProperty(PassThroughConstants.HTTP_SC))
                || canResponseHaveContentLength(msgContext, httpMethod)) {
            calculateContentLengthForChunkDisabledResponse(msgContext, sourceResponse);
        }
    }

    /**
     * Checks whether response can have Content-Length header.
     *
     * @param responseMsgContext out flow message context
     * @return true if response can have Content-Length header else false
     */
    private static boolean canResponseHaveContentLength(MessageContext responseMsgContext, String httpMethod) {

        Object httpStatus = responseMsgContext.getProperty(PassThroughConstants.HTTP_SC);
        int status;
        if (httpStatus == null || httpStatus.toString().equals("")) {
            return false;
        }
        if (httpStatus instanceof String) {
            status = Integer.parseInt((String) httpStatus);
        } else {
            status = (Integer) httpStatus;
        }
        if (PassThroughConstants.HTTP_CONNECT.equals(httpMethod)) {
            return (status / 100 != 2);
        } else {
            return HttpStatus.SC_NO_CONTENT != status && (status / 100 != 1);
        }
    }

    /**
     * Calculates the content-length when chunking is disabled.
     *
     * @param responseMsgContext outflow message context
     * @throws IOException
     */
    private static void calculateContentLengthForChunkDisabledResponse(
            MessageContext responseMsgContext, SourceResponse sourceResponse) throws IOException {

        String forceHttp10 = (String) responseMsgContext.getProperty(PassThroughConstants.FORCE_HTTP_1_0);
        boolean isChunkingDisabled = responseMsgContext.isPropertyTrue(PassThroughConstants.DISABLE_CHUNKING, false);

        if ("true".equals(forceHttp10) || isChunkingDisabled) {
            if (!responseMsgContext.isPropertyTrue(PassThroughConstants.MESSAGE_BUILDER_INVOKED,
                    false)) {
                try {
                    RelayUtils.buildMessage(responseMsgContext, false);
                    responseMsgContext.getEnvelope().buildWithAttachments();
                } catch (Exception e) {
                    throw new AxisFault(e.getMessage());
                }
            }

            Boolean noEntityBody = (Boolean) responseMsgContext.getProperty(PassThroughConstants.NO_ENTITY_BODY);

            if (noEntityBody != null && Boolean.TRUE == noEntityBody) {
                sourceResponse.removeHeader(HTTP.CONTENT_TYPE);
                return;
            }

            String contentType = sourceResponse.getHeader(HTTP.CONTENT_TYPE) != null ?
                    sourceResponse.getHeader(HTTP.CONTENT_TYPE).toString() : null;
            // Stream should be preserved to support disable chunking for SOAP based responses. This checks
            // whether chunking is disabled and response Content-Type is SOAP or FORCE_HTTP_1.0 is true.
            boolean preserveStream =
                    (isChunkingDisabled && isSOAPContentType(contentType)) || "true".equals(forceHttp10);

            MessageFormatter formatter = MessageProcessorSelector.getMessageFormatter(responseMsgContext);
            OMOutputFormat format = PassThroughTransportUtils.getOMOutputFormat(responseMsgContext);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            formatter.writeTo(responseMsgContext, format, out, preserveStream);
            sourceResponse.addHeader(HTTP.CONTENT_LEN, String.valueOf(out.toByteArray().length));
        }
    }

    /**
     * Set content type headers along with the charactor encoding if content type header is not preserved.
     *
     * @param msgContext     message context
     * @param sourceResponse source response
     * @param formatter      response formatter
     * @param format         response format
     */
    public static void setContentType(MessageContext msgContext, SourceResponse sourceResponse, MessageFormatter formatter,
                                      OMOutputFormat format) {

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
                sourceResponse.removeHeader(HTTP.CONTENT_TYPE);
                sourceResponse.addHeader(HTTP.CONTENT_TYPE, contentTypeValueInMsgCtx);
                isContentTypeSetFromMsgCtx = true;
            }
        }

        // If ContentType is not set from msg context, get the formatter ContentType
        if (!isContentTypeSetFromMsgCtx) {
            sourceResponse.removeHeader(HTTP.CONTENT_TYPE);
            sourceResponse.addHeader(HTTP.CONTENT_TYPE,
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
     * Checks whether Content-Type header related to a SOAP message.
     *
     * @param contentType Content-Type string
     * @return true if Content-Type is related to Soap.
     */
    private static boolean isSOAPContentType(String contentType) {

        return contentType != null &&
                (contentType.indexOf(SOAP11Constants.SOAP_11_CONTENT_TYPE) != -1 ||
                        contentType.indexOf(SOAP12Constants.SOAP_12_CONTENT_TYPE) != -1);
    }

    /**
     * Remove unwanted headers from the http response of outgoing request. These are headers which
     * should be dictated by the transport and not the user. We remove these as these may get
     * copied from the request messages
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
     * @param contentType
     * @return true for multipart content types
     */
    public static boolean isMultipartContent(String contentType) {

        if (contentType.contains(HTTPConstants.MEDIA_TYPE_MULTIPART_FORM_DATA)
                || contentType.contains(HTTPConstants.HEADER_ACCEPT_MULTIPART_RELATED)) {
            return true;
        }
        return false;
    }

    // If the HTTP method is GET or DELETE with no body, we need to write down the HEADER information to the wire
    // and need to ignore any entity enclosed methods available.
    public static boolean ignoreMessageBody(MessageContext msgContext) {

        if (HTTPConstants.HTTP_METHOD_GET.equals(msgContext.getProperty(Constants.Configuration.HTTP_METHOD)) ||
                RelayUtils.isDeleteRequestWithoutPayload(msgContext)) {
            return true;
        }
        return false;
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
     *
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
