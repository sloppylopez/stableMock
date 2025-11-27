package com.stablemock.core.storage;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.recording.RecordSpecBuilder;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;

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
    
    public static void mergePerTestMethodMappings(File baseMappingsDir) {
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
            file.isDirectory() && !file.getName().equals("mappings") && !file.getName().equals("__files"));
        if (testMethodDirs == null) {
            return;
        }
        
        int totalCopied = 0;
        for (File testMethodDir : testMethodDirs) {
            File methodMappingsDir = new File(testMethodDir, "mappings");
            File methodFilesDir = new File(testMethodDir, "__files");
            
            if (!methodMappingsDir.exists() || !methodMappingsDir.isDirectory()) {
                continue;
            }
            
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

