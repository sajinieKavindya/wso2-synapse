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
package org.apache.synapse.transport.netty.listener;

import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.soap.SOAP11Constants;
import org.apache.axiom.soap.SOAP12Constants;
import org.apache.axiom.soap.SOAPEnvelope;
import org.apache.axiom.soap.SOAPFactory;
import org.apache.axiom.soap.impl.llom.soap11.SOAP11Factory;
import org.apache.axis2.AxisFault;
import org.apache.axis2.Constants;
import org.apache.axis2.addressing.EndpointReference;
import org.apache.axis2.builder.BuilderUtil;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.description.AxisService;
import org.apache.axis2.description.Parameter;
import org.apache.axis2.description.TransportInDescription;
import org.apache.axis2.dispatchers.RequestURIBasedDispatcher;
import org.apache.axis2.engine.AxisEngine;
import org.apache.axis2.transport.TransportUtils;
import org.apache.axis2.transport.http.HTTPConstants;
import org.apache.axis2.transport.http.HTTPTransportUtils;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.http.protocol.HTTP;
import org.apache.log4j.Logger;
import org.apache.synapse.transport.netty.BridgeConstants;
import org.apache.synapse.transport.netty.util.RequestUtils;
import org.apache.synapse.transport.nhttp.NhttpConstants;
import org.apache.synapse.transport.nhttp.util.RESTUtil;
import org.apache.synapse.transport.passthru.HttpGetRequestProcessor;
import org.apache.synapse.transport.passthru.PassThroughConstants;
import org.apache.synapse.transport.passthru.ProtocolState;
import org.apache.synapse.transport.passthru.config.PassThroughConfiguration;
import org.wso2.transport.http.netty.message.HttpCarbonMessage;

import java.net.InetSocketAddress;
import javax.xml.parsers.FactoryConfigurationError;

import static org.apache.synapse.transport.netty.BridgeConstants.CONTENT_TYPE_HEADER;
import static org.apache.synapse.transport.netty.BridgeConstants.SOAP_ACTION_HEADER;

/**
 * {@code HttpRequestWorker} is the Thread which does the request processing.
 */
public class HttpRequestWorker implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(HttpRequestWorker.class);
    private ConfigurationContext configurationContext;
    private HttpCarbonMessage incomingCarbonMsg;
    private TransportInDescription transportInDescription;
    private HttpGetRequestProcessor httpGetRequestProcessor;
    private org.apache.axis2.context.MessageContext msgCtx = null;

    public HttpRequestWorker(HttpCarbonMessage incomingCarbonMsg, ConfigurationContext configurationContext,
                             TransportInDescription transportInDescription) {

        this.configurationContext = configurationContext;
        this.incomingCarbonMsg = incomingCarbonMsg;
        this.transportInDescription = transportInDescription;

        msgCtx = RequestUtils.convertCarbonMsgToAxis2MsgCtx(configurationContext, incomingCarbonMsg);


        Parameter param = transportInDescription.getParameter(NhttpConstants.HTTP_GET_PROCESSOR);
        if (param != null && param.getValue() != null) {
            httpGetRequestProcessor = RequestUtils.createHttpGetProcessor(param.getValue().toString());
            if (httpGetRequestProcessor == null) {
                RequestUtils.handleException("Cannot create HttpGetRequestProcessor");
            }
            try {
                httpGetRequestProcessor.init(configurationContext, null);
            } catch (AxisFault axisFault) {
                RequestUtils.handleException("Error while initializing httpGetRequestProcessor");
            }
        }
    }

    @Override
    public void run() {

        processHttpRequestUri(msgCtx);
        if (isRequestToFetchWSDL(msgCtx)) {
            return;
        }

        boolean isRest = isRESTRequest(msgCtx, incomingCarbonMsg.getHttpMethod());

        if (!isRest) {
            if (isEntityEnclosing(incomingCarbonMsg)) {
                processEntityEnclosingRequest(msgCtx, true);
            } else {
                processNonEntityEnclosingRESTHandler(null, msgCtx, true);
            }
        } else {
            String contentTypeHeader = incomingCarbonMsg.getHeaders().get(HTTP.CONTENT_TYPE);
            SOAPEnvelope soapEnvelope = this.handleRESTUrlPost(contentTypeHeader, msgCtx);
            processNonEntityEnclosingRESTHandler(soapEnvelope, msgCtx, true);
        }

//        populateProperties(msgCtx);
//        try {
//            AxisEngine.receive(msgCtx);
//        } catch (AxisFault ex) {
//            LOGGER.error(BridgeConstants.BRIDGE_LOG_PREFIX + "Error occurred while processing the request", ex);
//        }
    }

    private void processHttpRequestUri(MessageContext msgCtx) {

        String servicePrefixIndex = "://";
        msgCtx.setProperty(Constants.Configuration.HTTP_METHOD, incomingCarbonMsg.getHttpMethod());
        String oriUri = (String) incomingCarbonMsg.getProperty("TO");
        String restUrlPostfix = RequestUtils.getRestUrlPostfix(oriUri, configurationContext.getServicePath());

        String servicePrefix = oriUri.substring(0, oriUri.indexOf(restUrlPostfix));
        if (!servicePrefix.contains(servicePrefixIndex)) {
            InetSocketAddress localAddress =
                    (InetSocketAddress) incomingCarbonMsg.getProperty(
                            org.wso2.transport.http.netty.contract.Constants.LOCAL_ADDRESS);
            if (localAddress != null) {
                servicePrefix =
                        incomingCarbonMsg.getProperty(org.wso2.transport.http.netty.contract.Constants.PROTOCOL) +
                                servicePrefixIndex + localAddress.getHostName() + ":" +
                                incomingCarbonMsg.getProperty(
                                        org.wso2.transport.http.netty.contract.Constants.LISTENER_PORT) + servicePrefix;
            }
        }
        msgCtx.setProperty(BridgeConstants.SERVICE_PREFIX, servicePrefix);
        msgCtx.setTo(new EndpointReference(restUrlPostfix));
        msgCtx.setProperty(BridgeConstants.REST_URL_POSTFIX, restUrlPostfix);
        String requestUri = (String) incomingCarbonMsg.getProperty("TO");
        //TODO: check this
        msgCtx.setTo(new EndpointReference(requestUri));

        String method = incomingCarbonMsg.getHttpMethod();
        if (PassThroughConstants.HTTP_GET.equals(method) || PassThroughConstants.HTTP_HEAD.equals(method) ||
                PassThroughConstants.HTTP_OPTIONS.equals(method)) {

            HttpCarbonMessage outboundHttpCarbonMsg = new HttpCarbonMessage(
                    new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
            httpGetRequestProcessor.process(incomingCarbonMsg, outboundHttpCarbonMsg, msgCtx, true);
        }
    }

    private boolean isRequestToFetchWSDL(MessageContext msgContext) {
        //if WSDL done then moved out rather than hand over to entity handle methods.
//        SourceContext info = (SourceContext) request.getConnection().getContext().
//                getAttribute(SourceContext.CONNECTION_INFORMATION);
        ProtocolState state = (ProtocolState) incomingCarbonMsg.getProperty("conn");
        if (state != null && state.equals(ProtocolState.WSDL_RESPONSE_DONE) ||
                (msgContext.getProperty(PassThroughConstants.WSDL_GEN_HANDLED) != null &&
                Boolean.TRUE.equals((msgContext.getProperty(PassThroughConstants.WSDL_GEN_HANDLED))))) {
            return true;
        }
        return false;
    }

    private void populateProperties(MessageContext msgCtx) {

        String contentTypeHeader = incomingCarbonMsg.getHeaders().get(CONTENT_TYPE_HEADER);
        String charSetEncoding = null;
        String contentType = null;
        if (contentTypeHeader != null) {
            charSetEncoding = BuilderUtil.getCharSetEncoding(contentTypeHeader);
            contentType = TransportUtils.getContentType(contentTypeHeader, msgCtx);
        }
        msgCtx.setProperty(Constants.Configuration.CONTENT_TYPE, contentTypeHeader);
        msgCtx.setProperty(Constants.Configuration.MESSAGE_TYPE, "application/xml");
        if (contentTypeHeader == null ||
                RequestUtils.isRESTRequest(contentTypeHeader) ||
                RequestUtils.isRest(contentTypeHeader)) {
            msgCtx.setProperty(BridgeConstants.REST_REQUEST_CONTENT_TYPE, contentType);
            msgCtx.setDoingREST(true);
        }

        // get the contentType of char encoding
        if (charSetEncoding == null) {
            charSetEncoding = MessageContext.DEFAULT_CHAR_SET_ENCODING;
        }
        msgCtx.setProperty(Constants.Configuration.CHARACTER_SET_ENCODING, charSetEncoding);

        msgCtx.setProperty(BridgeConstants.HTTP_METHOD, incomingCarbonMsg.getHttpMethod());
        //TODO: create response buffers for  HTTP GET, DELETE, OPTION and HEAD methods ->
        // httpGetRequestProcessor.process ??
        //TODO: this is to handle wsdl requests

        msgCtx.setServerSide(true);

        String soapAction = incomingCarbonMsg.getHeaders().get(SOAP_ACTION_HEADER);
        if ((soapAction != null) && soapAction.startsWith("\"") && soapAction.endsWith("\"")) {
            soapAction = soapAction.substring(1, soapAction.length() - 1);
            msgCtx.setSoapAction(soapAction);
        }
        int soapVersion =
                RequestUtils.populateSOAPVersion(msgCtx, soapAction, contentTypeHeader);
        SOAPEnvelope envelope;
        if (soapVersion == 1) {
            SOAPFactory fac = OMAbstractFactory.getSOAP11Factory();
            envelope = fac.getDefaultEnvelope();
        } else {
            SOAPFactory fac = OMAbstractFactory.getSOAP12Factory();
            envelope = fac.getDefaultEnvelope();
        }
        try {
            msgCtx.setEnvelope(envelope);
        } catch (AxisFault ex) {
            LOGGER.error(BridgeConstants.BRIDGE_LOG_PREFIX + "Error occurred while setting the soap envelope", ex);
        }
    }

    private boolean isEntityEnclosing(HttpCarbonMessage httpCarbonMessage) {

        long contentLength = BridgeConstants.NO_CONTENT_LENGTH_FOUND;
        String lengthStr = httpCarbonMessage.getHeader(HttpHeaderNames.CONTENT_LENGTH.toString());
        try {
            contentLength = lengthStr != null ? Long.parseLong(lengthStr) : contentLength;
            if (contentLength == BridgeConstants.NO_CONTENT_LENGTH_FOUND) {
                //Read one byte to make sure the incoming stream has data
                contentLength = httpCarbonMessage.countMessageLengthTill(BridgeConstants.ONE_BYTE);
            }
        } catch (NumberFormatException e) {
            LOGGER.error("NumberFormatException. Invalid content length");
        }
        return contentLength > 0;
    }

    public void processEntityEnclosingRequest(MessageContext msgContext, boolean injectToAxis2Engine) {

        try {
            String contentTypeHeader = incomingCarbonMsg.getHeaders().get(CONTENT_TYPE_HEADER);
            contentTypeHeader = contentTypeHeader != null ? contentTypeHeader : inferContentType();

            String charSetEncoding = null;
            String contentType = null;

            if (contentTypeHeader != null) {
                charSetEncoding = BuilderUtil.getCharSetEncoding(contentTypeHeader);
                contentType = TransportUtils.getContentType(contentTypeHeader, msgContext);
            }
            // get the contentType of char encoding
            if (charSetEncoding == null) {
                charSetEncoding = MessageContext.DEFAULT_CHAR_SET_ENCODING;
            }
            String method = incomingCarbonMsg.getHttpMethod() != null ?
                    incomingCarbonMsg.getHttpMethod().toUpperCase() : "";

            msgContext.setTo(new EndpointReference((String) incomingCarbonMsg.getProperty("TO")));
            msgContext.setProperty(HTTPConstants.HTTP_METHOD, method);
            msgContext.setProperty(Constants.Configuration.CHARACTER_SET_ENCODING, charSetEncoding);
            msgContext.setServerSide(true);

            msgContext.setProperty(Constants.Configuration.CONTENT_TYPE, contentTypeHeader);
            msgContext.setProperty(Constants.Configuration.MESSAGE_TYPE, contentType);

            if (contentTypeHeader == null || HTTPTransportUtils.isRESTRequest(contentTypeHeader)
                    || isRest(contentTypeHeader)) {
                msgContext.setProperty(PassThroughConstants.REST_REQUEST_CONTENT_TYPE, contentType);
                msgContext.setDoingREST(true);
                SOAPEnvelope soapEnvelope = this.handleRESTUrlPost(contentTypeHeader, msgContext);
//                msgContext.setProperty(PassThroughConstants.PASS_THROUGH_PIPE, request.getPipe());
                processNonEntityEnclosingRESTHandler(soapEnvelope, msgContext, injectToAxis2Engine);
                return;
            } else {
                String soapAction = incomingCarbonMsg.getHeaders().get(SOAP_ACTION_HEADER);

                int soapVersion = HTTPTransportUtils.
                        initializeMessageContext(msgContext, soapAction,
                                (String) incomingCarbonMsg.getProperty("TO"), contentTypeHeader);
                SOAPEnvelope envelope;

                if (soapVersion == 1) {
                    SOAPFactory fac = OMAbstractFactory.getSOAP11Factory();
                    envelope = fac.getDefaultEnvelope();
                } else if (soapVersion == 2) {
                    SOAPFactory fac = OMAbstractFactory.getSOAP12Factory();
                    envelope = fac.getDefaultEnvelope();
                } else {
                    SOAPFactory fac = OMAbstractFactory.getSOAP12Factory();
                    envelope = fac.getDefaultEnvelope();
                }

                if ((soapAction != null) && soapAction.startsWith("\"") && soapAction.endsWith("\"")) {
                    soapAction = soapAction.substring(1, soapAction.length() - 1);
                    msgContext.setSoapAction(soapAction);
                }

                msgContext.setEnvelope(envelope);
            }
//            msgContext.setProperty(PassThroughConstants.PASS_THROUGH_PIPE, request.getPipe());
            if (injectToAxis2Engine) {
                AxisEngine.receive(msgContext);
            }
        } catch (AxisFault axisFault) {
            RequestUtils.handleException("Error processing " + incomingCarbonMsg.getHttpMethod() +
                    " request for : " + incomingCarbonMsg.getProperty("TO"), axisFault);
        } catch (Exception e) {
            RequestUtils.handleException(
                    "Error processing " + incomingCarbonMsg.getHttpMethod() + " request for : "
                            + incomingCarbonMsg.getProperty("TO") + ". Error detail: " + e.getMessage() + ". ", e);
        }
    }

    public void processNonEntityEnclosingRESTHandler(SOAPEnvelope soapEnvelope, MessageContext msgContext,
                                                     boolean injectToAxis2Engine) {

        String soapAction = incomingCarbonMsg.getHeaders().get(SOAP_ACTION_HEADER);
        if ((soapAction != null) && soapAction.startsWith("\"") && soapAction.endsWith("\"")) {
            soapAction = soapAction.substring(1, soapAction.length() - 1);
        }

        msgContext.setSoapAction(soapAction);
        msgContext.setTo(new EndpointReference((String) incomingCarbonMsg.getProperty("TO")));
        msgContext.setServerSide(true);
        msgContext.setDoingREST(true);
        if (!isEntityEnclosing(incomingCarbonMsg)) {
            msgContext.setProperty(PassThroughConstants.NO_ENTITY_BODY, Boolean.TRUE);
        }

        try {
            if (soapEnvelope == null) {
                msgContext.setEnvelope(new SOAP11Factory().getDefaultEnvelope());
            } else {
                msgContext.setEnvelope(soapEnvelope);
            }

            if (injectToAxis2Engine) {
                AxisEngine.receive(msgContext);
            }
        } catch (AxisFault axisFault) {
            RequestUtils.handleException("Error processing " + incomingCarbonMsg.getHttpMethod() +
                    " request for : " + incomingCarbonMsg.getProperty("TO"), axisFault);
        } catch (Exception e) {
            String encodedURL = StringEscapeUtils.escapeHtml((String) incomingCarbonMsg.getProperty("TO"));
            RequestUtils.handleException("Error processing " + incomingCarbonMsg.getHttpMethod()
                    + " request for : " + encodedURL + ". ", e);
        }
    }

    public boolean isRESTRequest(MessageContext msgContext, String method) {

        if (msgContext.getProperty(PassThroughConstants.REST_GET_DELETE_INVOKE) == null
                || !((Boolean) msgContext.getProperty(PassThroughConstants.REST_GET_DELETE_INVOKE))) {
            return false;
        }
        msgContext.setProperty(HTTPConstants.HTTP_METHOD, method);
        msgContext.setServerSide(true);
        msgContext.setDoingREST(true);
        return true;
    }

    private String inferContentType() {

        final String[] str = new String[1];
        incomingCarbonMsg.getHeaders().forEach(entry -> {
                    if (HTTP.CONTENT_TYPE.equalsIgnoreCase(entry.getKey())) {
                        str[0] = incomingCarbonMsg.getHeaders().get(entry.getKey());
                    }
                }
        );
        if (str[0] != null) {
            return str[0];
        }
        Parameter param = configurationContext.getAxisConfiguration().
                getParameter(PassThroughConstants.REQUEST_CONTENT_TYPE);
        if (param != null) {
            return param.getValue().toString();
        }
        return null;
    }

    private boolean isRest(String contentType) {

        return contentType != null &&
                contentType.indexOf(SOAP11Constants.SOAP_11_CONTENT_TYPE) == -1 &&
                contentType.indexOf(SOAP12Constants.SOAP_12_CONTENT_TYPE) == -1;
    }

    /**
     * Method will setup the necessary parameters for the rest url post action.
     *
     * @param
     * @return
     * @throws FactoryConfigurationError
     */
    public SOAPEnvelope handleRESTUrlPost(String contentTypeHdr, MessageContext msgContext)
            throws FactoryConfigurationError {

        SOAPEnvelope soapEnvelope = null;
        String contentType = contentTypeHdr != null ? TransportUtils.getContentType(contentTypeHdr, msgContext) : null;
        // When POST request doesn't contain a Content-Type,
        // recipient should consider it as application/octet-stream (rfc2616)
        if (contentType == null || contentType.isEmpty()) {
            contentType = PassThroughConstants.APPLICATION_OCTET_STREAM;
            // Temp fix for https://github.com/wso2/product-ei/issues/2001
            if (HTTPConstants.HTTP_METHOD_GET.equals(msgContext.getProperty("HTTP_METHOD")) || "DELETE"
                    .equals(msgContext.getProperty("HTTP_METHOD"))) {
                contentType = HTTPConstants.MEDIA_TYPE_X_WWW_FORM;
            }
        }
        if (HTTPConstants.MEDIA_TYPE_X_WWW_FORM.equals(contentType) ||
                (PassThroughConstants.APPLICATION_OCTET_STREAM.equals(contentType) && contentTypeHdr == null)) {
            msgContext.setTo(new EndpointReference((String) incomingCarbonMsg.getProperty("TO")));
            String charSetEncoding;
            if (contentTypeHdr != null) {
                msgContext.setProperty(Constants.Configuration.CONTENT_TYPE, contentTypeHdr);
                charSetEncoding = BuilderUtil.getCharSetEncoding(contentTypeHdr);
            } else {
                msgContext.setProperty(Constants.Configuration.CONTENT_TYPE, contentType);
                charSetEncoding = BuilderUtil.getCharSetEncoding(contentType);
            }
            msgContext.setProperty(Constants.Configuration.CHARACTER_SET_ENCODING, charSetEncoding);
            try {
                RESTUtil.dispatchAndVerify(msgContext);
            } catch (AxisFault e1) {
                LOGGER.error("Error while building message for REST_URL request", e1);
            }

            try {
                /**
                 * This reverseProxyMode was introduce to avoid the LB exposing
                 * it's own web service when REST call was initiated
                 */
                boolean reverseProxyMode = PassThroughConfiguration.getInstance().isReverseProxyMode();
                AxisService axisService = null;
                if (!reverseProxyMode) {
                    RequestURIBasedDispatcher requestDispatcher = new RequestURIBasedDispatcher();
                    axisService = requestDispatcher.findService(msgContext);
                }

                // the logic determines which service dispatcher to get invoke,
                // this will be determine
                // based on parameter defines at disableRestServiceDispatching,
                // and if super tenant invoke, with isTenantRequest
                // identifies whether the request to be dispatch to custom REST
                // Dispatcher Service.

                boolean isCustomRESTDispatcher = false;
                String requestURI = (String) incomingCarbonMsg.getProperty("TO");
                if (requestURI.matches(PassThroughConfiguration.getInstance().getRestUriApiRegex())
                        || requestURI.matches(PassThroughConfiguration.getInstance().getRestUriProxyRegex())) {
                    isCustomRESTDispatcher = true;
                }

                if (!isCustomRESTDispatcher) {
                    if (axisService == null) {
                        String defaultSvcName = PassThroughConfiguration.getInstance()
                                .getPassThroughDefaultServiceName();
                        axisService = msgContext.getConfigurationContext().getAxisConfiguration().
                                getService(defaultSvcName);
                        msgContext.setAxisService(axisService);
                    }
                } else {
                    String multiTenantDispatchService = PassThroughConfiguration.getInstance().getRESTDispatchService();
                    axisService = msgContext.getConfigurationContext().getAxisConfiguration()
                            .getService(multiTenantDispatchService);
                    msgContext.setAxisService(axisService);
                }
            } catch (AxisFault e) {
                RequestUtils.handleException("Error processing " + incomingCarbonMsg.getHttpMethod()
                        + " request for : " + incomingCarbonMsg.getProperty("TO"), e);
            }

            try {
                soapEnvelope = TransportUtils.createSOAPMessage(msgContext, null, contentType);
            } catch (Exception e) {
                LOGGER.error("Error while building message for REST_URL request");
            }
            //msgContext.setProperty(Constants.Configuration.CONTENT_TYPE,"application/xml");
            msgContext.setProperty(Constants.Configuration.MESSAGE_TYPE, HTTPConstants.MEDIA_TYPE_APPLICATION_XML);
        }
        return soapEnvelope;
    }
}
