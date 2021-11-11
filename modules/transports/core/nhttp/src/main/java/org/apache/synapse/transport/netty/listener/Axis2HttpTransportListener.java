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
package org.apache.synapse.transport.netty.listener;

import org.apache.axis2.AxisFault;
import org.apache.axis2.addressing.EndpointReference;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.context.SessionContext;
import org.apache.axis2.description.Parameter;
import org.apache.axis2.description.TransportInDescription;
import org.apache.axis2.transport.TransportListener;
import org.apache.axis2.transport.base.threads.WorkerPool;
import org.apache.axis2.transport.base.threads.WorkerPoolFactory;
import org.apache.log4j.Logger;
import org.apache.synapse.commons.handlers.MessagingHandler;
import org.apache.synapse.transport.netty.BridgeConstants;
import org.wso2.transport.http.netty.contract.HttpWsConnectorFactory;
import org.wso2.transport.http.netty.contract.ServerConnector;
import org.wso2.transport.http.netty.contract.ServerConnectorFuture;
import org.wso2.transport.http.netty.contract.config.ListenerConfiguration;
import org.wso2.transport.http.netty.contract.config.ServerBootstrapConfiguration;
import org.wso2.transport.http.netty.contractimpl.DefaultHttpWsConnectorFactory;

import java.util.HashMap;
import java.util.List;

/**
 * {@code Axis2HttpTransportListener} is the Axis2 Transport Listener implementation for HTTP transport.
 *
 */
public class Axis2HttpTransportListener implements TransportListener {

    private List<MessagingHandler> messagingHandlers;

    private static final Logger LOG = Logger.getLogger(Axis2HttpTransportListener.class);

    private ServerConnector serverConnector;

    private TransportInDescription transportInDescription;

    private ConfigurationContext configurationContext;

    private WorkerPool workerPool;

    public Axis2HttpTransportListener() {}

    public Axis2HttpTransportListener(List<MessagingHandler> messagingHandlers) {
        this.messagingHandlers = messagingHandlers;
    }

    @Override
    public void init(ConfigurationContext configurationContext, TransportInDescription transportInDescription) {

        this.configurationContext = configurationContext;
        this.transportInDescription = transportInDescription;
        workerPool = WorkerPoolFactory.getWorkerPool(BridgeConstants.DEFAULT_WORKER_POOL_SIZE_CORE,
                BridgeConstants.DEFAULT_WORKER_POOL_SIZE_MAX,
                BridgeConstants.DEFAULT_WORKER_THREAD_KEEPALIVE_SEC,
                BridgeConstants.DEFAULT_WORKER_POOL_QUEUE_LENGTH,
                BridgeConstants.HTTP_WORKER_THREAD_GROUP_NAME,
                BridgeConstants.HTTP_WORKER_THREAD_ID);

        ListenerConfiguration listenerConfiguration = new ListenerConfiguration();
        Parameter portParam = transportInDescription.getParameter("port");
        int port = Integer.parseInt(portParam.getValue().toString());
        listenerConfiguration.setPort(port);
        listenerConfiguration.setHost("localhost");

        HttpWsConnectorFactory httpWsConnectorFactory = new DefaultHttpWsConnectorFactory();
        this.serverConnector = httpWsConnectorFactory
                .createServerConnector(new ServerBootstrapConfiguration(new HashMap<>()), listenerConfiguration);
    }

    @Override
    public void start() {
        ServerConnectorFuture serverConnectorFuture = serverConnector.start();
        serverConnectorFuture.setHttpConnectorListener(
                new PassThroughHttpConnectorListener(configurationContext, workerPool,
                        transportInDescription, messagingHandlers));
        serverConnectorFuture.setWebSocketConnectorListener(new WebSocketServerListener(messagingHandlers));
        try {
            serverConnectorFuture.sync();
        } catch (InterruptedException e) {
            LOG.warn(BridgeConstants.BRIDGE_LOG_PREFIX + "Interrupted while waiting for server connector to start", e);
        }
    }

    @Override
    public void stop() {
    }

    @Override
    public EndpointReference getEPRForService(String s, String s1) throws AxisFault {
        return null;
    }

    @Override
    public EndpointReference[] getEPRsForService(String s, String s1) {
        return new EndpointReference[0];
    }

    @Override
    public SessionContext getSessionContext(MessageContext messageContext) {
        return null;
    }

    @Override
    public void destroy() {

    }
}
