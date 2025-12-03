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
            for (StubMapping mapping : mappings) {
                tempServer.addStubMapping(mapping);
            }
            tempServer.saveMappings();
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
        
        List<com.github.tomakehurst.wiremock.stubbing.ServeEvent> testMethodServeEvents = 
            allServeEvents.subList(existingRequestCount, allServeEvents.size());
        
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
        for (int i = 0; i < testMethodServeEvents.size(); i++) {
            if (!matchedServeEventIndices.contains(i)) {
                com.github.tomakehurst.wiremock.stubbing.ServeEvent se = testMethodServeEvents.get(i);
                logger.error("  ✗ NO MATCH for ServeEvent[{}]: {} {} - Mapping will be MISSING!", 
                    i, se.getRequest().getMethod().getName(), se.getRequest().getUrl());
            }
        }
        
        logger.info("=== RECORDING: Matched {}/{} mapping(s) ===", 
            testMethodMappings.size(), testMethodServeEvents.size());
        
        if (testMethodMappings.isEmpty()) {
            logger.error("=== RECORDING FAILED: No mappings matched! ===");
            return;
        }
        
        if (testMethodMappings.size() < testMethodServeEvents.size()) {
            logger.error("=== RECORDING WARNING: Only {}/{} mappings matched! {} request(s) will fail in playback ===", 
                testMethodMappings.size(), testMethodServeEvents.size(), 
                testMethodServeEvents.size() - testMethodMappings.size());
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
                            logger.error("Failed to copy body file {}: {}", bodyFileName, e.getMessage());
                        }
                    }
                }
            }
        }
        
        WireMockConfiguration tempConfig = WireMockConfiguration.wireMockConfig()
                .dynamicPort()
                .notifier(new com.github.tomakehurst.wiremock.common.ConsoleNotifier(false))
                .usingFilesUnderDirectory(testMethodMappingsDir.getAbsolutePath());
        
        WireMockServer tempServer = new WireMockServer(tempConfig);
        tempServer.start();
        
        try {
            for (StubMapping mapping : testMethodMappings) {
                tempServer.addStubMapping(mapping);
            }
            tempServer.saveMappings();
            logger.info("=== RECORDING: Saved {} mapping(s) to {} ===", 
                testMethodMappings.size(), testMethodMappingsSubDir.getAbsolutePath());
            
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
        String msg = "=== mergePerTestMethodMappings called for: " + baseMappingsDir.getAbsolutePath() + " ===";
        System.out.println(msg);
        logger.info(msg);
        
        if (!baseMappingsDir.exists()) {
            String errorMsg = "Base mappings directory does not exist: " + baseMappingsDir.getAbsolutePath();
            System.err.println("ERROR: " + errorMsg);
            logger.error(errorMsg);
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
        
        String startMsg = "=== Starting merge: " + testMethodDirs.length + " test method(s) in " + baseMappingsDir.getAbsolutePath() + " ===";
        System.out.println(startMsg);
        logger.info(startMsg);
        for (File testMethodDir : testMethodDirs) {
            String dirMsg = "  Test method directory: " + testMethodDir.getName();
            System.out.println(dirMsg);
            logger.info(dirMsg);
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
            String skipMsg = "Multiple URLs detected, skipping single URL merge for " + baseMappingsDir.getAbsolutePath();
            System.out.println(skipMsg);
            logger.info(skipMsg);
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
            System.out.println("Processing test method: " + testMethodDir.getName());
            
            if (!methodMappingsDir.exists() || !methodMappingsDir.isDirectory()) {
                String warnMsg = "  No mappings directory found for test method " + testMethodDir.getName();
                logger.warn(warnMsg);
                System.err.println("WARNING: " + warnMsg);
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
                            String errorMsg = "  ERROR: Source mapping file is missing or empty: " + mappingFile.getName();
                            System.err.println(errorMsg);
                            logger.error(errorMsg);
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
                                String warnMsg = "  WARNING: Destination file already exists and will be overwritten: " + destFile.getName();
                                System.err.println(warnMsg);
                                logger.warn(warnMsg);
                            }
                            
                            java.nio.file.Files.copy(mappingFile.toPath(), destFile.toPath(), 
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            
                            // Force file system sync
                            try {
                                java.nio.file.Files.getFileStore(destFile.toPath()).getUsableSpace();
                                destFile.getParentFile().getAbsolutePath();
                            } catch (Exception e) {
                                // Ignore
                            }
                            
                            // Verify file was actually written
                            if (!destFile.exists() || destFile.length() == 0) {
                                String errorMsg = "  ERROR: File copy failed - dest does not exist or is empty: " + destFile.getName();
                                System.err.println(errorMsg);
                                logger.error(errorMsg);
                                continue;
                            }
                            
                            totalMappingsCopied++;
                            if ("POST".equalsIgnoreCase(method)) {
                                postMappingsCopied++;
                            } else if ("GET".equalsIgnoreCase(method)) {
                                getMappingsCopied++;
                            }
                            String copyMsg = "  ✓ Copied: " + method + " " + url + " -> " + destFile.getName() + 
                                " (exists: " + destFile.exists() + ", size: " + destFile.length() + " bytes)";
                            System.out.println(copyMsg);
                            logger.info(copyMsg);
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
                            // Ignore individual file copy failures
                        }
                    }
                }
            }
        }
        
        // Log summary of merged mappings
        String completeMsg = "=== Merge complete: " + totalMappingsCopied + " mapping(s) copied (during copy: " + 
            getMappingsCopied + " GET, " + postMappingsCopied + " POST), " + skippedMethods + " test method(s) skipped ===";
        System.out.println(completeMsg);
        logger.info(completeMsg);
        
        if (skippedMethods > 0) {
            String skipMsg = "WARNING: " + skippedMethods + " test method(s) had no mappings directory - they may not have made HTTP requests during recording";
            System.err.println(skipMsg);
            logger.warn(skipMsg);
        }
        
        File[] finalMappings = classMappingsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
        if (finalMappings != null && finalMappings.length > 0) {
            String finalMsg = "Final merged mappings in class-level directory (" + finalMappings.length + " file(s)):";
            System.out.println(finalMsg);
            logger.info(finalMsg);
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
                        String mappingMsg = "  [" + method + "] " + url + " " + mappingFile.getName() + 
                            (mappingFile.length() > 0 ? " (" + mappingFile.length() + " bytes)" : " (EMPTY FILE!)");
                        System.out.println(mappingMsg);
                        logger.info(mappingMsg);
                    }
                } catch (Exception e) {
                    String errorMsg = "  Could not parse mapping file " + mappingFile.getName() + " for summary: " + e.getMessage();
                    System.err.println("ERROR: " + errorMsg);
                    logger.error(errorMsg);
                }
            }
            String summaryMsg = "Summary: " + getCount + " GET, " + postCount + " POST, " + (finalMappings.length - getCount - postCount) + " other";
            System.out.println(summaryMsg);
            logger.info(summaryMsg);
            if (postMappingsCopied > 0 && postCount == 0) {
                String errorMsg = "ERROR: " + postMappingsCopied + " POST mapping(s) were copied but 0 found in final directory! Files may not have been written correctly.";
                System.err.println(errorMsg);
                logger.error(errorMsg);
                // List all files in the directory to debug
                File[] allFiles = classMappingsDir.listFiles();
                if (allFiles != null) {
                    System.err.println("All files in class-level mappings directory:");
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
                        System.err.println("  - " + f.getName() + " [" + fileMethod + "] (" + f.length() + " bytes, exists: " + f.exists() + ")");
                    }
                }
                // This is a critical error - POST mappings are required
                throw new IllegalStateException("POST mappings were copied but not found in final directory. This will cause POST requests to fail.");
            }
            if (postCount == 0 && postMappingsCopied == 0) {
                String warnMsg = "WARNING: No POST mappings found in merged mappings! This will cause POST requests to fail.";
                System.err.println(warnMsg);
                logger.error(warnMsg);
            }
            if (getCount < 3) {
                String warnMsg = "WARNING: Expected at least 3 GET mappings (/users/1, /users/2, /users/3) but found only " + getCount;
                System.err.println(warnMsg);
                logger.warn(warnMsg);
            }
        } else {
            String errorMsg = "ERROR: No mappings found in class-level directory after merge! Directory: " + classMappingsDir.getAbsolutePath();
            System.err.println(errorMsg);
            logger.error(errorMsg);
            // List what test method directories exist for debugging
            if (testMethodDirs != null && testMethodDirs.length > 0) {
                String dirsMsg = "Test method directories that were checked: " + java.util.Arrays.toString(
                    java.util.Arrays.stream(testMethodDirs).map(File::getName).toArray(String[]::new));
                System.err.println(dirsMsg);
                logger.error(dirsMsg);
            }
        }
        
        // Force file system sync to ensure all files are written before WireMock loads them
        // This is critical on Linux where file system caching might delay visibility
        try {
            // Sync each copied file to ensure it's written to disk
            if (finalMappings != null && finalMappings.length > 0) {
                logger.info("Syncing {} mapping file(s) to disk", finalMappings.length);
                for (File mappingFile : finalMappings) {
                    try {
                        // Open file in append mode and sync to force write to disk
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(mappingFile, true);
                        fos.getFD().sync();
                        fos.close();
                        logger.debug("Synced file to disk: {}", mappingFile.getName());
                    } catch (Exception e) {
                        logger.warn("File sync failed for {}: {}", mappingFile.getName(), e.getMessage());
                    }
                }
                // Force directory metadata sync by touching a marker file
                try {
                    File markerFile = new File(classMappingsDir, ".sync-marker");
                    markerFile.createNewFile();
                    java.io.FileOutputStream markerFos = new java.io.FileOutputStream(markerFile, true);
                    markerFos.getFD().sync();
                    markerFos.close();
                    markerFile.delete(); // Clean up marker file
                } catch (Exception e) {
                    logger.debug("Directory sync marker failed (non-critical): {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.warn("File sync check failed: {}", e.getMessage());
        }
        
        // Longer delay to ensure file system has processed all writes (especially on Linux/CI)
        // This is critical for CI environments where file system operations may be slower
        try {
            Thread.sleep(500); // Increased from 100ms to 500ms for CI environments
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        logger.info("Completed merging mappings to class-level directory");
    }
}

