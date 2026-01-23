package example;

import com.stablemock.spring.BaseStableMockTest;

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
@U(urls = { "https://jsonplaceholder.typicode.com", "https://postman-echo.com" },
   properties = { "app.thirdparty.url", "app.postmanecho.url" })
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class MultipleAnnotationXmlTest extends BaseStableMockTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void registerMockUrls(DynamicPropertyRegistry registry) {
        autoRegisterProperties(registry, MultipleAnnotationXmlTest.class);
    }

    @Test
    void testMultipleAnnotationsWorkWithXml() {
        String xml = "<request><id>123</id><message>Hello</message></request>";

        // First API (jsonplaceholder) - JSON
        // Flow: Test -> Controller -> ThirdPartyService -> JsonPlaceholderClient -> jsonplaceholder.typicode.com/users/1
        ResponseEntity<String> jsonResponseEntity = restTemplate.getForEntity("/api/users/1", String.class);
        String jsonResponse = jsonResponseEntity.getBody();
        assertNotNull(jsonResponse, "Response from jsonplaceholder should not be null");
        assertTrue(jsonResponse.contains("\"id\": 1") || jsonResponse.contains("\"id\":1"),
                "Response should contain user id 1");

        // Second API (postman-echo) - XML
        // Flow: Test -> Controller -> ThirdPartyService -> PostmanEchoClient -> postman-echo.com/post
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        HttpEntity<String> request = new HttpEntity<>(xml, headers);
        ResponseEntity<String> postmanResponseEntity = restTemplate.postForEntity("/api/postmanecho/xml", request, String.class);
        String postmanResp = postmanResponseEntity.getBody();
        assertNotNull(postmanResp, "Postman Echo response should not be null");

        // Both services return JSON bodies; just sanity-check typical markers
        assertTrue(postmanResp.contains("data") || postmanResp.contains("postman") || postmanResp.contains("headers"),
                "Postman Echo response should look like an echo JSON. Got: " + preview(postmanResp));

        // In PLAYBACK, assert recorded directories exist from prior record runs.
        String mode = System.getProperty("stablemock.mode", "PLAYBACK");
        if (!"RECORD".equalsIgnoreCase(mode)) {
            File ann0Mappings = new File("src/test/resources/stablemock/MultipleAnnotationXmlTest/testMultipleAnnotationsWorkWithXml/annotation_0/mappings");
            File ann1Mappings = new File("src/test/resources/stablemock/MultipleAnnotationXmlTest/testMultipleAnnotationsWorkWithXml/annotation_1/mappings");
            assertTrue(ann0Mappings.exists(), "annotation_0 mappings should exist: " + ann0Mappings.getPath());
            assertTrue(ann1Mappings.exists(), "annotation_1 mappings should exist: " + ann1Mappings.getPath());
        }
    }

    private static String preview(String s) {
        if (s == null) return "null";
        return s.substring(0, Math.min(300, s.length()));
    }
}


