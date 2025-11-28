package com.stablemock.core.server;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.stablemock.core.config.PortFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;

/**
 * Manages WireMock server lifecycle and configuration.
 */
public final class WireMockServerManager {
    
    private static final Logger logger = LoggerFactory.getLogger(WireMockServerManager.class);
    
    private WireMockServerManager() {
        // utility class
    }
    
    public static WireMockServer startRecording(int port, File mappingsDir, List<String> targetUrls) {
        if (targetUrls.isEmpty()) {
            throw new IllegalArgumentException("At least one targetUrl must be provided for recording mode");
        }

        if (!mappingsDir.exists() && !mappingsDir.mkdirs()) {
            throw new RuntimeException("Failed to create mappings directory: " + mappingsDir.getAbsolutePath());
        }

        File mappingsSubDir = new File(mappingsDir, "mappings");
        File filesSubDir = new File(mappingsDir, "__files");
        if (!mappingsSubDir.exists() && !mappingsSubDir.mkdirs()) {
            throw new RuntimeException("Failed to create mappings subdirectory: " + mappingsSubDir.getAbsolutePath());
        }
        if (!filesSubDir.exists() && !filesSubDir.mkdirs()) {
            throw new RuntimeException("Failed to create __files subdirectory: " + filesSubDir.getAbsolutePath());
        }

        WireMockConfiguration config = WireMockConfiguration.wireMockConfig()
                .port(port)
                .notifier(new com.github.tomakehurst.wiremock.common.ConsoleNotifier(false))
                .usingFilesUnderDirectory(mappingsDir.getAbsolutePath());

        WireMockServer server = new WireMockServer(config);
        server.start();

        String primaryUrl = targetUrls.get(0);
        server.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock.any(
                                com.github.tomakehurst.wiremock.client.WireMock.anyUrl())
                        .willReturn(
                                com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                                        .proxiedFrom(primaryUrl)));

        logger.info("Recording mode on port {}, proxying to {}", port, primaryUrl);
        return server;
    }

    public static class AnnotationInfo {
        public final int index;
        public final String[] urls;

        public AnnotationInfo(int index, String[] urls) {
            this.index = index;
            this.urls = urls;
        }
    }
    
    public static WireMockServer startPlayback(int port, File mappingsDir, 
            File testResourcesDir, String testClassName, String testMethodName) {
        if (!mappingsDir.exists() && !mappingsDir.mkdirs()) {
            logger.warn("Mappings directory does not exist: {}", mappingsDir.getAbsolutePath());
        }


        // Load detected ignore patterns and modify stub files before loading
        // For class-level, we try to load patterns from all test methods
        if (testResourcesDir != null && testClassName != null) {
            List<String> ignorePatterns = new java.util.ArrayList<>();
            
            if (testMethodName != null) {
                // Method-level patterns
                ignorePatterns.addAll(com.stablemock.core.analysis.AnalysisResultStorage
                        .loadIgnorePatterns(testResourcesDir, testClassName, testMethodName));
            } else {
                // Class-level: try to load from all test methods
                File testClassDir = new File(testResourcesDir, "stablemock/" + testClassName);
                if (testClassDir.exists() && testClassDir.isDirectory()) {
                    File[] methodDirs = testClassDir.listFiles(File::isDirectory);
                    if (methodDirs != null) {
                        for (File methodDir : methodDirs) {
                            if (!methodDir.getName().equals("mappings") && 
                                !methodDir.getName().equals("__files") &&
                                !methodDir.getName().startsWith("url_") &&
                                !methodDir.getName().startsWith("annotation_")) {
                                List<String> methodPatterns = com.stablemock.core.analysis.AnalysisResultStorage
                                        .loadIgnorePatterns(testResourcesDir, testClassName, methodDir.getName());
                                ignorePatterns.addAll(methodPatterns);
                            }
                        }
                    }
                }
            }
            
            if (!ignorePatterns.isEmpty()) {
                logger.info("Applying {} auto-detected ignore patterns to stub files for {}", 
                        ignorePatterns.size(), 
                        testMethodName != null ? testClassName + "." + testMethodName : testClassName);
                applyIgnorePatternsToStubFiles(mappingsDir, ignorePatterns);
            }
        }

        WireMockConfiguration config = WireMockConfiguration.wireMockConfig()
                .port(port)
                .notifier(new com.github.tomakehurst.wiremock.common.ConsoleNotifier(false))
                .usingFilesUnderDirectory(mappingsDir.getAbsolutePath());

        WireMockServer server = new WireMockServer(config);
        server.start();

        logger.info("Playback mode on port {}, loading mappings from {}", port, mappingsDir.getAbsolutePath());
        return server;
    }
    
    /**
     * Applies ignore patterns to WireMock stub files before loading.
     * 
     * This approach uses WireMock 3's canonical placeholder mechanism:
     * 1. For JSON: Replaces ignored field values with `${json-unit.ignore}` placeholders
     * 2. For XML: Replaces ignored element/attribute values with `${xmlunit.ignore}` placeholders
     * 3. Converts `equalTo` to `equalToJson` or `equalToXml` when appropriate
     * 4. Sets `ignoreExtraElements: true` for JSON to allow extra fields in requests
     * 
     * This is the canonical WireMock 3 approach as documented:
     * - JSON: https://docs.wiremock.io/request-matching/json
     * - XML: https://docs.wiremock.io/soap-stubbing
     * 
     * Using placeholders (instead of removing fields) preserves the structure and works
     * for both JSON and XML formats consistently.
     */
    private static void applyIgnorePatternsToStubFiles(File mappingsDir, List<String> ignorePatterns) {
        try {
            File mappingsSubDir = new File(mappingsDir, "mappings");
            if (!mappingsSubDir.exists() || !mappingsSubDir.isDirectory()) {
                return;
            }
            
            File[] mappingFiles = mappingsSubDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
            if (mappingFiles == null) {
                return;
            }
            
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = 
                    new com.fasterxml.jackson.databind.ObjectMapper();
            
            for (File mappingFile : mappingFiles) {
                try {
                    com.fasterxml.jackson.databind.JsonNode mapping = objectMapper.readTree(mappingFile);
                    com.fasterxml.jackson.databind.node.ObjectNode mappingObj = 
                            (com.fasterxml.jackson.databind.node.ObjectNode) mapping;
                    
                    com.fasterxml.jackson.databind.JsonNode requestNode = mappingObj.get("request");
                    if (requestNode != null && requestNode.isObject()) {
                        com.fasterxml.jackson.databind.node.ObjectNode requestObj = 
                                (com.fasterxml.jackson.databind.node.ObjectNode) requestNode;
                        
                        com.fasterxml.jackson.databind.JsonNode bodyPatternsNode = requestObj.get("bodyPatterns");
                        if (bodyPatternsNode != null && bodyPatternsNode.isArray()) {
                            boolean modified = false;
                            for (com.fasterxml.jackson.databind.JsonNode patternNode : bodyPatternsNode) {
                                if (patternNode.isObject()) {
                                    com.fasterxml.jackson.databind.node.ObjectNode patternObj = 
                                            (com.fasterxml.jackson.databind.node.ObjectNode) patternNode;
                                    
                                    // Check for equalToJson first (WireMock 3 format)
                                    com.fasterxml.jackson.databind.JsonNode matcherNode = patternObj.get("equalToJson");
                                    String matcherKey = "equalToJson";
                                    
                                    // Check for equalToXml (WireMock 3 format)
                                    if (matcherNode == null) {
                                        matcherNode = patternObj.get("equalToXml");
                                        if (matcherNode != null) {
                                            matcherKey = "equalToXml";
                                        }
                                    }
                                    
                                    // Fall back to equalTo (WireMock 2 format or string matching)
                                    if (matcherNode == null) {
                                        matcherNode = patternObj.get("equalTo");
                                        matcherKey = "equalTo";
                                    }
                                    
                                    if (matcherNode != null && matcherNode.isTextual()) {
                                        String expectedBody = matcherNode.asText();
                                        
                                        // Try to parse as JSON - if it's valid JSON, use json-unit.ignore placeholders
                                        try {
                                            com.fasterxml.jackson.databind.ObjectMapper testMapper = 
                                                    new com.fasterxml.jackson.databind.ObjectMapper();
                                            testMapper.readTree(expectedBody);
                                            
                                            // It's valid JSON, replace ignored fields with ${json-unit.ignore}
                                            String normalizedJson = normalizeJsonStringWithPlaceholders(expectedBody, ignorePatterns);
                                            
                                            // Remove the old matcher and add equalToJson
                                            patternObj.remove(matcherKey);
                                            patternObj.put("equalToJson", normalizedJson);
                                            patternObj.put("ignoreArrayOrder", false);
                                            patternObj.put("ignoreExtraElements", true);
                                            modified = true;
                                            logger.debug("Changed {} to equalToJson with json-unit.ignore placeholders", matcherKey);
                                        } catch (Exception e) {
                                            // Not valid JSON, try XML
                                            try {
                                                // Check if it's XML by looking for XML structure
                                                if (expectedBody.trim().startsWith("<") && expectedBody.trim().endsWith(">")) {
                                                    // It's XML, replace ignored elements/attributes with ${xmlunit.ignore}
                                                    String normalizedXml = normalizeXmlStringWithPlaceholders(expectedBody, ignorePatterns);
                                                    
                                                    // Remove the old matcher and add equalToXml
                                                    patternObj.remove(matcherKey);
                                                    patternObj.put("equalToXml", normalizedXml);
                                                    modified = true;
                                                    logger.debug("Changed {} to equalToXml with xmlunit.ignore placeholders", matcherKey);
                                                }
                                            } catch (Exception xmlEx) {
                                                // Not XML either, skip
                                                logger.debug("Skipping non-JSON/XML body pattern: {}", e.getMessage());
                                            }
                                        }
                                    }
                                }
                            }
                            
                            if (modified) {
                                objectMapper.writerWithDefaultPrettyPrinter().writeValue(mappingFile, mapping);
                                logger.debug("Modified stub file {} to apply ignore patterns", mappingFile.getName());
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to modify stub file {}: {}", mappingFile.getName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to apply ignore patterns to stub files: {}", e.getMessage());
        }
    }
    
    /**
     * Normalizes JSON by replacing ignored fields with ${json-unit.ignore} placeholders.
     * This is the canonical WireMock 3 approach for ignoring dynamic JSON fields.
     */
    private static String normalizeJsonStringWithPlaceholders(String json, List<String> ignorePatterns) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = 
                    new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode jsonNode = objectMapper.readTree(json);
            
            for (String pattern : ignorePatterns) {
                if (pattern.startsWith("json:")) {
                    String jsonPath = pattern.substring(5);
                    replaceJsonPathWithPlaceholder(jsonNode, jsonPath);
                }
            }
            
            return objectMapper.writeValueAsString(jsonNode);
        } catch (Exception e) {
            logger.debug("Failed to normalize JSON: {}", e.getMessage());
            return json;
        }
    }
    
    /**
     * Normalizes XML by replacing ignored elements/attributes with ${xmlunit.ignore} placeholders.
     * This is the canonical WireMock 3 approach for ignoring dynamic XML content.
     */
    private static String normalizeXmlStringWithPlaceholders(String xml, List<String> ignorePatterns) {
        try {
            // Parse XML patterns and replace matching elements/attributes with ${xmlunit.ignore}
            String result = xml;
            
            for (String pattern : ignorePatterns) {
                if (pattern.startsWith("xml:")) {
                    String xpathPattern = pattern.substring(4);
                    // Handle XPath patterns like "//*[local-name()='timestamp']"
                    // For now, support simple element name patterns
                    if (xpathPattern.startsWith("//")) {
                        // Extract element name from XPath
                        String elementName = extractElementNameFromXPath(xpathPattern);
                        if (elementName != null) {
                            // Replace element content with ${xmlunit.ignore}
                            result = replaceXmlElementWithPlaceholder(result, elementName);
                        }
                    } else {
                        // Simple element name
                        result = replaceXmlElementWithPlaceholder(result, xpathPattern);
                    }
                }
            }
            
            return result;
        } catch (Exception e) {
            logger.debug("Failed to normalize XML: {}", e.getMessage());
            return xml;
        }
    }
    
    /**
     * Replaces a JSON field value with ${json-unit.ignore} placeholder.
     */
    private static void replaceJsonPathWithPlaceholder(com.fasterxml.jackson.databind.JsonNode node, String path) {
        if (node == null || !node.isObject()) {
            return;
        }
        
        if (path.contains(".")) {
            String[] parts = path.split("\\.", 2);
            String field = parts[0];
            String remaining = parts[1];
            
            if (node.has(field)) {
                replaceJsonPathWithPlaceholder(node.get(field), remaining);
            }
        } else {
            if (node.isObject()) {
                com.fasterxml.jackson.databind.node.ObjectNode objectNode = 
                        (com.fasterxml.jackson.databind.node.ObjectNode) node;
                if (objectNode.has(path)) {
                    objectNode.put(path, "${json-unit.ignore}");
                }
            }
        }
    }
    
    /**
     * Extracts element name from XPath pattern like "//*[local-name()='timestamp']"
     */
    private static String extractElementNameFromXPath(String xpath) {
        // Simple extraction for patterns like //*[local-name()='name']
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "local-name\\(\\)=['\"]([^'\"]+)['\"]");
        java.util.regex.Matcher matcher = pattern.matcher(xpath);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
    
    /**
     * Replaces XML element content with ${xmlunit.ignore} placeholder.
     */
    private static String replaceXmlElementWithPlaceholder(String xml, String elementName) {
        // Replace <elementName>value</elementName> with <elementName>${xmlunit.ignore}</elementName>
        // Handle both self-closing and paired tags
        String selfClosingPattern = "<" + java.util.regex.Pattern.quote(elementName) + 
                "([^>]*?)/>";
        String pairedPattern = "<" + java.util.regex.Pattern.quote(elementName) + 
                "([^>]*?)>([^<]*)</" + java.util.regex.Pattern.quote(elementName) + ">";
        
        // Replace paired tags
        xml = xml.replaceAll(pairedPattern, "<" + elementName + "$1>${xmlunit.ignore}</" + elementName + ">");
        
        // For self-closing tags, convert to paired with placeholder
        xml = xml.replaceAll(selfClosingPattern, "<" + elementName + "$1>${xmlunit.ignore}</" + elementName + ">");
        
        return xml;
    }
    
    private static void removeJsonPath(com.fasterxml.jackson.databind.JsonNode node, String path) {
        if (node == null || !node.isObject()) {
            return;
        }
        
        if (path.contains(".")) {
            String[] parts = path.split("\\.", 2);
            String field = parts[0];
            String remaining = parts[1];
            
            if (node.has(field)) {
                removeJsonPath(node.get(field), remaining);
            }
        } else {
            if (node.isObject()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) node).remove(path);
            }
        }
    }
    
    
    public static int findFreePort() {
        return PortFinder.findFreePort();
    }
}

