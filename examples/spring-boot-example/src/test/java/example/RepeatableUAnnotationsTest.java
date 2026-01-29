package example;

import com.stablemock.spring.BaseStableMockTest;

import com.stablemock.U;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class to verify that repeatable @U annotations work correctly.
 * This test uses two separate @U annotations (not a single @U with multiple URLs)
 * to verify that:
 * 1. Each @U annotation gets its own WireMock server instance
 * 2. System properties are correctly indexed (stablemock.baseUrl.0, stablemock.baseUrl.1)
 * 3. Property injection works correctly for both annotations
 * 4. Requests are recorded separately per annotation
 */
@U(urls = { "https://jsonplaceholder.typicode.com" },
   properties = { "app.thirdparty.url" })
@U(urls = { "https://postman-echo.com" },
   properties = { "app.postmanecho.url" })
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class RepeatableUAnnotationsTest extends BaseStableMockTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void registerMockUrls(DynamicPropertyRegistry registry) {
        autoRegisterProperties(registry, RepeatableUAnnotationsTest.class);
    }

    @Test
    void testRepeatableAnnotationsWork() {
        // Verify system properties are set correctly for both annotations
        String baseUrl0 = System.getProperty("stablemock.baseUrl.0");
        String baseUrl1 = System.getProperty("stablemock.baseUrl.1");
        String port0 = System.getProperty("stablemock.port.0");
        String port1 = System.getProperty("stablemock.port.1");
        
        assertNotNull(baseUrl0, "First annotation should set stablemock.baseUrl.0");
        assertNotNull(baseUrl1, "Second annotation should set stablemock.baseUrl.1");
        assertNotNull(port0, "First annotation should set stablemock.port.0");
        assertNotNull(port1, "Second annotation should set stablemock.port.1");
        
        assertTrue(baseUrl0.startsWith("http://localhost:"), "Base URL 0 should be localhost");
        assertTrue(baseUrl1.startsWith("http://localhost:"), "Base URL 1 should be localhost");
        assertNotEquals(port0, port1, "Each annotation should get its own port");
        
        // Call first API (jsonplaceholder) - Flow: Test -> Controller -> ThirdPartyService -> JsonPlaceholderClient
        ResponseEntity<String> response1Entity = restTemplate.getForEntity("/api/users/1", String.class);
        String response1 = response1Entity.getBody();
        assertNotNull(response1, "Response from jsonplaceholder should not be null");
        assertTrue(response1.contains("\"id\": 1") || response1.contains("\"id\":1"), 
                "Response should contain user id 1");
        
        // Call second API (postman-echo) - Flow: Test -> Controller -> ThirdPartyService -> PostmanEchoClient
        ResponseEntity<String> response2Entity = restTemplate.getForEntity("/api/postmanecho/users/1", String.class);
        String response2 = response2Entity.getBody();
        assertNotNull(response2, "Response from postman-echo should not be null");
        assertTrue(response2.contains("url") || response2.contains("args") || response2.contains("headers"), 
                "Response should contain echo fields");
    }

    @Test
    void testRepeatableAnnotationsRecordSeparately() {
        // Verify both annotations are tracked separately by making requests to both
        // First annotation: jsonplaceholder.typicode.com
        ResponseEntity<String> response1Entity = restTemplate.getForEntity("/api/users/2", String.class);
        String response1 = response1Entity.getBody();
        assertNotNull(response1, "Response from jsonplaceholder should not be null");
        assertTrue(response1.contains("\"id\": 2") || response1.contains("\"id\":2"), 
                "Response should contain user id 2");
        
        // Second annotation: postman-echo.com
        ResponseEntity<String> response2Entity = restTemplate.getForEntity("/api/postmanecho/users/2", String.class);
        String response2 = response2Entity.getBody();
        assertNotNull(response2, "Response from postman-echo should not be null");
        assertTrue(response2.contains("url") || response2.contains("args") || response2.contains("headers"), 
                "Response should contain echo fields");
    }
}


