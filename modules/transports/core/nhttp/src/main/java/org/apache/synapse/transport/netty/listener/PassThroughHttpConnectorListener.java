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

import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.description.TransportInDescription;
import org.apache.axis2.transport.base.threads.WorkerPool;
import org.apache.log4j.Logger;
import org.apache.synapse.commons.handlers.HandlerExecutor;
import org.apache.synapse.commons.handlers.MessagingHandler;
import org.apache.synapse.transport.netty.BridgeConstants;
import org.wso2.transport.http.netty.contract.HttpConnectorListener;
import org.wso2.transport.http.netty.message.HttpCarbonMessage;

import java.util.List;

/**
 * {@code ConnectorListenerToAxisBridge} receives the {@code HttpCarbonMessage} coming from the Netty HTTP transport,
 * converts them to {@code MessageContext} and finally deliver them to the axis engine.
 *
 */
public class PassThroughHttpConnectorListener implements HttpConnectorListener {

    private static final Logger LOG = Logger.getLogger(PassThroughHttpConnectorListener.class);

    private ConfigurationContext configurationContext;
    private WorkerPool workerPool;
    private TransportInDescription transportInDescription;
    private List<MessagingHandler> messagingHandlers;

    public PassThroughHttpConnectorListener(ConfigurationContext configurationContext, WorkerPool workerPool,
                                            TransportInDescription transportInDescription,
                                            List<MessagingHandler> messagingHandlers) {
        this.configurationContext = configurationContext;
        this.workerPool = workerPool;
        this.transportInDescription = transportInDescription;
        this.messagingHandlers = messagingHandlers;
    }

    public void onMessage(HttpCarbonMessage httpCarbonMessage) {
        LOG.debug(BridgeConstants.BRIDGE_LOG_PREFIX + "Message received to HTTP transport, submitting a worker to " +
                "the pool to process");
        workerPool.execute(new HttpRequestWorker(httpCarbonMessage, configurationContext, transportInDescription,
                messagingHandlers));
    }

    public void onError(Throwable throwable) {
    }


}
