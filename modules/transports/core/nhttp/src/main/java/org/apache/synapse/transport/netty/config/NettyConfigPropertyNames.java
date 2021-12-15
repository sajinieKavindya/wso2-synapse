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

public class NettyConfigPropertyNames {

    /**
     * Defines the core size (number of threads) of the worker thread pool.
     */
    public static final String WORKER_POOL_SIZE_CORE = "worker_pool_size_core";

    /**
     * Defines the maximum size (number of threads) of the worker thread pool.
     */
    public static final String WORKER_POOL_SIZE_MAX = "worker_pool_size_max";

    /**
     * Defines the keep-alive time for extra threads in the worker pool.
     */
    public static final String WORKER_THREAD_KEEP_ALIVE_SEC = "worker_thread_keepalive_sec";

    /**
     * Defines the length of the queue that is used to hold Runnable tasks to be executed by the
     * worker pool.
     */
    public static final String WORKER_POOL_QUEUE_LENGTH = "worker_pool_queue_length";

    /**
     * Defines the number of IO dispatcher threads used per reactor.
     */
    public static final String IO_THREADS_PER_REACTOR = "io_threads_per_reactor";

    /**
     * Defines the IO buffer size.
     */
    public static final String IO_BUFFER_SIZE = "io_buffer_size";

    /**
     * Defines the maximum open connection limit.
     */
    public static final String C_MAX_ACTIVE = "max_open_connections";

    /**
     * Defines whether ESB needs to preserve the original User-Agent header.
     */
    public static final String USER_AGENT_HEADER_PRESERVE = "http.user.agent.preserve";

    /**
     * Defines whether ESB needs to preserve the original Server header.
     */
    public static final String SERVER_HEADER_PRESERVE = "http.server.preserve";

    /**
     * Defines whether ESB needs to preserve the original Http header.
     */
    public static final String HTTP_RESPONSE_HEADERS_PRESERVE = "http.response.headers.preserve";

    /**
     * Defines whether ESB needs to preserve the original Http header.
     */
    public static final String HTTP_HEADERS_PRESERVE = "http.headers.preserve";

    /**
     * Defines whether HTTP keep-alive is disabled.
     */
    public static final String DISABLE_KEEPALIVE = "http.connection.disable.keepalive";

    /**
     * Defines the time interval for idle connection removal.
     */
    public static final String CONNECTION_IDLE_TIME = "transport.sender.connection.idle.time";

    /**
     * Defines the time allocated to avoid a connection being used
     * at the moment it is being closed or timed out in milliseconds.
     */
    public static final String CONNECTION_GRACE_TIME = "transport.sender.connection.grace.time";

    /**
     * Defines the time interval for maximum connection lifespan.
     */
    public static final String MAXIMUM_CONNECTION_LIFESPAN = "transport.sender.connection.maximum.lifespan";

    /**
     * Defines the maximum number of connections per host port.
     */
    public static final String MAX_CONNECTION_PER_HOST_PORT = "http.max.connection.per.host.port";

    /**
     * Defines the maximum number of waiting messages per host port.
     */
    public static final String MAX_MESSAGES_PER_HOST_PORT = "http.max.messages.per.host.port";

    public static final String TRANSPORT_LISTENER_SHUTDOWN_WAIT_TIME_SEC = "transport.listener.shutdown.wait.sec";

    /**
     * Defines whether Listening IOReactor is shared among non axis2 Listeners.
     */
    public static final String HTTP_LISTENING_IO_REACTOR_SHARING_ENABLE = "http_listening_io_reactor_sharing_enable";

    /**
     * Defines the header name set for correlation logs.
     */
    public static final String CORRELATION_HEADER_NAME_PROPERTY = "correlation_header_name";

    public static final String HTTP_GET_REQUEST_PROCESSOR = "http.get.request.processor";

    public static final String HTTP_LISTENER_HOSTNAME = "http.listener.hostname";

    public static final String HTTP_TRANSPORT_MEDIATION_INTERCEPTOR = "http.transport.mediation.interceptor";

    public static final String HTTP_BLOCK_SERVICE_LIST = "http.block_service_list";

    public static final String CLIENT_SSL_KEYSTORE_LOCATION = "client.ssl.keystore.location";
    public static final String CLIENT_SSL_KEYSTORE_TYPE = "client.ssl.keystore.type";
    public static final String CLIENT_SSL_KEYSTORE_PASSWORD = "client.ssl.keystore.password";
    public static final String CLIENT_SSL_KEYSTORE_KEYPASSWORD = "client.ssl.keystore.keypassword";
    public static final String CLIENT_SSL_TRUSTSTORE_LOCATION = "client.ssl.truststore.location";
    public static final String CLIENT_SSL_TRUSTSTORE_TYPE = "client.ssl.truststore.type";
    public static final String CLIENT_SSL_TRUSTSTORE_PASSWORD = "client.ssl.truststore.password";
    public static final String CLIENT_SSL_HTTPS_PROTOCOLS = "client.ssl.https.protocols";
    public static final String CLIENT_SSL_PROTOCOL = "client.ssl.protocol";
    public static final String CLIENT_SSL_PREFERRED_CIPHERS = "client.ssl.preferred.ciphers";
    public static final String CLIENT_SSL_SESSION_TIMEOUT = "client.ssl.session.timeout.in.seconds";
    public static final String CLIENT_SSL_HANDSHAKE_TIMEOUT = "client.ssl.handshake.timeout.in.seconds";
    public static final String CLIENT_SSL_DISABLE_CERT_VALIDATION = "client.ssl.disable.cert.validation";
    public static final String CLIENT_SSL_HOSTNAME_VERIFIER = "client.ssl.hostname.verifier";
    public static final String CLIENT_SSL_CERTIFICATE_REVOCATION_VERIFIER_ENABLE
            = "client.ssl.certificate.revocation.verifier.enable";
    public static final String CLIENT_SSL_CERTIFICATE_REVOCATION_VERIFIER_CACHE_SIZE
            = "client.ssl.certificate.revocation.verifier.cache.size";
    public static final String CLIENT_SSL_CERTIFICATE_REVOCATION_VERIFIER_CACHE_DELAY
            = "client.ssl.certificate.revocation.verifier.cache.delay";

}
