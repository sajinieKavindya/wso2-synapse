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

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.apache.axiom.om.OMOutputFormat;
import org.apache.axis2.AxisFault;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.transport.MessageFormatter;
import org.apache.axis2.transport.http.HTTPConstants;
import org.apache.axis2.util.MessageProcessorSelector;
import org.apache.http.protocol.HTTP;
import org.apache.synapse.transport.netty.BridgeConstants;
import org.apache.synapse.transport.netty.util.DataHolder;
import org.apache.synapse.transport.netty.util.RequestResponseUtils;
import org.apache.synapse.transport.nhttp.NhttpConstants;
import org.apache.synapse.transport.nhttp.util.MessageFormatterDecoratorFactory;
import org.apache.synapse.transport.passthru.PassThroughConstants;
import org.apache.synapse.transport.passthru.config.PassThroughConfiguration;
import org.apache.synapse.transport.passthru.util.PassThroughTransportUtils;
import org.apache.synapse.transport.passthru.util.RelayUtils;
import org.wso2.transport.http.netty.contract.Constants;
import org.wso2.transport.http.netty.message.HttpCarbonMessage;

import java.io.IOException;
import java.net.URL;
import java.util.Map;
import java.util.Objects;

/**
 * This class represents the Outbound Http request.
 */
public class HttpOutboundRequest {

    /**
     * Keep alive request.
     */
    private boolean keepAlive;
    /**
     * Weather chunk encoding should be used.
     */
    private boolean chunk = true;

    private String httpVersion;

    private String httpMethod;

    private HttpCarbonMessage httpCarbonMessage;

    public HttpOutboundRequest(MessageContext msgContext, URL url) throws AxisFault {
        httpCarbonMessage = new HttpCarbonMessage(new DefaultHttpRequest(HttpVersion.HTTP_1_1,
                HttpMethod.POST, ""));

        setHTTPVersion(msgContext, httpCarbonMessage);
        setHTTPMethod(msgContext, httpCarbonMessage);

        int port = getOutboundReqPort(url);
        String host = url.getHost();
        try {
            setOutboundReqHeaders(httpCarbonMessage, msgContext, host, port);
            setOutboundReqProperties(httpCarbonMessage, msgContext, url, host, port);
        } catch (IOException e) {
            throw new AxisFault("Error while creating the target request", e);
        }

        if (hasEntityBody(msgContext)) {
            // no need to specifically set the chunking status in the http carbon message because transport-http
            // will get the chunking status from the value we set in the SenderConfiguration.
            chunk = RequestResponseUtils.enableChunking(msgContext);
        } else {
            // If the request does not have a entity body to be sent, then we need to set the following property.
            httpCarbonMessage.setProperty(Constants.NO_ENTITY_BODY, true);
        }
    }

//    public HttpCarbonMessage getOutboundRequestCarbonMessage(MessageContext msgCtx, URL url) throws IOException {
//        if (Objects.isNull(httpCarbonMessage)) {
//            httpCarbonMessage = createOutboundRequestCarbonMessage(msgCtx, url);
//        }
//        return httpCarbonMessage;
//    }
//
//    private HttpCarbonMessage createOutboundRequestCarbonMessage(MessageContext msgContext, URL url)
//            throws IOException {
//        HttpCarbonMessage outboundRequest = new HttpCarbonMessage(new DefaultHttpRequest(HttpVersion.HTTP_1_1,
//                HttpMethod.POST, ""));
//
//        outboundRequest.setHttpVersion(getHTTPVersion(msgContext));
//        outboundRequest.setHttpMethod(getHTTPMethod(msgContext));
//
//        int port = getOutboundReqPort(url);
//        String host = url.getHost();
//        setOutboundReqProperties(outboundRequest, msgContext, url, host, port);
//        setOutboundReqHeaders(outboundRequest, msgContext, host, port);
//
//        // keep alive
////        String noKeepAlive = (String) msgContext.getProperty(PassThroughConstants.NO_KEEPALIVE);
////        if (org.apache.axis2.Constants.VALUE_TRUE.equals(noKeepAlive)
////                || PassThroughConfiguration.getInstance().isKeepAliveDisabled()) {
////            keepAlive = false;
////            outboundRequest.setHeader(HTTP.CONN_DIRECTIVE, HTTP.CONN_CLOSE);
////            // TODO: when connection is closed the server throws an error as below. Is this behavior correct?
////            // [2021-11-06 10:48:31,701] ERROR {org.apache.synapse.transport.netty.sender
////            // .PassThroughHttpOutboundRespListener}-[Bridge] Error while processing the response java.io.IOException:
////            // Broken pipe
////            // ERROR {org.wso2.transport.http.netty.contractimpl.sender.states.SendingEntityBody} -
////            // Error in HTTP client: Remote host closed the connection while writing outbound request entity body
////        } else {
////            keepAlive = true;
////            outboundRequest.setHeader(HTTP.CONN_DIRECTIVE, HTTP.CONN_KEEP_ALIVE);
////        }
//
//        if (hasEntityBody(msgContext)) {
//            chunk = RequestResponseUtils.enableChunking(msgContext);
//        }
//
//        return outboundRequest;
//    }

    private int getOutboundReqPort(URL url) {

        int port = 80;
        if (url.getPort() != -1) {
            port = url.getPort();
        } else if (url.getProtocol().equalsIgnoreCase(Constants.HTTPS_SCHEME)) {
            port = 443;
        }
        return port;
    }

    private void setHTTPVersion(MessageContext msgContext, HttpCarbonMessage outboundRequest) {
        String forceHttp10 = (String) msgContext.getProperty(PassThroughConstants.FORCE_HTTP_1_0);
        if (org.apache.axis2.Constants.VALUE_TRUE.equals(forceHttp10)) {
            httpVersion = "1.0";
            // need to set the version in the client connector
        } else {
            httpVersion = "1.1";
        }
        outboundRequest.setHttpVersion(httpVersion);
    }

    private void setHTTPMethod(MessageContext msgCtx, HttpCarbonMessage outboundRequest) {
        httpMethod = (String) msgCtx.getProperty(BridgeConstants.HTTP_METHOD);
        if (Objects.isNull(httpMethod)) {
            httpMethod = HTTPConstants.HTTP_METHOD_POST;
        }
        outboundRequest.setHttpMethod(httpMethod);
    }

    private void setOutboundReqProperties(HttpCarbonMessage outboundRequest, MessageContext msgCtx, URL url,
                                          String host, int port) throws AxisFault {

        outboundRequest.setProperty(Constants.HTTP_HOST, host);
        outboundRequest.setProperty(Constants.HTTP_PORT, port);
        try {
            String outboundReqPath = getOutboundReqPath(url, msgCtx);
            outboundRequest.setProperty(Constants.TO, outboundReqPath);
            outboundRequest.setProperty(Constants.PROTOCOL, url.getProtocol() != null ? url.getProtocol() : "http");
        } catch (IOException e) {
            throw new AxisFault("Error occurred while getting the target request path", e);
        }

    }

    private String getOutboundReqPath(URL url, MessageContext msgCtx) throws IOException {

        if ((PassThroughConstants.HTTP_GET.equals(msgCtx.getProperty(BridgeConstants.HTTP_METHOD)))
                || (RelayUtils.isDeleteRequestWithoutPayload(msgCtx))) {
            MessageFormatter formatter = MessageProcessorSelector.getMessageFormatter(msgCtx);
            OMOutputFormat format = PassThroughTransportUtils.getOMOutputFormat(msgCtx);
            if (formatter != null) {
                URL targetURL = formatter.getTargetAddress(msgCtx, format, url);
                if (targetURL != null && !targetURL.toString().isEmpty()) {
                    if (msgCtx.getProperty(NhttpConstants.POST_TO_URI) != null
                            && Boolean.TRUE.toString().equals(msgCtx.getProperty(NhttpConstants.POST_TO_URI))) {
                        return targetURL.toString();
                    } else {
                        return targetURL.getPath()
                                + ((targetURL.getQuery() != null && !targetURL.getQuery().isEmpty())
                                ? ("?" + targetURL.getQuery())
                                : "");
                    }
                }
            }
        }

        if (msgCtx.isPropertyTrue(NhttpConstants.POST_TO_URI)) {
            return url.toString();
        }

        String fullUrl = (String) msgCtx.getProperty(PassThroughConstants.FULL_URI);
        // TODO: need to check "(route.getProxyHost() != null && !route.isTunnelled())" as well
        String path = "true".equals(fullUrl) ?
                url.toString() : url.getPath() +
                (url.getQuery() != null ? "?" + url.getQuery() : "");

        return path;
    }

    private void setOutboundReqHeaders(HttpCarbonMessage outboundRequest, MessageContext msgCtx,
                                       String host, int port) throws AxisFault {

        setHostHeader(host, port, outboundRequest, msgCtx);
        RequestResponseUtils.addTransportHeaders(msgCtx, outboundRequest);
        RequestResponseUtils.addExcessHeaders(msgCtx, outboundRequest);
        setContentTypeHeaderIfApplicable(msgCtx, outboundRequest);
        setWSAActionIfApplicable(msgCtx, outboundRequest);
        setKeepAliveConfig(msgCtx);
    }

    private void setHostHeader(String host, int port, HttpCarbonMessage outboundRequest, MessageContext msgCtx) {

        HttpHeaders headers = outboundRequest.getHeaders();
        Map transportHeaders = (Map) msgCtx.getProperty(MessageContext.TRANSPORT_HEADERS);
        if (Objects.nonNull(transportHeaders)) {
            transportHeaders.remove(HTTPConstants.HEADER_HOST);
        }
        //this code block is needed to replace the host header in service chaining with REQUEST_HOST_HEADER
        //adding host header since it is not available in response message.
        //otherwise Host header will not replaced after first call
        if (msgCtx.getProperty(NhttpConstants.REQUEST_HOST_HEADER) != null
                && !DataHolder.getInstance().isPreserveHttpHeader(HTTPConstants.HEADER_HOST)) {
            headers.set(HttpHeaderNames.HOST, (String) msgCtx.getProperty(NhttpConstants.REQUEST_HOST_HEADER));
            return;
        }

        if (port == 80 || port == 443) {
            headers.set(HttpHeaderNames.HOST, host);
        } else {
            headers.set(HttpHeaderNames.HOST, host + ":" + port);
        }
    }

    private void setContentTypeHeaderIfApplicable(MessageContext msgCtx, HttpCarbonMessage outboundRequest)
            throws AxisFault {
        Map transportHeaders = (Map) msgCtx.getProperty(MessageContext.TRANSPORT_HEADERS);
        String cType = RequestResponseUtils.getContentType(msgCtx,
                DataHolder.getInstance().isPreserveHttpHeader(HTTP.CONTENT_TYPE), transportHeaders);
        if (cType != null
                && !HTTPConstants.HTTP_METHOD_GET.equals((String) msgCtx.getProperty(BridgeConstants.HTTP_METHOD))
                && shouldOverwriteContentType(msgCtx, outboundRequest)) {
            String messageType = (String) msgCtx.getProperty(NhttpConstants.MESSAGE_TYPE);
            if (messageType != null) {
                // if multipart related message type and unless if message
                // not get build we should
                // skip of setting formatter specific content Type
                if (!messageType.contains(HTTPConstants.MEDIA_TYPE_MULTIPART_RELATED)
                        && !messageType.contains(HTTPConstants.MEDIA_TYPE_MULTIPART_FORM_DATA)) {
                    if (transportHeaders != null && !cType.isEmpty()) {
                        transportHeaders.put(HTTP.CONTENT_TYPE, cType);
                    }
                    outboundRequest.setHeader(HTTP.CONTENT_TYPE, cType);
                } else {
                    // if messageType is related to multipart and if message
                    // already built we need to set new
                    // boundary related content type at Content-Type header
                    boolean builderInvoked = Boolean.TRUE.equals(msgCtx
                            .getProperty(PassThroughConstants.MESSAGE_BUILDER_INVOKED));
                    if (builderInvoked) {
                        outboundRequest.setHeader(HTTP.CONTENT_TYPE, cType);
                    }
                }
            } else {
                outboundRequest.setHeader(HTTP.CONTENT_TYPE, cType);
            }
        }

        if ((PassThroughConstants.HTTP_GET.equals(msgCtx.getProperty(BridgeConstants.HTTP_METHOD))) ||
                (RelayUtils.isDeleteRequestWithoutPayload(msgCtx))) {
            MessageFormatter formatter = MessageProcessorSelector.getMessageFormatter(msgCtx);
            OMOutputFormat format = PassThroughTransportUtils.getOMOutputFormat(msgCtx);
            if (formatter != null && format != null) {
                outboundRequest.removeHeader(HTTP.CONTENT_TYPE);
            }
        }

        if (transportHeaders != null) {
            String trpContentType = (String) transportHeaders.get(HTTP.CONTENT_TYPE);
            if (trpContentType != null && !trpContentType.equals("")
                    && !isMultipartContent(trpContentType)) {
                outboundRequest.setHeader(HTTP.CONTENT_TYPE, trpContentType);
            }
        }
    }

    private void setKeepAliveConfig(MessageContext msgContext) {
        String noKeepAlive = (String) msgContext.getProperty(PassThroughConstants.NO_KEEPALIVE);
        keepAlive = !org.apache.axis2.Constants.VALUE_TRUE.equals(noKeepAlive)
                && !PassThroughConfiguration.getInstance().isKeepAliveDisabled();
    }

    private void setWSAActionIfApplicable(MessageContext msgCtx, HttpCarbonMessage httpCarbonMessage) {
        String soapAction = msgCtx.getSoapAction();
        if (soapAction == null) {
            soapAction = msgCtx.getWSAAction();
            //TODO: do we need this?
            msgCtx.getAxisOperation().getInputAction();
        }

        MessageFormatter messageFormatter =
                MessageFormatterDecoratorFactory.createMessageFormatterDecorator(msgCtx);
        if (msgCtx.isSOAP11() && soapAction != null && soapAction.length() > 0 && Objects.nonNull(messageFormatter)) {
            // Even if the headers is already present, no need to remove the header before adding the same header.
            // Whenever a header is added, it will replace the similar headers.
            httpCarbonMessage.setHeader(HTTPConstants.HEADER_SOAP_ACTION,
                    messageFormatter.formatSOAPAction(msgCtx, null, soapAction));
        }
    }

    /**
     * Check whether the we should overwrite the content type for the outgoing request.
     * @param msgContext MessageContext
     * @return whether to overwrite the content type for the outgoing request
     *
     */
    public static boolean shouldOverwriteContentType(MessageContext msgContext, HttpCarbonMessage outboundRequest) {
        boolean builderInvoked = Boolean.TRUE.equals(msgContext
                .getProperty(PassThroughConstants.MESSAGE_BUILDER_INVOKED));
        boolean noEntityBodySet =
                Boolean.TRUE.equals(msgContext.getProperty(PassThroughConstants.NO_ENTITY_BODY));

        // if contentTypeInRequest is true, that means it is set from the transport headers. that means the header
        // came in the source request.
        boolean contentTypeInRequest = outboundRequest.getHeader("Content-Type") != null
                || outboundRequest.getHeader("content-type") != null;
        boolean isDefaultContentTypeEnabled = false;
        ConfigurationContext configurationContext = msgContext.getConfigurationContext();
        if (configurationContext != null && configurationContext.getAxisConfiguration()
                .getParameter(NhttpConstants.REQUEST_CONTENT_TYPE) != null) {
            isDefaultContentTypeEnabled = true;
        }
        // If builder is not invoked, which means the passthrough scenario, we should overwrite the content-type
        // depending on the presence of the incoming content-type.
        // If builder is invoked and no entity body property is not set (which means there is a payload in the request)
        // we should consider overwriting the content-type.
        return (builderInvoked && !noEntityBodySet) || contentTypeInRequest || isDefaultContentTypeEnabled;
    }

    private boolean hasEntityBody(MessageContext msgCtx) {
        if ((PassThroughConstants.HTTP_GET.equals(msgCtx.getProperty(BridgeConstants.HTTP_METHOD)))
                || (RelayUtils.isDeleteRequestWithoutPayload(msgCtx))) {
            return false;
        }

        if (msgCtx.getEnvelope().getBody().getFirstElement() != null) {
            return true;
        }

        return !Boolean.TRUE.equals(msgCtx.getProperty(NhttpConstants.NO_ENTITY_BODY));
    }

    /**
     * Check whether the content type is multipart or not.
     * @param contentType Content-Type of the
     * @return true for multipart content types
     */
    public static boolean isMultipartContent(String contentType) {

        return contentType.contains(HTTPConstants.MEDIA_TYPE_MULTIPART_FORM_DATA)
                || contentType.contains(HTTPConstants.HEADER_ACCEPT_MULTIPART_RELATED);
    }

    public boolean isChunk() {

        return chunk;
    }

    public String getHttpVersion() {

        return httpVersion;
    }

    public String getHttpMethod() {

        return httpMethod;
    }

    public boolean isKeepAlive() {

        return keepAlive;
    }

    public HttpCarbonMessage getOutboundCarbonRequest() {
        return httpCarbonMessage;
    }
}
