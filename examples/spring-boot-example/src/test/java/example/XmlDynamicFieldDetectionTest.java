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
 * Spring Boot test for XML dynamic field detection feature.
 *
 * Expected behavior:
 * - RECORD mode: detects changing XML fields and saves ignore patterns to detected-fields.json
 * - PLAYBACK mode: auto-applies detected ignore patterns by injecting ${xmlunit.ignore} placeholders
 *   into the recorded request-body matchers so future requests with different dynamic values still match.
 */
@U(urls = { "https://postman-echo.com" },
   properties = { "app.postmanecho.url" })
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class XmlDynamicFieldDetectionTest extends BaseStableMockTest {

    @Autowired
    private ThirdPartyService thirdPartyService;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        autoRegisterProperties(registry, XmlDynamicFieldDetectionTest.class);
        // app.thirdparty.url is not mocked, so it uses the value from application.properties
    }

    @Test
    void testDetectChangingFieldsInXml() throws Exception {
        // Calls ThirdPartyService.postXmlToPostmanEcho() -> PostmanEchoClient.postXml() -> postman-echo.com/post
        String xml1 = generateXmlWithDynamicFields();
        String resp1 = thirdPartyService.postXmlToPostmanEcho(xml1);
        assertNotNull(resp1, "First response should not be null");

        Thread.sleep(50);

        String xml2 = generateXmlWithDynamicFields();
        String resp2 = thirdPartyService.postXmlToPostmanEcho(xml2);
        assertNotNull(resp2, "Second response should not be null");

        assertNotEquals(xml1, xml2, "XML bodies should differ due to dynamic fields");

        String mode = System.getProperty("stablemock.mode", "PLAYBACK");
        if (!"RECORD".equalsIgnoreCase(mode)) {
            File analysisFile = new File(
                    "src/test/resources/stablemock/XmlDynamicFieldDetectionTest/testDetectChangingFieldsInXml/detected-fields.json");
            assertTrue(analysisFile.exists(),
                    "Analysis file should exist after recording: " + analysisFile.getAbsolutePath());
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

