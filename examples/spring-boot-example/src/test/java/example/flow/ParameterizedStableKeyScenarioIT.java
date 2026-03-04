package example.flow;

import com.stablemock.U;
import example.inheritance.BaseTestFeature;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parameterized Spring Boot test with stable key (useStableKey=true), same URL for both
 * invocations. Verifies scenario state is set before each invocation so each gets the
 * correct stub. Fails at playback if extension does not set scenario state in beforeEach
 * for methodName_8hex identifiers (regression test for parameterized playback 404 fix).
 */
@U(
    urls = { "https://postman-echo.com" },
    properties = { "app.postmanecho.url" },
    ignore = { "xml://*[local-name()='Scenario']" }
)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class ParameterizedStableKeyScenarioIT extends BaseTestFeature {

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        autoRegisterProperties(registry, ParameterizedStableKeyScenarioIT.class);
    }

    static Stream<Arguments> scenarios() {
        return Stream.of(
            Arguments.of("Loyalty"),
            Arguments.of("PlatinumNoLoyalty")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    @Execution(ExecutionMode.SAME_THREAD)
    void fullFlowSameUrlStableKey(String scenarioName) {
        String xml = "<root><Scenario>" + scenarioName + "</Scenario></root>";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        ResponseEntity<String> response = restTemplate.postForEntity(
            "/api/postmanecho/xml",
            new HttpEntity<>(xml, headers),
            String.class
        );
        assertNotNull(response, "Response null for " + scenarioName);
        assertEquals(200, response.getStatusCode().value(),
            "Wrong stub or 404 for " + scenarioName + " (scenario state must be set for stable-key); body: " + (response.getBody() != null ? response.getBody() : "null"));
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains(scenarioName),
            "Response must be for " + scenarioName + " (wrong stub); body: " + response.getBody());
    }
}
