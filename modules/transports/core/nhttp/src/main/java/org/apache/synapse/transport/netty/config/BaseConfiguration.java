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

import org.apache.axis2.AxisFault;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.transport.base.threads.WorkerPool;
import org.apache.axis2.transport.base.threads.WorkerPoolFactory;
import org.apache.http.protocol.HTTP;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public abstract class BaseConfiguration {

    /** The Axis2 ConfigurationContext */
    protected ConfigurationContext configurationContext = null;
    /** Weather User-Agent header coming from client should be preserved */
    protected boolean preserveUserAgentHeader = false;
    /** Weather Server header coming from server should be preserved */
    protected boolean preserveServerHeader = true;
    /** Http headers which should be preserved */
    protected List<String> preserveHttpHeaders;
    /** The thread pool for executing the messages passing through */
    private WorkerPool workerPool = null;

    private Boolean correlationLoggingEnabled = false;

    private static final String HTTP_WORKER_THREAD_GROUP_NAME = "HTTP Worker Thread Group";
    private static final String HTTP_WORKER_THREAD_ID = "HTTPWorker";

    NettyConfiguration conf = NettyConfiguration.getInstance();

    public BaseConfiguration(ConfigurationContext configurationContext, WorkerPool workerPool) {

        this.configurationContext = configurationContext;
        this.workerPool = workerPool;
    }

    public void build() throws AxisFault {
        if (Objects.isNull(workerPool)) {
            workerPool = WorkerPoolFactory.getWorkerPool(
                    conf.getWorkerPoolCoreSize(),
                    conf.getWorkerPoolMaxSize(),
                    conf.getWorkerThreadKeepaliveSec(),
                    conf.getWorkerPoolQueueLen(),
                    HTTP_WORKER_THREAD_GROUP_NAME,
                    HTTP_WORKER_THREAD_ID);
        }
    }

    /**
     * Check preserving status of the given http header name
     *
     * @param headerName http header name which need to check preserving status
     * @return preserving status of the given http header
     */
    public boolean isPreserveHttpHeader(String headerName) {

        if (preserveHttpHeaders == null || preserveHttpHeaders.isEmpty() || headerName == null) {
            return false;
        }
        return preserveHttpHeaders.contains(headerName.toUpperCase());
    }

    public List<String> getPreserveHttpHeaders() {
        return preserveHttpHeaders;
    }

    /**
     * Populate preserve http headers from comma separate string.
     *
     * @param preserveHeaders Comma separated preserve enable http headers
     */
    protected void populatePreserveHttpHeaders(String preserveHeaders) {

        preserveHttpHeaders = new ArrayList<String>();
        if (preserveHeaders != null && !preserveHeaders.isEmpty()) {
            String[] presHeaders = preserveHeaders.trim().toUpperCase().split(",");
            if (presHeaders.length > 0) {
                preserveHttpHeaders.addAll(Arrays.asList(presHeaders));
            }
        }

        if (preserveServerHeader && !preserveHttpHeaders.contains(HTTP.SERVER_HEADER.toUpperCase())) {
            preserveHttpHeaders.add(HTTP.SERVER_HEADER.toUpperCase());
        }

        if (preserveUserAgentHeader && !preserveHttpHeaders.contains(HTTP.USER_AGENT.toUpperCase())) {
            preserveHttpHeaders.add(HTTP.USER_AGENT.toUpperCase());
        }
    }

    public WorkerPool getWorkerPool() {
        return workerPool;
    }

    public ConfigurationContext getConfigurationContext() {
        return configurationContext;
    }
}
