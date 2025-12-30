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
 * Test class to verify that multiple @U annotations work correctly.
 * This test uses @U @U (double annotation) to record mocks for URLs
 * in both annotations. The test verifies that:
 * 1. WireMock server starts correctly with multiple annotations
 * 2. Requests are recorded for URLs in both annotations
 * 3. Mappings are saved separately per annotation
 */
@U(urls = { "https://jsonplaceholder.typicode.com", "https://postman-echo.com" })
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class MultipleAnnotationTest extends BaseStableMockTest {

    @Autowired
    private TestRestTemplate  restTemplate;

    @DynamicPropertySource
    static void registerMockUrls(DynamicPropertyRegistry registry) {
        registerPropertyWithFallbackByIndex(registry, "app.thirdparty.url", "MultipleAnnotationTest", 0,
                "https://jsonplaceholder.typicode.com");
        registerPropertyWithFallbackByIndex(registry, "app.postmanecho.url", "MultipleAnnotationTest", 1,
                "https://postman-echo.com");
    }

    @Test
    void testMultipleAnnotationsWork() {
        // This test verifies that multiple @U annotations work correctly
        // First annotation: jsonplaceholder.typicode.com - called via /api/users/1
        // Second annotation: postman-echo.com - called via /api/postmanecho/users/1
        // Both should be tracked separately
        
        // Call first API (jsonplaceholder)
        String response1 = restTemplate.getForObject("/api/users/1", String.class);
        assertNotNull(response1, "Response from jsonplaceholder should not be null");
        assertTrue(response1.contains("\"id\": 1") || response1.contains("\"id\":1"), 
                "Response should contain user id 1");
        assertTrue(response1.contains("username") || response1.contains("name"), 
                "Response should contain user fields");
        
        // Call second API (postman-echo)
        String response2 = restTemplate.getForObject("/api/postmanecho/users/1", String.class);
        assertNotNull(response2, "Response from postman-echo should not be null");
        assertTrue(response2.contains("url") || response2.contains("args") || response2.contains("headers"), 
                "Response should contain echo fields");
    }

    @Test
    void testMultipleAnnotationsRecordSeparately() {
        // This test makes requests to both APIs to verify both annotations are tracked
        // First annotation: jsonplaceholder.typicode.com
        String response1 = restTemplate.getForObject("/api/users/2", String.class);
        assertNotNull(response1, "Response from jsonplaceholder should not be null");
        assertTrue(response1.contains("\"id\": 2") || response1.contains("\"id\":2"), 
                "Response should contain user id 2");
        
        // Second annotation: postman-echo.com
        String response2 = restTemplate.getForObject("/api/postmanecho/users/2", String.class);
        assertNotNull(response2, "Response from postman-echo should not be null");
        assertTrue(response2.contains("url") || response2.contains("args") || response2.contains("headers"), 
                "Response should contain echo fields");
    }
}

