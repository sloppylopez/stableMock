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
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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
            try {
                int index = Integer.parseInt(annotationIndex);
                annotationNode.put("annotationIndex", index);
            } catch (NumberFormatException e) {
                logger.warn("Invalid annotation index '{}' for {}.{} in directory {}. Skipping numeric index.",
                        annotationIndex, testClassName, testMethodName, annotationDir.getAbsolutePath());
            }
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

                            JsonNode responseNode = mappingJson.get("response");
                            ObjectNode exampleNode = buildRequestExample(requestNode, responseNode, annotationDir, mappingFile);
                            if (exampleNode != null) {
                                requestInfo.addExample(exampleNode);
                            }
                            
                            // Extract mutating fields from body patterns if available
                            JsonNode bodyPatterns = requestNode.get("bodyPatterns");
                            if (bodyPatterns != null && bodyPatterns.isArray()) {
                                for (JsonNode bodyPattern : bodyPatterns) {
                                    if (bodyPattern.has("equalToJson") || bodyPattern.has("equalTo") || bodyPattern.has("equalToXml")) {
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

                    if (!requestInfo.examples.isEmpty()) {
                        ArrayNode examplesArray = requestNode.putArray("examples");
                        for (ObjectNode example : requestInfo.examples) {
                            examplesArray.add(example);
                        }
                    }
                    
                    // Map mutating fields to this endpoint if detected-fields.json exists
                    // Only include fields for requests that have bodies (POST, PUT, PATCH, etc.)
                    // IMPORTANT: Include ALL field types (JSON, XML, GraphQL) - no filtering by type
                    if (detectedFieldsFile.exists() && requestInfo.hasBody) {
                        try {
                            JsonNode detectedFieldsJson = objectMapper.readTree(detectedFieldsFile);
                            JsonNode dynamicFieldsNode = detectedFieldsJson.get("dynamic_fields");
                            if (dynamicFieldsNode != null && dynamicFieldsNode.isArray()) {
                                ArrayNode mutatingFieldsArray = requestNode.putArray("mutatingFields");
                                int jsonFieldCount = 0;
                                int xmlFieldCount = 0;
                                int otherFieldCount = 0;
                                
                                for (JsonNode fieldNode : dynamicFieldsNode) {
                                    String fieldPath = fieldNode.has("field_path") ? 
                                            fieldNode.get("field_path").asText() : null;
                                    if (fieldPath != null) {
                                        // Count field types for logging
                                        if (fieldPath.startsWith("json:")) {
                                            jsonFieldCount++;
                                        } else if (fieldPath.startsWith("xml:") || fieldPath.startsWith("xml://")) {
                                            xmlFieldCount++;
                                        } else {
                                            otherFieldCount++;
                                        }
                                        
                                        // Add ALL fields to the report (JSON, XML, GraphQL, etc.) - no filtering
                                        ObjectNode mutatingFieldNode = mutatingFieldsArray.addObject();
                                        mutatingFieldNode.put("fieldPath", fieldPath);
                                        
                                        if (fieldNode.has("sample_values") && fieldNode.get("sample_values").isArray()) {
                                            ArrayNode samplesArray = mutatingFieldNode.putArray("sampleValues");
                                            fieldNode.get("sample_values").forEach(sample -> samplesArray.add(sample.asText()));
                                        }
                                    }
                                }
                                
                                if (logger.isDebugEnabled() && mutatingFieldsArray.size() > 0) {
                                    logger.debug("Added {} mutating fields to report for {} {} ({} JSON, {} XML, {} other)", 
                                            mutatingFieldsArray.size(), requestInfo.method, requestInfo.url, 
                                            jsonFieldCount, xmlFieldCount, otherFieldCount);
                                }
                            }
                        } catch (IOException e) {
                            logger.debug("Failed to read detected fields file {}: {}", detectedFieldsFile.getName(), e.getMessage());
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

    private static ObjectNode buildRequestExample(JsonNode requestNode, JsonNode responseNode, File annotationDir, File mappingFile) {
        if (requestNode == null && responseNode == null) {
            return null;
        }

        ObjectNode exampleNode = objectMapper.createObjectNode();
        
        // Store mapping file name for reference
        if (mappingFile != null) {
            exampleNode.put("mappingFile", mappingFile.getName());
        }
        if (requestNode != null) {
            ObjectNode requestExample = exampleNode.putObject("request");
            if (requestNode.has("headers") && requestNode.get("headers").isObject()) {
                requestExample.set("headers", requestNode.get("headers"));
            }

            // Always preserve bodyFileName if it exists, even if body is also present
            if (requestNode.has("bodyFileName")) {
                String bodyFileName = requestNode.get("bodyFileName").asText();
                requestExample.put("bodyFileName", bodyFileName);
                // If body is not present, read from file
                if (!requestNode.has("body") && !requestNode.has("bodyPatterns")) {
                    String fileContents = readBodyFile(annotationDir, bodyFileName);
                    if (fileContents != null) {
                        requestExample.put("body", fileContents);
                        JsonNode requestBodyJson = parseJsonSafely(fileContents);
                        if (requestBodyJson != null) {
                            requestExample.set("bodyJson", requestBodyJson);
                        }
                    }
                }
            }

            String requestBody = extractRequestBody(requestNode);
            if (requestBody != null) {
                requestExample.put("body", requestBody);
                JsonNode requestBodyJson = parseJsonSafely(requestBody);
                if (requestBodyJson != null) {
                    requestExample.set("bodyJson", requestBodyJson);
                }
                
                // If bodyFileName is not set, try to find a matching file
                if (!requestNode.has("bodyFileName")) {
                    String matchingFileName = findMatchingBodyFile(annotationDir, requestBody);
                    if (matchingFileName != null) {
                        requestExample.put("bodyFileName", matchingFileName);
                    } else {
                        // Body is stored inline in the mapping file
                        if (mappingFile != null) {
                            requestExample.put("bodySource", "mapping:" + mappingFile.getName());
                        }
                    }
                }
            }
        }

        if (responseNode != null) {
            ObjectNode responseExample = exampleNode.putObject("response");
            if (responseNode.has("status")) {
                responseExample.put("status", responseNode.get("status").asInt());
            }
            if (responseNode.has("headers") && responseNode.get("headers").isObject()) {
                responseExample.set("headers", responseNode.get("headers"));
            }

            // Always preserve bodyFileName if it exists, even if body/jsonBody is also present
            if (responseNode.has("bodyFileName")) {
                String bodyFileName = responseNode.get("bodyFileName").asText();
                responseExample.put("bodyFileName", bodyFileName);
                // If body/jsonBody is not present, read from file
                if (!responseNode.has("jsonBody") && !responseNode.has("body")) {
                    String fileContents = readBodyFile(annotationDir, bodyFileName);
                    if (fileContents != null) {
                        responseExample.put("body", fileContents);
                        JsonNode bodyJson = parseJsonSafely(fileContents);
                        if (bodyJson != null) {
                            responseExample.set("bodyJson", bodyJson);
                        }
                    }
                }
            }
            
            String responseBody = null;
            if (responseNode.has("jsonBody")) {
                JsonNode jsonBody = responseNode.get("jsonBody");
                responseExample.set("bodyJson", jsonBody);
                try {
                    responseBody = objectMapper.writeValueAsString(jsonBody);
                } catch (IOException e) {
                    responseBody = jsonBody.toString();
                }
            } else if (responseNode.has("body")) {
                responseBody = responseNode.get("body").asText();
                responseExample.put("body", responseBody);
                JsonNode bodyJson = parseJsonSafely(responseBody);
                if (bodyJson != null) {
                    responseExample.set("bodyJson", bodyJson);
                }
            }
            
            // If bodyFileName is not set, try to find a matching file
            if (!responseNode.has("bodyFileName") && responseBody != null) {
                String matchingFileName = findMatchingBodyFile(annotationDir, responseBody);
                if (matchingFileName != null) {
                    responseExample.put("bodyFileName", matchingFileName);
                } else {
                    // Body is stored inline in the mapping file
                    if (mappingFile != null) {
                        responseExample.put("bodySource", "mapping:" + mappingFile.getName());
                    }
                }
            }
        }

        return exampleNode;
    }

    private static String extractRequestBody(JsonNode requestNode) {
        if (requestNode.has("body")) {
            JsonNode bodyNode = requestNode.get("body");
            return bodyNode.isTextual() ? bodyNode.asText() : bodyNode.toString();
        }

        if (requestNode.has("bodyPatterns") && requestNode.get("bodyPatterns").isArray()) {
            for (JsonNode bodyPattern : requestNode.get("bodyPatterns")) {
                if (bodyPattern.has("equalToJson")) {
                    JsonNode bodyNode = bodyPattern.get("equalToJson");
                    return bodyNode.isTextual() ? bodyNode.asText() : bodyNode.toString();
                }
                if (bodyPattern.has("equalToXml")) {
                    JsonNode bodyNode = bodyPattern.get("equalToXml");
                    return bodyNode.isTextual() ? bodyNode.asText() : bodyNode.toString();
                }
                if (bodyPattern.has("equalTo")) {
                    JsonNode bodyNode = bodyPattern.get("equalTo");
                    return bodyNode.isTextual() ? bodyNode.asText() : bodyNode.toString();
                }
            }
        }
        return null;
    }

    private static JsonNode parseJsonSafely(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(rawJson);
        } catch (IOException e) {
            return null;
        }
    }

    private static String readBodyFile(File annotationDir, String bodyFileName) {
        if (annotationDir == null || bodyFileName == null || bodyFileName.isBlank()) {
            return null;
        }
        File filesDir = new File(annotationDir, "__files");
        File bodyFile = new File(filesDir, bodyFileName);
        
        try {
            File canonicalFilesDir = filesDir.getCanonicalFile();
            File canonicalBodyFile = bodyFile.getCanonicalFile();
            String basePath = canonicalFilesDir.getPath();
            String targetPath = canonicalBodyFile.getPath();
            
            if (!targetPath.startsWith(basePath + File.separator) && !targetPath.equals(basePath)) {
                logger.debug("Rejected response body file outside of base directory: {}", targetPath);
                return null;
            }
            
            if (!canonicalBodyFile.exists() || !canonicalBodyFile.isFile()) {
                return null;
            }
            
            return Files.readString(canonicalBodyFile.toPath());
        } catch (IOException e) {
            logger.debug("Failed to read response body file {}: {}", bodyFile.getAbsolutePath(), e.getMessage());
            return null;
        }
    }
    
    private static String findMatchingBodyFile(File annotationDir, String bodyContent) {
        if (annotationDir == null || bodyContent == null || bodyContent.isBlank()) {
            return null;
        }
        
        // Normalize body content for comparison (handle JSON formatting differences)
        String normalizedBody = normalizeBodyContent(bodyContent);
        
        // Check multiple possible locations for body files
        java.util.List<File> possibleDirs = new java.util.ArrayList<>();
        
        // Primary location: annotationDir/__files
        File filesDir = new File(annotationDir, "__files");
        if (filesDir.exists() && filesDir.isDirectory()) {
            possibleDirs.add(filesDir);
        }
        
        // Check parent directories (for cases where files might be at class or base level)
        File parentDir = annotationDir.getParentFile();
        if (parentDir != null) {
            File parentFilesDir = new File(parentDir, "__files");
            if (parentFilesDir.exists() && parentFilesDir.isDirectory()) {
                possibleDirs.add(parentFilesDir);
            }
            
            // Check base directory
            File baseDir = parentDir.getParentFile();
            if (baseDir != null) {
                File baseFilesDir = new File(baseDir, "__files");
                if (baseFilesDir.exists() && baseFilesDir.isDirectory()) {
                    possibleDirs.add(baseFilesDir);
                }
            }
        }
        
        // Search all possible directories
        for (File dir : possibleDirs) {
            File[] files = dir.listFiles();
            if (files == null) {
                continue;
            }
            
            for (File file : files) {
                if (!file.isFile()) {
                    continue;
                }
                
                try {
                    String fileContent = Files.readString(file.toPath());
                    String normalizedFileContent = normalizeBodyContent(fileContent);
                    
                    if (normalizedBody.equals(normalizedFileContent)) {
                        return file.getName();
                    }
                } catch (IOException e) {
                    // Skip files we can't read
                    continue;
                }
            }
        }
        
        return null;
    }
    
    private static String normalizeBodyContent(String content) {
        if (content == null) {
            return "";
        }
        
        // Try to parse as JSON and re-serialize to normalize formatting
        try {
            JsonNode jsonNode = objectMapper.readTree(content);
            return objectMapper.writeValueAsString(jsonNode);
        } catch (IOException e) {
            // Not JSON, return trimmed content
            return content.trim();
        }
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
            
            // Copy logo image to stablemock directory for HTML report
            copyLogoImage(stablemockDir);
            
            // Generate HTML report
            HtmlReportGenerator.generateHtmlReport(report, htmlReportFile);
            logger.info("Recording report (HTML) saved to: {}", htmlReportFile.getAbsolutePath());
        } catch (IOException e) {
            logger.error("Failed to save recording report: {}", e.getMessage(), e);
        }
    }

    /**
     * Copies the StableMock logo image to the stablemock directory for use in HTML reports.
     */
    private static void copyLogoImage(File stablemockDir) {
        try {
            // Try to find logo relative to test resources directory first
            File testResourcesDir = stablemockDir.getParentFile();
            if (testResourcesDir != null) {
                File logoSource = new File(testResourcesDir, "images/stablemock-logo-transparent-outline.png");
                if (logoSource.exists() && logoSource.isFile()) {
                    File logoFile = new File(stablemockDir, "stablemock-logo-transparent-outline.png");
                    Files.copy(logoSource.toPath(), logoFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    logger.info("Logo image copied from test resources to: {}", logoFile.getAbsolutePath());
                    return;
                }
            }
            
            // Try to find logo in the project root's test resources (for when running from examples)
            // Walk up from stablemock directory: stablemock -> test/resources -> src/test/resources -> .../src/test/resources/images
            File currentDir = stablemockDir.getAbsoluteFile();
            int maxDepth = 15; // Increased depth to handle nested project structures
            int depth = 0;
            while (currentDir != null && depth < maxDepth) {
                File possibleLogo = new File(currentDir, "src/test/resources/images/stablemock-logo-transparent-outline.png");
                if (possibleLogo.exists() && possibleLogo.isFile()) {
                    File logoFile = new File(stablemockDir, "stablemock-logo-transparent-outline.png");
                    Files.copy(possibleLogo.toPath(), logoFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    logger.info("Logo image copied from {} to: {}", possibleLogo.getAbsolutePath(), logoFile.getAbsolutePath());
                    return;
                }
                currentDir = currentDir.getParentFile();
                depth++;
            }
            
            logger.warn("Logo image not found. Tried test resources and project root (up to {} levels deep). Logo will not appear in HTML report.", maxDepth);
        } catch (IOException e) {
            logger.warn("Failed to copy logo image: {}", e.getMessage(), e);
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
        final List<ObjectNode> examples = new ArrayList<>();

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

        void addExample(ObjectNode example) {
            if (!isDuplicate(example)) {
                this.examples.add(example);
            }
        }

        private boolean isDuplicate(ObjectNode newExample) {
            try {
                String newExampleJson = objectMapper.writeValueAsString(newExample);
                for (ObjectNode existingExample : examples) {
                    String existingExampleJson = objectMapper.writeValueAsString(existingExample);
                    if (newExampleJson.equals(existingExampleJson)) {
                        return true;
                    }
                }
            } catch (IOException e) {
                logger.debug("Failed to compare examples for deduplication: {}", e.getMessage());
            }
            return false;
        }
    }
}

