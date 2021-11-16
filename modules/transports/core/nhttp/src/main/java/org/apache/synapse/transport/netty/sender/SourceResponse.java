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
package org.apache.synapse.transport.netty.sender;

import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.apache.axiom.om.OMOutputFormat;
import org.apache.axiom.soap.SOAP11Constants;
import org.apache.axiom.soap.SOAP12Constants;
import org.apache.axis2.AxisFault;
import org.apache.axis2.Constants;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.transport.MessageFormatter;
import org.apache.axis2.transport.http.HTTPConstants;
import org.apache.axis2.util.MessageProcessorSelector;
import org.apache.http.HttpStatus;
import org.apache.http.protocol.HTTP;
import org.apache.log4j.Logger;
import org.apache.synapse.transport.netty.BridgeConstants;
import org.apache.synapse.transport.netty.util.DataHolder;
import org.apache.synapse.transport.netty.util.RequestResponseUtils;
import org.apache.synapse.transport.nhttp.NhttpConstants;
import org.apache.synapse.transport.nhttp.util.MessageFormatterDecoratorFactory;
import org.apache.synapse.transport.nhttp.util.NhttpUtil;
import org.apache.synapse.transport.passthru.PassThroughConstants;
import org.apache.synapse.transport.passthru.Pipe;
import org.apache.synapse.transport.passthru.config.PassThroughConfiguration;
import org.apache.synapse.transport.passthru.util.PassThroughTransportUtils;
import org.apache.synapse.transport.passthru.util.RelayUtils;
import org.wso2.transport.http.netty.contract.config.ChunkConfig;
import org.wso2.transport.http.netty.message.HttpCarbonMessage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * This class represents the Outbound Http response.
 */
public class SourceResponse {

    private static final Logger LOGGER = Logger.getLogger(SourceResponse.class);

    /**
     * Status of the response.
     */
    private int statusCode = HttpStatus.SC_OK;
    /**
     * Status line.
     */
    private String statusLine = null;
    /**
     * Version of the response.
     */
    private String version = "1.1";
    /**
     * Keep alive request.
     */
    private boolean keepAlive = true;


    private ChunkConfig chunkConfig = ChunkConfig.ALWAYS;

    /**
     * Outbound response Http Carbon msg.
     */
    private HttpCarbonMessage outboundHttpCarbonMessage;

    public HttpCarbonMessage getOutboundResponseCarbonMessage(MessageContext msgContext,
                                                              HttpCarbonMessage inboundCarbonMsg) throws IOException {
        if (Objects.isNull(outboundHttpCarbonMessage)) {
            outboundHttpCarbonMessage = createOutboundResponseCarbonMessage(msgContext, inboundCarbonMsg);
        }
        return outboundHttpCarbonMessage;
    }

    private HttpCarbonMessage createOutboundResponseCarbonMessage(MessageContext msgContext,
                                                                  HttpCarbonMessage inboundCarbonMsg)
            throws IOException {

        version = determineHttpVersion(msgContext);
        statusCode = determineHttpStatusCode(msgContext);
        statusLine = determineHttpStatusLine(msgContext, statusCode);

        // Even though we provide http version, status code, status line (reason phrase) when creating the
        // DefaultHttpResponse instance for the HttpCarbonMessage constructor, these values are not used at
        // transport-http. At transport-http, it will create a new DefaultHttpResponse instance based on the values
        // that we set inside the HttpCarbonMessage using setHttpVersion, setHttpStatusCode, etc.
        HttpCarbonMessage outboundHttpCarbonMessage = new HttpCarbonMessage(new DefaultHttpResponse(
                new HttpVersion(org.wso2.transport.http.netty.contract.Constants.HTTP_VERSION_PREFIX + version,
                        true), new HttpResponseStatus(statusCode, statusLine)));

        outboundHttpCarbonMessage.setHttpVersion(version);
        outboundHttpCarbonMessage.setHttpStatusCode(statusCode);
        // Whenever the status line is null, the transport-http will infer the correct status line based on the
        // provided status code.
        outboundHttpCarbonMessage.setProperty(org.wso2.transport.http.netty.contract.Constants.HTTP_REASON_PHRASE,
                statusLine);

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
                            && !Boolean.TRUE.equals(msgContext.getProperty(PassThroughConstants
                            .MESSAGE_BUILDER_INVOKED))
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

        RequestResponseUtils.addTransportHeaders(msgContext, outboundHttpCarbonMessage);

        // Add excess transport headers
        RequestResponseUtils.addExcessHeaders(msgContext, outboundHttpCarbonMessage);

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
            setContentLengthHeader(msgContext, inboundCarbonMsg.getHttpMethod(), outboundHttpCarbonMessage);
        } catch (IOException e) {
            String msg = "Failed to submit the response";
            LOGGER.error(msg, e);
            throw new AxisFault(msg, e);
        }

        // set chunking status
        if (canResponseHaveBody(inboundCarbonMsg.getHttpMethod(), statusCode)) {
            long contentLength = -1;

            String contentLengthHeader = outboundHttpCarbonMessage.getHeader(BridgeConstants.CONTENT_LEN);
            if (Objects.nonNull(contentLengthHeader)) {
                contentLength = Long.parseLong(contentLengthHeader);
            }

            // TODO: check if we need to set chunking enable for content len = 0
            if (contentLength != -1) {
                outboundHttpCarbonMessage.setProperty(BridgeConstants.CHUNKING_CONFIG, ChunkConfig.NEVER);
            } else {
                outboundHttpCarbonMessage.removeHeader(BridgeConstants.CONTENT_LEN);
                outboundHttpCarbonMessage.setProperty(BridgeConstants.CHUNKING_CONFIG, ChunkConfig.ALWAYS);
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
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Disable keep-alive in the client connection : Content-length/Transfer-encoding " +
                        "headers present for GET/HEAD/DELETE request");
            }
            keepAlive = false;
        }
        if (!keepAlive) {
            addHeader(outboundHttpCarbonMessage, HTTP.CONN_DIRECTIVE, HTTP.CONN_CLOSE);
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
            setContentType(msgContext, outboundHttpCarbonMessage, formatter, format);
        }

        return outboundHttpCarbonMessage;
    }

    private static String determineHttpVersion(MessageContext msgContext) {

        String version = "1.1";   //TODO: check the validity of setting the default http version as 1.1
        String forceHttp10 = (String) msgContext.getProperty(PassThroughConstants.FORCE_HTTP_1_0);
        if (Constants.VALUE_TRUE.equals(forceHttp10)) {
            version = "1.0";
        }
        return version;
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
                    LOGGER.warn("Unable to set the HTTP status code from the property "
                            + PassThroughConstants.HTTP_SC + " with value: " + statusCode);
                }
            }

            // Is this a fault message?
            boolean handleFault = msgContext.getEnvelope() != null ?
                    (msgContext.getEnvelope().getBody().hasFault() || msgContext.isProcessingFault()) : false;
            boolean faultsAsHttp200 = false;
            if (msgContext.getProperty(PassThroughConstants.FAULTS_AS_HTTP_200) != null) {
                // shall faults be transmitted with HTTP 200
                faultsAsHttp200 = PassThroughConstants.TRUE.equals(
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

    private static boolean isPayloadOptionalMethod(String httpMethod) {

        return (PassThroughConstants.HTTP_GET.equals(httpMethod) ||
                PassThroughConstants.HTTP_HEAD.equals(httpMethod) ||
                PassThroughConstants.HTTP_DELETE.equals(httpMethod));
    }

    /**
     * Set content type headers along with the charactor encoding if content type header is not preserved.
     *
     * @param msgContext     message context
     * @param outboundResponse source http response carbon message
     * @param formatter      response formatter
     * @param format         response format
     */
    public static void setContentType(MessageContext msgContext, HttpCarbonMessage outboundResponse,
                                      MessageFormatter formatter, OMOutputFormat format) {

        if (DataHolder.getInstance().isPreserveHttpHeader(HTTP.CONTENT_TYPE)) {
            return;
        }
        Object contentTypeInMsgCtx =
                msgContext.getProperty(Constants.Configuration.CONTENT_TYPE);
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
                outboundResponse.setHeader(HTTP.CONTENT_TYPE, contentTypeValueInMsgCtx);
                isContentTypeSetFromMsgCtx = true;
            }
        }

        // If ContentType is not set from msg context, get the formatter ContentType
        if (!isContentTypeSetFromMsgCtx) {
            outboundResponse.setHeader(HTTP.CONTENT_TYPE,
                    formatter.getContentType(msgContext, format, msgContext.getSoapAction()));
        }
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

    public static void setContentLengthHeader(MessageContext msgContext, String httpMethod,
                                              HttpCarbonMessage outboundResponse) throws IOException {

        boolean forceContentLength = msgContext.isPropertyTrue(NhttpConstants.FORCE_HTTP_CONTENT_LENGTH);
        boolean forceContentLengthCopy =
                msgContext.isPropertyTrue(PassThroughConstants.COPY_CONTENT_LENGTH_FROM_INCOMING);
        Object originalContentLengthProperty = msgContext.getProperty(PassThroughConstants.ORGINAL_CONTEN_LENGTH);

        if (forceContentLength
                && forceContentLengthCopy
                && originalContentLengthProperty != null
                && !DataHolder.getInstance().isPreserveHttpHeader(HTTP.CONTENT_LEN)) {
            addHeader(outboundResponse, HTTP.CONTENT_LEN, (String) originalContentLengthProperty);
        }

        // When invoking http HEAD request esb set content length as 0 to response header. Since there is no message
        // body content length cannot be calculated inside synapse. Hence content length of the backend response is
        // set to sourceResponse.
        if (PassThroughConstants.HTTP_HEAD.equalsIgnoreCase(httpMethod)
                && originalContentLengthProperty != null
                && !DataHolder.getInstance().isPreserveHttpHeader(PassThroughConstants.ORGINAL_CONTEN_LENGTH)) {
            addHeader(outboundResponse, PassThroughConstants.ORGINAL_CONTEN_LENGTH,
                    (String) originalContentLengthProperty);
        }

        if (Objects.isNull(msgContext.getProperty(PassThroughConstants.HTTP_SC))
                || canResponseHaveContentLength(msgContext, httpMethod)) {
            calculateContentLengthForChunkDisabledResponse(msgContext, outboundResponse);
        }
    }

    /**
     * Calculates the content-length when chunking is disabled.
     *
     * @param responseMsgContext outflow message context
     * @throws IOException
     */
    private static void calculateContentLengthForChunkDisabledResponse(MessageContext responseMsgContext,
                                                                       HttpCarbonMessage outboundResponse)
            throws IOException {

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
                outboundResponse.removeHeader(HTTP.CONTENT_TYPE);
                return;
            }

            String contentType = outboundResponse.getHeader(HTTP.CONTENT_TYPE) != null ?
                    outboundResponse.getHeader(HTTP.CONTENT_TYPE) : null;
            // Stream should be preserved to support disable chunking for SOAP based responses. This checks
            // whether chunking is disabled and response Content-Type is SOAP or FORCE_HTTP_1.0 is true.
            boolean preserveStream =
                    (isChunkingDisabled && isSOAPContentType(contentType)) || "true".equals(forceHttp10);

            MessageFormatter formatter = MessageProcessorSelector.getMessageFormatter(responseMsgContext);
            OMOutputFormat format = PassThroughTransportUtils.getOMOutputFormat(responseMsgContext);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            formatter.writeTo(responseMsgContext, format, out, preserveStream);
            addHeader(outboundResponse, HTTP.CONTENT_LEN, String.valueOf(out.toByteArray().length));
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
     * Checks whether Content-Type header related to a SOAP message.
     *
     * @param contentType Content-Type string
     * @return true if Content-Type is related to Soap.
     */
    private static boolean isSOAPContentType(String contentType) {

        return contentType != null && (contentType.contains(SOAP11Constants.SOAP_11_CONTENT_TYPE)
                || contentType.contains(SOAP12Constants.SOAP_12_CONTENT_TYPE));
    }

    private static void addHeader(HttpCarbonMessage httpCarbonMessage, String name, String value) {
        String headerValue = httpCarbonMessage.getHeader(name);
        if (Objects.isNull(headerValue)) {
            httpCarbonMessage.setHeader(name, value);
        } else {
            headerValue = String.join(",", headerValue, value);
            httpCarbonMessage.setHeader(name, headerValue);
        }
    }
}
