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

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.apache.http.HttpStatus;
import org.apache.synapse.transport.netty.BridgeConstants;
import org.wso2.transport.http.netty.contract.config.ChunkConfig;
import org.wso2.transport.http.netty.message.HttpCarbonMessage;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 *
 */
public class SourceResponse {

    /**
     * Transport headers.
     */
    private Map<String, TreeSet<String>> headers = new HashMap<String, TreeSet<String>>();
    /**
     * Status of the response.
     */
    private int statusCode = HttpStatus.SC_OK;
    /**
     * Status line.
     */
    private String statusLine = null;
    /**
     * Version of the response.
     */
    private HttpVersion version = HttpVersion.HTTP_1_1;
    /**
     * Keep alive request.
     */
    private boolean keepAlive = true;


    private ChunkConfig chunkConfig = ChunkConfig.ALWAYS;

    /**
     * Outbound response Http Carbon msg.
     */
    private HttpCarbonMessage response;

    public SourceResponse(Map<String, TreeSet<String>> headers, int statusCode, String statusLine, HttpVersion version,
                          boolean keepAlive) {

        this.headers = headers;
        this.statusCode = statusCode;
        this.statusLine = statusLine;
        this.version = version;
        this.keepAlive = keepAlive;
    }

    public SourceResponse(Map<String, TreeSet<String>> headers, int statusCode, HttpVersion version,
                          boolean keepAlive) {

        this.headers = headers;
        this.statusCode = statusCode;
        this.version = version;
        this.keepAlive = keepAlive;
    }

    public SourceResponse(HttpVersion version, int statusCode, String statusLine) {

        this(version, statusCode);
        this.statusLine = statusLine;
    }

    public SourceResponse(HttpVersion version, int statusCode) {

        this.statusCode = statusCode;
        this.version = version;
    }

    public HttpCarbonMessage getResponse() {

        if (Objects.isNull(response)) {
            createResponse();
        }
        return response;
    }

    public void createResponse() {

        HttpHeaders httpHeaders = new DefaultHttpHeaders(true);
//        if (!keepAlive) {
//            httpHeaders.add(HTTP.CONN_DIRECTIVE, HTTP.CONN_CLOSE);
//        }

        headers.remove("Host");

        // set any transport headers
        Set<Map.Entry<String, TreeSet<String>>> entries = headers.entrySet();
        for (Map.Entry<String, TreeSet<String>> entry : entries) {
            if (entry.getKey() != null) {
                Iterator<String> i = entry.getValue().iterator();
                while (i.hasNext()) {
                    httpHeaders.add(entry.getKey(), i.next());
                }
            }
        }

        DefaultHttpResponse httpResponse;
        if (Objects.isNull(statusLine)) {
            httpResponse = new DefaultHttpResponse(version, HttpResponseStatus.valueOf(statusCode), httpHeaders);
        } else {
            HttpResponseStatus newResponseStatus = new HttpResponseStatus(statusCode, statusLine);
            httpResponse = new DefaultHttpResponse(version, newResponseStatus, httpHeaders);
        }

        HttpCarbonMessage outboundHttpCarbonMessage = new HttpCarbonMessage(httpResponse);

        outboundHttpCarbonMessage.setProperty(BridgeConstants.CHUNKING_CONFIG, chunkConfig);

        response = outboundHttpCarbonMessage;

    }

    public int getStatusCode() {

        return statusCode;
    }

    public void setStatusCode(int statusCode) {

        this.statusCode = statusCode;
    }

    public String getStatusLine() {

        return statusLine;
    }

    public void setStatusLine(String statusLine) {

        this.statusLine = statusLine;
    }

    public HttpVersion getVersion() {

        return version;
    }

    public void setVersion(HttpVersion version) {

        this.version = version;
    }

    public boolean isKeepAlive() {

        return keepAlive;
    }

    public void setKeepAlive(boolean keepAlive) {

        this.keepAlive = keepAlive;
    }

    public Map<String, TreeSet<String>> getHeaders() {

        return headers;
    }

    public TreeSet<String> getHeader(String header) {
        return headers.get(header);
    }

    public void removeHeader(String header) {
        headers.remove(header);
    }

    public void setChunked(boolean chunked) {
        if (!chunked) {
            chunkConfig = ChunkConfig.NEVER;
        }
    }

    public void addHeader(String name, String value) {
        if (Objects.isNull(headers.get(name))) {
            TreeSet<String> values = new TreeSet<String>();
            values.add(value);
            headers.put(name, values);
        } else {
            TreeSet<String> values = headers.get(name);
            values.add(value);
        }
    }
}
