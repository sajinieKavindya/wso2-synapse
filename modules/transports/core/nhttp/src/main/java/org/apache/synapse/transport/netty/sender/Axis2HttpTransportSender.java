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

import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;
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
import org.apache.log4j.Logger;
import org.apache.synapse.commons.handlers.MessagingHandler;
import org.apache.synapse.transport.netty.BridgeConstants;
import org.apache.synapse.transport.netty.config.TargetConfiguration;
import org.apache.synapse.transport.netty.util.MessageUtils;
import org.apache.synapse.transport.netty.util.RequestResponseUtils;
import org.apache.synapse.transport.nhttp.NhttpConstants;
import org.apache.synapse.transport.passthru.PassThroughConstants;
import org.apache.synapse.transport.passthru.util.RelayUtils;
import org.wso2.caching.CachingConstants;
import org.wso2.caching.digest.DigestGenerator;
import org.wso2.transport.http.netty.contract.HttpClientConnector;
import org.wso2.transport.http.netty.contract.HttpResponseFuture;
import org.wso2.transport.http.netty.contract.HttpWsConnectorFactory;
import org.wso2.transport.http.netty.contract.config.ChunkConfig;
import org.wso2.transport.http.netty.contract.config.KeepAliveConfig;
import org.wso2.transport.http.netty.contract.config.SenderConfiguration;
import org.wso2.transport.http.netty.contractimpl.DefaultHttpWsConnectorFactory;
import org.wso2.transport.http.netty.contractimpl.sender.channel.pool.ConnectionManager;
import org.wso2.transport.http.netty.contractimpl.sender.channel.pool.PoolConfiguration;
import org.wso2.transport.http.netty.message.HttpCarbonMessage;
import org.wso2.transport.http.netty.message.HttpCarbonRequest;
import org.wso2.transport.http.netty.message.HttpMessageDataStreamer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.xml.stream.XMLStreamException;

/**
 * {@code AxisToClientConnectorBridge} receives the outgoing axis2 {@code MessageContext}, convert it into a
 * {@code HttpCarbonMessage} and deliver it to the Http Client connector.
 */
public class Axis2HttpTransportSender extends AbstractHandler implements TransportSender {

    private static final Logger LOG = Logger.getLogger(Axis2HttpTransportSender.class);

    private WorkerPool workerPool;

    private final DigestGenerator digestGenerator  = CachingConstants.DEFAULT_XML_IDENTIFIER;

    ConnectionManager connectionManager;

    HttpWsConnectorFactory httpWsConnectorFactory;

    private List<MessagingHandler> messagingHandlers;

    private TargetConfiguration targetConfiguration;

    public Axis2HttpTransportSender() {}

    public Axis2HttpTransportSender(List<MessagingHandler> messagingHandlers) {
        this.messagingHandlers = messagingHandlers;
    }

    @Override
    public void init(ConfigurationContext configurationContext, TransportOutDescription transportOutDescription)
            throws AxisFault {

        httpWsConnectorFactory = new DefaultHttpWsConnectorFactory();
        connectionManager = new ConnectionManager(new PoolConfiguration());
        targetConfiguration = new TargetConfiguration(configurationContext, transportOutDescription, messagingHandlers);
        targetConfiguration.build();
        workerPool = targetConfiguration.getWorkerPool();
    }

    @Override
    public InvocationResponse invoke(MessageContext msgCtx) throws AxisFault {

        Boolean noEntityBody = (Boolean) msgCtx.getProperty(PassThroughConstants.NO_ENTITY_BODY);

        // check if only the second condition is enough to set the message_builder_invoked property true
        if ((Objects.isNull(noEntityBody) || !noEntityBody)
                && msgCtx.getEnvelope().getBody().getFirstElement() != null) {
            msgCtx.setProperty(BridgeConstants.MESSAGE_BUILDER_INVOKED, true);
        }

        HttpCarbonMessage originalCarbonMessage =
                (HttpCarbonMessage) msgCtx.getProperty(BridgeConstants.HTTP_CARBON_MESSAGE);

        RequestResponseUtils.removeUnwantedHeaders(msgCtx);

        if (AddressingHelper.isReplyRedirected(msgCtx) && !msgCtx.getReplyTo().hasNoneAddress()) {
            msgCtx.setProperty(PassThroughConstants.IGNORE_SC_ACCEPTED, org.apache.axis2.Constants.VALUE_TRUE);
        }

        EndpointReference destinationEPR = RequestResponseUtils.getDestinationEPR(msgCtx);
        if (destinationEPR != null) {
            if (destinationEPR.hasNoneAddress()) {
                handleException("Cannot send message to " + AddressingConstants.Final.WSA_NONE_URI);
            }
            try {
                URL destinationURL = new URL(destinationEPR.getAddress());
                sendForward(msgCtx, originalCarbonMessage, destinationURL);
            } catch (MalformedURLException e) {
                handleException("Malformed Endpoint url found", e);
            } catch (IOException e) {
                // TODO: this is a temporary fix. need to carefully handle the exception
                handleException("IOException found", e);
            }
        } else { // Response submission back to the client
//            if (originalCarbonMessage == null) {
//                LOG.info(BridgeConstants.BRIDGE_LOG_PREFIX + "Carbon Message not found, " +
//                        "sending requests originated from non HTTP transport is not supported yet");
//                return InvocationResponse.ABORT;
//            }
            try {
                sendBack(msgCtx, originalCarbonMessage);
            } catch (IOException e) {
                handleException("IOException found", e);
            }
        }
        return InvocationResponse.CONTINUE;
    }

    // response submission back to the client (client <-- transport)
    private void sendBack(MessageContext msgCtx, HttpCarbonMessage inboundCarbonMessage) throws IOException {
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

        SourceResponse sourceResponse = new SourceResponse();
        HttpCarbonMessage outboundResponseCarbonMessage =
                sourceResponse.getOutboundResponseCarbonMessage(msgCtx, inboundCarbonMessage);

//        HttpCarbonMessage outboundResponseCarbonMessage =
//                RequestResponseUtils.createOutboundResponse(inboundCarbonMessage, msgCtx);
//        msgCtx.setProperty(BridgeConstants.HTTP_CARBON_MESSAGE, outboundResponseCarbonMessage);

        try {
            clientRequest.respond(outboundResponseCarbonMessage);
            // TODO: check if we need to write empty http content at some point
            // inboundCarbonMessage = null means, the final call was not initiated from http protocol
            if (Boolean.TRUE.equals(msgCtx.getProperty(NhttpConstants.NO_ENTITY_BODY))
                    || inboundCarbonMessage == null) {
                final HttpMessageDataStreamer httpMessageDataStreamer =
                        RequestResponseUtils.getHttpMessageDataStreamer(outboundResponseCarbonMessage);
                OutputStream outputStream = httpMessageDataStreamer.getOutputStream();
                outputStream.write(new byte[0]);
                outputStream.close();
                return;
            }

            if (Boolean.TRUE.equals((msgCtx.getProperty(BridgeConstants.MESSAGE_BUILDER_INVOKED)))) {
                final HttpMessageDataStreamer httpMessageDataStreamer =
                        RequestResponseUtils.getHttpMessageDataStreamer(outboundResponseCarbonMessage);
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
                do {
                    HttpContent httpContent = inboundCarbonMessage.getHttpContent();
                    outboundResponseCarbonMessage.addHttpContent(httpContent);
                    if (httpContent instanceof LastHttpContent) {
                        break;
                    }
                } while (true);
            }

        } catch (Exception e) {
            LOG.error(BridgeConstants.BRIDGE_LOG_PREFIX + "Error occurred while submitting the response " +
                    "back to the client", e);
        }
    }

    // outgoing request (transport --> remote)
    private void sendForward(MessageContext msgCtx, HttpCarbonMessage inboundCarbonMessage, URL url)
            throws IOException {

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

        TargetRequest targetRequest = new TargetRequest();
        HttpCarbonMessage outboundRequest = targetRequest.getOutboundRequestCarbonMessage(msgCtx, url);

        SenderConfiguration senderConfiguration = new SenderConfiguration();
        senderConfiguration.setHttpVersion(targetRequest.getHttpVersion());
        if (targetRequest.isChunk()) {
            senderConfiguration.setChunkingConfig(ChunkConfig.ALWAYS);
        } else {
            senderConfiguration.setChunkingConfig(ChunkConfig.NEVER);
        }

        if (targetRequest.isKeepAlive()) {
            senderConfiguration.setKeepAliveConfig(KeepAliveConfig.ALWAYS);
        } else {
            senderConfiguration.setKeepAliveConfig(KeepAliveConfig.NEVER);
        }

        HttpClientConnector clientConnector = httpWsConnectorFactory
                .createHttpClientConnector(new HashMap<>(), senderConfiguration, connectionManager);

//        msgCtx.setProperty(BridgeConstants.HTTP_CARBON_MESSAGE, outboundHttpCarbonMessage);
        HttpResponseFuture future = clientConnector.send(outboundRequest);
        future.setHttpConnectorListener(new PassThroughHttpOutboundRespListener(workerPool, msgCtx));

        // TODO: Check HTTP method is GET or DELETE with no body
        if (RequestResponseUtils.ignoreMessageBody(msgCtx)) {
            final HttpMessageDataStreamer httpMessageDataStreamer =
                    RequestResponseUtils.getHttpMessageDataStreamer(outboundRequest);
            OutputStream outputStream = httpMessageDataStreamer.getOutputStream();
            outputStream.write(new byte[0]);
            outputStream.close();
            return;
        }

        // serialize
        if (Boolean.TRUE.equals(msgCtx.getProperty(BridgeConstants.MESSAGE_BUILDER_INVOKED))) {
            final HttpMessageDataStreamer outboundMsgDataStreamer =
                    RequestResponseUtils.getHttpMessageDataStreamer(outboundRequest);
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
        } else {
            // the inbound carbon message is null when the call is initiated from the server itself.
            // For example, a Task that inject to a proxy service will have no http carbon message.
            // In these cases, either payload can be empty or available in the envelope in the message context.
            // If the message context has an empty envelope and if the inbound carbon message is null, we need to
            // write an empty body to the backend.
            if (inboundCarbonMessage == null) {
                final HttpMessageDataStreamer httpMessageDataStreamer =
                        RequestResponseUtils.getHttpMessageDataStreamer(outboundRequest);
                OutputStream outputStream = httpMessageDataStreamer.getOutputStream();
                outputStream.write(new byte[0]);
                outputStream.close();
                return;
            }
            do {
                HttpContent httpContent = inboundCarbonMessage.getHttpContent();
                outboundRequest.addHttpContent(httpContent);
                if (httpContent instanceof LastHttpContent) {
                    break;
                }
            } while (true);
        }
    }

    @Override
    public void cleanup(MessageContext messageContext) {

    }

    @Override
    public void stop() {
    }

    private void handleException(String s, Exception e) throws AxisFault {
        LOG.error(s, e);
        throw new AxisFault(s, e);
    }

    private void handleException(String msg) throws AxisFault {
        LOG.error(msg);
        throw new AxisFault(msg);
    }

    public List<MessagingHandler> getMessagingHandlers() {

        return messagingHandlers;
    }

    public void setMessagingHandlers(List<MessagingHandler> messagingHandlers) {

        this.messagingHandlers = messagingHandlers;
    }
}
