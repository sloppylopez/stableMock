package com.stablemock.core.analysis;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class XmlBodyParserTest {

    @Test
    void testParseXml_ValidXml() {
        String xml = "<root><child>value</child></root>";
        Document result = XmlBodyParser.parseXml(xml);
        
        assertNotNull(result);
        assertEquals("root", result.getDocumentElement().getNodeName());
    }

    @Test
    void testParseXml_WithNamespaces() {
        String xml = "<ns:root xmlns:ns=\"http://example.com\"><ns:child>value</ns:child></ns:root>";
        Document result = XmlBodyParser.parseXml(xml);
        
        assertNotNull(result);
        assertNotNull(result.getDocumentElement());
    }

    @Test
    void testParseXml_NullInput() {
        Document result = XmlBodyParser.parseXml(null);
        assertNull(result);
    }

    @Test
    void testParseXml_EmptyString() {
        Document result = XmlBodyParser.parseXml("");
        assertNull(result);
    }

    @Test
    void testParseXml_InvalidXml() {
        String invalidXml = "<root><unclosed>";
        Document result = XmlBodyParser.parseXml(invalidXml);
        assertNull(result);
    }

    @Test
    void testIsXml_ValidXml() {
        assertTrue(XmlBodyParser.isXml("<root>data</root>"));
        assertTrue(XmlBodyParser.isXml("  <root>data</root>  "));
    }

    @Test
    void testIsXml_SelfClosing() {
        assertTrue(XmlBodyParser.isXml("<root/>"));
        assertTrue(XmlBodyParser.isXml("<root />"));
    }

    @Test
    void testIsXml_NotXml() {
        assertFalse(XmlBodyParser.isXml("not xml"));
        assertFalse(XmlBodyParser.isXml("{\"json\":true}"));
        assertFalse(XmlBodyParser.isXml("plain text"));
    }

    @Test
    void testIsXml_NullOrEmpty() {
        assertFalse(XmlBodyParser.isXml(null));
        assertFalse(XmlBodyParser.isXml(""));
        assertFalse(XmlBodyParser.isXml("   "));
    }

    @Test
    void testIsXmlContentType_ApplicationXml() {
        assertTrue(XmlBodyParser.isXmlContentType("application/xml"));
        assertTrue(XmlBodyParser.isXmlContentType("application/xml; charset=utf-8"));
    }

    @Test
    void testIsXmlContentType_TextXml() {
        assertTrue(XmlBodyParser.isXmlContentType("text/xml"));
    }

    @Test
    void testIsXmlContentType_SoapXml() {
        assertTrue(XmlBodyParser.isXmlContentType("application/soap+xml"));
    }

    @Test
    void testIsXmlContentType_ContainsXml() {
        assertTrue(XmlBodyParser.isXmlContentType("application/vnd.api+xml"));
    }

    @Test
    void testIsXmlContentType_CaseInsensitive() {
        assertTrue(XmlBodyParser.isXmlContentType("APPLICATION/XML"));
        assertTrue(XmlBodyParser.isXmlContentType("Application/Xml"));
    }

    @Test
    void testIsXmlContentType_NotXml() {
        assertFalse(XmlBodyParser.isXmlContentType("application/json"));
        assertFalse(XmlBodyParser.isXmlContentType("text/plain"));
        assertFalse(XmlBodyParser.isXmlContentType(null));
    }

    @Test
    void testExtractElementValues_SimpleXml() {
        String xml = "<root><name>test</name><value>123</value></root>";
        Document doc = XmlBodyParser.parseXml(xml);
        Map<String, String> values = XmlBodyParser.extractElementValues(doc);
        
        assertEquals(2, values.size());
        assertEquals("test", values.get("root/name"));
        assertEquals("123", values.get("root/value"));
    }

    @Test
    void testExtractElementValues_NestedXml() {
        String xml = "<root><parent><child>value</child></parent></root>";
        Document doc = XmlBodyParser.parseXml(xml);
        Map<String, String> values = XmlBodyParser.extractElementValues(doc);
        
        assertEquals(1, values.size());
        assertEquals("value", values.get("root/parent/child"));
    }

    @Test
    void testExtractElementValues_MultipleElements() {
        String xml = "<root><item>1</item><item>2</item></root>";
        Document doc = XmlBodyParser.parseXml(xml);
        Map<String, String> values = XmlBodyParser.extractElementValues(doc);
        
        // When multiple elements have the same name, map overwrites with last value
        // So we get 1 entry with the last value
        assertEquals(1, values.size());
        assertTrue(values.containsKey("root/item"));
        // The value will be the last one processed (implementation-dependent, but typically "2")
        assertNotNull(values.get("root/item"));
    }

    @Test
    void testExtractElementValues_EmptyElements() {
        String xml = "<root><empty></empty><withValue>test</withValue></root>";
        Document doc = XmlBodyParser.parseXml(xml);
        Map<String, String> values = XmlBodyParser.extractElementValues(doc);
        
        assertEquals(1, values.size());
        assertEquals("test", values.get("root/withValue"));
    }

    @Test
    void testExtractElementValues_NullDocument() {
        Map<String, String> values = XmlBodyParser.extractElementValues(null);
        assertTrue(values.isEmpty());
    }

    @Test
    void testParseAllXmlBodies_ValidBodies() {
        var bodies = List.of(
            "<root><a>1</a></root>",
            "<root><b>2</b></root>",
            "<root><c>3</c></root>"
        );
        
        var result = XmlBodyParser.parseAllXmlBodies(bodies);
        
        assertEquals(3, result.size());
        assertFalse(result.get(0).isEmpty());
        assertFalse(result.get(1).isEmpty());
        assertFalse(result.get(2).isEmpty());
    }

    @Test
    void testParseAllXmlBodies_WithInvalid() {
        var bodies = List.of(
            "<root><a>1</a></root>",
            "<invalid>",
            "<root><b>2</b></root>"
        );
        
        var result = XmlBodyParser.parseAllXmlBodies(bodies);
        
        // Invalid XML is skipped (parseXml returns null), so we get 2 entries, not 3
        assertEquals(2, result.size());
        assertFalse(result.get(0).isEmpty());
        assertFalse(result.get(1).isEmpty());
    }

    @Test
    void testParseAllXmlBodies_EmptyList() {
        var result = XmlBodyParser.parseAllXmlBodies(List.of());
        assertTrue(result.isEmpty());
    }
}

