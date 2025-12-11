package example;

import com.stablemock.U;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.File;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spring Boot test for XML dynamic field detection feature.
 *
 * Expected behavior:
 * - RECORD mode: detects changing XML fields and saves ignore patterns to detected-fields.json
 * - PLAYBACK mode: auto-applies detected ignore patterns by injecting ${xmlunit.ignore} placeholders
 *   into the recorded request-body matchers so future requests with different dynamic values still match.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@U(urls = { "https://postman-echo.com" })
class XmlDynamicFieldDetectionTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("app.thirdparty.url", () -> {
            String baseUrl = getThreadLocalBaseUrl();
            if (baseUrl == null || baseUrl.isEmpty()) {
                baseUrl = System.getProperty("stablemock.baseUrl");
            }
            return baseUrl != null && !baseUrl.isEmpty()
                    ? baseUrl
                    : "https://jsonplaceholder.typicode.com";
        });

        registry.add("app.postmanecho.url", () -> {
            String baseUrl = getThreadLocalBaseUrl();
            if (baseUrl == null || baseUrl.isEmpty()) {
                baseUrl = System.getProperty("stablemock.baseUrl");
            }
            return baseUrl != null && !baseUrl.isEmpty()
                    ? baseUrl
                    : "https://postman-echo.com";
        });
    }

    private static String getThreadLocalBaseUrl() {
        try {
            Class<?> wireMockContextClass = Class.forName("com.stablemock.WireMockContext");
            java.lang.reflect.Method method = wireMockContextClass.getMethod("getThreadLocalBaseUrl");
            return (String) method.invoke(null);
        } catch (Exception e) {
            return null;
        }
    }

    @Test
    void testDetectChangingFieldsInXml() throws Exception {
        String xml1 = generateXmlWithDynamicFields();
        ResponseEntity<String> resp1 = postXml(xml1);
        assertResponseOkOrFailWithInstructions(resp1);

        Thread.sleep(50);

        String xml2 = generateXmlWithDynamicFields();
        ResponseEntity<String> resp2 = postXml(xml2);
        assertResponseOkOrFailWithInstructions(resp2);

        assertNotEquals(xml1, xml2, "XML bodies should differ due to dynamic fields");

        String mode = System.getProperty("stablemock.mode", "PLAYBACK");
        if (!"RECORD".equalsIgnoreCase(mode)) {
            File analysisFile = new File(
                    "src/test/resources/stablemock/XmlDynamicFieldDetectionTest/testDetectChangingFieldsInXml/detected-fields.json");
            assertTrue(analysisFile.exists(),
                    "Analysis file should exist after recording: " + analysisFile.getAbsolutePath());
        }
    }

    private ResponseEntity<String> postXml(String xml) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        HttpEntity<String> entity = new HttpEntity<>(xml, headers);
        return restTemplate.exchange("/api/postmanecho/xml", HttpMethod.POST, entity, String.class);
    }

    private void assertResponseOkOrFailWithInstructions(ResponseEntity<String> response) {
        int status = response.getStatusCodeValue();
        String mode = System.getProperty("stablemock.mode", "PLAYBACK");
        if ("RECORD".equalsIgnoreCase(mode)) {
            assertEquals(200, status, "Request should succeed in RECORD mode. Status: " + status);
            assertNotNull(response.getBody(), "Response body should not be null");
        } else {
            if (status != 200) {
                String bodyPreview = response.getBody() != null
                        ? response.getBody().substring(0, Math.min(300, response.getBody().length()))
                        : "null";
                fail(String.format(
                        "Request failed in PLAYBACK mode! Status: %d, Body: %s%n" +
                                "STEP 1: Run record twice: ./gradlew stableMockRecord%n" +
                                "STEP 2: Then run playback: ./gradlew stableMockPlayback%n",
                        status, bodyPreview));
            }
        }
    }

    private String generateXmlWithDynamicFields() {
        String timestamp = Instant.now().toString();
        String requestId = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();

        return String.format(
                "<request>" +
                        "<header id=\"%s\" version=\"%s\">" +
                        "<timestamp>%s</timestamp>" +
                        "<requestId>%s</requestId>" +
                        "</header>" +
                        "<data>" +
                        "<user sessionId=\"%s\">" +
                        "<name>Test User</name>" +
                        "<email>test@example.com</email>" +
                        "</user>" +
                        "</data>" +
                        "</request>",
                sessionId, "1." + System.currentTimeMillis(), timestamp, requestId, sessionId);
    }
}

