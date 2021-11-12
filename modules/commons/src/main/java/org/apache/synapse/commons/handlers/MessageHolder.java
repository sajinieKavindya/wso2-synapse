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

/**
 * Message holder for inbound requests/messages.
 */
public class MessageHolder {

    /**
     * The message to be passed to the handler implementations to perform message
     * handling. This is in Object type since there are variety of message types in
     * different protocols. For example, the WebSocket handshake is a type of
     * FullHttpRequest while the WebSocket message is a type of WebSocketFrame.
     */
    private Object message;

    /**
     * The protocol of the transport that the message belongs to.
     */
    private Protocol protocol;

    public ConnectionId connectionId;

    public MessageHolder(Object message, Protocol protocol, ConnectionId connectionId) {

        this.message = message;
        this.protocol = protocol;
        this.connectionId = connectionId;
    }

    public Object getMessage() {
        return message;
    }

    public void setMessage(Object message) {
        this.message = message;
    }

    public Protocol getProtocol() {
        return protocol;
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    public ConnectionId getConnectionId() {

        return connectionId;
    }

    public void setConnectionId(ConnectionId connectionId) {

        this.connectionId = connectionId;
    }
}
