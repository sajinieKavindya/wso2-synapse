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

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import org.apache.axiom.om.OMOutputFormat;
import org.apache.axis2.AxisFault;
import org.apache.axis2.addressing.AddressingConstants;
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
import org.apache.log4j.Logger;
import org.apache.synapse.transport.netty.BridgeConstants;
import org.apache.synapse.transport.netty.util.MessageUtils;
import org.apache.synapse.transport.netty.util.RequestUtils;
import org.apache.synapse.transport.nhttp.util.MessageFormatterDecoratorFactory;
import org.apache.synapse.transport.passthru.PassThroughConstants;
import org.apache.synapse.transport.passthru.util.RelayUtils;
import org.wso2.caching.CachingConstants;
import org.wso2.caching.digest.DigestGenerator;
import org.wso2.transport.http.netty.contract.Constants;
import org.wso2.transport.http.netty.contract.HttpClientConnector;
import org.wso2.transport.http.netty.contract.HttpResponseFuture;
import org.wso2.transport.http.netty.contract.HttpWsConnectorFactory;
import org.wso2.transport.http.netty.contract.config.SenderConfiguration;
import org.wso2.transport.http.netty.contractimpl.DefaultHttpWsConnectorFactory;
import org.wso2.transport.http.netty.contractimpl.sender.channel.pool.ConnectionManager;
import org.wso2.transport.http.netty.message.HttpCarbonMessage;
import org.wso2.transport.http.netty.message.HttpCarbonRequest;
import org.wso2.transport.http.netty.message.HttpMessageDataStreamer;
import org.wso2.transport.http.netty.message.PooledDataStreamerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
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
        ConnectionManager connectionManager = new ConnectionManager(senderConfiguration.getPoolConfiguration());
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
        // TODO: remove unwanted HTTP headers (if any from the current message) -- 1

        // TODO: handle redirect reply scenario -- 2

        Boolean noEntityBody = (Boolean) msgCtx.getProperty(PassThroughConstants.NO_ENTITY_BODY);

        if (msgCtx.getEnvelope().getBody().getFirstElement() != null) {
            noEntityBody = false;
        }

//        Boolean contentAwareMediatorFound = (Boolean) msgCtx.getProperty("CONTENT_AWARE_MEDIATOR_FOUND");

        if ((Objects.isNull(noEntityBody) || !noEntityBody)) {
            msgCtx.setProperty(BridgeConstants.MESSAGE_BUILDER_INVOKED, true);
        }

        HttpCarbonMessage originalCarbonMessage =
                (HttpCarbonMessage) msgCtx.getProperty(BridgeConstants.HTTP_CARBON_MESSAGE);

        HttpCarbonMessage outboundHttpCarbonMsg;
        if (Boolean.TRUE.equals(msgCtx.getProperty(BridgeConstants.MESSAGE_BUILDER_INVOKED))) {
            outboundHttpCarbonMsg = RequestUtils.convertAxis2MsgCtxToCarbonMsg(msgCtx);
        } else {
            outboundHttpCarbonMsg = RequestUtils.createOutboundCarbonMsg(originalCarbonMessage, msgCtx);
        }

        if (originalCarbonMessage == null) {
            LOG.info(BridgeConstants.BRIDGE_LOG_PREFIX + "Carbon Message not found, " +
                    "sending " +
                    "requests originated from non HTTP transport is not supported yet");
            return InvocationResponse.ABORT;
        }

        msgCtx.setProperty(BridgeConstants.HTTP_CARBON_MESSAGE, outboundHttpCarbonMsg);

        EndpointReference destinationEPR = RequestUtils.getDestinationEPR(msgCtx);
        if (destinationEPR != null) {
            if (destinationEPR.hasNoneAddress()) {
                handleException("Cannot send message to " + AddressingConstants.Final.WSA_NONE_URI);
            }
            try {
                URL destinationURL = new URL(destinationEPR.getAddress());
                sendForward(msgCtx, outboundHttpCarbonMsg, destinationURL);
            } catch (MalformedURLException e) {
                handleException("Malformed Endpoint url found", e);
            }
        } else { // Response submission back to the client
            sendBack(msgCtx, outboundHttpCarbonMsg);
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
    private void sendBack(MessageContext msgCtx, HttpCarbonMessage httpCarbonMessage) throws AxisFault {
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

        RequestUtils.createOutboundResponse(httpCarbonMessage, msgCtx);

        try {
            clientRequest.respond(httpCarbonMessage);

            if (Boolean.TRUE.equals((msgCtx.getProperty(BridgeConstants.MESSAGE_BUILDER_INVOKED)))) {
                final HttpMessageDataStreamer httpMessageDataStreamer = getHttpMessageDataStreamer(httpCarbonMessage);
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
            }
        } catch (Exception e) {
            LOG.error(BridgeConstants.BRIDGE_LOG_PREFIX + "Error occurred while submitting the response " +
                    "back to the client", e);
        }
    }

    // outgoing request (transport --> remote)
    private void sendForward(MessageContext msgCtx, HttpCarbonMessage httpCarbonMessage, URL url) {

        // TODO: Check FORCE_HTTP_CONTENT_LENGTH & COPY_CONTENT_LENGTH_FROM_INCOMING and set PASSTROUGH_MESSAGE_LENGTH

        int port = getOutboundReqPort(url);
        String host = url.getHost();
        setOutboundReqHeaders(httpCarbonMessage, port, host);
        setOutboundReqProperties(httpCarbonMessage, url, port, host);
        httpCarbonMessage.getNettyHttpRequest().setUri(url.toString());

        String soapAction = msgCtx.getSoapAction();
        if (soapAction == null) {
            soapAction = msgCtx.getWSAAction();
            msgCtx.getAxisOperation().getInputAction();
        }

        if (msgCtx.isSOAP11() && soapAction != null &&
                soapAction.length() > 0) {
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


    private void setOutboundReqHeaders(HttpCarbonMessage outboundRequest, int port, String host) {

        HttpHeaders headers = outboundRequest.getHeaders();
        setHostHeader(host, port, headers);
    }

    private void setOutboundReqProperties(HttpCarbonMessage outboundRequest, URL url, int port, String host) {

        outboundRequest.setProperty(Constants.HTTP_HOST, host);
        outboundRequest.setProperty(Constants.HTTP_PORT, port);
        String outboundReqPath = getOutboundReqPath(url);
        outboundRequest.setProperty(Constants.TO, outboundReqPath);
        outboundRequest.setProperty(Constants.PROTOCOL, url.getProtocol());
    }

    private void setHostHeader(String host, int port, HttpHeaders headers) {

        if (port == 80 || port == 443) {
            headers.set(HttpHeaderNames.HOST, host);
        } else {
            headers.set(HttpHeaderNames.HOST, host + ":" + port);
        }
    }

    private String getOutboundReqPath(URL url) {

        String toPath = url.getPath();
        String query = url.getQuery();
        if (query != null) {
            toPath = toPath + "?" + query;
        }
        return toPath;
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

    private void handleException(String s, Exception e) throws AxisFault {
        LOG.error(s, e);
        throw new AxisFault(s, e);
    }

    private void handleException(String msg) throws AxisFault {
        LOG.error(msg);
        throw new AxisFault(msg);
    }
}
