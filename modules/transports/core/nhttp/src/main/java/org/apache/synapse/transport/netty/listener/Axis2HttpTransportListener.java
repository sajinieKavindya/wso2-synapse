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
import org.apache.axis2.description.TransportInDescription;
import org.apache.axis2.transport.TransportListener;
import org.apache.log4j.Logger;
import org.apache.synapse.commons.handlers.MessagingHandler;
import org.apache.synapse.transport.http.conn.Scheme;
import org.apache.synapse.transport.netty.BridgeConstants;
import org.apache.synapse.transport.netty.config.SourceConfiguration;
import org.apache.synapse.transport.netty.util.RequestResponseUtils;
import org.wso2.transport.http.netty.contract.HttpWsConnectorFactory;
import org.wso2.transport.http.netty.contract.ServerConnector;
import org.wso2.transport.http.netty.contract.ServerConnectorFuture;
import org.wso2.transport.http.netty.contract.config.ListenerConfiguration;
import org.wso2.transport.http.netty.contract.config.ServerBootstrapConfiguration;
import org.wso2.transport.http.netty.contractimpl.DefaultHttpWsConnectorFactory;

import java.util.HashMap;
import java.util.List;

/**
 * {@code Axis2HttpTransportListener} is the Axis2 Transport Listener implementation for HTTP transport based on Netty.
 */
public class Axis2HttpTransportListener implements TransportListener {

    private static final Logger LOG = Logger.getLogger(Axis2HttpTransportListener.class);

    private ServerConnector serverConnector;
    protected SourceConfiguration sourceConfiguration = null;
    protected List<MessagingHandler> messagingHandlers;

    public Axis2HttpTransportListener() {}

    public Axis2HttpTransportListener(List<MessagingHandler> messagingHandlers) {
        this.messagingHandlers = messagingHandlers;
    }

    @Override
    public void init(ConfigurationContext configurationContext, TransportInDescription transportInDescription)
            throws AxisFault {

        Scheme scheme = initScheme();
        sourceConfiguration = new SourceConfiguration(configurationContext, transportInDescription, scheme,
                messagingHandlers);
        sourceConfiguration.build();

        if (sourceConfiguration.getHttpGetRequestProcessor() != null) {
            sourceConfiguration.getHttpGetRequestProcessor().init(sourceConfiguration.getConfigurationContext(), null);
        }

        ListenerConfiguration listenerConfiguration = initListenerConfiguration(transportInDescription);

        HttpWsConnectorFactory httpWsConnectorFactory = new DefaultHttpWsConnectorFactory();
        this.serverConnector = httpWsConnectorFactory
                .createServerConnector(new ServerBootstrapConfiguration(new HashMap<>()), listenerConfiguration);
    }

    @Override
    public void start() {
        ServerConnectorFuture serverConnectorFuture = serverConnector.start();
        serverConnectorFuture.setHttpConnectorListener(
                new PassThroughHttpConnectorListener(sourceConfiguration));
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

    protected Scheme initScheme() {
        return new Scheme("http", 80, false);
    }

    protected ListenerConfiguration initListenerConfiguration(TransportInDescription transportInDescription)
            throws AxisFault {
        return RequestResponseUtils.getListenerConfig(transportInDescription, false);
    }
}
