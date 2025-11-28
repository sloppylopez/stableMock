package com.stablemock.core.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Detects dynamic fields in JSON request bodies by comparing multiple requests.
 * Identifies fields whose values change across different executions.
 */
public final class DynamicFieldDetector {

    private static final Logger logger = LoggerFactory.getLogger(DynamicFieldDetector.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private DynamicFieldDetector() {
        // utility class
    }

    /**
     * Analyzes a list of request snapshots to detect dynamic fields.
     * 
     * @param requests       List of request snapshots from the same endpoint
     * @param testClassName  Name of the test class
     * @param testMethodName Name of the test method
     * @param newRequestsCount Number of new requests analyzed in this run (for reporting)
     * @return DetectionResult containing identified dynamic fields and ignore
     *         patterns
     */
    public static DetectionResult analyzeRequests(List<RequestSnapshot> requests,
            String testClassName,
            String testMethodName,
            int newRequestsCount) {
        // Use total history size for analyzed_requests_count - this represents the total
        // number of requests that were analyzed to detect patterns. This is more meaningful
        // because:
        // 1. The analysis actually uses ALL requests in history to detect patterns
        // 2. It indicates confidence/reliability (more requests = higher confidence)
        // 3. It shows the actual data size used for detection
        // 
        // The count will grow as history accumulates (up to MAX_HISTORY_SIZE=10), which is
        // expected behavior - it shows the detection is based on more data over time.
        DetectionResult result = new DetectionResult(testClassName, testMethodName, requests.size());

        if (requests.size() < 2) {
            logger.debug("Not enough requests to analyze (need at least 2, got {})", requests.size());
            return result;
        }

        // Group requests by URL and method
        Map<String, List<RequestSnapshot>> requestsByEndpoint = groupByEndpoint(requests);

        for (Map.Entry<String, List<RequestSnapshot>> entry : requestsByEndpoint.entrySet()) {
            List<RequestSnapshot> endpointRequests = entry.getValue();

            if (endpointRequests.size() < 2) {
                continue;
            }

            // Analyze JSON bodies
            List<JsonNode> jsonBodies = new ArrayList<>();
            for (RequestSnapshot request : endpointRequests) {
                try {
                    if (request.getBody() != null && !request.getBody().trim().isEmpty()) {
                        JsonNode node = objectMapper.readTree(request.getBody());
                        jsonBodies.add(node);
                    }
                } catch (Exception e) {
                    logger.debug("Failed to parse JSON body: {}", e.getMessage());
                }
            }

            if (jsonBodies.size() >= 2) {
                detectDynamicFieldsInJson(jsonBodies, "", result);
            }
        }

        return result;
    }

    /**
     * Groups requests by endpoint (URL + method).
     */
    private static Map<String, List<RequestSnapshot>> groupByEndpoint(List<RequestSnapshot> requests) {
        Map<String, List<RequestSnapshot>> groups = new LinkedHashMap<>();

        for (RequestSnapshot request : requests) {
            String key = request.getMethod() + " " + request.getUrl();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(request);
        }

        return groups;
    }

    /**
     * Recursively detects dynamic fields in JSON by comparing values across
     * multiple bodies.
     */
    private static void detectDynamicFieldsInJson(List<JsonNode> nodes, String pathPrefix,
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
                    String firstValue = fieldValues.get(0).toString();
                    for (int i = 1; i < fieldValues.size(); i++) {
                        if (!firstValue.equals(fieldValues.get(i).toString())) {
                            allSame = false;
                            break;
                        }
                    }

                    if (!allSame) {
                        // This field has changing values - it's dynamic!
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

                        String confidence = calculateConfidence(fieldValues.size());
                        String jsonPath = "json:" + currentPath;

                        result.addDynamicField(new DetectionResult.DynamicField(
                                jsonPath, confidence, sampleValues));
                        result.addIgnorePattern(jsonPath);

                        logger.info("Detected dynamic field: {} (confidence: {}, samples: {})",
                                jsonPath, confidence, sampleValues.size());
                    } else if (fieldValues.get(0).isObject() || fieldValues.get(0).isArray()) {
                        // Recurse into nested objects/arrays
                        detectDynamicFieldsInJson(fieldValues, currentPath, result);
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
                    detectDynamicFieldsInJson(elementsAtIndex, currentPath, result);
                }
            }
        }
    }

    /**
     * Calculates confidence level based on sample size.
     */
    private static String calculateConfidence(int sampleSize) {
        if (sampleSize >= 5) {
            return "HIGH";
        } else if (sampleSize >= 3) {
            return "MEDIUM";
        } else {
            return "LOW";
        }
    }
}
