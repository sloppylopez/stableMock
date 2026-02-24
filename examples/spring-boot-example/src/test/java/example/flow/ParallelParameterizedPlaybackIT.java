package example.flow;

import com.stablemock.U;
import com.stablemock.WireMockContext;
import example.inheritance.BaseTestFeature;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies Option A (per-invocation WireMock server) for parameterized playback.
 * Runs 8 parameterized invocations in parallel; each must use its own WireMock port.
 * If Spring caches the base URL at context startup, all invocations would hit the same
 * port and the unique-port assertion would fail.
 *
 * Requires Option A enabled: -Dstablemock.parameterized.playback.reload=true (default).
 * Run stableMockRecord first to generate invocation dirs parallelParameterizedPlayback__i0 .. __i7.
 */
@U(
    urls = { "https://postman-echo.com" },
    properties = { "app.postmanecho.url" }
)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Execution(ExecutionMode.CONCURRENT)
public class ParallelParameterizedPlaybackIT extends BaseTestFeature {

    private static final Logger logger = LoggerFactory.getLogger(ParallelParameterizedPlaybackIT.class);

    private static final Set<Integer> observedPorts = ConcurrentHashMap.newKeySet();

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        autoRegisterProperties(registry, ParallelParameterizedPlaybackIT.class);
    }

    @ParameterizedTest(name = "invocation {0}")
    @ValueSource(ints = { 1, 2, 3, 4, 5, 6, 7, 8 })
    void parallelParameterizedPlayback(int invocationParam) {
        Integer currentPort = WireMockContext.getThreadLocalPort();
        assertNotNull(currentPort, "WireMockContext port must be set for this invocation");
        observedPorts.add(currentPort);

        String testMethodIdentifier = "parallelParameterizedPlayback__i" + (invocationParam - 1);
        logger.info("StableMock Option A check: testMethodIdentifier={}, invocationParam={}, WireMock port={}",
                testMethodIdentifier, invocationParam, currentPort);

        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/postmanecho/users/" + invocationParam,
                String.class);

        assertNotNull(response, "Response must not be null");
        assertEquals(200, response.getStatusCode().value(),
                "HTTP call must succeed (no 404 from WireMock); body: " + (response.getBody() != null ? response.getBody() : "null"));
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("args") || response.getBody().contains("url"),
                "Expected postman-echo style payload; body: " + response.getBody());
    }

    @AfterAll
    static void assertPerInvocationIsolation() {
        String mode = System.getProperty("stablemock.mode", "PLAYBACK");
        if (!"PLAYBACK".equalsIgnoreCase(mode)) {
            return;
        }
        assertEquals(8, observedPorts.size(),
                "Each of 8 parameterized invocations must have used a distinct WireMock port (Option A). " +
                "observedPorts=" + observedPorts + ". If size is 1, Spring likely cached the base URL.");
    }
}
