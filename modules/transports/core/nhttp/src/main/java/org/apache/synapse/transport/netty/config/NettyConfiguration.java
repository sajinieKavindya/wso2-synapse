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
package org.apache.synapse.transport.netty.config;

import org.apache.axis2.transport.base.threads.WorkerPool;
import org.apache.log4j.Logger;
import org.apache.synapse.transport.passthru.ConnectCallback;
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

    private static final Logger LOGGER = Logger.getLogger(NettyConfiguration.class);

    /**
     * Default tuning parameter values.
     */
    private static final int DEFAULT_WORKER_POOL_SIZE_CORE = 400;
    private static final int DEFAULT_WORKER_POOL_SIZE_MAX = 500;
    private static final int DEFAULT_WORKER_THREAD_KEEPALIVE_SEC = 60;
    private static final int DEFAULT_WORKER_POOL_QUEUE_LENGTH = -1;
    private static final int DEFAULT_IO_BUFFER_SIZE = 8 * 1024;


    //additional rest dispatch handlers
    private static final String REST_DISPATCHER_SERVICE = "rest.dispatcher.service";
    // URI configurations that determine if it requires custom rest dispatcher
    private static final String REST_URI_API_REGEX = "rest_uri_api_regex";
    private static final String REST_URI_PROXY_REGEX = "rest_uri_proxy_regex";
    // properties which are allowed to be directly pass through from request context to response context explicitly
    private static final String ALLOWED_RESPONSE_PROPERTIES = "allowed_response_properties";
    private static final String REQUEST_LIMIT_VALIDATION = "http.requestLimits.validation.enabled";
    private static final String MAX_STATUS_LINE_LENGTH = "http.requestLimits.maxStatusLineLength";
    private static final String MAX_HEADER_SIZE = "http.requestLimits.maxHeaderSize";
    private static final String MAX_ENTITY_BODY_SIZE = "http.requestLimits.maxEntityBodySize";
    private static final String HTTP_SOCKET_TIMEOUT = "http.socket.timeout";

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

    public int getIOBufferSize() {
        return ConfigurationBuilderUtil.getIntProperty(PassThroughConfigPNames.IO_BUFFER_SIZE,
                DEFAULT_IO_BUFFER_SIZE, props);
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
        return ConfigurationBuilderUtil.getIntProperty(MAX_STATUS_LINE_LENGTH, 4096, props);
    }

    public int getMaxHeaderSize() {
        return ConfigurationBuilderUtil.getIntProperty(MAX_HEADER_SIZE, 8192, props);
    }

    public int getMaxEntityBodySize() {
        return ConfigurationBuilderUtil.getIntProperty(MAX_ENTITY_BODY_SIZE, -1, props);
    }

    public int getSocketTimeout() {
        return ConfigurationBuilderUtil.getIntProperty(HTTP_SOCKET_TIMEOUT, 180000, props);
    }

    public boolean isKeepAliveDisabled() {
        if (isKeepAliveDisabled == null) {
            isKeepAliveDisabled = ConfigurationBuilderUtil
                    .getBooleanProperty(PassThroughConfigPNames.DISABLE_KEEPALIVE, false, props);
        }
        return isKeepAliveDisabled;
    }

    public String getHttpGetRequestProcessorClass() {
        return ConfigurationBuilderUtil
                    .getStringProperty(NettyConfigPropertyNames.HTTP_GET_REQUEST_PROCESSOR, "", props);

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

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Loading the file '" + filePath + "' from classpath");
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
                LOGGER.warn(msg);
            }
        }


        if (in == null) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Unable to load file  '" + filePath + "'");
            }

            filePath = "conf" + File.separatorChar + filePath;
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Loading the file '" + filePath + "'");
            }

            in = cl.getResourceAsStream(filePath);
            if (in == null) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Unable to load file  '" + filePath + "'");
                }
            }
        }
        if (in != null) {
            try {
                properties.load(in);
            } catch (IOException e) {
                String msg = "Error loading properties from a file at : " + filePath;
                LOGGER.error(msg, e);
            }
        }
        return properties;
    }
}
