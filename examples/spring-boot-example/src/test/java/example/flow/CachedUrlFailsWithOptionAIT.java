package example.flow;

import com.stablemock.U;
import example.CachedBaseUrlClient;
import example.inheritance.BaseTestFeature;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Playback with a client that caches the base URL at context init (CachedBaseUrlClient).
 * Verifies that parameterized playback works: the app gets the class-level WireMock URL
 * via BaseStableMockTest's fallback (stablemock.baseUrl.<ClassName>) when ThreadLocal
 * is null in the context-creation thread, so all invocations hit the class-level server
 * with merged mappings.
 */
@U(
    urls = { "https://postman-echo.com" },
    properties = { "app.postmanecho.url" }
)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class CachedUrlFailsWithOptionAIT extends BaseTestFeature {

    @Autowired
    private CachedBaseUrlClient cachedBaseUrlClient;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        autoRegisterProperties(registry, CachedUrlFailsWithOptionAIT.class);
    }

    @ParameterizedTest(name = "cached client invocation {0}")
    @ValueSource(ints = { 1, 2, 3 })
    void cachedUrlHitsSamePort(int id) {
        String responseBody = cachedBaseUrlClient.get(id);

        assertNotNull(responseBody, "Response body should not be null");
        assertTrue(responseBody.contains("\"id\":\"" + id + "\""),
                "Expected playback response for id=" + id + ", got body: " + responseBody);
    }
}
