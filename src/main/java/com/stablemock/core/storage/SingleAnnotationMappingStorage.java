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
        List<com.github.tomakehurst.wiremock.stubbing.ServeEvent> testMethodServeEvents = 
            allServeEvents.subList(existingRequestCount, allServeEvents.size());
        
        if (testMethodServeEvents.isEmpty()) {
            return;
        }

        RecordSpecBuilder builder = new RecordSpecBuilder()
                .forTarget(targetUrl)
                .makeStubsPersistent(true)
                .extractTextBodiesOver(255);

        com.github.tomakehurst.wiremock.recording.SnapshotRecordResult result = wireMockServer.snapshotRecord(builder.build());
        List<StubMapping> allMappings = result.getStubMappings();

        List<StubMapping> testMethodMappings = new java.util.ArrayList<>();
        for (StubMapping mapping : allMappings) {
            boolean matches = false;
            for (com.github.tomakehurst.wiremock.stubbing.ServeEvent serveEvent : testMethodServeEvents) {
                try {
                    if (mapping.getRequest().match(serveEvent.getRequest()).isExactMatch()) {
                        matches = true;
                        break;
                    }
                } catch (Exception e) {
                    String requestUrl = serveEvent.getRequest().getUrl();
                    String requestMethod = serveEvent.getRequest().getMethod().getName();
                    String mappingMethod = mapping.getRequest().getMethod() != null 
                        ? mapping.getRequest().getMethod().getName() 
                        : "";
                    String mappingUrl = mapping.getRequest().getUrl() != null 
                        ? mapping.getRequest().getUrl() 
                        : "";
                    if (mappingMethod.equals(requestMethod) && 
                        (mappingUrl.equals(requestUrl) || mappingUrl.contains(requestUrl) || requestUrl.contains(mappingUrl))) {
                        matches = true;
                        break;
                    }
                }
            }
            
            if (matches) {
                testMethodMappings.add(mapping);
            }
        }

        if (testMethodMappings.isEmpty()) {
            return;
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
        } finally {
            tempServer.stop();
        }
        
        logger.info("Saved {} mappings to {}", testMethodMappings.size(), testMethodMappingsSubDir.getAbsolutePath());
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
            logger.info("Multiple URLs detected, skipping single URL merge");
            return;
        }
        
        // Single URL case - merge test method mappings to class-level directory
        int totalMappingsCopied = 0;
        for (File testMethodDir : testMethodDirs) {
            File methodMappingsDir = new File(testMethodDir, "mappings");
            File methodFilesDir = new File(testMethodDir, "__files");
            
            logger.info("Processing test method: {}", testMethodDir.getName());
            
            if (!methodMappingsDir.exists() || !methodMappingsDir.isDirectory()) {
                logger.warn("  No mappings directory found for test method {}", testMethodDir.getName());
                continue;
            }
            
            if (methodMappingsDir.exists() && methodMappingsDir.isDirectory()) {
                File[] mappingFiles = methodMappingsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
                if (mappingFiles != null && mappingFiles.length > 0) {
                    logger.info("  Found {} mapping file(s) in {}", mappingFiles.length, testMethodDir.getName());
                    for (File mappingFile : mappingFiles) {
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
                            
                            java.nio.file.Files.copy(mappingFile.toPath(), destFile.toPath(), 
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            totalMappingsCopied++;
                            logger.info("  ✓ Copied: {} {} -> {}", method, url, destFile.getName());
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
        logger.info("=== Merge complete: {} mapping(s) copied ===", totalMappingsCopied);
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
                        logger.info("  [{}] {} {} ({})", method, url, mappingFile.getName(), 
                            mappingFile.length() > 0 ? mappingFile.length() + " bytes" : "EMPTY FILE!");
                    }
                } catch (Exception e) {
                    logger.error("  Could not parse mapping file {} for summary: {}", mappingFile.getName(), e.getMessage());
                }
            }
            logger.info("Summary: {} GET, {} POST, {} other", getCount, postCount, finalMappings.length - getCount - postCount);
            if (postCount == 0) {
                logger.error("WARNING: No POST mappings found in merged mappings! This will cause POST requests to fail.");
            }
        } else {
            logger.error("ERROR: No mappings found in class-level directory after merge! Directory: {}", classMappingsDir.getAbsolutePath());
            // List what test method directories exist for debugging
            if (testMethodDirs != null && testMethodDirs.length > 0) {
                logger.error("Test method directories that were checked: {}", java.util.Arrays.toString(
                    java.util.Arrays.stream(testMethodDirs).map(File::getName).toArray(String[]::new)));
            }
        }
        
        // Force file system sync to ensure all files are written before WireMock loads them
        // This is important on Linux where file system caching might delay visibility
        try {
            java.nio.file.Files.createFile(new File(classMappingsDir, ".merge-complete").toPath());
            java.nio.file.Files.deleteIfExists(new File(classMappingsDir, ".merge-complete").toPath());
        } catch (Exception e) {
            // Ignore - this is just to force a sync
            logger.debug("File sync check failed (non-critical): {}", e.getMessage());
        }
        
        logger.info("Completed merging mappings to class-level directory");
    }
}

