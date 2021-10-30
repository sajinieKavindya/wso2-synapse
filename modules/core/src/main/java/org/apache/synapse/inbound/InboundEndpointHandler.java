package org.apache.synapse.inbound;

import org.apache.axis2.context.MessageContext;

public interface InboundEndpointHandler {

    public boolean handleRequest(MessageContext messageContext, String protocol, String correlationId);

    public boolean handleResponse(MessageContext messageContext, String protocol, String correlationId);
}
