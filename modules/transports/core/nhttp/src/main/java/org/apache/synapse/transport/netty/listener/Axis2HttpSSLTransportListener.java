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
import org.apache.axis2.description.TransportInDescription;
import org.apache.synapse.commons.handlers.MessagingHandler;
import org.apache.synapse.transport.http.conn.Scheme;
import org.apache.synapse.transport.netty.util.RequestResponseUtils;
import org.wso2.transport.http.netty.contract.config.ListenerConfiguration;

import java.util.List;

/**
 * {@code Axis2HttpsTransportListener} is the Axis2 Transport Listener implementation for HTTPs transport.
 *
 */
public class Axis2HttpSSLTransportListener extends Axis2HttpTransportListener {

    private List<MessagingHandler> messagingHandlers;
    public Axis2HttpSSLTransportListener() {}

    public Axis2HttpSSLTransportListener(List<MessagingHandler> messagingHandlers) {
        this.messagingHandlers = messagingHandlers;
    }

    protected Scheme initScheme() {
        return new Scheme("https", 443, true);
    }

    protected ListenerConfiguration initListenerConfiguration(TransportInDescription transportInDescription)
            throws AxisFault {
        return RequestResponseUtils.getListenerConfig(transportInDescription, true);
    }

}
