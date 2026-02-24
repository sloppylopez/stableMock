package example.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stablemock.U;
import example.inheritance.BaseTestFeature;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Demonstrates the same failure mode as nh-api-bp FullFlowFlexibleIT: the app
 * uses a client that resolved the base URL at context init (CachedBaseUrlClient),
 * so with Option A all parameterized invocations hit the same WireMock port.
 * We assert that more than one port was used (per-invocation); with the cached
 * client we get only one port, so this test fails in playback.
 */
@U(
    urls = { "https://postman-echo.com" },
    properties = { "app.postmanecho.url" }
)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class CachedUrlFailsWithOptionAIT extends BaseTestFeature {

    private static final Set<Integer> observedPorts = ConcurrentHashMap.newKeySet();
    private static final ObjectMapper objectMapper = new ObjectMapper();

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
        Assumptions.assumeTrue(response.getStatusCode().is2xxSuccessful() && response.getBody() != null,
                "Need successful response with body to inspect url");
        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            if (!root.has("url")) {
                return;
            }
            String urlStr = root.get("url").asText();
            URI uri = URI.create(urlStr);
            if ("localhost".equals(uri.getHost()) && uri.getPort() > 0) {
                observedPorts.add(uri.getPort());
            }
        } catch (Exception ignored) {
            // skip if we can't parse
        }
    }

    @AfterAll
    static void assertMoreThanOnePortUsed() {
        String mode = System.getProperty("stablemock.mode", "PLAYBACK");
        if (!"PLAYBACK".equalsIgnoreCase(mode)) {
            return;
        }
        // With cached base URL the app hits one WireMock port; we'd see at most one port.
        // Recorded response body has "url": "https://postman-echo.com/..." so we often see 0 ports;
        // either way we require > 1 to pass (Option A per-invocation ports).
        Assertions.assertTrue(
                observedPorts.size() > 1,
                "Expected more than one WireMock port (Option A). With cached base URL only one port is used; observedPorts=" + observedPorts);
    }
}
