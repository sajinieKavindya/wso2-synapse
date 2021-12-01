package org.apache.synapse.transport;

import org.apache.axis2.context.MessageContext;
import org.apache.synapse.transport.netty.BridgeConstants;
import org.apache.synapse.transport.netty.util.MessageUtils;
import org.apache.synapse.transport.passthru.PassThroughConstants;
import org.wso2.transport.http.netty.message.HttpCarbonMessage;
import org.wso2.transport.http.netty.message.HttpMessageDataStreamer;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public class NettyHttpTransportInterceptor implements TransportInterceptor {

    @Override
    public InputStream getMessageDataStream(MessageContext msgContext) {

        HttpCarbonMessage carbonMessage =
                (HttpCarbonMessage) msgContext.getProperty(BridgeConstants.HTTP_CARBON_MESSAGE);
        if (Objects.isNull(carbonMessage)) {
            return null;
        }

        BufferedInputStream bufferedInputStream;
        if (msgContext.getProperty(PassThroughConstants.BUFFERED_INPUT_STREAM) != null) {
            bufferedInputStream =
                    (BufferedInputStream) msgContext.getProperty(PassThroughConstants.BUFFERED_INPUT_STREAM);
            try {
                bufferedInputStream.reset();
                bufferedInputStream.mark(0);
            } catch (Exception e) {
                //just ignore the error
            }
        } else {
            HttpMessageDataStreamer httpMessageDataStreamer = new HttpMessageDataStreamer(carbonMessage);
            bufferedInputStream = new BufferedInputStream(httpMessageDataStreamer.getInputStream());
            // Multiplied it by two because we always need a bigger read-limit than the buffer size.
            bufferedInputStream.mark(Integer.MAX_VALUE);
            msgContext.setProperty(PassThroughConstants.BUFFERED_INPUT_STREAM, bufferedInputStream);
        }
        return bufferedInputStream;
    }

    @Override
    public void buildMessage(MessageContext messageContext) throws IOException {

        MessageUtils.buildMessage(messageContext, false);
    }

    @Override
    public void buildMessage(MessageContext messageContext, boolean earlyBuild) throws IOException {

        MessageUtils.buildMessage(messageContext, earlyBuild);
    }
}
