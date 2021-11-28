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
package org.apache.synapse.transport.netty.listener;

import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.soap.SOAPEnvelope;
import org.apache.axiom.soap.SOAPFactory;
import org.apache.axis2.AxisFault;
import org.apache.axis2.Constants;
import org.apache.axis2.addressing.EndpointReference;
import org.apache.axis2.builder.BuilderUtil;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.description.Parameter;
import org.apache.axis2.engine.AxisEngine;
import org.apache.axis2.transport.RequestResponseTransport;
import org.apache.axis2.transport.TransportUtils;
import org.apache.axis2.transport.http.HTTPConstants;
import org.apache.axis2.util.MessageContextBuilder;
import org.apache.http.HttpStatus;
import org.apache.http.protocol.HTTP;
import org.apache.log4j.Logger;
import org.apache.synapse.commons.handlers.HandlerResponse;
import org.apache.synapse.commons.handlers.MessagingHandler;
import org.apache.synapse.transport.netty.BridgeConstants;
import org.apache.synapse.transport.netty.config.SourceConfiguration;
import org.apache.synapse.transport.netty.util.RequestResponseUtils;
import org.apache.synapse.transport.nhttp.NhttpConstants;
import org.apache.synapse.transport.passthru.PassThroughConstants;
import org.wso2.transport.http.netty.contract.exceptions.ServerConnectorException;
import org.wso2.transport.http.netty.message.HttpCarbonMessage;
import org.wso2.transport.http.netty.message.HttpCarbonRequest;
import org.wso2.transport.http.netty.message.HttpMessageDataStreamer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;

import static org.apache.synapse.transport.netty.BridgeConstants.CONTENT_TYPE_HEADER;
import static org.apache.synapse.transport.netty.BridgeConstants.SOAP_ACTION_HEADER;

/**
 * {@code HttpRequestWorker} is the Thread that does the request processing.
 */
public class HttpRequestWorker implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(HttpRequestWorker.class);
    private final HttpCarbonMessage incomingCarbonMsg;
    private final MessageContext msgContext;
    private final ConfigurationContext configurationContext;
    private final SourceConfiguration sourceConfiguration;


    public HttpRequestWorker(HttpCarbonMessage incomingCarbonMsg, SourceConfiguration sourceConfiguration) {

        this.sourceConfiguration = sourceConfiguration;
        this.incomingCarbonMsg = incomingCarbonMsg;
        this.configurationContext = sourceConfiguration.getConfigurationContext();
        this.msgContext = RequestResponseUtils.convertCarbonMsgToAxis2MsgCtx(incomingCarbonMsg, sourceConfiguration);
    }

    @Override
    public void run() {

        processHttpRequestUri();

        boolean continueFlow = invokeHandlers();
        if (!continueFlow) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Discard the message flow as there was an error return from a MessagingHandler");
            }
            return;
        }

        // check if the request is to fetch wsdl. If so, return the message flow without going through the normal flow
        if (isRequestToFetchWSDL()) {
            return;
        }

        try {
            populateProperties();
            AxisEngine.receive(msgContext);
        } catch (AxisFault ex) {
            handleException("Error processing " + incomingCarbonMsg.getHttpMethod()
                    + " request for : " + incomingCarbonMsg.getProperty("TO"), ex);
        }
        sendAck();
        cleanup();
    }

    /**
     * Check if the request is a WSDL query by invoking the registered {@code HttpGetRequestProcessor} for this
     * transport.
     *
     * @return if the request is a WSDL query or not
     */
    private boolean isRequestToFetchWSDL() {

        String method = incomingCarbonMsg.getHttpMethod();

        // WSDL queries are normally GET or HEAD requests. Therefore, we need to invoke the http GET request processor
        // for such requests to handle WSDL requests.
        if (PassThroughConstants.HTTP_GET.equals(method)
                || PassThroughConstants.HTTP_HEAD.equals(method)
                || PassThroughConstants.HTTP_OPTIONS.equals(method)) {

            invokeHttpGetRequestProcessor(msgContext, incomingCarbonMsg, sourceConfiguration);
        }

        // if this request is to fetch WSDL, then HttpGetRequestProcessor set the following properties
        // in the message context or in the inbound carbon message.
        return Boolean.TRUE.equals(incomingCarbonMsg.getProperty("WSDL_RESPONSE_DONE"))
                || Boolean.TRUE.equals((msgContext.getProperty(PassThroughConstants.WSDL_GEN_HANDLED)));
    }

    private void invokeHttpGetRequestProcessor(MessageContext msgContext, HttpCarbonMessage incomingCarbonMsg,
                                               SourceConfiguration sourceConfiguration) {

        HttpCarbonMessage outboundHttpCarbonMsg = new HttpCarbonMessage(
                new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
        sourceConfiguration.getHttpGetRequestProcessor()
                .process(incomingCarbonMsg, outboundHttpCarbonMsg, msgContext, true);
    }

    private boolean isEntityEnclosing(HttpCarbonMessage httpCarbonMessage) {

        long contentLength = BridgeConstants.NO_CONTENT_LENGTH_FOUND;
        String lengthStr = httpCarbonMessage.getHeader(HttpHeaderNames.CONTENT_LENGTH.toString());
        try {
            contentLength = lengthStr != null ? Long.parseLong(lengthStr) : contentLength;
            if (contentLength == BridgeConstants.NO_CONTENT_LENGTH_FOUND) {
                //Read one byte to make sure the incoming stream has data
                contentLength = httpCarbonMessage.countMessageLengthTill(BridgeConstants.ONE_BYTE);
            }
        } catch (NumberFormatException e) {
            LOGGER.error("NumberFormatException. Invalid content length");
        }
        return contentLength > 0;
    }

    /**
     * Get the URI of underlying HttpCarbonMessage and generate the service prefix and add to the message context.
     */
    private void processHttpRequestUri() {

        String servicePrefixIndex = "://";
        msgContext.setProperty(Constants.Configuration.HTTP_METHOD, incomingCarbonMsg.getHttpMethod().toUpperCase());
        String oriUri = (String) incomingCarbonMsg.getProperty("TO");
        String restUrlPostfix = RequestResponseUtils.getRestUrlPostfix(oriUri, configurationContext.getServicePath());

        String servicePrefix = oriUri.substring(0, oriUri.indexOf(restUrlPostfix));
        if (!servicePrefix.contains(servicePrefixIndex)) {
            InetSocketAddress localAddress = (InetSocketAddress) incomingCarbonMsg
                    .getProperty(org.wso2.transport.http.netty.contract.Constants.LOCAL_ADDRESS);
            if (localAddress != null) {
                servicePrefix = incomingCarbonMsg.getProperty(org.wso2.transport.http.netty.contract.Constants.PROTOCOL)
                        + servicePrefixIndex + localAddress.getHostName() + ":"
                        + incomingCarbonMsg.getProperty(org.wso2.transport.http.netty.contract.Constants.LISTENER_PORT)
                        + servicePrefix;
            }
        }
        msgContext.setProperty(BridgeConstants.SERVICE_PREFIX, servicePrefix);
        msgContext.setTo(new EndpointReference(restUrlPostfix));
        msgContext.setProperty(BridgeConstants.REST_URL_POSTFIX, restUrlPostfix);
    }

    /**
     * Populates required properties in the message context.
     *
     * @throws AxisFault if an error occurs while setting the SOAP envelope
     */
    private void populateProperties() throws AxisFault {

        String contentTypeHeader = incomingCarbonMsg.getHeaders().get(CONTENT_TYPE_HEADER);
        String charSetEncoding;
        String contentType;
        String messageType;

        if (contentTypeHeader != null) {
            contentType = contentTypeHeader;
            if (HTTPConstants.MEDIA_TYPE_X_WWW_FORM.equals(contentTypeHeader)) {
                // if the Content-Type headers is application/x-www-form-urlencoded, then setting the message type as
                // application/xml.
                messageType = "application/xml";
            } else {
                messageType = TransportUtils.getContentType(contentTypeHeader, msgContext);
            }
        } else {
            if (isEntityEnclosing(incomingCarbonMsg)) {
                Parameter param = sourceConfiguration.getConfigurationContext().getAxisConfiguration().
                        getParameter(PassThroughConstants.REQUEST_CONTENT_TYPE);
                if (param != null) {
                    contentType = param.getValue().toString();
                    messageType = contentType;
                } else {
                    // According to the RFC 7231 section 3.1.5.5, if the request containing a payload body does not
                    // have a Content-Type header field, then the recipient may assume a media type
                    // of "application/octet-stream"
                    contentType = PassThroughConstants.APPLICATION_OCTET_STREAM;
                    messageType = "application/xml";
                }
            } else {
                String httpMethod = (String) this.msgContext.getProperty(BridgeConstants.HTTP_METHOD);
                if (HTTPConstants.HEADER_GET.equals(httpMethod) || HTTPConstants.HEADER_DELETE.equals(httpMethod)) {
                    contentType = HTTPConstants.MEDIA_TYPE_X_WWW_FORM;
                } else {
                    contentType = PassThroughConstants.APPLICATION_OCTET_STREAM;
                }
                messageType = "application/xml";
            }

//            if (HTTPConstants.HEADER_GET.equals(httpMethod) || HTTPConstants.HEADER_DELETE.equals(httpMethod)) {
//                // if this is a GET or DELETE request with no Content-Type header, then set
//                // application/x-www-form-urlencoded as the default content type.
//                contentType = HTTPConstants.MEDIA_TYPE_X_WWW_FORM;
//                // if the Content-Type headers is application/x-www-form-urlencoded, then setting the message type as
//                // application/xml.
//                messageType = "application/xml";
//            } else {
//                // TODO: DEFAULT_REQUEST_CONTENT_TYPE parameter is set only in POST, PUT requests if they do not
////                //  have a content type header in the request?
////                Parameter param = sourceConfiguration.getConfigurationContext().getAxisConfiguration().
//                        getParameter(PassThroughConstants.REQUEST_CONTENT_TYPE);
//                if (param != null) {
//                    contentType = param.getValue().toString();
//                    messageType = contentType;
//                } else {
//                    // According to the RFC 7231 section 3.1.5.5, if the request containing a payload body does not
//                    // have a Content-Type header field, then the recipient may assume a media type
//                    // of "application/octet-stream"
//                    contentType = PassThroughConstants.APPLICATION_OCTET_STREAM;
//                    messageType = "application/xml";
//                }
//            }
        }
        msgContext.setProperty(Constants.Configuration.CONTENT_TYPE, contentType);
        msgContext.setProperty(Constants.Configuration.MESSAGE_TYPE, messageType);
        charSetEncoding = BuilderUtil.getCharSetEncoding(contentType);
        msgContext.setProperty(Constants.Configuration.CHARACTER_SET_ENCODING, charSetEncoding);

        msgContext.setProperty(HTTPConstants.HTTP_METHOD, incomingCarbonMsg.getHttpMethod().toUpperCase());
        this.msgContext.setTo(new EndpointReference((String) incomingCarbonMsg.getProperty("TO")));
        msgContext.setServerSide(true);

        if (!isEntityEnclosing(incomingCarbonMsg)) {
            this.msgContext.setProperty(PassThroughConstants.NO_ENTITY_BODY, Boolean.TRUE);
        }

        String soapAction = incomingCarbonMsg.getHeaders().get(SOAP_ACTION_HEADER);
        if ((soapAction != null) && soapAction.startsWith("\"") && soapAction.endsWith("\"")) {
            soapAction = soapAction.substring(1, soapAction.length() - 1);
            msgContext.setSoapAction(soapAction);
        }
        int soapVersion = RequestResponseUtils.populateSOAPVersion(msgContext, soapAction, contentType);
        SOAPEnvelope envelope;
        SOAPFactory fac;
        if (soapVersion == 1) {
            fac = OMAbstractFactory.getSOAP11Factory();
        } else {
            fac = OMAbstractFactory.getSOAP12Factory();
        }
        envelope = fac.getDefaultEnvelope();
        msgContext.setEnvelope(envelope);

        if (RequestResponseUtils.isDoingREST(msgContext, contentType, soapVersion, soapAction)) {
            msgContext.setProperty(BridgeConstants.REST_REQUEST_CONTENT_TYPE, contentType);
            msgContext.setDoingREST(true);
        }
    }

    /**
     * Sends a HTTP response to the client immediately after the current execution thread finishes, if the
     * 1. FORCE_SC_ACCEPTED property is true or
     * 2. A response is not written or not skipped and no FORCE_SOAP_FAULT is set or
     * 3. NIO-ACK-Requested property is set to true or
     * 4. RequestResponseTransportStatus is set to ACKED.
     */
    private void sendAck() {

        String responseWritten = "";
        if (msgContext.getOperationContext() != null) {
            responseWritten = (String) msgContext.getOperationContext().getProperty(Constants.RESPONSE_WRITTEN);
        }

        if (msgContext.getProperty(PassThroughConstants.FORCE_SOAP_FAULT) != null) {
            responseWritten = "SKIP";
        }

        boolean respWillFollow = !Constants.VALUE_TRUE.equals(responseWritten)
                && !"SKIP".equals(responseWritten);

        RequestResponseTransport.RequestResponseTransportStatus transportStatus =
                ((RequestResponseTransport) msgContext.getProperty(RequestResponseTransport.TRANSPORT_CONTROL))
                        .getStatus();
        boolean ack = RequestResponseTransport.RequestResponseTransportStatus.ACKED.equals(transportStatus);
        boolean forced = msgContext.isPropertyTrue(NhttpConstants.FORCE_SC_ACCEPTED);
        boolean nioAck = msgContext.isPropertyTrue("NIO-ACK-Requested", false);

        if (respWillFollow || ack || forced || nioAck) {
            HttpCarbonMessage clientRequest =
                    (HttpCarbonRequest) this.msgContext.getProperty(BridgeConstants.HTTP_CLIENT_REQUEST_CARBON_MESSAGE);

            HttpCarbonMessage outboundResponse;

            if (!nioAck) {
                msgContext.removeProperty(MessageContext.TRANSPORT_HEADERS);
                outboundResponse = new HttpCarbonMessage(
                        new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.ACCEPTED));
                outboundResponse.setHttpStatusCode(HttpStatus.SC_ACCEPTED);

            } else {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Sending ACK response with status " + msgContext.getProperty(NhttpConstants.HTTP_SC)
                            + ", for MessageID : " + msgContext.getMessageID());
                }
                int statusCode = Integer.parseInt(msgContext.getProperty(NhttpConstants.HTTP_SC).toString());
                outboundResponse = new HttpCarbonMessage(
                        new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(statusCode)));
                outboundResponse.setHttpStatusCode(statusCode);
            }

            try {
                clientRequest.respond(outboundResponse);
            } catch (ServerConnectorException e) {
                LOGGER.error("Error occurred while submitting the Ack to the client", e);
            }

            final HttpMessageDataStreamer httpMessageDataStreamer =
                    RequestResponseUtils.getHttpMessageDataStreamer(outboundResponse);

            try (OutputStream outputStream = httpMessageDataStreamer.getOutputStream()) {
                outputStream.write(new byte[0]);
            } catch (IOException e) {
                LOGGER.error("Error occurred while writing the Ack to the client", e);
            }
        }
    }

    private void handleException(String msg, Exception e) {

        if (e == null) {
            LOGGER.error(msg);
            e = new Exception(msg);
        } else {
            LOGGER.error(msg, e);
        }

        try {
            //TODO: check if need to consume and discard the remaining input in the http carbon message
            MessageContext faultContext = MessageContextBuilder.createFaultMessageContext(msgContext, e);
            msgContext.setProperty(PassThroughConstants.FORCE_SOAP_FAULT, Boolean.TRUE);
            AxisEngine.sendFault(faultContext);

        } catch (Exception ex) {
            HttpCarbonMessage clientRequest =
                    (HttpCarbonRequest) msgContext.getProperty(BridgeConstants.HTTP_CLIENT_REQUEST_CARBON_MESSAGE);

            HttpCarbonMessage outboundResponse = new HttpCarbonMessage(
                    new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR));
            //TODO: check what are the required request headers to be sent here
            outboundResponse.setHeader(HTTP.CONTENT_TYPE, "text/html");
            outboundResponse.setHttpStatusCode(HttpStatus.SC_INTERNAL_SERVER_ERROR);

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Sending ACK response with status " + msgContext.getProperty(NhttpConstants.HTTP_SC)
                        + ", for MessageID : " + msgContext.getMessageID());
            }
            HttpMessageDataStreamer httpMessageDataStreamer =
                    RequestResponseUtils.getHttpMessageDataStreamer(outboundResponse);
            OutputStream outputStream = httpMessageDataStreamer.getOutputStream();

            try {
                String body = "<html><body><h1>" + "Failed to process the request"
                        + "</h1><p>" + msg + "</p><p>" + ex.getMessage()
                        + "</p></body></html>";
                outputStream.write(body.getBytes());
                outputStream.flush();
                outputStream.close();
            } catch (Exception ignore) {
                // ignore
            }

            try {
                clientRequest.respond(outboundResponse);
            } catch (ServerConnectorException serverConnectorException) {
                LOGGER.error("Error occurred while submitting the response to the client");
            }
        }
    }

    /**
     * {@code MessagingHandler} is an extension point to intercept the inbound HTTP request for further processing.
     * This invokes the {@code handleSourceRequest} method of all the registered MessagingHandler instances to handle
     * the inbound request before going to the mediation flow.
     *
     * @return whether flow should continue further
     */
    private boolean invokeHandlers() {

        List<MessagingHandler> messagingHandlers = sourceConfiguration.getMessagingHandlers();
        if (Objects.nonNull(messagingHandlers) && !messagingHandlers.isEmpty()) {
            for (MessagingHandler handler : messagingHandlers) {
                HandlerResponse response = handler.handleSourceRequest(msgContext);
                if (Objects.isNull(response) || !response.isError()) {
                    continue;
                }
                // TODO: handle HandlerResponse error
            }
        }
        return true;
    }

    /**
     * Perform cleanup of HttpRequestWorker.
     */
    private void cleanup() {
        //clean threadLocal variables
        MessageContext.destroyCurrentMessageContext();
        // TODO: clean tenantInfo
    }
}
