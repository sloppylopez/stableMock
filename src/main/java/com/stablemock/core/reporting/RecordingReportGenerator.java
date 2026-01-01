package com.stablemock.core.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Generates a comprehensive report about recorded requests, mutating parameters,
 * and generated ignore patterns for StableMock recordings.
 */
public final class RecordingReportGenerator {

    private static final Logger logger = LoggerFactory.getLogger(RecordingReportGenerator.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private RecordingReportGenerator() {
        // utility class
    }

    /**
     * Generates a comprehensive recording report for all tests in the stablemock directory.
     * 
     * @param testResourcesDir Test resources directory (e.g., src/test/resources)
     * @param triggeringTestClass Optional test class name that triggered this report generation
     * @return The generated report as a JSON object, or null if no recordings found
     */
    public static ObjectNode generateReport(File testResourcesDir, String triggeringTestClass) {
        File stablemockDir = new File(testResourcesDir, "stablemock");
        
        if (!stablemockDir.exists() || !stablemockDir.isDirectory()) {
            logger.debug("No stablemock directory found at: {}", stablemockDir.getAbsolutePath());
            return null;
        }

        ObjectNode report = objectMapper.createObjectNode();
        report.put("generatedAt", java.time.Instant.now().toString());
        report.put("baseDirectory", stablemockDir.getAbsolutePath());
        if (triggeringTestClass != null) {
            report.put("triggeredBy", triggeringTestClass);
        }
        
        ArrayNode testClassesArray = report.putArray("testClasses");
        
        File[] testClassDirs = stablemockDir.listFiles(File::isDirectory);
        if (testClassDirs == null || testClassDirs.length == 0) {
            logger.debug("No test class directories found in stablemock");
            return report;
        }

        for (File testClassDir : testClassDirs) {
            String testClassName = testClassDir.getName();
            ObjectNode testClassNode = testClassesArray.addObject();
            testClassNode.put("testClass", testClassName);
            
            ArrayNode testMethodsArray = testClassNode.putArray("testMethods");
            
            File[] testMethodDirs = testClassDir.listFiles(File::isDirectory);
            if (testMethodDirs != null) {
                for (File testMethodDir : testMethodDirs) {
                    String testMethodName = testMethodDir.getName();
                    
                    // Skip WireMock internal directories
                    if ("mappings".equals(testMethodName) || "__files".equals(testMethodName)) {
                        continue;
                    }
                    
                    ObjectNode testMethodNode = processTestMethod(testMethodDir, testClassName, testMethodName);
                    if (testMethodNode != null) {
                        testMethodsArray.add(testMethodNode);
                    }
                }
            }
        }

        return report;
    }

    /**
     * Processes a single test method directory and extracts all relevant information.
     */
    private static ObjectNode processTestMethod(File testMethodDir, String testClassName, String testMethodName) {
        ObjectNode testMethodNode = objectMapper.createObjectNode();
        testMethodNode.put("testMethod", testMethodName);
        testMethodNode.put("folderPath", testMethodDir.getAbsolutePath());
        
        // Check for multiple annotations (annotation_0, annotation_1, etc.)
        File[] subDirs = testMethodDir.listFiles(File::isDirectory);
        boolean hasMultipleAnnotations = false;
        if (subDirs != null) {
            for (File subDir : subDirs) {
                if (subDir.getName().startsWith("annotation_")) {
                    hasMultipleAnnotations = true;
                    break;
                }
            }
        }
        
        if (hasMultipleAnnotations) {
            // Process multiple annotations
            ArrayNode annotationsArray = testMethodNode.putArray("annotations");
            for (File subDir : subDirs) {
                if (subDir.getName().startsWith("annotation_")) {
                    String annotationIndex = subDir.getName().substring("annotation_".length());
                    ObjectNode annotationNode = processAnnotationDirectory(subDir, testClassName, testMethodName, annotationIndex);
                    if (annotationNode != null) {
                        annotationsArray.add(annotationNode);
                    }
                }
            }
        } else {
            // Single annotation - process directly
            ObjectNode singleAnnotationNode = processAnnotationDirectory(testMethodDir, testClassName, testMethodName, null);
            if (singleAnnotationNode != null) {
                testMethodNode.set("annotation", singleAnnotationNode);
            }
        }
        
        return testMethodNode;
    }

    /**
     * Processes an annotation directory (either single annotation or annotation_X).
     */
    private static ObjectNode processAnnotationDirectory(File annotationDir, String testClassName, 
                                                         String testMethodName, String annotationIndex) {
        ObjectNode annotationNode = objectMapper.createObjectNode();
        if (annotationIndex != null) {
            annotationNode.put("annotationIndex", Integer.parseInt(annotationIndex));
        }
        
        // Read detected-fields.json if it exists
        File detectedFieldsFile = new File(annotationDir, "detected-fields.json");
        if (detectedFieldsFile.exists()) {
            try {
                JsonNode detectedFieldsJson = objectMapper.readTree(detectedFieldsFile);
                annotationNode.set("detectedFields", detectedFieldsJson);
                
                // Extract ignore patterns
                JsonNode ignorePatternsNode = detectedFieldsJson.get("ignore_patterns");
                if (ignorePatternsNode != null && ignorePatternsNode.isArray()) {
                    ArrayNode ignorePatternsArray = annotationNode.putArray("ignorePatterns");
                    ignorePatternsNode.forEach(pattern -> ignorePatternsArray.add(pattern.asText()));
                }
            } catch (IOException e) {
                logger.warn("Failed to read detected-fields.json for {}: {}", 
                        annotationDir.getAbsolutePath(), e.getMessage());
            }
        }
        
        // Process mapping files to extract request information
        File mappingsDir = new File(annotationDir, "mappings");
        if (mappingsDir.exists() && mappingsDir.isDirectory()) {
            ArrayNode requestsArray = annotationNode.putArray("requests");
            Map<String, RequestInfo> requestsByEndpoint = new LinkedHashMap<>();
            
            File[] mappingFiles = mappingsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
            if (mappingFiles != null) {
                for (File mappingFile : mappingFiles) {
                    try {
                        JsonNode mappingJson = objectMapper.readTree(mappingFile);
                        JsonNode requestNode = mappingJson.get("request");
                        
                        if (requestNode != null) {
                            String method = requestNode.has("method") ? requestNode.get("method").asText() : "UNKNOWN";
                            String url = extractUrl(requestNode);
                            String endpointKey = method + " " + url;
                            
                            RequestInfo requestInfo = requestsByEndpoint.computeIfAbsent(endpointKey, 
                                    k -> new RequestInfo(method, url));
                            
                            requestInfo.incrementCount();
                            
                            // Extract mutating fields from body patterns if available
                            JsonNode bodyPatterns = requestNode.get("bodyPatterns");
                            if (bodyPatterns != null && bodyPatterns.isArray()) {
                                for (JsonNode bodyPattern : bodyPatterns) {
                                    if (bodyPattern.has("equalToJson") || bodyPattern.has("equalTo")) {
                                        // This request has a body - we can note it
                                        requestInfo.setHasBody(true);
                                    }
                                }
                            }
                        }
                    } catch (IOException e) {
                        logger.debug("Failed to read mapping file {}: {}", mappingFile.getName(), e.getMessage());
                    }
                }
                
                // Convert to JSON array
                for (RequestInfo requestInfo : requestsByEndpoint.values()) {
                    ObjectNode requestNode = requestsArray.addObject();
                    requestNode.put("method", requestInfo.method);
                    requestNode.put("url", requestInfo.url);
                    requestNode.put("requestCount", requestInfo.count);
                    requestNode.put("hasBody", requestInfo.hasBody);
                    
                    // Map mutating fields to this endpoint if detected-fields.json exists
                    // Only include fields for requests that have bodies (POST, PUT, PATCH, etc.)
                    if (detectedFieldsFile.exists() && requestInfo.hasBody) {
                        try {
                            JsonNode detectedFieldsJson = objectMapper.readTree(detectedFieldsFile);
                            JsonNode dynamicFieldsNode = detectedFieldsJson.get("dynamic_fields");
                            if (dynamicFieldsNode != null && dynamicFieldsNode.isArray()) {
                                ArrayNode mutatingFieldsArray = requestNode.putArray("mutatingFields");
                                for (JsonNode fieldNode : dynamicFieldsNode) {
                                    String fieldPath = fieldNode.has("field_path") ? 
                                            fieldNode.get("field_path").asText() : null;
                                    if (fieldPath != null) {
                                        ObjectNode mutatingFieldNode = mutatingFieldsArray.addObject();
                                        mutatingFieldNode.put("fieldPath", fieldPath);
                                        mutatingFieldNode.put("confidence", 
                                                fieldNode.has("confidence") ? fieldNode.get("confidence").asText() : "UNKNOWN");
                                        
                                        if (fieldNode.has("sample_values") && fieldNode.get("sample_values").isArray()) {
                                            ArrayNode samplesArray = mutatingFieldNode.putArray("sampleValues");
                                            fieldNode.get("sample_values").forEach(sample -> samplesArray.add(sample.asText()));
                                        }
                                    }
                                }
                            }
                        } catch (IOException e) {
                            // Already logged above
                        }
                    }
                }
            }
        }
        
        return annotationNode;
    }

    /**
     * Extracts URL from a request node, handling different WireMock formats.
     */
    private static String extractUrl(JsonNode requestNode) {
        if (requestNode.has("url")) {
            JsonNode urlNode = requestNode.get("url");
            if (urlNode.isTextual()) {
                return urlNode.asText();
            } else if (urlNode.isObject()) {
                // URL matcher object
                if (urlNode.has("equalTo")) {
                    return urlNode.get("equalTo").asText();
                } else if (urlNode.has("matches")) {
                    return urlNode.get("matches").asText();
                } else if (urlNode.has("urlPath")) {
                    return urlNode.get("urlPath").asText();
                }
            }
        }
        if (requestNode.has("urlPath")) {
            return requestNode.get("urlPath").asText();
        }
        if (requestNode.has("urlPattern")) {
            return requestNode.get("urlPattern").asText();
        }
        return "/unknown";
    }


    /**
     * Saves the report to a JSON file and generates an HTML version.
     */
    public static void saveReport(ObjectNode report, File testResourcesDir) {
        if (report == null) {
            return;
        }
        
        File stablemockDir = new File(testResourcesDir, "stablemock");
        File jsonReportFile = new File(stablemockDir, "recording-report.json");
        File htmlReportFile = new File(stablemockDir, "recording-report.html");
        
        try {
            if (!stablemockDir.exists() && !stablemockDir.mkdirs()) {
                logger.warn("Failed to create stablemock directory for report");
                return;
            }
            
            // Save JSON report
            objectMapper.writeValue(jsonReportFile, report);
            logger.info("Recording report (JSON) saved to: {}", jsonReportFile.getAbsolutePath());
            
            // Generate HTML report
            HtmlReportGenerator.generateHtmlReport(report, htmlReportFile);
            logger.info("Recording report (HTML) saved to: {}", htmlReportFile.getAbsolutePath());
        } catch (IOException e) {
            logger.error("Failed to save recording report: {}", e.getMessage(), e);
        }
    }

    /**
     * Helper class to track request information by endpoint.
     */
    private static class RequestInfo {
        final String method;
        final String url;
        int count = 0;
        boolean hasBody = false;

        RequestInfo(String method, String url) {
            this.method = method;
            this.url = url;
        }

        void incrementCount() {
            count++;
        }

        void setHasBody(boolean hasBody) {
            this.hasBody = hasBody;
        }
    }
}

