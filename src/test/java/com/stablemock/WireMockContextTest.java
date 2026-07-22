package com.stablemock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WireMockContextTest {

    @AfterEach
    void tearDown() {
        WireMockContext.clear();
    }

    @Test
    void indexOneDoesNotSilentlyReusePrimaryBaseUrlWhenIndexedArrayMissing() {
        WireMockContext.setBaseUrl("http://localhost:1111");
        assertNull(WireMockContext.getThreadLocalBaseUrl(1),
                "index 1 must not fall back to index-0 URL when threadLocalBaseUrls is unset");
        assertEquals("http://localhost:1111", WireMockContext.getThreadLocalBaseUrl(0));
    }

    @Test
    void indexOneDoesNotFallBackWhenArrayTooShort() {
        WireMockContext.setBaseUrls(new String[] { "http://localhost:1" });
        WireMockContext.setBaseUrl("http://localhost:999");
        assertNull(WireMockContext.getThreadLocalBaseUrl(1));
        assertEquals("http://localhost:1", WireMockContext.getThreadLocalBaseUrl(0));
    }

    @Test
    void setBaseUrlsNullClearsIndexedArray() {
        WireMockContext.setBaseUrls(new String[] { "http://localhost:1", "http://localhost:2" });
        WireMockContext.setBaseUrls(null);
        assertNull(WireMockContext.getThreadLocalBaseUrl(1));
    }
}
