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
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import org.apache.axiom.om.OMOutputFormat;
import org.apache.axis2.AxisFault;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.transport.MessageFormatter;
import org.apache.axis2.transport.http.HTTPConstants;
import org.apache.http.HttpStatus;
import org.apache.http.protocol.HTTP;
import org.apache.log4j.Logger;
import org.apache.synapse.transport.netty.BridgeConstants;
import org.apache.synapse.transport.netty.config.BaseConfiguration;
import org.apache.synapse.transport.netty.config.NettyConfiguration;
import org.apache.synapse.transport.netty.config.TargetConfiguration;
import org.apache.synapse.transport.nhttp.NhttpConstants;
import org.apache.synapse.transport.passthru.PassThroughConstants;
import org.apache.synapse.transport.passthru.config.PassThroughConfiguration;
import org.apache.synapse.transport.passthru.util.RelayUtils;
import org.wso2.securevault.SecretResolver;
import org.wso2.securevault.SecretResolverFactory;
import org.wso2.transport.http.netty.contract.HttpResponseFuture;
import org.wso2.transport.http.netty.contract.config.ChunkConfig;
import org.wso2.transport.http.netty.contract.config.KeepAliveConfig;
import org.wso2.transport.http.netty.contract.config.SslConfiguration;
import org.wso2.transport.http.netty.contract.exceptions.ServerConnectorException;
import org.wso2.transport.http.netty.contractimpl.sender.channel.pool.ConnectionManager;
import org.wso2.transport.http.netty.contractimpl.sender.channel.pool.PoolConfiguration;
import org.wso2.transport.http.netty.message.HttpCarbonMessage;
import org.wso2.transport.http.netty.message.HttpMessageDataStreamer;
import org.wso2.transport.http.netty.message.PooledDataStreamerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * Utility class providing HTTP utility methods.
 */
public class HttpUtils {

    private static final Logger LOGGER = Logger.getLogger(HttpUtils.class);

    public static ConnectionManager getConnectionManager() {

        PoolConfiguration poolConfiguration = new PoolConfiguration();
        if (NettyConfiguration.getInstance().isCustomConnectionPoolConfigsEnabled()) {
            populatePoolingConfig(poolConfiguration);
        }
        return new ConnectionManager(poolConfiguration);
    }

    public static void populatePoolingConfig(PoolConfiguration poolConfiguration) {

        NettyConfiguration globalConf = NettyConfiguration.getInstance();
        poolConfiguration.setMaxActivePerPool(globalConf.getConnectionPoolingMaxActiveConnections());
        poolConfiguration.setMaxIdlePerPool(globalConf.getConnectionPoolingMaxIdleConnections());
        poolConfiguration.setMaxWaitTime((long) globalConf.getConnectionPoolingWaitTime() * 1000);

        // This only applies to HTTP/2.
        int maxActiveStreamsPerConnection = globalConf.getConnectionPoolingMaxActiveStreamsPerConnection();
        poolConfiguration.setHttp2MaxActiveStreamsPerConnection(
                maxActiveStreamsPerConnection == -1 ? Integer.MAX_VALUE : maxActiveStreamsPerConnection);
    }

    /**
     * Creates an HttpCarbonRequest or an HttpCarbonResponse based on the given flag.
     *
     * @param isRequest whether to create a HttpCarbonRequest or an HttpCarbonResponse
     * @return an HttpCarbonRequest or an HttpCarbonResponse based on the given flag
     */
    public static HttpCarbonMessage createHttpCarbonMessage(boolean isRequest) {

        HttpCarbonMessage httpCarbonMessage;
        if (isRequest) {
            httpCarbonMessage = new HttpCarbonMessage(
                    new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, ""));
        } else {
            httpCarbonMessage = new HttpCarbonMessage(
                    new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
        }
        return httpCarbonMessage;
    }

    /**
     * RRemove unwanted headers from the http response of outgoing request. These are headers which
     * should be dictated by the transport and not by the user. We remove these as these may get
     * copied from the request messages.
     *
     * @param msgContext axis2 message context
     * @param baseConfiguration configuration that has all the preserved header details
     */
    public static void removeUnwantedHeadersFromInternalTransportHeadersMap(MessageContext msgContext,
                                                                            BaseConfiguration baseConfiguration) {

        Map transportHeaders = (Map) msgContext.getProperty(MessageContext.TRANSPORT_HEADERS);

        if (Objects.nonNull(transportHeaders) && !transportHeaders.isEmpty()) {
            Iterator iter = transportHeaders.keySet().iterator();
            while (iter.hasNext()) {
                String headerName = (String) iter.next();
                if (HTTP.TRANSFER_ENCODING.equalsIgnoreCase(headerName)) {
                    iter.remove();
                }

                if (HTTP.CONN_DIRECTIVE.equalsIgnoreCase(headerName)
                        && !baseConfiguration.isPreserveHttpHeader(HTTP.CONN_DIRECTIVE)) {
                    iter.remove();
                }

                if (HTTP.CONN_KEEP_ALIVE.equalsIgnoreCase(headerName)
                        && !baseConfiguration.isPreserveHttpHeader(HTTP.CONN_KEEP_ALIVE)) {
                    iter.remove();
                }

                if (HTTP.CONTENT_LEN.equalsIgnoreCase(headerName)
                        && !baseConfiguration.isPreserveHttpHeader(HTTP.CONTENT_LEN)) {
                    iter.remove();
                }

                if (HTTP.DATE_HEADER.equalsIgnoreCase(headerName)
                        && !baseConfiguration.isPreserveHttpHeader(HTTP.DATE_HEADER)) {
                    iter.remove();
                }

                if (HTTP.SERVER_HEADER.equalsIgnoreCase(headerName)
                        && !baseConfiguration.isPreserveHttpHeader(HTTP.SERVER_HEADER)) {
                    iter.remove();
                }

                if (HTTP.USER_AGENT.equalsIgnoreCase(headerName)
                        && !baseConfiguration.isPreserveHttpHeader(HTTP.USER_AGENT)) {
                    iter.remove();
                }

                if (HTTP.TARGET_HOST.equalsIgnoreCase(headerName)
                        && !baseConfiguration.isPreserveHttpHeader(HTTP.TARGET_HOST)) {
                    // TODO: can HOST header be a preservable header?
                    iter.remove();
                }
            }
        }
    }

    public static boolean isFaultMessage(MessageContext msgContext) {

        return msgContext.getEnvelope() != null
                && (msgContext.getEnvelope().getBody().hasFault() || msgContext.isProcessingFault());
    }

    public static boolean sendFaultAsHTTP200(MessageContext msgContext) {

        Object faultsAsHttp200Property = msgContext.getProperty(BridgeConstants.FAULTS_AS_HTTP_200);
        return Objects.nonNull(faultsAsHttp200Property) && "true".equalsIgnoreCase(faultsAsHttp200Property.toString());
    }

    public static ChunkConfig getChunkConfig(String chunkConfig) throws AxisFault {

        switch (chunkConfig) {
            case BridgeConstants.AUTO:
                return ChunkConfig.AUTO;
            case BridgeConstants.ALWAYS:
                return ChunkConfig.ALWAYS;
            case BridgeConstants.NEVER:
                return ChunkConfig.NEVER;
            default:
                throw new AxisFault(
                        "Invalid configuration found for Transfer-Encoding: " + chunkConfig);
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
     * This method should never be called directly to send out responses for ballerina HTTP 1.1. Use
     * PipeliningHandler's sendPipelinedResponse() method instead.
     *
     * @param requestMsg  Represent the request message
     * @param responseMsg Represent the corresponding response
     * @return HttpResponseFuture that represent the future results
     */
    public static HttpResponseFuture sendOutboundResponse(HttpCarbonMessage requestMsg,
                                                          HttpCarbonMessage responseMsg) throws AxisFault {

        HttpResponseFuture responseFuture;
        try {
            responseFuture = requestMsg.respond(responseMsg);
        } catch (ServerConnectorException e) {
            throw new AxisFault("Error occurred while submitting the response to the client", e);
        }
        return responseFuture;
    }

    /**
     * Get the response data streamer that should be used for serializing data.
     *
     * @param outboundResponse Represents native response
     * @return HttpMessageDataStreamer that should be used for serializing
     */
    public static HttpMessageDataStreamer getResponseDataStreamer(HttpCarbonMessage outboundResponse) {

        final HttpMessageDataStreamer outboundMsgDataStreamer;
        final PooledDataStreamerFactory pooledDataStreamerFactory = (PooledDataStreamerFactory)
                outboundResponse.getProperty(BridgeConstants.POOLED_BYTE_BUFFER_FACTORY);
        if (pooledDataStreamerFactory != null) {
            outboundMsgDataStreamer = pooledDataStreamerFactory.createHttpDataStreamer(outboundResponse);
        } else {
            outboundMsgDataStreamer = new HttpMessageDataStreamer(outboundResponse);
        }
        return outboundMsgDataStreamer;
    }

    public static void serializeBytes(OutputStream outputStream, byte bytes[]) throws AxisFault {

        try {
            outputStream.write(bytes);
        } catch (IOException e) {
            throw new AxisFault("Error while writing the entity body to the http CarbonMessage");
        }
    }

    public static void serializeDataUsingMessageFormatter(MessageContext msgContext, MessageFormatter messageFormatter,
                                                          OutputStream outputStream) {

        OMOutputFormat format = MessageUtils.getOMOutputFormat(msgContext);
        try {
            messageFormatter.writeTo(msgContext, format, outputStream, false);
        } catch (AxisFault axisFault) {
            LOGGER.error(BridgeConstants.BRIDGE_LOG_PREFIX + axisFault.getMessage());
        }
    }

    public static void serializeDataFromInboundHttpCarbonMessage(HttpCarbonMessage inboundMsg,
                                                                 HttpCarbonMessage outboundResponseMsg) {

        do {
            HttpContent httpContent = inboundMsg.getHttpContent();
            outboundResponseMsg.addHttpContent(httpContent);
            if (httpContent instanceof LastHttpContent) {
                break;
            }
        } while (true);
    }

    public static void closeMessageOutputStream(OutputStream messageOutputStream) {

        try {
            if (messageOutputStream != null) {
                messageOutputStream.close();
            }
        } catch (IOException e) {
            LOGGER.error("Couldn't close message output stream", e);
        }
    }

    public static KeepAliveConfig getKeepAliveConfig(boolean keepAlive) throws AxisFault {

        if (keepAlive) {
            return getKeepAliveConfig(BridgeConstants.ALWAYS);
        } else {
            return getKeepAliveConfig(BridgeConstants.NEVER);
        }
    }

    /**
     * Check whether the content type is multipart or not.
     *
     * @param contentType Content-Type of the
     * @return true for multipart content types
     */
    public static boolean isMultipartContent(String contentType) {

        return contentType.contains(HTTPConstants.MEDIA_TYPE_MULTIPART_FORM_DATA)
                || contentType.contains(HTTPConstants.HEADER_ACCEPT_MULTIPART_RELATED);
    }

    public static boolean isGetRequest(MessageContext msgCtx) {

        return BridgeConstants.HTTP_GET.equals(msgCtx.getProperty(BridgeConstants.HTTP_METHOD));
    }

    public static boolean isNoEntityBodyRequest(MessageContext msgCtx) {

        if ((PassThroughConstants.HTTP_GET.equals(msgCtx.getProperty(BridgeConstants.HTTP_METHOD)))
                || (RelayUtils.isDeleteRequestWithoutPayload(msgCtx))) {
            return false;
        }
        return !hasEntityBody(msgCtx);
    }

    private static boolean hasEntityBody(MessageContext msgCtx) {

        if (msgCtx.getEnvelope().getBody().getFirstElement() != null) {
            return true;
        }
        return !Boolean.TRUE.equals(msgCtx.getProperty(NhttpConstants.NO_ENTITY_BODY));
    }

    public static void addTransportHeadersToTransportMessage(HttpHeaders headers, MessageContext msgCtx) {

        Map transportHeaders = (Map) msgCtx.getProperty(MessageContext.TRANSPORT_HEADERS);
        if (transportHeaders != null) {
            for (Object entryObj : transportHeaders.entrySet()) {
                Map.Entry entry = (Map.Entry) entryObj;
                if (entry.getValue() != null && entry.getKey() instanceof String &&
                        entry.getValue() instanceof String) {
                    headers.add((String) entry.getKey(), entry.getValue());
                }
            }
        }
    }

    public static void setHostHeader(String host, int port, HttpHeaders headers, MessageContext msgCtx,
                                     boolean isPreservedHeader) {

        if (headers.contains(HttpHeaderNames.USER_AGENT) && isPreservedHeader) {
            return;
        }
        // If REQUEST_HOST_HEADER property is defined, the value of this property will be set as the
        // HTTP host header of outgoing request.
        if (msgCtx.getProperty(BridgeConstants.REQUEST_HOST_HEADER) != null) {
            headers.set(HttpHeaderNames.HOST, msgCtx.getProperty(NhttpConstants.REQUEST_HOST_HEADER));
            return;
        }

        if (port == 80 || port == 443) {
            headers.set(HttpHeaderNames.HOST, host);
        } else {
            headers.set(HttpHeaderNames.HOST, host + ":" + port);
        }
    }

    /**
     * Populates SSL configuration instance with secure socket configuration.
     *
     * @param senderConfiguration SSL configuration instance.
     * @param secureSocket        Secure socket configuration.
     */
    public static void populateSSLConfiguration(SslConfiguration senderConfiguration) {

//        List<Parameter> clientParamList = new ArrayList<>();
//        boolean enable = secureSocket.getBooleanValue(HttpConstants.SECURESOCKET_CONFIG_DISABLE_SSL);
//        if (!enable) {
//            senderConfiguration.disableSsl();
//            BMap<BString, Object> key = getBMapValueIfPresent(secureSocket, HttpConstants.SECURESOCKET_CONFIG_KEY);
//            if (key != null) {
//                evaluateKeyField(key, senderConfiguration);
//            }
//            return;
//        }
//        Object cert = secureSocket.get(HttpConstants.SECURESOCKET_CONFIG_CERT);
//        if (cert == null) {
//            BMap<BString, Object> key = getBMapValueIfPresent(secureSocket, HttpConstants.SECURESOCKET_CONFIG_KEY);
//            if (key != null) {
//                senderConfiguration.useJavaDefaults();
//            } else {
//                throw createHttpError("Need to configure cert with client SSL certificates file",
//                        HttpErrorType.SSL_ERROR);
//            }
//        } else {
//            evaluateCertField(cert, senderConfiguration);
//        }
//
//        SecretResolver secretResolver;
//        ConfigurationContext configurationContext = sourceConfiguration.getConfigurationContext();
//        if (configurationContext != null && configurationContext.getAxisConfiguration() != null) {
//            secretResolver = configurationContext.getAxisConfiguration().getSecretResolver();
//        } else {
//            secretResolver = SecretResolverFactory.create(keyStoreEl, false);
//        }
//
//        RequestResponseUtils.populateKeyStoreConfigs(key, senderConfiguration);
//
//        RequestResponseUtils.populateTrustStoreConfigs(cert, senderConfiguration);
//
//        RequestResponseUtils.evaluateProtocolField(protocol, senderConfiguration, clientParamList);
//
//        RequestResponseUtils.populateCertValidationConfigs(certValidation, senderConfiguration);
//
//        RequestResponseUtils.evaluateCiphersField(ciphers, clientParamList);
//
//        RequestResponseUtils.populateCommonConfigs(secureSocket, senderConfiguration, clientParamList);
//
//        BMap<BString, Object> key = getBMapValueIfPresent(secureSocket, HttpConstants.SECURESOCKET_CONFIG_KEY);
//        if (key != null) {
//            evaluateKeyField(key, senderConfiguration);
//        }
//        BMap<BString, Object> protocol = getBMapValueIfPresent(secureSocket, SECURESOCKET_CONFIG_PROTOCOL);
//        if (protocol != null) {
//            evaluateProtocolField(protocol, senderConfiguration, clientParamList);
//        }
//        BMap<BString, Object> certValidation = getBMapValueIfPresent(secureSocket, SECURESOCKET_CONFIG_CERT_VALIDATION);
//        if (certValidation != null) {
//            evaluateCertValidationField(certValidation, senderConfiguration);
//        }
//        BArray ciphers = secureSocket.containsKey(HttpConstants.SECURESOCKET_CONFIG_CIPHERS) ?
//                secureSocket.getArrayValue(HttpConstants.SECURESOCKET_CONFIG_CIPHERS) : null;
//        if (ciphers != null) {
//            evaluateCiphersField(ciphers, clientParamList);
//        }
//        evaluateCommonFields(secureSocket, senderConfiguration, clientParamList);
//
//        if (!clientParamList.isEmpty()) {
//            senderConfiguration.setParameters(clientParamList);
//        }
    }
}
