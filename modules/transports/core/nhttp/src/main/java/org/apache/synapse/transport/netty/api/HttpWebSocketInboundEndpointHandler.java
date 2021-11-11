/*
 * Copyright (c) 2021, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.synapse.transport.netty.api;

import org.apache.axis2.AxisFault;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.description.Parameter;
import org.apache.axis2.description.TransportInDescription;
import org.apache.axis2.transport.TransportListener;
import org.apache.http.nio.NHttpServerEventHandler;
import org.apache.log4j.Logger;
import org.apache.synapse.commons.handlers.MessagingHandler;
import org.apache.synapse.transport.netty.listener.Axis2HttpTransportListener;
import org.apache.synapse.transport.passthru.core.PassThroughListeningIOReactorManager;
import org.apache.synapse.transport.passthru.core.ssl.SSLConfiguration;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;

/**
 * API class to access Pass-Through (HTTP) and WebSocket Core Inbound Endpoint management classes.
 */
public class HttpWebSocketInboundEndpointHandler {

    private static final Logger LOGGER = Logger.getLogger(HttpWebSocketInboundEndpointHandler.class);

    /**
     * Start Endpoint Listen and events related to Endpoint handle by  given NHttpServerEventHandler.
     *
     * @param inetSocketAddress       Socket Address of the Endpoint need to be start by underlying IOReactor
     * @param configurationContext Event Handler for handle events for Endpoint
     * @param messagingHandlers Event Handler for handle events for Endpoint
     * @param endpointName            Name of the Endpoint
     * @return Is Endpoint started successfully
     */
    public static boolean startEndpoint(InetSocketAddress inetSocketAddress, ConfigurationContext configurationContext,
                                     List<MessagingHandler> messagingHandlers, String endpointName) {
        TransportInDescription transportInDescription = new TransportInDescription("http");
        try {
            Parameter parameter = new Parameter();
            parameter.setName("port");
            parameter.setValue(inetSocketAddress.getPort());
            transportInDescription.addParameter(parameter);

            TransportListener transportListener = new Axis2HttpTransportListener(messagingHandlers);
            transportListener.init(configurationContext, transportInDescription);
            transportListener.start();
            return true;

        } catch (AxisFault e) {
            LOGGER.error("Exception occurred while starting the " + transportInDescription.getName()
                    + " transport listener for endpoint : " + endpointName + " on port "
                    + inetSocketAddress.getPort(), e);
        }
        return false;
    }

    /**
     * Close ListeningEndpoint running on the given port.
     *
     * @param port Port of  ListeningEndpoint to be closed
     * @return IS successfully closed
     */
    public static boolean closeEndpoint(int port) {
        return PassThroughListeningIOReactorManager.getInstance().closeDynamicPTTEndpoint(port);
    }

    /**
     * Check Whether inbound endpoint is running for a particular port.
     *
     * @param port port
     * @return whether inbound endpoint is running
     */
    public static boolean isEndpointRunning(int port) {
        return PassThroughListeningIOReactorManager.getInstance().isDynamicEndpointRunning(port);
    }

    /**
     * Start SSL Endpoint Listen and events related to Endpoint handle by  given NHttpServerEventHandler.
     *
     * @param inetSocketAddress Socket Address of the Endpoint need to be start by underlying IOReactor
     * @param nHttpServerEventHandler Event Handler for handle events for Endpoint
     * @param endpointName  Name of the Endpoint
     * @param sslConfiguration SSL Configuration
     * @return Started or Not
     */
    public static boolean startSSLEndpoint(InetSocketAddress inetSocketAddress,
                                           NHttpServerEventHandler nHttpServerEventHandler, String endpointName,
                                            SSLConfiguration sslConfiguration) {
        return PassThroughListeningIOReactorManager.getInstance().
                   startDynamicPTTSSLEndpoint(inetSocketAddress, nHttpServerEventHandler,
                                              endpointName, sslConfiguration);
    }

    public static boolean isPortAvailable(int port) {
        try {
            ServerSocket ss = new ServerSocket(port);
            ss.close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

}
