package com.stablemock.core.server;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.common.FatalStartupException;
import com.stablemock.core.config.PortFinder;
import com.stablemock.core.config.StableMockConfig;
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

        // During recording, WireMock needs the base directory structure
        // For class-level servers (parameterized tests), WireMock's snapshotRecord will write to
        // mappingsDir/mappings and mappingsDir/__files, so we need to ensure both exist at class level
        // Method-specific directories will be created in afterEach when mappings are saved
        try {
            java.nio.file.Files.createDirectories(mappingsDir.toPath());
            // Create mappings and __files directories at class level for WireMock's snapshotRecord
            File classMappingsDir = new File(mappingsDir, "mappings");
            File classFilesDir = new File(mappingsDir, "__files");
            if (!classMappingsDir.exists()) {
                java.nio.file.Files.createDirectories(classMappingsDir.toPath());
            }
            if (!classFilesDir.exists()) {
                java.nio.file.Files.createDirectories(classFilesDir.toPath());
            }
        } catch (java.nio.file.FileAlreadyExistsException e) {
            // Directory already exists, that's fine (another thread may have created it)
            if (!mappingsDir.isDirectory()) {
                throw new RuntimeException("Path exists but is not a directory: " + mappingsDir.getAbsolutePath());
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create mappings directory: " + mappingsDir.getAbsolutePath(), e);
        }

        int currentPort = port;
        int maxRetries = 5;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                int proxyTimeoutMs = StableMockConfig.getProxyTimeoutMs();
                WireMockConfiguration config = WireMockConfiguration.wireMockConfig()
                        .port(currentPort)
                        .notifier(new com.github.tomakehurst.wiremock.common.ConsoleNotifier(false))
                        .usingFilesUnderDirectory(mappingsDir.getAbsolutePath())
                        .proxyTimeout(proxyTimeoutMs); // Configurable proxy timeout (default 60s, important for WSL)

                WireMockServer server = new WireMockServer(config);
                server.start();
                
                // Wait for server to be ready (important for WSL/file system sync)
                // Use exponential backoff with readiness check
                waitForServerReady(server, currentPort);

                String primaryUrl = targetUrls.get(0);
                server.stubFor(
                        WireMock.any(WireMock.anyUrl())
                                .willReturn(WireMock.aResponse()
                                        .proxiedFrom(primaryUrl)));
                
                // Verify stub is registered and server is responding (important for WSL)
                verifyStubWorking(server, currentPort);

                if (attempt > 0) {
                    logger.info("Recording mode started on port {} (retry attempt {}) after port conflict, proxying to {}", 
                            currentPort, attempt + 1, primaryUrl);
                } else {
                    logger.info("Recording mode on port {}, proxying to {}", currentPort, primaryUrl);
                }
                return server;
            } catch (FatalStartupException e) {
                // Check if it's a port binding issue
                Throwable cause = e.getCause();
                if (cause != null && (cause instanceof java.net.BindException || 
                        cause.getMessage() != null && cause.getMessage().contains("Address already in use"))) {
                    if (attempt < maxRetries - 1) {
                        logger.warn("Port {} is already in use, trying a new port (attempt {}/{})", 
                                currentPort, attempt + 1, maxRetries);
                        currentPort = PortFinder.findFreePort();
                        try {
                            // Increased delay to allow ports to be fully released (especially in CI)
                            Thread.sleep(200 + (attempt * 50)); // Progressive delay: 200ms, 250ms, 300ms...
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Interrupted while retrying server startup", ie);
                        }
                        continue;
                    }
                }
                // Not a port binding issue or out of retries, rethrow
                throw e;
            }
        }
        throw new RuntimeException("Failed to start recording server after " + maxRetries + " attempts");
    }

    public record AnnotationInfo(int index, String[] urls) {
    }
    
    public static WireMockServer startPlayback(int port, File mappingsDir, 
            File testResourcesDir, String testClassName, String testMethodName, 
            List<String> annotationIgnorePatterns) {
        logger.info("=== Starting WireMock playback on port {} ===", port);
        logger.info("Loading mappings from: {}", mappingsDir.getAbsolutePath());
        
        // Use Files.createDirectories() which is atomic and handles race conditions
        try {
            java.nio.file.Files.createDirectories(mappingsDir.toPath());
        } catch (java.nio.file.FileAlreadyExistsException e) {
            // Directory already exists, that's fine (another thread may have created it)
            if (!mappingsDir.isDirectory()) {
                logger.error("Path exists but is not a directory: {}", mappingsDir.getAbsolutePath());
            }
        } catch (Exception e) {
            logger.error("Failed to create mappings directory: {} - {}", mappingsDir.getAbsolutePath(), e.getMessage());
        }
        
        if (mappingsDir.exists() && mappingsDir.isDirectory()) {
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
                    if (logger.isDebugEnabled()) {
                        logger.debug("Patterns to apply: {}", ignorePatterns);
                    }
                    File playbackMappingsDir = preparePlaybackMappings(mappingsDir);
                    applyIgnorePatternsToStubFiles(playbackMappingsDir, ignorePatterns, testMethodName);
                    mappingsDir = playbackMappingsDir;
                } else {
                    logger.info("No ignore patterns to apply for {}", testClassName + "." + testMethodName);
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
                                    logger.info("Loaded {} ignore patterns for method {}: {}", 
                                            methodPatterns.size(), methodName, methodPatterns);
                                    patternsByMethod.put(methodName, methodPatterns);
                                } else {
                                    logger.debug("No ignore patterns found for method {}", methodName);
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

        int currentPort = port;
        int maxRetries = 5;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                int proxyTimeoutMs = StableMockConfig.getProxyTimeoutMs();
                WireMockConfiguration config = WireMockConfiguration.wireMockConfig()
                        .port(currentPort)
                        .notifier(new com.github.tomakehurst.wiremock.common.ConsoleNotifier(false))
                        .usingFilesUnderDirectory(mappingsDir.getAbsolutePath())
                        .proxyTimeout(proxyTimeoutMs); // Configurable proxy timeout (default 60s, important for WSL)

                WireMockServer server = new WireMockServer(config);
                server.start();
                
                // Wait for server to be ready (important for WSL/file system sync)
                // Use exponential backoff with readiness check
                waitForServerReady(server, currentPort);
                
                // Add catch-all stub to return 404 instead of proxying when no mapping matches
                // This prevents WireMock from trying to proxy to the real API in playback mode
                server.stubFor(
                    WireMock.any(WireMock.anyUrl())
                        .atPriority(1000) // Low priority - only matches if no other stub matches
                        .willReturn(WireMock.aResponse()
                            .withStatus(404)
                            .withBody("No matching stub mapping found")));
                
                // Verify stub is registered and server is responding (important for WSL)
                verifyStubWorking(server, currentPort);

                if (attempt > 0) {
                    logger.info("Playback mode started on port {} (retry attempt {}) after port conflict, loading mappings from {}", 
                            currentPort, attempt + 1, mappingsDir.getAbsolutePath());
                } else {
                    logger.info("Playback mode on port {}, loading mappings from {}", currentPort, mappingsDir.getAbsolutePath());
                }
                return server;
            } catch (FatalStartupException e) {
                // Check if it's a port binding issue
                Throwable cause = e.getCause();
                if (cause != null && (cause instanceof java.net.BindException || 
                        cause.getMessage() != null && cause.getMessage().contains("Address already in use"))) {
                    if (attempt < maxRetries - 1) {
                        logger.warn("Port {} is already in use, trying a new port (attempt {}/{})", 
                                currentPort, attempt + 1, maxRetries);
                        currentPort = PortFinder.findFreePort();
                        try {
                            // Increased delay to allow ports to be fully released (especially in CI)
                            Thread.sleep(200 + (attempt * 50)); // Progressive delay: 200ms, 250ms, 300ms...
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Interrupted while retrying server startup", ie);
                        }
                        continue;
                    }
                }
                // Not a port binding issue or out of retries, rethrow
                throw e;
            }
        }
        throw new RuntimeException("Failed to start playback server after " + maxRetries + " attempts");
    }
    
    /**
     * Applies ignore patterns to a method-specific directory (before merge).
     * This is a public method to allow applying patterns before merging.
     */
    public static void applyIgnorePatternsToMethodDirectory(File methodMappingsDir, List<String> ignorePatterns) {
        if (ignorePatterns == null || ignorePatterns.isEmpty()) {
            logger.info("No ignore patterns to apply to method directory: {}", methodMappingsDir.getAbsolutePath());
            return;
        }
        logger.info("Applying {} ignore patterns to method directory: {}", ignorePatterns.size(), methodMappingsDir.getAbsolutePath());
        applyIgnorePatternsToStubFiles(methodMappingsDir, ignorePatterns, null);
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
        logger.info("applyIgnorePatternsToStubFiles called with mappingsDir: {}, patterns: {}", 
                mappingsDir.getAbsolutePath(), ignorePatterns.size());
        try {
            File mappingsSubDir = new File(mappingsDir, "mappings");
            logger.info("Checking mappings subdirectory: {} (exists: {})", 
                    mappingsSubDir.getAbsolutePath(), mappingsSubDir.exists());
            if (!mappingsSubDir.exists() || !mappingsSubDir.isDirectory()) {
                logger.warn("Mappings subdirectory does not exist: {}", mappingsSubDir.getAbsolutePath());
                return;
            }
            
            File[] mappingFiles = mappingsSubDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
            if (mappingFiles == null || mappingFiles.length == 0) {
                logger.warn("No mapping files found in: {}", mappingsSubDir.getAbsolutePath());
                return;
            }
            logger.info("Found {} mapping file(s) to process in {}", mappingFiles.length, mappingsSubDir.getAbsolutePath());
            
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = 
                    new com.fasterxml.jackson.databind.ObjectMapper();
            
            for (File mappingFile : mappingFiles) {
                try {
                    // For method-level: WireMock doesn't add method name prefixes to mapping files.
                    // If testMethodName is provided, apply patterns to all files in the directory.
                    // The method isolation is handled by:
                    // 1. Method-specific directories (before merge), OR
                    // 2. Per-method invocation (after merge - we're called once per method)
                    // So we don't need to check file name prefixes.
                    // Note: This means patterns will be applied to all files, but that's correct
                    // because we're being invoked per-method and the directory contains only that method's files
                    // (either because it's method-specific, or because merge hasn't happened yet, or because
                    // we're applying to the merged directory but only for this specific method invocation).
                    
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
                                            // Check if we have attribute patterns (typically SOAP XML)
                                            boolean hasAttributePatterns = ignorePatterns.stream()
                                                    .anyMatch(p -> p.startsWith("xml:") && p.contains("/@*"));
                                            
                                            // Check if this is SOAP XML with namespaces (triggers XMLUnit bug)
                                            boolean isSoapXml = expectedBody.contains("soap:Envelope") || 
                                                    expectedBody.contains("SOAP-ENV:Envelope") ||
                                                    expectedBody.contains("xmlns:soap") ||
                                                    expectedBody.contains("xmlns:SOAP-ENV") ||
                                                    expectedBody.contains("http://schemas.xmlsoap.org");
                                            
                                            if (hasAttributePatterns && isSoapXml) {
                                                // For SOAP XML with dynamic attributes, use matchesXPath instead of equalToXml
                                                // This completely avoids the XMLUnit "type: -1" bug
                                                patternObj.remove(matcherKey);
                                                
                                                // Extract a key element from the XML to create a minimal match
                                                // This matches based on the presence of the SOAP structure
                                                String xpathMatch = extractSoapXPathMatch(expectedBody);
                                                patternObj.put("matchesXPath", xpathMatch);
                                                
                                                modified = true;
                                                logger.info("Changed {} to matchesXPath for SOAP XML in {} (avoiding XMLUnit bug)", 
                                                        matcherKey, mappingFile.getName());
                                            } else {
                                                // Non-SOAP XML: use normal equalToXml with placeholders
                                                String normalizedXml = normalizeXmlStringWithPlaceholders(expectedBody, ignorePatterns);
                                                boolean xmlModified = !normalizedXml.equals(expectedBody);
                                                
                                                if (matcherKey.equals("equalTo") || xmlModified) {
                                                    patternObj.remove(matcherKey);
                                                    patternObj.put("equalToXml", normalizedXml);
                                                    patternObj.put("enablePlaceholders", true);
                                                    patternObj.put("ignoreWhitespace", true);
                                                    
                                                    modified = true;
                                                    logger.info("Changed {} to equalToXml with xmlunit.ignore placeholders for {}", 
                                                            matcherKey, mappingFile.getName());
                                                }
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
     * File naming convention: methodName_post-uuid.json (e.g., testSoapFlow[2]_post-abc123.json)
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
            
            logger.info("Applying method-specific ignore patterns to {} mapping files (from {} method(s))", 
                    mappingFiles.length, patternsByMethod.size());
            
            for (File mappingFile : mappingFiles) {
                try {
                    // Extract method name from file prefix. Merged files are named "methodName_originalName.json"
                    // (e.g. "should_make_a_full_flow_until_confirmation_using_flexible_payment[1]_sap_bc_....json").
                    // Match against known method names so names containing underscores are resolved correctly.
                    String fileName = mappingFile.getName();
                    String methodName = null;
                    for (String knownMethod : patternsByMethod.keySet()) {
                        if (fileName.startsWith(knownMethod + "_")) {
                            methodName = knownMethod;
                            break;
                        }
                    }
                    if (methodName == null) {
                        int underscoreIdx = fileName.indexOf('_');
                        if (underscoreIdx > 0) {
                            methodName = fileName.substring(0, underscoreIdx);
                        }
                    }
                    
                    // Get patterns specific to this method
                    List<String> ignorePatterns = new java.util.ArrayList<>();
                    if (methodName != null && patternsByMethod.containsKey(methodName)) {
                        ignorePatterns.addAll(patternsByMethod.get(methodName));
                        logger.debug("Found {} patterns for method {} (file: {})", 
                                ignorePatterns.size(), methodName, fileName);
                    } else {
                        logger.debug("No method-specific patterns for file {} (extracted method: {})", 
                                fileName, methodName);
                    }
                    
                    // Merge with annotation patterns (annotation patterns always apply)
                    if (annotationIgnorePatterns != null && !annotationIgnorePatterns.isEmpty()) {
                        for (String annotationPattern : annotationIgnorePatterns) {
                            if (!ignorePatterns.contains(annotationPattern)) {
                                ignorePatterns.add(annotationPattern);
                            }
                        }
                    }
                    
                    if (ignorePatterns.isEmpty()) {
                        logger.debug("No ignore patterns to apply for mapping {}", fileName);
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
                                                logger.debug("Modified mapping {} with json-unit.ignore placeholders", 
                                                        mappingFile.getName());
                                            }
                                        } else if (isXml) {
                                            // Check if we have attribute patterns (typically SOAP XML)
                                            boolean hasAttributePatterns = ignorePatterns.stream()
                                                    .anyMatch(p -> p.startsWith("xml:") && p.contains("/@*"));
                                            
                                            // Check if this is SOAP XML with namespaces (triggers XMLUnit bug)
                                            boolean isSoapXml = expectedBody.contains("soap:Envelope") || 
                                                    expectedBody.contains("SOAP-ENV:Envelope") ||
                                                    expectedBody.contains("xmlns:soap") ||
                                                    expectedBody.contains("xmlns:SOAP-ENV") ||
                                                    expectedBody.contains("http://schemas.xmlsoap.org");
                                            
                                            if (hasAttributePatterns && isSoapXml) {
                                                // For SOAP XML with dynamic attributes, use matchesXPath instead of equalToXml
                                                // This completely avoids the XMLUnit "type: -1" bug
                                                patternObj.remove(matcherKey);
                                                
                                                // Extract a key element from the XML to create a minimal match
                                                String xpathMatch = extractSoapXPathMatch(expectedBody);
                                                patternObj.put("matchesXPath", xpathMatch);
                                                
                                                modified = true;
                                                logger.info("Changed {} to matchesXPath for SOAP XML in {} (avoiding XMLUnit bug)", 
                                                        matcherKey, mappingFile.getName());
                                            } else {
                                                // Non-SOAP XML: use normal equalToXml with placeholders
                                                String normalizedXml = normalizeXmlStringWithPlaceholders(expectedBody, ignorePatterns);
                                                boolean xmlModified = !normalizedXml.equals(expectedBody);
                                                
                                                if (matcherKey.equals("equalTo") || xmlModified) {
                                                    patternObj.remove(matcherKey);
                                                    patternObj.put("equalToXml", normalizedXml);
                                                    patternObj.put("enablePlaceholders", true);
                                                    patternObj.put("ignoreWhitespace", true);
                                                    
                                                    modified = true;
                                                    logger.debug("Modified mapping {} with xmlunit.ignore placeholders", 
                                                            mappingFile.getName());
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            
                            if (modified) {
                                objectMapper.writerWithDefaultPrettyPrinter().writeValue(mappingFile, mapping);
                                logger.info("Applied {} ignore patterns to mapping {}", 
                                        ignorePatterns.size(), mappingFile.getName());
                            } else {
                                logger.debug("No modifications needed for mapping {} (no matching patterns)", 
                                        mappingFile.getName());
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
                        logger.debug("Skipping ignore pattern '{}' - field does not exist in JSON", normalizedPattern);
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
     * Extracts an XPath expression that matches the key element(s) in SOAP XML.
     * This is used to avoid the XMLUnit "type: -1" bug with namespaced SOAP XML.
     * Returns an XPath that matches based on the SOAP body's root element.
     * When multiple stubs exist for the same operation (e.g. parameterized tests), appends
     * a discriminator (e.g. RatePlanCode) so each stub matches only its request variant
     * and responses are not mixed.
     */
    private static String extractSoapXPathMatch(String xml) {
        try {
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            org.w3c.dom.Document doc = builder.parse(new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            
            org.w3c.dom.NodeList bodyNodes = doc.getElementsByTagNameNS("http://schemas.xmlsoap.org/soap/envelope/", "Body");
            if (bodyNodes.getLength() > 0) {
                org.w3c.dom.Node bodyNode = bodyNodes.item(0);
                org.w3c.dom.NodeList children = bodyNode.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    org.w3c.dom.Node child = children.item(i);
                    if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                        String localName = child.getLocalName();
                        String baseXpath = "//*[local-name()='Body']/*[local-name()='" + localName + "']";
                        String discriminator = extractSoapXPathDiscriminator((org.w3c.dom.Element) child);
                        if (discriminator != null && !discriminator.isEmpty()) {
                            return baseXpath + discriminator;
                        }
                        return baseXpath;
                    }
                }
            }
            return "//*[local-name()='Envelope']";
        } catch (Exception e) {
            logger.warn("Failed to extract SOAP XPath match: {}", e.getMessage());
            return "//*[local-name()='Envelope']";
        }
    }

    /**
     * Finds a stable attribute in the SOAP body root subtree to distinguish this request
     * from others (e.g. RatePlanCode in OTA_HotelAvailRQ). Avoids mixing responses when
     * multiple parameterized invocations are merged into one playback.
     */
    private static String extractSoapXPathDiscriminator(org.w3c.dom.Element bodyRoot) {
        String[] elementNames = {"RatePlanCandidate", "RoomStayCandidate", "HotelRef"};
        String[] discriminatorAttrs = {"RatePlanCode", "RatePlanID", "RoomTypeCode", "HotelCode"};
        for (String eltName : elementNames) {
            java.util.List<org.w3c.dom.Node> found = findElementsByLocalName(bodyRoot, eltName);
            for (org.w3c.dom.Node n : found) {
                org.w3c.dom.NamedNodeMap attrs = n.getAttributes();
                if (attrs == null) continue;
                for (String attrName : discriminatorAttrs) {
                    for (int a = 0; a < attrs.getLength(); a++) {
                        org.w3c.dom.Node attr = attrs.item(a);
                        if (attr.getLocalName() != null && attrName.equals(attr.getLocalName())) {
                            String val = attr.getNodeValue();
                            if (val != null && !val.isEmpty()) {
                                String eltLocal = n.getLocalName() != null ? n.getLocalName() : n.getNodeName();
                                return "//*[local-name()='" + eltLocal + "'][@*[local-name()='" + attrName + "']=" + xpathLiteral(val) + "]";
                            }
                        }
                    }
                }
            }
        }
        String[] discriminatorElements = {"UserType", "CustomerCode", "CardNumber", "Category"};
        for (String eltName : discriminatorElements) {
            java.util.List<org.w3c.dom.Node> found = findElementsByLocalName(bodyRoot, eltName);
            for (org.w3c.dom.Node n : found) {
                String text = n.getTextContent();
                if (text != null) {
                    text = text.trim();
                }
                if (text != null && !text.isEmpty()) {
                    return "//*[local-name()='" + eltName + "' and normalize-space()=" + xpathLiteral(text) + "]";
                }
            }
        }
        return null;
    }

    private static java.util.List<org.w3c.dom.Node> findElementsByLocalName(org.w3c.dom.Node node, String localName) {
        java.util.List<org.w3c.dom.Node> out = new java.util.ArrayList<>();
        if (node.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
            String ln = node.getLocalName() != null ? node.getLocalName() : node.getNodeName();
            if (localName.equals(ln)) {
                out.add(node);
            }
        }
        org.w3c.dom.NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            out.addAll(findElementsByLocalName(children.item(i), localName));
        }
        return out;
    }

    private static String xpathLiteral(String val) {
        if (val == null) return "''";
        if (val.indexOf('\'') < 0) return "'" + val + "'";
        return "\"" + val.replace("\"", "\"\"") + "\"";
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
            
            // Apply ignore patterns - replace ignored elements with placeholders
            // NOTE: For attribute patterns (/@*), we DON'T apply placeholders because
            // ${xmlunit.ignore} in attribute values causes "INVALID_CHARACTER_ERR" in XML parsers.
            // Instead, we use exemptedComparisons: ["ATTR_VALUE"] at the matcher level.
            int patternsApplied = 0;
            int attributePatternsSkipped = 0;
            logger.debug("Processing {} ignore patterns for XML normalization", ignorePatterns.size());
            for (String pattern : ignorePatterns) {
                if (pattern.startsWith("xml:")) {
                    // Skip attribute patterns - these will be handled via exemptedComparisons
                    if (pattern.contains("/@*")) {
                        attributePatternsSkipped++;
                        logger.debug("Skipping attribute pattern (will use exemptedComparisons): {}", pattern);
                        continue;
                    }
                    
                    String xpathPattern = pattern.substring(4);
                    // Handle xml://*[...] format: after removing xml:, we get //*[...]
                    // This is actually an absolute path from root, not a descendant search
                    // Strip the leading // if pattern is //*[...] to treat as absolute path
                    if (xpathPattern.startsWith("//*[")) {
                        xpathPattern = xpathPattern.substring(2); // Remove // to make it *[...]
                    }
                    logger.debug("Processing XML element pattern: {} -> {}", pattern, xpathPattern);
                    String xmlBefore = docToString(doc);
                    applyXmlIgnorePattern(doc, xpathPattern);
                    String xmlAfter = docToString(doc);
                    if (!xmlBefore.equals(xmlAfter)) {
                        patternsApplied++;
                        logger.info("Applied XML ignore pattern: {}", pattern);
                        // Verify placeholder is actually in the XML
                        if (!xmlAfter.contains("${xmlunit.ignore}")) {
                            logger.warn("Pattern {} was applied but placeholder not found in XML! XML length: {} -> {}", 
                                    pattern, xmlBefore.length(), xmlAfter.length());
                        }
                    } else {
                        logger.debug("Pattern did not modify XML: {}", pattern);
                    }
                }
            }
            
            if (attributePatternsSkipped > 0) {
                logger.info("Skipped {} XML attribute pattern(s) - will use exemptedComparisons instead", attributePatternsSkipped);
            }
            
            if (patternsApplied > 0) {
                logger.info("Applied {} XML ignore pattern(s), {} total patterns processed", patternsApplied, ignorePatterns.size());
            } else if (!ignorePatterns.isEmpty()) {
                logger.warn("No XML patterns were applied! {} patterns processed but XML unchanged", ignorePatterns.size());
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
            logger.warn("Failed to normalize XML with {} patterns: {}", ignorePatterns.size(), e.getMessage());
            if (logger.isDebugEnabled()) {
                logger.debug("XML normalization error details", e);
            }
            return xml;
        }
    }
    
    /**
     * Helper method to convert Document to string for comparison.
     */
    private static String docToString(Document doc) {
        try {
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(doc), new StreamResult(writer));
            return writer.toString();
        } catch (Exception e) {
            return "";
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
        if (path == null || path.isEmpty()) {
            return segments;
        }
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

            // Skip empty segments (e.g., consecutive dots or leading/trailing dots)
            if (!segmentToken.isEmpty()) {
                segments.add(parseJsonPathSegment(segmentToken));
            }
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
                String indexText = matcher.group(1);
                try {
                    indices.add(Integer.parseInt(indexText));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid array index in JSON path segment: '" + indexText + "' in token '" + token + "'", e);
                }
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
     * Supports both absolute paths (*[...]) and descendant paths (//*[...]).
     */
    private static void applyXmlIgnorePattern(Document doc, String xpathPattern) {
        // Handle both absolute paths (*[...]) and descendant paths (//*[...])
        boolean isDescendantPath = xpathPattern.startsWith("//");
        boolean isAbsolutePath = xpathPattern.startsWith("*[");
        
        logger.debug("applyXmlIgnorePattern: pattern={}, isDescendantPath={}, isAbsolutePath={}", 
                xpathPattern, isDescendantPath, isAbsolutePath);
        
        if (!isDescendantPath && !isAbsolutePath) {
            // Pattern doesn't match expected format, skip
            logger.warn("Pattern doesn't match expected format (must start with *[ or //): {}", xpathPattern);
            return;
        }
        
        if (xpathPattern.contains("@")) {
            // Attribute pattern: *[...]/@*[...] or //*[...]/@*[...]
            String[] parts = xpathPattern.split("/@");
            if (parts.length == 2) {
                String elementPattern = parts[0];
                String attrPattern = parts[1];
                List<String> elementPath = extractElementPathFromXPath(elementPattern);
                String attrName = extractAttributeNameFromXPath(attrPattern);
                logger.debug("Attribute pattern - elementPath={}, attrName={}", elementPath, attrName);
                if (!elementPath.isEmpty() && attrName != null) {
                    setXmlAttributesToPlaceholderByPath(doc, elementPath, attrName);
                } else {
                    logger.warn("Failed to extract elementPath or attrName from pattern: {}", xpathPattern);
                }
            } else {
                logger.warn("Invalid attribute pattern format (expected exactly one /@): {}", xpathPattern);
            }
        } else {
            // Element pattern: *[...] or //*[...]
            List<String> elementPath = extractElementPathFromXPath(xpathPattern);
            logger.debug("Element pattern - elementPath={}", elementPath);
            if (!elementPath.isEmpty()) {
                setXmlElementsToPlaceholderByPath(doc, elementPath);
            } else {
                logger.warn("Failed to extract elementPath from pattern: {}", xpathPattern);
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
        // Start from document root element and traverse the path
        Element rootElement = doc.getDocumentElement();
        if (rootElement != null) {
            String rootLocalName = rootElement.getLocalName() != null ? rootElement.getLocalName() : rootElement.getNodeName();
            if (rootLocalName.equals(elementPath.get(0))) {
                // Root matches first element in path, traverse from root
                applyElementPathPlaceholder(rootElement, elementPath, 1);
            } else {
                // Root doesn't match, search for matching elements (fallback for non-standard XML)
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
        // Start from document root element and traverse the path
        Element rootElement = doc.getDocumentElement();
        if (rootElement != null) {
            String rootLocalName = rootElement.getLocalName() != null ? rootElement.getLocalName() : rootElement.getNodeName();
            if (rootLocalName.equals(elementPath.get(0))) {
                // Root matches first element in path, traverse from root
                applyElementPathAttributePlaceholder(rootElement, elementPath, 1, attrName);
            } else {
                // Root doesn't match, search for matching elements (fallback for non-standard XML)
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
            // Reached target element - set attribute placeholder
            String elementLocalName = element.getLocalName() != null ? element.getLocalName() : element.getNodeName();
            if (element.hasAttribute(attrName)) {
                element.setAttribute(attrName, "${xmlunit.ignore}");
                logger.debug("Set attribute {} to placeholder on element {}", attrName, elementLocalName);
            } else {
                // Check all attributes to see what's available (for debugging)
                logger.warn("Element {} does not have attribute {}. Available attributes: {}", 
                        elementLocalName, attrName, 
                        java.util.stream.IntStream.range(0, element.getAttributes().getLength())
                                .mapToObj(i -> element.getAttributes().item(i).getNodeName())
                                .collect(java.util.stream.Collectors.joining(", ")));
            }
            return;
        }

        NodeList children = element.getChildNodes();
        String expectedName = elementPath.get(index);
        int matchesFound = 0;
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                Element child = (Element) node;
                String localName = child.getLocalName() != null ? child.getLocalName() : child.getNodeName();
                if (localName.equals(expectedName)) {
                    matchesFound++;
                    applyElementPathAttributePlaceholder(child, elementPath, index + 1, attrName);
                }
            }
        }
        if (matchesFound == 0) {
            logger.debug("No child element '{}' found at path index {} in element {}", 
                    expectedName, index, element.getLocalName() != null ? element.getLocalName() : element.getNodeName());
        }
    }
    
    /**
     * Extracts all element names from an XPath pattern (for example,
     * "//*[local-name()='timestamp']/*[local-name()='value']") and returns them
     * as a list representing the element path.
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

    /**
     * Waits for WireMock server to be ready to accept connections.
     * Uses exponential backoff with a readiness check to ensure server is fully started.
     * 
     * @param server The WireMock server instance
     * @param port The port the server is running on
     */
    private static void waitForServerReady(WireMockServer server, int port) {
        int maxAttempts = 15; // Increased for WSL
        long initialDelay = 100; // Start with 100ms (increased for WSL)
        long maxDelay = 2000; // Cap at 2 seconds (increased for WSL)
        
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            // Check if server is running
            if (!server.isRunning()) {
                long delay = Math.min(initialDelay * (1L << attempt), maxDelay);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            
            // Try to connect to verify server is ready (with longer timeout for WSL)
            try {
                java.net.Socket socket = new java.net.Socket();
                socket.connect(new java.net.InetSocketAddress("localhost", port), 500); // Increased timeout to 500ms
                socket.close();
                
                // Additional small delay after successful connection to ensure WireMock is fully initialized
                // This is especially important in WSL where file system operations can be slower
                try {
                    Thread.sleep(200); // Wait 200ms after connection succeeds
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                
                // Server is ready
                if (attempt > 0) {
                    logger.debug("WireMock server on port {} ready after {} attempt(s)", port, attempt + 1);
                }
                return;
            } catch (java.io.IOException e) {
                // Server not ready yet, wait and retry
                long delay = Math.min(initialDelay * (1L << attempt), maxDelay);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        
        // If we get here, server might still be starting but we've waited long enough
        logger.warn("WireMock server on port {} may not be fully ready after {} attempts, continuing anyway", port, maxAttempts);
    }

    /**
     * Verifies that WireMock stub is working by making a test HTTP request.
     * This ensures the server is not just accepting connections but also processing requests.
     * 
     * @param server The WireMock server instance
     * @param port The port the server is running on
     */
    private static void verifyStubWorking(WireMockServer server, int port) {
        int maxAttempts = 10;
        int attempts = 0;
        long delay = 50;
        
        while (attempts < maxAttempts) {
            try {
                // Make a simple HTTP request to verify stub is working
                java.net.URL url = new java.net.URL("http://localhost:" + port + "/__admin");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(500);
                conn.setReadTimeout(1000);
                conn.setRequestMethod("GET");
                
                int responseCode = conn.getResponseCode();
                conn.disconnect();
                
                // If we get any response (even 404), the server is working
                if (responseCode > 0) {
                    if (attempts > 0) {
                        logger.debug("WireMock stub verified working on port {} after {} attempt(s)", port, attempts + 1);
                    }
                    // Additional delay to ensure everything is fully initialized.
                    // Default 0; set stablemock.wiremock.startupExtraSleepMs (e.g. 500) for WSL if needed.
                    int extraSleepMs = StableMockConfig.getStartupExtraSleepMs();
                    if (extraSleepMs > 0) {
                        try {
                            Thread.sleep(extraSleepMs);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return;
                }
            } catch (Exception e) {
                // Server not ready yet, wait and retry
                attempts++;
                if (attempts < maxAttempts) {
                    try {
                        Thread.sleep(delay);
                        delay = Math.min(delay * 2, 500); // Exponential backoff, max 500ms
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
        
        logger.warn("Could not verify WireMock stub on port {} after {} attempts, continuing anyway", port, maxAttempts);
    }
}
