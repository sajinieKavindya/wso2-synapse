package org.apache.synapse.transport.netty.util;

import org.wso2.transport.http.netty.message.HttpCarbonMessage;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAccessor;

public class CacheUtils {

    public static final String WEAK_VALIDATOR_TAG = "W/";
    public static final String ETAG_HEADER = "ETag";
    public static final String IF_NONE_MATCH_HEADER = "If-None-Match";
    public static final String IF_MODIFIED_SINCE_HEADER = "If-Modified-Since";
    public static final String LAST_MODIFIED_HEADER = "Last-Modified";

    /**
     * Method for revalidating a cached response. This method follows the RFC7232 and RFC7234 specifications.
     *
     * @param outboundResponse The response to be sent to downstream
     * @param inboundRequest   The request received from downstream
     * @return Returns true if the cached response is still valid
     */
    public static boolean isValidCachedResponse(HttpCarbonMessage outboundResponse, HttpCarbonMessage inboundRequest) {
        String outgoingETag = outboundResponse.getHeader(ETAG_HEADER);
        String incomingETags = inboundRequest.getHeader(IF_NONE_MATCH_HEADER);

        if (incomingETags != null) {
            if (outgoingETag == null) {
                // If inbound request has the If-None-Match header, but the outgoing request does not have an ETag
                // header, it is considered that the cached response is invalid.
                return false;
            }

            return !isNonMatchingETag(incomingETags, outgoingETag);
        }

        // If there isn't an If-None-Match header, then check if there is a If-Modified-Since header.
        String ifModifiedSince = inboundRequest.getHeader(IF_MODIFIED_SINCE_HEADER);
        if (ifModifiedSince == null) {
            // If both If-None-Match and If-Modified-Since headers aren't there, then it is not looking for cache
            // revalidation.
            return false;
        }

        String lastModified = outboundResponse.getHeader(LAST_MODIFIED_HEADER);
        if (lastModified == null) {
            return false;
        }

        try {
            TemporalAccessor ifModifiedSinceTime = ZonedDateTime.parse(ifModifiedSince,
                    DateTimeFormatter.RFC_1123_DATE_TIME);
            TemporalAccessor lastModifiedTime = ZonedDateTime.parse(lastModified, DateTimeFormatter.RFC_1123_DATE_TIME);
            return ifModifiedSinceTime.equals(lastModifiedTime);
        } catch (DateTimeParseException e) {
            // If the Date header cannot be parsed, it is ignored.
            return false;
        }
    }

    private static boolean isNonMatchingETag(String etags, String outgoingETag) {
        String[] etagArray = etags.split(",");

        if (etagArray.length == 1 && "*".equals(etagArray[0])) {
            return false;
        }

        for (String etag : etagArray) {
            if (weakEquals(etag.trim(), outgoingETag)) {
                return false;
            }
        }

        return true;
    }

    private static boolean weakEquals(String requestETag, String responseETag) {
        // Taking the sub string to ignore "W/"
        String requestTagPortion = isWeakEntityTag(requestETag) ?
                requestETag.substring(WEAK_VALIDATOR_TAG.length()) : requestETag;
        String responseTagPortion = isWeakEntityTag(responseETag) ?
                responseETag.substring(WEAK_VALIDATOR_TAG.length()) : responseETag;

        return requestTagPortion.equals(responseTagPortion);
    }

    private static boolean isWeakEntityTag(String etag) {
        return etag.startsWith(WEAK_VALIDATOR_TAG);
    }

    private CacheUtils() {
    }

}
