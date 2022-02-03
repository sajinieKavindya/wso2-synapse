package org.apache.synapse.transport.netty.listener;

import org.apache.synapse.commons.handlers.MessagingHandler;
import org.wso2.transport.http.netty.contract.websocket.WebSocketConnectorListener;

import java.util.List;

/**
 * Server Connector listener for WebSocket.
 */
public interface WebSocketServerListener extends WebSocketConnectorListener {

    public void setMessagingHandlers(List<MessagingHandler> messagingHandlers);

}
