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

    @JsonCreator
    public RequestSnapshot(
            @JsonProperty("timestamp") String timestamp,
            @JsonProperty("url") String url,
            @JsonProperty("method") String method,
            @JsonProperty("body") String body) {
        this.timestamp = timestamp;
        this.url = url;
        this.method = method;
        this.body = body;
    }

    public RequestSnapshot(String url, String method, String body) {
        this(Instant.now().toString(), url, method, body);
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

    @Override
    public String toString() {
        return "RequestSnapshot{" +
                "timestamp='" + timestamp + '\'' +
                ", url='" + url + '\'' +
                ", method='" + method + '\'' +
                ", bodyLength=" + (body != null ? body.length() : 0) +
                '}';
    }
}
