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
package org.apache.synapse.transport.netty.config;

import org.apache.axis2.transport.base.threads.WorkerPool;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.transport.passthru.HttpGetRequestProcessor;
import org.apache.synapse.transport.passthru.PassThroughConstants;
import org.apache.synapse.transport.passthru.config.PassThroughConfigPNames;
import org.apache.synapse.transport.util.ConfigurationBuilderUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * This class encapsulates netty transport tuning configurations specified via a
 * configurations file or system properties.
 */
public class NettyConfiguration {

    private static final Log LOG = LogFactory.getLog(NettyConfiguration.class);

    /**
     * Default tuning parameter values.
     */
    public static final int DEFAULT_WORKER_POOL_SIZE_CORE = 400;
    public static final int DEFAULT_WORKER_POOL_SIZE_MAX = 400;
    public static final int DEFAULT_WORKER_THREAD_KEEPALIVE_SEC = 60;
    public static final int DEFAULT_WORKER_POOL_QUEUE_LENGTH = -1;
    public static final String HTTP_WORKER_THREAD_GROUP_NAME = "HTTP Worker Thread Group";
    public static final String HTTP_WORKER_THREAD_ID = "HTTPWorker";

    //additional rest dispatch handlers
    private static final String REST_DISPATCHER_SERVICE = "rest.dispatcher.service";
    // URI configurations that determine if it requires custom rest dispatcher
    private static final String REST_URI_API_REGEX = "rest_uri_api_regex";
    private static final String REST_URI_PROXY_REGEX = "rest_uri_proxy_regex";
    // properties which are allowed to be directly pass through from request context to response context explicitly
    // TODO: verify https://github.com/wso2/wso2-synapse/pull/1729
    private static final String ALLOWED_RESPONSE_PROPERTIES = "allowed_response_properties";
    private static final String REQUEST_LIMIT_VALIDATION = "http.requestLimits.validation.enabled";
    private static final String MAX_STATUS_LINE_LENGTH = "http.requestLimits.maxStatusLineLength";
    private static final String MAX_HEADER_SIZE = "http.requestLimits.maxHeaderSize";
    private static final String MAX_ENTITY_BODY_SIZE = "http.requestLimits.maxEntityBodySize";
    private static final String CLIENT_REQUEST_LIMIT_VALIDATION = "http.client.requestLimits.validation.enabled";
    private static final String MAX_CLIENT_REQUEST_STATUS_LINE_LENGTH = "http.client.requestLimits.maxStatusLineLength";
    private static final String MAX_CLIENT_REQUEST_HEADER_SIZE = "http.client.requestLimits.maxHeaderSize";
    private static final String MAX_CLIENT_REQUEST_ENTITY_BODY_SIZE = "http.client.requestLimits.maxEntityBodySize";

    private static final String HTTP_SOCKET_TIMEOUT = "http.socket.timeout";

    //Client connection pooling configs
    public static final String ENABLE_CUSTOM_CONNECTION_POOL_CONFIG = "custom.connection.pool.config.enabled";
    /**
     * Max active connections per route(host:port). Default value is -1 which indicates unlimited.
     */
    public static final String CONNECTION_POOLING_MAX_ACTIVE_CONNECTIONS = "connection.pool.maxActiveConnections";
    /**
     * Maximum number of idle connections allowed per pool.
     */
    public static final String CONNECTION_POOLING_MAX_IDLE_CONNECTIONS = "connection.pool.maxIdleConnections";

    /**
     * Maximum amount of time, the client should wait for an idle connection before it sends an error
     * when the pool is exhausted.
     */
    public static final String CONNECTION_POOLING_WAIT_TIME = "connection.pool.waitTimeInMillis";
    /**
     * Maximum active streams per connection. This only applies to HTTP/2.
     */
    public static final String CONNECTION_POOLING_MAX_ACTIVE_STREAMS_PER_CONNECTION =
            "connection.pool.maxActiveStreamsPerConnection";
    public static final String CLIENT_ENDPOINT_SOCKET_TIMEOUT = "http.client.endpoint.socket.timeout";

    private Boolean isKeepAliveDisabled = null;
    private Boolean reverseProxyMode = null;

    /**
     * WSDL processor for Get requests.
     */
    private HttpGetRequestProcessor httpGetRequestProcessor = null;

    /**
     * Default Synapse service name.
     */
    private String passThroughDefaultServiceName = null;

    /**
     * The thread pool for executing the messages passing through.
     */
    private WorkerPool workerPool = null;

    private Properties props;

    private static NettyConfiguration instance = new NettyConfiguration();

    private NettyConfiguration() {
        try {
            props = loadProperties("netty.properties");
        } catch (Exception ignored) {
            // ignore
        }
    }

    public static NettyConfiguration getInstance() {
        return instance;
    }

    public int getWorkerPoolCoreSize() {
        return ConfigurationBuilderUtil.getIntProperty(PassThroughConfigPNames.WORKER_POOL_SIZE_CORE,
                DEFAULT_WORKER_POOL_SIZE_CORE, props);
    }

    public int getWorkerPoolMaxSize() {
        return ConfigurationBuilderUtil.getIntProperty(PassThroughConfigPNames.WORKER_POOL_SIZE_MAX,
                DEFAULT_WORKER_POOL_SIZE_MAX, props);
    }

    public int getWorkerThreadKeepaliveSec() {
        return ConfigurationBuilderUtil.getIntProperty(PassThroughConfigPNames.WORKER_THREAD_KEEP_ALIVE_SEC,
                DEFAULT_WORKER_THREAD_KEEPALIVE_SEC, props);
    }

    public int getWorkerPoolQueueLen() {
        return ConfigurationBuilderUtil.getIntProperty(PassThroughConfigPNames.WORKER_POOL_QUEUE_LENGTH,
                DEFAULT_WORKER_POOL_QUEUE_LENGTH, props);
    }

    public String getRestUriApiRegex() {
        return ConfigurationBuilderUtil.getStringProperty(REST_URI_API_REGEX, "", props);
    }

    public String getRESTDispatchService() {
        return ConfigurationBuilderUtil.getStringProperty(REST_DISPATCHER_SERVICE, "", props);
    }

    public String getRestUriProxyRegex() {
        return ConfigurationBuilderUtil.getStringProperty(REST_URI_PROXY_REGEX, "", props);
    }

    public boolean isRequestLimitsValidationEnabled() {
        return ConfigurationBuilderUtil.getBooleanProperty(REQUEST_LIMIT_VALIDATION, false, props);
    }

    public int getMaxStatusLineLength() {
        return ConfigurationBuilderUtil.getIntProperty(MAX_STATUS_LINE_LENGTH, -1, props);
    }

    public int getMaxHeaderSize() {
        return ConfigurationBuilderUtil.getIntProperty(MAX_HEADER_SIZE, -1, props);
    }

    public int getMaxEntityBodySize() {
        return ConfigurationBuilderUtil.getIntProperty(MAX_ENTITY_BODY_SIZE, -1, props);
    }

    public boolean isClientRequestLimitsValidationEnabled() {
        return ConfigurationBuilderUtil.getBooleanProperty(CLIENT_REQUEST_LIMIT_VALIDATION, false, props);
    }

    public int getClientRequestMaxStatusLineLength() {
        return ConfigurationBuilderUtil.getIntProperty(MAX_CLIENT_REQUEST_STATUS_LINE_LENGTH, -1, props);
    }

    public int getClientRequestMaxHeaderSize() {
        return ConfigurationBuilderUtil.getIntProperty(MAX_CLIENT_REQUEST_HEADER_SIZE, -1, props);
    }

    public int getClientRequestMaxEntityBodySize() {
        return ConfigurationBuilderUtil.getIntProperty(MAX_CLIENT_REQUEST_ENTITY_BODY_SIZE, -1, props);
    }

    public int getSocketTimeout() {
        return ConfigurationBuilderUtil.getIntProperty(HTTP_SOCKET_TIMEOUT, 180000, props);
    }

    public boolean isCustomConnectionPoolConfigsEnabled() {
        return ConfigurationBuilderUtil.getBooleanProperty(ENABLE_CUSTOM_CONNECTION_POOL_CONFIG, false, props);
    }

    public int getConnectionPoolingMaxActiveConnections() {
        return ConfigurationBuilderUtil.getIntProperty(CONNECTION_POOLING_MAX_ACTIVE_CONNECTIONS, -1, props);
    }

    public int getConnectionPoolingMaxIdleConnections() {
        return ConfigurationBuilderUtil.getIntProperty(CONNECTION_POOLING_MAX_IDLE_CONNECTIONS, 100, props);
    }

    public int getConnectionPoolingWaitTime() {
        return ConfigurationBuilderUtil.getIntProperty(CONNECTION_POOLING_WAIT_TIME, 30, props);
    }

    public int getConnectionPoolingMaxActiveStreamsPerConnection() {
        return ConfigurationBuilderUtil.getIntProperty(CONNECTION_POOLING_MAX_ACTIVE_STREAMS_PER_CONNECTION,
                50, props);
    }

    public int getClientEndpointSocketTimeout() {
        return ConfigurationBuilderUtil.getIntProperty(CLIENT_ENDPOINT_SOCKET_TIMEOUT, 60, props);
    }

    public boolean isKeepAliveDisabled() {
        if (isKeepAliveDisabled == null) {
            isKeepAliveDisabled = ConfigurationBuilderUtil
                    .getBooleanProperty(PassThroughConfigPNames.DISABLE_KEEPALIVE, false, props);
        }
        return isKeepAliveDisabled;
    }

    public String getListenerHostname() {
        return ConfigurationBuilderUtil
                .getStringProperty(NettyConfigPropertyNames.HTTP_LISTENER_HOSTNAME, "", props);

    }
    public String getHttpGetRequestProcessorClass() {
        return ConfigurationBuilderUtil
                    .getStringProperty(NettyConfigPropertyNames.HTTP_GET_REQUEST_PROCESSOR, "", props);

    }

    public String getHttpTransportMediationInterceptorClass() {
        return ConfigurationBuilderUtil
                .getStringProperty(NettyConfigPropertyNames.HTTP_TRANSPORT_MEDIATION_INTERCEPTOR, "", props);

    }

    public boolean isPreserveUserAgentHeader() {
        return ConfigurationBuilderUtil.getBooleanProperty(NettyConfigPropertyNames.USER_AGENT_HEADER_PRESERVE,
                false, props);
    }

    public boolean isPreserveServerHeader() {
        return ConfigurationBuilderUtil.getBooleanProperty(NettyConfigPropertyNames.SERVER_HEADER_PRESERVE,
                false, props);
    }

    public String getPreserveHttpHeaders() {
        return ConfigurationBuilderUtil.getStringProperty(NettyConfigPropertyNames.HTTP_HEADERS_PRESERVE,
                "", props);
    }

    public String isServiceListBlocked() {
        return ConfigurationBuilderUtil.getStringProperty(NettyConfigPropertyNames.HTTP_BLOCK_SERVICE_LIST,
                "false", props);
    }

    public String getResponsePreserveHttpHeaders() {
        return ConfigurationBuilderUtil.getStringProperty(NettyConfigPropertyNames.HTTP_RESPONSE_HEADERS_PRESERVE,
                "", props);
    }

    public String getClientSSLKeystoreLocation() {
        return ConfigurationBuilderUtil.getStringProperty(NettyConfigPropertyNames.CLIENT_SSL_KEYSTORE_LOCATION,
                "", props);
    }

    public String getClientSSLKeystoreType() {
        return ConfigurationBuilderUtil.getStringProperty(NettyConfigPropertyNames.CLIENT_SSL_KEYSTORE_TYPE,
                "", props);
    }

    public String getClientSSLKeystorePassword() {
        return ConfigurationBuilderUtil.getStringProperty(NettyConfigPropertyNames.CLIENT_SSL_KEYSTORE_PASSWORD,
                "", props);
    }

    public String getClientSSLKeystoreKeyPassword() {
        return ConfigurationBuilderUtil.getStringProperty(NettyConfigPropertyNames.CLIENT_SSL_KEYSTORE_KEYPASSWORD,
                "", props);
    }

    public String getClientSSLTruststoreLocation() {
        return ConfigurationBuilderUtil.getStringProperty(NettyConfigPropertyNames.CLIENT_SSL_TRUSTSTORE_LOCATION,
                "", props);
    }

    public String getClientSSLTruststoreType() {
        return ConfigurationBuilderUtil.getStringProperty(NettyConfigPropertyNames.CLIENT_SSL_TRUSTSTORE_TYPE,
                "", props);
    }

    public String getClientSSLTruststorePassword() {
        return ConfigurationBuilderUtil.getStringProperty(NettyConfigPropertyNames.CLIENT_SSL_TRUSTSTORE_PASSWORD,
                "", props);
    }

    public String getClientSSLHttpsProtocols() {
        return ConfigurationBuilderUtil.getStringProperty(NettyConfigPropertyNames.CLIENT_SSL_HTTPS_PROTOCOLS,
                "", props);
    }

    public String getClientSSLProtocol() {
        return ConfigurationBuilderUtil.getStringProperty(NettyConfigPropertyNames.CLIENT_SSL_PROTOCOL,
                "", props);
    }

    public String getClientSSLPreferredCiphers() {
        return ConfigurationBuilderUtil.getStringProperty(NettyConfigPropertyNames.CLIENT_SSL_PREFERRED_CIPHERS,
                "", props);
    }

    public int getClientSSLSessionTimeout() {
        return ConfigurationBuilderUtil.getIntProperty(NettyConfigPropertyNames.CLIENT_SSL_SESSION_TIMEOUT,
                0, props);
    }

    public int getClientSSLHandshakeTimeout() {
        return ConfigurationBuilderUtil.getIntProperty(NettyConfigPropertyNames.CLIENT_SSL_HANDSHAKE_TIMEOUT,
                0, props);
    }

    public Boolean getClientSSLValidateCert() {
        return ConfigurationBuilderUtil.getBooleanProperty(NettyConfigPropertyNames.CLIENT_SSL_DISABLE_CERT_VALIDATION,
                false, props);
    }

    public String getClientSSLHostnameVerifier() {
        return ConfigurationBuilderUtil.getStringProperty(NettyConfigPropertyNames.CLIENT_SSL_HOSTNAME_VERIFIER,
                "", props);
    }

    public boolean getClientSSLCertificateRevocationVerifierEnabled() {
        return ConfigurationBuilderUtil.getBooleanProperty(
                NettyConfigPropertyNames.CLIENT_SSL_CERTIFICATE_REVOCATION_VERIFIER_ENABLE, false, props);
    }

    public String getClientSSLCertificateRevocationVerifierCacheSize() {
        return ConfigurationBuilderUtil.getStringProperty(
                NettyConfigPropertyNames.CLIENT_SSL_CERTIFICATE_REVOCATION_VERIFIER_CACHE_SIZE, "", props);
    }

    public String getClientSSLCertificateRevocationVerifierCacheDelay() {
        return ConfigurationBuilderUtil.getStringProperty(
                NettyConfigPropertyNames.CLIENT_SSL_CERTIFICATE_REVOCATION_VERIFIER_CACHE_DELAY, "", props);
    }

    /**
     * Check for reverse proxy mode.
     *
     * @return whether reverse proxy mode is enabled
     */
    public boolean isReverseProxyMode() {
        if (reverseProxyMode == null) {
            reverseProxyMode = Boolean.parseBoolean(System.getProperty("reverseProxyMode"));
        }
        return reverseProxyMode;
    }

    /**
     * Get the default synapse service name.
     *
     * @return synapse service name
     */
    public String getPassThroughDefaultServiceName() {
        if (passThroughDefaultServiceName == null) {
            passThroughDefaultServiceName = ConfigurationBuilderUtil.getStringProperty("passthru.default.service",
                    "__SynapseService", props);
        }
        return passThroughDefaultServiceName;
    }

    /**
     * Load the properties from the given property file path.
     *
     * @param filePath Path of the property file
     * @return Properties loaded from given file
     */
    private static Properties loadProperties(String filePath) {

        Properties properties = new Properties();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();

        if (LOG.isDebugEnabled()) {
            LOG.debug("Loading the file '" + filePath + "' from classpath");
        }

        InputStream in  = null;

        //if we reach to this assume that the we may have to looking to the customer provided external location for the
        //given properties
        if (System.getProperty(PassThroughConstants.CONF_LOCATION) != null) {
            try {
                in = new FileInputStream(System.getProperty(PassThroughConstants.CONF_LOCATION)
                        + File.separator + filePath);
            } catch (FileNotFoundException e) {
                String msg = "Error loading properties from a file at from the System defined location: " + filePath;
                LOG.warn(msg);
            }
        }


        if (in == null) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Unable to load file  '" + filePath + "'");
            }

            filePath = "conf" + File.separatorChar + filePath;
            if (LOG.isDebugEnabled()) {
                LOG.debug("Loading the file '" + filePath + "'");
            }

            in = cl.getResourceAsStream(filePath);
            if (in == null) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Unable to load file  '" + filePath + "'");
                }
            }
        }
        if (in != null) {
            try {
                properties.load(in);
            } catch (IOException e) {
                String msg = "Error loading properties from a file at : " + filePath;
                LOG.error(msg, e);
            }
        }
        return properties;
    }

    public Boolean getBooleanProperty(String name) {
        return ConfigurationBuilderUtil.getBooleanProperty(name, null, props);
    }

    public Boolean getBooleanProperty(String name, Boolean def) {
        return ConfigurationBuilderUtil.getBooleanProperty(name, def, props);
    }

    public Integer getIntProperty(String name, Integer def) {
        return ConfigurationBuilderUtil.getIntProperty(name, def, props);
    }

    public String getStringProperty(String name, String def) {
        return ConfigurationBuilderUtil.getStringProperty(name, def, props);
    }
}
