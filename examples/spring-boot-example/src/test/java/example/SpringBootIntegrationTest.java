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
 * E2E Spring Boot test with Feign client that:
 * 1. @U extension starts WireMock and sets stablemock.baseUrl in ThreadLocal in
 * beforeAll/beforeEach()
 * 2. @DynamicPropertySource supplier reads from ThreadLocal lazily (canonical
 * solution for parallel execution)
 * 3. ThirdPartyService reads app.thirdparty.url from Spring Environment
 * 4. JsonPlaceholderClient ends up calling WireMock instead of the real
 * jsonplaceholder host
 * 5. Tests hit controller -> service -> Feign client -> WireMock
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@U(urls = { "https://jsonplaceholder.typicode.com" })
class SpringBootIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * Dynamic property source that reads from WireMockContext ThreadLocal.
     * The supplier is evaluated lazily when Spring needs the value (after
     * beforeAll/beforeEach runs),
     * so it can read the ThreadLocal value set by StableMock.
     * This is the canonical solution for parallel test execution in Spring Boot.
     */
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
            return baseUrl != null && !baseUrl.isEmpty()
                    ? baseUrl
                    : "https://jsonplaceholder.typicode.com";
        });
        
        // app.postmanecho.url is required for PostmanEchoClient (no default value)
        registry.add("app.postmanecho.url", () -> "https://postman-echo.com");
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
    void testGetUserViaController() {
        String response = restTemplate.getForObject("/api/users/1", String.class);

        assertNotNull(response);
        assertTrue(response.contains("\"id\": 1"), "Response should contain user id 1");
    }

    @Test
    void testCreatePostViaController() {
        String response = restTemplate.postForObject(
                "/api/posts?title=Test Title&body=Test Body&userId=1",
                null,
                String.class);

        assertNotNull(response, "Response should not be null");
        assertTrue(response.contains("\"id\": 101"), "Response should contain new post id 101");
    }

    @Test
    void testGetUser2ViaController() {
        // This test uses a different user ID to ensure it's a distinct request
        String response = restTemplate.getForObject("/api/users/2", String.class);

        assertNotNull(response);
        assertTrue(response.contains("\"id\": 2"), "Response should contain user id 2");
    }

    @Test
    void testGetUser3ViaController() {
        // Another parallel test with different user ID
        String response = restTemplate.getForObject("/api/users/3", String.class);

        assertNotNull(response);
        assertTrue(response.contains("\"id\": 3"), "Response should contain user id 3");
    }
}
