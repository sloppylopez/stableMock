package com.stablemock.core.server;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.common.FatalStartupException;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
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
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages WireMock server lifecycle and configuration.
 */
public final class WireMockServerManager {
    
    private static final Logger logger = LoggerFactory.getLogger(WireMockServerManager.class);

    // Directory names used for stub storage
    private static final String DIR_MAPPINGS = "mappings";
    private static final String DIR_FILES = "__files";
    private static final String JSON_EXT = ".json";
    private static final String PREFIX_ANNOTATION = "annotation_";

    // JSON keys for request matching
    private static final String REQ_KEY_REQUEST = "request";
    private static final String REQ_KEY_METHOD = "method";
    private static final String REQ_KEY_URLPATH = "urlPath";

    // JSON keys for body patterns
    private static final String BODY_PATTERNS_KEY = "bodyPatterns";
    private static final String EQUAL_TO_XML_KEY = "equalToXml";
    private static final String EQUAL_TO_JSON_KEY = "equalToJson";
    private static final String EQUAL_TO_KEY = "equalTo";

    // WireMock 3 xmlunit ignore placeholder (json-unit uses "${json-unit.ignore}" inline)
    private static final String PLACEHOLDER_XML_IGNORE = "${xmlunit.ignore}";

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

        // Use Files.createDirectories() which is atomic and handles race conditions
        try {
            java.nio.file.Files.createDirectories(mappingsDir.toPath());
        } catch (java.nio.file.FileAlreadyExistsException e) {
            // Directory already exists, that's fine (another thread may have created it)
            if (!mappingsDir.isDirectory()) {
                throw new RuntimeException("Path exists but is not a directory: " + mappingsDir.getAbsolutePath());
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create mappings directory: " + mappingsDir.getAbsolutePath(), e);
        }

        File mappingsSubDir = new File(mappingsDir, DIR_MAPPINGS);
        File filesSubDir = new File(mappingsDir, DIR_FILES);
        try {
            java.nio.file.Files.createDirectories(mappingsSubDir.toPath());
        } catch (java.nio.file.FileAlreadyExistsException e) {
            if (!mappingsSubDir.isDirectory()) {
                throw new RuntimeException("Path exists but is not a directory: " + mappingsSubDir.getAbsolutePath());
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create mappings subdirectory: " + mappingsSubDir.getAbsolutePath(), e);
        }
        try {
            java.nio.file.Files.createDirectories(filesSubDir.toPath());
        } catch (java.nio.file.FileAlreadyExistsException e) {
            if (!filesSubDir.isDirectory()) {
                throw new RuntimeException("Path exists but is not a directory: " + filesSubDir.getAbsolutePath());
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create __files subdirectory: " + filesSubDir.getAbsolutePath(), e);
        }
        
        // Small delay after directory creation to ensure file system sync (important for WSL)
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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

                // WireMock 3.7+ supports preserveUserAgentProxyHeader; older WireMock versions
                // will throw NoSuchMethodError if we call it directly. Use reflection for
                // compatibility across WireMock versions on the classpath.
                try {
                    java.lang.reflect.Method m = config.getClass()
                            .getMethod("preserveUserAgentProxyHeader", boolean.class);
                    config = (WireMockConfiguration) m.invoke(config, true);
                } catch (NoSuchMethodException ignored) {
                    // Method not available in this WireMock version; continue without UA preservation.
                } catch (Exception e) {
                    logger.warn("Failed to enable preserveUserAgentProxyHeader on this WireMock version: {}",
                            e.toString());
                }

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
                if (isPortBindFailure(e) && attempt < maxRetries - 1) {
                    logger.warn("Port {} is already in use, trying a new port (attempt {}/{})",
                            currentPort, attempt + 1, maxRetries);
                    currentPort = PortFinder.findFreePort();
                    try {
                        Thread.sleep(200 + (attempt * 50));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted while retrying server startup", ie);
                    }
                    continue;
                }
                throw e;
            }
        }
        throw new RuntimeException("Failed to start recording server after " + maxRetries + " attempts");
    }

    public record AnnotationInfo(int index, String[] urls, String[] ignoreResponseHeaders) {
    }
    
    public static WireMockServer startPlayback(int port, File mappingsDir, 
            File testResourcesDir, String testClassName, String testMethodName, 
            List<String> annotationIgnorePatterns,
            List<String> annotationDontIgnorePatterns) {
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
            File mappingsSubDir = new File(mappingsDir, DIR_MAPPINGS);
            if (mappingsSubDir.exists()) {
                File[] mappingFiles = mappingsSubDir.listFiles((dir, name) -> name.toLowerCase().endsWith(JSON_EXT));
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
                            com.fasterxml.jackson.databind.JsonNode requestNode = mappingJson.get(REQ_KEY_REQUEST);
                            if (requestNode != null) {
                                String method = requestNode.has(REQ_KEY_METHOD) ? requestNode.get(REQ_KEY_METHOD).asText() : "UNKNOWN";
                                String url = "UNKNOWN";
                                if (requestNode.has("url")) {
                                    url = requestNode.get("url").asText();
                                } else if (requestNode.has(REQ_KEY_URLPATH)) {
                                    url = requestNode.get(REQ_KEY_URLPATH).asText();
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
                
                // Merge with annotation patterns (explicit ignore has priority over auto-detected)
                if (annotationIgnorePatterns != null && !annotationIgnorePatterns.isEmpty()) {
                    int autoDetectedCount = ignorePatterns.size();
                    ignorePatterns.removeAll(annotationIgnorePatterns);
                    ignorePatterns.addAll(annotationIgnorePatterns);
                    logger.info("Merging ignore patterns: {} auto-detected ({} kept after override) + {} from annotation (annotation patterns have priority)", 
                            autoDetectedCount,
                            ignorePatterns.size() - annotationIgnorePatterns.size(), 
                            annotationIgnorePatterns.size());
                }
                // Apply dontIgnore: remove any ignore pattern that targets a protected field
                ignorePatterns = applyDontIgnorePatterns(ignorePatterns, annotationDontIgnorePatterns);
                
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
                            if (!methodDir.getName().equals(DIR_MAPPINGS) && 
                                !methodDir.getName().equals(DIR_FILES) &&
                                !methodDir.getName().startsWith("url_") &&
                                !methodDir.getName().startsWith(PREFIX_ANNOTATION)) {
                                String methodName = methodDir.getName();
                                // First try single-annotation path: <method>/detected-fields.json
                                List<String> singlePatterns = com.stablemock.core.analysis.AnalysisResultStorage
                                        .loadIgnorePatterns(testResourcesDir, testClassName, methodName);
                                // Collect into a mutable list
                                List<String> methodPatterns = new java.util.ArrayList<>(singlePatterns);
                                // For multi-@U tests, also scan annotation_X subdirs
                                if (methodPatterns.isEmpty()) {
                                    File[] annotDirs = methodDir.listFiles((d, name) -> name.startsWith(PREFIX_ANNOTATION));
                                    if (annotDirs != null) {
                                        for (File annotDir : annotDirs) {
                                            Integer idx = Integer.parseInt(annotDir.getName().substring(PREFIX_ANNOTATION.length()));
                                            List<String> annotPatterns = com.stablemock.core.analysis.AnalysisResultStorage
                                                    .loadIgnorePatterns(testResourcesDir, testClassName, methodName, idx);
                                            methodPatterns.addAll(annotPatterns);
                                        }
                                    }
                                }
                        if (!methodPatterns.isEmpty()) {
                            patternsByMethod.put(methodName, methodPatterns);
                        }
                            }
                        }
                        
                        if (!patternsByMethod.isEmpty()) {
                            logger.info("Applying ignore patterns per test method for {}", testClassName);
                            File playbackMappingsDir = preparePlaybackMappings(mappingsDir);
                            applyIgnorePatternsToStubFilesPerMethod(playbackMappingsDir, patternsByMethod, annotationIgnorePatterns, annotationDontIgnorePatterns);
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
                if (isPortBindFailure(e) && attempt < maxRetries - 1) {
                    logger.warn("Port {} is already in use, trying a new port (attempt {}/{})",
                            currentPort, attempt + 1, maxRetries);
                    currentPort = PortFinder.findFreePort();
                    try {
                        Thread.sleep(200 + (attempt * 50));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted while retrying server startup", ie);
                    }
                    continue;
                }
                throw e;
            }
        }
        throw new RuntimeException("Failed to start playback server after " + maxRetries + " attempts");
    }

    /**
     * Hot-reloads stub mappings on an existing WireMock server from an invocation-specific directory.
     * Resets server state and loads mappings without restarting the server or changing the port.
     *
     * @param server                      existing WireMock server (must be running)
     * @param invocationMappingsDir      directory containing mappings/ and __files/ for this invocation
     * @param serverRootDir               root directory the server was started with (for __files copy)
     * @param testResourcesDir            test resources dir (for ignore patterns)
     * @param testClassName              test class name
     * @param testMethodIdentifier       test method identifier (for ignore patterns)
     * @param annotationIgnorePatterns   ignore patterns from @U annotation
     * @param annotationDontIgnorePatterns dont-ignore patterns from @U annotation
     * @param annotationIndex            optional annotation index for multi-@U tests (enables per-annotation detected-fields.json lookup)
     */
    public static void reloadMappingsOnServer(WireMockServer server,
            File invocationMappingsDir,
            File serverRootDir,
            File testResourcesDir,
            String testClassName,
            String testMethodIdentifier,
            List<String> annotationIgnorePatterns,
            List<String> annotationDontIgnorePatterns,
            Integer annotationIndex) {
        List<String> ignorePatterns = new java.util.ArrayList<>();
        if (testResourcesDir != null && testClassName != null && testMethodIdentifier != null) {
            ignorePatterns.addAll(com.stablemock.core.analysis.AnalysisResultStorage
                    .loadIgnorePatterns(testResourcesDir, testClassName, testMethodIdentifier, annotationIndex));
            logger.debug("Reload for {} (annotationIndex={}): {} auto-detected ignore pattern(s)", testMethodIdentifier, annotationIndex, ignorePatterns.size());
            if (annotationIgnorePatterns != null && !annotationIgnorePatterns.isEmpty()) {
                ignorePatterns.removeAll(annotationIgnorePatterns);
                ignorePatterns.addAll(annotationIgnorePatterns);
            }
            ignorePatterns = applyDontIgnorePatterns(ignorePatterns, annotationDontIgnorePatterns);
        }
        File sourceDir = invocationMappingsDir;
        java.nio.file.Path tempPathUsed = null;
        if (!ignorePatterns.isEmpty()) {
            try {
                //logger.info("Applying {} ignore pattern(s) for reload on {}", ignorePatterns.size(), testMethodIdentifier);
                java.nio.file.Path tempPath = java.nio.file.Files.createTempDirectory("stablemock-reload-");
                File tempDir = tempPath.toFile();
                try {
                    copyDirectory(invocationMappingsDir.toPath(), tempPath);
                    applyIgnorePatternsToStubFiles(tempDir, ignorePatterns, testMethodIdentifier);
                    sourceDir = tempDir;
                    tempPathUsed = tempPath; // track for eager cleanup later
                } catch (Exception e) {
                    logger.debug("Failed to copy or transform temp dir for reload: {}", e.getMessage());
                }
            } catch (Exception e) {
                logger.warn("Could not apply ignore patterns for reload, using raw mappings: {} (stubs will have literal values and may not match; check logs above for which pattern failed)", e.getMessage(), e);
            }
        }
        // Clean up old __files before copying new ones to avoid stale body files
        File serverFilesDir = new File(serverRootDir, DIR_FILES);
        File sourceFilesDir = new File(sourceDir, DIR_FILES);
        if (serverFilesDir.exists() && serverFilesDir.isDirectory()) {
            File[] existing = serverFilesDir.listFiles(File::isFile);
            if (existing != null) {
                for (File f : existing) {
                    try { Files.delete(f.toPath()); } catch (Exception ignored) {}
                }
            }
        }
        if (sourceFilesDir.exists() && sourceFilesDir.isDirectory()) {
            if (!serverFilesDir.exists()) {
                serverFilesDir.mkdirs();
            }
            File[] bodyFiles = sourceFilesDir.listFiles(File::isFile);
            if (bodyFiles != null) {
                for (File f : bodyFiles) {
                    try {
                        Files.copy(f.toPath(), new File(serverFilesDir, f.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
                    } catch (Exception e) {
                        logger.warn("Failed to copy body file {}: {}", f.getName(), e.getMessage());
                    }
                }
            }
        }
        server.resetAll();
        File mappingsSubDir = new File(sourceDir, DIR_MAPPINGS);
        int loadedCount = loadMappingsFromDir(server, sourceDir);
        // If ignore patterns were applied via temp dir, loadedCount > 0 means success.
        // But if we loaded from a temp dir that happened to copy both src and build dirs,
        // reset again before fallback to avoid duplicates.
        boolean usedFallback = false;
        if (loadedCount == 0 && sourceDir.equals(invocationMappingsDir) && testResourcesDir != null && testClassName != null) {
            File buildTestResources = resolveBuildTestResourcesDir(testResourcesDir);
            if (buildTestResources != null) {
                File fallbackInvocationDir = new File(buildTestResources, "stablemock/" + testClassName + "/" + invocationMappingsDir.getName());
                logger.debug("Reload got 0 stubs from src; trying build dir fallback: {} (exists: {})",
                        fallbackInvocationDir.getAbsolutePath(), fallbackInvocationDir.exists());
                if (fallbackInvocationDir.exists() && fallbackInvocationDir.isDirectory()) {
                    int fallbackCount = loadMappingsFromDir(server, fallbackInvocationDir);
                    if (fallbackCount > 0) {
                        loadedCount = fallbackCount;
                        usedFallback = true;
                        File fallbackFilesDir = new File(fallbackInvocationDir, DIR_FILES);
                        if (fallbackFilesDir.exists() && fallbackFilesDir.isDirectory()) {
                            if (!serverFilesDir.exists()) {
                                serverFilesDir.mkdirs();
                            }
                            File[] bodyFiles = fallbackFilesDir.listFiles(File::isFile);
                            if (bodyFiles != null) {
                                for (File f : bodyFiles) {
                                    try {
                                        Files.copy(f.toPath(), new File(serverFilesDir, f.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
                                    } catch (Exception e) {
                                        logger.warn("Failed to copy body file {}: {}", f.getName(), e.getMessage());
                                    }
                                }
                            }
                        }
                        logger.info("Reloaded {} stub(s) from build dir fallback: {}", loadedCount, fallbackInvocationDir.getAbsolutePath());
                    }
                } else {
                    logger.warn("Reload fallback dir missing or not a directory: {}", fallbackInvocationDir.getAbsolutePath());
                }
            } else {
                logger.warn("Reload: build/resources/test not found for project root (testResourcesDir: {})", testResourcesDir.getAbsolutePath());
            }
        }
        if (loadedCount == 0) {
            if (!mappingsSubDir.exists() || !mappingsSubDir.isDirectory()) {
                logger.warn("Reload skipped: invocation dir has no mappings folder or does not exist: {}", invocationMappingsDir.getAbsolutePath());
            }
            logger.warn("Reload loaded 0 stubs from {} - all requests will return 404", invocationMappingsDir.getAbsolutePath());
        } else if (!usedFallback) {
            logger.info("Reloaded {} stub(s) on server from {}", loadedCount, invocationMappingsDir.getAbsolutePath());
        }
        // Eagerly clean up temp dir used for ignore-pattern transformation (avoids shutdown hook leak)
        if (tempPathUsed != null) {
            try {
                java.nio.file.Files.walk(tempPathUsed).sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { java.nio.file.Files.deleteIfExists(p); } catch (java.io.IOException ignored) { }
                        });
            } catch (java.io.IOException ignored) { }
        }
        server.stubFor(
                WireMock.any(WireMock.anyUrl())
                        .atPriority(1000)
                        .willReturn(WireMock.aResponse()
                                .withStatus(404)
                                .withBody("No matching stub mapping found")));
    }

    /**
     * Loads stub mappings from invocation dir and adds them with priority-by-specificity:
     * same (method, url) stubs are ordered by body pattern length descending, then by a
     * tie-break so the most specific request body is tried before less specific ones.
     * Tie-break prefers body containing a preferred plan marker, then any plan marker, then none.
     */
    private static int loadMappingsFromDir(WireMockServer server, File invocationDir) {
        File mappingsSubDir = new File(invocationDir, DIR_MAPPINGS);
        if (!mappingsSubDir.exists() || !mappingsSubDir.isDirectory()) {
            return 0;
        }
        File[] jsonFiles = mappingsSubDir.listFiles((d, n) -> n != null && n.toLowerCase().endsWith(JSON_EXT));
        if (jsonFiles == null || jsonFiles.length == 0) {
            return 0;
        }
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        List<MappingEntry> entries = new ArrayList<>();
        for (File mappingFile : jsonFiles) {
            try {

                String json = new String(Files.readAllBytes(mappingFile.toPath()));
                com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(json);
                String requestKey = requestKeyFromMapping(root);
                int bodyLength = bodyPatternLengthFromMapping(root);
                String bodyContent = bodyPatternContentFromMapping(root);
                int ratePlanTieBreak = ratePlanTieBreakFromBody(bodyContent);
                entries.add(new MappingEntry(json, requestKey, bodyLength, bodyContent, ratePlanTieBreak));
            } catch (Exception e) {
                logger.warn("Failed to read mapping {}: {}", mappingFile.getName(), e.getMessage());
            }
        }
        Map<String, List<MappingEntry>> byKey = new LinkedHashMap<>();
        for (MappingEntry e : entries) {
            byKey.computeIfAbsent(e.requestKey, k -> new ArrayList<>()).add(e);
        }
        int priority = 0;
        for (List<MappingEntry> group : byKey.values()) {
            group.sort(Comparator.comparingInt(MappingEntry::bodyLength).reversed()
                    .thenComparingInt(MappingEntry::ratePlanTieBreak).reversed()
                    .thenComparing(MappingEntry::bodyContent));
            for (MappingEntry entry : group) {
                try {
                    StubMapping mapping = StubMapping.buildFrom(entry.json);
                    // Auto-fix duplicate IDs to prevent silent failures
                    try {
                        mapping.setPriority(priority);
                        server.addStubMapping(mapping);
                    } catch (Exception firstEx) {
                        // Try regenerating ID on conflict
                        try {
                            com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(entry.json);
                            if (root.has("id")) {
                                ((com.fasterxml.jackson.databind.node.ObjectNode)root).put("id", java.util.UUID.randomUUID().toString());
                                String fixedJson = root.toString();
                                mapping = StubMapping.buildFrom(fixedJson);
                                mapping.setPriority(priority);
                                server.addStubMapping(mapping);
                            }
                        } catch (Exception ignored) {}
                    }
                    priority++;
                } catch (Exception ex) {
                    logger.warn("Failed to load mapping: {}", ex.getMessage());
                }
            }
        }
        return priority;
    }

    private static String requestKeyFromMapping(com.fasterxml.jackson.databind.JsonNode root) {
        com.fasterxml.jackson.databind.JsonNode req = root != null ? root.get(REQ_KEY_REQUEST) : null;
        if (req == null || !req.isObject()) {
            return "GET /";
        }
        String method = req.has(REQ_KEY_METHOD) ? req.get(REQ_KEY_METHOD).asText("GET") : "GET";
        String url = null;
        if (req.has("url")) {
            url = req.get("url").asText();
        } else if (req.has("urlPathPattern")) {
            url = req.get("urlPathPattern").asText();
        } else if (req.has(REQ_KEY_URLPATH)) {
            url = req.get(REQ_KEY_URLPATH).asText();
        }
        if (url == null) {
            url = "/";
        }
        return method.toUpperCase() + " " + url;
    }

    private static int bodyPatternLengthFromMapping(com.fasterxml.jackson.databind.JsonNode root) {
        com.fasterxml.jackson.databind.JsonNode req = root != null ? root.get(REQ_KEY_REQUEST) : null;
        if (req == null) {
            return 0;
        }
        com.fasterxml.jackson.databind.JsonNode bodyPatterns = req.get(BODY_PATTERNS_KEY);
        if (bodyPatterns == null || !bodyPatterns.isArray()) {
            return 0;
        }
        int len = 0;
        for (com.fasterxml.jackson.databind.JsonNode p : bodyPatterns) {
            if (p.has(EQUAL_TO_XML_KEY)) {
                len += p.get(EQUAL_TO_XML_KEY).asText().length();
            }
            if (p.has(EQUAL_TO_JSON_KEY)) {
                len += p.get(EQUAL_TO_JSON_KEY).asText().length();
            }
            if (p.has(EQUAL_TO_KEY)) {
                len += p.get(EQUAL_TO_KEY).asText().length();
            }
        }
        return len;
    }

    private static String bodyPatternContentFromMapping(com.fasterxml.jackson.databind.JsonNode root) {
        com.fasterxml.jackson.databind.JsonNode req = root != null ? root.get(REQ_KEY_REQUEST) : null;
        if (req == null) {
            return "";
        }
        com.fasterxml.jackson.databind.JsonNode bodyPatterns = req.get(BODY_PATTERNS_KEY);
        if (bodyPatterns == null || !bodyPatterns.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (com.fasterxml.jackson.databind.JsonNode p : bodyPatterns) {
            if (p.has(EQUAL_TO_XML_KEY)) {
                sb.append(p.get(EQUAL_TO_XML_KEY).asText());
            }
            if (p.has(EQUAL_TO_JSON_KEY)) {
                sb.append(p.get(EQUAL_TO_JSON_KEY).asText());
            }
            if (p.has(EQUAL_TO_KEY)) {
                sb.append(p.get(EQUAL_TO_KEY).asText());
            }
        }
        return sb.toString();
    }

    /**
     * Tie-break for same (method, url) stubs when body length is equal: prefer bodies that
     * include a plan candidate section over those that don't, so more specific plans are tried
     * before generic ones. Uses a generic marker that matches existing stub bodies but does not
     * encode any consumer-specific plan codes.
     */
    private static int ratePlanTieBreakFromBody(String bodyContent) {
        if (bodyContent == null) {
            return 0;
        }
        // Generic marker: any occurrence of a plan candidate element is treated as \"more specific\"
        if (bodyContent.contains("RatePlanCandidate")) {
            return 1;
        }
        return 0;
    }

    private static final class MappingEntry {
        final String json;
        final String requestKey;
        final int bodyLength;
        final String bodyContent;
        final int ratePlanTieBreak;

        MappingEntry(String json, String requestKey, int bodyLength, String bodyContent, int ratePlanTieBreak) {
            this.json = json;
            this.requestKey = requestKey;
            this.bodyLength = bodyLength;
            this.bodyContent = bodyContent;
            this.ratePlanTieBreak = ratePlanTieBreak;
        }

        String bodyContent() {
            return bodyContent;
        }

        int bodyLength() {
            return bodyLength;
        }

        int ratePlanTieBreak() {
            return ratePlanTieBreak;
        }
    }

    /**
     * Resolves build/resources/test when testResourcesDir is src/test/resources and build exists (Gradle).
     */
    private static File resolveBuildTestResourcesDir(File testResourcesDir) {
        if (testResourcesDir == null || !testResourcesDir.exists() || !testResourcesDir.isDirectory()) {
            return null;
        }
        String path = testResourcesDir.getAbsolutePath();
        if (!path.replace('\\', '/').contains("src/test/resources")) {
            return null;
        }
        File projectRoot = testResourcesDir.getParentFile();
        if (projectRoot != null) {
            projectRoot = projectRoot.getParentFile();
        }
        if (projectRoot != null) {
            projectRoot = projectRoot.getParentFile();
        }
        if (projectRoot == null || !projectRoot.exists()) {
            return null;
        }
        File buildTestResources = new File(projectRoot, "build/resources/test");
        return (buildTestResources.exists() && buildTestResources.isDirectory()) ? buildTestResources : null;
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
            File mappingsSubDir = new File(mappingsDir, DIR_MAPPINGS);
            if (!mappingsSubDir.exists() || !mappingsSubDir.isDirectory()) {
                return;
            }
            
            File[] mappingFiles = mappingsSubDir.listFiles((dir, name) -> name.toLowerCase().endsWith(JSON_EXT));
            if (mappingFiles == null) {
                return;
            }
            // When loading from an invocation dir, mapping files have no method prefix (e.g. sap_bc_...json).
            // When loading from merged class-level dir, files are named methodName_originalName.json.
            // Only filter by prefix if at least one file has it (merged case).
            boolean filterByMethodPrefix = testMethodName != null && java.util.Arrays.stream(mappingFiles)
                    .anyMatch(f -> f.getName().startsWith(testMethodName + "_"));
            
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = 
                    new com.fasterxml.jackson.databind.ObjectMapper();
            
            for (File mappingFile : mappingFiles) {
                try {
                    if (filterByMethodPrefix && !mappingFile.getName().startsWith(testMethodName + "_")) {
                        continue;
                    }
                    
                    com.fasterxml.jackson.databind.JsonNode mapping = objectMapper.readTree(mappingFile);
                    com.fasterxml.jackson.databind.node.ObjectNode mappingObj = 
                            (com.fasterxml.jackson.databind.node.ObjectNode) mapping;
                    
                    com.fasterxml.jackson.databind.JsonNode requestNode = mappingObj.get(REQ_KEY_REQUEST);
                    if (requestNode != null && requestNode.isObject()) {
                        com.fasterxml.jackson.databind.node.ObjectNode requestObj = 
                                (com.fasterxml.jackson.databind.node.ObjectNode) requestNode;
                        
                        com.fasterxml.jackson.databind.JsonNode bodyPatternsNode = requestObj.get(BODY_PATTERNS_KEY);
                        if (bodyPatternsNode != null && bodyPatternsNode.isArray()) {
                            boolean modified = false;
                            for (com.fasterxml.jackson.databind.JsonNode patternNode : bodyPatternsNode) {
                                if (patternNode.isObject()) {
                                    com.fasterxml.jackson.databind.node.ObjectNode patternObj = 
                                            (com.fasterxml.jackson.databind.node.ObjectNode) patternNode;
                                    
                                    // Check for equalToJson first (WireMock 3 format)
                                    com.fasterxml.jackson.databind.JsonNode matcherNode = patternObj.get(EQUAL_TO_JSON_KEY);
                                    String matcherKey = EQUAL_TO_JSON_KEY;
                                    
                                    // Check for equalToXml (WireMock 3 format)
                                    if (matcherNode == null) {
                                        matcherNode = patternObj.get(EQUAL_TO_XML_KEY);
                                        if (matcherNode != null) {
                                            matcherKey = EQUAL_TO_XML_KEY;
                                        }
                                    }
                                    
                                    // Fall back to equalTo (WireMock 2 format or string matching)
                                    if (matcherNode == null) {
                                        matcherNode = patternObj.get(EQUAL_TO_KEY);
                                        matcherKey = EQUAL_TO_KEY;
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
                                            if (matcherKey.equals(EQUAL_TO_KEY) || !normalizedJson.equals(expectedBody)) {
                                                patternObj.remove(matcherKey);
                                                patternObj.put(EQUAL_TO_JSON_KEY, normalizedJson);
                                                patternObj.put("ignoreArrayOrder", false);
                                                patternObj.put("ignoreExtraElements", true);
                                                modified = true;
                                                logger.debug("Changed {} to equalToJson with json-unit.ignore placeholders", matcherKey);
                                            }
                                        } else if (isXml) {
                                            // It's XML, replace ignored elements/attributes with ${xmlunit.ignore}
                                            String normalizedXml = normalizeXmlStringWithPlaceholders(expectedBody, ignorePatterns);
                                            
                                            // Convert equalTo to equalToXml for WireMock 3 compatibility
                                            if (matcherKey.equals(EQUAL_TO_KEY) || !normalizedXml.equals(expectedBody)) {
                                                patternObj.remove(matcherKey);
                                                patternObj.put(EQUAL_TO_XML_KEY, normalizedXml);
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
            // Deduplicate: identical normalized requests create competing stubs; keep first only.
            java.util.Set<String> seenKeys = new java.util.HashSet<>();
            for (File mf : mappingFiles) {
                try {
                    com.fasterxml.jackson.databind.JsonNode m = objectMapper.readTree(mf);
                    String url = ((com.fasterxml.jackson.databind.node.ObjectNode)m.get(REQ_KEY_REQUEST)).get("url").asText("");
                    String method = ((com.fasterxml.jackson.databind.node.ObjectNode)m.get(REQ_KEY_REQUEST)).get(REQ_KEY_METHOD).asText("");
                    String body = ((com.fasterxml.jackson.databind.node.ObjectNode)m.get(REQ_KEY_REQUEST)).get(BODY_PATTERNS_KEY).isArray()
                            ? ((com.fasterxml.jackson.databind.node.ObjectNode)((com.fasterxml.jackson.databind.node.ArrayNode)(m.get(REQ_KEY_REQUEST)).get(BODY_PATTERNS_KEY)).get(0)).toString()
                            : "";
                    String key = url + "|" + method + "|" + body;
                    if (!seenKeys.add(key)) {
                        mf.delete();
                        logger.info("Removed duplicate stub {}", mf.getName());
                    }
                } catch (Exception ignore) {}
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
            List<String> annotationIgnorePatterns,
            List<String> annotationDontIgnorePatterns) {
        try {
            File mappingsSubDir = new File(mappingsDir, DIR_MAPPINGS);
            if (!mappingsSubDir.exists() || !mappingsSubDir.isDirectory()) {
                return;
            }
            
            File[] mappingFiles = mappingsSubDir.listFiles((dir, name) -> name.toLowerCase().endsWith(JSON_EXT));
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
                    
                    // Merge with annotation patterns, then apply dontIgnore
                    if (annotationIgnorePatterns != null && !annotationIgnorePatterns.isEmpty()) {
                        ignorePatterns.removeAll(annotationIgnorePatterns);
                        ignorePatterns.addAll(annotationIgnorePatterns);
                    }
                    ignorePatterns = applyDontIgnorePatterns(ignorePatterns, annotationDontIgnorePatterns);
                    
                    if (ignorePatterns.isEmpty()) {
                        continue;
                    }
                    
                    com.fasterxml.jackson.databind.JsonNode mapping = objectMapper.readTree(mappingFile);
                    com.fasterxml.jackson.databind.node.ObjectNode mappingObj = 
                            (com.fasterxml.jackson.databind.node.ObjectNode) mapping;
                    
                    com.fasterxml.jackson.databind.JsonNode requestNode = mappingObj.get(REQ_KEY_REQUEST);
                    if (requestNode != null && requestNode.isObject()) {
                        com.fasterxml.jackson.databind.node.ObjectNode requestObj = 
                                (com.fasterxml.jackson.databind.node.ObjectNode) requestNode;
                        
                        com.fasterxml.jackson.databind.JsonNode bodyPatternsNode = requestObj.get(BODY_PATTERNS_KEY);
                        if (bodyPatternsNode != null && bodyPatternsNode.isArray()) {
                            boolean modified = false;
                            for (com.fasterxml.jackson.databind.JsonNode patternNode : bodyPatternsNode) {
                                if (patternNode.isObject()) {
                                    com.fasterxml.jackson.databind.node.ObjectNode patternObj = 
                                            (com.fasterxml.jackson.databind.node.ObjectNode) patternNode;
                                    
                                    com.fasterxml.jackson.databind.JsonNode matcherNode = patternObj.get(EQUAL_TO_JSON_KEY);
                                    String matcherKey = EQUAL_TO_JSON_KEY;
                                    
                                    if (matcherNode == null) {
                                        matcherNode = patternObj.get(EQUAL_TO_XML_KEY);
                                        if (matcherNode != null) {
                                            matcherKey = EQUAL_TO_XML_KEY;
                                        }
                                    }
                                    
                                    if (matcherNode == null) {
                                        matcherNode = patternObj.get(EQUAL_TO_KEY);
                                        matcherKey = EQUAL_TO_KEY;
                                    }
                                    
                                    if (matcherNode != null && matcherNode.isTextual()) {
                                        String expectedBody = matcherNode.asText();
                                        
                                        boolean isJson = com.stablemock.core.analysis.JsonBodyParser.isJson(expectedBody);
                                        boolean isXml = com.stablemock.core.analysis.XmlBodyParser.isXml(expectedBody);
                                        
                                        if (isJson) {
                                            String normalizedJson = normalizeJsonStringWithPlaceholders(expectedBody, ignorePatterns);
                                            
                                            if (matcherKey.equals(EQUAL_TO_KEY) || !normalizedJson.equals(expectedBody)) {
                                                patternObj.remove(matcherKey);
                                                patternObj.put(EQUAL_TO_JSON_KEY, normalizedJson);
                                                patternObj.put("ignoreArrayOrder", false);
                                                patternObj.put("ignoreExtraElements", true);
                                                modified = true;
                                                logger.debug("Modified mapping {} for test method {} with json-unit.ignore placeholders", 
                                                        mappingFile.getName(), matchingMethod);
                                            }
                                        } else if (isXml) {
                                            String normalizedXml = normalizeXmlStringWithPlaceholders(expectedBody, ignorePatterns);
                                            
                                            if (matcherKey.equals(EQUAL_TO_KEY) || !normalizedXml.equals(expectedBody)) {
                                                patternObj.remove(matcherKey);
                                                patternObj.put(EQUAL_TO_XML_KEY, normalizedXml);
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
            // Deduplicate identical normalized requests within same invocation (keep invocation prefix in key).
            java.util.Set<String> seenKeys = new java.util.HashSet<>();
            for (File mf : mappingFiles) {
                try {
                    com.fasterxml.jackson.databind.JsonNode m = objectMapper.readTree(mf);
                    String url = ((com.fasterxml.jackson.databind.node.ObjectNode)m.get(REQ_KEY_REQUEST)).get("url").asText("");
                    String method = ((com.fasterxml.jackson.databind.node.ObjectNode)m.get(REQ_KEY_REQUEST)).get(REQ_KEY_METHOD).asText("");
                    String body = ((com.fasterxml.jackson.databind.node.ObjectNode)m.get(REQ_KEY_REQUEST)).get(BODY_PATTERNS_KEY).isArray()
                            ? ((com.fasterxml.jackson.databind.node.ObjectNode)((com.fasterxml.jackson.databind.node.ArrayNode)(m.get(REQ_KEY_REQUEST)).get(BODY_PATTERNS_KEY)).get(0)).toString()
                            : "";
                    // Include invocation prefix (before last _hash.json) to avoid cross-invocation dedup.
                    String fn = mf.getName().replace(JSON_EXT, "");
                    int lastUnderscore = fn.lastIndexOf('_');
                    String prefix = lastUnderscore > 0 ? fn.substring(0, lastUnderscore) : fn;
                    String key = prefix + "|" + url + "|" + method + "|" + body;
                    if (!seenKeys.add(key)) {
                        mf.delete();
                        logger.info("Removed duplicate stub {}", mf.getName());
                    }
                } catch (Exception ignore) {}
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
                    try {
                        applyXmlIgnorePattern(doc, xpathPattern);
                    } catch (Exception e) {
                        logger.warn("Failed to apply ignore pattern '{}': {} (annotation or detected-fields pattern may be invalid)", pattern, e.getMessage());
                        throw e;
                    }
                }
            }
            
            // Convert back to string (explicit UTF-8 to avoid invalid character issues)
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");

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

    /**
     * Applies dontIgnore semantics on top of an ignore pattern list.
     * Keeps all existing behavior when dontIgnore is empty, but when present:
     * - For XML patterns, removes any ignore pattern whose XPath targets a field whose local-name()
     *   appears in a dontIgnore XPath (generic or specific).
     * - For JSON/GraphQL patterns, removes any ignore pattern whose JSON path ends with the same
     *   tail as a dontIgnore JSON/GraphQL pattern.
     * Package-private for testing.
     */
    static List<String> applyDontIgnorePatterns(List<String> ignorePatterns, List<String> dontIgnorePatterns) {
        if (ignorePatterns == null || ignorePatterns.isEmpty() || dontIgnorePatterns == null || dontIgnorePatterns.isEmpty()) {
            return ignorePatterns;
        }
        // Pre-normalize dontIgnore for json / gql patterns
        List<String> normalizedDontIgnore = new java.util.ArrayList<>();
        for (String p : dontIgnorePatterns) {
            if (p == null) {
                continue;
            }
            normalizedDontIgnore.add(normalizeGraphQlPattern(p));
        }

        List<String> result = new java.util.ArrayList<>();
        for (String ignore : ignorePatterns) {
            if (ignore == null) {
                continue;
            }
            if (!shouldRemoveByDontIgnore(ignore, normalizedDontIgnore)) {
                result.add(ignore);
            }
        }
        return result;
    }

    private static boolean shouldRemoveByDontIgnore(String ignorePattern, List<String> dontIgnorePatterns) {
        if (dontIgnorePatterns == null || dontIgnorePatterns.isEmpty() || ignorePattern == null) {
            return false;
        }
        if (ignorePattern.startsWith("xml:")) {
            String ignoreXPath = ignorePattern.substring(4);
            java.util.Set<String> ignoreNames = new java.util.HashSet<>(extractElementPathFromXPath(ignoreXPath));
            String ignoreAttr = extractAttributeNameFromXPath(ignoreXPath);
            if (ignoreAttr != null) {
                ignoreNames.add(ignoreAttr);
            }
            if (ignoreNames.isEmpty()) {
                return false;
            }
            for (String d : dontIgnorePatterns) {
                if (d == null || !d.startsWith("xml:")) {
                    continue;
                }
                String dontXPath = d.substring(4);
                java.util.Set<String> dontNames = new java.util.HashSet<>(extractElementPathFromXPath(dontXPath));
                String dontAttr = extractAttributeNameFromXPath(dontXPath);
                if (dontAttr != null) {
                    dontNames.add(dontAttr);
                }
                if (!dontNames.isEmpty() && ignoreNames.containsAll(dontNames)) {
                    return true;
                }
            }
            return false;
        }
        if (ignorePattern.startsWith("json:")) {
            String ignorePath = ignorePattern.substring(5);
            for (String d : dontIgnorePatterns) {
                if (d == null) {
                    continue;
                }
                String normalized = normalizeGraphQlPattern(d);
                if (normalized != null && normalized.startsWith("json:")) {
                    String dontPath = normalized.substring(5);
                    if (!dontPath.isEmpty() && (ignorePath.equals(dontPath) || ignorePath.endsWith("." + dontPath))) {
                        return true;
                    }
                }
            }
        }
        return false;
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
     * Auto-detected patterns from XmlFieldDetector use root-relative form "*[local-name()='X']/...";
     * normalize to "//*[local-name()='X']/..." so the same matching logic applies.
     */
    private static void applyXmlIgnorePattern(Document doc, String xpathPattern) {
        if (!xpathPattern.startsWith("//") && xpathPattern.startsWith("*[local-name()=")) {
            xpathPattern = "//" + xpathPattern;
        }
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
                        element.setTextContent(PLACEHOLDER_XML_IGNORE);
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
                element.setTextContent(PLACEHOLDER_XML_IGNORE);
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
                element.setAttribute(attrName, PLACEHOLDER_XML_IGNORE);
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

    /**
     * Returns true if the throwable (or any cause in the chain) indicates a port bind failure,
     * e.g. BindException or "Address already in use" / "Failed to bind".
     */
    private static boolean isPortBindFailure(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof java.net.BindException) {
                return true;
            }
            String msg = c.getMessage();
            if (msg != null && (msg.contains("Address already in use") || msg.contains("Failed to bind"))) {
                return true;
            }
        }
        return false;
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
