package example.inheritance;

import com.stablemock.U;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.File;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reproduction test for multi-level inheritance issue with ignore patterns.
 * 
 * Inheritance chain: ReproductionTest -> BaseTestFeature -> BaseOpenApiTestFeature -> BaseStableMockTest
 * 
 * Problem: In PLAYBACK mode, ignore patterns from detected-fields.json are not applied,
 * causing WireMock to return 404 because dynamic XML fields (TransactionIdentifier, EchoToken, TimeStamp)
 * have different values than the hardcoded values in the mapping file.
 * 
 * Expected behavior:
 * - RECORD mode: Works correctly - StableMock records requests and creates detected-fields.json
 * - PLAYBACK mode: Should apply ignore patterns to allow requests with different dynamic field values to match
 */
@U(urls = { "https://postman-echo.com" },
   properties = { "app.postmanecho.url" })
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class ReproductionTest extends BaseTestFeature {

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Use the method from BaseStableMockTest (now in library)
        autoRegisterProperties(registry, ReproductionTest.class);
    }

    @Test
    void testXmlWithDynamicFieldsInInheritance() throws Exception {
        // Generate XML with dynamic fields similar to GetAvailabilityV2Test
        // These fields change each run: TransactionIdentifier, EchoToken, TimeStamp
        String xml1 = generateXmlWithDynamicFields();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        HttpEntity<String> request1 = new HttpEntity<>(xml1, headers);
        ResponseEntity<String> response1Entity = restTemplate.postForEntity("/api/postmanecho/xml", request1, String.class);
        String resp1 = response1Entity.getBody();
        assertNotNull(resp1, "First response should not be null");

        Thread.sleep(50);

        // Second request with different dynamic field values
        String xml2 = generateXmlWithDynamicFields();
        HttpEntity<String> request2 = new HttpEntity<>(xml2, headers);
        ResponseEntity<String> response2Entity = restTemplate.postForEntity("/api/postmanecho/xml", request2, String.class);
        String resp2 = response2Entity.getBody();
        assertNotNull(resp2, "Second response should not be null");

        assertNotEquals(xml1, xml2, "XML bodies should differ due to dynamic fields");

        // In PLAYBACK mode, detected-fields.json should exist and ignore patterns should be applied
        String mode = System.getProperty("stablemock.mode", "PLAYBACK");
        if (!"RECORD".equalsIgnoreCase(mode)) {
            File analysisFile = new File(
                    "src/test/resources/stablemock/ReproductionTest/testXmlWithDynamicFieldsInInheritance/detected-fields.json");
            assertTrue(analysisFile.exists(),
                    "Analysis file should exist after recording: " + analysisFile.getAbsolutePath());
            
            // The test should pass in PLAYBACK mode because ignore patterns are applied
            // If this fails with 404, it means ignore patterns are not being applied correctly
        }
    }

    /**
     * Generates XML with dynamic fields similar to GetAvailabilityV2Test:
     * - TransactionIdentifier: changes each run
     * - EchoToken: changes each run  
     * - TimeStamp: changes each run
     */
    private String generateXmlWithDynamicFields() {
        // Simulate TransactionIdentifier, EchoToken, TimeStamp like in GetAvailabilityV2Test
        long timestamp = System.currentTimeMillis();
        String transactionIdentifier = String.valueOf(timestamp - 10);
        String echoToken = String.valueOf(timestamp);
        String timeStamp = Instant.now().toString();

        return String.format(
                "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
                        "<soap:Header>" +
                        "<TransactionIdentifier>%s</TransactionIdentifier>" +
                        "<EchoToken>%s</EchoToken>" +
                        "</soap:Header>" +
                        "<soap:Body>" +
                        "<Request>" +
                        "<TimeStamp>%s</TimeStamp>" +
                        "<StayDateRange>" +
                        "<Start>2026-01-22</Start>" +
                        "<End>2026-01-24</End>" +
                        "</StayDateRange>" +
                        "</Request>" +
                        "</soap:Body>" +
                        "</soap:Envelope>",
                transactionIdentifier, echoToken, timeStamp);
    }
}
