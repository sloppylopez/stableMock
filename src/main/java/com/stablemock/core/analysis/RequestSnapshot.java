package com.stablemock.core.analysis;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * Represents a snapshot of a request captured during test execution.
 * Used for tracking and comparing requests across multiple executions
 * to detect dynamic fields.
 */
public class RequestSnapshot {

    private final String timestamp;
    private final String url;
    private final String method;
    private final String body;
    private final String contentType;

    @JsonCreator
    public RequestSnapshot(
            @JsonProperty("timestamp") String timestamp,
            @JsonProperty("url") String url,
            @JsonProperty("method") String method,
            @JsonProperty("body") String body,
            @JsonProperty("contentType") String contentType) {
        this.timestamp = timestamp;
        this.url = url;
        this.method = method;
        this.body = body;
        this.contentType = contentType; // May be null for backward compatibility
    }

    public RequestSnapshot(String url, String method, String body, String contentType) {
        this(Instant.now().toString(), url, method, body, contentType);
    }

    public String getTimestamp() {
        return timestamp;
    }

    public String getUrl() {
        return url;
    }

    public String getMethod() {
        return method;
    }

    public String getBody() {
        return body;
    }

    public String getContentType() {
        return contentType;
    }

    @Override
    public String toString() {
        return "RequestSnapshot{" +
                "timestamp='" + timestamp + '\'' +
                ", url='" + url + '\'' +
                ", method='" + method + '\'' +
                ", bodyLength=" + (body != null ? body.length() : 0) +
                ", contentType='" + contentType + '\'' +
                '}';
    }
}
