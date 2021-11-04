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
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import org.apache.axiom.om.OMOutputFormat;
import org.apache.axis2.AxisFault;
import org.apache.axis2.addressing.AddressingConstants;
import org.apache.axis2.addressing.AddressingHelper;
import org.apache.axis2.addressing.EndpointReference;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.description.TransportOutDescription;
import org.apache.axis2.handlers.AbstractHandler;
import org.apache.axis2.transport.MessageFormatter;
import org.apache.axis2.transport.TransportSender;
import org.apache.axis2.transport.base.threads.WorkerPool;
import org.apache.axis2.transport.base.threads.WorkerPoolFactory;
import org.apache.axis2.transport.http.HTTPConstants;
import org.apache.axis2.util.MessageProcessorSelector;
import io.netty.handler.codec.http.HttpVersion;
import org.apache.http.protocol.HTTP;
import org.apache.log4j.Logger;
import org.apache.synapse.transport.netty.BridgeConstants;
import org.apache.synapse.transport.netty.util.DataHolder;
import org.apache.synapse.transport.netty.util.MessageUtils;
import org.apache.synapse.transport.netty.util.RequestUtils;
import org.apache.synapse.transport.nhttp.NhttpConstants;
import org.apache.synapse.transport.nhttp.util.MessageFormatterDecoratorFactory;
import org.apache.synapse.transport.passthru.PassThroughConstants;
import org.apache.synapse.transport.passthru.config.PassThroughConfiguration;
import org.apache.synapse.transport.passthru.util.PassThroughTransportUtils;
import org.apache.synapse.transport.passthru.util.RelayUtils;
import org.apache.synapse.transport.passthru.util.TargetRequestFactory;
import org.wso2.caching.CachingConstants;
import org.wso2.caching.digest.DigestGenerator;
import org.wso2.transport.http.netty.contract.Constants;
import org.wso2.transport.http.netty.contract.HttpClientConnector;
import org.wso2.transport.http.netty.contract.HttpResponseFuture;
import org.wso2.transport.http.netty.contract.HttpWsConnectorFactory;
import org.wso2.transport.http.netty.contract.config.ChunkConfig;
import org.wso2.transport.http.netty.contract.config.SenderConfiguration;
import org.wso2.transport.http.netty.contractimpl.DefaultHttpWsConnectorFactory;
import org.wso2.transport.http.netty.contractimpl.sender.channel.pool.ConnectionManager;
import org.wso2.transport.http.netty.contractimpl.sender.channel.pool.PoolConfiguration;
import org.wso2.transport.http.netty.message.HttpCarbonMessage;
import org.wso2.transport.http.netty.message.HttpCarbonRequest;
import org.wso2.transport.http.netty.message.HttpMessageDataStreamer;
import org.wso2.transport.http.netty.message.PooledDataStreamerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import javax.xml.stream.XMLStreamException;

/**
 * {@code AxisToClientConnectorBridge} receives the outgoing axis2 {@code MessageContext}, convert it into a
 * {@code HttpCarbonMessage} and deliver it to the Http Client connector.
 */
public class Axis2HttpTransportSender extends AbstractHandler implements TransportSender {

    private static final Logger LOG = Logger.getLogger(Axis2HttpTransportSender.class);
    private HttpClientConnector clientConnector;

    private WorkerPool workerPool;

    private final DigestGenerator digestGenerator  = CachingConstants.DEFAULT_XML_IDENTIFIER;

    @Override
    public void init(ConfigurationContext configurationContext, TransportOutDescription transportOutDescription) {

        HttpWsConnectorFactory httpWsConnectorFactory = new DefaultHttpWsConnectorFactory();
        SenderConfiguration senderConfiguration = new SenderConfiguration();
        ConnectionManager connectionManager = new ConnectionManager(new PoolConfiguration());
        clientConnector = httpWsConnectorFactory
                .createHttpClientConnector(new HashMap<>(), senderConfiguration, connectionManager);
        workerPool = WorkerPoolFactory.getWorkerPool(BridgeConstants.DEFAULT_WORKER_POOL_SIZE_CORE,
                BridgeConstants.DEFAULT_WORKER_POOL_SIZE_MAX,
                BridgeConstants.DEFAULT_WORKER_THREAD_KEEPALIVE_SEC,
                BridgeConstants.DEFAULT_WORKER_POOL_QUEUE_LENGTH,
                BridgeConstants.HTTP_WORKER_THREAD_GROUP_NAME,
                BridgeConstants.HTTP_WORKER_THREAD_ID);
    }

    @Override
    public InvocationResponse invoke(MessageContext msgCtx) throws AxisFault {

        Boolean noEntityBody = (Boolean) msgCtx.getProperty(PassThroughConstants.NO_ENTITY_BODY);
        if (msgCtx.getEnvelope().getBody().getFirstElement() != null) {
            noEntityBody = false;
        }
        if ((Objects.isNull(noEntityBody) || !noEntityBody)) {
            msgCtx.setProperty(BridgeConstants.MESSAGE_BUILDER_INVOKED, true);
        }

        HttpCarbonMessage originalCarbonMessage =
                (HttpCarbonMessage) msgCtx.getProperty(BridgeConstants.HTTP_CARBON_MESSAGE);
        if (originalCarbonMessage == null) {
            LOG.info(BridgeConstants.BRIDGE_LOG_PREFIX + "Carbon Message not found, " +
                    "sending requests originated from non HTTP transport is not supported yet");
            return InvocationResponse.ABORT;
        }

        RequestUtils.removeUnwantedHeaders(msgCtx);

        if (AddressingHelper.isReplyRedirected(msgCtx) && !msgCtx.getReplyTo().hasNoneAddress()) {

            msgCtx.setProperty(PassThroughConstants.IGNORE_SC_ACCEPTED, org.apache.axis2.Constants.VALUE_TRUE);
        }

        HttpCarbonMessage outboundHttpCarbonMsg;
//        if (Boolean.TRUE.equals(msgCtx.getProperty(BridgeConstants.MESSAGE_BUILDER_INVOKED))) {
//            outboundHttpCarbonMsg = RequestUtils.convertAxis2MsgCtxToCarbonMsg(msgCtx);
//        } else {
//            outboundHttpCarbonMsg = RequestUtils.createOutboundCarbonMsg(originalCarbonMessage, msgCtx);
//        }

//        msgCtx.setProperty(BridgeConstants.HTTP_CARBON_MESSAGE, outboundHttpCarbonMsg);

        EndpointReference destinationEPR = RequestUtils.getDestinationEPR(msgCtx);
        if (destinationEPR != null) {
            if (destinationEPR.hasNoneAddress()) {
                handleException("Cannot send message to " + AddressingConstants.Final.WSA_NONE_URI);
            }
            try {
                URL destinationURL = new URL(destinationEPR.getAddress());
//                sendForward(msgCtx, originalCarbonMessage, destinationURL);
            } catch (MalformedURLException e) {
                handleException("Malformed Endpoint url found", e);
            }
        } else { // Response submission back to the client
            sendBack(msgCtx, originalCarbonMessage);
        }
        return InvocationResponse.CONTINUE;
    }

    private HttpMessageDataStreamer getHttpMessageDataStreamer(HttpCarbonMessage outboundRequestMsg) {

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

    // response submission back to the client (client <-- transport)
    private void sendBack(MessageContext msgCtx, HttpCarbonMessage inboundCarbonMessage) throws AxisFault {
        HttpCarbonRequest clientRequest =
                (HttpCarbonRequest) msgCtx.getProperty(BridgeConstants.HTTP_CLIENT_REQUEST_CARBON_MESSAGE);
        if (clientRequest == null) {
            throw new AxisFault("Original client request not found");
        }

        // Handle ETag caching
        if (msgCtx.getProperty(PassThroughConstants.HTTP_ETAG_ENABLED) != null
                && (Boolean) msgCtx.getProperty(PassThroughConstants.HTTP_ETAG_ENABLED)) {
            try {
                RelayUtils.buildMessage(msgCtx);
            } catch (IOException e) {
                handleException("IO Error occurred while building the message", e);
            } catch (XMLStreamException e) {
                handleException("XML Error occurred while building the message", e);
            }
            String hash = digestGenerator.getDigest(msgCtx);
            Map headers = (Map) msgCtx.getProperty(MessageContext.TRANSPORT_HEADERS);
            headers.put(org.apache.http.HttpHeaders.ETAG, "\"" + hash + "\"");
        }

        if (msgCtx.getProperty(org.apache.axis2.Constants.Configuration.ENABLE_MTOM) != null
                && !Boolean.TRUE.equals(msgCtx.getProperty(PassThroughConstants.MESSAGE_BUILDER_INVOKED))) {
            try {
                RelayUtils.buildMessage(msgCtx);
            } catch (IOException e) {
                handleException("IO Error occurred while building the message", e);
            } catch (XMLStreamException e) {
                handleException("XML Error occurred while building the message", e);
            }
        }

        HttpCarbonMessage outboundResponseCarbonMessage =
                RequestUtils.createOutboundResponse(inboundCarbonMessage, msgCtx);
        msgCtx.setProperty(BridgeConstants.HTTP_CARBON_MESSAGE, outboundResponseCarbonMessage);

        try {
            clientRequest.respond(outboundResponseCarbonMessage);

            if (Boolean.TRUE.equals((msgCtx.getProperty(BridgeConstants.MESSAGE_BUILDER_INVOKED)))) {
                final HttpMessageDataStreamer httpMessageDataStreamer = getHttpMessageDataStreamer(outboundResponseCarbonMessage);
                OutputStream outputStream = httpMessageDataStreamer.getOutputStream();
                OMOutputFormat format = MessageUtils.getOMOutputFormat(msgCtx);
                try {
                    MessageFormatter messageFormatter = MessageUtils.getMessageFormatter(msgCtx);
                    messageFormatter.writeTo(msgCtx, format, outputStream, false);
                } catch (AxisFault axisFault) {
                    LOG.error(BridgeConstants.BRIDGE_LOG_PREFIX + axisFault.getMessage());
                } finally {
                    try {
                        outputStream.close();
                    } catch (IOException e) {
                        LOG.error(BridgeConstants.BRIDGE_LOG_PREFIX + e.getMessage());
                    }
                }
            } else {
                HttpContent httpContent = inboundCarbonMessage.getHttpContent();
                outboundResponseCarbonMessage.addHttpContent(httpContent);
            }
        } catch (Exception e) {
            LOG.error(BridgeConstants.BRIDGE_LOG_PREFIX + "Error occurred while submitting the response " +
                    "back to the client", e);
        }
    }

    // outgoing request (transport --> remote)
    private void sendForward(MessageContext msgCtx, HttpCarbonMessage inboundCarbonMessage,
                             HttpCarbonMessage httpCarbonMessage, URL url) throws AxisFault {

        // TODO: Check if this is needed
//        // NOTE:this a special case where, when the backend service expects content-length but,there is no
//        // desire that the message should be build, if FORCE_HTTP_CONTENT_LENGTH and
//        // COPY_CONTENT_LENGTH_FROM_INCOMING, we assume that the content coming from the client side has not
//        // been changed
//        boolean forceContentLength = msgCtx.isPropertyTrue(NhttpConstants.FORCE_HTTP_CONTENT_LENGTH);
//        boolean forceContentLengthCopy = msgCtx
//                .isPropertyTrue(PassThroughConstants.COPY_CONTENT_LENGTH_FROM_INCOMING);
//
//        if (forceContentLength && forceContentLengthCopy
//                && msgCtx.getProperty(PassThroughConstants.ORGINAL_CONTEN_LENGTH) != null) {
//            msgCtx.setProperty(PassThroughConstants.PASSTROUGH_MESSAGE_LENGTH, Long.parseLong(
//                    (String) msgCtx.getProperty(PassThroughConstants.ORGINAL_CONTEN_LENGTH)));
//        }

        HttpCarbonMessage outboundHttpCarbonMessage = new HttpCarbonMessage(new DefaultHttpRequest(HttpVersion.HTTP_1_1,
                HttpMethod.POST, ""));

        int port = getOutboundReqPort(url);
        String host = url.getHost();
        setOutboundReqProperties(httpCarbonMessage, url, port, host, msgCtx);
        setOutboundReqHeaders(httpCarbonMessage, port, host, msgCtx);


        String httpMethod = (String) msgCtx.getProperty(BridgeConstants.HTTP_METHOD);
        if (Objects.isNull(httpMethod)) {
            httpMethod = HTTPConstants.HTTP_METHOD_POST;
        }
        outboundHttpCarbonMessage.setHttpMethod(httpMethod);


        // version
        String forceHttp10 = (String) msgCtx.getProperty(PassThroughConstants.FORCE_HTTP_1_0);
        if (org.apache.axis2.Constants.VALUE_TRUE.equals(forceHttp10)) {
            outboundHttpCarbonMessage.setHttpVersion("1.0");
            // need to set the version in the client connector
        } else {
            outboundHttpCarbonMessage.setHttpVersion("1.1");
        }

        // keep alive
        String noKeepAlive = (String) msgCtx.getProperty(PassThroughConstants.NO_KEEPALIVE);
        if (org.apache.axis2.Constants.VALUE_TRUE.equals(noKeepAlive) || PassThroughConfiguration.getInstance().isKeepAliveDisabled()) {
            outboundHttpCarbonMessage.setKeepAlive(false);
            // need to set in the carbon msg
        } else {
            outboundHttpCarbonMessage.setKeepAlive(true);
        }

        // chunk
        String disableChunking = (String) msgCtx.getProperty(PassThroughConstants.DISABLE_CHUNKING);
        if (org.apache.axis2.Constants.VALUE_TRUE.equals(disableChunking)) {
            outboundHttpCarbonMessage.setProperty(BridgeConstants.CHUNKING_CONFIG, ChunkConfig.NEVER);
            // need to set the version in the client connector
        } else {
            outboundHttpCarbonMessage.setProperty(BridgeConstants.CHUNKING_CONFIG, ChunkConfig.ALWAYS);
        }

        // Add excess response header.
        addExcessHeaders(msgCtx, outboundHttpCarbonMessage);

        // Check HTTP method is GET or DELETE with no body
        if (RequestUtils.ignoreMessageBody(msgCtx)) {
            return;
        }

        ////// inside targetRequest

        //fix GET request empty body
        if ((PassThroughConstants.HTTP_GET.equals(requestMsgCtx.getProperty(org.apache.axis2.Constants.Configuration.HTTP_METHOD))) ||
                (RelayUtils.isDeleteRequestWithoutPayload(requestMsgCtx))) {
            hasEntityBody = false;
            MessageFormatter formatter = MessageProcessorSelector.getMessageFormatter(requestMsgCtx);
            OMOutputFormat format = PassThroughTransportUtils.getOMOutputFormat(requestMsgCtx);
            if (formatter != null && format != null) {
                URL _url = formatter.getTargetAddress(requestMsgCtx, format, url);
                if (_url != null && !_url.toString().isEmpty()) {
                    if (requestMsgCtx.getProperty(NhttpConstants.POST_TO_URI) != null
                            && Boolean.TRUE.toString().equals(requestMsgCtx.getProperty(NhttpConstants.POST_TO_URI))) {
                        path = _url.toString();
                    } else {
                        path = _url.getPath()
                                + ((_url.getQuery() != null && !_url.getQuery().isEmpty())
                                ? ("?" + _url.getQuery())
                                : "");
                    }

                }
                headers.remove(HTTP.CONTENT_TYPE);
            }
        }

        Object o = requestMsgCtx.getProperty(MessageContext.TRANSPORT_HEADERS);
        if (o != null && o instanceof TreeMap) {
            Map _headers = (Map) o;
            String trpContentType = (String) _headers.get(HTTP.CONTENT_TYPE);
            if (trpContentType != null && !trpContentType.equals("")
                    && !TargetRequestFactory.isMultipartContent(trpContentType)) {
                addHeader(HTTP.CONTENT_TYPE, trpContentType);
            }

        }

        if (hasEntityBody) {
            boolean forceContentLength = requestMsgCtx.isPropertyTrue(
                    NhttpConstants.FORCE_HTTP_CONTENT_LENGTH);
            boolean forceContentLengthCopy = requestMsgCtx.isPropertyTrue(
                    PassThroughConstants.COPY_CONTENT_LENGTH_FROM_INCOMING);

            if (forceContentLength) {
                entity.setChunked(false);
                if (forceContentLengthCopy && contentLength != -1) {
                    entity.setContentLength(contentLength);
                }
            } else {
                if (contentLength != -1) {
                    entity.setChunked(false);
                    entity.setContentLength(contentLength);
                } else {
                    entity.setChunked(chunk);
                }
            }

        }

        //setup wsa action..
        setWSAActionIfApplicable(msgCtx, outboundHttpCarbonMessage);

        //Chunking is not performed for request has "http 1.0" and "GET" http method
        if (!((request.getProtocolVersion().equals(HttpVersion.HTTP_1_0)) ||
                (PassThroughConstants.HTTP_GET.equals(requestMsgCtx.getProperty(org.apache.axis2.Constants.Configuration.HTTP_METHOD))) ||
                RelayUtils.isDeleteRequestWithoutPayload(requestMsgCtx) || !(hasEntityBody))) {
            this.processChunking(conn, requestMsgCtx);
        }

        if (!keepAlive) {
            request.setHeader(HTTP.CONN_DIRECTIVE, HTTP.CONN_CLOSE);
        }


        HttpResponseFuture future = clientConnector.send(httpCarbonMessage);

        future.setHttpConnectorListener(new PassThroughHttpOutboundRespListener(workerPool, msgCtx));

        // serialize
        if (Boolean.TRUE.equals(msgCtx.getProperty(BridgeConstants.MESSAGE_BUILDER_INVOKED))) {
            final HttpMessageDataStreamer outboundMsgDataStreamer = getHttpMessageDataStreamer(httpCarbonMessage);
            final OutputStream outputStream = outboundMsgDataStreamer.getOutputStream();
            OMOutputFormat format = MessageUtils.getOMOutputFormat(msgCtx);
            try {
                MessageFormatter messageFormatter = MessageUtils.getMessageFormatter(msgCtx);
                messageFormatter.writeTo(msgCtx, format, outputStream, false);
            } catch (AxisFault axisFault) {
                LOG.error(BridgeConstants.BRIDGE_LOG_PREFIX + axisFault.getMessage());
            } finally {
                try {
                    outputStream.close();
                } catch (IOException e) {
                    LOG.error(BridgeConstants.BRIDGE_LOG_PREFIX + e.getMessage());
                }
            }
        }
    }

    public void addTransportHeaders(MessageContext msgCtx, HttpCarbonMessage outboundRequest) {
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

    private static void addExcessHeaders(MessageContext msgCtx, HttpCarbonMessage outboundHttpCarbonMessage) {
        Map excessHeaders = (Map) msgCtx.getProperty(NhttpConstants.EXCESS_TRANSPORT_HEADERS);
        if (excessHeaders != null) {
            for (Iterator iterator = excessHeaders.keySet().iterator(); iterator.hasNext();) {
                String key = (String) iterator.next();
                for (String excessVal : (Collection<String>) excessHeaders.get(key)) {
                    outboundHttpCarbonMessage.setHeader(key, (String) excessVal);
                }
            }
        }
    }


    @Override
    public void cleanup(MessageContext messageContext) {

    }

    @Override
    public void stop() {

    }

    private URL getDestinationURL(MessageContext msgContext) throws AxisFault {

        String transportURL = (String) msgContext.getProperty(
                org.apache.axis2.Constants.Configuration.TRANSPORT_URL);

        EndpointReference endpointReference;
        if (transportURL != null) {
            endpointReference = new EndpointReference(transportURL);
        } else if ((msgContext.getTo() != null) && !msgContext.getTo().hasAnonymousAddress()) {
            endpointReference = msgContext.getTo();
        } else {
            return null;
        }

        try {
            return new URL(endpointReference.getAddress());
        } catch (MalformedURLException e) {
            throw new AxisFault("Malformed Endpoint url found", e);
        }
    }


    private void setOutboundReqHeaders(HttpCarbonMessage outboundRequest, int port, String host,
                                       MessageContext msgCtx) {

        HttpHeaders headers = outboundRequest.getHeaders();
        setHostHeader(host, port, headers, msgCtx);
        addTransportHeaders(msgCtx, outboundRequest);
        setContentTypeHeaderIfApplicable(msgCtx, outboundRequest);
        addExcessHeaders(msgCtx, outboundRequest);
    }

    private void setOutboundReqProperties(HttpCarbonMessage outboundRequest, URL url, int port, String host,
                                          MessageContext msgCtx) {

        outboundRequest.setProperty(Constants.HTTP_HOST, host);
        outboundRequest.setProperty(Constants.HTTP_PORT, port);
        String outboundReqPath = getOutboundReqPath(url, msgCtx);
        outboundRequest.setProperty(Constants.TO, outboundReqPath);
        outboundRequest.setProperty(Constants.PROTOCOL, url.getProtocol() != null ? url.getProtocol() : "http");
    }

    private void setHostHeader(String host, int port, HttpHeaders headers, MessageContext msgCtx) {

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
        // TODO: need to remove the HOST header from transport headers
    }

    private String getOutboundReqPath(URL url, MessageContext msgCtx) {

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
        String path = fullUrl || (route.getProxyHost() != null && !route.isTunnelled()) ?
                url.toString() : url.getPath() +
                (url.getQuery() != null ? "?" + url.getQuery() : "");

        return path;
    }

    private int getOutboundReqPort(URL url) {

        int port = 80;
        if (url.getPort() != -1) {
            port = url.getPort();
        } else if (url.getProtocol().equalsIgnoreCase(Constants.HTTPS_SCHEME)) {
            port = 443;
        }
        return port;
    }

    private void setContentTypeHeaderIfApplicable(MessageContext msgCtx, HttpCarbonMessage outboundRequest) {
        Map transportHeaders = (Map) msgCtx.getProperty(MessageContext.TRANSPORT_HEADERS);
        String cType = RequestUtils.getContentType(msgCtx,
                DataHolder.getInstance().isPreserveHttpHeader(HTTP.CONTENT_TYPE), transportHeaders);
        if (cType != null
                && !HTTPConstants.HTTP_METHOD_GET.equals(outboundRequest.getHttpMethod())
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
                    && !TargetRequestFactory.isMultipartContent(trpContentType)) {
                outboundRequest.setHeader(HTTP.CONTENT_TYPE, trpContentType);
            }
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

    private void setWSAActionIfApplicable(MessageContext msgCtx, HttpCarbonMessage httpCarbonMessage) {
        //setup wsa action..
        String soapAction = msgCtx.getSoapAction();
        if (soapAction == null) {
            soapAction = msgCtx.getWSAAction();
            msgCtx.getAxisOperation().getInputAction();
        }

        if (msgCtx.isSOAP11() && soapAction != null &&
                soapAction.length() > 0) {
            // TODO: check if I can remove the header without checking
            String existingHeader =
                    httpCarbonMessage.getHeader(HTTPConstants.HEADER_SOAP_ACTION);
            if (existingHeader != null) {
                httpCarbonMessage.removeHeader(existingHeader);
            }
            MessageFormatter messageFormatter =
                    MessageFormatterDecoratorFactory.createMessageFormatterDecorator(msgCtx);
            httpCarbonMessage.setHeader(HTTPConstants.HEADER_SOAP_ACTION,
                    messageFormatter.formatSOAPAction(msgCtx, null, soapAction));
        }
    }

    private void handleException(String s, Exception e) throws AxisFault {
        LOG.error(s, e);
        throw new AxisFault(s, e);
    }

    private void handleException(String msg) throws AxisFault {
        LOG.error(msg);
        throw new AxisFault(msg);
    }
}
