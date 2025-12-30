package com.stablemock.core.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsonFieldDetectorTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void testDetectDynamicFieldsInJson_ChangingField() throws Exception {
        DetectionResult result = new DetectionResult("TestClass", "testMethod", 2);
        
        List<JsonNode> bodies = List.of(
            objectMapper.readTree("{\"id\":1,\"name\":\"test\",\"timestamp\":\"2025-01-01T10:00:00Z\"}"),
            objectMapper.readTree("{\"id\":1,\"name\":\"test\",\"timestamp\":\"2025-01-01T10:00:01Z\"}")
        );
        
        JsonFieldDetector.detectDynamicFieldsInJson(bodies, result);
        
        assertEquals(1, result.getDynamicFields().size());
        assertEquals("json:timestamp", result.getDynamicFields().get(0).getFieldPath());
        assertEquals(1, result.getIgnorePatterns().size());
        assertTrue(result.getIgnorePatterns().contains("json:timestamp"));
    }

    @Test
    void testDetectDynamicFieldsInJson_MultipleChangingFields() throws Exception {
        DetectionResult result = new DetectionResult("TestClass", "testMethod", 2);
        
        List<JsonNode> bodies = List.of(
            objectMapper.readTree("{\"id\":1,\"timestamp\":\"2025-01-01T10:00:00Z\",\"requestId\":\"abc-123\"}"),
            objectMapper.readTree("{\"id\":1,\"timestamp\":\"2025-01-01T10:00:01Z\",\"requestId\":\"def-456\"}")
        );
        
        JsonFieldDetector.detectDynamicFieldsInJson(bodies, result);
        
        assertEquals(2, result.getDynamicFields().size());
        assertTrue(result.getIgnorePatterns().contains("json:timestamp"));
        assertTrue(result.getIgnorePatterns().contains("json:requestId"));
    }

    @Test
    void testDetectDynamicFieldsInJson_NoChangingFields() throws Exception {
        DetectionResult result = new DetectionResult("TestClass", "testMethod", 2);
        
        List<JsonNode> bodies = List.of(
            objectMapper.readTree("{\"id\":1,\"name\":\"test\"}"),
            objectMapper.readTree("{\"id\":1,\"name\":\"test\"}")
        );
        
        JsonFieldDetector.detectDynamicFieldsInJson(bodies, result);
        
        assertTrue(result.getDynamicFields().isEmpty());
        assertTrue(result.getIgnorePatterns().isEmpty());
    }

    @Test
    void testDetectDynamicFieldsInJson_NestedObjects() throws Exception {
        DetectionResult result = new DetectionResult("TestClass", "testMethod", 2);
        
        List<JsonNode> bodies = List.of(
            objectMapper.readTree("{\"user\":{\"id\":1,\"timestamp\":\"2025-01-01T10:00:00Z\"}}"),
            objectMapper.readTree("{\"user\":{\"id\":1,\"timestamp\":\"2025-01-01T10:00:01Z\"}}")
        );
        
        JsonFieldDetector.detectDynamicFieldsInJson(bodies, result);
        
        // Current implementation: when nested field changes, whole parent object is marked as dynamic
        // Recursion only happens when values are same, so we get "user" not "user.timestamp"
        assertTrue(result.getDynamicFields().size() >= 1);
        var fieldPaths = result.getDynamicFields().stream()
            .map(f -> f.getFieldPath())
            .toList();
        // The detector marks "user" as dynamic since the whole object differs
        assertTrue(fieldPaths.contains("json:user"),
            "Should detect user as dynamic when nested field changes. Found: " + fieldPaths);
    }

    @Test
    void testDetectDynamicFieldsInJson_Arrays() throws Exception {
        DetectionResult result = new DetectionResult("TestClass", "testMethod", 2);
        
        List<JsonNode> bodies = List.of(
            objectMapper.readTree("{\"items\":[{\"id\":1,\"timestamp\":\"2025-01-01T10:00:00Z\"}]}"),
            objectMapper.readTree("{\"items\":[{\"id\":1,\"timestamp\":\"2025-01-01T10:00:01Z\"}]}")
        );
        
        JsonFieldDetector.detectDynamicFieldsInJson(bodies, result);
        
        // Current implementation: when arrays differ, the whole array field is marked as dynamic
        // Arrays are compared as whole values, so if elements differ, the array itself is marked
        assertTrue(result.getDynamicFields().size() >= 1);
        var fieldPaths = result.getDynamicFields().stream()
            .map(f -> f.getFieldPath())
            .toList();
        // The detector marks items as dynamic since the arrays differ
        assertTrue(fieldPaths.contains("json:items"),
            "Should detect items as dynamic when array elements differ. Found: " + fieldPaths);
    }

    @Test
    void testDetectDynamicFieldsInJson_InsufficientBodies() throws Exception {
        DetectionResult result = new DetectionResult("TestClass", "testMethod", 1);
        
        List<JsonNode> bodies = List.of(
            objectMapper.readTree("{\"id\":1}")
        );
        
        JsonFieldDetector.detectDynamicFieldsInJson(bodies, result);
        
        assertTrue(result.getDynamicFields().isEmpty());
    }

    @Test
    void testDetectDynamicFieldsInJson_NullBodies() {
        DetectionResult result = new DetectionResult("TestClass", "testMethod", 0);
        
        JsonFieldDetector.detectDynamicFieldsInJson(null, result);
        
        assertTrue(result.getDynamicFields().isEmpty());
    }

    @Test
    void testDetectDynamicFieldsInJson_WithNullNodes() throws Exception {
        DetectionResult result = new DetectionResult("TestClass", "testMethod", 2);
        
        List<JsonNode> bodies = new ArrayList<>();
        bodies.add(objectMapper.readTree("{\"id\":1}"));
        bodies.add(null);
        bodies.add(objectMapper.readTree("{\"id\":2}"));
        
        JsonFieldDetector.detectDynamicFieldsInJson(bodies, result);
        
        assertEquals(1, result.getDynamicFields().size());
        assertEquals("json:id", result.getDynamicFields().get(0).getFieldPath());
    }

    @Test
    void testDetectDynamicFieldsInJson_ConfidenceLevels() throws Exception {
        DetectionResult result = new DetectionResult("TestClass", "testMethod", 5);
        
        List<JsonNode> bodies = List.of(
            objectMapper.readTree("{\"timestamp\":\"2025-01-01T10:00:00Z\"}"),
            objectMapper.readTree("{\"timestamp\":\"2025-01-01T10:00:01Z\"}"),
            objectMapper.readTree("{\"timestamp\":\"2025-01-01T10:00:02Z\"}"),
            objectMapper.readTree("{\"timestamp\":\"2025-01-01T10:00:03Z\"}"),
            objectMapper.readTree("{\"timestamp\":\"2025-01-01T10:00:04Z\"}")
        );
        
        JsonFieldDetector.detectDynamicFieldsInJson(bodies, result);
        
        assertEquals(1, result.getDynamicFields().size());
        assertEquals("HIGH", result.getDynamicFields().get(0).getConfidence());
    }

    @Test
    void testDetectDynamicFieldsInJson_SampleValuesLimit() throws Exception {
        DetectionResult result = new DetectionResult("TestClass", "testMethod", 5);
        
        List<JsonNode> bodies = List.of(
            objectMapper.readTree("{\"timestamp\":\"val1\"}"),
            objectMapper.readTree("{\"timestamp\":\"val2\"}"),
            objectMapper.readTree("{\"timestamp\":\"val3\"}"),
            objectMapper.readTree("{\"timestamp\":\"val4\"}"),
            objectMapper.readTree("{\"timestamp\":\"val5\"}")
        );
        
        JsonFieldDetector.detectDynamicFieldsInJson(bodies, result);
        
        assertEquals(1, result.getDynamicFields().size());
        assertEquals(3, result.getDynamicFields().get(0).getSampleValues().size());
    }
}

