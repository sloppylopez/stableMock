package example;

import com.stablemock.U;
import com.stablemock.WireMockContext;
import com.stablemock.spring.BaseStableMockTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link BaseStableMockTest#autoRegisterProperties}.
 *
 * Regression for Bug 2: when multiple {@code @U} annotations are present and a
 * property uses the inline-path syntax (e.g. {@code "soap.endpoint=/soap-path"}),
 * the path must be preserved in the registered WireMock URL.
 */
class BaseStableMockAutoRegisterTest extends BaseStableMockTest {

    // ── test fixtures ───────────────────────────────────────────────────────

    /** Two @U annotations; first one uses inline-path syntax. */
    @U(urls = {"https://postman-echo.com"}, properties = {"soap.endpoint=/soap-path"})
    @U(urls = {"https://jsonplaceholder.typicode.com"}, properties = {"app.thirdparty.url"})
    static class MultiUrlWithInlinePath {}

    /** Single @U with inline-path syntax. */
    @U(urls = {"https://api.example.com"}, properties = {"api.url=/v1/users"})
    static class SingleUrlWithInlinePath {}

    /** Single @U, no path. */
    @U(urls = {"https://api.example.com"}, properties = {"api.url"})
    static class SingleUrlNoPath {}

    // ── helpers ─────────────────────────────────────────────────────────────

    @AfterEach
    void clearContext() {
        WireMockContext.clear();
    }

    /** Minimal DynamicPropertyRegistry that collects (name, supplier) pairs. */
    private static class CapturingRegistry implements DynamicPropertyRegistry {
        final Map<String, Supplier<Object>> entries = new LinkedHashMap<>();

        @Override
        public void add(String name, Supplier<Object> valueSupplier) {
            entries.put(name, valueSupplier);
        }
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    void multipleUrlsInlinePathIsPreserved() {
        WireMockContext.setBaseUrls(new String[]{
                "http://localhost:10001",
                "http://localhost:10002"
        });

        CapturingRegistry registry = new CapturingRegistry();
        BaseStableMockTest.autoRegisterProperties(registry, MultiUrlWithInlinePath.class);

        assertTrue(registry.entries.containsKey("soap.endpoint"),
                "soap.endpoint should be registered");
        assertTrue(registry.entries.containsKey("app.thirdparty.url"),
                "app.thirdparty.url should be registered");

        String soapValue = (String) registry.entries.get("soap.endpoint").get();
        assertNotNull(soapValue);
        assertTrue(soapValue.startsWith("http://localhost:"),
                "soap.endpoint should point to WireMock, got: " + soapValue);
        assertTrue(soapValue.endsWith("/soap-path"),
                "soap.endpoint should preserve the '/soap-path' suffix, got: " + soapValue);

        String thirdPartyValue = (String) registry.entries.get("app.thirdparty.url").get();
        assertNotNull(thirdPartyValue);
        assertTrue(thirdPartyValue.startsWith("http://localhost:"),
                "app.thirdparty.url should point to WireMock, got: " + thirdPartyValue);
    }

    @Test
    void byIndexSecondPropertyDoesNotReusePrimaryWireMockWhenIndexedArrayUnset() {
        WireMockContext.setBaseUrl("http://localhost:40404");

        CapturingRegistry registry = new CapturingRegistry();
        BaseStableMockTest.autoRegisterProperties(registry, MultiUrlWithInlinePath.class);

        String soap = (String) registry.entries.get("soap.endpoint").get();
        String thirdParty = (String) registry.entries.get("app.thirdparty.url").get();
        assertTrue(soap.contains("40404"), "index 0 may use primary ThreadLocal; soap=" + soap);
        assertFalse(thirdParty.contains("40404"),
                "index 1 must not fall back to primary port; got app.thirdparty.url=" + thirdParty);
    }

    @Test
    void singleUrlInlinePathIsPreserved() {
        WireMockContext.setBaseUrl("http://localhost:20000");

        CapturingRegistry registry = new CapturingRegistry();
        BaseStableMockTest.autoRegisterProperties(registry, SingleUrlWithInlinePath.class);

        assertTrue(registry.entries.containsKey("api.url"));
        String value = (String) registry.entries.get("api.url").get();
        assertEquals("http://localhost:20000/v1/users", value);
    }

    @Test
    void singleUrlNoPathRegistersBaseUrl() {
        WireMockContext.setBaseUrl("http://localhost:20001");

        CapturingRegistry registry = new CapturingRegistry();
        BaseStableMockTest.autoRegisterProperties(registry, SingleUrlNoPath.class);

        assertTrue(registry.entries.containsKey("api.url"));
        String value = (String) registry.entries.get("api.url").get();
        assertEquals("http://localhost:20001", value);
    }

    @Test
    void noAnnotationsRegistersNothing() {
        CapturingRegistry registry = new CapturingRegistry();
        BaseStableMockTest.autoRegisterProperties(registry, Object.class);
        assertTrue(registry.entries.isEmpty());
    }
}
