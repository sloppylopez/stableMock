package example.flow;

import com.stablemock.U;
import com.stablemock.WireMockContext;
import com.stablemock.core.config.StableMockConfig;
import example.inheritance.BaseTestFeature;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dual {@code @U} mirroring bp-api: first annotation = one upstream + several path-style Spring
 * properties; second = another upstream + Feign-style base property. Regression for indexed
 * {@link WireMockContext} / {@code registerPropertyWithFallbackByIndex} during playback.
 */
@U(
    urls = { "https://postman-echo.com" },
    properties = {
            "dualu.svc.echoA=/get?id=701",
            "dualu.svc.echoB=/get?id=702"
    }
)
@U(
    urls = { "https://jsonplaceholder.typicode.com" },
    properties = { "app.thirdparty.url" }
)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class DualUMultiPropertyPlaybackIT extends BaseTestFeature {

    @Autowired
    private Environment environment;

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        autoRegisterProperties(registry, DualUMultiPropertyPlaybackIT.class);
    }

    @Test
    void playbackIndexedWireMockBasesAreDistinctAndHttpSucceeds() {
        assertNotNull(WireMockContext.getThreadLocalBaseUrl(0), "index 0 WireMock base");
        assertNotNull(WireMockContext.getThreadLocalBaseUrl(1), "index 1 WireMock base");
        assertNotEquals(WireMockContext.getThreadLocalBaseUrl(0), WireMockContext.getThreadLocalBaseUrl(1));

        String echoA = environment.getRequiredProperty("dualu.svc.echoA");
        String echoB = environment.getRequiredProperty("dualu.svc.echoB");
        assertTrue(echoA.startsWith("http://localhost:"), "echoA: " + echoA);
        assertTrue(echoB.startsWith("http://localhost:"), "echoB: " + echoB);
        assertNotEquals(echoA, echoB);

        assertNotNull(restTemplate.getForObject(echoA, String.class));
        assertNotNull(restTemplate.getForObject(echoB, String.class));

        ResponseEntity<String> user = restTemplate.getForEntity("/api/users/5", String.class);
        assertEquals(200, user.getStatusCode().value(), "jsonplaceholder via app.thirdparty.url");
        assertNotNull(user.getBody());
    }

    @BeforeAll
    static void forceClassLevelParameterizedPlayback() {
        System.setProperty(StableMockConfig.PARAMETERIZED_PLAYBACK_USE_CLASS_SERVER_PROPERTY, "true");
    }

    @AfterAll
    static void clearClassLevelProperty() {
        System.clearProperty(StableMockConfig.PARAMETERIZED_PLAYBACK_USE_CLASS_SERVER_PROPERTY);
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1 })
    @Execution(ExecutionMode.SAME_THREAD)
    void parameterizedPlaybackKeepsTwoIndexedBases(int invocation) {
        assertNotNull(WireMockContext.getThreadLocalBaseUrl(0));
        assertNotNull(WireMockContext.getThreadLocalBaseUrl(1));
        assertNotEquals(WireMockContext.getThreadLocalBaseUrl(0), WireMockContext.getThreadLocalBaseUrl(1));
        ResponseEntity<String> user = restTemplate.getForEntity("/api/users/6", String.class);
        assertEquals(200, user.getStatusCode().value(), "invocation=" + invocation + " body: " + user.getBody());
    }
}
