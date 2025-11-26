package example;

import com.stablemock.U;
import au.com.origin.snapshots.Expect;
import au.com.origin.snapshots.junit5.SnapshotExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E Spring Boot test with Feign client that:
 * 1. @U extension starts WireMock and sets stablemock.baseUrl in ThreadLocal in beforeEach()
 * 2. @DynamicPropertySource supplier reads from ThreadLocal lazily (canonical solution for parallel execution)
 * 3. ThirdPartyService.getBaseUri() reads from Spring Environment
 * 4. Feign client methods accept URI parameter to override base URL dynamically
 * 5. Test calls controller -> service -> Feign client -> WireMock
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@ExtendWith(SnapshotExtension.class)
@U(urls = { "https://jsonplaceholder.typicode.com" })
public class SpringBootIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;
    
    /**
     * Dynamic property source that reads from ThreadLocal.
     * The supplier is evaluated lazily when Spring needs the value (after beforeEach() runs),
     * so it can read the ThreadLocal value set by StableMock.
     * This is the canonical solution for parallel test execution in Spring Boot.
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Map stablemock.baseUrl to app.thirdparty.url so the service uses WireMock
        // The supplier is evaluated lazily when Spring needs the value (after beforeEach() runs)
        registry.add("app.thirdparty.url", () -> {
            // Try ThreadLocal first (canonical solution for parallel execution)
            String baseUrl = getThreadLocalBaseUrl();
            if (baseUrl == null || baseUrl.isEmpty()) {
                // Fallback to system property (set by StableMockExtension in beforeEach)
                baseUrl = System.getProperty("stablemock.baseUrl");
            }
            String finalUrl = baseUrl != null && !baseUrl.isEmpty() ? baseUrl : "https://jsonplaceholder.typicode.com";
            System.out.println("SpringBootIntegrationTest: app.thirdparty.url=" + finalUrl);
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
    public void testGetUserViaController(Expect expect) {
        String response = restTemplate.getForObject("/api/users/1", String.class);

        assertNotNull(response);
        // Use snapshot testing to verify the response structure
        // Using default serializer - JSON serializer requires Jackson plugin with correct package
        expect.toMatchSnapshot(response);
    }

    @Test
    public void testCreatePostViaController(Expect expect) {
        String response = restTemplate.postForObject(
                "/api/posts?title=Test Title&body=Test Body&userId=1",
                null,
                String.class);

        assertNotNull(response, "Response should not be null");
        // Use snapshot testing to verify the response structure
        // Using default serializer - JSON serializer requires Jackson plugin with correct package
        expect.toMatchSnapshot(response);
    }
}
