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
 * Parallel test class to verify that multiple test classes can run in parallel
 * with StableMock, each getting their own WireMock instance.
 *
 * This test should run in parallel with SpringBootIntegrationTest without
 * conflicts.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@U(urls = { "https://jsonplaceholder.typicode.com" })
class ParallelSpringBootTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Map stablemock.baseUrl to app.thirdparty.url so the service uses WireMock
        registry.add("app.thirdparty.url", () -> {
            // ALWAYS read from ThreadLocal first (set by StableMockExtension in
            // beforeAll/beforeEach)
            // System property is global and gets overwritten in parallel execution
            String baseUrl = getThreadLocalBaseUrl();
            if (baseUrl == null || baseUrl.isEmpty()) {
                // Fallback to system property only if ThreadLocal not set
                baseUrl = System.getProperty("stablemock.baseUrl");
            }
            String finalUrl = baseUrl != null && !baseUrl.isEmpty()
                    ? baseUrl
                    : "https://jsonplaceholder.typicode.com";

            System.out.println(
                    "ParallelSpringBootTest: app.thirdparty.url=" + finalUrl +
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
    void testGetUser10ViaController() {
        // This test uses user ID 10 to ensure it's distinct from other tests
        // When run in parallel, each test should get its own WireMock instance on a
        // different port
        String response = restTemplate.getForObject("/api/users/10", String.class);

        assertNotNull(response, "Response should not be null");
        // JsonPlaceholder returns pretty-printed JSON with spaces
        assertTrue(response.contains("\"id\": 10"), "Response should contain user id 10");
        assertTrue(response.contains("username"), "Response should contain username field");

        System.out.println(
                "ParallelSpringBootTest.testGetUser10ViaController completed (thread=" +
                        Thread.currentThread().getName() + ")");
    }

    @Test
    void testGetUser1ViaController() {
        // Use user ID 1 instead of 11 (which doesn't exist)
        String response = restTemplate.getForObject("/api/users/1", String.class);

        assertNotNull(response, "Response should not be null");
        assertTrue(response.contains("\"id\": 1"), "Response should contain user id 1");

        System.out.println(
                "ParallelSpringBootTest.testGetUser1ViaController completed (thread=" +
                        Thread.currentThread().getName() + ")");
    }
}
