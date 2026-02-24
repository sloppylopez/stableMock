package example.flow;

import com.stablemock.U;
import com.stablemock.WireMockContext;
import example.inheritance.BaseTestFeature;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates StableMock Option A (per-invocation WireMock server) and detects the
 * Spring Boot base-URL caching problem: if the app resolves ${app.postmanecho.url}
 * once at context startup, all parameterized invocations would hit the same port
 * and Option A would be silently broken. This test fails when only one port is used.
 */
@U(
    urls = { "https://postman-echo.com" },
    properties = { "app.postmanecho.url" }
)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Execution(ExecutionMode.CONCURRENT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ParallelParameterizedIsolationIT extends BaseTestFeature {

    private static final Logger logger = LoggerFactory.getLogger(ParallelParameterizedIsolationIT.class);

    private static final Set<Integer> observedPorts = ConcurrentHashMap.newKeySet();

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        autoRegisterProperties(registry, ParallelParameterizedIsolationIT.class);
    }

    @Order(1)
    @ParameterizedTest(name = "invocation {0}")
    @ValueSource(ints = { 1, 2, 3, 4, 5, 6 })
    void verifyEachInvocationUsesDistinctPort(int id) {
        Integer port = WireMockContext.getThreadLocalPort();
        assertNotNull(port, "WireMockContext port must be set for this invocation");
        observedPorts.add(port);

        int invocationIndex = id - 1;
        logger.info("[StableMock-Test] invocation={} port={}", invocationIndex, port);

        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/postmanecho/users/" + id,
                String.class);

        assertNotNull(response, "Response must not be null");
        assertTrue(response.getStatusCode().is2xxSuccessful(),
                "HTTP call must succeed; body: " + (response.getBody() != null ? response.getBody() : "null"));
        assertNotNull(response.getBody());
    }

    @AfterAll
    static void verifyPerInvocationIsolation() {
        String mode = System.getProperty("stablemock.mode", "PLAYBACK");
        if (!"PLAYBACK".equalsIgnoreCase(mode)) {
            return;
        }
        assertTrue(
                observedPorts.size() > 1,
                "Only one WireMock port was used. Spring likely cached the base URL and Option A is broken."
        );
    }
}
