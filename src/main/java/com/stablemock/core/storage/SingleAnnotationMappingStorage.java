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
        File classMappingsDir = new File(baseMappingsDir, "mappings");
        File classFilesDir = new File(baseMappingsDir, "__files");
        
        if (classMappingsDir.exists()) {
            File[] existingFiles = classMappingsDir.listFiles();
            if (existingFiles != null) {
                for (File file : existingFiles) {
                    if (!file.delete()) {
                        logger.error("Failed to delete existing file: {}", file.getAbsolutePath());
                    }
                }
            }
        } else if (!classMappingsDir.mkdirs()) {
            logger.warn("Failed to create class-level mappings directory");
            return;
        }
        
        if (!classFilesDir.exists() && !classFilesDir.mkdirs()) {
            logger.warn("Failed to create class-level __files directory");
            return;
        }
        
        File[] testMethodDirs = baseMappingsDir.listFiles(file -> 
            file.isDirectory() && !file.getName().equals("mappings") && !file.getName().equals("__files") && !file.getName().startsWith("url_"));
        if (testMethodDirs == null || testMethodDirs.length == 0) {
            logger.warn("No test method directories found in {}", baseMappingsDir.getAbsolutePath());
            return;
        }
        
        // Sort test method directories for consistent ordering across platforms
        // This ensures the same merge order on Windows and Linux
        java.util.Arrays.sort(testMethodDirs, java.util.Comparator.comparing(File::getName));
        
        logger.info("Merging mappings for single URL from {} test method(s)", testMethodDirs.length);
        
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
            logger.debug("Multiple URLs detected, skipping single URL merge");
            return;
        }
        
        // Single URL case - merge test method mappings to class-level directory
        for (File testMethodDir : testMethodDirs) {
            File methodMappingsDir = new File(testMethodDir, "mappings");
            File methodFilesDir = new File(testMethodDir, "__files");
            
            if (!methodMappingsDir.exists() || !methodMappingsDir.isDirectory()) {
                logger.debug("No mappings directory found for test method {}", testMethodDir.getName());
                continue;
            }
            
            if (methodMappingsDir.exists() && methodMappingsDir.isDirectory()) {
                File[] mappingFiles = methodMappingsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
                if (mappingFiles != null && mappingFiles.length > 0) {
                    for (File mappingFile : mappingFiles) {
                        try {
                            String prefix = testMethodDir.getName() + "_";
                            String newName = prefix + mappingFile.getName();
                            File destFile = new File(classMappingsDir, newName);
                            
                            // Ensure destination directory exists
                            if (!classMappingsDir.exists() && !classMappingsDir.mkdirs()) {
                                logger.error("Failed to create class-level mappings directory");
                                continue;
                            }
                            
                            java.nio.file.Files.copy(mappingFile.toPath(), destFile.toPath(), 
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            logger.debug("Copied mapping {} from {} to {}", mappingFile.getName(), testMethodDir.getName(), destFile.getName());
                        } catch (Exception e) {
                            logger.error("Failed to copy mapping {}: {}", mappingFile.getName(), e.getMessage(), e);
                        }
                    }
                    logger.info("Copied {} mapping(s) from test method {} to class-level", 
                        mappingFiles.length, testMethodDir.getName());
                } else {
                    logger.debug("No mapping files found in {}", methodMappingsDir.getAbsolutePath());
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

