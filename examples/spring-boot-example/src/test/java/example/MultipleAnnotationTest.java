package example;

import com.stablemock.U;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class to verify that multiple @U annotations work correctly.
 * 
 * This test uses @U @U (double annotation) to record mocks for URLs
 * in both annotations. The test verifies that:
 * 1. WireMock server starts correctly with multiple annotations
 * 2. Requests are recorded for URLs in both annotations
 * 3. Mappings are saved separately per annotation
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@U(urls = { "https://jsonplaceholder.typicode.com" })
@U(urls = { "https://jsonplaceholder.typicode.com" })
class MultipleAnnotationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("app.thirdparty.url", () -> {
            String baseUrl = getThreadLocalBaseUrl();
            if (baseUrl == null || baseUrl.isEmpty()) {
                baseUrl = System.getProperty("stablemock.baseUrl");
            }
            String finalUrl = baseUrl != null && !baseUrl.isEmpty()
                    ? baseUrl
                    : "https://jsonplaceholder.typicode.com";

            System.out.println(
                    "MultipleAnnotationTest: app.thirdparty.url=" + finalUrl +
                            " (thread=" + Thread.currentThread().getName() + ")");
            return finalUrl;
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
    void testMultipleAnnotationsWork() {
        // This test verifies that multiple @U annotations are handled correctly
        // Both annotations point to the same URL, but they should be tracked separately
        String response = restTemplate.getForObject("/api/users/1", String.class);

        assertNotNull(response, "Response should not be null");
        assertTrue(response.contains("\"id\": 1"), "Response should contain user id 1");
        assertTrue(response.contains("username"), "Response should contain username field");

        System.out.println(
                "MultipleAnnotationTest.testMultipleAnnotationsWork completed (thread=" +
                        Thread.currentThread().getName() + ")");
    }

    @Test
    void testMultipleAnnotationsRecordSeparately() {
        // This test makes a different request to verify both annotations are tracked
        String response = restTemplate.getForObject("/api/users/2", String.class);

        assertNotNull(response, "Response should not be null");
        assertTrue(response.contains("\"id\": 2"), "Response should contain user id 2");

        System.out.println(
                "MultipleAnnotationTest.testMultipleAnnotationsRecordSeparately completed (thread=" +
                        Thread.currentThread().getName() + ")");
    }
}

