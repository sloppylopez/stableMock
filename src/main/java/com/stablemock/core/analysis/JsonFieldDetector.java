package com.stablemock.core.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Detects dynamic fields in JSON request bodies by comparing multiple requests.
 * Identifies JSON fields whose values change across different executions.
 */
public final class JsonFieldDetector {

    private static final Logger logger = LoggerFactory.getLogger(JsonFieldDetector.class);

    private JsonFieldDetector() {
        // utility class
    }

    /**
     * Analyzes JSON request bodies to detect dynamic fields.
     * 
     * @param jsonBodies List of JsonNode objects from the same endpoint
     * @param result DetectionResult to populate with findings
     */
    public static void detectDynamicFieldsInJson(List<JsonNode> jsonBodies, DetectionResult result) {
        if (jsonBodies == null || jsonBodies.size() < 2) {
            logger.debug("Not enough JSON bodies to analyze (need at least 2, got {})", 
                    jsonBodies != null ? jsonBodies.size() : 0);
            return;
        }

        // Filter out null nodes (failed parses)
        List<JsonNode> validNodes = new ArrayList<>();
        for (JsonNode node : jsonBodies) {
            if (node != null) {
                validNodes.add(node);
            }
        }

        if (validNodes.size() < 2) {
            logger.debug("Not enough valid JSON bodies for analysis");
            return;
        }

        detectDynamicFieldsInJsonRecursive(validNodes, "", result);
    }

    /**
     * Recursively detects dynamic fields in JSON by comparing values across
     * multiple bodies.
     */
    private static void detectDynamicFieldsInJsonRecursive(List<JsonNode> nodes, String pathPrefix,
            DetectionResult result) {
        if (nodes.isEmpty()) {
            return;
        }

        JsonNode first = nodes.get(0);

        if (first.isObject()) {
            // Get all field names from all objects
            Set<String> allFieldNames = new LinkedHashSet<>();
            for (JsonNode node : nodes) {
                if (node.isObject()) {
                    node.fieldNames().forEachRemaining(allFieldNames::add);
                }
            }

            // Analyze each field
            for (String fieldName : allFieldNames) {
                List<JsonNode> fieldValues = new ArrayList<>();
                for (JsonNode node : nodes) {
                    if (node.isObject() && node.has(fieldName)) {
                        fieldValues.add(node.get(fieldName));
                    }
                }

                if (fieldValues.size() >= 2) {
                    String currentPath = pathPrefix.isEmpty() ? fieldName : pathPrefix + "." + fieldName;

                    // Check if all values are the same
                    boolean allSame = true;
                    String firstValueStr = fieldValues.get(0).toString();
                    for (int i = 1; i < fieldValues.size(); i++) {
                        if (!firstValueStr.equals(fieldValues.get(i).toString())) {
                            allSame = false;
                            break;
                        }
                    }

                    if (!allSame) {
                        // This field has changing values
                        // If it's a primitive (string, number, boolean, null), mark it as dynamic
                        // If it's an object or array, recurse to find the specific nested fields that are changing
                        JsonNode firstValueNode = fieldValues.get(0);
                        boolean isComplexType = firstValueNode.isObject() || firstValueNode.isArray();
                        
                        logger.debug("Field {} has changing values. isObject: {}, isArray: {}, isComplexType: {}", 
                                currentPath, firstValueNode.isObject(), firstValueNode.isArray(), isComplexType);
                        
                        if (isComplexType) {
                            // It's a complex type - recurse to find specific nested dynamic fields
                            // Don't add the parent field, only add the specific nested fields that are changing
                            logger.info("Field {} is a complex type (object/array), recursing to find nested dynamic fields instead of marking parent as dynamic", currentPath);
                            detectDynamicFieldsInJsonRecursive(fieldValues, currentPath, result);
                        } else {
                            // It's a primitive type - mark it as dynamic
                            List<String> sampleValues = new ArrayList<>();
                            for (JsonNode value : fieldValues) {
                                if (value.isTextual()) {
                                    sampleValues.add(value.asText());
                                } else {
                                    sampleValues.add(value.toString());
                                }
                            }

                            // Limit sample values to first 3
                            if (sampleValues.size() > 3) {
                                sampleValues = sampleValues.subList(0, 3);
                            }

                            String confidence = ConfidenceCalculator.calculateConfidence(fieldValues.size());
                            String jsonPath = "json:" + currentPath;

                            result.addDynamicField(new DetectionResult.DynamicField(
                                    jsonPath, confidence, sampleValues));
                            result.addIgnorePattern(jsonPath);

                            logger.info("Detected dynamic JSON field: {} (confidence: {}, samples: {})",
                                    jsonPath, confidence, sampleValues.size());
                        }
                    } else if (fieldValues.get(0).isObject() || fieldValues.get(0).isArray()) {
                        // Values are same, but recurse to check nested structure
                        detectDynamicFieldsInJsonRecursive(fieldValues, currentPath, result);
                    }
                }
            }
        } else if (first.isArray()) {
            // For arrays, we compare elements at the same index
            int minSize = Integer.MAX_VALUE;
            for (JsonNode node : nodes) {
                if (node.isArray()) {
                    minSize = Math.min(minSize, node.size());
                }
            }

            // Analyze common indices
            for (int i = 0; i < minSize; i++) {
                List<JsonNode> elementsAtIndex = new ArrayList<>();
                for (JsonNode node : nodes) {
                    if (node.isArray() && node.size() > i) {
                        elementsAtIndex.add(node.get(i));
                    }
                }

                if (elementsAtIndex.size() >= 2) {
                    String currentPath = pathPrefix + "[" + i + "]";
                    detectDynamicFieldsInJsonRecursive(elementsAtIndex, currentPath, result);
                }
            }
        }
    }

}

