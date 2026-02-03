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

    @Test
    void testDetectDynamicFieldsInJson_HeuristicDetection_SameDateValues() throws Exception {
        DetectionResult result = new DetectionResult("TestClass", "testMethod", 2);
        
        // Same date values (as would happen in a single test run)
        List<JsonNode> bodies = List.of(
            objectMapper.readTree("{\"startDate\":\"2025-02-23\",\"endDate\":\"2025-02-24\"}"),
            objectMapper.readTree("{\"startDate\":\"2025-02-23\",\"endDate\":\"2025-02-24\"}")
        );
        
        JsonFieldDetector.detectDynamicFieldsInJson(bodies, result);
        
        // Should detect startDate and endDate via heuristic even though values are same
        assertEquals(2, result.getDynamicFields().size());
        assertEquals(2, result.getIgnorePatterns().size());
        
        var fieldPaths = result.getDynamicFields().stream()
            .map(f -> f.fieldPath())
            .toList();
        assertTrue(fieldPaths.contains("json:startDate"),
            "Should detect startDate via heuristic. Found: " + fieldPaths);
        assertTrue(fieldPaths.contains("json:endDate"),
            "Should detect endDate via heuristic. Found: " + fieldPaths);
    }

    @Test
    void testDetectDynamicFieldsInJson_HeuristicDetection_Timestamp() throws Exception {
        DetectionResult result = new DetectionResult("TestClass", "testMethod", 2);
        
        List<JsonNode> bodies = List.of(
            objectMapper.readTree("{\"timestamp\":\"2025-02-23T10:00:00Z\"}"),
            objectMapper.readTree("{\"timestamp\":\"2025-02-23T10:00:00Z\"}")
        );
        
        JsonFieldDetector.detectDynamicFieldsInJson(bodies, result);
        
        // Should detect timestamp via heuristic
        assertEquals(1, result.getDynamicFields().size());
        assertEquals("json:timestamp", result.getDynamicFields().get(0).fieldPath());
    }

    @Test
    void testDetectDynamicFieldsInJson_HeuristicDetection_StartEnd() throws Exception {
        DetectionResult result = new DetectionResult("TestClass", "testMethod", 2);
        
        List<JsonNode> bodies = List.of(
            objectMapper.readTree("{\"start\":\"2025-02-23\",\"end\":\"2025-02-24\"}"),
            objectMapper.readTree("{\"start\":\"2025-02-23\",\"end\":\"2025-02-24\"}")
        );
        
        JsonFieldDetector.detectDynamicFieldsInJson(bodies, result);
        
        // Should detect start and end via heuristic
        assertEquals(2, result.getDynamicFields().size());
        var fieldPaths = result.getDynamicFields().stream()
            .map(f -> f.fieldPath())
            .toList();
        assertTrue(fieldPaths.contains("json:start"),
            "Should detect start via heuristic. Found: " + fieldPaths);
        assertTrue(fieldPaths.contains("json:end"),
            "Should detect end via heuristic. Found: " + fieldPaths);
    }

    @Test
    void testDetectDynamicFieldsInJson_HeuristicDetection_NoFalsePositive() throws Exception {
        DetectionResult result = new DetectionResult("TestClass", "testMethod", 2);
        
        // Field names don't suggest date/time, values are same - should NOT be detected
        List<JsonNode> bodies = List.of(
            objectMapper.readTree("{\"id\":123,\"name\":\"test\"}"),
            objectMapper.readTree("{\"id\":123,\"name\":\"test\"}")
        );
        
        JsonFieldDetector.detectDynamicFieldsInJson(bodies, result);
        
        // Should NOT detect id or name as dynamic (no heuristic match, values are same)
        assertTrue(result.getDynamicFields().isEmpty());
        assertTrue(result.getIgnorePatterns().isEmpty());
    }

    @Test
    void testDetectDynamicFieldsInJson_HeuristicDetection_RequestId() throws Exception {
        DetectionResult result = new DetectionResult("TestClass", "testMethod", 2);
        
        List<JsonNode> bodies = List.of(
            objectMapper.readTree("{\"requestId\":\"abc-123\"}"),
            objectMapper.readTree("{\"requestId\":\"abc-123\"}")
        );
        
        JsonFieldDetector.detectDynamicFieldsInJson(bodies, result);
        
        // Should detect requestId via heuristic
        assertEquals(1, result.getDynamicFields().size());
        assertEquals("json:requestId", result.getDynamicFields().get(0).fieldPath());
    }

    @Test
    void testDetectDynamicFieldsInJson_HeuristicDetection_SessionToken() throws Exception {
        DetectionResult result = new DetectionResult("TestClass", "testMethod", 2);
        
        List<JsonNode> bodies = List.of(
            objectMapper.readTree("{\"sessionToken\":\"token-123\"}"),
            objectMapper.readTree("{\"sessionToken\":\"token-123\"}")
        );
        
        JsonFieldDetector.detectDynamicFieldsInJson(bodies, result);
        
        // Should detect sessionToken via heuristic
        assertEquals(1, result.getDynamicFields().size());
        assertEquals("json:sessionToken", result.getDynamicFields().get(0).fieldPath());
    }

    @Test
    void testDetectDynamicFieldsInJson_HeuristicDetection_NestedDateField() throws Exception {
        DetectionResult result = new DetectionResult("TestClass", "testMethod", 2);
        
        List<JsonNode> bodies = List.of(
            objectMapper.readTree("{\"search\":{\"startDate\":\"2025-02-23\",\"endDate\":\"2025-02-24\"}}"),
            objectMapper.readTree("{\"search\":{\"startDate\":\"2025-02-23\",\"endDate\":\"2025-02-24\"}}")
        );
        
        JsonFieldDetector.detectDynamicFieldsInJson(bodies, result);
        
        // Should detect nested startDate and endDate via heuristic
        assertEquals(2, result.getDynamicFields().size());
        var fieldPaths = result.getDynamicFields().stream()
            .map(f -> f.fieldPath())
            .toList();
        assertTrue(fieldPaths.contains("json:search.startDate"),
            "Should detect nested startDate via heuristic. Found: " + fieldPaths);
        assertTrue(fieldPaths.contains("json:search.endDate"),
            "Should detect nested endDate via heuristic. Found: " + fieldPaths);
    }
}

