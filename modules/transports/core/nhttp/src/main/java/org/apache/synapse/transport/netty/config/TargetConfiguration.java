package org.apache.synapse.transport.netty.config;

import org.apache.axis2.AxisFault;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.description.TransportOutDescription;
import org.apache.axis2.transport.base.threads.WorkerPool;
import org.apache.synapse.commons.handlers.MessagingHandler;

import java.util.List;

public class TargetConfiguration extends BaseConfiguration {

    private TransportOutDescription transportOutDescription;

    private List<MessagingHandler> messagingHandlers;

    public TargetConfiguration(ConfigurationContext configurationContext,
                               TransportOutDescription transportOutDescription,
                               List<MessagingHandler> messagingHandlers, WorkerPool workerPool) {

        super(configurationContext, workerPool);
        this.transportOutDescription = transportOutDescription;
        this.messagingHandlers = messagingHandlers;
    }

    public TargetConfiguration(ConfigurationContext configurationContext,
                               TransportOutDescription transportOutDescription,
                               List<MessagingHandler> messagingHandlers) {

        this(configurationContext, transportOutDescription, messagingHandlers, null);
    }

    public void build() throws AxisFault {
        super.build();
        preserveUserAgentHeader = conf.isPreserveUserAgentHeader();
        preserveServerHeader = conf.isPreserveServerHeader();
        populatePreserveHttpHeaders(conf.getPreserveHttpHeaders());
    }

    public List<MessagingHandler> getMessagingHandlers() {

        return messagingHandlers;
    }

    public void setMessagingHandlers(List<MessagingHandler> messagingHandlers) {

        this.messagingHandlers = messagingHandlers;
    }

    public TransportOutDescription getTransportOutDescription() {

        return transportOutDescription;
    }

    public void setTransportOutDescription(TransportOutDescription transportOutDescription) {

        this.transportOutDescription = transportOutDescription;
    }
}
