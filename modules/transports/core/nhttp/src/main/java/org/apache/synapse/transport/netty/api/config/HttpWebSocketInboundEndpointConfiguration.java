package org.apache.synapse.transport.netty.api.config;

import org.apache.synapse.commons.handlers.MessagingHandler;

import java.util.List;

/**
 * {@code HttpWebSocketInboundEndpointConfiguration} encapsulates the configurations that are passed from the
 * inbound endpoints to be used when initializing and starting the listener.
 */
public class HttpWebSocketInboundEndpointConfiguration {

    private int port;
    private String endpointName;
    private WorkerPoolConfiguration workerPoolConfiguration;
    private SSLConfiguration sslConfiguration;
    private List<MessagingHandler> inboundEndpointHandlers;

    public WorkerPoolConfiguration getWorkerPoolConfiguration() {

        return workerPoolConfiguration;
    }

    public void setWorkerPoolConfiguration(WorkerPoolConfiguration workerPoolConfiguration) {

        this.workerPoolConfiguration = workerPoolConfiguration;
    }

    public SSLConfiguration getSslConfiguration() {

        return sslConfiguration;
    }

    public void setSslConfiguration(SSLConfiguration sslConfiguration) {

        this.sslConfiguration = sslConfiguration;
    }

    public List<MessagingHandler> getInboundEndpointHandlers() {

        return inboundEndpointHandlers;
    }

    public void setInboundEndpointHandlers(List<MessagingHandler> inboundEndpointHandlers) {

        this.inboundEndpointHandlers = inboundEndpointHandlers;
    }

    public String getEndpointName() {

        return endpointName;
    }

    public void setEndpointName(String endpointName) {

        this.endpointName = endpointName;
    }

    public int getPort() {

        return port;
    }

    public void setPort(int port) {

        this.port = port;
    }
}
