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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Like {@link MultipleAnnotationTest} but using XML POST requests.
 *
 * This test uses a single fictitious flow that calls two different third-party hosts:
 * - jsonplaceholder (JSON GET)
 * - postman-echo (XML POST)
 *
 * Expected behavior:
 * - RECORD: both XML calls are recorded, and mappings are saved separately per annotation index.
 * - PLAYBACK: both calls match the recorded stubs.
 */
@U(urls = { "https://jsonplaceholder.typicode.com", "https://postman-echo.com" })
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class MultipleAnnotationXmlTest extends BaseStableMockTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void registerMockUrls(DynamicPropertyRegistry registry) {
        registerPropertyWithFallbackByIndex(registry, "app.thirdparty.url", "MultipleAnnotationXmlTest", 0,
                "https://jsonplaceholder.typicode.com");
        registerPropertyWithFallbackByIndex(registry, "app.postmanecho.url", "MultipleAnnotationXmlTest", 1,
                "https://postman-echo.com");
    }

    @Test
    void testMultipleAnnotationsWorkWithXml() {
        String xml = "<request><id>123</id><message>Hello</message></request>";

        // First API (jsonplaceholder) - JSON
        String jsonResponse = restTemplate.getForObject("/api/users/1", String.class);
        assertNotNull(jsonResponse, "Response from jsonplaceholder should not be null");
        assertTrue(jsonResponse.contains("\"id\": 1") || jsonResponse.contains("\"id\":1"),
                "Response should contain user id 1");

        // Second API (postman-echo) - XML
        ResponseEntity<String> postmanResp = postXml("/api/postmanecho/xml", xml);
        assertResponseOkOrFailWithInstructions(postmanResp);

        assertNotNull(postmanResp.getBody(), "Postman Echo response should not be null");

        // Both services return JSON bodies; just sanity-check typical markers
        assertTrue(postmanResp.getBody().contains("data") || postmanResp.getBody().contains("postman") || postmanResp.getBody().contains("headers"),
                "Postman Echo response should look like an echo JSON. Got: " + preview(postmanResp.getBody()));

        // In PLAYBACK, assert recorded directories exist from prior record runs.
        String mode = System.getProperty("stablemock.mode", "PLAYBACK");
        if (!"RECORD".equalsIgnoreCase(mode)) {
            File ann0Mappings = new File("src/test/resources/stablemock/MultipleAnnotationXmlTest/testMultipleAnnotationsWorkWithXml/annotation_0/mappings");
            File ann1Mappings = new File("src/test/resources/stablemock/MultipleAnnotationXmlTest/testMultipleAnnotationsWorkWithXml/annotation_1/mappings");
            assertTrue(ann0Mappings.exists(), "annotation_0 mappings should exist: " + ann0Mappings.getPath());
            assertTrue(ann1Mappings.exists(), "annotation_1 mappings should exist: " + ann1Mappings.getPath());
        }
    }

    private ResponseEntity<String> postXml(String path, String xml) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        HttpEntity<String> entity = new HttpEntity<>(xml, headers);
        return restTemplate.exchange(path, HttpMethod.POST, entity, String.class);
    }

    private void assertResponseOkOrFailWithInstructions(ResponseEntity<String> response) {
        int status = response.getStatusCodeValue();
        String mode = System.getProperty("stablemock.mode", "PLAYBACK");
        if ("RECORD".equalsIgnoreCase(mode)) {
            assertEquals(200, status, "Request should succeed in RECORD mode. Status: " + status);
        } else {
            if (status != 200) {
                fail(String.format(
                        "Request failed in PLAYBACK mode! Status: %d, Body: %s%n" +
                                "STEP 1: Run record twice: ./gradlew stableMockRecord%n" +
                                "STEP 2: Then run playback: ./gradlew stableMockPlayback%n",
                        status, preview(response.getBody())));
            }
        }
    }

    private static String preview(String s) {
        if (s == null) return "null";
        return s.substring(0, Math.min(300, s.length()));
    }
}

