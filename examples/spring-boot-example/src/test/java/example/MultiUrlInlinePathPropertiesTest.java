package example;

import com.stablemock.U;
import com.stablemock.spring.BaseStableMockTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for multiple-URL + inline-path properties.
 *
 * Scenario:
 * - First @U uses a single URL and an inline-path property entry:
 *     properties = { "stablemock.soap.endpoint=/soap-path" }
 *   This mirrors the real-world pattern svc.cloud.avail.endpoint=/sap/... where
 *   the property name is the Spring property key and the "=..." part is the path
 *   that should be appended to the WireMock base URL.
 *
 * - Second @U adds another URL/property so that, overall, autoRegisterProperties()
 *   sees more than one URL and takes the "multiple URLs" branch.
 *
 * Expected (desired) behavior:
 * - stablemock.soap.endpoint is registered as a dynamic property.
 * - Its value points to http://localhost:<port>/soap-path (WireMock base + preserved path),
 *   even though there are multiple URLs/annotations involved.
 *
 * To run against the fixed StableMock: from repo root run {@code ./gradlew publishToMavenLocal},
 * then from examples/spring-boot-example run {@code ./gradlew test --tests example.MultiUrlInlinePathPropertiesTest}.
 */
@U(
    urls = { "https://postman-echo.com" },
    properties = { "stablemock.soap.endpoint=/soap-path" }
)
@U(
    urls = { "https://jsonplaceholder.typicode.com" },
    properties = { "app.thirdparty.url" }
)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class MultiUrlInlinePathPropertiesTest extends BaseStableMockTest {

    private static final String SOAP_ENDPOINT_PROPERTY = "stablemock.soap.endpoint";

    @Autowired
    private Environment environment;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        autoRegisterProperties(registry, MultiUrlInlinePathPropertiesTest.class);
    }

    @BeforeAll
    static void setUpSystemProperty() {
        // Mirror real-world setup where the property value may be just a path,
        // not a full URL. BaseStableMockTest.extractPathFromProperty() will see
        // this but cannot parse it as a URL, so the only reliable source of the
        // path should be the inline "= /soap-path" syntax in the @U annotation.
        System.setProperty(SOAP_ENDPOINT_PROPERTY, "/soap-path");
    }

    @AfterAll
    static void clearSystemProperty() {
        System.clearProperty(SOAP_ENDPOINT_PROPERTY);
    }

    @Test
    void inlinePathPropertyIsPreservedWithMultipleUrls() {
        String value = environment.getProperty(SOAP_ENDPOINT_PROPERTY);
        assertNotNull(value, SOAP_ENDPOINT_PROPERTY + " should be registered as a dynamic property");

        assertTrue(
            value.startsWith("http://localhost:"),
            SOAP_ENDPOINT_PROPERTY + " should point to WireMock (localhost), but was: " + value
        );

        assertTrue(
            value.endsWith("/soap-path"),
            SOAP_ENDPOINT_PROPERTY + " should preserve the inline path '/soap-path' even when multiple @U URLs are present. Actual value: " + value
        );
    }
}

