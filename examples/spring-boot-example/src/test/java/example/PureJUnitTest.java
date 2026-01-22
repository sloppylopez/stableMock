package example;

import com.stablemock.spring.BaseStableMockTest;

import com.stablemock.U;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure JUnit 5 test example without Spring Boot.
 * This demonstrates that StableMock works as a general JUnit 5 extension
 * without requiring Spring Boot or autoRegisterProperties.
 * 
 * In pure JUnit tests, you access WireMock URLs via system properties:
 * - stablemock.baseUrl (for single URL)
 * - stablemock.baseUrl.0, stablemock.baseUrl.1 (for multiple URLs)
 */
@U(urls = { "https://jsonplaceholder.typicode.com" })
class PureJUnitTest {

    @Test
    void testPureJUnitWithStableMock(int port) {
        // In pure JUnit, the port is injected as a parameter
        // Build base URL from the injected port
        String baseUrl = "http://localhost:" + port;
        assertTrue(port > 0, "Port should be greater than 0");
        
        // Make HTTP request using Java 11+ HttpClient
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/users/1"))
                .GET()
                .build();
        
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            assertEquals(200, response.statusCode(), "Request should succeed");
            assertNotNull(response.body(), "Response body should not be null");
            assertTrue(response.body().contains("\"id\": 1") || response.body().contains("\"id\":1"), 
                    "Response should contain user id 1");
        } catch (java.io.IOException e) {
            if (e instanceof java.net.ConnectException) {
                fail("Failed to connect to WireMock at " + baseUrl + ". Make sure WireMock is running. Error: " + e.getMessage());
            } else {
                fail("HTTP request failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        } catch (Exception e) {
            fail("Unexpected error: " + e.getClass().getSimpleName() + ": " + (e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    @Test
    void testPureJUnitWithSystemProperty() {
        // Alternative: Get base URL from system property directly
        String baseUrl = System.getProperty("stablemock.baseUrl");
        assertNotNull(baseUrl, "Base URL should be available via system property");
        
        // Verify it's a valid URL format
        assertTrue(baseUrl.startsWith("http://"), "Base URL should be HTTP URL");
    }
}


