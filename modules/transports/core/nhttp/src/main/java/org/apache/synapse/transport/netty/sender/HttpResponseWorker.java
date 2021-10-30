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

import io.netty.handler.codec.http.HttpHeaders;
import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.soap.SOAPEnvelope;
import org.apache.axiom.soap.SOAPFactory;
import org.apache.axiom.soap.impl.llom.soap11.SOAP11Factory;
import org.apache.axis2.AxisFault;
import org.apache.axis2.Constants;
import org.apache.axis2.builder.BuilderUtil;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.description.Parameter;
import org.apache.axis2.description.WSDL2Constants;
import org.apache.axis2.engine.AxisEngine;
import org.apache.axis2.wsdl.WSDLConstants;
import org.apache.http.HttpStatus;
import org.apache.http.protocol.HTTP;
import org.apache.log4j.Logger;
import org.apache.synapse.transport.netty.BridgeConstants;
import org.apache.synapse.transport.passthru.PassThroughConstants;
import org.wso2.transport.http.netty.message.HttpCarbonMessage;

import java.util.Map;
import java.util.TreeMap;

/**
 * {@code HttpResponseWorker} is the Thread which does the response processing.
 */
public class HttpResponseWorker implements Runnable {

    private static final Logger LOG = Logger.getLogger(HttpResponseWorker.class);

    private HttpCarbonMessage httpResponse;
    private MessageContext requestMsgCtx;

    HttpResponseWorker(MessageContext requestMsgCtx, HttpCarbonMessage httpResponse) {
        this.httpResponse = httpResponse;
        this.requestMsgCtx = requestMsgCtx;
    }

    @Override
    public void run() {

        MessageContext responseMsgCtx;
        try {
            responseMsgCtx = requestMsgCtx.getOperationContext().
                    getMessageContext(WSDL2Constants.MESSAGE_LABEL_IN);
            if (responseMsgCtx != null) {
                responseMsgCtx.setSoapAction("");
            }
        } catch (AxisFault ex) {
            LOG.error(BridgeConstants.BRIDGE_LOG_PREFIX + "Error getting response message context " +
                    "from the operation context", ex);
            return;
        }

        if (responseMsgCtx == null) {
            if (requestMsgCtx.getOperationContext().isComplete()) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Error getting IN message context from the operation context. " +
                            "Possibly an RM terminate sequence message");
                }
                return;
            }
            responseMsgCtx = new MessageContext();
            responseMsgCtx.setOperationContext(requestMsgCtx.getOperationContext());
        }

        responseMsgCtx.setServerSide(true);
        responseMsgCtx.setDoingREST(requestMsgCtx.isDoingREST());
        responseMsgCtx.setProperty(MessageContext.TRANSPORT_IN,
                requestMsgCtx.getProperty(MessageContext.TRANSPORT_IN));
        responseMsgCtx.setTransportIn(requestMsgCtx.getTransportIn());
        responseMsgCtx.setTransportOut(requestMsgCtx.getTransportOut());

        responseMsgCtx.setAxisMessage(requestMsgCtx.getOperationContext().getAxisOperation().
                getMessage(WSDLConstants.MESSAGE_LABEL_IN_VALUE));
        responseMsgCtx.setOperationContext(requestMsgCtx.getOperationContext());
        responseMsgCtx.setConfigurationContext(requestMsgCtx.getConfigurationContext());
        responseMsgCtx.setTo(null);
        // Set headers
        Map<String, String> headers = new TreeMap<>(String::compareToIgnoreCase);
        httpResponse.getHeaders().forEach(entry -> headers.put(entry.getKey(), entry.getValue()));
        responseMsgCtx.setProperty(MessageContext.TRANSPORT_HEADERS, headers);

        try {
            if (isResponseHaveBodyExpected(httpResponse)) {
                String contentType = httpResponse.getHeader(BridgeConstants.CONTENT_TYPE_HEADER);
                if (contentType == null) {
                    // Server hasn't sent the header - Try to infer the content type
                    contentType = inferContentType(responseMsgCtx);;
                }
                responseMsgCtx.setProperty(Constants.Configuration.CONTENT_TYPE, contentType);

                String charSetEncoding = BuilderUtil.getCharSetEncoding(contentType);
                if (contentType != null) {
                    responseMsgCtx.setProperty(
                            Constants.Configuration.CHARACTER_SET_ENCODING,
                            contentType.indexOf("charset") > 0 ?
                                    charSetEncoding : MessageContext.DEFAULT_CHAR_SET_ENCODING);
                    responseMsgCtx.removeProperty(PassThroughConstants.NO_ENTITY_BODY);
                }

                // Set payload
                SOAPFactory fac = OMAbstractFactory.getSOAP11Factory();
                SOAPEnvelope envelope = fac.getDefaultEnvelope();
                try {
                    responseMsgCtx.setEnvelope(envelope);
                } catch (AxisFault axisFault) {
                    LOG.error(BridgeConstants.BRIDGE_LOG_PREFIX + "Error occurred while setting SOAP envelope",
                            axisFault);
                }
            } else {
                // there is no response entity-body
                responseMsgCtx.setProperty(PassThroughConstants.NO_ENTITY_BODY, Boolean.TRUE);
                responseMsgCtx.setEnvelope(new SOAP11Factory().getDefaultEnvelope());
            }

            // Set status code
            int statusCode = httpResponse.getHttpStatusCode();
            responseMsgCtx.setProperty(BridgeConstants.HTTP_STATUS_CODE_PROP, statusCode);
            responseMsgCtx.setProperty(BridgeConstants.HTTP_STATUS_CODE_DESCRIPTION_PROP,
                    httpResponse.getProperty(BridgeConstants.HTTP_REASON_PHRASE));
            responseMsgCtx.setServerSide(true);

            // Set rest of the properties
            responseMsgCtx.setProperty(BridgeConstants.HTTP_CARBON_MESSAGE, httpResponse);
            responseMsgCtx.setProperty(BridgeConstants.HTTP_CLIENT_REQUEST_CARBON_MESSAGE,
                    requestMsgCtx.getProperty(BridgeConstants.HTTP_CLIENT_REQUEST_CARBON_MESSAGE));

            // Handover message to the axis engine for processing
            try {
                AxisEngine.receive(responseMsgCtx);
            } catch (AxisFault ex) {
                LOG.error(BridgeConstants.BRIDGE_LOG_PREFIX + "Error occurred while processing " +
                        "response message through Axis2", ex);
            }
        } catch (AxisFault af) {
            LOG.error("Fault creating response SOAP envelope", af);
        }
    }

    private boolean isResponseHaveBodyExpected(final HttpCarbonMessage response) {

//        if ("HEAD".equalsIgnoreCase(method)) {
//            return false;
//        }

        int status = response.getHttpStatusCode();
        return status >= HttpStatus.SC_OK
                && status != HttpStatus.SC_NO_CONTENT
                && status != HttpStatus.SC_NOT_MODIFIED
                && status != HttpStatus.SC_RESET_CONTENT;
    }

    private String inferContentType(MessageContext responseMsgCtx) {
        //Check whether server sent Content-Type in different case
        HttpHeaders headers = httpResponse.getHeaders();
        if (!headers.isEmpty()) {
            for (Map.Entry<String, String> entry : headers.entries()) {
                if (HTTP.CONTENT_TYPE.equalsIgnoreCase(entry.getKey())) {
                    return entry.getValue();
                }
            }
        }

        // Try to get the content type from the message context
        Object cTypeProperty = responseMsgCtx.getProperty(PassThroughConstants.CONTENT_TYPE);
        if (cTypeProperty != null) {
            return cTypeProperty.toString();
        }
        // Try to get the content type from the axis configuration
        Parameter cTypeParam = requestMsgCtx.getConfigurationContext().getAxisConfiguration().getParameter(
                PassThroughConstants.CONTENT_TYPE);
        if (cTypeParam != null) {
            return cTypeParam.getValue().toString();
        }

        // When the response from backend does not have the body(Content-Length is 0 )
        // and Content-Type is not set; ESB should not do any modification to the response and pass-through as it is.
        boolean contentLengthHeaderPresent = false;
        String contentLengthHeader = headers.get(HTTP.CONTENT_LEN);

        if (contentLengthHeader != null) {
            contentLengthHeaderPresent = true;
        } else {
            if (!headers.isEmpty()) {
                for (Map.Entry<String, String> entry : headers.entries()) {
                    if (HTTP.CONTENT_LEN.equalsIgnoreCase(entry.getKey())) {
                        contentLengthHeader = headers.get(entry.getKey());
                        contentLengthHeaderPresent = true;
                        break;
                    }
                }
            }
        }

        boolean transferEncodingHeaderPresent = httpResponse.getHeader(HTTP.TRANSFER_ENCODING) != null;

        if (!transferEncodingHeaderPresent) {
            if (!headers.isEmpty()) {
                for (Map.Entry<String, String> entry : headers.entries()) {
                    if (HTTP.TRANSFER_ENCODING.equalsIgnoreCase(entry.getKey())) {
                        transferEncodingHeaderPresent = true;
                        break;
                    }
                }
            }
        }

        if ((!contentLengthHeaderPresent && !transferEncodingHeaderPresent)
                || "0".equals(contentLengthHeader)) {
            responseMsgCtx.setProperty(PassThroughConstants.NO_ENTITY_BODY, Boolean.TRUE);
            return null;
        }

        // Unable to determine the content type - Return default value
        return PassThroughConstants.DEFAULT_CONTENT_TYPE;
    }
}
