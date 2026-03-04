package example;

import com.stablemock.spring.BaseStableMockTest;
import com.stablemock.U;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the single-URL, multiple-properties pattern where each property specifies
 * its path inline ("name=/path") and no dummy first property is required.
 * Uses generic property names and ignore patterns (no project-specific nomenclature).
 */
@U(
    urls = {"https://postman-echo.com"},
    properties = {
        "app.backend.get.url=/get",
        "app.backend.post.url=/post",
        "app.backend.put.url=/put",
        "app.backend.patch.url=/patch"
    },
    ignore = {
        "json:timestamp",
        "json:requestId"
    }
)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class SingleUrlInlinePathsTest extends BaseStableMockTest {

    @Value("${app.backend.get.url}")
    private String getUrl;

    @Value("${app.backend.post.url}")
    private String postUrl;

    @Value("${app.backend.put.url}")
    private String putUrl;

    @Value("${app.backend.patch.url}")
    private String patchUrl;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        autoRegisterProperties(registry, SingleUrlInlinePathsTest.class);
    }

    @Test
    void testInlinePathPropertiesPointToWireMockWithCorrectPaths() {
        assertNotNull(getUrl, "app.backend.get.url should be registered");
        assertNotNull(postUrl, "app.backend.post.url should be registered");
        assertNotNull(putUrl, "app.backend.put.url should be registered");
        assertNotNull(patchUrl, "app.backend.patch.url should be registered");

        assertTrue(getUrl.startsWith("http://localhost:"),
            "get should point to WireMock: " + getUrl);
        assertTrue(postUrl.startsWith("http://localhost:"),
            "post should point to WireMock: " + postUrl);
        assertTrue(putUrl.startsWith("http://localhost:"),
            "put should point to WireMock: " + putUrl);
        assertTrue(patchUrl.startsWith("http://localhost:"),
            "patch should point to WireMock: " + patchUrl);

        assertTrue(getUrl.endsWith("/get"), "get should preserve path /get: " + getUrl);
        assertTrue(postUrl.endsWith("/post"), "post should preserve path /post: " + postUrl);
        assertTrue(putUrl.endsWith("/put"), "put should preserve path /put: " + putUrl);
        assertTrue(patchUrl.endsWith("/patch"), "patch should preserve path /patch: " + patchUrl);
    }

    @Test
    void testAllPropertiesUseSameWireMockPort() {
        int portGet = extractPort(getUrl);
        int portPost = extractPort(postUrl);
        assertEquals(portGet, portPost, "get and post should use same port");
        assertEquals(portGet, extractPort(putUrl), "put should use same port");
        assertEquals(portGet, extractPort(patchUrl), "patch should use same port");
    }

    private int extractPort(String url) {
        // "http://localhost:12345/path" -> 12345
        int colonIndex = url.indexOf("://");
        if (colonIndex < 0) return -1;
        int start = url.indexOf(':', colonIndex + 3);
        if (start < 0) return -1;
        int end = url.indexOf('/', start + 1);
        if (end < 0) end = url.length();
        return Integer.parseInt(url.substring(start + 1, end));
    }
}
