package example.flow;

import com.stablemock.U;
import example.inheritance.BaseTestFeature;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies Option A playback with a non-Feign client that resolves the target URL
 * at request time instead of caching it at context initialization.
 */
@U(
    urls = { "https://postman-echo.com" },
    properties = { "app.postmanecho.url" }
)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class CachedUrlFailsWithOptionAIT extends BaseTestFeature {

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        autoRegisterProperties(registry, CachedUrlFailsWithOptionAIT.class);
    }

    @ParameterizedTest(name = "cached client invocation {0}")
    @ValueSource(ints = { 1, 2, 3 })
    void cachedUrlHitsSamePort(int id) {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/postmanecho/cached-users/" + id,
                String.class);

        assertNotNull(response, "Response should not be null");
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody(), "Response body should not be null");
        assertTrue(response.getBody().contains("\"id\":\"" + id + "\""),
                "Expected playback response for id=" + id + ", got body: " + response.getBody());
    }
}
