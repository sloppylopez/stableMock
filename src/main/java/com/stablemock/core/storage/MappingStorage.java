package com.stablemock.core.storage;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.recording.RecordSpecBuilder;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.stablemock.core.server.WireMockServerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Handles saving and loading WireMock mappings to/from disk.
 */
public final class MappingStorage {
    
    private static final Logger logger = LoggerFactory.getLogger(MappingStorage.class);
    
    private MappingStorage() {
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
    
    public static void saveMappingsForTestMethodMultipleAnnotations(WireMockServer wireMockServer, 
            File testMethodMappingsDir, File baseMappingsDir, 
            java.util.List<WireMockServerManager.AnnotationInfo> annotationInfos, int existingRequestCount,
            java.util.List<WireMockServer> allServers) throws IOException {
        
        // Get mappings from each server separately
        for (WireMockServerManager.AnnotationInfo annotationInfo : annotationInfos) {
            // Get the server for this annotation index
            WireMockServer annotationServer = wireMockServer;
            if (allServers != null && annotationInfo.index < allServers.size()) {
                annotationServer = allServers.get(annotationInfo.index);
            }
            
            if (annotationServer == null) {
                logger.warn("No server found for annotation {}, skipping", annotationInfo.index);
                continue;
            }
            
            // Get serve events from this specific server
            List<com.github.tomakehurst.wiremock.stubbing.ServeEvent> allServeEvents = annotationServer.getAllServeEvents();
            // For class-level servers, we track existingRequestCount from the primary server
            // But for multiple servers, we need to track per-server. For now, use 0 as we're getting all events from this server
            int serverExistingRequestCount = (annotationInfo.index == 0) ? existingRequestCount : 0;
            List<com.github.tomakehurst.wiremock.stubbing.ServeEvent> testMethodServeEvents = 
                allServeEvents.size() > serverExistingRequestCount 
                    ? allServeEvents.subList(serverExistingRequestCount, allServeEvents.size())
                    : new java.util.ArrayList<>();
            
            if (testMethodServeEvents.isEmpty()) {
                continue;
            }
            
            // Get mappings from this server
            RecordSpecBuilder builder = new RecordSpecBuilder()
                    .makeStubsPersistent(true)
                    .extractTextBodiesOver(255);
            
            if (annotationInfo.urls.length > 0) {
                builder.forTarget(annotationInfo.urls[0]);
            }

            com.github.tomakehurst.wiremock.recording.SnapshotRecordResult result = annotationServer.snapshotRecord(builder.build());
            List<StubMapping> allMappings = result.getStubMappings();
            File annotationMappingsDir = new File(testMethodMappingsDir, "annotation_" + annotationInfo.index);
            File annotationMappingsSubDir = new File(annotationMappingsDir, "mappings");
            File annotationFilesSubDir = new File(annotationMappingsDir, "__files");

            if (!annotationMappingsSubDir.exists() && !annotationMappingsSubDir.mkdirs()) {
                throw new IOException("Failed to create mappings subdirectory: " + annotationMappingsSubDir.getAbsolutePath());
            }
            if (!annotationFilesSubDir.exists() && !annotationFilesSubDir.mkdirs()) {
                throw new IOException("Failed to create __files subdirectory: " + annotationFilesSubDir.getAbsolutePath());
            }

            List<StubMapping> annotationMappings = new java.util.ArrayList<>();
            for (StubMapping mapping : allMappings) {
                boolean matches = false;
                for (com.github.tomakehurst.wiremock.stubbing.ServeEvent serveEvent : testMethodServeEvents) {
                    try {
                        if (mapping.getRequest().match(serveEvent.getRequest()).isExactMatch()) {
                            String requestUrl = serveEvent.getRequest().getUrl();
                            if (matchesAnnotationUrl(requestUrl, annotationInfo.urls)) {
                                matches = true;
                                break;
                            }
                        }
                    } catch (Exception e) {
                        String requestUrl = serveEvent.getRequest().getUrl();
                        if (matchesAnnotationUrl(requestUrl, annotationInfo.urls)) {
                            matches = true;
                            break;
                        }
                    }
                }
                
                if (matches) {
                    annotationMappings.add(mapping);
                }
            }

            if (annotationMappings.isEmpty()) {
                continue;
            }

            // Copy body files from url_X/__files (where they're stored during recording with multiple URLs)
            // Also check base __files directory as fallback
            File urlIndexFilesDir = new File(baseMappingsDir, "url_" + annotationInfo.index + "/__files");
            File baseFilesDir = new File(baseMappingsDir, "__files");
            
            // annotationServer is already set above - use it to get the files directory
            File serverFilesDir = null;
            try {
                // Get the files root from the server's configuration
                java.lang.reflect.Field optionsField = annotationServer.getClass().getDeclaredField("options");
                optionsField.setAccessible(true);
                com.github.tomakehurst.wiremock.core.WireMockConfiguration options = 
                    (com.github.tomakehurst.wiremock.core.WireMockConfiguration) optionsField.get(annotationServer);
                if (options != null) {
                    java.lang.reflect.Method filesRootMethod = options.getClass().getMethod("filesRoot");
                    java.io.File filesRoot = (java.io.File) filesRootMethod.invoke(options);
                    if (filesRoot != null) {
                        serverFilesDir = new File(filesRoot, "__files");
                    }
                }
            } catch (Exception e) {
                // Ignore reflection errors, fall back to url_X/__files
            }
            
            for (StubMapping mapping : annotationMappings) {
                String bodyFileName = mapping.getResponse().getBodyFileName();
                if (bodyFileName != null) {
                    File sourceFile = null;
                    
                    // First try url_X/__files (for multiple URLs - this is where they should be)
                    if (urlIndexFilesDir.exists() && urlIndexFilesDir.isDirectory()) {
                        File urlFile = new File(urlIndexFilesDir, bodyFileName);
                        if (urlFile.exists()) {
                            sourceFile = urlFile;
                        }
                    }
                    
                    // Try server's __files directory (from the server's configuration)
                    if (sourceFile == null && serverFilesDir != null && serverFilesDir.exists() && serverFilesDir.isDirectory()) {
                        File serverFile = new File(serverFilesDir, bodyFileName);
                        if (serverFile.exists()) {
                            sourceFile = serverFile;
                        }
                    }
                    
                    // Fallback to base __files directory
                    if (sourceFile == null && baseFilesDir.exists() && baseFilesDir.isDirectory()) {
                        File baseFile = new File(baseFilesDir, bodyFileName);
                        if (baseFile.exists()) {
                            sourceFile = baseFile;
                        }
                    }
                    
                    if (sourceFile != null) {
                        File destFile = new File(annotationFilesSubDir, bodyFileName);
                        try {
                            java.nio.file.Files.copy(sourceFile.toPath(), destFile.toPath(), 
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        } catch (Exception e) {
                            logger.error("Failed to copy body file {}: {}", bodyFileName, e.getMessage());
                        }
                    } else {
                        logger.warn("Body file not found: {} (checked url_{}/__files, server __files, and base __files)", bodyFileName, annotationInfo.index);
                    }
                }
            }
            
            WireMockConfiguration tempConfig = WireMockConfiguration.wireMockConfig()
                    .dynamicPort()
                    .notifier(new com.github.tomakehurst.wiremock.common.ConsoleNotifier(false))
                    .usingFilesUnderDirectory(annotationMappingsDir.getAbsolutePath());
            
            WireMockServer tempServer = new WireMockServer(tempConfig);
            tempServer.start();
            
            try {
                for (StubMapping mapping : annotationMappings) {
                    tempServer.addStubMapping(mapping);
                }
                tempServer.saveMappings();
            } finally {
                tempServer.stop();
            }
            
            logger.info("Saved {} mappings for annotation {} to {}", annotationMappings.size(), annotationInfo.index, annotationMappingsSubDir.getAbsolutePath());
        }
    }
    
    /**
     * Merges all test methods' annotation_X mappings for a specific URL index into url_X directory.
     * This is used in playback mode for multiple URLs.
     */
    public static void mergeAnnotationMappingsForUrlIndex(File baseMappingsDir, int urlIndex) {
        File urlDir = new File(baseMappingsDir, "url_" + urlIndex);
        File urlMappingsDir = new File(urlDir, "mappings");
        File urlFilesDir = new File(urlDir, "__files");
        
        // Clean up existing url_X directory if it exists
        if (urlDir.exists()) {
            try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.walk(urlDir.toPath())) {
                stream.sorted(java.util.Comparator.reverseOrder())
                    .map(java.nio.file.Path::toFile)
                    .forEach(file -> {
                        if (!file.delete()) {
                            logger.warn("Failed to delete file: {}", file.getPath());
                        }
                    });
            } catch (Exception e) {
                logger.error("Failed to clean up url_{} directory: {}", urlIndex, e.getMessage());
            }
        }
        
        if (!urlMappingsDir.exists() && !urlMappingsDir.mkdirs()) {
            logger.error("Failed to create url_{} mappings directory", urlIndex);
            return;
        }
        if (!urlFilesDir.exists() && !urlFilesDir.mkdirs()) {
            logger.error("Failed to create url_{} __files directory", urlIndex);
            return;
        }
        
        File[] testMethodDirs = baseMappingsDir.listFiles(file -> 
            file.isDirectory() && !file.getName().equals("mappings") && !file.getName().equals("__files") && !file.getName().startsWith("url_"));
        if (testMethodDirs == null) {
            return;
        }
        
        for (File testMethodDir : testMethodDirs) {
            File annotationDir = new File(testMethodDir, "annotation_" + urlIndex);
            File annotationMappingsDir = new File(annotationDir, "mappings");
            File annotationFilesDir = new File(annotationDir, "__files");
            
            if (annotationMappingsDir.exists() && annotationMappingsDir.isDirectory()) {
                File[] mappingFiles = annotationMappingsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
                if (mappingFiles != null) {
                    for (File mappingFile : mappingFiles) {
                        try {
                            String prefix = testMethodDir.getName() + "_";
                            String newName = prefix + mappingFile.getName();
                            File destFile = new File(urlMappingsDir, newName);
                            java.nio.file.Files.copy(mappingFile.toPath(), destFile.toPath(), 
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        } catch (Exception e) {
                            logger.error("Failed to copy mapping {}: {}", mappingFile.getName(), e.getMessage());
                        }
                    }
                }
            }
            
            // Copy body files from annotation_X/__files
            if (annotationFilesDir.exists() && annotationFilesDir.isDirectory()) {
                File[] bodyFiles = annotationFilesDir.listFiles();
                if (bodyFiles != null) {
                    for (File bodyFile : bodyFiles) {
                        try {
                            File destFile = new File(urlFilesDir, bodyFile.getName());
                            // Always copy/overwrite to ensure latest version is used
                            java.nio.file.Files.copy(bodyFile.toPath(), destFile.toPath(), 
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        } catch (Exception e) {
                            logger.error("Failed to copy body file {}: {}", bodyFile.getName(), e.getMessage());
                        }
                    }
                }
            }
            
            // Also copy body files from base __files directory and url_X/__files (where they're stored during recording)
            File baseFilesDir = new File(baseMappingsDir, "__files");
            if (baseFilesDir.exists() && baseFilesDir.isDirectory()) {
                File[] baseBodyFiles = baseFilesDir.listFiles();
                if (baseBodyFiles != null) {
                    for (File bodyFile : baseBodyFiles) {
                        try {
                            File destFile = new File(urlFilesDir, bodyFile.getName());
                            // Copy if it doesn't exist or if source is newer
                            if (!destFile.exists() || bodyFile.lastModified() > destFile.lastModified()) {
                                java.nio.file.Files.copy(bodyFile.toPath(), destFile.toPath(), 
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            }
                        } catch (Exception e) {
                            // Ignore individual file copy failures
                        }
                    }
                }
            }
            
            // Also check url_X/__files directory (created during recording in beforeAll)
            File urlIndexFilesDir = new File(baseMappingsDir, "url_" + urlIndex + "/__files");
            if (urlIndexFilesDir.exists() && urlIndexFilesDir.isDirectory()) {
                File[] urlBodyFiles = urlIndexFilesDir.listFiles();
                if (urlBodyFiles != null) {
                    for (File bodyFile : urlBodyFiles) {
                        try {
                            File destFile = new File(urlFilesDir, bodyFile.getName());
                            // Always copy from url_X/__files as it's the source of truth for this URL index
                            java.nio.file.Files.copy(bodyFile.toPath(), destFile.toPath(), 
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        } catch (Exception e) {
                            // Ignore individual file copy failures
                        }
                    }
                }
            }
        }
    }
    
    public static void mergePerTestMethodMappings(File baseMappingsDir) {
        // First, handle single URL case (merge to baseMappingsDir/mappings)
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
        if (testMethodDirs == null) {
            return;
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
            return;
        }
        
        // Single URL case - merge test method mappings to class-level directory
        for (File testMethodDir : testMethodDirs) {
            File methodMappingsDir = new File(testMethodDir, "mappings");
            File methodFilesDir = new File(testMethodDir, "__files");
            
            if (methodMappingsDir.exists() && methodMappingsDir.isDirectory()) {
                File[] mappingFiles = methodMappingsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
                if (mappingFiles != null) {
                    for (File mappingFile : mappingFiles) {
                        try {
                            String prefix = testMethodDir.getName() + "_";
                            String newName = prefix + mappingFile.getName();
                            File destFile = new File(classMappingsDir, newName);
                            java.nio.file.Files.copy(mappingFile.toPath(), destFile.toPath(), 
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        } catch (Exception e) {
                            logger.error("Failed to copy mapping {}: {}", mappingFile.getName(), e.getMessage());
                        }
                    }
                }
            }
            
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
    }
    
    public static void cleanupClassLevelDirectory(File baseMappingsDir) {
        File baseMappingsSubDir = new File(baseMappingsDir, "mappings");
        File baseFilesDir = new File(baseMappingsDir, "__files");
        
        if (baseMappingsSubDir.exists() && baseMappingsSubDir.isDirectory()) {
            File[] mappingFiles = baseMappingsSubDir.listFiles();
            if (mappingFiles != null) {
                for (File file : mappingFiles) {
                    if (file.isFile() && !file.delete()) {
                        logger.error("Failed to delete mapping file: {}", file.getAbsolutePath());
                    }
                }
            }
        }
        
        if (baseFilesDir.exists() && baseFilesDir.isDirectory()) {
            File[] bodyFiles = baseFilesDir.listFiles();
            if (bodyFiles != null) {
                for (File file : bodyFiles) {
                    if (file.isFile() && !file.delete()) {
                        logger.error("Failed to delete body file: {}", file.getAbsolutePath());
                    }
                }
            }
        }
        
        if (baseMappingsSubDir.exists() && baseMappingsSubDir.isDirectory()) {
            File[] remainingFiles = baseMappingsSubDir.listFiles();
            if (remainingFiles == null || remainingFiles.length == 0) {
                if (!baseMappingsSubDir.delete()) {
                    logger.warn("Failed to delete base mappings directory: {}", baseMappingsSubDir.getAbsolutePath());
                }
            }
        }
        
        if (baseFilesDir.exists() && baseFilesDir.isDirectory()) {
            File[] remainingFiles = baseFilesDir.listFiles();
            if (remainingFiles == null || remainingFiles.length == 0) {
                if (!baseFilesDir.delete()) {
                    logger.warn("Failed to delete base files directory: {}", baseFilesDir.getAbsolutePath());
                }
            }
        }
    }
    
    /**
     * Checks if a request URL matches any of the annotation URLs.
     * 
     * <p>Matching semantics:
     * <ul>
     *   <li>Extracts the path component from each annotation URL</li>
     *   <li>Matches if the request URL starts with the annotation path</li>
     *   <li>Matches if the annotation path is empty (root path)</li>
     *   <li>If URL parsing fails (MalformedURLException), returns true as a fallback
     *       to ensure the request is not incorrectly excluded</li>
     * </ul>
     * 
     * @param requestUrl the request URL to match against
     * @param annotationUrls array of annotation URLs to check
     * @return true if the request URL matches any annotation URL, false otherwise
     */
    private static boolean matchesAnnotationUrl(String requestUrl, String[] annotationUrls) {
        for (String annotationUrl : annotationUrls) {
            try {
                java.net.URL parsedAnnotationUrl = new java.net.URL(annotationUrl);
                String annotationPath = parsedAnnotationUrl.getPath();
                if (annotationPath.isEmpty() || requestUrl.startsWith(annotationPath)) {
                    return true;
                }
            } catch (java.net.MalformedURLException e) {
                // If URL parsing fails, return true as fallback to avoid incorrectly excluding requests
                return true;
            }
        }
        return false;
    }
}

