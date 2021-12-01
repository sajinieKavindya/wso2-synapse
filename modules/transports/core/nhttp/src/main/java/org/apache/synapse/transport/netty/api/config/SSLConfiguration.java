/*
 * Copyright (c) 2021, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.synapse.transport.netty.api.config;


import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMFactory;
import org.apache.axiom.om.util.AXIOMUtil;
import org.apache.log4j.Logger;
import org.apache.synapse.transport.nhttp.NhttpConstants;

import javax.xml.stream.XMLStreamException;

/**
 * {@code SSLConfiguration} encapsulates the transport level security related configurations.
 */
public class SSLConfiguration {

    private static final Logger LOGGER = Logger.getLogger(SSLConfiguration.class);

    private final String keyStore;
    private final String trustStore;
    private final String clientAuthEl;
    private final String httpsProtocolsEl;
    private final String revocationVerifier;
    private final String sslProtocol;
    private final String preferredCiphersEl;

    private OMElement keyStoreElement;
    private OMElement trustStoreElement;
    private OMElement clientAuthElement;
    private OMElement revocationVerifierElement;
    private OMElement httpsProtocolElement;
    private OMElement sslProtocolElement;
    private OMElement preferredCiphersElement;


    public SSLConfiguration(SSLConfigurationBuilder builder) {
        this.keyStore = builder.keyStore;
        this.trustStore = builder.trustStore;
        this.clientAuthEl = builder.clientAuthEl;
        this.httpsProtocolsEl = builder.httpsProtocolsEl;
        this.revocationVerifier = builder.revocationVerifier;
        this.sslProtocol = builder.sslProtocol;
        this.preferredCiphersEl = builder.preferredCiphersEl;
    }

    public OMElement getKeyStoreElement() {
        if (keyStore != null) {
            try {
                keyStoreElement = AXIOMUtil.stringToOM(keyStore);
            } catch (XMLStreamException e) {
                LOGGER.error("Keystore may not be well formed XML", e);
            }
        }
        return keyStoreElement;
    }

    public OMElement getClientAuthElement() {
        if (clientAuthEl != null) {
            OMFactory fac = OMAbstractFactory.getOMFactory();
            clientAuthElement = fac.createOMElement("SSLVerifyClient", "", "");
            clientAuthElement.setText(clientAuthEl);
        }
        return clientAuthElement;
    }

    public OMElement getTrustStoreElement() {
        if (trustStore != null) {
            try {
                trustStoreElement = AXIOMUtil.stringToOM(trustStore);
            } catch (XMLStreamException e) {
                LOGGER.error("TrustStore may not be well formed XML", e);
            }
        }
        return trustStoreElement;
    }

    public OMElement getRevocationVerifierElement() {
        if (revocationVerifier != null) {
            try {
                revocationVerifierElement = AXIOMUtil.stringToOM(revocationVerifier);
            } catch (XMLStreamException e) {
                LOGGER.error("CertificateRevocationVerifier may not be well formed XML", e);
            }
        }
        return revocationVerifierElement;
    }

    public OMElement getHttpsProtocolElement() {
        if (httpsProtocolsEl != null) {
            OMFactory fac = OMAbstractFactory.getOMFactory();
            httpsProtocolElement = fac.createOMElement("HttpsProtocols", "", "");
            httpsProtocolElement.setText(httpsProtocolsEl);
        }
        return httpsProtocolElement;
    }

    public OMElement getSslProtocolElement() {
        if (sslProtocol != null) {
            OMFactory fac = OMAbstractFactory.getOMFactory();
            sslProtocolElement = fac.createOMElement("SSLProtocol", "", "");
            sslProtocolElement.setText(sslProtocol);
        }
        return sslProtocolElement;
    }

    public String getPreferredCiphersEl() {
        return preferredCiphersEl;
    }

    /**
     * Return a OMElement of preferred ciphers parameter values.
     * @return OMElement
     */
    public OMElement getPreferredCiphersElement() {
        if (preferredCiphersEl != null) {
            OMFactory fac = OMAbstractFactory.getOMFactory();
            preferredCiphersElement = fac.createOMElement(NhttpConstants.PREFERRED_CIPHERS, "", "");
            preferredCiphersElement.setText(preferredCiphersEl);
        }
        return preferredCiphersElement;
    }

    public String getSslProtocol() {
        return sslProtocol;
    }

    public String getKeyStore() {
        return keyStore;
    }

    public String getTrustStore() {
        return trustStore;
    }

    public String getClientAuthEl() {
        return clientAuthEl;
    }

    public String getHttpsProtocolsEl() {
        return httpsProtocolsEl;
    }

    public String getRevocationVerifier() {
        return revocationVerifier;
    }

    public static class SSLConfigurationBuilder {

        private String keyStore;
        private String trustStore;
        private String clientAuthEl;
        private String httpsProtocolsEl;
        private String revocationVerifier;
        private String sslProtocol;
        private String preferredCiphersEl;

        public SSLConfiguration build() {
            return new SSLConfiguration(this);
        }

        public SSLConfigurationBuilder keyStore(String keyStore) {
            this.keyStore = keyStore;
            return this;
        }

        public SSLConfigurationBuilder trustStore(String trustStore) {
            this.trustStore = trustStore;
            return this;
        }

        public SSLConfigurationBuilder clientAuthEl(String clientAuthEl) {
            this.clientAuthEl = clientAuthEl;
            return this;
        }

        public SSLConfigurationBuilder httpsProtocolsEl(String httpsProtocolsEl) {
            this.httpsProtocolsEl = httpsProtocolsEl;
            return this;
        }

        public SSLConfigurationBuilder revocationVerifier(String revocationVerifier) {
            this.revocationVerifier = revocationVerifier;
            return this;
        }

        public SSLConfigurationBuilder sslProtocol(String sslProtocol) {
            this.sslProtocol = sslProtocol;
            return this;
        }

        public SSLConfigurationBuilder preferredCiphersEl(String preferredCiphersEl) {
            this.preferredCiphersEl = preferredCiphersEl;
            return this;
        }
    }

}
