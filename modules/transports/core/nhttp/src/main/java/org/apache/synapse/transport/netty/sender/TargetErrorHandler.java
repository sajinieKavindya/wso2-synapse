/*
 *  Copyright (c) 2021, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.apache.synapse.transport.netty.sender;

import org.apache.axiom.soap.SOAPEnvelope;
import org.apache.axis2.AxisFault;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.description.OutOnlyAxisOperation;
import org.apache.axis2.engine.MessageReceiver;
import org.apache.axis2.util.MessageContextBuilder;
import org.apache.axis2.wsdl.WSDLConstants;
import org.apache.log4j.Logger;
import org.apache.synapse.commons.CorrelationConstants;
import org.apache.synapse.transport.netty.config.TargetConfiguration;
import org.apache.synapse.transport.passthru.PassThroughConstants;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;

public class TargetErrorHandler {

    private static final Logger LOG = Logger.getLogger(TargetErrorHandler.class);

    private TargetConfiguration targetConfiguration;

    public TargetErrorHandler(TargetConfiguration targetConfiguration) {

        this.targetConfiguration = targetConfiguration;
    }

    public void handleError(final MessageContext msgContext, final int errorCode, final String errorMessage,
                            final Throwable exceptionToRaise) {

        if (errorCode == -1 && errorMessage == null && exceptionToRaise == null) {
            return;
        }

        if (msgContext.getAxisOperation() == null || msgContext.getAxisOperation().getMessageReceiver() == null) {
            return;
        }

        targetConfiguration.getWorkerPool().execute(new Runnable() {
            public void run() {
                MessageReceiver msgReceiver = msgContext.getAxisOperation().getMessageReceiver();

                // TODO: handle correlation here

                try {
                    AxisFault axisFault = (exceptionToRaise != null ?
                            new AxisFault(errorMessage, exceptionToRaise) :
                            new AxisFault(errorMessage));

                    MessageContext faultMessageContext =
                            MessageContextBuilder.createFaultMessageContext(msgContext, axisFault);

                    SOAPEnvelope envelope = faultMessageContext.getEnvelope();

                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Sending Fault for Request with Message ID : "
                                + msgContext.getMessageID());
                    }

                    faultMessageContext.setTo(null);
                    faultMessageContext.setServerSide(true);
                    faultMessageContext.setDoingREST(msgContext.isDoingREST());
                    faultMessageContext.setProperty(MessageContext.TRANSPORT_IN, msgContext
                            .getProperty(MessageContext.TRANSPORT_IN));
                    faultMessageContext.setTransportIn(msgContext.getTransportIn());
                    faultMessageContext.setTransportOut(msgContext.getTransportOut());

                    if (!(msgContext.getOperationContext().getAxisOperation() instanceof OutOnlyAxisOperation)) {
                        faultMessageContext.setAxisMessage(msgContext.getOperationContext().getAxisOperation()
                                .getMessage(WSDLConstants.MESSAGE_LABEL_IN_VALUE));
                    }

                    faultMessageContext.setOperationContext(msgContext.getOperationContext());
                    faultMessageContext.setConfigurationContext(msgContext.getConfigurationContext());

                    faultMessageContext.setProperty(PassThroughConstants.SENDING_FAULT, Boolean.TRUE);
                    faultMessageContext.setProperty(PassThroughConstants.ERROR_MESSAGE, errorMessage);
                    if (errorCode != -1) {
                        faultMessageContext.setProperty(
                                PassThroughConstants.ERROR_CODE, getErrorCode(errorCode));
                    }
                    if (exceptionToRaise != null) {
                        faultMessageContext.setProperty(
                                PassThroughConstants.ERROR_DETAIL, getStackTrace(exceptionToRaise));
                        faultMessageContext.setProperty(
                                PassThroughConstants.ERROR_EXCEPTION, exceptionToRaise);
                        envelope.getBody().getFault().getDetail().setText(
                                exceptionToRaise.toString());
                    } else {
                        faultMessageContext.setProperty(
                                PassThroughConstants.ERROR_DETAIL, errorMessage);
                        envelope.getBody().getFault().getDetail().setText(errorMessage);
                    }

                    faultMessageContext.setProperty(PassThroughConstants.NO_ENTITY_BODY, true);
                    faultMessageContext.setProperty(CorrelationConstants.CORRELATION_ID,
                            msgContext.getProperty(CorrelationConstants.CORRELATION_ID));
                    faultMessageContext.setProperty(PassThroughConstants.INTERNAL_EXCEPTION_ORIGIN,
                            msgContext.getProperty(PassThroughConstants.INTERNAL_EXCEPTION_ORIGIN));
                    msgReceiver.receive(faultMessageContext);

                } catch (AxisFault af) {
                    LOG.error("Unable to report failure back to the message receiver", af);
                }
            }
        });
    }

    private int getErrorCode(int errorCode) {
        return errorCode;
    }

    private String getStackTrace(Throwable aThrowable) {
        final Writer result = new StringWriter();
        final PrintWriter printWriter = new PrintWriter(result);
        aThrowable.printStackTrace(printWriter);
        return result.toString();
    }

}
