package com.stablemock.core.analysis;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class XmlFieldDetectorTest {

    @Test
    void testDetectDynamicFieldsInXml_ChangingElement() {
        DetectionResult result = new DetectionResult("TestClass", "testMethod", 2);
        
        List<String> xmlBodies = List.of(
            "<root><id>1</id><name>test</name><timestamp>2025-01-01T10:00:00Z</timestamp></root>",
            "<root><id>1</id><name>test</name><timestamp>2025-01-01T10:00:01Z</timestamp></root>"
        );
        
        XmlFieldDetector.detectDynamicFieldsInXml(xmlBodies, result);
        
        assertEquals(1, result.getDynamicFields().size());
        assertTrue(result.getDynamicFields().get(0).getFieldPath().contains("timestamp"));
        assertEquals(1, result.getIgnorePatterns().size());
        assertTrue(result.getIgnorePatterns().get(0).startsWith("xml://"));
    }

    @Test
    void testDetectDynamicFieldsInXml_MultipleChangingElements() {
        DetectionResult result = new DetectionResult("TestClass", "testMethod", 2);
        
        List<String> xmlBodies = List.of(
            "<root><id>1</id><timestamp>2025-01-01T10:00:00Z</timestamp><requestId>abc-123</requestId></root>",
            "<root><id>1</id><timestamp>2025-01-01T10:00:01Z</timestamp><requestId>def-456</requestId></root>"
        );
        
        XmlFieldDetector.detectDynamicFieldsInXml(xmlBodies, result);
        
        assertEquals(2, result.getDynamicFields().size());
        assertEquals(2, result.getIgnorePatterns().size());
    }

    @Test
    void testDetectDynamicFieldsInXml_NoChangingElements() {
        DetectionResult result = new DetectionResult("TestClass", "testMethod", 2);
        
        List<String> xmlBodies = List.of(
            "<root><id>1</id><name>test</name></root>",
            "<root><id>1</id><name>test</name></root>"
        );
        
        XmlFieldDetector.detectDynamicFieldsInXml(xmlBodies, result);
        
        assertTrue(result.getDynamicFields().isEmpty());
        assertTrue(result.getIgnorePatterns().isEmpty());
    }

    @Test
    void testDetectDynamicFieldsInXml_NestedElements() {
        DetectionResult result = new DetectionResult("TestClass", "testMethod", 2);
        
        List<String> xmlBodies = List.of(
            "<root><user><id>1</id><timestamp>2025-01-01T10:00:00Z</timestamp></user></root>",
            "<root><user><id>1</id><timestamp>2025-01-01T10:00:01Z</timestamp></user></root>"
        );
        
        XmlFieldDetector.detectDynamicFieldsInXml(xmlBodies, result);
        
        assertEquals(1, result.getDynamicFields().size());
        assertTrue(result.getDynamicFields().get(0).getFieldPath().contains("timestamp"));
    }

    @Test
    void testDetectDynamicFieldsInXml_InsufficientBodies() {
        DetectionResult result = new DetectionResult("TestClass", "testMethod", 1);
        
        List<String> xmlBodies = List.of(
            "<root><id>1</id></root>"
        );
        
        XmlFieldDetector.detectDynamicFieldsInXml(xmlBodies, result);
        
        assertTrue(result.getDynamicFields().isEmpty());
    }

    @Test
    void testDetectDynamicFieldsInXml_NullBodies() {
        DetectionResult result = new DetectionResult("TestClass", "testMethod", 0);
        
        XmlFieldDetector.detectDynamicFieldsInXml(null, result);
        
        assertTrue(result.getDynamicFields().isEmpty());
    }

    @Test
    void testDetectDynamicFieldsInXml_InvalidXml() {
        DetectionResult result = new DetectionResult("TestClass", "testMethod", 2);
        
        List<String> xmlBodies = List.of(
            "<root><id>1</id></root>",
            "<invalid>",
            "<root><id>2</id></root>"
        );
        
        XmlFieldDetector.detectDynamicFieldsInXml(xmlBodies, result);
        
        // Should detect id as changing between the two valid XML documents
        assertTrue(result.getDynamicFields().size() >= 1);
        boolean foundId = result.getDynamicFields().stream()
            .anyMatch(f -> f.getFieldPath().contains("id"));
        assertTrue(foundId, "Should detect id field as changing");
    }

    @Test
    void testDetectDynamicFieldsInXml_ConfidenceLevels() {
        DetectionResult result = new DetectionResult("TestClass", "testMethod", 5);
        
        List<String> xmlBodies = List.of(
            "<root><timestamp>2025-01-01T10:00:00Z</timestamp></root>",
            "<root><timestamp>2025-01-01T10:00:01Z</timestamp></root>",
            "<root><timestamp>2025-01-01T10:00:02Z</timestamp></root>",
            "<root><timestamp>2025-01-01T10:00:03Z</timestamp></root>",
            "<root><timestamp>2025-01-01T10:00:04Z</timestamp></root>"
        );
        
        XmlFieldDetector.detectDynamicFieldsInXml(xmlBodies, result);
        
        assertEquals(1, result.getDynamicFields().size());
        assertEquals("HIGH", result.getDynamicFields().get(0).getConfidence());
    }

    @Test
    void testDetectDynamicFieldsInXml_SampleValuesLimit() {
        DetectionResult result = new DetectionResult("TestClass", "testMethod", 5);
        
        List<String> xmlBodies = List.of(
            "<root><timestamp>val1</timestamp></root>",
            "<root><timestamp>val2</timestamp></root>",
            "<root><timestamp>val3</timestamp></root>",
            "<root><timestamp>val4</timestamp></root>",
            "<root><timestamp>val5</timestamp></root>"
        );
        
        XmlFieldDetector.detectDynamicFieldsInXml(xmlBodies, result);
        
        assertEquals(1, result.getDynamicFields().size());
        assertEquals(3, result.getDynamicFields().get(0).getSampleValues().size());
    }

    @Test
    void testDetectDynamicFieldsInXml_XPathPatternGeneration() {
        DetectionResult result = new DetectionResult("TestClass", "testMethod", 2);
        
        List<String> xmlBodies = List.of(
            "<root><timestamp>2025-01-01T10:00:00Z</timestamp></root>",
            "<root><timestamp>2025-01-01T10:00:01Z</timestamp></root>"
        );
        
        XmlFieldDetector.detectDynamicFieldsInXml(xmlBodies, result);
        
        assertEquals(1, result.getIgnorePatterns().size());
        String pattern = result.getIgnorePatterns().get(0);
        assertTrue(pattern.startsWith("xml://"));
        assertTrue(pattern.contains("local-name()"));
        assertTrue(pattern.contains("timestamp"));
    }

    @Test
    void testDetectDynamicFieldsInXml_DifferentRootElements() {
        DetectionResult result = new DetectionResult("TestClass", "testMethod", 2);
        
        List<String> xmlBodies = List.of(
            "<request><id>1</id><timestamp>2025-01-01T10:00:00Z</timestamp></request>",
            "<request><id>1</id><timestamp>2025-01-01T10:00:01Z</timestamp></request>"
        );
        
        XmlFieldDetector.detectDynamicFieldsInXml(xmlBodies, result);
        
        assertEquals(1, result.getDynamicFields().size());
        assertTrue(result.getIgnorePatterns().get(0).contains("timestamp"));
    }
}

