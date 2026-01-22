package example;

import com.stablemock.spring.BaseStableMockTest;

import com.stablemock.U;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class to verify that multiple properties for a single URL work correctly.
 * This test verifies that:
 * 1. Multiple properties can be mapped to a single URL
 * 2. All properties resolve to the same WireMock URL
 * 3. Requests work correctly with both properties
 */
@U(urls = { "https://jsonplaceholder.typicode.com" },
   properties = { "app.thirdparty.url", "app.thirdparty.backup.url" })
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class MultiplePropertiesSingleUrlTest extends BaseStableMockTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private Environment environment;

    @DynamicPropertySource
    static void registerMockUrls(DynamicPropertyRegistry registry) {
        autoRegisterProperties(registry, MultiplePropertiesSingleUrlTest.class);
    }

    @Test
    void testMultiplePropertiesMapToSameUrl() {
        // Verify both properties are registered and point to the same WireMock URL
        String url1 = environment.getProperty("app.thirdparty.url");
        String url2 = environment.getProperty("app.thirdparty.backup.url");
        
        assertNotNull(url1, "app.thirdparty.url should be registered");
        assertNotNull(url2, "app.thirdparty.backup.url should be registered");
        
        // Both should point to the same WireMock URL (localhost with dynamic port)
        assertTrue(url1.startsWith("http://localhost:"), 
                "app.thirdparty.url should point to WireMock: " + url1);
        assertTrue(url2.startsWith("http://localhost:"), 
                "app.thirdparty.backup.url should point to WireMock: " + url2);
        
        // Both should resolve to the same URL
        assertEquals(url1, url2, 
                "Both properties should map to the same WireMock URL");
        
        // Verify the port is the same (extract port from URL)
        String port1 = extractPort(url1);
        String port2 = extractPort(url2);
        assertEquals(port1, port2, "Both properties should use the same WireMock port");
    }

    @Test
    void testRequestWorksWithMultipleProperties() {
        // Verify that requests work correctly when using the primary property
        // Flow: Test -> Controller -> ThirdPartyService -> JsonPlaceholderClient -> jsonplaceholder.typicode.com/users/1
        ResponseEntity<String> responseEntity = restTemplate.getForEntity("/api/users/1", String.class);
        String response = responseEntity.getBody();
        
        assertNotNull(response, "Response should not be null");
        assertTrue(response.contains("\"id\": 1") || response.contains("\"id\":1"), 
                "Response should contain user id 1");
    }

    private String extractPort(String url) {
        // Extract port from URL like "http://localhost:54321"
        int colonIndex = url.lastIndexOf(':');
        if (colonIndex > 0) {
            String afterColon = url.substring(colonIndex + 1);
            // Remove trailing slash if present
            int slashIndex = afterColon.indexOf('/');
            if (slashIndex > 0) {
                return afterColon.substring(0, slashIndex);
            }
            return afterColon;
        }
        return null;
    }
}

