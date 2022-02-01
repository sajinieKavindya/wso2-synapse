package org.apache.synapse.transport;

import org.apache.axis2.context.MessageContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.transport.passthru.PassThroughConstants;
import org.apache.synapse.transport.passthru.Pipe;
import org.apache.synapse.transport.passthru.config.PassThroughConfiguration;
import org.apache.synapse.transport.passthru.util.RelayUtils;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import javax.xml.stream.XMLStreamException;

public class PassThroughTransportInterceptor implements TransportInterceptor {

    private static final Log LOG = LogFactory.getLog(PassThroughTransportInterceptor.class);

    @Override
    public InputStream getMessageDataStream(MessageContext context) throws IOException {

        Pipe pipe = (Pipe) context.getProperty(PassThroughConstants.PASS_THROUGH_PIPE);

        if (pipe != null && context.getProperty(PassThroughConstants.BUFFERED_INPUT_STREAM) != null) {
            BufferedInputStream bufferedInputStream =
                    (BufferedInputStream) context.getProperty(PassThroughConstants.BUFFERED_INPUT_STREAM);
            try {
                bufferedInputStream.reset();
                bufferedInputStream.mark(0);
            } catch (Exception e) {
                //just ignore the error
            }
            return bufferedInputStream;
        }

        if (pipe != null) {
            BufferedInputStream bufferedInputStream = new BufferedInputStream(pipe.getInputStream());
            // Multiplied it by two because we always need a bigger read-limit than the buffer size.
            bufferedInputStream.mark(PassThroughConfiguration.getInstance().getIOBufferSize() * 2);
            OutputStream resetOutStream = pipe.resetOutputStream();

            ReadableByteChannel inputChannel = Channels.newChannel(bufferedInputStream);
            WritableByteChannel outputChannel = Channels.newChannel(resetOutStream);

            if (!isMessageBiggerThanBuffer(inputChannel, outputChannel)) {
                //TODO:need to find a proper solution
                try {
                    bufferedInputStream.reset();
                    context.setProperty(PassThroughConstants.BUFFERED_INPUT_STREAM, bufferedInputStream);
                    RelayUtils.buildMessage(context);
                } catch (Exception e) {
                    LOG.error("Error while building message", e);
                }
                return null;
            }
            try {
                bufferedInputStream.reset();
            } catch (Exception e) {
                // just ignore the error
            }

            pipe.setRawSerializationComplete(true);

            return bufferedInputStream;
        }
        return null;
    }

    @Override
    public void buildMessage(MessageContext messageContext) throws XMLStreamException, IOException {
        RelayUtils.buildMessage(messageContext, false);
    }

    @Override
    public void buildMessage(MessageContext messageContext, boolean earlyBuild) throws XMLStreamException, IOException {
        RelayUtils.buildMessage(messageContext, earlyBuild);
    }

    public boolean isMessageBiggerThanBuffer(final ReadableByteChannel src, final WritableByteChannel dest) throws IOException {

        int bufferSizeSupport = PassThroughConfiguration.getInstance().getIOBufferSize();

        // Added one to make sure temp buffer is always bigger than the io_buffer
        final ByteBuffer buffer = ByteBuffer.allocate(bufferSizeSupport + 1);

        while (src.read(buffer) != -1) {
            if (bufferSizeSupport < buffer.position()) {
                return false;
            }
        }

        buffer.flip();
        dest.write(buffer);

        return true;
    }
}
