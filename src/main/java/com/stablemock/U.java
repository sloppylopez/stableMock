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
     *   Example: urls = {"https://api.com"}, properties = {"app.api.url", "app.api.backup.url"}
     * - If there are multiple URLs, properties map 1:1 (first property to first URL, etc.).
     *   Extra properties beyond URLs map to the last URL.
     *   Example: urls = {"https://api1.com", "https://api2.com"}, properties = {"app.api1.url", "app.api2.url", "app.api2.backup.url"}
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
     * Enable scenario mode for sequential responses.
     * When true, multiple responses for the same endpoint will be returned sequentially
     * using WireMock scenarios. Useful for testing stateful behavior where the same
     * request should return different responses over time.
     * Example: Testing pagination, polling, or retry logic.
     */
    boolean scenario() default false;

    /**
     * Container annotation for repeatable @U annotations.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface List {
        U[] value();
    }
}

