package com.stablemock;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(U.List.class)
@ExtendWith(StableMockExtension.class)
public @interface U {
    /**
     * URLs to proxy and record.
     */
    String[] urls() default {};

    /**
     * Spring property names to map to these URLs (for automatic @DynamicPropertySource registration).
     *
     * Mapping rules:
     * - If there is 1 URL and multiple properties, all properties map to that URL.
     *   Each entry may be "name" (base URL only) or "name=/path" (base URL + path). Example:
     *   urls = {"https://api.com"}, properties = {"app.base.url", "app.get.url=/api/v1/get"}
     * - If there are multiple URLs, properties map 1:1 (first property to first URL, etc.).
     *   Extra properties beyond URLs map to the last URL. Use plain names only.
     *   Example: urls = {"https://api1.com", "https://api2.com"}, properties = {"app.api1.url", "app.api2.url"}
     */
    String[] properties() default {};

    /**
     * Fields or patterns to ignore during request matching.
     * Supports JSON fields: "json:timestamp", "json:requestId"
     * Supports GraphQL variables: "gql:variables.cursor", "graphql:variables.timestamp"
     * Supports XML: "xml://*[local-name()='timestamp']"
     * Example: {"json:timestamp", "json:requestId", "gql:variables.cursor"}
     */
    String[] ignore() default {};

    /**
     * Explicit protect list – these paths must not be ignored.
     * Entries here override both auto-detected ignore patterns and {@link #ignore()}.
     * Typical usage is to list identifying fields that should remain part of matching
     * even if they look dynamic to the detector.
     *
     * <p>Entries must use the same prefix/format conventions as {@link #ignore()}:
     * <ul>
     *   <li>JSON fields: {@code "json:fieldName"} or {@code "json:parent.child"}</li>
     *   <li>GraphQL variables: {@code "gql:variables.cursor"} or {@code "graphql:variables.timestamp"}
     *       (both are normalized to the equivalent {@code json:} path at runtime)</li>
     *   <li>XML elements or attributes: {@code "xml://*[local-name()='fieldName']"}</li>
     * </ul>
     * Entries that do not follow these conventions will not match any auto-detected or
     * explicit ignore pattern and will silently have no effect.
     * Example: {@code {"json:requestId", "xml://*[local-name()='correlationId']"}}
     */
    String[] dontIgnore() default {};

    /**
     * Enable scenario mode for sequential responses.
     * When true, multiple responses for the same endpoint will be returned sequentially
     * using WireMock scenarios. Useful for testing stateful behavior where the same
     * request should return different responses over time.
     * Example: Testing pagination, polling, or retry logic.
     */
    boolean scenario() default false;

    /**
     * Response headers to drop from recorded stubs.
     * - Header names are matched case-insensitively.
     * - A special value "*" means drop all response headers.
     * This only affects what is written into StableMock's recorded mappings; it does not
     * change how the upstream service is called during recording.
     */
    String[] ignoreResponseHeaders() default {};

    /**
     * Optional path overrides for properties when using 1 URL with multiple properties.
     * Format: "propertyName=/path" (e.g. "app.backend.get.url=/api/v1/get").
     * Used so the library can preserve per-property paths without project-specific Java (e.g. KNOWN_PATHS).
     * If not set, path is resolved from system property, application.properties, or defaultUrl.
     */
    String[] paths() default {};

    /**
     * Container annotation for repeatable @U annotations.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface List {
        U[] value();
    }
}

