package example;

import com.stablemock.spring.BaseStableMockTest;

import com.stablemock.U;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
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
 * 5. Tests call controller -> service -> Feign client -> WireMock (third-party API calls)
 */
@U(urls = { "https://jsonplaceholder.typicode.com" },
   properties = { "app.thirdparty.url" })
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class SpringBootIntegrationTest extends BaseStableMockTest {

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
        autoRegisterProperties(registry, SpringBootIntegrationTest.class);
        // app.postmanecho.url is not mocked, so it uses the value from application.properties
    }

    @Test
    void testGetUserViaController() {
        // Flow: Test -> Controller -> ThirdPartyService -> JsonPlaceholderClient -> jsonplaceholder.typicode.com/users/1
        ResponseEntity<String> response = restTemplate.getForEntity("/api/users/1", String.class);

        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("\"id\": 1"), "Response should contain user id 1");
    }

    @Test
    void testCreatePostViaController() {
        // Flow: Test -> Controller -> ThirdPartyService -> JsonPlaceholderClient -> jsonplaceholder.typicode.com/posts
        String url = "/api/posts?title=Test Title&body=Test Body&userId=1";
        ResponseEntity<String> response = restTemplate.postForEntity(url, null, String.class);

        assertNotNull(response.getBody(), "Response should not be null");
        assertTrue(response.getBody().contains("\"id\": 101"), "Response should contain new post id 101");
    }

    @Test
    void testGetUser2ViaController() {
        // This test uses a different user ID to ensure it's a distinct request
        // Flow: Test -> Controller -> ThirdPartyService -> JsonPlaceholderClient -> jsonplaceholder.typicode.com/users/2
        ResponseEntity<String> response = restTemplate.getForEntity("/api/users/2", String.class);

        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("\"id\": 2"), "Response should contain user id 2");
    }

    @Test
    void testGetUser3ViaController() {
        // Another parallel test with different user ID
        // Flow: Test -> Controller -> ThirdPartyService -> JsonPlaceholderClient -> jsonplaceholder.typicode.com/users/3
        ResponseEntity<String> response = restTemplate.getForEntity("/api/users/3", String.class);

        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("\"id\": 3"), "Response should contain user id 3");
    }
}

