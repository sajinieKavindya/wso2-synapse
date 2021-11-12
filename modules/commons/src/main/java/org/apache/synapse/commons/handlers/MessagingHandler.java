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
package org.apache.synapse.commons.handlers;

import org.apache.axis2.context.MessageContext;

/**
 * Interface for Inbound Messaging Handler.
 */
public interface MessagingHandler {

    /**
     * Handle source request.
     *
     * This method handles the inbound requests that come with request headers.
     * For example, WebSocket handshake request comes as a HTTP upgrade request
     * with headers and the message body comes as a WebSocket frame. In such scenarios,
     * this method can be invoked to handle the WebSocket handshake.
     *
     * @param axis2MessageContext axis2 message context.
     */
    HandlerResponse handleSourceRequest(MessageContext axis2MessageContext);

    /**
     * Handle Source Message.
     *
     * This method handles the entity body of the inbound request.
     * For example, WebSocket handshake request comes as a HTTP upgrade request and
     * the message body comes as a WebSocket frame. In such scenarios, this method
     * can be invoked to handle the WebSocket frame that represent the actual message.
     *
     * @param axis2MessageContext axis2 message context.
     */
    HandlerResponse handleSourceMessage(MessageContext axis2MessageContext);

    /**
     * Handle Error.
     *
     * This method is invoked whenever an exception occurs in the request flow
     * so that the implementations can clear any in-memory resources.
     *
     * @param connectionId Identifier of the connection.
     */
    void handleError(ConnectionId connectionId);

    /**
     * Triggered to release and dispose any sort of resources hold against a
     * particular message flow. For example, if a WebSocket connection is terminated,
     * this method can be used to notify the handler about this event.
     *
     * @param connectionId Identifier of the connection.
     */
    void dispose(ConnectionId connectionId);


}
