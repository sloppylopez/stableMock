package com.stablemock.core.storage;

import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the response-header ignore feature driven by @U(ignoreResponseHeaders).
 */
class SingleAnnotationMappingStorageHeaderIgnoreTest {

    @Test
    void ignoreSpecificResponseHeaders_removesOnlyThoseHeaders() throws Exception {
        String json = """
            {
              "id": "11111111-1111-1111-1111-111111111111",
              "name": "test",
              "request": { "url": "/test", "method": "GET" },
              "response": {
                "status": 200,
                "headers": {
                  "X-Foo": "bar",
                  "Set-Cookie": [ "a=1" ],
                  "CF-RAY": "abc123"
                }
              }
            }
            """;
        StubMapping mapping = StubMapping.buildFrom(json);
        List<StubMapping> mappings = new ArrayList<>();
        mappings.add(mapping);

        SingleAnnotationMappingStorage.applyIgnoreResponseHeaders(mappings, new String[]{"Set-Cookie", "cf-ray"});

        assertEquals(1, mappings.size());
        StubMapping rewritten = mappings.get(0);
        assertNotNull(rewritten.getResponse());
        var headers = rewritten.getResponse().getHeaders();
        assertNotNull(headers);
        assertTrue(headers.getHeader("X-Foo").isPresent());
        assertFalse(headers.getHeader("Set-Cookie").isPresent());
        assertFalse(headers.getHeader("CF-RAY").isPresent());
    }

    @Test
    void ignoreAllResponseHeaders_removesHeadersObjectCompletely() throws Exception {
        String json = """
            {
              "id": "22222222-2222-2222-2222-222222222222",
              "name": "test-all",
              "request": { "url": "/all", "method": "GET" },
              "response": {
                "status": 200,
                "headers": {
                  "X-Foo": "bar",
                  "Set-Cookie": [ "a=1" ]
                }
              }
            }
            """;
        StubMapping mapping = StubMapping.buildFrom(json);
        List<StubMapping> mappings = new ArrayList<>();
        mappings.add(mapping);

        SingleAnnotationMappingStorage.applyIgnoreResponseHeaders(mappings, new String[]{"*"});

        assertEquals(1, mappings.size());
        StubMapping rewritten = mappings.get(0);
        assertNotNull(rewritten.getResponse());
        // When headers are removed from JSON, WireMock builds a response with null or empty headers
        var headers = rewritten.getResponse().getHeaders();
        if (headers != null) {
            assertTrue(headers.all().isEmpty());
        }
    }
}

