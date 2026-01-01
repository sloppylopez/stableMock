package com.stablemock.core.storage;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.recording.RecordSpecBuilder;
import com.github.tomakehurst.wiremock.matching.ContentPattern;
import com.github.tomakehurst.wiremock.matching.EqualToJsonPattern;
import com.github.tomakehurst.wiremock.matching.EqualToPattern;
import com.github.tomakehurst.wiremock.matching.EqualToXmlPattern;
import com.github.tomakehurst.wiremock.matching.UrlPattern;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Handles mapping storage operations for single annotation case.
 */
public final class SingleAnnotationMappingStorage extends BaseMappingStorage {
    
    private static final Logger logger = LoggerFactory.getLogger(SingleAnnotationMappingStorage.class);
    
    private SingleAnnotationMappingStorage() {
        // utility class
    }
    
    public static void saveMappings(WireMockServer wireMockServer, File mappingsDir, String targetUrl) throws IOException {
        File mappingsSubDir = new File(mappingsDir, "mappings");
        File filesSubDir = new File(mappingsDir, "__files");

        if (!mappingsSubDir.exists() && !mappingsSubDir.mkdirs()) {
            throw new IOException("Failed to create mappings subdirectory: " + mappingsSubDir.getAbsolutePath());
        }
        if (!filesSubDir.exists() && !filesSubDir.mkdirs()) {
            throw new IOException("Failed to create __files subdirectory: " + filesSubDir.getAbsolutePath());
        }

        RecordSpecBuilder builder = new RecordSpecBuilder()
                .forTarget(targetUrl)
                .makeStubsPersistent(true)
                .extractTextBodiesOver(255);

        com.github.tomakehurst.wiremock.recording.SnapshotRecordResult result = wireMockServer.snapshotRecord(builder.build());
        List<StubMapping> mappings = result.getStubMappings();

        WireMockConfiguration tempConfig = WireMockConfiguration.wireMockConfig()
                .dynamicPort()
                .notifier(new com.github.tomakehurst.wiremock.common.ConsoleNotifier(false))
                .usingFilesUnderDirectory(mappingsDir.getAbsolutePath());
        
        WireMockServer tempServer = new WireMockServer(tempConfig);
        tempServer.start();
        
        try {
            // Regenerate IDs to avoid conflicts when re-recording
            for (StubMapping mapping : mappings) {
                mapping.setId(java.util.UUID.randomUUID());
                tempServer.addStubMapping(mapping);
            }
            tempServer.saveMappings();
            
            // Small delay to ensure files are flushed to disk (especially important for WSL)
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } finally {
            tempServer.stop();
        }
        
        logger.info("Saved {} mappings to {}", mappings.size(), mappingsSubDir.getAbsolutePath());
    }
    
    /**
     * Saves mappings for a specific test method based on newly recorded serve events.
     *
     * @param wireMockServer         the WireMock server instance from which to read recorded serve events
     * @param testMethodMappingsDir  the directory where mappings for this test method should be stored
     * @param baseMappingsDir         the base directory containing existing mappings for the test class or suite
     * @param targetUrl               the target URL that was recorded against
     * @param existingRequestCount    the number of serve events that existed before this test method was executed
     * @param scenario                if {@code true}, creates WireMock scenario mappings for sequential responses
     *                                when the same endpoint is called multiple times. When enabled, multiple requests
     *                                to the same endpoint will be saved as separate mappings with proper scenario
     *                                state transitions (Started -> state-2 -> state-3 -> ...)
     * @param testMethodStartTime     timestamp when the test method started (milliseconds since epoch), or null to skip timestamp filtering
     * @throws IOException if an I/O error occurs while creating directories or saving mappings
     */
    public static void saveMappingsForTestMethod(WireMockServer wireMockServer, File testMethodMappingsDir, 
            File baseMappingsDir, String targetUrl, int existingRequestCount, boolean scenario, Long testMethodStartTime) throws IOException {
        File testMethodMappingsSubDir = new File(testMethodMappingsDir, "mappings");
        File testMethodFilesSubDir = new File(testMethodMappingsDir, "__files");

        if (!testMethodMappingsSubDir.exists() && !testMethodMappingsSubDir.mkdirs()) {
            throw new IOException("Failed to create mappings subdirectory: " + testMethodMappingsSubDir.getAbsolutePath());
        }
        if (!testMethodFilesSubDir.exists() && !testMethodFilesSubDir.mkdirs()) {
            throw new IOException("Failed to create __files subdirectory: " + testMethodFilesSubDir.getAbsolutePath());
        }

        List<com.github.tomakehurst.wiremock.stubbing.ServeEvent> allServeEvents = wireMockServer.getAllServeEvents();
        logger.info("=== RECORDING: Total serve events in server: {}, existing count: {} ===", 
            allServeEvents.size(), existingRequestCount);
        
        // WireMock returns serve events in REVERSE chronological order (newest first)
        // So we need to get elements from the START of the list, not the end
        int newEventsCount = allServeEvents.size() - existingRequestCount;
        List<com.github.tomakehurst.wiremock.stubbing.ServeEvent> candidateEvents = 
            newEventsCount > 0 ? allServeEvents.subList(0, newEventsCount) : new java.util.ArrayList<>();
        
        // Use count-based filtering (WireMock returns events in reverse chronological order)
        // Clean the mappings directory first to ensure we don't mix requests from different test runs
        if (testMethodMappingsSubDir.exists()) {
            java.io.File[] existingMappings = testMethodMappingsSubDir.listFiles();
            if (existingMappings != null && existingMappings.length > 0) {
                logger.info("Cleaning {} existing mapping(s) from test method directory before saving new ones", existingMappings.length);
                for (java.io.File mapping : existingMappings) {
                    if (mapping.isFile() && mapping.getName().endsWith(".json")) {
                        if (!mapping.delete()) {
                            logger.warn("Failed to delete existing mapping file: {}", mapping.getAbsolutePath());
                        }
                    }
                }
            }
        }
        
        List<com.github.tomakehurst.wiremock.stubbing.ServeEvent> testMethodServeEvents = candidateEvents;
        
        if (testMethodServeEvents.isEmpty()) {
            logger.warn("No serve events for this test method (existing: {}, total: {}, filtered: {})", 
                existingRequestCount, allServeEvents.size(), candidateEvents.size());
            return;
        }

        logger.info("=== RECORDING: {} serve event(s) for this test method ===", testMethodServeEvents.size());
        for (int i = 0; i < testMethodServeEvents.size(); i++) {
            com.github.tomakehurst.wiremock.stubbing.ServeEvent se = testMethodServeEvents.get(i);
            String bodyPreview = se.getRequest().getBodyAsString();
            if (bodyPreview != null && bodyPreview.length() > 100) {
                bodyPreview = bodyPreview.substring(0, 100) + "...";
            }
            logger.info("  ServeEvent[{}]: {} {} | Body preview: {}", i, 
                se.getRequest().getMethod().getName(), se.getRequest().getUrl(), 
                bodyPreview != null ? bodyPreview : "(no body)");
        }

        // Match mappings to serve events by signature AND body content (when needed)
        // First, check if we have multiple serve events with the same signature but different bodies
        // If so, we need to use body matching to distinguish them
        java.util.Map<String, java.util.List<Integer>> serveEventsBySignature = new java.util.HashMap<>();
        for (int i = 0; i < testMethodServeEvents.size(); i++) {
            com.github.tomakehurst.wiremock.stubbing.ServeEvent se = testMethodServeEvents.get(i);
            String seMethod = se.getRequest().getMethod().getName();
            String sePath = normalizePath(se.getRequest().getUrl());
            String seSignature = seMethod.toUpperCase() + ":" + sePath;
            serveEventsBySignature.computeIfAbsent(seSignature, k -> new java.util.ArrayList<>()).add(i);
        }
        
        // Determine if we need body matching: when multiple serve events share the same signature
        // AND they have different bodies (for POST/PUT/PATCH)
        boolean needsBodyMatching = false;
        for (java.util.List<Integer> indices : serveEventsBySignature.values()) {
            if (indices.size() > 1) {
                // Multiple serve events with same signature - check if they have different bodies
                java.util.Set<String> bodies = new java.util.HashSet<>();
                boolean hasPostPutPatch = false;
                for (Integer idx : indices) {
                    com.github.tomakehurst.wiremock.stubbing.ServeEvent se = testMethodServeEvents.get(idx);
                    String method = se.getRequest().getMethod().getName();
                    if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || 
                        "PATCH".equalsIgnoreCase(method)) {
                        hasPostPutPatch = true;
                        String body = se.getRequest().getBodyAsString();
                        if (body != null && !body.isEmpty()) {
                            bodies.add(body);
                        } else {
                            bodies.add(""); // Empty body
                        }
                    }
                }
                // If we have multiple POST/PUT/PATCH requests with the same signature but different bodies,
                // we need body matching to distinguish them
                if (hasPostPutPatch && bodies.size() > 1) {
                    needsBodyMatching = true;
                    logger.debug("Detected {} serve events with same signature but {} different bodies - enabling body matching", 
                        indices.size(), bodies.size());
                    break;
                }
            }
        }

        List<StubMapping> testMethodMappings = new java.util.ArrayList<>();

        if (needsBodyMatching) {
            java.util.Set<String> processedKeys = new java.util.HashSet<>();
            for (int seIdx = 0; seIdx < testMethodServeEvents.size(); seIdx++) {
                com.github.tomakehurst.wiremock.stubbing.ServeEvent se = testMethodServeEvents.get(seIdx);
                String seMethod = se.getRequest().getMethod().getName();
                String sePath = normalizePath(se.getRequest().getUrl());
                String seSignature = seMethod.toUpperCase() + ":" + sePath;
                String seBody = se.getRequest().getBodyAsString();
                String dedupeKey = seSignature + "::" + (seBody != null ? seBody : "");
                if (!processedKeys.add(dedupeKey)) {
                    continue;
                }

                UrlPattern urlPattern = WireMock.urlPathEqualTo(sePath);
                RequestPatternBuilder requestPattern = RequestPatternBuilder.newRequestPattern(
                        RequestMethod.fromString(seMethod), urlPattern);
                if (seBody != null && !seBody.isEmpty()
                        && ("POST".equalsIgnoreCase(seMethod)
                        || "PUT".equalsIgnoreCase(seMethod)
                        || "PATCH".equalsIgnoreCase(seMethod))) {
                    requestPattern.withRequestBody(buildBodyPattern(seBody));
                }

                RecordSpecBuilder builder = new RecordSpecBuilder()
                        .forTarget(targetUrl)
                        .makeStubsPersistent(true)
                        .extractTextBodiesOver(255)
                        .onlyRequestsMatching(requestPattern);

                com.github.tomakehurst.wiremock.recording.SnapshotRecordResult filteredResult =
                        wireMockServer.snapshotRecord(builder.build());
                List<StubMapping> filteredMappings = filteredResult.getStubMappings();

                if (filteredMappings.isEmpty()) {
                    logger.warn("=== RECORDING WARNING: No mapping created for serve event {} {} (body hash {}) ===",
                            seMethod, sePath, seBody != null ? seBody.hashCode() : "none");
                } else {
                    testMethodMappings.addAll(filteredMappings);
                    logger.info("  ✓ Mapping recorded for {} {} ({} mapping(s))",
                            seMethod, sePath, filteredMappings.size());
                }
            }
        } else {
            // Use snapshotRecord to create mappings, then match to serve events by signature
            RecordSpecBuilder builder = new RecordSpecBuilder()
                    .forTarget(targetUrl)
                    .makeStubsPersistent(true)
                    .extractTextBodiesOver(255);

            com.github.tomakehurst.wiremock.recording.SnapshotRecordResult result = wireMockServer.snapshotRecord(builder.build());
            List<StubMapping> allMappings = result.getStubMappings();

            logger.info("=== RECORDING: snapshotRecord created {} mapping(s), matching to {} serve event(s) ===",
                    allMappings.size(), testMethodServeEvents.size());

            java.util.Set<Integer> matchedServeEventIndices = new java.util.HashSet<>();

            for (int mIdx = 0; mIdx < allMappings.size(); mIdx++) {
                StubMapping mapping = allMappings.get(mIdx);
                String mappingMethod = mapping.getRequest().getMethod() != null
                        ? mapping.getRequest().getMethod().getName() : "";
                String mappingUrl = mapping.getRequest().getUrl() != null
                        ? mapping.getRequest().getUrl()
                        : (mapping.getRequest().getUrlPath() != null ? mapping.getRequest().getUrlPath() : "");
                String mappingPath = normalizePath(mappingUrl);
                String mappingSignature = mappingMethod.toUpperCase() + ":" + mappingPath;

                Integer matchedSeIdx = null;
                for (int seIdx = 0; seIdx < testMethodServeEvents.size(); seIdx++) {
                    if (matchedServeEventIndices.contains(seIdx)) {
                        continue;
                    }

                    com.github.tomakehurst.wiremock.stubbing.ServeEvent se = testMethodServeEvents.get(seIdx);
                    String seMethod = se.getRequest().getMethod().getName();
                    String sePath = normalizePath(se.getRequest().getUrl());
                    String seSignature = seMethod.toUpperCase() + ":" + sePath;

                    if (!mappingSignature.equals(seSignature)) {
                        continue;
                    }

                    matchedSeIdx = seIdx;
                    break;
                }

                if (matchedSeIdx != null) {
                    testMethodMappings.add(mapping);
                    matchedServeEventIndices.add(matchedSeIdx);
                    logger.info("  ✓ Match[M{}->SE{}]: {} {} -> {} {}",
                            mIdx, matchedSeIdx, mappingMethod, mappingUrl,
                            testMethodServeEvents.get(matchedSeIdx).getRequest().getMethod().getName(),
                            testMethodServeEvents.get(matchedSeIdx).getRequest().getUrl());
                } else {
                    logger.debug("  ⊗ Skip[M{}]: No unmatched serve events found for signature '{}'",
                            mIdx, mappingSignature);
                }
            }
        }

        logger.info("=== RECORDING: Matched {}/{} mapping(s) ===",
                testMethodMappings.size(), testMethodServeEvents.size());

        if (testMethodMappings.size() < testMethodServeEvents.size()) {
            int unmatchedCount = testMethodServeEvents.size() - testMethodMappings.size();
            logger.warn("=== RECORDING WARNING: Only {}/{} mappings matched! {} request(s) will fail in playback ===",
                    testMethodMappings.size(), testMethodServeEvents.size(), unmatchedCount);
            if (needsBodyMatching) {
                logger.warn("This is likely because snapshotRecord only creates one mapping per signature (method + URL),");
                logger.warn("not per unique body. Consider using different URLs or methods for tests with different bodies.");
            }
        }

        if (testMethodMappings.isEmpty()) {
            logger.error("=== RECORDING FAILED: No mappings created! ===");
            return;
        }

        // Apply scenario mode: if scenario=true and we have multiple requests to the same endpoint,
        // create separate mappings with proper scenario state transitions
        if (scenario) {
            logger.info("=== SCENARIO MODE: Processing {} mapping(s) for scenario state transitions ===", testMethodMappings.size());
            
            // Group serve events by signature to detect duplicate endpoints
            java.util.Map<String, java.util.List<Integer>> eventsBySignature = new java.util.HashMap<>();
            for (int i = 0; i < testMethodServeEvents.size(); i++) {
                com.github.tomakehurst.wiremock.stubbing.ServeEvent se = testMethodServeEvents.get(i);
                String seMethod = se.getRequest().getMethod().getName();
                String sePath = normalizePath(se.getRequest().getUrl());
                String seSignature = seMethod.toUpperCase() + ":" + sePath;
                eventsBySignature.computeIfAbsent(seSignature, k -> new java.util.ArrayList<>()).add(i);
            }
            
            // For endpoints with multiple requests, create scenario mappings
            List<StubMapping> scenarioMappings = new java.util.ArrayList<>();
            for (java.util.Map.Entry<String, java.util.List<Integer>> entry : eventsBySignature.entrySet()) {
                String signature = entry.getKey();
                java.util.List<Integer> eventIndices = entry.getValue();
                
                if (eventIndices.size() > 1) {
                    logger.info("=== SCENARIO MODE: Found {} requests for endpoint '{}', creating scenario mappings ===", 
                            eventIndices.size(), signature);
                    
                    // Find the base mapping for this endpoint (use the first one)
                    StubMapping baseMapping = null;
                    for (StubMapping mapping : testMethodMappings) {
                        String mappingMethod = mapping.getRequest().getMethod() != null
                                ? mapping.getRequest().getMethod().getName() : "";
                        String mappingUrl = mapping.getRequest().getUrl() != null
                                ? mapping.getRequest().getUrl()
                                : (mapping.getRequest().getUrlPath() != null ? mapping.getRequest().getUrlPath() : "");
                        String mappingPath = normalizePath(mappingUrl);
                        String mappingSignature = mappingMethod.toUpperCase() + ":" + mappingPath;
                        
                        if (signature.equals(mappingSignature)) {
                            baseMapping = mapping;
                            break;
                        }
                    }
                    
                    if (baseMapping != null) {
                        // Create scenario name from endpoint (e.g., "scenario-1-posts" for GET /posts)
                        // Extract path from signature (format: "METHOD:path")
                        String path = signature.contains(":") ? signature.substring(signature.indexOf(":") + 1) : signature;
                        // Remove leading slash and use path as scenario identifier
                        String pathPart = path.startsWith("/") ? path.substring(1) : path;
                        pathPart = pathPart.replace("/", "-");
                        // Use a simple counter-based approach similar to WireMock's auto-generation
                        String scenarioName = "scenario-" + Math.abs(signature.hashCode() % 1000) + "-" + 
                                (pathPart.isEmpty() ? "root" : pathPart);
                        
                        // Get responses for each request (need to match serve events to their responses)
                        // For now, we'll use the same response for all, but create separate mappings
                        // In a real scenario, responses might differ, but WireMock snapshotRecord
                        // only captures one response per endpoint, so we use that for all
                        
                        // Create mappings for each request with proper state transitions
                        // Serialize base mapping to JSON and rebuild to create copies
                        try {
                            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                            String baseMappingJson = mapper.writeValueAsString(baseMapping);
                            
                            for (int i = 0; i < eventIndices.size(); i++) {
                                StubMapping scenarioMapping = StubMapping.buildFrom(baseMappingJson);
                                scenarioMapping.setId(java.util.UUID.randomUUID());
                                scenarioMapping.setScenarioName(scenarioName);
                                
                                if (i == 0) {
                                    // First mapping: requires "Started", transitions to state-2
                                    scenarioMapping.setRequiredScenarioState("Started");
                                    scenarioMapping.setNewScenarioState(scenarioName + "-2");
                                } else if (i == eventIndices.size() - 1) {
                                    // Last mapping: requires previous state, transitions to final state
                                    scenarioMapping.setRequiredScenarioState(scenarioName + "-" + (i + 1));
                                    scenarioMapping.setNewScenarioState(scenarioName + "-" + (i + 2));
                                } else {
                                    // Middle mappings: require previous state, transition to next
                                    scenarioMapping.setRequiredScenarioState(scenarioName + "-" + (i + 1));
                                    scenarioMapping.setNewScenarioState(scenarioName + "-" + (i + 2));
                                }
                                
                                scenarioMappings.add(scenarioMapping);
                                logger.info("  Created scenario mapping {}: {} -> {} (state: {} -> {})", 
                                        i + 1, signature, scenarioMapping.getId(),
                                        scenarioMapping.getRequiredScenarioState(), scenarioMapping.getNewScenarioState());
                            }
                            
                            // Only remove the original mapping if we successfully created scenario mappings
                            if (!scenarioMappings.isEmpty()) {
                                testMethodMappings.remove(baseMapping);
                            }
                        } catch (Exception e) {
                            logger.error("Failed to create scenario mappings for {}: {}", signature, e.getMessage(), e);
                            // Continue without scenario mode - use original mapping (don't remove baseMapping)
                        }
                    }
                }
            }
            
            // Add scenario mappings to the list
            testMethodMappings.addAll(scenarioMappings);
            logger.info("=== SCENARIO MODE: Created {} scenario mapping(s), total mappings: {} ===", 
                    scenarioMappings.size(), testMethodMappings.size());
        }

        // Load existing mappings from the directory BEFORE creating temp server
        // (WireMock's saveMappings() will overwrite, so we need to preserve them first)
        List<StubMapping> existingMappings = new java.util.ArrayList<>();
        logger.info("=== RECORDING: Checking for existing mappings in: {} ===", testMethodMappingsSubDir.getAbsolutePath());
        
        java.nio.file.Path mappingsPath = testMethodMappingsSubDir.toPath();
        if (java.nio.file.Files.exists(mappingsPath) && java.nio.file.Files.isDirectory(mappingsPath)) {
            File[] jsonFiles = testMethodMappingsSubDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
            if (jsonFiles != null && jsonFiles.length > 0) {
                logger.info("=== RECORDING: Found {} existing mapping file(s) to merge ===", jsonFiles.length);
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                for (File mappingFile : jsonFiles) {
                    try {
                        StubMapping existingMapping = StubMapping.buildFrom(mapper.readTree(mappingFile).toString());
                        existingMappings.add(existingMapping);
                        logger.info("  Loaded existing mapping: {} ({} {})", mappingFile.getName(),
                            existingMapping.getRequest().getMethod().getName(),
                            existingMapping.getRequest().getUrl());
                    } catch (Exception e) {
                        logger.warn("Failed to load existing mapping from {}: {}", mappingFile.getName(), e.getMessage());
                    }
                }
            } else {
                logger.info("No existing mapping files found in {}", mappingsPath);
            }
        } else {
            logger.info("Test method mappings directory does not exist yet: {}", mappingsPath);
        }
        
        File baseFilesDir = new File(baseMappingsDir, "__files");
        if (baseFilesDir.exists() && baseFilesDir.isDirectory()) {
            for (StubMapping mapping : testMethodMappings) {
                String bodyFileName = mapping.getResponse().getBodyFileName();
                if (bodyFileName != null) {
                    File sourceFile = new File(baseFilesDir, bodyFileName);
                    if (sourceFile.exists()) {
                        File destFile = new File(testMethodFilesSubDir, bodyFileName);
                        try {
                            java.nio.file.Files.copy(sourceFile.toPath(), destFile.toPath(), 
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        } catch (Exception e) {
                            logBodyFileCopyFailure(bodyFileName, e);
                        }
                    }
                }
            }
        }
        
        // Merge existing mappings with new ones (new mappings take precedence for duplicates)
        // Use LinkedHashMap for O(1) lookup while preserving insertion order
        java.util.Map<String, StubMapping> mappingsByKey = new java.util.LinkedHashMap<>();
        for (StubMapping mapping : existingMappings) {
            String key = buildMappingKey(mapping);
            mappingsByKey.put(key, mapping);
        }
        for (StubMapping mapping : testMethodMappings) {
            String key = buildMappingKey(mapping);
            mappingsByKey.put(key, mapping); // New mappings overwrite existing (take precedence)
        }
        List<StubMapping> mergedMappings = new java.util.ArrayList<>(mappingsByKey.values());
        
        // Use a temporary directory for the temp server to avoid conflicts
        String uniqueId = java.util.UUID.randomUUID().toString();
        File tempMappingsDir = new java.io.File(java.lang.System.getProperty("java.io.tmpdir"), "stablemock-temp-" + uniqueId);
        File tempMappingsSubDir = new File(tempMappingsDir, "mappings");
        File tempFilesSubDir = new File(tempMappingsDir, "__files");
        
        try {
            if (!tempMappingsDir.exists()) {
                java.nio.file.Files.createDirectories(tempMappingsDir.toPath());
            }
            if (!tempMappingsSubDir.exists()) {
                java.nio.file.Files.createDirectories(tempMappingsSubDir.toPath());
            }
            if (!tempFilesSubDir.exists()) {
                java.nio.file.Files.createDirectories(tempFilesSubDir.toPath());
            }
        } catch (java.nio.file.FileAlreadyExistsException e) {
            // Directory already exists, that's fine
            logger.debug("Temp directory already exists: {}", tempMappingsDir.getAbsolutePath());
        } catch (Exception e) {
            throw new IOException("Failed to create temp directory: " + tempMappingsDir.getAbsolutePath() + " - " + e.getMessage(), e);
        }
        
        WireMockConfiguration tempConfig = WireMockConfiguration.wireMockConfig()
                .dynamicPort()
                .notifier(new com.github.tomakehurst.wiremock.common.ConsoleNotifier(false))
                .usingFilesUnderDirectory(tempMappingsDir.getAbsolutePath());
        
        WireMockServer tempServer = new WireMockServer(tempConfig);
        tempServer.start();
        
        try {
            // Add all merged mappings to temp server
            // Regenerate IDs to avoid duplicate ID conflicts when re-recording
            for (StubMapping mapping : mergedMappings) {
                // Create a new UUID for each mapping to avoid conflicts
                mapping.setId(java.util.UUID.randomUUID());
                tempServer.addStubMapping(mapping);
            }
            tempServer.saveMappings();
            
            // Copy saved mappings from temp directory to actual directory
            File[] tempSavedFiles = tempMappingsSubDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
            if (tempSavedFiles != null) {
                for (File savedFile : tempSavedFiles) {
                    File destFile = new File(testMethodMappingsSubDir, savedFile.getName());
                    java.nio.file.Files.copy(savedFile.toPath(), destFile.toPath(), 
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            
            // Small delay to ensure files are flushed to disk (especially important for WSL)
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            logger.info("=== RECORDING: Saved {} mapping(s) ({} existing + {} new) to {} ===", 
                mergedMappings.size(), existingMappings.size(), testMethodMappings.size(), testMethodMappingsSubDir.getAbsolutePath());
            
            // Verify files were actually written
            File[] savedFiles = testMethodMappingsSubDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
            if (savedFiles != null) {
                logger.info("=== RECORDING: Verified {} file(s) written to disk ===", savedFiles.length);
                for (File f : savedFiles) {
                    logger.info("  File: {} ({} bytes)", f.getName(), f.length());
                }
            } else {
                logger.error("=== RECORDING ERROR: No files found after save! ===");
            }
        } finally {
            tempServer.stop();
        }
    }
    
    public static void mergePerTestMethodMappings(File baseMappingsDir) {
        logger.info("=== mergePerTestMethodMappings called for: {} ===", baseMappingsDir.getAbsolutePath());
        
        if (!baseMappingsDir.exists()) {
            logger.error("Base mappings directory does not exist: {}", baseMappingsDir.getAbsolutePath());
            return;
        }
        
        File classMappingsDir = new File(baseMappingsDir, "mappings");
        File classFilesDir = new File(baseMappingsDir, "__files");
        
        logger.info("Class mappings dir: {}", classMappingsDir.getAbsolutePath());
        logger.info("Class files dir: {}", classFilesDir.getAbsolutePath());
        
        if (classMappingsDir.exists()) {
            File[] existingFiles = classMappingsDir.listFiles();
            int deletedCount = 0;
            if (existingFiles != null) {
                for (File file : existingFiles) {
                    if (file.delete()) {
                        deletedCount++;
                    } else {
                        logger.error("Failed to delete existing file: {}", file.getAbsolutePath());
                    }
                }
            }
            logger.info("Cleaned {} existing mapping file(s) from class-level directory", deletedCount);
        } else if (!classMappingsDir.mkdirs()) {
            logger.error("Failed to create class-level mappings directory: {}", classMappingsDir.getAbsolutePath());
            return;
        } else {
            logger.info("Created class-level mappings directory");
        }
        
        if (!classFilesDir.exists() && !classFilesDir.mkdirs()) {
            logger.error("Failed to create class-level __files directory: {}", classFilesDir.getAbsolutePath());
            return;
        }
        
        // List all directories in baseMappingsDir for debugging
        File[] allDirs = baseMappingsDir.listFiles(File::isDirectory);
        if (allDirs != null && allDirs.length > 0) {
            logger.info("All directories in baseMappingsDir: {}", java.util.Arrays.toString(
                java.util.Arrays.stream(allDirs).map(File::getName).toArray(String[]::new)));
        }
        
        File[] testMethodDirs = baseMappingsDir.listFiles(file ->
            file.isDirectory() && !file.getName().equals("mappings") && !file.getName().equals("__files") && !file.getName().startsWith("url_"));
        if (testMethodDirs == null || testMethodDirs.length == 0) {
            logger.error("No test method directories found in {} - merge cannot proceed!", baseMappingsDir.getAbsolutePath());
            return;
        }
        
        // Sort test method directories for consistent ordering across platforms
        // This ensures the same merge order on Windows and Linux
        java.util.Arrays.sort(testMethodDirs, java.util.Comparator.comparing(File::getName));
        
        logger.info("=== Starting merge: {} test method(s) in {} ===", testMethodDirs.length, baseMappingsDir.getAbsolutePath());
        for (File testMethodDir : testMethodDirs) {
            logger.info("  Test method directory: {}", testMethodDir.getName());
        }
        
        // Check if we have multiple URLs by looking for annotation_X directories in test methods
        boolean hasMultipleUrls = false;
        for (File testMethodDir : testMethodDirs) {
            File[] annotationDirs = testMethodDir.listFiles(file -> 
                file.isDirectory() && file.getName().startsWith("annotation_"));
            if (annotationDirs != null && annotationDirs.length > 0) {
                hasMultipleUrls = true;
                break;
            }
        }
        
        // For multiple URLs, we don't merge to class-level directory - that's handled separately
        if (hasMultipleUrls) {
            logger.info("Multiple URLs detected, skipping single URL merge for {}", baseMappingsDir.getAbsolutePath());
            return;
        }
        
        // Single URL case - merge test method mappings to class-level directory
        int totalMappingsCopied = 0;
        int postMappingsCopied = 0;
        int getMappingsCopied = 0;
        int skippedMethods = 0;
        for (File testMethodDir : testMethodDirs) {
            File methodMappingsDir = new File(testMethodDir, "mappings");
            File methodFilesDir = new File(testMethodDir, "__files");
            
            logger.info("Processing test method: {}", testMethodDir.getName());
            
            if (!methodMappingsDir.exists() || !methodMappingsDir.isDirectory()) {
                logger.warn("  No mappings directory found for test method {}", testMethodDir.getName());
                skippedMethods++;
                continue;
            }
            
            if (methodMappingsDir.exists() && methodMappingsDir.isDirectory()) {
                File[] mappingFiles = methodMappingsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
                if (mappingFiles != null && mappingFiles.length > 0) {
                    logger.info("  Found {} mapping file(s) in {}", mappingFiles.length, testMethodDir.getName());
                    // Log all files found for debugging
                    for (File f : mappingFiles) {
                        logger.debug("    File: {} (exists: {}, size: {} bytes)", f.getName(), f.exists(), f.length());
                    }
                    for (File mappingFile : mappingFiles) {
                        // Verify source file exists and is readable
                        if (!mappingFile.exists() || mappingFile.length() == 0) {
                            logger.error("  Source mapping file is missing or empty: {}", mappingFile.getName());
                            continue;
                        }
                        try {
                            // Read mapping to log method and URL
                            String method = "UNKNOWN";
                            String url = "UNKNOWN";
                            try {
                                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                                com.fasterxml.jackson.databind.JsonNode mappingJson = mapper.readTree(mappingFile);
                                com.fasterxml.jackson.databind.JsonNode requestNode = mappingJson.get("request");
                                if (requestNode != null) {
                                    method = requestNode.has("method") ? requestNode.get("method").asText() : "UNKNOWN";
                                    if (requestNode.has("url")) {
                                        url = requestNode.get("url").asText();
                                    } else if (requestNode.has("urlPath")) {
                                        url = requestNode.get("urlPath").asText();
                                    }
                                }
                            } catch (Exception e) {
                                logger.warn("  Could not parse mapping file {}: {}", mappingFile.getName(), e.getMessage());
                            }
                            
                            String prefix = testMethodDir.getName() + "_";
                            String newName = prefix + mappingFile.getName();
                            File destFile = new File(classMappingsDir, newName);
                            
                            // Ensure destination directory exists
                            if (!classMappingsDir.exists() && !classMappingsDir.mkdirs()) {
                                logger.error("  Failed to create class-level mappings directory");
                                continue;
                            }
                            
                            // Check if destination file already exists (would be overwritten)
                            if (destFile.exists()) {
                                logger.warn("  Destination file already exists and will be overwritten: {}", destFile.getName());
                            }
                            
                            java.nio.file.Files.copy(mappingFile.toPath(), destFile.toPath(), 
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            
                            // Verify file was actually written
                            if (!destFile.exists() || destFile.length() == 0) {
                                logger.error("  File copy failed - dest does not exist or is empty: {}", destFile.getName());
                                continue;
                            }
                            
                            // Additional verification: try to read the file to ensure it's accessible
                            // This helps catch file system caching issues on Linux with JDK 17
                            try {
                                java.nio.file.Files.readAllBytes(destFile.toPath());
                            } catch (Exception e) {
                                logger.error("  File copied but not readable: {} - {}", destFile.getName(), e.getMessage());
                                // Continue anyway - might be a transient issue
                            }
                            
                            totalMappingsCopied++;
                            if ("POST".equalsIgnoreCase(method)) {
                                postMappingsCopied++;
                            } else if ("GET".equalsIgnoreCase(method)) {
                                getMappingsCopied++;
                            }
                            logger.info("  ✓ Copied: {} {} -> {} (exists: {}, size: {} bytes)", 
                                method, url, destFile.getName(), destFile.exists(), destFile.length());
                        } catch (Exception e) {
                            logger.error("  ✗ Failed to copy mapping {}: {}", mappingFile.getName(), e.getMessage(), e);
                        }
                    }
                } else {
                    logger.warn("  No mapping files found in {} - test method may not have made any HTTP requests", methodMappingsDir.getAbsolutePath());
                }
            }
            
            // Copy body files from test method __files to class-level __files
            if (methodFilesDir.exists() && methodFilesDir.isDirectory()) {
                File[] bodyFiles = methodFilesDir.listFiles();
                if (bodyFiles != null) {
                    for (File bodyFile : bodyFiles) {
                        try {
                            File destFile = new File(classFilesDir, bodyFile.getName());
                            java.nio.file.Files.copy(bodyFile.toPath(), destFile.toPath(), 
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        } catch (Exception e) {
                            logBodyFileCopyFailure(bodyFile.getName(), e);
                        }
                    }
                }
            }
        }
        
        // Log summary of merged mappings
        logger.info("=== Merge complete: {} mapping(s) copied (during copy: {} GET, {} POST), {} test method(s) skipped ===", 
            totalMappingsCopied, getMappingsCopied, postMappingsCopied, skippedMethods);
        
        if (skippedMethods > 0) {
            logger.warn("{} test method(s) had no mappings directory - they may not have made HTTP requests during recording", skippedMethods);
        }
        
        File[] finalMappings = classMappingsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
        if (finalMappings != null && finalMappings.length > 0) {
            logger.info("Final merged mappings in class-level directory ({} file(s)):", finalMappings.length);
            int postCount = 0;
            int getCount = 0;
            for (File mappingFile : finalMappings) {
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
                        if ("POST".equalsIgnoreCase(method)) {
                            postCount++;
                        } else if ("GET".equalsIgnoreCase(method)) {
                            getCount++;
                        }
                        if (mappingFile.length() > 0) {
                            logger.info("  [{}] {} {} ({} bytes)", method, url, mappingFile.getName(), mappingFile.length());
                        } else {
                            logger.warn("  [{}] {} {} (EMPTY FILE!)", method, url, mappingFile.getName());
                        }
                    }
                } catch (Exception e) {
                    logger.error("  Could not parse mapping file {} for summary: {}", mappingFile.getName(), e.getMessage());
                }
            }
            logger.info("Summary: {} GET, {} POST, {} other", getCount, postCount, finalMappings.length - getCount - postCount);
            if (postMappingsCopied > 0 && postCount == 0) {
                logger.error("{} POST mapping(s) were copied but 0 found in final directory! Files may not have been written correctly.", postMappingsCopied);
                // List all files in the directory to debug
                File[] allFiles = classMappingsDir.listFiles();
                if (allFiles != null) {
                    logger.error("All files in class-level mappings directory:");
                    for (File f : allFiles) {
                        // Try to parse each file to see what method it is
                        String fileMethod = "UNKNOWN";
                        try {
                            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                            com.fasterxml.jackson.databind.JsonNode mappingJson = mapper.readTree(f);
                            com.fasterxml.jackson.databind.JsonNode requestNode = mappingJson.get("request");
                            if (requestNode != null && requestNode.has("method")) {
                                fileMethod = requestNode.get("method").asText();
                            }
                        } catch (Exception e) {
                            fileMethod = "PARSE_ERROR";
                        }
                        logger.error("  - {} [{}] ({} bytes, exists: {})", f.getName(), fileMethod, f.length(), f.exists());
                    }
                }
                // This is a critical error - POST mappings are required
                throw new IllegalStateException("POST mappings were copied but not found in final directory. This will cause POST requests to fail.");
            }
            if (getMappingsCopied > 0 && getCount == 0) {
                logger.error("{} GET mapping(s) were copied but 0 found in final directory! Files may not have been written correctly.", getMappingsCopied);
                // This is a critical error - GET mappings are required
                throw new IllegalStateException("GET mappings were copied but not found in final directory. This will cause GET requests to fail.");
            }
            if (postCount == 0 && postMappingsCopied == 0) {
                logger.error("No POST mappings found in merged mappings! This will cause POST requests to fail.");
                // List all test method directories to help debug
                if (testMethodDirs != null && testMethodDirs.length > 0) {
                    logger.error("Test method directories checked:");
                    for (File testMethodDir : testMethodDirs) {
                        File methodMappingsDir = new File(testMethodDir, "mappings");
                        int fileCount = 0;
                        if (methodMappingsDir.exists()) {
                            File[] files = methodMappingsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
                            fileCount = files != null ? files.length : 0;
                        }
                        logger.error("  - {}: {} mapping file(s)", testMethodDir.getName(), fileCount);
                    }
                }
            }
            if (getCount == 0 && getMappingsCopied == 0) {
                logger.error("No GET mappings found in merged mappings! This will cause GET requests to fail.");
            }
            if (getCount < 3) {
                logger.warn("Expected at least 3 GET mappings (/users/1, /users/2, /users/3) but found only {}", getCount);
            }
        } else {
            logger.error("No mappings found in class-level directory after merge! Directory: {}", classMappingsDir.getAbsolutePath());
            // List what test method directories exist for debugging
            if (testMethodDirs != null && testMethodDirs.length > 0) {
                logger.error("Test method directories that were checked: {}", 
                    java.util.Arrays.toString(java.util.Arrays.stream(testMethodDirs).map(File::getName).toArray(String[]::new)));
            }
        }
        
        logger.info("Completed merging mappings to class-level directory");
    }


    private static String buildMappingKey(StubMapping mapping) {
        String methodName = mapping.getRequest().getMethod() != null
                ? mapping.getRequest().getMethod().getName()
                : "";
        String url = mapping.getRequest().getUrl();
        if (url == null || url.isEmpty()) {
            url = mapping.getRequest().getUrlPath();
        }
        if (url == null) {
            url = "";
        }
        String key = methodName + ":" + url;
        String body = extractMappingBody(mapping);
        if (body != null && !body.isEmpty()) {
            key += ":" + body.hashCode();
        }
        // Include scenario state in key to distinguish scenario mappings
        if (mapping.getScenarioName() != null) {
            key += ":scenario:" + mapping.getScenarioName();
            if (mapping.getRequiredScenarioState() != null) {
                key += ":" + mapping.getRequiredScenarioState();
            }
            if (mapping.getNewScenarioState() != null) {
                key += "->" + mapping.getNewScenarioState();
            }
        }
        return key;
    }

    private static String normalizePath(String url) {
        if (url == null) {
            return "/";
        }
        String path = url.contains("?") ? url.substring(0, url.indexOf("?")) : url;
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return path.replaceAll("/+$", "");
    }

    private static ContentPattern<?> buildBodyPattern(String body) {
        if (body == null) {
            return null;
        }
        String trimmed = body.trim();
        if (!trimmed.isEmpty()) {
            try {
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(trimmed);
                return new EqualToJsonPattern(trimmed, true, true);
            } catch (Exception ignored) {
                // Not JSON, fall through
            }
            if (trimmed.startsWith("<") && trimmed.endsWith(">")) {
                return new EqualToXmlPattern(trimmed);
            }
        }
        return new EqualToPattern(body);
    }

    private static String extractMappingBody(StubMapping mapping) {
        String mappingMethod = mapping.getRequest().getMethod() != null
                ? mapping.getRequest().getMethod().getName()
                : "";
        if (!"POST".equalsIgnoreCase(mappingMethod) && !"PUT".equalsIgnoreCase(mappingMethod)
                && !"PATCH".equalsIgnoreCase(mappingMethod)) {
            return null;
        }
        if (mapping.getRequest().getBodyPatterns() == null || mapping.getRequest().getBodyPatterns().isEmpty()) {
            return null;
        }
        ContentPattern<?> bodyPattern = mapping.getRequest().getBodyPatterns().get(0);
        if (bodyPattern instanceof EqualToJsonPattern) {
            return ((EqualToJsonPattern) bodyPattern).getExpected();
        }
        if (bodyPattern instanceof EqualToPattern) {
            return ((EqualToPattern) bodyPattern).getExpected();
        }
        if (bodyPattern instanceof EqualToXmlPattern) {
            return ((EqualToXmlPattern) bodyPattern).getExpected();
        }
        return null;
    }
}
