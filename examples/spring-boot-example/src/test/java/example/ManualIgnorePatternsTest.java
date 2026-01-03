package example;

import com.stablemock.U;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for manual ignore patterns feature.
 * This test explicitly uses the ignore parameter to ignore dynamic fields
 * without requiring multiple runs for auto-detection.
 * 
 * Expected behavior:
 * - RECORD mode: Manual ignore patterns are applied immediately
 * - PLAYBACK mode: Requests with different values for ignored fields should still match
 */
@U(urls = { "https://jsonplaceholder.typicode.com" },
   properties = { "app.thirdparty.url" },
   ignore = { "json:timestamp", "json:requestId" })
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ManualIgnorePatternsTest extends BaseStableMockTest {

    @Autowired
    private JsonPlaceholderClient client;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        autoRegisterProperties(registry, ManualIgnorePatternsTest.class);
    }

    @Test
    void testManualIgnorePatternsWork() {
        // Send request with dynamic fields that are manually ignored
        String body1 = generateRequestWithDynamicFields();
        String response1 = client.createPost(body1);
        assertNotNull(response1, "First response should not be null");

        // Send another request with DIFFERENT values for ignored fields
        // This should still match the same stub in PLAYBACK mode
        String body2 = generateRequestWithDynamicFields();
        String response2 = client.createPost(body2);
        assertNotNull(response2, "Second response should not be null");

        // Verify that the bodies are different (dynamic fields changed)
        assertNotEquals(body1, body2, "Request bodies should differ due to dynamic fields");

        // In RECORD mode, manual ignore patterns are applied immediately
        // In PLAYBACK mode, both requests should match the same stub despite different
        // values for timestamp and requestId fields
    }

    /**
     * Generates a JSON request body with both static and dynamic fields.
     * Dynamic fields (timestamp, requestId) are manually ignored via @U annotation.
     */
    private String generateRequestWithDynamicFields() {
        return String.format(
                "{\"title\":\"Manual Ignore Test\",\"body\":\"Testing manual ignore patterns\",\"userId\":1," +
                        "\"timestamp\":\"%s\",\"requestId\":\"%s\"}",
                Instant.now().toString(),
                UUID.randomUUID());
    }
}

