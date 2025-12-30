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
            
            // Merge patterns: annotation patterns override auto-detected ones
            // Start with auto-detected patterns, then add annotation patterns (removing duplicates)
            if (annotationIgnorePatterns != null && !annotationIgnorePatterns.isEmpty()) {
                int autoDetectedCount = ignorePatterns.size();
                // Remove any auto-detected patterns that conflict with annotation patterns
                // (annotation patterns take priority - remove duplicates from auto-detected)
                ignorePatterns.removeAll(annotationIgnorePatterns);
                // Add annotation patterns (they override any conflicting auto-detected patterns)
                ignorePatterns.addAll(annotationIgnorePatterns);
                logger.info("Merging ignore patterns: {} auto-detected ({} kept after override) + {} from annotation (annotation patterns have priority)", 
                        autoDetectedCount,
                        ignorePatterns.size() - annotationIgnorePatterns.size(), 
                        annotationIgnorePatterns.size());
            }
            
            if (!ignorePatterns.isEmpty()) {
                logger.info("Applying {} ignore patterns to stub files for {}", 
                        ignorePatterns.size(), 
                        testMethodName != null ? testClassName + "." + testMethodName : testClassName);
                File playbackMappingsDir = preparePlaybackMappings(mappingsDir);
                applyIgnorePatternsToStubFiles(playbackMappingsDir, ignorePatterns);
                mappingsDir = playbackMappingsDir;
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
                    String elementName = extractElementNameFromXPath(elementPattern);
                    String attrName = extractAttributeNameFromXPath(attrPattern);
                    if (elementName != null && attrName != null) {
                        setXmlAttributesToPlaceholder(doc, elementName, attrName);
                    }
                }
            } else {
                // Element pattern: //*[local-name()='element']
                String elementName = extractElementNameFromXPath(xpathPattern);
                if (elementName != null) {
                    setXmlElementsToPlaceholder(doc, elementName);
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
