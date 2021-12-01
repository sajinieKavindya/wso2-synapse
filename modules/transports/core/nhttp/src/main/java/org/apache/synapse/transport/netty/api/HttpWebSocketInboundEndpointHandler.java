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

import org.apache.axiom.om.OMElement;
import org.apache.axis2.AxisFault;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.description.Parameter;
import org.apache.axis2.description.TransportInDescription;
import org.apache.axis2.description.TransportOutDescription;
import org.apache.axis2.transport.TransportListener;
import org.apache.axis2.transport.TransportSender;
import org.apache.axis2.transport.base.threads.WorkerPoolFactory;
import org.apache.log4j.Logger;
import org.apache.synapse.commons.handlers.MessagingHandler;
import org.apache.synapse.transport.netty.api.config.HttpWebSocketInboundEndpointConfiguration;
import org.apache.synapse.transport.netty.api.config.SSLConfiguration;
import org.apache.synapse.transport.netty.api.config.WorkerPoolConfiguration;
import org.apache.synapse.transport.netty.listener.Axis2HttpSSLTransportListener;
import org.apache.synapse.transport.netty.listener.Axis2HttpTransportListener;
import org.apache.synapse.transport.netty.sender.Axis2HttpTransportSender;

import java.util.List;
import java.util.Objects;

/**
 * API class to access Pass-Through (HTTP) and WebSocket Core Inbound Endpoint management classes.
 */
public class HttpWebSocketInboundEndpointHandler {

    private static final Logger LOGGER = Logger.getLogger(HttpWebSocketInboundEndpointHandler.class);

    /**
     * Start Endpoint Listen and events related to Endpoint handle by  given NHttpServerEventHandler.
     *
     * @param configurationContext Event Handler for handle events for Endpoint
     * @param httpWebSocketInboundEndpointConfiguration Event Handler for handle events for Endpoint
     * @return Is Endpoint started successfully
     */
    public static boolean startEndpoint(ConfigurationContext configurationContext,
                                        HttpWebSocketInboundEndpointConfiguration
                                                httpWebSocketInboundEndpointConfiguration) {

        // prepare Axis2HttpTransportListener
        Axis2HttpTransportListener transportListener = new Axis2HttpTransportListener();
        setWorkerPoolConfig(transportListener, httpWebSocketInboundEndpointConfiguration.getWorkerPoolConfiguration());

        List<MessagingHandler> inboundEndpointHandlers =
                httpWebSocketInboundEndpointConfiguration.getInboundEndpointHandlers();
        if (Objects.nonNull(inboundEndpointHandlers)) {
            transportListener.setMessagingHandlers(inboundEndpointHandlers);
        }

        // prepare Axis2HttpTransportSender
        TransportSender transportSender = new Axis2HttpTransportSender(inboundEndpointHandlers);

        return startServer(httpWebSocketInboundEndpointConfiguration.getPort(),
                "http", transportListener, transportSender, null,
                configurationContext, httpWebSocketInboundEndpointConfiguration.getEndpointName());
    }

    /**
     * Close ListeningEndpoint running on the given port.
     *
     * @param port Port of  ListeningEndpoint to be closed
     * @return IS successfully closed
     */
    public static boolean closeEndpoint(int port) {
        return true;
    }

    /**
     * Start SSL Endpoint Listen and events related to Endpoint handle by  given NHttpServerEventHandler.
     *
     * @return Started or Not
     */
    public static boolean startSSLEndpoint(ConfigurationContext configurationContext,
                                           HttpWebSocketInboundEndpointConfiguration
                                                   httpWebSocketInboundEndpointConfiguration) {
        // prepare Axis2HttpSSLTransportListener
        Axis2HttpSSLTransportListener transportListener = new Axis2HttpSSLTransportListener();
        setWorkerPoolConfig(transportListener, httpWebSocketInboundEndpointConfiguration.getWorkerPoolConfiguration());

        List<MessagingHandler> inboundEndpointHandlers =
                httpWebSocketInboundEndpointConfiguration.getInboundEndpointHandlers();
        if (Objects.nonNull(inboundEndpointHandlers)) {
            transportListener.setMessagingHandlers(inboundEndpointHandlers);
        }

        // prepare Axis2HttpTransportSender
        TransportSender transportSender = new Axis2HttpTransportSender(inboundEndpointHandlers);

        return startServer(httpWebSocketInboundEndpointConfiguration.getPort(),
                "https", transportListener, transportSender,
                httpWebSocketInboundEndpointConfiguration.getSslConfiguration(), configurationContext,
                httpWebSocketInboundEndpointConfiguration.getEndpointName());
    }

    private static boolean startServer(int port, String transportName, TransportListener transportListener,
                                       TransportSender transportSender, SSLConfiguration sslConfiguration,
                                       ConfigurationContext configurationContext, String endpointName) {

        TransportInDescription transportInDescription;
        try {
            transportInDescription = generateTransportInDescription(port, transportName,
                    transportListener, sslConfiguration);
        } catch (AxisFault e) {
            LOGGER.error("Error occurred while generating TransportInDescription. Hence, unable to"
                    + " start the " + transportName + " transport listener for endpoint : "
                    + endpointName + " on port " + port, e);
            return false;
        }

        TransportOutDescription transportOutDescription = new TransportOutDescription(transportName);
        transportOutDescription.setSender(transportSender);

        try {
            configurationContext.getAxisConfiguration().addTransportIn(transportInDescription);
            configurationContext.getAxisConfiguration().addTransportOut(transportOutDescription);

            transportListener.init(configurationContext, transportInDescription);
        } catch (AxisFault e) {
            LOGGER.error("Couldn't initialize the " + transportInDescription.getName()
                    + " transport listener for endpoint : " + endpointName + " on port "
                    + port, e);
            return false;
        }

        try {
            transportListener.start();
        } catch (AxisFault e) {
            LOGGER.error("Couldn't start the " + transportInDescription.getName()
                    + " transport listener for endpoint : " + endpointName + " on port "
                    + port, e);
            return false;
        }
        return true;
    }

    private static TransportInDescription generateTransportInDescription(int port, String transportName,
                                                                         TransportListener transportListener,
                                                                         SSLConfiguration sslConfiguration)
            throws AxisFault {
        TransportInDescription transportInDescription = new TransportInDescription(transportName);
        transportInDescription.setReceiver(transportListener);
        Parameter parameter = new Parameter();
        parameter.setName(TransportListener.PARAM_PORT);
        parameter.setValue(port);
        transportInDescription.addParameter(parameter);

        if (Objects.nonNull(sslConfiguration)) {
            injectSSLParameters(sslConfiguration, transportInDescription);
        }

        return transportInDescription;
    }

    private static void injectSSLParameters(SSLConfiguration sslConfiguration,
                                            TransportInDescription transportInDescription) throws AxisFault {
        addParameter(transportInDescription, sslConfiguration.getKeyStoreElement());
        addParameter(transportInDescription, sslConfiguration.getTrustStoreElement());
        addParameter(transportInDescription, sslConfiguration.getClientAuthElement());
        addParameter(transportInDescription, sslConfiguration.getHttpsProtocolElement());
        addParameter(transportInDescription, sslConfiguration.getSslProtocolElement());
        addParameter(transportInDescription, sslConfiguration.getRevocationVerifierElement());
        addParameter(transportInDescription, sslConfiguration.getPreferredCiphersElement());
    }

    private static void addParameter(TransportInDescription transportInDescription, OMElement parameterElement)
            throws AxisFault {
        Parameter parameter = new Parameter();
        parameter.setParameterElement(parameterElement);
        transportInDescription.addParameter(parameter);
    }

    private static void setWorkerPoolConfig(Axis2HttpTransportListener transportListener,
                                            WorkerPoolConfiguration workerPoolConfiguration) {
        if (Objects.nonNull(workerPoolConfiguration)) {
            transportListener.setWorkerPool(WorkerPoolFactory.getWorkerPool(
                    workerPoolConfiguration.getWorkerPoolCoreSize(),
                    workerPoolConfiguration.getWorkerPoolSizeMax(),
                    workerPoolConfiguration.getWorkerPoolThreadKeepAliveSec(),
                    workerPoolConfiguration.getWorkerPoolQueueLength(),
                    workerPoolConfiguration.getThreadGroupID(),
                    workerPoolConfiguration.getThreadID()));
        }
    }
}
