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
import org.apache.axis2.addressing.AddressingConstants;
import org.apache.axis2.builder.BuilderUtil;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.description.Parameter;
import org.apache.axis2.description.WSDL2Constants;
import org.apache.axis2.engine.AxisEngine;
import org.apache.axis2.engine.MessageReceiver;
import org.apache.axis2.util.JavaUtils;
import org.apache.axis2.wsdl.WSDLConstants;
import org.apache.http.HttpStatus;
import org.apache.http.protocol.HTTP;
import org.apache.log4j.Logger;
import org.apache.synapse.transport.netty.BridgeConstants;
import org.apache.synapse.transport.netty.config.TargetConfiguration;
import org.apache.synapse.transport.nhttp.NhttpConstants;
import org.apache.synapse.transport.passthru.PassThroughConstants;
import org.wso2.transport.http.netty.message.HttpCarbonMessage;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code HttpResponseWorker} is the Thread which does the response processing.
 */
public class HttpInboundResponseWorker implements Runnable {

    private static final Logger LOG = Logger.getLogger(HttpInboundResponseWorker.class);

    private HttpCarbonMessage httpResponse;
    private final MessageContext requestMsgCtx;
    private final TargetConfiguration targetConfiguration;

    HttpInboundResponseWorker(MessageContext requestMsgCtx, HttpCarbonMessage httpResponse,
                              TargetConfiguration targetConfiguration) {
        this.httpResponse = httpResponse;
        this.requestMsgCtx = requestMsgCtx;
        this.targetConfiguration = targetConfiguration;
    }

    @Override
    public void run() {

        // TODO: check this
        int statusCode = httpResponse.getHttpStatusCode();
        if (statusCode < HttpStatus.SC_OK) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Received a 100 Continue response");
            }
            // Ignore 1xx response
            return;
        }

        MessageContext responseMsgCtx;
        try {
            responseMsgCtx = requestMsgCtx.getOperationContext().
                    getMessageContext(WSDL2Constants.MESSAGE_LABEL_IN);
            if (statusCode == HttpStatus.SC_ACCEPTED && handle202(requestMsgCtx, responseMsgCtx)) {
                return;
            }
            if (responseMsgCtx != null) {
                responseMsgCtx.setSoapAction("");
            } else {
                if (requestMsgCtx.getOperationContext().isComplete()) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Error getting IN message context from the operation context. " +
                                "Possibly an RM terminate sequence message");
                    }
                    cleanup();
                    return;
                }
            }
        } catch (AxisFault ex) {
            LOG.error(BridgeConstants.BRIDGE_LOG_PREFIX + "Error getting response message context " +
                    "from the operation context", ex);
            return;
        }

        if (responseMsgCtx == null) {
            responseMsgCtx = new MessageContext();
            responseMsgCtx.setOperationContext(requestMsgCtx.getOperationContext());
        }

        // TODO: check this logic and it's validity. See whether this is handled in transport-http
        // Special casing 301, 302, 303 and 307 scenario in following section. Not sure whether it's the correct fix,
        // but this fix makes it possible to do http --> https redirection.
        String oriURL = httpResponse.getHeader(PassThroughConstants.LOCATION);
        if (oriURL != null && ((statusCode != HttpStatus.SC_MOVED_TEMPORARILY)
                && (statusCode != HttpStatus.SC_MOVED_PERMANENTLY)
                && (statusCode != HttpStatus.SC_CREATED)
                && (statusCode != HttpStatus.SC_SEE_OTHER)
                && (statusCode != HttpStatus.SC_TEMPORARY_REDIRECT)
                && !targetConfiguration.isPreserveHttpHeader(PassThroughConstants.LOCATION))) {
            URL url;
            String urlContext;
            try {
                url = new URL(oriURL);
                urlContext = url.getFile();
            } catch (MalformedURLException e) {
                //Fix ESBJAVA-3461 - In the case when relative path is sent should be handled
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Relative URL received for Location : " + oriURL, e);
                }
                urlContext = oriURL;
            }

            httpResponse.removeHeader(PassThroughConstants.LOCATION);
            String servicePrefix = (String) requestMsgCtx.getProperty(PassThroughConstants.SERVICE_PREFIX);
            if (servicePrefix != null) {
                if (urlContext != null && urlContext.startsWith("/")) {
                    //Remove the preceding '/' character
                    urlContext = urlContext.substring(1);
                }
                httpResponse.setHeader(PassThroughConstants.LOCATION, servicePrefix + urlContext);
            }
        }

        // This fixes the issue of disableAddressingForInMessages property value being overridden at RelayUtils and
        // value of the property not being set to the response Axis2 Message context. Fixes wso2/product-ei#4078
        responseMsgCtx.setProperty(AddressingConstants.DISABLE_ADDRESSING_FOR_IN_MESSAGES,
                requestMsgCtx.getProperty(AddressingConstants.DISABLE_ADDRESSING_FOR_IN_MESSAGES));

        responseMsgCtx.setServerSide(true);
        responseMsgCtx.setDoingREST(requestMsgCtx.isDoingREST());
        responseMsgCtx.setProperty(MessageContext.TRANSPORT_IN,
                requestMsgCtx.getProperty(MessageContext.TRANSPORT_IN));
        responseMsgCtx.setTransportIn(requestMsgCtx.getTransportIn());
        responseMsgCtx.setTransportOut(requestMsgCtx.getTransportOut());

        responseMsgCtx.setProperty(PassThroughConstants.INVOKED_REST, requestMsgCtx.isDoingREST());
        responseMsgCtx.setProperty(PassThroughConstants.ORIGINAL_HTTP_SC, statusCode);
        responseMsgCtx.setProperty(PassThroughConstants.ORIGINAL_HTTP_REASON_PHRASE, httpResponse.getReasonPhrase());

        responseMsgCtx.setAxisMessage(requestMsgCtx.getOperationContext().getAxisOperation().
                getMessage(WSDLConstants.MESSAGE_LABEL_IN_VALUE));
        responseMsgCtx.setOperationContext(requestMsgCtx.getOperationContext());
        responseMsgCtx.setConfigurationContext(requestMsgCtx.getConfigurationContext());
        responseMsgCtx.setTo(null);

        // Set any transport headers received
        Map<String, String> headers = new TreeMap<>(String::compareToIgnoreCase);
        httpResponse.getHeaders().forEach(entry -> headers.put(entry.getKey(), entry.getValue()));
        responseMsgCtx.setProperty(MessageContext.TRANSPORT_HEADERS, headers);

        if (202 == httpResponse.getHttpStatusCode()) {
            responseMsgCtx.setProperty(AddressingConstants.
                    DISABLE_ADDRESSING_FOR_OUT_MESSAGES, Boolean.TRUE);
            responseMsgCtx.setProperty(PassThroughConstants.MESSAGE_BUILDER_INVOKED, Boolean.FALSE);
            responseMsgCtx.setProperty(NhttpConstants.SC_ACCEPTED, Boolean.TRUE);
        }

        // TODO: set correlation_id
        // TODO: set stream interceptors

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
            responseMsgCtx.setProperty(PassThroughConstants.HTTP_SC, statusCode);
            responseMsgCtx.setProperty(PassThroughConstants.HTTP_SC_DESC, httpResponse.getReasonPhrase());
            if (statusCode >= 400) {
                responseMsgCtx.setProperty(PassThroughConstants.FAULT_MESSAGE,
                        PassThroughConstants.TRUE);
            }
            responseMsgCtx.setProperty(PassThroughConstants.NON_BLOCKING_TRANSPORT, true);

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
                String errorMessage = "Fault processing response message through Axis2: " + ex.getMessage();
                responseMsgCtx.setProperty(
                        NhttpConstants.SENDING_FAULT, Boolean.TRUE);
                responseMsgCtx.setProperty(
                        NhttpConstants.ERROR_CODE, NhttpConstants.RESPONSE_PROCESSING_FAILURE);
                responseMsgCtx.setProperty(
                        NhttpConstants.ERROR_MESSAGE, errorMessage.split("\n")[0]);
                responseMsgCtx.setProperty(
                        NhttpConstants.ERROR_DETAIL, JavaUtils.stackToString(ex));
                responseMsgCtx.setProperty(
                        NhttpConstants.ERROR_EXCEPTION, ex);
                responseMsgCtx.getAxisOperation().getMessageReceiver().receive(responseMsgCtx);
            }
        } catch (AxisFault af) {
            LOG.error("Fault creating response SOAP envelope", af);
        } finally {
            cleanup();
        }
    }

    private boolean isResponseHaveBodyExpected(final HttpCarbonMessage response) {

        if ("HEAD".equalsIgnoreCase((String) requestMsgCtx.getProperty(BridgeConstants.HTTP_METHOD))) {
            return false;
        }

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

    private boolean handle202(MessageContext requestMsgContext, MessageContext responseMsgContext) throws AxisFault {
        if (requestMsgContext.isPropertyTrue(NhttpConstants.IGNORE_SC_ACCEPTED)) {
            // We should not further process this 202 response - Ignore it
            return true;
        }

        MessageReceiver mr = requestMsgContext.getAxisOperation().getMessageReceiver();
        if (responseMsgContext == null || requestMsgContext.getOptions().isUseSeparateListener()) {
            // Most probably a response from a dual channel invocation
            // Inject directly into the SynapseCallbackReceiver
            requestMsgContext.setProperty(NhttpConstants.HTTP_202_RECEIVED, "true");
            mr.receive(requestMsgContext);
            return true;
        }

        return false;
    }

    /**
     * Perform cleanup of ClientWorker.
     */
    private void cleanup() {
        //clean threadLocal variables
        MessageContext.destroyCurrentMessageContext();
//        TenantInfoInitiator tenantInfoInitiator = TenantInfoInitiatorProvider.getTenantInfoInitiator();
//        if (tenantInfoInitiator != null) {
//            tenantInfoInitiator.cleanTenantInfo();
//        }
    }
}
