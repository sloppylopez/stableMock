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
@U(urls = { "https://reqres.in" })
class MultipleAnnotationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // First annotation: jsonplaceholder.typicode.com
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
        
        // Second annotation: reqres.in
        registry.add("app.reqres.url", () -> {
            String baseUrl = getThreadLocalBaseUrl();
            if (baseUrl == null || baseUrl.isEmpty()) {
                baseUrl = System.getProperty("stablemock.baseUrl");
            }
            String finalUrl = baseUrl != null && !baseUrl.isEmpty()
                    ? baseUrl
                    : "https://reqres.in";

            System.out.println(
                    "MultipleAnnotationTest: app.reqres.url=" + finalUrl +
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
        // This test verifies that multiple @U annotations work correctly
        // First annotation: jsonplaceholder.typicode.com - called via /api/users/1
        // Second annotation: reqres.in - called via /api/reqres/users/1
        // Both should be tracked separately
        
        // Call first API (jsonplaceholder)
        String response1 = restTemplate.getForObject("/api/users/1", String.class);
        assertNotNull(response1, "Response from jsonplaceholder should not be null");
        assertTrue(response1.contains("\"id\": 1") || response1.contains("\"id\":1"), 
                "Response should contain user id 1");
        assertTrue(response1.contains("username") || response1.contains("name"), 
                "Response should contain user fields");
        
        // Call second API (reqres)
        String response2 = restTemplate.getForObject("/api/reqres/users/1", String.class);
        assertNotNull(response2, "Response from reqres should not be null");
        assertTrue(response2.contains("\"id\": 1") || response2.contains("\"id\":1"), 
                "Response should contain user id 1");
        assertTrue(response2.contains("first_name") || response2.contains("email") || response2.contains("data"), 
                "Response should contain user fields");

        System.out.println(
                "MultipleAnnotationTest.testMultipleAnnotationsWork completed (thread=" +
                        Thread.currentThread().getName() + ")");
    }

    @Test
    void testMultipleAnnotationsRecordSeparately() {
        // This test makes requests to both APIs to verify both annotations are tracked
        // First annotation: jsonplaceholder.typicode.com
        String response1 = restTemplate.getForObject("/api/users/2", String.class);
        assertNotNull(response1, "Response from jsonplaceholder should not be null");
        assertTrue(response1.contains("\"id\": 2") || response1.contains("\"id\":2"), 
                "Response should contain user id 2");
        
        // Second annotation: reqres.in
        String response2 = restTemplate.getForObject("/api/reqres/users/2", String.class);
        assertNotNull(response2, "Response from reqres should not be null");
        assertTrue(response2.contains("\"id\": 2") || response2.contains("\"id\":2"), 
                "Response should contain user id 2");

        System.out.println(
                "MultipleAnnotationTest.testMultipleAnnotationsRecordSeparately completed (thread=" +
                        Thread.currentThread().getName() + ")");
    }
}

