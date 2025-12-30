package example;

import com.stablemock.U;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class to verify that multiple @U annotations (each with 1 URL and 1 property) work correctly.
 * This test uses @U @U (double annotation) instead of a single @U with multiple URLs.
 * 
 * This demonstrates the cleaner approach where each annotation maps to one service:
 * - First @U: jsonplaceholder.typicode.com -> app.thirdparty.url
 * - Second @U: postman-echo.com -> app.postmanecho.url
 * 
 * The test verifies that:
 * 1. WireMock servers start correctly for each annotation
 * 2. Requests are recorded separately per annotation
 * 3. Properties are auto-registered correctly via autoRegisterProperties()
 * 4. Playback works correctly with mappings from both annotations
 */
@U(urls = { "https://jsonplaceholder.typicode.com" }, properties = { "app.thirdparty.url" })
@U(urls = { "https://postman-echo.com" }, properties = { "app.postmanecho.url" })
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class DoubleAnnotationTest extends BaseStableMockTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void registerMockUrls(DynamicPropertyRegistry registry) {
        autoRegisterProperties(registry, DoubleAnnotationTest.class);
    }

    @Test
    void testDoubleAnnotationsWork() {
        // This test verifies that two separate @U annotations work correctly
        // First annotation: jsonplaceholder.typicode.com - called via /api/users/1
        // Second annotation: postman-echo.com - called via /api/postmanecho/users/1
        // Both should be tracked separately with their own annotation indices
        
        // Call first API (jsonplaceholder) - annotation 0
        String response1 = restTemplate.getForObject("/api/users/1", String.class);
        assertNotNull(response1, "Response from jsonplaceholder should not be null");
        assertTrue(response1.contains("\"id\": 1") || response1.contains("\"id\":1"), 
                "Response should contain user id 1");
        assertTrue(response1.contains("username") || response1.contains("name"), 
                "Response should contain user fields");
        
        // Call second API (postman-echo) - annotation 1
        String response2 = restTemplate.getForObject("/api/postmanecho/users/1", String.class);
        assertNotNull(response2, "Response from postman-echo should not be null");
        assertTrue(response2.contains("url") || response2.contains("args") || response2.contains("headers"), 
                "Response should contain echo fields");
    }

    @Test
    void testDoubleAnnotationsRecordSeparately() {
        // This test makes requests to both APIs to verify both annotations are tracked separately
        // First annotation (index 0): jsonplaceholder.typicode.com
        String response1 = restTemplate.getForObject("/api/users/2", String.class);
        assertNotNull(response1, "Response from jsonplaceholder should not be null");
        assertTrue(response1.contains("\"id\": 2") || response1.contains("\"id\":2"), 
                "Response should contain user id 2");
        
        // Second annotation (index 1): postman-echo.com
        String response2 = restTemplate.getForObject("/api/postmanecho/users/2", String.class);
        assertNotNull(response2, "Response from postman-echo should not be null");
        assertTrue(response2.contains("url") || response2.contains("args") || response2.contains("headers"), 
                "Response should contain echo fields");
    }
}
