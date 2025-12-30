package com.stablemock.core.server;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.stablemock.core.config.PortFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
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
        if (targetUrls.size() > 1) {
            logger.warn("Multiple target URLs provided for recording; using only the first: {}", targetUrls.get(0));
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
                WireMock.any(WireMock.anyUrl())
                        .willReturn(WireMock.aResponse()
                                .proxiedFrom(primaryUrl)));

        logger.info("Recording mode on port {}, proxying to {}", port, primaryUrl);
        return server;
    }

    public record AnnotationInfo(int index, String[] urls) {
    }
    
    public static WireMockServer startPlayback(int port, File mappingsDir, 
            File testResourcesDir, String testClassName, String testMethodName, 
            List<String> annotationIgnorePatterns) {
        logger.info("=== Starting WireMock playback on port {} ===", port);
        logger.info("Loading mappings from: {}", mappingsDir.getAbsolutePath());
        
        if (!mappingsDir.exists() && !mappingsDir.mkdirs()) {
            logger.error("Mappings directory does not exist: {}", mappingsDir.getAbsolutePath());
        } else {
            File mappingsSubDir = new File(mappingsDir, "mappings");
            if (mappingsSubDir.exists()) {
                File[] mappingFiles = mappingsSubDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
                if (mappingFiles != null) {
                    logger.info("Found {} mapping file(s) in {}", mappingFiles.length, mappingsSubDir.getAbsolutePath());
                    int postCount = 0;
                    int getCount = 0;
                    // Sort files for consistent ordering and better debugging
                    java.util.Arrays.sort(mappingFiles, java.util.Comparator.comparing(File::getName));
                    
                    for (File mappingFile : mappingFiles) {
                        try {
                            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                            com.fasterxml.jackson.databind.JsonNode mappingJson = mapper.readTree(mappingFile);
                            com.fasterxml.jackson.databind.JsonNode requestNode = mappingJson.get("request");
                            if (requestNode != null) {
                                String method = requestNode.has("method") ? requestNode.get("method").asText() : "UNKNOWN";
                                String url = "UNKNOWN";
                                if (requestNode.has("url")) {
                                    url = requestNode.get("url").asText();
                                } else if (requestNode.has("urlPath")) {
                                    url = requestNode.get("urlPath").asText();
                                }
                                String mappingName = mappingJson.has("name") ? mappingJson.get("name").asText() : "unnamed";
                                logger.info("  Loaded: {} {} (name: {}, file: {})", method, url, mappingName, mappingFile.getName());
                                
                                // Log if this is GET /users/2 for debugging
                                if ("GET".equalsIgnoreCase(method) && "/users/2".equals(url)) {
                                    logger.info("  >>> FOUND GET /users/2 mapping: {} <<<", mappingFile.getName());
                                }
                                
                                if ("POST".equalsIgnoreCase(method)) {
                                    postCount++;
                                } else if ("GET".equalsIgnoreCase(method)) {
                                    getCount++;
                                }
                            }
                        } catch (Exception e) {
                            logger.error("Failed to parse mapping file {}: {}", mappingFile.getName(), e.getMessage());
                        }
                    }
                    logger.info("Mappings breakdown: {} GET, {} POST, {} other", getCount, postCount, mappingFiles.length - getCount - postCount);
                    if (postCount == 0 && getCount == 0) {
                        logger.warn("No mappings found! All requests will fail.");
                    } else {
                        if (postCount == 0) {
                            logger.warn("No POST mappings found! POST requests may fail.");
                        }
                        if (getCount == 0) {
                            logger.warn("No GET mappings found! GET requests may fail.");
                        }
                    }
                } else {
                    logger.warn("No mapping files found in {}", mappingsSubDir.getAbsolutePath());
                }
            } else {
                logger.warn("Mappings subdirectory does not exist: {}", mappingsSubDir.getAbsolutePath());
            }
        }


        // Load detected ignore patterns and modify stub files before loading
        // For class-level, apply patterns per test method based on mapping file prefixes
        if (testResourcesDir != null && testClassName != null) {
            if (testMethodName != null) {
                // Method-level: load patterns for this specific method
                List<String> ignorePatterns = new java.util.ArrayList<>();
                ignorePatterns.addAll(com.stablemock.core.analysis.AnalysisResultStorage
                        .loadIgnorePatterns(testResourcesDir, testClassName, testMethodName));
                
                // Merge with annotation patterns
                if (annotationIgnorePatterns != null && !annotationIgnorePatterns.isEmpty()) {
                    int autoDetectedCount = ignorePatterns.size();
                    ignorePatterns.removeAll(annotationIgnorePatterns);
                    ignorePatterns.addAll(annotationIgnorePatterns);
                    logger.info("Merging ignore patterns: {} auto-detected ({} kept after override) + {} from annotation (annotation patterns have priority)", 
                            autoDetectedCount,
                            ignorePatterns.size() - annotationIgnorePatterns.size(), 
                            annotationIgnorePatterns.size());
                }
                
                if (!ignorePatterns.isEmpty()) {
                    logger.info("Applying {} ignore patterns to stub files for {}", 
                            ignorePatterns.size(), testClassName + "." + testMethodName);
                    File playbackMappingsDir = preparePlaybackMappings(mappingsDir);
                    applyIgnorePatternsToStubFiles(playbackMappingsDir, ignorePatterns, testMethodName);
                    mappingsDir = playbackMappingsDir;
                }
            } else {
                // Class-level: apply patterns per test method based on mapping file prefixes
                File testClassDir = new File(testResourcesDir, "stablemock/" + testClassName);
                if (testClassDir.exists() && testClassDir.isDirectory()) {
                    File[] methodDirs = testClassDir.listFiles(File::isDirectory);
                    if (methodDirs != null) {
                        java.util.Map<String, List<String>> patternsByMethod = new java.util.HashMap<>();
                        for (File methodDir : methodDirs) {
                            if (!methodDir.getName().equals("mappings") && 
                                !methodDir.getName().equals("__files") &&
                                !methodDir.getName().startsWith("url_") &&
                                !methodDir.getName().startsWith("annotation_")) {
                                String methodName = methodDir.getName();
                                List<String> methodPatterns = com.stablemock.core.analysis.AnalysisResultStorage
                                        .loadIgnorePatterns(testResourcesDir, testClassName, methodName);
                                if (!methodPatterns.isEmpty()) {
                                    patternsByMethod.put(methodName, methodPatterns);
                                }
                            }
                        }
                        
                        if (!patternsByMethod.isEmpty()) {
                            logger.info("Applying ignore patterns per test method for {}", testClassName);
                            File playbackMappingsDir = preparePlaybackMappings(mappingsDir);
                            applyIgnorePatternsToStubFilesPerMethod(playbackMappingsDir, patternsByMethod, annotationIgnorePatterns);
                            mappingsDir = playbackMappingsDir;
                        }
                    }
                }
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
     * This approach uses WireMock 3's canonical placeholder mechanism:
     * 1. For JSON: Replaces ignored field values with `${json-unit.ignore}` placeholders
     * 2. For XML: Replaces ignored element/attribute values with `${xmlunit.ignore}` placeholders
     * 3. Converts `equalTo` to `equalToJson` or `equalToXml` when appropriate
     * 4. Sets `ignoreExtraElements: true` for JSON to allow extra fields in requests
     * This is the canonical WireMock 3 approach as documented:
     * - JSON: <a href="https://docs.wiremock.io/request-matching/json">...</a>
     * - XML: <a href="https://docs.wiremock.io/soap-stubbing">...</a>
     * Using placeholders (instead of removing fields) preserves the structure and works
     * for both JSON and XML formats consistently.
     */
    private static void applyIgnorePatternsToStubFiles(File mappingsDir, List<String> ignorePatterns, String testMethodName) {
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
                    // For method-level, only apply patterns to mappings from this method
                    if (testMethodName != null && !mappingFile.getName().startsWith(testMethodName + "_")) {
                        continue;
                    }
                    
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
                                        
                                        // Use helper methods for format detection (faster than try-catch)
                                        boolean isJson = com.stablemock.core.analysis.JsonBodyParser.isJson(expectedBody);
                                        boolean isXml = com.stablemock.core.analysis.XmlBodyParser.isXml(expectedBody);
                                        
                                        if (isJson) {
                                            // It's JSON, replace ignored fields with ${json-unit.ignore} placeholders
                                            String normalizedJson = normalizeJsonStringWithPlaceholders(expectedBody, ignorePatterns);
                                            
                                            // Convert equalTo to equalToJson for WireMock 3 compatibility
                                            if (matcherKey.equals("equalTo") || !normalizedJson.equals(expectedBody)) {
                                                patternObj.remove(matcherKey);
                                                patternObj.put("equalToJson", normalizedJson);
                                                patternObj.put("ignoreArrayOrder", false);
                                                patternObj.put("ignoreExtraElements", true);
                                                modified = true;
                                                logger.debug("Changed {} to equalToJson with json-unit.ignore placeholders", matcherKey);
                                            }
                                        } else if (isXml) {
                                            // It's XML, replace ignored elements/attributes with ${xmlunit.ignore}
                                            String normalizedXml = normalizeXmlStringWithPlaceholders(expectedBody, ignorePatterns);
                                            
                                            // Convert equalTo to equalToXml for WireMock 3 compatibility
                                            if (matcherKey.equals("equalTo") || !normalizedXml.equals(expectedBody)) {
                                                patternObj.remove(matcherKey);
                                                patternObj.put("equalToXml", normalizedXml);
                                                patternObj.put("enablePlaceholders", true);
                                                patternObj.put("ignoreWhitespace", true);
                                                modified = true;
                                                logger.debug("Changed {} to equalToXml with xmlunit.ignore placeholders", matcherKey);
                                            }
                                        }
                                        // If neither JSON nor XML, skip silently
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
     * Applies ignore patterns per test method based on mapping file prefixes.
     * Only applies patterns from a specific test method to mappings that belong to that method.
     */
    private static void applyIgnorePatternsToStubFilesPerMethod(File mappingsDir, 
            java.util.Map<String, List<String>> patternsByMethod, 
            List<String> annotationIgnorePatterns) {
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
                    // Find which test method this mapping belongs to based on filename prefix
                    String fileName = mappingFile.getName();
                    String matchingMethod = null;
                    for (String methodName : patternsByMethod.keySet()) {
                        if (fileName.startsWith(methodName + "_")) {
                            matchingMethod = methodName;
                            break;
                        }
                    }
                    
                    // Only apply patterns if this mapping belongs to a known test method
                    if (matchingMethod == null) {
                        continue;
                    }
                    
                    // Get patterns for this specific test method
                    List<String> ignorePatterns = new java.util.ArrayList<>(patternsByMethod.get(matchingMethod));
                    
                    // Merge with annotation patterns
                    if (annotationIgnorePatterns != null && !annotationIgnorePatterns.isEmpty()) {
                        ignorePatterns.removeAll(annotationIgnorePatterns);
                        ignorePatterns.addAll(annotationIgnorePatterns);
                    }
                    
                    if (ignorePatterns.isEmpty()) {
                        continue;
                    }
                    
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
                                    
                                    com.fasterxml.jackson.databind.JsonNode matcherNode = patternObj.get("equalToJson");
                                    String matcherKey = "equalToJson";
                                    
                                    if (matcherNode == null) {
                                        matcherNode = patternObj.get("equalToXml");
                                        if (matcherNode != null) {
                                            matcherKey = "equalToXml";
                                        }
                                    }
                                    
                                    if (matcherNode == null) {
                                        matcherNode = patternObj.get("equalTo");
                                        matcherKey = "equalTo";
                                    }
                                    
                                    if (matcherNode != null && matcherNode.isTextual()) {
                                        String expectedBody = matcherNode.asText();
                                        
                                        boolean isJson = com.stablemock.core.analysis.JsonBodyParser.isJson(expectedBody);
                                        boolean isXml = com.stablemock.core.analysis.XmlBodyParser.isXml(expectedBody);
                                        
                                        if (isJson) {
                                            String normalizedJson = normalizeJsonStringWithPlaceholders(expectedBody, ignorePatterns);
                                            
                                            if (matcherKey.equals("equalTo") || !normalizedJson.equals(expectedBody)) {
                                                patternObj.remove(matcherKey);
                                                patternObj.put("equalToJson", normalizedJson);
                                                patternObj.put("ignoreArrayOrder", false);
                                                patternObj.put("ignoreExtraElements", true);
                                                modified = true;
                                                logger.debug("Modified mapping {} for test method {} with json-unit.ignore placeholders", 
                                                        mappingFile.getName(), matchingMethod);
                                            }
                                        } else if (isXml) {
                                            String normalizedXml = normalizeXmlStringWithPlaceholders(expectedBody, ignorePatterns);
                                            
                                            if (matcherKey.equals("equalTo") || !normalizedXml.equals(expectedBody)) {
                                                patternObj.remove(matcherKey);
                                                patternObj.put("equalToXml", normalizedXml);
                                                patternObj.put("enablePlaceholders", true);
                                                patternObj.put("ignoreWhitespace", true);
                                                modified = true;
                                                logger.debug("Modified mapping {} for test method {} with xmlunit.ignore placeholders", 
                                                        mappingFile.getName(), matchingMethod);
                                            }
                                        }
                                    }
                                }
                            }
                            
                            if (modified) {
                                objectMapper.writerWithDefaultPrettyPrinter().writeValue(mappingFile, mapping);
                                logger.debug("Applied {} ignore patterns to mapping {} for test method {}", 
                                        ignorePatterns.size(), mappingFile.getName(), matchingMethod);
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to modify stub file {}: {}", mappingFile.getName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to apply ignore patterns per method to stub files: {}", e.getMessage());
        }
    }

    private static File preparePlaybackMappings(File mappingsDir) {
        try {
            java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("stablemock-playback-");
            copyDirectory(mappingsDir.toPath(), tempDir);
            registerTempDirectoryCleanup(tempDir);
            return tempDir.toFile();
        } catch (Exception e) {
            logger.warn("Failed to create temporary playback mappings directory; using original mappings. {}", e.getMessage());
            return mappingsDir;
        }
    }

    private static void copyDirectory(java.nio.file.Path source, java.nio.file.Path target) throws java.io.IOException {
        try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(source)) {
            paths.forEach(path -> {
                java.nio.file.Path dest = target.resolve(source.relativize(path));
                try {
                    if (java.nio.file.Files.isDirectory(path)) {
                        java.nio.file.Files.createDirectories(dest);
                    } else {
                        java.nio.file.Files.copy(path, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
    }

    private static void registerTempDirectoryCleanup(java.nio.file.Path tempDir) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(tempDir)) {
                paths.sorted(java.util.Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                java.nio.file.Files.deleteIfExists(path);
                            } catch (java.io.IOException e) {
                                logger.debug("Failed to delete temp path {}: {}", path, e.getMessage());
                            }
                        });
            } catch (java.io.IOException e) {
                logger.debug("Failed to clean up temp playback mappings {}: {}", tempDir, e.getMessage());
            }
        }, "stablemock-playback-cleanup"));
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
            
            // Filter ignore patterns to only those that actually exist in the JSON
            // This prevents adding fields that don't exist (e.g., "variables" when the request doesn't have it)
            List<String> applicablePatterns = new java.util.ArrayList<>();
            for (String pattern : ignorePatterns) {
                String normalizedPattern = normalizeGraphQlPattern(pattern);
                if (normalizedPattern.startsWith("json:")) {
                    String jsonPath = normalizedPattern.substring(5);
                    if (fieldExistsInJson(jsonNode, jsonPath)) {
                        applicablePatterns.add(normalizedPattern);
                    } else {
                        logger.debug("Skipping ignore pattern '{}' - field does not exist in JSON", pattern);
                    }
                } else {
                    applicablePatterns.add(normalizedPattern); // Non-JSON patterns (XML, etc.) are always applicable
                }
            }
            
            for (String pattern : applicablePatterns) {
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
     * Checks if a JSON path exists in the JSON node.
     * Supports simple field names (e.g., "variables") and nested paths (e.g., "variables.code").
     */
    private static boolean fieldExistsInJson(com.fasterxml.jackson.databind.JsonNode node, String path) {
        return getJsonNodeAtPath(node, path) != null;
    }
    
    /**
     * Normalizes XML by replacing ignored elements/attributes with ${xmlunit.ignore} placeholders.
     * This is the canonical WireMock 3 approach for ignoring dynamic XML content.
     * Uses DOM manipulation for precise handling of nested elements and attributes.
     */
    private static String normalizeXmlStringWithPlaceholders(String xml, List<String> ignorePatterns) {
        try {
            // Parse XML into DOM
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));
            
            // Apply ignore patterns - replace ignored elements/attributes with placeholders
            for (String pattern : ignorePatterns) {
                if (pattern.startsWith("xml:")) {
                    String xpathPattern = pattern.substring(4);
                    applyXmlIgnorePattern(doc, xpathPattern);
                }
            }
            
            // Convert back to string
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(doc), new StreamResult(writer));
            return writer.toString();
        } catch (Exception e) {
            logger.debug("Failed to normalize XML: {}", e.getMessage());
            return xml;
        }
    }
    
    
    /**
     * Replaces a JSON field value with ${json-unit.ignore} placeholder.
     */
    private static void replaceJsonPathWithPlaceholder(com.fasterxml.jackson.databind.JsonNode node, String path) {
        setJsonNodePlaceholder(node, path);
    }

    private static void setJsonNodePlaceholder(com.fasterxml.jackson.databind.JsonNode root, String path) {
        if (root == null || path == null || path.isEmpty()) {
            return;
        }

        List<JsonPathSegment> segments = parseJsonPath(path);
        if (segments.isEmpty()) {
            return;
        }

        com.fasterxml.jackson.databind.JsonNode current = root;
        for (int i = 0; i < segments.size() - 1; i++) {
            current = getChildNode(current, segments.get(i));
            if (current == null) {
                return;
            }
        }

        JsonPathSegment lastSegment = segments.get(segments.size() - 1);
        applyPlaceholderAtSegment(current, lastSegment);
    }

    private static com.fasterxml.jackson.databind.JsonNode getJsonNodeAtPath(com.fasterxml.jackson.databind.JsonNode root, String path) {
        if (root == null || path == null || path.isEmpty()) {
            return null;
        }

        List<JsonPathSegment> segments = parseJsonPath(path);
        com.fasterxml.jackson.databind.JsonNode current = root;
        for (JsonPathSegment segment : segments) {
            current = getChildNode(current, segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static com.fasterxml.jackson.databind.JsonNode getChildNode(com.fasterxml.jackson.databind.JsonNode current, JsonPathSegment segment) {
        com.fasterxml.jackson.databind.JsonNode node = current;
        if (segment.fieldName != null) {
            if (node == null || !node.isObject()) {
                return null;
            }
            node = node.get(segment.fieldName);
        }
        if (node == null) {
            return null;
        }
        for (Integer index : segment.arrayIndices) {
            if (!node.isArray() || index < 0 || index >= node.size()) {
                return null;
            }
            node = node.get(index);
            if (node == null) {
                return null;
            }
        }
        return node;
    }

    private static void applyPlaceholderAtSegment(com.fasterxml.jackson.databind.JsonNode current, JsonPathSegment segment) {
        if (segment.fieldName != null) {
            if (!current.isObject()) {
                return;
            }
            com.fasterxml.jackson.databind.node.ObjectNode objectNode =
                    (com.fasterxml.jackson.databind.node.ObjectNode) current;
            if (!objectNode.has(segment.fieldName)) {
                return;
            }
            com.fasterxml.jackson.databind.JsonNode child = objectNode.get(segment.fieldName);
            if (segment.arrayIndices.isEmpty()) {
                objectNode.put(segment.fieldName, "${json-unit.ignore}");
                return;
            }
            com.fasterxml.jackson.databind.JsonNode target = child;
            for (int i = 0; i < segment.arrayIndices.size() - 1; i++) {
                int index = segment.arrayIndices.get(i);
                if (!target.isArray() || index < 0 || index >= target.size()) {
                    return;
                }
                target = target.get(index);
            }
            int lastIndex = segment.arrayIndices.get(segment.arrayIndices.size() - 1);
            if (target != null && target.isArray() && lastIndex >= 0 && lastIndex < target.size()) {
                ((com.fasterxml.jackson.databind.node.ArrayNode) target).set(lastIndex,
                        com.fasterxml.jackson.databind.node.TextNode.valueOf("${json-unit.ignore}"));
            }
        } else {
            com.fasterxml.jackson.databind.JsonNode target = current;
            for (int i = 0; i < segment.arrayIndices.size() - 1; i++) {
                int index = segment.arrayIndices.get(i);
                if (!target.isArray() || index < 0 || index >= target.size()) {
                    return;
                }
                target = target.get(index);
            }
            int lastIndex = segment.arrayIndices.get(segment.arrayIndices.size() - 1);
            if (target != null && target.isArray() && lastIndex >= 0 && lastIndex < target.size()) {
                ((com.fasterxml.jackson.databind.node.ArrayNode) target).set(lastIndex,
                        com.fasterxml.jackson.databind.node.TextNode.valueOf("${json-unit.ignore}"));
            }
        }
    }

    private static List<JsonPathSegment> parseJsonPath(String path) {
        List<JsonPathSegment> segments = new java.util.ArrayList<>();
        String remaining = path;
        while (!remaining.isEmpty()) {
            String segmentToken;
            int dotIndex = remaining.indexOf('.');
            if (dotIndex >= 0) {
                segmentToken = remaining.substring(0, dotIndex);
                remaining = remaining.substring(dotIndex + 1);
            } else {
                segmentToken = remaining;
                remaining = "";
            }

            segments.add(parseJsonPathSegment(segmentToken));
        }
        return segments;
    }

    private static JsonPathSegment parseJsonPathSegment(String token) {
        String fieldName = null;
        List<Integer> indices = new java.util.ArrayList<>();
        int bracketIndex = token.indexOf('[');
        if (bracketIndex >= 0) {
            fieldName = bracketIndex == 0 ? null : token.substring(0, bracketIndex);
            String remainder = token.substring(bracketIndex);
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\[(\\d+)]").matcher(remainder);
            while (matcher.find()) {
                indices.add(Integer.parseInt(matcher.group(1)));
            }
        } else {
            fieldName = token;
        }
        return new JsonPathSegment(fieldName, indices);
    }

    private static String normalizeGraphQlPattern(String pattern) {
        if (pattern == null) {
            return null;
        }
        if (pattern.startsWith("gql:")) {
            return "json:" + pattern.substring(4);
        }
        if (pattern.startsWith("graphql:")) {
            return "json:" + pattern.substring(8);
        }
        return pattern;
    }

    private static class JsonPathSegment {
        private final String fieldName;
        private final List<Integer> arrayIndices;

        private JsonPathSegment(String fieldName, List<Integer> arrayIndices) {
            this.fieldName = fieldName;
            this.arrayIndices = arrayIndices;
        }
    }
    
    /**
     * Sets ignored elements/attributes to ${xmlunit.ignore} placeholder.
     */
    private static void applyXmlIgnorePattern(Document doc, String xpathPattern) {
        if (xpathPattern.startsWith("//")) {
            if (xpathPattern.contains("@")) {
                // Attribute pattern: //*[local-name()='element']/@*[local-name()='attr']
                String[] parts = xpathPattern.split("/@");
                if (parts.length == 2) {
                    String elementPattern = parts[0];
                    String attrPattern = parts[1];
                    List<String> elementPath = extractElementPathFromXPath(elementPattern);
                    String attrName = extractAttributeNameFromXPath(attrPattern);
                    if (!elementPath.isEmpty() && attrName != null) {
                        setXmlAttributesToPlaceholderByPath(doc, elementPath, attrName);
                    }
                }
            } else {
                // Element pattern: //*[local-name()='element']
                List<String> elementPath = extractElementPathFromXPath(xpathPattern);
                if (!elementPath.isEmpty()) {
                    setXmlElementsToPlaceholderByPath(doc, elementPath);
                }
            }
        }
    }
    
    /**
     * Sets text content of matching leaf elements to ${xmlunit.ignore}.
     */
    private static void setXmlElementsToPlaceholder(Document doc, String elementName) {
        NodeList elements = doc.getElementsByTagName("*");
        for (int i = 0; i < elements.getLength(); i++) {
            Node node = elements.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                Element element = (Element) node;
                String localName = element.getLocalName() != null ? element.getLocalName() : element.getNodeName();
                if (localName.equals(elementName)) {
                    // Only set placeholder when element has no child elements (leaf)
                    NodeList children = element.getChildNodes();
                    boolean hasElementChildren = false;
                    for (int j = 0; j < children.getLength(); j++) {
                        if (children.item(j).getNodeType() == Node.ELEMENT_NODE) {
                            hasElementChildren = true;
                            break;
                        }
                    }
                    if (!hasElementChildren) {
                        // Replace text content with placeholder while keeping structure
                        element.setTextContent("${xmlunit.ignore}");
                    }
                }
            }
        }
    }

    private static void setXmlElementsToPlaceholderByPath(Document doc, List<String> elementPath) {
        if (elementPath.isEmpty()) {
            return;
        }
        NodeList elements = doc.getElementsByTagName("*");
        for (int i = 0; i < elements.getLength(); i++) {
            Node node = elements.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                Element element = (Element) node;
                String localName = element.getLocalName() != null ? element.getLocalName() : element.getNodeName();
                if (localName.equals(elementPath.get(0))) {
                    applyElementPathPlaceholder(element, elementPath, 1);
                }
            }
        }
    }
    
    /**
     * Sets matching attributes to ${xmlunit.ignore}.
     */
    private static void setXmlAttributesToPlaceholder(Document doc, String elementName, String attrName) {
        NodeList elements = doc.getElementsByTagName("*");
        for (int i = 0; i < elements.getLength(); i++) {
            Node node = elements.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                Element element = (Element) node;
                String localName = element.getLocalName() != null ? element.getLocalName() : element.getNodeName();
                if (localName.equals(elementName) && element.hasAttribute(attrName)) {
                    element.setAttribute(attrName, "${xmlunit.ignore}");
                }
            }
        }
    }

    private static void setXmlAttributesToPlaceholderByPath(Document doc, List<String> elementPath, String attrName) {
        if (elementPath.isEmpty()) {
            return;
        }
        NodeList elements = doc.getElementsByTagName("*");
        for (int i = 0; i < elements.getLength(); i++) {
            Node node = elements.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                Element element = (Element) node;
                String localName = element.getLocalName() != null ? element.getLocalName() : element.getNodeName();
                if (localName.equals(elementPath.get(0))) {
                    applyElementPathAttributePlaceholder(element, elementPath, 1, attrName);
                }
            }
        }
    }

    private static void applyElementPathPlaceholder(Element element, List<String> elementPath, int index) {
        if (index == elementPath.size()) {
            NodeList children = element.getChildNodes();
            boolean hasElementChildren = false;
            for (int j = 0; j < children.getLength(); j++) {
                if (children.item(j).getNodeType() == Node.ELEMENT_NODE) {
                    hasElementChildren = true;
                    break;
                }
            }
            if (!hasElementChildren) {
                element.setTextContent("${xmlunit.ignore}");
            }
            return;
        }

        NodeList children = element.getChildNodes();
        String expectedName = elementPath.get(index);
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                Element child = (Element) node;
                String localName = child.getLocalName() != null ? child.getLocalName() : child.getNodeName();
                if (localName.equals(expectedName)) {
                    applyElementPathPlaceholder(child, elementPath, index + 1);
                }
            }
        }
    }

    private static void applyElementPathAttributePlaceholder(Element element, List<String> elementPath, int index, String attrName) {
        if (index == elementPath.size()) {
            if (element.hasAttribute(attrName)) {
                element.setAttribute(attrName, "${xmlunit.ignore}");
            }
            return;
        }

        NodeList children = element.getChildNodes();
        String expectedName = elementPath.get(index);
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                Element child = (Element) node;
                String localName = child.getLocalName() != null ? child.getLocalName() : child.getNodeName();
                if (localName.equals(expectedName)) {
                    applyElementPathAttributePlaceholder(child, elementPath, index + 1, attrName);
                }
            }
        }
    }
    
    /**
     * Extracts element name from XPath pattern like "//*[local-name()='timestamp']"
     */
    private static List<String> extractElementPathFromXPath(String xpath) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "local-name\\(\\)=['\"]([^'\"]+)['\"]");
        java.util.regex.Matcher matcher = pattern.matcher(xpath);
        List<String> elements = new java.util.ArrayList<>();
        while (matcher.find()) {
            elements.add(matcher.group(1));
        }
        return elements;
    }
    
    /**
     * Extracts attribute name from XPath pattern like "@*[local-name()='id']"
     */
    private static String extractAttributeNameFromXPath(String xpath) {
        // Extract from patterns like @*[local-name()='attr']
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "local-name\\(\\)=['\"]([^'\"]+)['\"]");
        java.util.regex.Matcher matcher = pattern.matcher(xpath);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    public static int findFreePort() {
        return PortFinder.findFreePort();
    }
}
