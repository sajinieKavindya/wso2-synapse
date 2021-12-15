/*
 *  Copyright (c) 2022, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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

import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.LastHttpContent;
import org.apache.axiom.om.OMOutputFormat;
import org.apache.axis2.AxisFault;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.transport.MessageFormatter;
import org.apache.axis2.transport.http.HTTPConstants;
import org.apache.http.protocol.HTTP;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.transport.netty.BridgeConstants;
import org.apache.synapse.transport.netty.config.BaseConfiguration;
import org.apache.synapse.transport.netty.config.NettyConfiguration;
import org.apache.synapse.transport.netty.config.TargetConfiguration;
import org.apache.synapse.transport.nhttp.NhttpConstants;
import org.apache.synapse.transport.passthru.util.RelayUtils;
import org.wso2.securevault.SecretResolver;
import org.wso2.securevault.commons.MiscellaneousUtil;
import org.wso2.transport.http.netty.contract.HttpResponseFuture;
import org.wso2.transport.http.netty.contract.config.ChunkConfig;
import org.wso2.transport.http.netty.contract.config.KeepAliveConfig;
import org.wso2.transport.http.netty.contract.config.Parameter;
import org.wso2.transport.http.netty.contract.config.SslConfiguration;
import org.wso2.transport.http.netty.contract.exceptions.ServerConnectorException;
import org.wso2.transport.http.netty.contractimpl.sender.channel.pool.ConnectionManager;
import org.wso2.transport.http.netty.contractimpl.sender.channel.pool.PoolConfiguration;
import org.wso2.transport.http.netty.message.HttpCarbonMessage;
import org.wso2.transport.http.netty.message.HttpMessageDataStreamer;
import org.wso2.transport.http.netty.message.PooledDataStreamerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Utility class providing HTTP utility methods.
 */
public class HttpUtils {

    private static final Log LOG = LogFactory.getLog(HttpUtils.class);

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
    }

    /**
     * RRemove unwanted headers from the http response of outgoing request. These are headers which
     * should be dictated by the transport and not by the user. We remove these as these may get
     * copied from the request messages.
     *
     * @param msgContext        axis2 message context
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

                if (HTTP.TARGET_HOST.equalsIgnoreCase(headerName)) {
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
     * Invokes {@code HttpResponseFuture} respond method to send the response back to the client.
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
            RequestResponseUtils.handleException("Error occurred while serializing the message body.", e);
        }
    }

    public static void serializeDataUsingMessageFormatter(MessageContext msgContext, MessageFormatter messageFormatter,
                                                          OutputStream outputStream) throws AxisFault {

        OMOutputFormat format = MessageUtils.getOMOutputFormat(msgContext);
        try {
            messageFormatter.writeTo(msgContext, format, outputStream, false);
        } catch (AxisFault e) {
            RequestResponseUtils.handleException("Error occurred while serializing the message body.", e);
        } finally {
            HttpUtils.closeMessageOutputStreamQuietly(outputStream);
        }
    }

    public static void copyContentFromInboundHttpCarbonMessage(HttpCarbonMessage inboundMsg,
                                                               HttpCarbonMessage outboundResponseMsg) {

        do {
            HttpContent httpContent = inboundMsg.getHttpContent();
            outboundResponseMsg.addHttpContent(httpContent);
            if (httpContent instanceof LastHttpContent) {
                break;
            }
        } while (true);
    }

    public static void closeMessageOutputStreamQuietly(OutputStream messageOutputStream) {

        try {
            if (messageOutputStream != null) {
                messageOutputStream.close();
            }
        } catch (IOException e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Couldn't close the message output stream: " + e.getMessage());
            }
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

    public static boolean isGETRequest(MessageContext msgCtx) {

        return BridgeConstants.HTTP_GET.equalsIgnoreCase(msgCtx.getProperty(BridgeConstants.HTTP_METHOD).toString());
    }

    public static boolean isHEADRequest(MessageContext msgCtx) {

        return BridgeConstants.HTTP_HEAD.equalsIgnoreCase(msgCtx.getProperty(BridgeConstants.HTTP_METHOD).toString());
    }

    public static boolean isCONNECTRequest(MessageContext msgCtx) {

        return BridgeConstants.HTTP_CONNECT.equalsIgnoreCase(msgCtx.getProperty(BridgeConstants.HTTP_CONNECT)
                .toString());
    }

    public static boolean isNoEntityBodyRequest(MessageContext msgCtx) {

        if (HttpUtils.isGETRequest(msgCtx) || (RelayUtils.isDeleteRequestWithoutPayload(msgCtx))) {
            return true;
        }
        return !hasEntityBody(msgCtx);
    }

    private static boolean hasEntityBody(MessageContext msgCtx) {

        if (msgCtx.getEnvelope().getBody().getFirstElement() != null) {
            return true;
        }
        return !msgCtx.isPropertyTrue(BridgeConstants.NO_ENTITY_BODY);
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

        if (headers.contains(HttpHeaderNames.HOST) && isPreservedHeader) {
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
     * @param targetConfiguration target configuration.
     */
    public static void populateSSLConfiguration(SslConfiguration senderConfiguration,
                                                TargetConfiguration targetConfiguration) throws AxisFault {

        List<Parameter> clientParamList = new ArrayList<>();
        NettyConfiguration globalConfig = NettyConfiguration.getInstance();
        SecretResolver secretResolver = targetConfiguration.getConfigurationContext()
                .getAxisConfiguration().getSecretResolver();

        populateKeyStoreConfigs(senderConfiguration, secretResolver, globalConfig);

        boolean disableCertValidation = globalConfig.getClientSSLValidateCert();
        if (disableCertValidation) {
            senderConfiguration.disableSsl();
        } else {
            populateTrustStoreConfigs(senderConfiguration, secretResolver, globalConfig);
        }

        // TODO: no need to have this if cert is disabled
        populateProtocolConfigs(senderConfiguration, clientParamList, globalConfig);

        populateCertValidationConfigs(senderConfiguration, globalConfig);

        populateCiphersConfigs(clientParamList, globalConfig);

        populateTimeoutConfigs(senderConfiguration, globalConfig);

//        populateHostnameVerifierConfigs

        if (!clientParamList.isEmpty()) {
            senderConfiguration.setParameters(clientParamList);
        }
    }

    public static void populateKeyStoreConfigs(SslConfiguration sslConfiguration, SecretResolver secretResolver,
                                               NettyConfiguration globalConfig) throws AxisFault {

        String location = globalConfig.getClientSSLKeystoreLocation();
        String type = globalConfig.getClientSSLKeystoreType();
        String storePassword = globalConfig.getClientSSLKeystorePassword();
        String keyPassword = globalConfig.getClientSSLKeystoreKeyPassword();

        if (Objects.isNull(location) || location.isEmpty()) {
            throw new AxisFault("KeyStore file location must be provided for secure connection");
        }

        if (Objects.isNull(storePassword)) {
            throw new AxisFault("KeyStore password must be provided for secure connection");
        }
        if (Objects.isNull(keyPassword)) {
            throw new AxisFault("Cannot proceed because KeyPassword element is missing in KeyStore");
        }
        storePassword = MiscellaneousUtil.resolve(storePassword, secretResolver);
        keyPassword = MiscellaneousUtil.resolve(keyPassword, secretResolver);

        sslConfiguration.setKeyStoreFile(location);
        sslConfiguration.setKeyStorePass(storePassword);
        sslConfiguration.setCertPass(keyPassword);
        sslConfiguration.setTLSStoreType(type);
    }

    public static void populateTrustStoreConfigs(SslConfiguration sslConfiguration, SecretResolver secretResolver,
                                                 NettyConfiguration globalConfig) throws AxisFault {

        String location = globalConfig.getClientSSLTruststoreLocation();
        String type = globalConfig.getClientSSLTruststoreType();
        String storePassword = globalConfig.getClientSSLTruststorePassword();
        if (Objects.isNull(storePassword)) {
            throw new AxisFault("Cannot proceed because Password element is missing in TrustStore");
        }
        storePassword = MiscellaneousUtil.resolve(storePassword, secretResolver);

        sslConfiguration.setTrustStoreFile(location);
        sslConfiguration.setTrustStorePass(storePassword);
        // TODO: need to edit the transport-http to have a type for truststore - verified from bhashinee
    }

    private static void populateProtocolConfigs(SslConfiguration sslConfiguration, List<Parameter> paramList,
                                                NettyConfiguration globalConfig) {

        String configuredHttpsProtocols = globalConfig.getClientSSLHttpsProtocols().replaceAll("\\s", "");

        if (!configuredHttpsProtocols.isEmpty()) {
            Parameter serverProtocols = new Parameter("sslEnabledProtocols", configuredHttpsProtocols);
            paramList.add(serverProtocols);
        }

        String sslProtocol = globalConfig.getClientSSLProtocol();
        if (Objects.isNull(sslProtocol) || sslProtocol.isEmpty()) {
            sslProtocol = "TLS";
        }
        sslConfiguration.setSSLProtocol(sslProtocol);
    }

    public static void populateCertValidationConfigs(SslConfiguration sslConfiguration,
                                                     NettyConfiguration globalConfig) {


        boolean certRevocationVerifierEnabled = globalConfig.getClientSSLCertificateRevocationVerifierEnabled();

        if (certRevocationVerifierEnabled) {
            sslConfiguration.setValidateCertEnabled(true);
            String cacheSizeString = globalConfig.getClientSSLCertificateRevocationVerifierCacheSize();
            String cacheDelayString = globalConfig.getClientSSLCertificateRevocationVerifierCacheDelay();
            Integer cacheSize = null;
            Integer cacheDelay = null;
            try {
                cacheSize = new Integer(cacheSizeString);
                cacheDelay = new Integer(cacheDelayString);
            } catch (NumberFormatException e) {
                // do nothing
            }

            if (Objects.nonNull(cacheDelay) && cacheDelay != 0) {
                sslConfiguration.setCacheValidityPeriod(Math.toIntExact(cacheDelay));
            }
            if (Objects.nonNull(cacheSize) && cacheSize != 0) {
                sslConfiguration.setCacheSize(Math.toIntExact(cacheSize));
            }
        }
    }

    private static void populateCiphersConfigs(List<Parameter> paramList, NettyConfiguration globalConfig) {

        String preferredCiphers = globalConfig.getClientSSLPreferredCiphers().replaceAll("\\s", "");

        if (!preferredCiphers.isEmpty()) {
            Parameter serverParameters = new Parameter("ciphers", preferredCiphers);
            paramList.add(serverParameters);
        }
    }

    public static void populateTimeoutConfigs(SslConfiguration sslConfiguration, NettyConfiguration globalConfig) {

        int sessionTimeout = globalConfig.getClientSSLSessionTimeout();
        int handshakeTimeout = globalConfig.getClientSSLHandshakeTimeout();
        if (sessionTimeout > 0) {
            try {
                sslConfiguration.setSslSessionTimeOut(sessionTimeout);
            } catch (NumberFormatException e) {
                LOG.warn("Invalid number found for ssl sessionTimeout : " + sessionTimeout
                        + ". Hence, using the default value of 86400s/24h");
            }
        }
        if (handshakeTimeout > 0) {
            try {
                sslConfiguration.setSslHandshakeTimeOut(handshakeTimeout);
            } catch (NumberFormatException e) {
                LOG.warn("Invalid number found for ssl handshakeTimeout : " + handshakeTimeout +
                        ". Hence, using the default value of 10s");
            }
        }
    }

    public static void populateHostnameVerifierConfigs(SslConfiguration sslConfiguration,
                                                       NettyConfiguration globalConfig) throws AxisFault {
        // TODO: verify from Bhashinee
        String hostNameVerificationEnabled = globalConfig.getClientSSLHostnameVerifier();
//        sslConfiguration.setHostNameVerificationEnabled(hostNameVerificationEnabled);
    }

    }
