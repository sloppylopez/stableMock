package example;

import com.stablemock.U;
import com.stablemock.spring.BaseStableMockTest;
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
import java.nio.file.Files;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify that StableMock correctly handles XML elements with namespace prefixes.
 * 
 * This test ensures that XPath patterns generated in detected-fields.json use local names
 * (without namespace prefixes) in local-name() function calls, which is required for
 * correct XPath matching.
 * 
 * Bug: Previously, patterns like "xml://*[local-name()='ns4:ServiceRequest']" were
 * generated, which never match because local-name() returns only the local part
 * (without prefix). The correct pattern is "xml://*[local-name()='ServiceRequest']".
 * 
 * Expected behavior:
 * - RECORD mode: Generates correct XPath patterns without namespace prefixes in local-name()
 * - PLAYBACK mode: Patterns match correctly, allowing requests with different dynamic field values
 */
@U(urls = { "https://postman-echo.com" },
   properties = { "app.postmanecho.url" })
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class XmlNamespacePrefixTest extends BaseStableMockTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        autoRegisterProperties(registry, XmlNamespacePrefixTest.class);
    }

    @Test
    void testXmlWithNamespacePrefixes() throws Exception {
        // Generate XML with namespace prefixes similar to SOAP services
        // This mimics real-world scenarios with namespaced XML elements
        String xml1 = generateXmlWithNamespacePrefixes();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        HttpEntity<String> request1 = new HttpEntity<>(xml1, headers);
        ResponseEntity<String> response1Entity = restTemplate.postForEntity("/api/postmanecho/xml", request1, String.class);
        String resp1 = response1Entity.getBody();
        assertNotNull(resp1, "First response should not be null");

        Thread.sleep(50);

        // Second request with different dynamic field values
        String xml2 = generateXmlWithNamespacePrefixes();
        HttpEntity<String> request2 = new HttpEntity<>(xml2, headers);
        ResponseEntity<String> response2Entity = restTemplate.postForEntity("/api/postmanecho/xml", request2, String.class);
        String resp2 = response2Entity.getBody();
        assertNotNull(resp2, "Second response should not be null");

        assertNotEquals(xml1, xml2, "XML bodies should differ due to dynamic fields");

        // In PLAYBACK mode, verify that detected-fields.json contains correct patterns
        String mode = System.getProperty("stablemock.mode", "PLAYBACK");
        if (!"RECORD".equalsIgnoreCase(mode)) {
            File analysisFile = new File(
                    "src/test/resources/stablemock/XmlNamespacePrefixTest/testXmlWithNamespacePrefixes/detected-fields.json");
            assertTrue(analysisFile.exists(),
                    "Analysis file should exist after recording: " + analysisFile.getAbsolutePath());
            
            // Verify that the patterns do NOT contain namespace prefixes in local-name() calls
            String content = new String(Files.readAllBytes(analysisFile.toPath()));
            
            // These patterns should NOT exist (incorrect):
            assertFalse(content.contains("local-name()='SOAP-ENV:Envelope'"),
                    "XPath should not include namespace prefix in local-name() for Envelope");
            assertFalse(content.contains("local-name()='SOAP-ENV:Body'"),
                    "XPath should not include namespace prefix in local-name() for Body");
            assertFalse(content.contains("local-name()='ns4:ServiceRequest'"),
                    "XPath should not include namespace prefix in local-name() for ServiceRequest");
            
            // These patterns SHOULD exist (correct):
            assertTrue(content.contains("local-name()='Envelope'"),
                    "XPath should use local name 'Envelope' without prefix");
            assertTrue(content.contains("local-name()='Body'"),
                    "XPath should use local name 'Body' without prefix");
            assertTrue(content.contains("local-name()='ServiceRequest'"),
                    "XPath should use local name 'ServiceRequest' without prefix");
            
            // Verify attribute patterns also don't have prefixes
            assertTrue(content.contains("@*[local-name()='RequestId'") ||
                       content.contains("local-name()='RequestId'"),
                    "Attribute XPath should use local name 'RequestId' without prefix");
            assertTrue(content.contains("@*[local-name()='SessionToken'") ||
                       content.contains("local-name()='SessionToken'"),
                    "Attribute XPath should use local name 'SessionToken' without prefix");
            assertTrue(content.contains("@*[local-name()='Timestamp'") ||
                       content.contains("local-name()='Timestamp'"),
                    "Attribute XPath should use local name 'Timestamp' without prefix");
            
            // The test should pass in PLAYBACK mode because ignore patterns are correctly applied
            // If this fails with 404, it means ignore patterns are not being applied correctly
        }
    }

    /**
     * Generates XML with namespace prefixes similar to real-world SOAP services.
     * 
     * Structure:
     * - SOAP-ENV:Envelope and SOAP-ENV:Body (SOAP envelope)
     * - ns4:ServiceRequest element with namespace prefix
     * - Attributes with dynamic values: RequestId, SessionToken, Timestamp
     */
    private String generateXmlWithNamespacePrefixes() {
        // Simulate dynamic fields that change each run
        long timestamp = System.currentTimeMillis();
        String requestId = String.valueOf(timestamp - 10);
        String sessionToken = String.valueOf(timestamp);
        String timestampStr = Instant.now().toString();

        return String.format(
                "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
                        "<SOAP-ENV:Body>" +
                        "<ns4:ServiceRequest xmlns:ns4=\"http://example.com/api/v1\" " +
                        "RequestId=\"%s\" " +
                        "SessionToken=\"%s\" " +
                        "Timestamp=\"%s\">" +
                        "<ns4:DateRange>" +
                        "<ns4:Start>2026-01-22</ns4:Start>" +
                        "<ns4:End>2026-01-24</ns4:End>" +
                        "</ns4:DateRange>" +
                        "</ns4:ServiceRequest>" +
                        "</SOAP-ENV:Body>" +
                        "</SOAP-ENV:Envelope>",
                requestId, sessionToken, timestampStr);
    }
}
