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
                                    
                                    // Fall back to equalTo (WireMock 2 format or string matching)
                                    if (matcherNode == null) {
                                        matcherNode = patternObj.get("equalTo");
                                        matcherKey = "equalTo";
                                    }
                                    
                                    if (matcherNode != null && matcherNode.isTextual()) {
                                        String expectedJson = matcherNode.asText();
                                        
                                        // Try to parse as JSON - if it's valid JSON, normalize it
                                        try {
                                            com.fasterxml.jackson.databind.ObjectMapper testMapper = 
                                                    new com.fasterxml.jackson.databind.ObjectMapper();
                                            testMapper.readTree(expectedJson);
                                            
                                            // It's valid JSON, normalize it and change to equalToJson matcher
                                            String normalizedJson = normalizeJsonString(expectedJson, ignorePatterns);
                                            
                                            // Remove the old matcher and add equalToJson
                                            patternObj.remove(matcherKey);
                                            patternObj.put("equalToJson", normalizedJson);
                                            patternObj.put("ignoreArrayOrder", false);
                                            patternObj.put("ignoreExtraElements", true);
                                            modified = true;
                                            logger.debug("Changed {} to equalToJson and removed ignored fields", matcherKey);
                                        } catch (Exception e) {
                                            // Not valid JSON, skip
                                            logger.debug("Skipping non-JSON body pattern: {}", e.getMessage());
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
    
    private static String normalizeJsonString(String json, List<String> ignorePatterns) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = 
                    new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode jsonNode = objectMapper.readTree(json);
            
            for (String pattern : ignorePatterns) {
                if (pattern.startsWith("json:")) {
                    String jsonPath = pattern.substring(5);
                    removeJsonPath(jsonNode, jsonPath);
                }
            }
            
            return objectMapper.writeValueAsString(jsonNode);
        } catch (Exception e) {
            logger.debug("Failed to normalize JSON: {}", e.getMessage());
            return json;
        }
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

