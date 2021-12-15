package org.apache.synapse.transport.netty.sender;

import org.apache.synapse.commons.handlers.MessagingHandler;

import java.util.List;

public class Axis2HttpSSLTransportSender extends Axis2HttpTransportSender {

    public Axis2HttpSSLTransportSender() {

    }

    public Axis2HttpSSLTransportSender(List<MessagingHandler> messagingHandlers) {

        super(messagingHandlers);
    }
}
