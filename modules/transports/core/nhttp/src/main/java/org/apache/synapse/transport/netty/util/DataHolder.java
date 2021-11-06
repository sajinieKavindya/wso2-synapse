package org.apache.synapse.transport.netty.util;

import org.apache.http.protocol.HTTP;
import org.apache.synapse.transport.passthru.PassThroughConstants;
import org.apache.synapse.transport.passthru.config.PassThroughConfiguration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 *
 */
public class DataHolder {

    /**
     * Whether User-Agent header coming from client should be preserved.
     */
    protected static boolean preserveUserAgentHeader = false;

    /**
     * Whether Server header coming from server should be preserved.
     */
    protected static boolean preserveServerHeader = true;

    /**
     * Http headers which should be preserved.
     */
    protected static List<String> preserveHttpHeaders;

    protected static PassThroughConfiguration passThroughConfiguration = PassThroughConfiguration.getInstance();

    private static DataHolder instance = new DataHolder();

    private DataHolder() {
        populatePreserveHttpHeaders(passThroughConfiguration.getResponsePreseveHttpHeaders());
    }

    public static DataHolder getInstance() {
        return instance;
    }

    /**
     * Check preserving status of the given http header name.
     *
     * @param headerName http header name which need to check preserving status
     * @return preserving status of the given http header
     */
    public boolean isPreserveHttpHeader(String headerName) {

        if (preserveHttpHeaders == null || preserveHttpHeaders.isEmpty() || headerName == null) {
            return false;
        }
        return preserveHttpHeaders.contains(headerName.toUpperCase());
    }

    public List<String> getPreserveHttpHeaders() {
        return preserveHttpHeaders;
    }

    /**
     * Populate preserve http headers from comma separate string.
     *
     * @param preserveHeaders Comma separated preserve enable http headers
     */
    protected static void populatePreserveHttpHeaders(String preserveHeaders) {
        preserveHttpHeaders = new ArrayList<String>();
        if (preserveHeaders != null && !preserveHeaders.isEmpty()) {
            String[] presHeaders = preserveHeaders.trim().toUpperCase().split(",");
            if (presHeaders != null && presHeaders.length > 0) {
                preserveHttpHeaders.addAll(Arrays.asList(presHeaders));
            }
        }

        if (preserveServerHeader && !preserveHttpHeaders.contains(HTTP.SERVER_HEADER.toUpperCase())) {
            preserveHttpHeaders.add(HTTP.SERVER_HEADER.toUpperCase());
        }

        if (preserveUserAgentHeader && !preserveHttpHeaders.contains(HTTP.USER_AGENT.toUpperCase())) {
            preserveHttpHeaders.add(HTTP.USER_AGENT.toUpperCase());
        }
    }

    public boolean isForcedXmlMessageValidationEnabled() {
        return passThroughConfiguration.getBooleanProperty(PassThroughConstants.FORCE_XML_MESSAGE_VALIDATION, false);
    }

    public Boolean getBooleanProperty(String name) {
        return passThroughConfiguration.getBooleanProperty(name, null);
    }

}
