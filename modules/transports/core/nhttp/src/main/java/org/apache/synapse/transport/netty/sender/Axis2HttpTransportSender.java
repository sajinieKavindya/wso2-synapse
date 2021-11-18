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

        // Consider a non HTTP transport to HTTP transport scenarios where the message is present in the envelope.
        // In these scenarios, MESSAGE_BUILDER_INVOKED property is not set. But we need to format the message
        // before sending out. Hence, setting MESSAGE_BUILDER_INVOKED property true.
        if (msgCtx.getEnvelope().getBody().getFirstElement() != null) {
            msgCtx.setProperty(BridgeConstants.MESSAGE_BUILDER_INVOKED, true);
        }
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
                // Send request to the backend service.
                sendForward(msgCtx, destinationURL);
            } catch (MalformedURLException e) {
                handleException("Malformed URL in the target EPR", e);
            } catch (IOException e) {
                handleException("Error while sending the request to the backend service "
                        + destinationEPR.getAddress(), e);
            }
        } else {
            // Response submission back to the client
            try {
                sendBack(msgCtx);
            } catch (IOException e) {
                handleException("IOException found", e);
            }
        }
        return InvocationResponse.CONTINUE;
    }

    // response submission back to the client (client <-- transport)
    private void sendBack(MessageContext msgCtx) throws IOException {
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

        HttpOutboundResponse httpOutboundResponse = new HttpOutboundResponse();
        HttpCarbonMessage outboundResponseCarbonMessage =
                httpOutboundResponse.getOutboundResponseCarbonMessage(msgCtx, clientRequest);

        try {
            clientRequest.respond(outboundResponseCarbonMessage);
            writeEntityBody(msgCtx, outboundResponseCarbonMessage);

        } catch (Exception e) {
            LOG.error(BridgeConstants.BRIDGE_LOG_PREFIX + "Error occurred while submitting the response " +
                    "back to the client", e);
        }
    }

    // outgoing request (transport --> remote)
    private void sendForward(MessageContext msgCtx, URL url)
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

        HttpOutboundRequest httpOutboundRequest = new HttpOutboundRequest(msgCtx, url);
        HttpCarbonMessage outboundCarbonRequest = httpOutboundRequest.getOutboundCarbonRequest();

        SenderConfiguration senderConfiguration = createSenderConfiguration(httpOutboundRequest);
        HttpClientConnector clientConnector = httpWsConnectorFactory
                .createHttpClientConnector(new HashMap<>(), senderConfiguration, connectionManager);

        HttpResponseFuture future = clientConnector.send(outboundCarbonRequest);
        future.setHttpConnectorListener(new PassThroughHttpInboundRespListener(workerPool, msgCtx,
                targetConfiguration));

        if (RequestResponseUtils.ignoreMessageBody(msgCtx)) {
            writeEmptyBody(outboundCarbonRequest);
        }
        writeEntityBody(msgCtx, outboundCarbonRequest);
    }

    private SenderConfiguration createSenderConfiguration(HttpOutboundRequest httpOutboundRequest) {

        SenderConfiguration senderConfiguration = new SenderConfiguration();
        senderConfiguration.setHttpVersion(httpOutboundRequest.getHttpVersion());
        if (httpOutboundRequest.isChunk()) {
            senderConfiguration.setChunkingConfig(ChunkConfig.ALWAYS);
        } else {
            senderConfiguration.setChunkingConfig(ChunkConfig.NEVER);
        }

        if (httpOutboundRequest.isKeepAlive()) {
            senderConfiguration.setKeepAliveConfig(KeepAliveConfig.ALWAYS);
        } else {
            senderConfiguration.setKeepAliveConfig(KeepAliveConfig.NEVER);
        }
        return senderConfiguration;
    }

    private void writeEmptyBody(HttpCarbonMessage outboundCarbonMessage) throws AxisFault {
        final HttpMessageDataStreamer httpMessageDataStreamer =
                RequestResponseUtils.getHttpMessageDataStreamer(outboundCarbonMessage);
        OutputStream outputStream = httpMessageDataStreamer.getOutputStream();
        try {
            outputStream.write(new byte[0]);
            outputStream.close();
        } catch (IOException e) {
            throw new AxisFault("Error while writing the entity body to the http CarbonMessage");
        }
    }

    private void writeEntityBody(MessageContext msgContext, HttpCarbonMessage outboundCarbonMessage) throws AxisFault {
        if (Boolean.TRUE.equals(msgContext.getProperty(BridgeConstants.MESSAGE_BUILDER_INVOKED))) {
            final HttpMessageDataStreamer outboundMsgDataStreamer =
                    RequestResponseUtils.getHttpMessageDataStreamer(outboundCarbonMessage);
            final OutputStream outputStream = outboundMsgDataStreamer.getOutputStream();
            OMOutputFormat format = MessageUtils.getOMOutputFormat(msgContext);
            try {
                MessageFormatter messageFormatter = MessageUtils.getMessageFormatter(msgContext);
                messageFormatter.writeTo(msgContext, format, outputStream, false);
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
            HttpCarbonMessage inboundCarbonMessage =
                    (HttpCarbonMessage) msgContext.getProperty(BridgeConstants.HTTP_CARBON_MESSAGE);
            // the inbound carbon message is null when the call is initiated from non-http transport.
            // In these cases, either payload can be empty or available in the envelope in the message context.
            // If the message context has an empty envelope and if the inbound carbon message is null, we need to
            // write an empty body to the backend.
            if (inboundCarbonMessage == null) {
                writeEmptyBody(outboundCarbonMessage);
                return;
            }
            do {
                HttpContent httpContent = inboundCarbonMessage.getHttpContent();
                outboundCarbonMessage.addHttpContent(httpContent);
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
