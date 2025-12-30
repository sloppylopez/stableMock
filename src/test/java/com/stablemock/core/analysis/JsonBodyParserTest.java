package com.stablemock.core.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonBodyParserTest {

    @Test
    void testParseJson_ValidObject() {
        String json = "{\"name\":\"test\",\"value\":123}";
        JsonNode result = JsonBodyParser.parseJson(json);
        
        assertNotNull(result);
        assertTrue(result.isObject());
        assertEquals("test", result.get("name").asText());
        assertEquals(123, result.get("value").asInt());
    }

    @Test
    void testParseJson_ValidArray() {
        String json = "[1,2,3]";
        JsonNode result = JsonBodyParser.parseJson(json);
        
        assertNotNull(result);
        assertTrue(result.isArray());
        assertEquals(3, result.size());
    }

    @Test
    void testParseJson_NullInput() {
        JsonNode result = JsonBodyParser.parseJson(null);
        assertNull(result);
    }

    @Test
    void testParseJson_EmptyString() {
        JsonNode result = JsonBodyParser.parseJson("");
        assertNull(result);
    }

    @Test
    void testParseJson_WhitespaceOnly() {
        JsonNode result = JsonBodyParser.parseJson("   ");
        assertNull(result);
    }

    @Test
    void testParseJson_InvalidJson() {
        String invalidJson = "{invalid json}";
        JsonNode result = JsonBodyParser.parseJson(invalidJson);
        assertNull(result);
    }

    @Test
    void testIsJson_ValidObject() {
        assertTrue(JsonBodyParser.isJson("{\"key\":\"value\"}"));
    }

    @Test
    void testIsJson_ValidArray() {
        assertTrue(JsonBodyParser.isJson("[1,2,3]"));
    }

    @Test
    void testIsJson_WithWhitespace() {
        assertTrue(JsonBodyParser.isJson("  {\"key\":\"value\"}  "));
    }

    @Test
    void testIsJson_NotJson() {
        assertFalse(JsonBodyParser.isJson("not json"));
        assertFalse(JsonBodyParser.isJson("<xml>data</xml>"));
        assertFalse(JsonBodyParser.isJson("plain text"));
    }

    @Test
    void testIsJson_NullOrEmpty() {
        assertFalse(JsonBodyParser.isJson(null));
        assertFalse(JsonBodyParser.isJson(""));
        assertFalse(JsonBodyParser.isJson("   "));
    }

    @Test
    void testIsJsonContentType_ApplicationJson() {
        assertTrue(JsonBodyParser.isJsonContentType("application/json"));
        assertTrue(JsonBodyParser.isJsonContentType("application/json; charset=utf-8"));
    }

    @Test
    void testIsJsonContentType_ContainsJson() {
        assertTrue(JsonBodyParser.isJsonContentType("application/vnd.api+json"));
        assertTrue(JsonBodyParser.isJsonContentType("text/json"));
    }

    @Test
    void testIsJsonContentType_CaseInsensitive() {
        assertTrue(JsonBodyParser.isJsonContentType("APPLICATION/JSON"));
        assertTrue(JsonBodyParser.isJsonContentType("Application/Json"));
    }

    @Test
    void testIsJsonContentType_NotJson() {
        assertFalse(JsonBodyParser.isJsonContentType("application/xml"));
        assertFalse(JsonBodyParser.isJsonContentType("text/plain"));
        assertFalse(JsonBodyParser.isJsonContentType(null));
    }

    @Test
    void testParseAllJsonBodies_ValidBodies() {
        var bodies = java.util.List.of(
            "{\"a\":1}",
            "{\"b\":2}",
            "{\"c\":3}"
        );
        
        var result = JsonBodyParser.parseAllJsonBodies(bodies);
        
        assertEquals(3, result.size());
        assertNotNull(result.get(0));
        assertNotNull(result.get(1));
        assertNotNull(result.get(2));
    }

    @Test
    void testParseAllJsonBodies_WithInvalid() {
        var bodies = java.util.List.of(
            "{\"a\":1}",
            "invalid json",
            "{\"b\":2}"
        );
        
        var result = JsonBodyParser.parseAllJsonBodies(bodies);
        
        assertEquals(3, result.size());
        assertNotNull(result.get(0));
        assertNull(result.get(1));
        assertNotNull(result.get(2));
    }

    @Test
    void testParseAllJsonBodies_EmptyList() {
        var result = JsonBodyParser.parseAllJsonBodies(java.util.List.of());
        assertTrue(result.isEmpty());
    }
}

