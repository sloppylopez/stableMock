package example;

import com.stablemock.U;
import org.junit.jupiter.api.Test;
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
 * Test for dynamic field detection in nested JSON structures.
 * 
 * This test verifies that StableMock can detect changing fields at various nesting levels
 * in complex JSON structures (e.g., orders with nested items, payments, metadata).
 * 
 * Expected behavior:
 * - RECORD mode: Detects changing fields at different nesting levels and saves to detected-fields.json
 * - PLAYBACK mode: Auto-applies detected patterns so requests with different nested values still match
 */
@U(urls = { "https://jsonplaceholder.typicode.com" },
   properties = { "app.thirdparty.url" })
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class NestedJsonDynamicFieldsTest extends BaseStableMockTest {

    @Autowired
    private ThirdPartyService thirdPartyService;

    @DynamicPropertySource
    static void registerMockUrls(DynamicPropertyRegistry registry) {
        autoRegisterProperties(registry, NestedJsonDynamicFieldsTest.class);
    }

    @Test
    void testNestedJsonWithDynamicFields() throws InterruptedException {
        // First request: Complex nested JSON with dynamic fields at various levels
        // Calls ThirdPartyService.createPostWithDynamicFields() -> JsonPlaceholderClient.createPost() -> jsonplaceholder.typicode.com/posts
        String timestamp1 = Instant.now().toString();
        String requestId1 = UUID.randomUUID().toString();
        String sessionId1 = UUID.randomUUID().toString();
        String transactionId1 = UUID.randomUUID().toString();
        
        String body1 = String.format("""
            {
              "order": {
                "id": "order-123",
                "items": [
                  {
                    "productId": "prod-1",
                    "quantity": 2,
                    "metadata": {
                      "timestamp": "%s",
                      "sessionId": "%s"
                    }
                  }
                ],
                "payment": {
                  "transactionId": "%s",
                  "timestamp": "%s"
                }
              },
              "requestId": "%s"
            }
            """, timestamp1, sessionId1, transactionId1, timestamp1, requestId1);

        String response1 = thirdPartyService.createPostWithDynamicFields(body1);
        assertNotNull(response1, "First response should not be null");

        Thread.sleep(50); // Small delay to ensure values change

        // Second request: Same structure, different dynamic values
        String timestamp2 = Instant.now().toString();
        String requestId2 = UUID.randomUUID().toString();
        String sessionId2 = UUID.randomUUID().toString();
        String transactionId2 = UUID.randomUUID().toString();
        
        String body2 = String.format("""
            {
              "order": {
                "id": "order-123",
                "items": [
                  {
                    "productId": "prod-1",
                    "quantity": 2,
                    "metadata": {
                      "timestamp": "%s",
                      "sessionId": "%s"
                    }
                  }
                ],
                "payment": {
                  "transactionId": "%s",
                  "timestamp": "%s"
                }
              },
              "requestId": "%s"
            }
            """, timestamp2, sessionId2, transactionId2, timestamp2, requestId2);

        String response2 = thirdPartyService.createPostWithDynamicFields(body2);
        assertNotNull(response2, "Second response should not be null");

        // Verify that the bodies are different (dynamic fields changed)
        assertNotEquals(body1, body2, "Request bodies should differ due to dynamic fields");

        // In RECORD mode, multiple nested paths should be detected:
        // - json:requestId
        // - json:order.items[0].metadata.timestamp
        // - json:order.items[0].metadata.sessionId
        // - json:order.payment.transactionId
        // - json:order.payment.timestamp
        // In PLAYBACK mode, both requests should match the same stub despite different nested values
        String mode = System.getProperty("stablemock.mode", "PLAYBACK");
        if (!"RECORD".equalsIgnoreCase(mode)) {
            File detectedFields = new File(
                    "src/test/resources/stablemock/NestedJsonDynamicFieldsTest/testNestedJsonWithDynamicFields/detected-fields.json");
            assertTrue(detectedFields.exists(),
                    "Detected fields file should exist after recording: " + detectedFields.getAbsolutePath());
        }
    }
}

