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
        int unmatchedCount = 0;
        for (int i = 0; i < testMethodServeEvents.size(); i++) {
            if (!matchedServeEventIndices.contains(i)) {
                unmatchedCount++;
                com.github.tomakehurst.wiremock.stubbing.ServeEvent se = testMethodServeEvents.get(i);
                String errorMsg = "  ✗ NO MATCH for ServeEvent[" + i + "]: " + 
                    se.getRequest().getMethod().getName() + " " + se.getRequest().getUrl() + 
                    " - Mapping will be MISSING!";
                System.err.println("ERROR: " + errorMsg);
                logger.error(errorMsg);
            }
        }
        
        logger.info("=== RECORDING: Matched {}/{} mapping(s) ===", 
            testMethodMappings.size(), testMethodServeEvents.size());
        
        if (unmatchedCount > 0) {
            String errorMsg = "=== RECORDING WARNING: " + unmatchedCount + " serve event(s) had no matching mapping! ===";
            System.err.println(errorMsg);
            logger.error(errorMsg);
        }
        
        if (testMethodMappings.isEmpty()) {
            String errorMsg = "=== RECORDING FAILED: No mappings matched! ===";
            System.err.println(errorMsg);
            logger.error(errorMsg);
            return;
        }
        
        if (testMethodMappings.size() < testMethodServeEvents.size()) {
            logger.error("=== RECORDING WARNING: Only {}/{} mappings matched! {} request(s) will fail in playback ===", 
                testMethodMappings.size(), testMethodServeEvents.size(), 
                testMethodServeEvents.size() - testMethodMappings.size());
        }

        // Load existing mappings from the directory BEFORE creating temp server
        // (WireMock's saveMappings() will overwrite, so we need to preserve them first)
        // WSL file system issue: Files may exist but not be visible in directory listings
        // Solution: Use both directory listing AND direct file access attempts
        List<StubMapping> existingMappings = new java.util.ArrayList<>();
        logger.info("=== RECORDING: Checking for existing mappings in: {} ===", testMethodMappingsSubDir.getAbsolutePath());
        
        java.nio.file.Path mappingsPath = testMethodMappingsSubDir.toPath();
        if (java.nio.file.Files.exists(mappingsPath) && java.nio.file.Files.isDirectory(mappingsPath)) {
            // Strategy: Try multiple approaches to find files in WSL
            List<java.nio.file.Path> existingFilesList = new java.util.ArrayList<>();
            java.util.Set<String> foundFileNames = new java.util.HashSet<>();
            
            for (int retry = 0; retry < 8; retry++) {
                // Approach 1: Use Files.list() - most reliable for WSL
                try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.list(mappingsPath)) {
                    stream.filter(path -> {
                        String fileName = path.getFileName().toString().toLowerCase();
                        return java.nio.file.Files.isRegularFile(path) && fileName.endsWith(".json");
                    }).forEach(path -> {
                        String fileName = path.getFileName().toString();
                        if (!foundFileNames.contains(fileName)) {
                            existingFilesList.add(path);
                            foundFileNames.add(fileName);
                        }
                    });
                } catch (Exception e) {
                    logger.debug("Files.list() failed on retry {}: {}", retry, e.getMessage());
                }
                
                // Approach 2: Try File.listFiles() as fallback (sometimes works when Files.list() doesn't)
                if (existingFilesList.isEmpty() && retry >= 2) {
                    try {
                        File[] allFiles = testMethodMappingsSubDir.listFiles();
                        if (allFiles != null) {
                            for (File file : allFiles) {
                                if (file.isFile() && file.getName().toLowerCase().endsWith(".json")) {
                                    String fileName = file.getName();
                                    if (!foundFileNames.contains(fileName)) {
                                        existingFilesList.add(file.toPath());
                                        foundFileNames.add(fileName);
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("File.listFiles() failed on retry {}: {}", retry, e.getMessage());
                    }
                }
                
                logger.info("  JSON files found (retry {}): {} (combined methods)", retry, existingFilesList.size());
                if (existingFilesList.size() > 0) {
                    break; // Found files, exit retry loop
                }
                
                // Wait before retrying with increasing delays
                if (retry < 7) {
                    try {
                        // Force sync attempt for WSL
                        if (retry >= 3) {
                            try {
                                java.nio.file.Files.getFileStore(mappingsPath).getAttribute("basic:isReadOnly");
                            } catch (Exception e) {
                                // Ignore
                            }
                        }
                        Thread.sleep(250 * (retry + 1)); // 250ms, 500ms, 750ms, 1000ms, 1250ms, 1500ms, 1750ms
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            
            if (existingFilesList.size() > 0) {
                logger.info("=== RECORDING: Found {} existing mapping file(s) to merge ===", existingFilesList.size());
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                for (java.nio.file.Path mappingFilePath : existingFilesList) {
                    try {
                        File mappingFile = mappingFilePath.toFile();
                        StubMapping existingMapping = StubMapping.buildFrom(mapper.readTree(mappingFile).toString());
                        existingMappings.add(existingMapping);
                        logger.info("  Loaded existing mapping: {} ({} {})", mappingFile.getName(),
                            existingMapping.getRequest().getMethod().getName(),
                            existingMapping.getRequest().getUrl());
                    } catch (Exception e) {
                        logger.warn("Failed to load existing mapping from {}: {}", mappingFilePath.getFileName(), e.getMessage(), e);
                    }
                }
            } else {
                // WSL file system issue: Files exist but aren't visible in directory listing
                // Don't lose existing mappings - check if directory has any files at all
                try {
                    long fileCount = java.nio.file.Files.list(mappingsPath)
                        .filter(p -> java.nio.file.Files.isRegularFile(p))
                        .count();
                    if (fileCount > 0) {
                        logger.warn("WSL file system issue detected: Directory has {} file(s) but JSON filter found 0. " +
                            "Existing mappings may be lost. Consider running on native Linux or Windows.", fileCount);
                        // Try one more time with a longer delay
                        Thread.sleep(1000);
                        try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.list(mappingsPath)) {
                            stream.filter(path -> {
                                String fileName = path.getFileName().toString().toLowerCase();
                                return java.nio.file.Files.isRegularFile(path) && fileName.endsWith(".json");
                            }).forEach(path -> {
                                if (!foundFileNames.contains(path.getFileName().toString())) {
                                    existingFilesList.add(path);
                                    foundFileNames.add(path.getFileName().toString());
                                }
                            });
                        }
                        if (existingFilesList.size() > 0) {
                            logger.info("Found {} files after extended wait", existingFilesList.size());
                            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                            for (java.nio.file.Path mappingFilePath : existingFilesList) {
                                try {
                                    File mappingFile = mappingFilePath.toFile();
                                    StubMapping existingMapping = StubMapping.buildFrom(mapper.readTree(mappingFile).toString());
                                    existingMappings.add(existingMapping);
                                } catch (Exception e) {
                                    logger.warn("Failed to load: {}", mappingFilePath.getFileName());
                                }
                            }
                        }
                    } else {
                        logger.info("No existing mapping files found in {} (directory is empty)", mappingsPath);
                    }
                } catch (Exception e) {
                    logger.debug("Could not check file count: {}", e.getMessage());
                }
            }
            
            // Critical check: If directory exists and has files but we couldn't load them,
            // this is a WSL file system issue - don't proceed as we'll lose data
            if (existingMappings.isEmpty() && java.nio.file.Files.exists(mappingsPath)) {
                try {
                    long totalFiles = java.nio.file.Files.list(mappingsPath)
                        .filter(p -> java.nio.file.Files.isRegularFile(p))
                        .count();
                    if (totalFiles > 0) {
                        logger.error("CRITICAL WSL FILE SYSTEM ISSUE: Directory has {} file(s) but we couldn't load any JSON mappings. " +
                            "Proceeding would lose existing mappings. Aborting to prevent data loss.", totalFiles);
                        throw new IOException("Cannot load existing mappings due to WSL file system sync issue. " +
                            "Directory has " + totalFiles + " files but JSON filter found 0. " +
                            "This is a known WSL issue with Windows-mounted drives. " +
                            "Solution: Increase delay in Makefile/build script, or run on native Linux/Windows.");
                    }
                } catch (IOException e) {
                    throw e; // Re-throw our error
                } catch (Exception e) {
                    logger.debug("Could not verify file count: {}", e.getMessage());
                }
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
                            logger.error("Failed to copy body file {}: {}", bodyFileName, e.getMessage());
                        }
                    }
                }
            }
        }
        
        // Merge existing mappings with new ones (new mappings take precedence for duplicates)
        List<StubMapping> mergedMappings = new java.util.ArrayList<>(existingMappings);
        for (StubMapping newMapping : testMethodMappings) {
            // Check if this mapping already exists (by request method + URL)
            boolean isDuplicate = false;
            String newMethod = newMapping.getRequest().getMethod().getName();
            String newUrl = newMapping.getRequest().getUrl();
            for (StubMapping existingMapping : existingMappings) {
                String existingMethod = existingMapping.getRequest().getMethod().getName();
                String existingUrl = existingMapping.getRequest().getUrl();
                if (newMethod.equals(existingMethod) && newUrl.equals(existingUrl)) {
                    isDuplicate = true;
                    break;
                }
            }
            if (!isDuplicate) {
                mergedMappings.add(newMapping);
            } else {
                // Replace duplicate with new mapping (newer takes precedence)
                mergedMappings.removeIf(m -> {
                    String mMethod = m.getRequest().getMethod().getName();
                    String mUrl = m.getRequest().getUrl();
                    return mMethod.equals(newMethod) && mUrl.equals(newUrl);
                });
                mergedMappings.add(newMapping);
            }
        }
        
        // Use a temporary directory for the temp server to avoid conflicts
        // Use thread ID + timestamp + random to ensure uniqueness in parallel execution
        String uniqueId = Thread.currentThread().getId() + "-" + System.currentTimeMillis() + "-" + (int)(Math.random() * 1000000);
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
            for (StubMapping mapping : mergedMappings) {
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
                            
                            // Verify file was actually written
                            if (!destFile.exists() || destFile.length() == 0) {
                                String errorMsg = "  ERROR: File copy failed - dest does not exist or is empty: " + destFile.getName();
                                System.err.println(errorMsg);
                                logger.error(errorMsg);
                                continue;
                            }
                            
                            // Additional verification: try to read the file to ensure it's accessible
                            // This helps catch file system caching issues on Linux with JDK 17
                            try {
                                java.nio.file.Files.readAllBytes(destFile.toPath());
                            } catch (Exception e) {
                                String errorMsg = "  ERROR: File copied but not readable: " + destFile.getName() + " - " + e.getMessage();
                                System.err.println(errorMsg);
                                logger.error(errorMsg);
                                // Continue anyway - might be a transient issue
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
                // List all test method directories to help debug
                if (testMethodDirs != null && testMethodDirs.length > 0) {
                    System.err.println("Test method directories checked:");
                    for (File testMethodDir : testMethodDirs) {
                        File methodMappingsDir = new File(testMethodDir, "mappings");
                        int fileCount = 0;
                        if (methodMappingsDir.exists()) {
                            File[] files = methodMappingsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
                            fileCount = files != null ? files.length : 0;
                        }
                        System.err.println("  - " + testMethodDir.getName() + ": " + fileCount + " mapping file(s)");
                    }
                }
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
        
        logger.info("Completed merging mappings to class-level directory");
    }
}

