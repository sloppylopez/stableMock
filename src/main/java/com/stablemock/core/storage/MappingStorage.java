package com.stablemock.core.storage;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.recording.RecordSpecBuilder;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.stablemock.core.server.WireMockServerManager;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Handles saving and loading WireMock mappings to/from disk.
 */
public final class MappingStorage {
    
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
        
        System.out.println("StableMock: Saved " + mappings.size() + " mappings to " + mappingsSubDir.getAbsolutePath());
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
            System.out.println("StableMock: No new requests recorded for this test method, skipping mapping save");
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
            System.out.println("StableMock: No mappings found for requests in this test method, skipping save");
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
                            System.err.println("StableMock: Failed to copy body file " + bodyFileName + ": " + e.getMessage());
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
        
        System.out.println("StableMock: Saved " + testMethodMappings.size() + " mappings to " + testMethodMappingsSubDir.getAbsolutePath());
    }
    
    /**
     * Saves mappings for test methods with multiple @U annotations.
     * Each annotation's mappings are saved separately in annotation_X directories
     * within the test method's mappings directory, allowing proper isolation of
     * requests made to different target URLs.
     *
     * <p>This method iterates through each annotation configuration, retrieves the
     * corresponding WireMock server, filters serve events that occurred during this
     * test method, and saves the resulting mappings to separate directories.</p>
     *
     * @param wireMockServer The primary WireMock server (used as fallback for annotation index 0)
     * @param testMethodMappingsDir Directory for this test method's mappings (e.g., testClassName/testMethodName)
     * @param baseMappingsDir Base directory for all test class mappings
     * @param annotationInfos List of annotation configurations containing URL targets and indices
     * @param existingRequestCount Number of requests from previous tests (for class-level server tracking)
     * @param allServers List of all WireMock servers (one per annotation), may be null
     * @throws IOException If directory creation or file operations fail
     */
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
                System.err.println("StableMock: No server found for annotation " + annotationInfo.index + ", skipping");
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
                System.out.println("StableMock: No new requests recorded for annotation " + annotationInfo.index + " in this test method, skipping");
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
                            for (String annotationUrl : annotationInfo.urls) {
                                try {
                                    java.net.URL parsedAnnotationUrl = new java.net.URL(annotationUrl);
                                    String annotationPath = parsedAnnotationUrl.getPath();
                                    if (requestUrl.startsWith(annotationPath) || annotationPath.isEmpty()) {
                                        matches = true;
                                        break;
                                    }
                                } catch (java.net.MalformedURLException e) {
                                    System.err.println("StableMock: Failed to parse annotation URL: " + annotationUrl + " - " + e.getMessage());
                                    // Continue to next URL instead of treating as match
                                }
                            }
                            if (matches) {
                                break;
                            }
                        }
                    } catch (Exception e) {
                        String requestUrl = serveEvent.getRequest().getUrl();
                        for (String annotationUrl : annotationInfo.urls) {
                            try {
                                java.net.URL parsedAnnotationUrl = new java.net.URL(annotationUrl);
                                String annotationPath = parsedAnnotationUrl.getPath();
                                if (requestUrl.startsWith(annotationPath) || annotationPath.isEmpty()) {
                                    matches = true;
                                    break;
                                }
                            } catch (java.net.MalformedURLException ex) {
                                System.err.println("StableMock: Failed to parse annotation URL: " + annotationUrl + " - " + ex.getMessage());
                                // Continue to next URL instead of treating as match
                            }
                        }
                        if (matches) {
                            break;
                        }
                    }
                }
                
                if (matches) {
                    annotationMappings.add(mapping);
                }
            }

            if (annotationMappings.isEmpty()) {
                System.out.println("StableMock: No mappings found for annotation " + annotationInfo.index + ", skipping save");
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
                            System.err.println("StableMock: Failed to copy body file " + bodyFileName + ": " + e.getMessage());
                        }
                    } else {
                        System.err.println("StableMock: Warning - body file not found: " + bodyFileName + " (checked url_" + annotationInfo.index + "/__files, server __files, and base __files)");
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
            
            System.out.println("StableMock: Saved " + annotationMappings.size() + " mappings for annotation " + 
                    annotationInfo.index + " to " + annotationMappingsSubDir.getAbsolutePath());
        }
    }
    
    /**
     * Merges all annotation_X directory mappings within a single test method directory
     * into the method's root mappings directory.
     *
     * <p>This method consolidates mappings from multiple annotation directories
     * (e.g., annotation_0, annotation_1) into a single mappings directory for easier
     * loading during playback mode. Mapping files are prefixed with their source
     * annotation directory name to prevent naming conflicts.</p>
     *
     * <p>Body files from each annotation's __files directory are also copied to
     * the merged __files directory.</p>
     *
     * @param methodMappingsDir The test method's mappings directory containing annotation_X subdirectories
     */
    public static void mergeAnnotationMappingsForMethod(File methodMappingsDir) {
        File mergedMappingsDir = new File(methodMappingsDir, "mappings");
        File mergedFilesDir = new File(methodMappingsDir, "__files");
        
        if (!mergedMappingsDir.exists() && !mergedMappingsDir.mkdirs()) {
            System.out.println("StableMock: Warning - failed to create merged mappings directory");
            return;
        }
        
        if (!mergedFilesDir.exists() && !mergedFilesDir.mkdirs()) {
            System.out.println("StableMock: Warning - failed to create merged __files directory");
            return;
        }
        
        File[] annotationDirs = methodMappingsDir.listFiles(file -> 
            file.isDirectory() && file.getName().startsWith("annotation_"));
        if (annotationDirs == null || annotationDirs.length == 0) {
            return;
        }
        
        int totalCopied = 0;
        for (File annotationDir : annotationDirs) {
            File annotationMappingsDir = new File(annotationDir, "mappings");
            File annotationFilesDir = new File(annotationDir, "__files");
            
            if (annotationMappingsDir.exists() && annotationMappingsDir.isDirectory()) {
                File[] mappingFiles = annotationMappingsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
                if (mappingFiles != null) {
                    for (File mappingFile : mappingFiles) {
                        try {
                            String prefix = annotationDir.getName() + "_";
                            String newName = prefix + mappingFile.getName();
                            File destFile = new File(mergedMappingsDir, newName);
                            java.nio.file.Files.copy(mappingFile.toPath(), destFile.toPath(), 
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            totalCopied++;
                        } catch (Exception e) {
                            System.err.println("StableMock: Failed to copy annotation mapping " + mappingFile.getName() + ": " + e.getMessage());
                        }
                    }
                }
            }
            
            if (annotationFilesDir.exists() && annotationFilesDir.isDirectory()) {
                File[] bodyFiles = annotationFilesDir.listFiles();
                if (bodyFiles != null) {
                    for (File bodyFile : bodyFiles) {
                        try {
                            File destFile = new File(mergedFilesDir, bodyFile.getName());
                            java.nio.file.Files.copy(bodyFile.toPath(), destFile.toPath(), 
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        } catch (Exception e) {
                            // Ignore individual file copy failures
                        }
                    }
                }
            }
        }
        
        if (totalCopied > 0) {
            System.out.println("StableMock: Merged " + totalCopied + " mappings from annotation directories into " + mergedMappingsDir.getAbsolutePath());
        }
    }
    
    /**
     * Merges all test methods' annotation_X mappings for a specific URL index into url_X directory.
     * This is used in playback mode for multiple URLs to consolidate mappings from all test methods.
     *
     * <p>For each test method directory, this method finds the annotation_X directory matching
     * the specified URL index and copies its mappings to the url_X directory at the base level.
     * This allows WireMock to serve stubs for a specific target URL during playback.</p>
     *
     * <p>The method performs the following operations:</p>
     * <ul>
     *   <li>Cleans up existing url_X directory if it exists</li>
     *   <li>Creates new url_X/mappings and url_X/__files directories</li>
     *   <li>Iterates through all test method directories</li>
     *   <li>Copies mappings from annotation_X to url_X with method name prefix</li>
     *   <li>Copies body files from multiple sources (annotation, base, and existing url_X __files)</li>
     * </ul>
     *
     * @param baseMappingsDir Base directory for all test class mappings
     * @param urlIndex The annotation/URL index to merge (0-based, corresponds to @U annotation order)
     */
    public static void mergeAnnotationMappingsForUrlIndex(File baseMappingsDir, int urlIndex) {
        File urlDir = new File(baseMappingsDir, "url_" + urlIndex);
        File urlMappingsDir = new File(urlDir, "mappings");
        File urlFilesDir = new File(urlDir, "__files");
        
        // Clean up existing url_X directory if it exists
        if (urlDir.exists()) {
            try {
                java.nio.file.Files.walk(urlDir.toPath())
                    .sorted(java.util.Comparator.reverseOrder())
                    .map(java.nio.file.Path::toFile)
                    .forEach(File::delete);
            } catch (Exception e) {
                System.err.println("StableMock: Failed to clean up url_" + urlIndex + " directory: " + e.getMessage());
            }
        }
        
        if (!urlMappingsDir.exists() && !urlMappingsDir.mkdirs()) {
            System.err.println("StableMock: Failed to create url_" + urlIndex + " mappings directory");
            return;
        }
        if (!urlFilesDir.exists() && !urlFilesDir.mkdirs()) {
            System.err.println("StableMock: Failed to create url_" + urlIndex + " __files directory");
            return;
        }
        
        File[] testMethodDirs = baseMappingsDir.listFiles(file -> 
            file.isDirectory() && !file.getName().equals("mappings") && !file.getName().equals("__files") && !file.getName().startsWith("url_"));
        if (testMethodDirs == null) {
            return;
        }
        
        int totalCopied = 0;
        for (File testMethodDir : testMethodDirs) {
            File annotationDir = new File(testMethodDir, "annotation_" + urlIndex);
            File annotationMappingsDir = new File(annotationDir, "mappings");
            File annotationFilesDir = new File(annotationDir, "__files");
            
            if (annotationMappingsDir.exists() && annotationMappingsDir.isDirectory()) {
                File[] mappingFiles = annotationMappingsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
                if (mappingFiles != null && mappingFiles.length > 0) {
                    System.out.println("StableMock: Merging " + mappingFiles.length + " mappings from " + testMethodDir.getName() + "/annotation_" + urlIndex + " to url_" + urlIndex);
                    for (File mappingFile : mappingFiles) {
                        try {
                            String prefix = testMethodDir.getName() + "_";
                            String newName = prefix + mappingFile.getName();
                            File destFile = new File(urlMappingsDir, newName);
                            java.nio.file.Files.copy(mappingFile.toPath(), destFile.toPath(), 
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            totalCopied++;
                        } catch (Exception e) {
                            System.err.println("StableMock: Failed to copy mapping " + mappingFile.getName() + ": " + e.getMessage());
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
                            System.err.println("StableMock: Failed to copy body file " + bodyFile.getName() + ": " + e.getMessage());
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
        
        if (totalCopied > 0) {
            System.out.println("StableMock: Merged " + totalCopied + " mappings for url_" + urlIndex + " from all test methods");
        } else {
            System.out.println("StableMock: Warning - No mappings found for url_" + urlIndex);
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
                        System.err.println("StableMock: Failed to delete existing file: " + file.getAbsolutePath());
                    }
                }
            }
        } else if (!classMappingsDir.mkdirs()) {
            System.out.println("StableMock: Warning - failed to create class-level mappings directory");
            return;
        }
        
        if (!classFilesDir.exists() && !classFilesDir.mkdirs()) {
            System.out.println("StableMock: Warning - failed to create class-level __files directory");
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
        int totalCopied = 0;
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
                            totalCopied++;
                        } catch (Exception e) {
                            System.err.println("StableMock: Failed to copy mapping " + mappingFile.getName() + ": " + e.getMessage());
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
        
        if (totalCopied > 0) {
            System.out.println("StableMock: Merged " + totalCopied + " mappings from per-test-method directories into " + classMappingsDir.getAbsolutePath());
        }
    }
    
    public static void cleanupClassLevelDirectory(File baseMappingsDir) {
        File baseMappingsSubDir = new File(baseMappingsDir, "mappings");
        File baseFilesDir = new File(baseMappingsDir, "__files");
        
        int deletedMappings = 0;
        int deletedFiles = 0;
        
        if (baseMappingsSubDir.exists() && baseMappingsSubDir.isDirectory()) {
            File[] mappingFiles = baseMappingsSubDir.listFiles();
            if (mappingFiles != null) {
                for (File file : mappingFiles) {
                    if (file.isFile() && file.delete()) {
                        deletedMappings++;
                    } else if (file.isFile()) {
                        System.err.println("StableMock: Failed to delete mapping file: " + file.getAbsolutePath());
                    }
                }
            }
        }
        
        if (baseFilesDir.exists() && baseFilesDir.isDirectory()) {
            File[] bodyFiles = baseFilesDir.listFiles();
            if (bodyFiles != null) {
                for (File file : bodyFiles) {
                    if (file.isFile() && file.delete()) {
                        deletedFiles++;
                    } else if (file.isFile()) {
                        System.err.println("StableMock: Failed to delete body file: " + file.getAbsolutePath());
                    }
                }
            }
        }
        
        if (deletedMappings > 0 || deletedFiles > 0) {
            System.out.println("StableMock: Cleaned up class-level directory - removed " + 
                deletedMappings + " mappings and " + deletedFiles + " body files " +
                "(files are in test-method directories)");
        }
        
        if (baseMappingsSubDir.exists() && baseMappingsSubDir.isDirectory()) {
            File[] remainingFiles = baseMappingsSubDir.listFiles();
            if (remainingFiles == null || remainingFiles.length == 0) {
                if (baseMappingsSubDir.delete()) {
                    System.out.println("StableMock: Removed empty class-level mappings directory");
                }
            }
        }
        
        if (baseFilesDir.exists() && baseFilesDir.isDirectory()) {
            File[] remainingFiles = baseFilesDir.listFiles();
            if (remainingFiles == null || remainingFiles.length == 0) {
                if (baseFilesDir.delete()) {
                    System.out.println("StableMock: Removed empty class-level __files directory");
                }
            }
        }
    }
}

