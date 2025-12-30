package com.stablemock.core.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Orchestrates dynamic field detection across multiple request formats.
 * Routes requests to specialized detectors (JSON, XML) based on content type.
 * This class acts as a coordinator, delegating actual detection logic to format-specific detectors.
 */
public final class DynamicFieldDetector {

    private static final Logger logger = LoggerFactory.getLogger(DynamicFieldDetector.class);

    private DynamicFieldDetector() {
        // utility class
    }

    /**
     * Analyzes a list of request snapshots to detect dynamic fields.
     * 
     * @param requests       List of request snapshots from the same endpoint
     * @param testClassName  Name of the test class
     * @param testMethodName Name of the test method
     * @return DetectionResult containing identified dynamic fields and ignore
     *         patterns
     */
    public static DetectionResult analyzeRequests(List<RequestSnapshot> requests,
            String testClassName,
            String testMethodName) {
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

            // Separate requests by format (JSON vs XML)
            List<RequestSnapshot> jsonRequests = new ArrayList<>();
            List<RequestSnapshot> xmlRequests = new ArrayList<>();

            for (RequestSnapshot request : endpointRequests) {
                if (request.getBody() == null || request.getBody().trim().isEmpty()) {
                    continue;
                }

                // Determine format: check Content-Type first, then body content
                boolean isXml = false;
                boolean isJson = false;
                
                if (request.getContentType() != null) {
                    isXml = XmlBodyParser.isXmlContentType(request.getContentType());
                    isJson = JsonBodyParser.isJsonContentType(request.getContentType());
                }
                
                // Fallback: check body content if Content-Type not available or ambiguous
                if (!isXml && !isJson) {
                    // Try to detect from body content
                    boolean bodyIsXml = XmlBodyParser.isXml(request.getBody());
                    boolean bodyIsJson = JsonBodyParser.isJson(request.getBody());
                    //TODO if we are able to distinguish between json and xml here, why do we use a try catch in the other code block annotated with a TODO?
                    // If both detect, prefer Content-Type if available, otherwise prefer JSON
                    if (bodyIsXml && bodyIsJson) {
                        // Ambiguous - default to JSON for backward compatibility
                        isJson = true;
                    } else if (bodyIsXml) {
                        isXml = true;
                    } else if (bodyIsJson) {
                        isJson = true;
                    }
                    // If neither detected and no Content-Type, default to JSON for backward compatibility
                    // (old behavior assumed JSON)
                    if (!isXml && !isJson && request.getContentType() == null) {
                        isJson = true;
                    }
                }

                if (isXml) {
                    xmlRequests.add(request);
                } else if (isJson) {
                    jsonRequests.add(request);
                }
                // If neither XML nor JSON detected, skip (could be form data, binary, etc.)
            }

            // Analyze JSON requests
            if (jsonRequests.size() >= 2) {
                List<String> jsonBodyStrings = new ArrayList<>();
                for (RequestSnapshot request : jsonRequests) {
                    jsonBodyStrings.add(request.getBody());
                }
                List<com.fasterxml.jackson.databind.JsonNode> jsonBodies = JsonBodyParser.parseAllJsonBodies(jsonBodyStrings);
                
                // Filter out null nodes (failed parses)
                List<com.fasterxml.jackson.databind.JsonNode> validJsonBodies = new ArrayList<>();
                for (com.fasterxml.jackson.databind.JsonNode node : jsonBodies) {
                    if (node != null) {
                        validJsonBodies.add(node);
                    }
                }
                
                if (validJsonBodies.size() >= 2) {
                    JsonFieldDetector.detectDynamicFieldsInJson(validJsonBodies, result);
                }
            }

            // Analyze XML requests
            if (xmlRequests.size() >= 2) {
                List<String> xmlBodies = new ArrayList<>();
                for (RequestSnapshot request : xmlRequests) {
                    xmlBodies.add(request.getBody());
                }
                XmlFieldDetector.detectDynamicFieldsInXml(xmlBodies, result);
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
}
