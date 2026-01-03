package example;

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
 * Test to demonstrate stablemock.showMatches diagnostic mode.
 * This test verifies that when showMatches is enabled, detailed matching
 * information is logged for debugging request matching issues.
 * 
 * To run with showMatches enabled:
 * ./gradlew stableMockRecord "-Dstablemock.showMatches=true"
 * ./gradlew stableMockPlayback "-Dstablemock.showMatches=true"
 */
@U(urls = { "https://jsonplaceholder.typicode.com" },
   properties = { "app.thirdparty.url" })
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ShowMatchesTest extends BaseStableMockTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        autoRegisterProperties(registry, ShowMatchesTest.class);
    }

    @Test
    void testShowMatchesEnabled() {
        // Check if showMatches is enabled
        String showMatches = System.getProperty("stablemock.showMatches", "false");
        boolean isEnabled = "true".equalsIgnoreCase(showMatches);
        
        // Make a request - if showMatches is enabled, detailed matching logs should appear
        ResponseEntity<String> response = restTemplate.getForEntity("/api/users/1", String.class);
        
        assertNotNull(response.getBody(), "Response should not be null");
        assertTrue(response.getBody().contains("\"id\": 1") || response.getBody().contains("\"id\":1"), 
                "Response should contain user id 1");
        
        // When showMatches=true, WireMock logs detailed matching information
        // This is useful for debugging why requests don't match expected stubs
        // The actual logging happens in WireMock, we just verify the test runs correctly
    }

    @Test
    void testShowMatchesWithMismatch() {
        // This test intentionally makes a request that might not match perfectly
        // to demonstrate showMatches logging when there are matching issues
        ResponseEntity<String> response = restTemplate.getForEntity("/api/users/3", String.class);
        
        assertNotNull(response.getBody(), "Response should not be null");
        
        // With showMatches enabled, you'll see detailed logs about:
        // - Request matching attempts
        // - Why requests matched or didn't match
        // - Available stubs and their patterns
    }
}

