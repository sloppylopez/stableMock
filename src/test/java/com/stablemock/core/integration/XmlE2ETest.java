package com.stablemock.core.integration;

import com.stablemock.U;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test for XML request handling with dynamic field detection.
 * Uses Postman Echo API to send real XML requests and verify:
 * 1. XML auto-detection works
 * 2. Dynamic field detection identifies changing elements and attributes
 * 3. Ignore patterns are applied correctly
 * 4. Playback mode works with ignored fields
 */
@Execution(ExecutionMode.SAME_THREAD) // avoid parallel runs that lock shared mappings
@U(urls = { "https://postman-echo.com" })
class XmlE2ETest {

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private String getBaseUrl() {
        String baseUrl = System.getProperty("stablemock.baseUrl");
        assertNotNull(baseUrl, "stablemock.baseUrl should be set by StableMock extension");
        return baseUrl;
    }

    @Test
    void testXmlRequestWithDynamicFields(int port) throws Exception {
        // Generate unique values that will change on each run
        String timestamp = java.time.Instant.now().toString();
        String requestId = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();

        // Create XML request with dynamic fields (elements and attributes)
        String xmlBody = String.format(
                "<request>" +
                "<header id=\"%s\" version=\"1.0\">" +
                "<timestamp>%s</timestamp>" +
                "<requestId>%s</requestId>" +
                "</header>" +
                "<data>" +
                "<user sessionId=\"%s\">" +
                "<name>Test User</name>" +
                "<email>test@example.com</email>" +
                "</user>" +
                "</data>" +
                "</request>",
                sessionId, timestamp, requestId, sessionId);

        // Send POST request to Postman Echo (which echoes back the request)
        String url = getBaseUrl() + "/post";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/xml")
                .POST(HttpRequest.BodyPublishers.ofString(xmlBody))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // In RECORD mode: WireMock proxies to Postman Echo and records the response
        // In PLAYBACK mode: WireMock returns recorded response (requires running RECORD mode first)
        String mode = System.getProperty("stablemock.mode", "PLAYBACK");
        int statusCode = response.statusCode();
        String responseBody = response.body();
        
        if ("RECORD".equals(mode)) {
            assertEquals(200, statusCode, 
                    "Request should succeed in RECORD mode. Status: " + statusCode + 
                    ", Body: " + (responseBody != null ? responseBody.substring(0, Math.min(500, responseBody.length())) : "null"));
        } else {
            // In PLAYBACK mode: Fail if no mappings exist (user must run RECORD mode first)
            // Once mappings exist, this will pass normally
            if (statusCode != 200) {
                String bodyPreview = responseBody != null ? responseBody.substring(0, Math.min(300, responseBody.length())) : "null";
                fail(String.format(
                    "Request failed in PLAYBACK mode! Status: %d, Body: %s%n" +
                    "STEP 1: Run in RECORD mode first: ./gradlew test --tests XmlE2ETest -Dstablemock.mode=RECORD%n" +
                    "STEP 2: Then run in PLAYBACK mode: ./gradlew test --tests XmlE2ETest",
                    statusCode, bodyPreview
                ));
            }
        }
        
        assertNotNull(response.body(), "Response body should not be null");
        
        // Postman Echo returns JSON with the echoed data
        // In RECORD mode, WireMock will capture this
        // In PLAYBACK mode, WireMock will return the recorded response
        assertTrue(response.body().contains("data") || response.body().contains("xml") || 
                   response.body().contains("postman") || response.body().contains("echo"), 
                "Response should contain echoed data. Got: " + response.body().substring(0, Math.min(300, response.body().length())));
    }

    @Test
    void testXmlRequestWithNestedDynamicFields(int port) throws Exception {
        // Test nested elements with same name (should handle correctly)
        String timestamp1 = java.time.Instant.now().toString();
        String timestamp2 = java.time.Instant.now().plusSeconds(1).toString();

        String xmlBody = String.format(
                "<order>" +
                "<orderId>12345</orderId>" +
                "<timestamp>%s</timestamp>" +
                "<items>" +
                "<item>" +
                "<id>1</id>" +
                "<timestamp>%s</timestamp>" +
                "</item>" +
                "</items>" +
                "</order>",
                timestamp1, timestamp2);

        String url = getBaseUrl() + "/post";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/xml")
                .POST(HttpRequest.BodyPublishers.ofString(xmlBody))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        String mode = System.getProperty("stablemock.mode", "PLAYBACK");
        int statusCode = response.statusCode();
        String responseBody = response.body();
        
        if ("RECORD".equals(mode)) {
            assertEquals(200, statusCode, 
                    "Request should succeed in RECORD mode. Status: " + statusCode);
        } else {
            // In PLAYBACK mode: Fail if no mappings exist (user must run RECORD mode first)
            // Once mappings exist, this will pass normally
            if (statusCode != 200) {
                String bodyPreview = responseBody != null ? responseBody.substring(0, Math.min(300, responseBody.length())) : "null";
                fail(String.format(
                    "Request failed in PLAYBACK mode! Status: %d, Body: %s%n" +
                    "STEP 1: Run in RECORD mode first: ./gradlew test --tests XmlE2ETest -Dstablemock.mode=RECORD%n" +
                    "STEP 2: Then run in PLAYBACK mode: ./gradlew test --tests XmlE2ETest",
                    statusCode, bodyPreview
                ));
            }
        }
    }

    @Test
    void testXmlRequestWithAttributes(int port) throws Exception {
        // Test dynamic attributes
        String id = UUID.randomUUID().toString();
        String version = "2." + System.currentTimeMillis();

        String xmlBody = String.format(
                "<document id=\"%s\" version=\"%s\">" +
                "<title>Test Document</title>" +
                "<content>Static content</content>" +
                "</document>",
                id, version);

        String url = getBaseUrl() + "/post";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/xml")
                .POST(HttpRequest.BodyPublishers.ofString(xmlBody))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        String mode = System.getProperty("stablemock.mode", "PLAYBACK");
        int statusCode = response.statusCode();
        String responseBody = response.body();
        
        if ("RECORD".equals(mode)) {
            assertEquals(200, statusCode, 
                    "Request should succeed in RECORD mode. Status: " + statusCode);
        } else {
            // In PLAYBACK mode: Fail if no mappings exist (user must run RECORD mode first)
            // Once mappings exist, this will pass normally
            if (statusCode != 200) {
                String bodyPreview = responseBody != null ? responseBody.substring(0, Math.min(300, responseBody.length())) : "null";
                fail(String.format(
                    "Request failed in PLAYBACK mode! Status: %d, Body: %s%n" +
                    "STEP 1: Run in RECORD mode first: ./gradlew test --tests XmlE2ETest -Dstablemock.mode=RECORD%n" +
                    "STEP 2: Then run in PLAYBACK mode: ./gradlew test --tests XmlE2ETest",
                    statusCode, bodyPreview
                ));
            }
        }
    }
}

