package org.apache.synapse.transport.netty.listener;

import org.apache.synapse.commons.handlers.MessagingHandler;
import org.wso2.transport.http.netty.contract.websocket.WebSocketBinaryMessage;
import org.wso2.transport.http.netty.contract.websocket.WebSocketCloseMessage;
import org.wso2.transport.http.netty.contract.websocket.WebSocketConnection;
import org.wso2.transport.http.netty.contract.websocket.WebSocketConnectorListener;
import org.wso2.transport.http.netty.contract.websocket.WebSocketControlMessage;
import org.wso2.transport.http.netty.contract.websocket.WebSocketHandshaker;
import org.wso2.transport.http.netty.contract.websocket.WebSocketTextMessage;

import java.util.List;

/**
 * Server Connector listener for WebSocket.
 */
public class WebSocketServerListener implements WebSocketConnectorListener {

    List<MessagingHandler> messagingHandlers;

    public WebSocketServerListener(List<MessagingHandler> messagingHandlers) {
        this.messagingHandlers = messagingHandlers;
    }

    @Override
    public void onHandshake(WebSocketHandshaker webSocketHandshaker) {

    }

    @Override
    public void onMessage(WebSocketTextMessage webSocketTextMessage) {

    }

    @Override
    public void onMessage(WebSocketBinaryMessage webSocketBinaryMessage) {

    }

    @Override
    public void onMessage(WebSocketControlMessage webSocketControlMessage) {

    }

    @Override
    public void onMessage(WebSocketCloseMessage webSocketCloseMessage) {

    }

    @Override
    public void onClose(WebSocketConnection webSocketConnection) {

    }

    @Override
    public void onError(WebSocketConnection webSocketConnection, Throwable throwable) {

    }

    @Override
    public void onIdleTimeout(WebSocketControlMessage webSocketControlMessage) {

    }
}
