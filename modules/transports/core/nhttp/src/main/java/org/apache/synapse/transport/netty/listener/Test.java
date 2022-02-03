package org.apache.synapse.transport.netty.listener;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.commons.handlers.MessagingHandler;
import org.wso2.transport.http.netty.contract.websocket.WebSocketBinaryMessage;
import org.wso2.transport.http.netty.contract.websocket.WebSocketCloseMessage;
import org.wso2.transport.http.netty.contract.websocket.WebSocketConnection;
import org.wso2.transport.http.netty.contract.websocket.WebSocketConnectorListener;
import org.wso2.transport.http.netty.contract.websocket.WebSocketControlMessage;
import org.wso2.transport.http.netty.contract.websocket.WebSocketHandshaker;
import org.wso2.transport.http.netty.contract.websocket.WebSocketTextMessage;

import java.util.List;

public class Test implements WebSocketConnectorListener {

    private static final Log LOGGER = LogFactory.getLog(Test.class);

//    List<MessagingHandler> messagingHandlers;

//    public Test(List<MessagingHandler> messagingHandlers) {
//        this.messagingHandlers = messagingHandlers;
//    }

    @Override
    public void onHandshake(WebSocketHandshaker webSocketHandshaker) {

        LOGGER.info("testing testing!");
    }

    @Override
    public void onMessage(WebSocketTextMessage webSocketTextMessage) {

        LOGGER.info("testing testing!");
    }

    @Override
    public void onMessage(WebSocketBinaryMessage webSocketBinaryMessage) {

        LOGGER.info("testing testing!");
    }

    @Override
    public void onMessage(WebSocketControlMessage webSocketControlMessage) {

        LOGGER.info("testing testing!");
    }

    @Override
    public void onMessage(WebSocketCloseMessage webSocketCloseMessage) {

        LOGGER.info("testing testing!");
    }

    @Override
    public void onClose(WebSocketConnection webSocketConnection) {

        LOGGER.info("testing testing!");
    }

    @Override
    public void onError(WebSocketConnection webSocketConnection, Throwable throwable) {

        LOGGER.info("testing testing!");
    }

    @Override
    public void onIdleTimeout(WebSocketControlMessage webSocketControlMessage) {

        LOGGER.info("testing testing!");
    }
}
