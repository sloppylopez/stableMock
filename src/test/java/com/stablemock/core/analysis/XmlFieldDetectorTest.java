package com.stablemock.core.analysis;

import org.junit.jupiter.api.Test;

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
        assertTrue(result.getDynamicFields().get(0).fieldPath().contains("timestamp"));
        assertEquals(1, result.getIgnorePatterns().size());
        assertTrue(result.getIgnorePatterns().get(0).startsWith("xml:"), 
            "Pattern should start with 'xml:', got: " + result.getIgnorePatterns().get(0));
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
        assertTrue(result.getDynamicFields().get(0).fieldPath().contains("timestamp"));
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
            .anyMatch(f -> f.fieldPath().contains("id"));
        assertTrue(foundId, "Should detect id field as changing");
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
        assertEquals(3, result.getDynamicFields().get(0).sampleValues().size());
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
        assertTrue(pattern.startsWith("xml:"), "Pattern should start with 'xml:', got: " + pattern);
        assertTrue(pattern.contains("local-name()"), "Pattern should contain 'local-name()', got: " + pattern);
        assertTrue(pattern.contains("timestamp"), "Pattern should contain 'timestamp', got: " + pattern);
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

    @Test
    void testDetectDynamicFieldsInXml_HeuristicDetection_SameDateValues() {
        DetectionResult result = new DetectionResult("TestClass", "testMethod", 2);
        
        // Same date values (as would happen in a single test run)
        List<String> xmlBodies = List.of(
            "<root><StayDateRange Start=\"2025-02-23\" End=\"2025-02-24\"/></root>",
            "<root><StayDateRange Start=\"2025-02-23\" End=\"2025-02-24\"/></root>"
        );
        
        XmlFieldDetector.detectDynamicFieldsInXml(xmlBodies, result);
        
        // Should detect Start and End via heuristic even though values are same
        assertEquals(2, result.getDynamicFields().size());
        assertEquals(2, result.getIgnorePatterns().size());
        
        var fieldPaths = result.getDynamicFields().stream()
            .map(f -> f.fieldPath())
            .toList();
        assertTrue(fieldPaths.stream().anyMatch(p -> p.contains("Start")),
            "Should detect Start attribute via heuristic. Found: " + fieldPaths);
        assertTrue(fieldPaths.stream().anyMatch(p -> p.contains("End")),
            "Should detect End attribute via heuristic. Found: " + fieldPaths);
    }

    @Test
    void testDetectDynamicFieldsInXml_HeuristicDetection_DateElement() {
        DetectionResult result = new DetectionResult("TestClass", "testMethod", 2);
        
        List<String> xmlBodies = List.of(
            "<root><startDate>2025-02-23</startDate><endDate>2025-02-24</endDate></root>",
            "<root><startDate>2025-02-23</startDate><endDate>2025-02-24</endDate></root>"
        );
        
        XmlFieldDetector.detectDynamicFieldsInXml(xmlBodies, result);
        
        // Should detect startDate and endDate via heuristic
        assertEquals(2, result.getDynamicFields().size());
        var fieldPaths = result.getDynamicFields().stream()
            .map(f -> f.fieldPath())
            .toList();
        assertTrue(fieldPaths.stream().anyMatch(p -> p.contains("startDate")),
            "Should detect startDate via heuristic. Found: " + fieldPaths);
        assertTrue(fieldPaths.stream().anyMatch(p -> p.contains("endDate")),
            "Should detect endDate via heuristic. Found: " + fieldPaths);
    }

    @Test
    void testDetectDynamicFieldsInXml_HeuristicDetection_Timestamp() {
        DetectionResult result = new DetectionResult("TestClass", "testMethod", 2);
        
        List<String> xmlBodies = List.of(
            "<root><timestamp>2025-02-23T10:00:00Z</timestamp></root>",
            "<root><timestamp>2025-02-23T10:00:00Z</timestamp></root>"
        );
        
        XmlFieldDetector.detectDynamicFieldsInXml(xmlBodies, result);
        
        // Should detect timestamp via heuristic
        assertEquals(1, result.getDynamicFields().size());
        assertTrue(result.getDynamicFields().get(0).fieldPath().contains("timestamp"));
    }

    @Test
    void testDetectDynamicFieldsInXml_HeuristicDetection_NoFalsePositive() {
        DetectionResult result = new DetectionResult("TestClass", "testMethod", 2);
        
        // Field name doesn't suggest date/time, values are same - should NOT be detected
        List<String> xmlBodies = List.of(
            "<root><id>123</id><name>test</name></root>",
            "<root><id>123</id><name>test</name></root>"
        );
        
        XmlFieldDetector.detectDynamicFieldsInXml(xmlBodies, result);
        
        // Should NOT detect id or name as dynamic (no heuristic match, values are same)
        assertTrue(result.getDynamicFields().isEmpty());
        assertTrue(result.getIgnorePatterns().isEmpty());
    }

    @Test
    void testDetectDynamicFieldsInXml_HeuristicDetection_NestedStayDateRange() {
        DetectionResult result = new DetectionResult("TestClass", "testMethod", 2);
        
        // Simulating OTA HotelAvailRQ structure
        List<String> xmlBodies = List.of(
            "<Envelope><Body><OTA_HotelAvailRQ><StayDateRange Start=\"2025-02-23\" End=\"2025-02-24\"/></OTA_HotelAvailRQ></Body></Envelope>",
            "<Envelope><Body><OTA_HotelAvailRQ><StayDateRange Start=\"2025-02-23\" End=\"2025-02-24\"/></OTA_HotelAvailRQ></Body></Envelope>"
        );
        
        XmlFieldDetector.detectDynamicFieldsInXml(xmlBodies, result);
        
        // Should detect Start and End attributes via heuristic
        assertEquals(2, result.getDynamicFields().size());
        var fieldPaths = result.getDynamicFields().stream()
            .map(f -> f.fieldPath())
            .toList();
        assertTrue(fieldPaths.stream().anyMatch(p -> p.contains("Start")),
            "Should detect Start attribute via heuristic. Found: " + fieldPaths);
        assertTrue(fieldPaths.stream().anyMatch(p -> p.contains("End")),
            "Should detect End attribute via heuristic. Found: " + fieldPaths);
    }

    @Test
    void testDetectDynamicFieldsInXml_HeuristicDetection_RequestId() {
        DetectionResult result = new DetectionResult("TestClass", "testMethod", 2);
        
        List<String> xmlBodies = List.of(
            "<root><requestId>abc-123</requestId></root>",
            "<root><requestId>abc-123</requestId></root>"
        );
        
        XmlFieldDetector.detectDynamicFieldsInXml(xmlBodies, result);
        
        // Should detect requestId via heuristic
        assertEquals(1, result.getDynamicFields().size());
        assertTrue(result.getDynamicFields().get(0).fieldPath().contains("requestId"));
    }
}

