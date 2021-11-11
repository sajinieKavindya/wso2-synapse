package org.apache.synapse.commons.handlers;

/**
 * Message holder for inbound requests and messages.
 */
public class MessageHolder {

    private Object message;
    private Protocol protocol;

    public MessageHolder(Object message, Protocol protocol) {

        this.message = message;
        this.protocol = protocol;
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
}
