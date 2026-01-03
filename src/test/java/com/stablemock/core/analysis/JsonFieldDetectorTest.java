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
        assertEquals("json:timestamp", result.getDynamicFields().get(0).fieldPath());
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
        
        // Updated implementation: when nested field changes, we recurse into the object
        // to find the specific nested field that is changing, not the whole parent object
        assertTrue(result.getDynamicFields().size() >= 1);
        var fieldPaths = result.getDynamicFields().stream()
            .map(f -> f.fieldPath())
            .toList();
        // The detector should find the specific nested field "user.timestamp", not the whole "user" object
        assertTrue(fieldPaths.contains("json:user.timestamp"),
            "Should detect user.timestamp as dynamic when nested field changes. Found: " + fieldPaths);
        // Should NOT mark the whole user object as dynamic
        assertFalse(fieldPaths.contains("json:user"),
            "Should NOT mark the whole user object as dynamic. Found: " + fieldPaths);
    }

    @Test
    void testDetectDynamicFieldsInJson_Arrays() throws Exception {
        DetectionResult result = new DetectionResult("TestClass", "testMethod", 2);
        
        List<JsonNode> bodies = List.of(
            objectMapper.readTree("{\"items\":[{\"id\":1,\"timestamp\":\"2025-01-01T10:00:00Z\"}]}"),
            objectMapper.readTree("{\"items\":[{\"id\":1,\"timestamp\":\"2025-01-01T10:00:01Z\"}]}")
        );
        
        JsonFieldDetector.detectDynamicFieldsInJson(bodies, result);
        
        // Updated implementation: when arrays differ, we recurse into the array elements
        // to find the specific nested field that is changing, not the whole array
        assertTrue(result.getDynamicFields().size() >= 1);
        var fieldPaths = result.getDynamicFields().stream()
            .map(f -> f.fieldPath())
            .toList();
        // The detector should find the specific nested field "items[0].timestamp", not the whole "items" array
        assertTrue(fieldPaths.contains("json:items[0].timestamp"),
            "Should detect items[0].timestamp as dynamic when array element field changes. Found: " + fieldPaths);
        // Should NOT mark the whole items array as dynamic
        assertFalse(fieldPaths.contains("json:items"),
            "Should NOT mark the whole items array as dynamic. Found: " + fieldPaths);
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
        assertEquals("json:id", result.getDynamicFields().get(0).fieldPath());
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
        assertEquals(3, result.getDynamicFields().get(0).sampleValues().size());
    }
}

