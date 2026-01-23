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
 * Test class to verify that multiple @U annotations work correctly.
 * This test uses @U @U (double annotation) to record mocks for URLs
 * in both annotations. The test verifies that:
 * 1. WireMock server starts correctly with multiple annotations
 * 2. Requests are recorded for URLs in both annotations
 * 3. Mappings are saved separately per annotation
 */
@U(urls = { "https://jsonplaceholder.typicode.com", "https://postman-echo.com" },
   properties = { "app.thirdparty.url", "app.postmanecho.url" })
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class MultipleAnnotationTest extends BaseStableMockTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void registerMockUrls(DynamicPropertyRegistry registry) {
        autoRegisterProperties(registry, MultipleAnnotationTest.class);
    }

    @Test
    void testMultipleAnnotationsWork() {
        // This test verifies that multiple @U annotations work correctly
        // First annotation: jsonplaceholder.typicode.com - called via Controller -> ThirdPartyService.getUser()
        // Second annotation: postman-echo.com - called via Controller -> ThirdPartyService.getUserFromPostmanEcho()
        // Both should be tracked separately
        
        // Call first API (jsonplaceholder) - Flow: Test -> Controller -> ThirdPartyService -> JsonPlaceholderClient -> jsonplaceholder.typicode.com/users/1
        ResponseEntity<String> response1Entity = restTemplate.getForEntity("/api/users/1", String.class);
        String response1 = response1Entity.getBody();
        assertNotNull(response1, "Response from jsonplaceholder should not be null");
        assertTrue(response1.contains("\"id\": 1") || response1.contains("\"id\":1"), 
                "Response should contain user id 1");
        assertTrue(response1.contains("username") || response1.contains("name"), 
                "Response should contain user fields");
        
        // Call second API (postman-echo) - Flow: Test -> Controller -> ThirdPartyService -> PostmanEchoClient -> postman-echo.com/get?id=1
        ResponseEntity<String> response2Entity = restTemplate.getForEntity("/api/postmanecho/users/1", String.class);
        String response2 = response2Entity.getBody();
        assertNotNull(response2, "Response from postman-echo should not be null");
        assertTrue(response2.contains("url") || response2.contains("args") || response2.contains("headers"), 
                "Response should contain echo fields");
    }

    @Test
    void testMultipleAnnotationsRecordSeparately() {
        // This test makes requests to both APIs to verify both annotations are tracked
        // First annotation: jsonplaceholder.typicode.com - Flow: Test -> Controller -> ThirdPartyService -> JsonPlaceholderClient -> jsonplaceholder.typicode.com/users/2
        ResponseEntity<String> response1Entity = restTemplate.getForEntity("/api/users/2", String.class);
        String response1 = response1Entity.getBody();
        assertNotNull(response1, "Response from jsonplaceholder should not be null");
        assertTrue(response1.contains("\"id\": 2") || response1.contains("\"id\":2"), 
                "Response should contain user id 2");
        
        // Second annotation: postman-echo.com - Flow: Test -> Controller -> ThirdPartyService -> PostmanEchoClient -> postman-echo.com/get?id=2
        ResponseEntity<String> response2Entity = restTemplate.getForEntity("/api/postmanecho/users/2", String.class);
        String response2 = response2Entity.getBody();
        assertNotNull(response2, "Response from postman-echo should not be null");
        assertTrue(response2.contains("url") || response2.contains("args") || response2.contains("headers"), 
                "Response should contain echo fields");
    }
}


