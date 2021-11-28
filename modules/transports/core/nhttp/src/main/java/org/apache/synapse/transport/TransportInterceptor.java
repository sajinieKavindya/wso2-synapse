package org.apache.synapse.transport;

import org.apache.axis2.context.MessageContext;

import java.io.IOException;
import java.io.InputStream;
import javax.xml.stream.XMLStreamException;

public interface TransportInterceptor {

    public InputStream getMessageDataStream(MessageContext context) throws IOException;

    public void buildMessage(MessageContext messageContext) throws XMLStreamException, IOException;

    public void buildMessage(MessageContext messageContext, boolean earlyBuild) throws XMLStreamException, IOException;

}
