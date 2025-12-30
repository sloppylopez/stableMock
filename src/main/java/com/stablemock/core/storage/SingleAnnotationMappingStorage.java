package com.stablemock.core.storage;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.recording.RecordSpecBuilder;
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
    
    public static void saveMappingsForTestMethod(WireMockServer wireMockServer, File testMethodMappingsDir, 
            File baseMappingsDir, String targetUrl, int existingRequestCount) throws IOException {
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
        List<com.github.tomakehurst.wiremock.stubbing.ServeEvent> testMethodServeEvents = 
            newEventsCount > 0 ? allServeEvents.subList(0, newEventsCount) : new java.util.ArrayList<>();
        
        if (testMethodServeEvents.isEmpty()) {
            logger.warn("No serve events for this test method (existing: {}, total: {})", 
                existingRequestCount, allServeEvents.size());
            return;
        }

        logger.info("=== RECORDING: {} serve event(s) for this test method ===", testMethodServeEvents.size());
        for (int i = 0; i < testMethodServeEvents.size(); i++) {
            com.github.tomakehurst.wiremock.stubbing.ServeEvent se = testMethodServeEvents.get(i);
            logger.info("  ServeEvent[{}]: {} {}", i, se.getRequest().getMethod().getName(), se.getRequest().getUrl());
        }

        // Use snapshotRecord and match leniently by method + normalized URL path
        RecordSpecBuilder builder = new RecordSpecBuilder()
                .forTarget(targetUrl)
                .makeStubsPersistent(true)
                .extractTextBodiesOver(255);

        com.github.tomakehurst.wiremock.recording.SnapshotRecordResult result = wireMockServer.snapshotRecord(builder.build());
        List<StubMapping> allMappings = result.getStubMappings();
        
        logger.info("=== RECORDING: snapshotRecord created {} mapping(s), matching to {} serve event(s) ===", 
            allMappings.size(), testMethodServeEvents.size());
        
        // Build normalized signatures from serve events
        // Normalize paths but preserve leading slash (WireMock expects it)
        // Use List<Integer> to handle duplicate signatures (same request made multiple times)
        java.util.Map<String, java.util.List<Integer>> serveEventSignatures = new java.util.HashMap<>();
        for (int i = 0; i < testMethodServeEvents.size(); i++) {
            com.github.tomakehurst.wiremock.stubbing.ServeEvent se = testMethodServeEvents.get(i);
            String method = se.getRequest().getMethod().getName();
            String url = se.getRequest().getUrl();
            String path = url.contains("?") ? url.substring(0, url.indexOf("?")) : url;
            // Normalize: ensure leading slash, remove trailing slash
            if (!path.startsWith("/")) {
                path = "/" + path;
            }
            path = path.replaceAll("/+$", "");
            String signature = method.toUpperCase() + ":" + path;
            serveEventSignatures.computeIfAbsent(signature, k -> new java.util.ArrayList<>()).add(i);
        }
        
        // Match mappings to serve events by signature
        List<StubMapping> testMethodMappings = new java.util.ArrayList<>();
        java.util.Set<Integer> matchedServeEventIndices = new java.util.HashSet<>();
        // Track which serve event index we're matching for each signature
        java.util.Map<String, Integer> signatureMatchIndex = new java.util.HashMap<>();
        
        logger.info("=== RECORDING: Looking for signatures: {} ===", serveEventSignatures.keySet());
        
        for (int mIdx = 0; mIdx < allMappings.size(); mIdx++) {
            StubMapping mapping = allMappings.get(mIdx);
            String mappingMethod = mapping.getRequest().getMethod() != null 
                ? mapping.getRequest().getMethod().getName() : "";
            String mappingUrl = mapping.getRequest().getUrl() != null 
                ? mapping.getRequest().getUrl() 
                : (mapping.getRequest().getUrlPath() != null ? mapping.getRequest().getUrlPath() : "");
            String mappingPath = mappingUrl.contains("?") ? mappingUrl.substring(0, mappingUrl.indexOf("?")) : mappingUrl;
            // Normalize: ensure leading slash, remove trailing slash (same as serve events)
            if (!mappingPath.startsWith("/")) {
                mappingPath = "/" + mappingPath;
            }
            mappingPath = mappingPath.replaceAll("/+$", "");
            String mappingSignature = mappingMethod.toUpperCase() + ":" + mappingPath;
            
            logger.info("  Mapping[{}]: signature='{}' ({} {})", mIdx, mappingSignature, mappingMethod, mappingUrl);
            
            if (serveEventSignatures.containsKey(mappingSignature)) {
                java.util.List<Integer> seIndices = serveEventSignatures.get(mappingSignature);
                // Get the next unmatched serve event index for this signature
                int matchIndex = signatureMatchIndex.getOrDefault(mappingSignature, 0);
                if (matchIndex < seIndices.size()) {
                    Integer seIdx = seIndices.get(matchIndex);
                    if (!matchedServeEventIndices.contains(seIdx)) {
                        testMethodMappings.add(mapping);
                        matchedServeEventIndices.add(seIdx);
                        signatureMatchIndex.put(mappingSignature, matchIndex + 1);
                        logger.info("  ✓ Match[M{}->SE{}]: {} {} -> {} {}", 
                            mIdx, seIdx, mappingMethod, mappingUrl,
                            testMethodServeEvents.get(seIdx).getRequest().getMethod().getName(),
                            testMethodServeEvents.get(seIdx).getRequest().getUrl());
                    } else {
                        // This serve event was already matched, try next one
                        boolean foundUnmatched = false;
                        for (int nextIdx = matchIndex + 1; nextIdx < seIndices.size(); nextIdx++) {
                            Integer nextSeIdx = seIndices.get(nextIdx);
                            if (!matchedServeEventIndices.contains(nextSeIdx)) {
                                testMethodMappings.add(mapping);
                                matchedServeEventIndices.add(nextSeIdx);
                                signatureMatchIndex.put(mappingSignature, nextIdx + 1);
                                logger.info("  ✓ Match[M{}->SE{}]: {} {} -> {} {} (skipped already-matched SE{})", 
                                    mIdx, nextSeIdx, mappingMethod, mappingUrl,
                                    testMethodServeEvents.get(nextSeIdx).getRequest().getMethod().getName(),
                                    testMethodServeEvents.get(nextSeIdx).getRequest().getUrl(), seIdx);
                                foundUnmatched = true;
                                break;
                            }
                        }
                        if (!foundUnmatched) {
                            logger.warn("  ⊗ Skip[M{}->SE{}]: All serve events with this signature already matched", mIdx, seIdx);
                        }
                    }
                } else {
                    logger.warn("  ⊗ Skip[M{}]: No more unmatched serve events for signature '{}'", mIdx, mappingSignature);
                }
            } else {
                logger.info("  ✗ Filter[M{}]: signature '{}' not in serve events (from other test)", mIdx, mappingSignature);
            }
        }
        
        // Check for unmatched serve events
        int unmatchedCount = 0;
        for (int i = 0; i < testMethodServeEvents.size(); i++) {
            if (!matchedServeEventIndices.contains(i)) {
                unmatchedCount++;
                com.github.tomakehurst.wiremock.stubbing.ServeEvent se = testMethodServeEvents.get(i);
                logger.error("  ✗ NO MATCH for ServeEvent[{}]: {} {} - Mapping will be MISSING!", 
                    i, se.getRequest().getMethod().getName(), se.getRequest().getUrl());
            }
        }
        
        logger.info("=== RECORDING: Matched {}/{} mapping(s) ===", 
            testMethodMappings.size(), testMethodServeEvents.size());
        
        if (unmatchedCount > 0) {
            logger.error("=== RECORDING WARNING: {} serve event(s) had no matching mapping! ===", unmatchedCount);
        }
        
        if (testMethodMappings.isEmpty()) {
            logger.error("=== RECORDING FAILED: No mappings matched! ===");
            return;
        }
        
        if (testMethodMappings.size() < testMethodServeEvents.size()) {
            logger.error("=== RECORDING WARNING: Only {}/{} mappings matched! {} request(s) will fail in playback ===", 
                testMethodMappings.size(), testMethodServeEvents.size(), 
                testMethodServeEvents.size() - testMethodMappings.size());
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
            String key = mapping.getRequest().getMethod().getName() + ":" + mapping.getRequest().getUrl();
            mappingsByKey.put(key, mapping);
        }
        for (StubMapping mapping : testMethodMappings) {
            String key = mapping.getRequest().getMethod().getName() + ":" + mapping.getRequest().getUrl();
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
}

