package example;

import com.stablemock.spring.BaseStableMockTest;

import com.stablemock.U;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.File;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for dynamic field detection feature.
 * This test sends multiple requests with dynamic fields (timestamp, requestId)
 * and expects StableMock to automatically detect and save these patterns.
 * Expected behavior:
 * - RECORD mode: Detects changing fields and saves to detected-fields.json
 * - PLAYBACK mode: Auto-applies detected patterns (without manual annotation)
 */
@U(urls = { "https://jsonplaceholder.typicode.com" },
   properties = { "app.thirdparty.url" })
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class DynamicFieldDetectionTest extends BaseStableMockTest {

    private static final Logger logger = LoggerFactory.getLogger(DynamicFieldDetectionTest.class);

    @Autowired
    private JsonPlaceholderClient client;

    /**
     * Dynamic property source that reads from WireMockContext ThreadLocal.
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        autoRegisterProperties(registry, DynamicFieldDetectionTest.class);
        // app.postmanecho.url is not mocked, so it uses the value from application.properties
    }

    @Test
    void testDetectChangingFields() throws InterruptedException {
        // First execution - send request with dynamic fields
        String body1 = generateRequestWithDynamicFields();
        String response1 = client.createPost(body1);
        assertNotNull(response1, "First response should not be null");

        // Small delay to ensure timestamp changes
        Thread.sleep(50);

        // Second execution - send request with DIFFERENT dynamic field values
        // But SAME static fields (title, body, userId)
        String body2 = generateRequestWithDynamicFields();
        String response2 = client.createPost(body2);
        assertNotNull(response2, "Second response should not be null");

        // Verify that the bodies are different (dynamic fields changed)
        assertNotEquals(body1, body2, "Request bodies should differ due to dynamic fields");

        // In RECORD mode, this will create detected-fields.json
        // In PLAYBACK mode, both requests should match the same stub despite different
        // dynamic fields

        // Verify the analysis file was created (only check in RECORD mode)
        String mode = System.getProperty("stablemock.mode", "PLAYBACK");
        if ("RECORD".equalsIgnoreCase(mode)) {
            // After the test completes (in afterEach), the detected-fields.json should
            // exist
            // We can't check this here, but we'll verify manually after running the test
            logger.info("RECORD mode: Detection analysis will be saved in afterEach()");
        } else {
            // In PLAYBACK mode, verify the analysis file exists and was auto-applied
            File analysisFile = new File(
                    "src/test/resources/stablemock/DynamicFieldDetectionTest/testDetectChangingFields/detected-fields.json");
            assertTrue(analysisFile.exists(),
                    "Analysis file should exist after recording: " + analysisFile.getAbsolutePath());
            logger.info("PLAYBACK mode: Using auto-detected patterns from {}", analysisFile.getAbsolutePath());
        }
    }

    /**
     * Generates a JSON request body with both static and dynamic fields.
     * Static fields (should NOT be detected as dynamic):
     * - title: "Test Post"
     * - body: "This is a test post body"
     * - userId: 1
     * Dynamic fields (SHOULD be detected as dynamic):
     * - timestamp: Current timestamp (changes every call)
     * - requestId: Random UUID (changes every call)
     */
    private String generateRequestWithDynamicFields() {
        return String.format(
                "{\"title\":\"Test Post\",\"body\":\"This is a test post body\",\"userId\":1," +
                        "\"timestamp\":\"%s\",\"requestId\":\"%s\"}",
                Instant.now().toString(),
                UUID.randomUUID());
    }
}

