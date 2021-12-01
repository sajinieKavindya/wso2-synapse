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
package org.apache.synapse.transport.netty;

/**
 * {@code BridgeConstants} contains the constants related to netty axis2 bridge.
 */
public class BridgeConstants {
    public static final String BRIDGE_LOG_PREFIX = "[Bridge] ";

    public static final String NO_ENTITY_BODY = "NO_ENTITY_BODY";

    public static final String REMOTE_HOST = "REMOTE_HOST";

    public static final String HTTP_METHOD = "HTTP_METHOD";
    public static final String HTTP_STATUS_CODE = "HTTP_STATUS_CODE";
    public static final String HTTP_REASON_PHRASE = "HTTP_REASON_PHRASE";
    public static final String CONTENT_TYPE_HEADER = "Content-Type";
    public static final String SOAP_ACTION_HEADER = "SOAPAction";
    public static final String TRANSFER_ENCODING = "Transfer-Encoding";

    public static final String CONTENT_LEN = "Content-Length";

    public static final String HTTP_STATUS_CODE_PROP = "HTTP_SC";
    public static final String HTTP_STATUS_CODE_DESCRIPTION_PROP = "HTTP_SC_DESC";

    public static final String REST_URL_POSTFIX = "REST_URL_POSTFIX";
    public static final String SERVICE_PREFIX = "SERVICE_PREFIX";

    public static final String REST_REQUEST_CONTENT_TYPE = "synapse.internal.rest.contentType";
    public static final String HTTP_CARBON_MESSAGE = "HTTP_CARBON_MESSAGE";
    public static final String HTTP_CLIENT_REQUEST_CARBON_MESSAGE = "HTTP_CLIENT_REQUEST_CARBON_MESSAGE";

    public static final String MESSAGE_BUILDER_INVOKED = "message.builder.invoked";

    public static final long NO_CONTENT_LENGTH_FOUND = -1;
    public static final short ONE_BYTE = 1;

    public static final String INVOKED_REST = "invokedREST";

    public static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";
    public static final String RELAY_EARLY_BUILD = "relay_early_build";
    public static final String RAW_PAYLOAD = "RAW_PAYLOAD";

    public static final String POOLED_BYTE_BUFFER_FACTORY = "POOLED_BYTE_BUFFER_FACTORY";
    public static final String MESSAGE_OUTPUT_FORMAT = "MESSAGE_OUTPUT_FORMAT";
    public static final String FORCE_SOAP_FAULT = "FORCE_SOAP_FAULT";
    public static final String FORCE_SC_ACCEPTED = "FORCE_SC_ACCEPTED";
    public static final String NIO_ACK_REQUESTED = "NIO-ACK-Requested";
    public static final String WSDL_REQUEST_HANDLED = "WSDL_REQUEST_HANDLED";
    // used to define the default content type as a parameter in the axis2.xml
    public static final String REQUEST_CONTENT_TYPE = "DEFAULT_REQUEST_CONTENT_TYPE";


    // move later
    public static final String CARBON_SERVER_XML_NAMESPACE = "http://wso2.org/projects/carbon/carbon.xml";

    public static final String CHUNKING_CONFIG = "chunking_config";

    public static final String DEFAULT_VERSION_HTTP_1_1 = "HTTP/1.1";
    public static final float HTTP_1_1 = 1.1f;
    public static final float HTTP_1_0 = 1.0f;
    public static final String HTTP_2_0 = "2.0";
    public static final String HTTP_VERSION_PREFIX = "HTTP/";
    public static final String HTTP_1_1_VERSION = "1.1";
    public static final String HTTP_2_0_VERSION = "2.0";

    public static final String AUTO = "AUTO";
    public static final String ALWAYS = "ALWAYS";
    public static final String NEVER = "NEVER";

    public static final String PROTOCOL_HTTP = "http";
    public static final String PROTOCOL_HTTPS = "https";

    private static final String LOCAL_HOST = "localhost";
    public static final String HTTP_DEFAULT_HOST = "0.0.0.0";
    public static final String TO = "TO";

    // Logging related runtime parameter names
    public static final String HTTP_TRACE_LOG = "http.tracelog";
    public static final String HTTP_TRACE_LOG_ENABLED = "http.tracelog.enabled";
    public static final String HTTP_ACCESS_LOG = "http.accesslog";
    public static final String HTTP_ACCESS_LOG_ENABLED = "http.accesslog.enabled";

    public static final String KEY_STORE = "keystore";
    public static final String TRUST_STORE = "truststore";
    public static final String SSL_VERIFY_CLIENT = "SSLVerifyClient";
    public static final String SSL_PROTOCOL = "SSLProtocol";
    public static final String HTTPS_PROTOCOL = "HttpsProtocols";
    public static final String CLIENT_REVOCATION = "CertificateRevocationVerifier";
    public static final String PREFERRED_CIPHERS = "PreferredCiphers";
    public static final String SSL_SESSION_TIMEOUT = "sessionTimeout";
    public static final String SSL_HANDSHAKE_TIMEOUT = "handshakeTimeout";
    public static final String STORE_LOCATION = "Location";
    public static final String TYPE = "Type";
    public static final String PASSWORD = "Password";
    public static final String KEY_PASSWORD = "KeyPassword";

}
