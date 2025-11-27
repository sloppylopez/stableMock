package com.stablemock;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.recording.RecordSpecBuilder;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import org.junit.jupiter.api.extension.*;
import org.junit.jupiter.api.extension.BeforeAllCallback;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.List;

/**
 * JUnit 5 extension for StableMock that handles WireMock lifecycle per test.
 * Supports both RECORD and PLAYBACK modes with parallel execution.
 */
public class StableMockExtension implements BeforeAllCallback, BeforeEachCallback, AfterEachCallback, AfterAllCallback, ParameterResolver {

    private static final String MODE_PROPERTY = "stablemock.mode";
    private static final String DEFAULT_MODE = "PLAYBACK";
    
    // Store class-level WireMock server for Spring Boot tests
    // Use ExtensionContext.Store at class level instead of ThreadLocal for parallel execution

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        // For Spring Boot tests, @DynamicPropertySource is evaluated during Spring initialization
        // which happens before beforeEach(). We need to ensure ThreadLocal is set early.
        // We'll use per-test-method servers, but set a placeholder in beforeAll so Spring can read it.
        U annotation = findUAnnotation(context);
        if (annotation == null) {
            return;
        }

        String[] urls = annotation.urls();
        if (urls.length == 0) {
            return;
        }

        // Check if this is a Spring Boot test
        Class<?> testClass = context.getRequiredTestClass();
        boolean isSpringBootTest = false;
        try {
            @SuppressWarnings("unchecked")
            Class<? extends java.lang.annotation.Annotation> springBootTestClass = 
                (Class<? extends java.lang.annotation.Annotation>) Class.forName("org.springframework.boot.test.context.SpringBootTest");
            isSpringBootTest = testClass.isAnnotationPresent(springBootTestClass);
        } catch (ClassNotFoundException e) {
            // Spring Boot not in classpath, not a Spring Boot test
        }
        
        if (isSpringBootTest) {
            // For Spring Boot tests: Use ONE WireMock server per test class (one port) in RECORD mode
            // In PLAYBACK mode, we'll use per-test-method servers to load mappings correctly
            String mode = System.getProperty(MODE_PROPERTY, DEFAULT_MODE);
            
            if ("RECORD".equalsIgnoreCase(mode)) {
                // RECORD mode: Use shared server (one port for all test methods)
                String testClassName = testClass.getSimpleName();
                File testResourcesDir = findTestResourcesDirectory(context);
                File baseMappingsDir = new File(testResourcesDir, "stablemock/" + testClassName);
                
                int port = findFreePort();
                WireMockServer server = startRecording(port, baseMappingsDir, Arrays.asList(urls));
                
                // Store class-level server (shared by all test methods in this class)
                ExtensionContext.Store classStore = context.getStore(ExtensionContext.Namespace.create(context.getRequiredTestClass()));
                classStore.put("wireMockServer", server);
                classStore.put("port", port);
                classStore.put("mode", mode);
                classStore.put("targetUrl", urls.length > 0 ? urls[0] : null);
                
                String baseUrl = "http://localhost:" + port;
                // Set ThreadLocal in beforeAll so @DynamicPropertySource can read it during Spring initialization
                WireMockContext.setBaseUrl(baseUrl);
                WireMockContext.setPort(port);
                System.setProperty("stablemock.baseUrl", baseUrl);
                System.setProperty("stablemock.port", String.valueOf(port));
                
                System.out.println("StableMock: Started shared WireMock server in beforeAll for " + testClassName + " on port " + port);
            } else {
                // PLAYBACK mode: Also use a shared server per test class (one port)
                // Load all mappings from per-test-method directories in beforeAll
                String testClassName = testClass.getSimpleName();
                File testResourcesDir = findTestResourcesDirectory(context);
                File baseMappingsDir = new File(testResourcesDir, "stablemock/" + testClassName);
                
                int port = findFreePort();
                // Start server without auto-loading - we'll load mappings manually
                WireMockConfiguration config = WireMockConfiguration.wireMockConfig()
                        .port(port)
                        .notifier(new com.github.tomakehurst.wiremock.common.ConsoleNotifier(false))
                        .disableRequestJournal();
                
                WireMockServer server = new WireMockServer(config);
                server.start();
                
                // Merge all per-test-method mappings into class-level directory, then use WireMock's auto-loading
                mergePerTestMethodMappings(baseMappingsDir);
                
                // Configure server to load from the merged directory
                // WireMock will automatically load mappings from baseMappingsDir/mappings/
                WireMockConfiguration mergedConfig = WireMockConfiguration.wireMockConfig()
                        .port(port)
                        .notifier(new com.github.tomakehurst.wiremock.common.ConsoleNotifier(false))
                        .usingFilesUnderDirectory(baseMappingsDir.getAbsolutePath());
                
                server.stop();
                server = new WireMockServer(mergedConfig);
                server.start();
                
                // Store class-level server (shared by all test methods in this class)
                ExtensionContext.Store classStore = context.getStore(ExtensionContext.Namespace.create(context.getRequiredTestClass()));
                classStore.put("wireMockServer", server);
                classStore.put("port", port);
                classStore.put("mode", mode);
                classStore.put("targetUrl", urls.length > 0 ? urls[0] : null);
                
                String baseUrl = "http://localhost:" + port;
                WireMockContext.setBaseUrl(baseUrl);
                WireMockContext.setPort(port);
                System.setProperty("stablemock.baseUrl", baseUrl);
                System.setProperty("stablemock.port", String.valueOf(port));
                
                System.out.println("StableMock: Started shared WireMock server in beforeAll for " + testClassName + " on port " + port + " (PLAYBACK mode)");
            }
        }
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        U annotation = findUAnnotation(context);
        if (annotation == null) {
            return;
        }

        String[] urls = annotation.urls();
        if (urls.length == 0) {
            return;
        }

        // Check if there's a shared server from beforeAll (Spring Boot tests in RECORD mode)
        ExtensionContext.Store classStore = context.getStore(ExtensionContext.Namespace.create(context.getRequiredTestClass()));
        WireMockServer classServer = classStore.get("wireMockServer", WireMockServer.class);
        String classMode = classStore.get("mode", String.class);
        
        // Use shared server for both RECORD and PLAYBACK modes
        // In RECORD mode: server records requests, we save mappings per test method
        // In PLAYBACK mode: server loads from class-level directory, but we can load per-test-method mappings in beforeEach
        if (classServer != null) {
            // Use the shared server - ThreadLocal is already set in beforeAll
            // Just ensure it's still set (ThreadLocal is per-thread, safe for parallel execution)
            Integer port = classStore.get("port", Integer.class);
            String baseUrl = "http://localhost:" + port;
            WireMockContext.setBaseUrl(baseUrl);
            WireMockContext.setPort(port);
            
            // Store method-specific info for saving mappings later
            String testClassName = context.getRequiredTestClass().getSimpleName();
            String testMethodName = context.getTestMethod()
                    .map(Method::getName)
                    .orElse("unknown");
            File testResourcesDir = findTestResourcesDirectory(context);
            File mappingsDir = new File(testResourcesDir, "stablemock/" + testClassName + "/" + testMethodName);
            
            String mode = classStore.get("mode", String.class);
            
            // In PLAYBACK mode, mappings are already loaded in beforeAll, so nothing to do here
            
            // Store the request journal size to identify new requests in afterEach
            // In RECORD mode, WireMock records requests but doesn't create stub mappings until snapshotRecord()
            int existingRequestCount = classServer.getAllServeEvents().size();
            
            context.getStore(ExtensionContext.Namespace.create(context.getUniqueId()))
                    .put("wireMockServer", classServer);
            context.getStore(ExtensionContext.Namespace.create(context.getUniqueId()))
                    .put("port", port);
            context.getStore(ExtensionContext.Namespace.create(context.getUniqueId()))
                    .put("mode", mode);
            context.getStore(ExtensionContext.Namespace.create(context.getUniqueId()))
                    .put("mappingsDir", mappingsDir);
            context.getStore(ExtensionContext.Namespace.create(context.getUniqueId()))
                    .put("targetUrl", classStore.get("targetUrl", String.class));
            context.getStore(ExtensionContext.Namespace.create(context.getUniqueId()))
                    .put("useClassLevelServer", true);
            context.getStore(ExtensionContext.Namespace.create(context.getUniqueId()))
                    .put("existingRequestCount", existingRequestCount);
            
            System.out.println("StableMock: Using shared server on port " + port + " for test method " + testMethodName);
            return;
        }

        // Non-Spring Boot test or Spring Boot test without beforeAll - start WireMock per test
        String mode = System.getProperty(MODE_PROPERTY, DEFAULT_MODE);
        String testClassName = context.getRequiredTestClass().getSimpleName();
        String testMethodName = context.getTestMethod()
                .map(Method::getName)
                .orElse("unknown");

        // Create mappings directory: src/test/resources/stablemock/<TestClass>/<testMethod>/
        File testResourcesDir = findTestResourcesDirectory(context);
        File mappingsDir = new File(testResourcesDir, "stablemock/" + testClassName + "/" + testMethodName);
        System.out.println("StableMock: Mappings directory: " + mappingsDir.getAbsolutePath());

        WireMockServer wireMockServer;
        int port = findFreePort();

        if ("RECORD".equalsIgnoreCase(mode)) {
            wireMockServer = startRecording(port, mappingsDir, Arrays.asList(urls));
        } else {
            wireMockServer = startPlayback(port, mappingsDir);
        }

        // Store in extension context for cleanup
        context.getStore(ExtensionContext.Namespace.create(context.getUniqueId()))
                .put("wireMockServer", wireMockServer);
        context.getStore(ExtensionContext.Namespace.create(context.getUniqueId()))
                .put("port", port);
        context.getStore(ExtensionContext.Namespace.create(context.getUniqueId()))
                .put("mode", mode);
        context.getStore(ExtensionContext.Namespace.create(context.getUniqueId()))
                .put("mappingsDir", mappingsDir);
        context.getStore(ExtensionContext.Namespace.create(context.getUniqueId()))
                .put("targetUrl", urls.length > 0 ? urls[0] : null);
        context.getStore(ExtensionContext.Namespace.create(context.getUniqueId()))
                .put("useClassLevelServer", false);

        // Set system properties and thread-local
        // For Spring Boot tests, ThreadLocal is critical because @DynamicPropertySource
        // supplier reads from it, and it's per-thread (safe for parallel execution)
        String baseUrl = "http://localhost:" + port;
        WireMockContext.setBaseUrl(baseUrl);
        WireMockContext.setPort(port);
        
        // Also set system property as fallback
        System.setProperty("stablemock.port", String.valueOf(port));
        System.setProperty("stablemock.baseUrl", baseUrl);
        
        System.out.println("StableMock: Started WireMock on port " + port + " for test method " + 
            context.getTestMethod().map(Method::getName).orElse("unknown"));
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        ExtensionContext.Store store = context.getStore(ExtensionContext.Namespace.create(context.getUniqueId()));
        Boolean useClassLevelServer = store.get("useClassLevelServer", Boolean.class);
        
        if (useClassLevelServer != null && useClassLevelServer) {
            // For Spring Boot tests using shared server, save mappings for this test method
            // Note: snapshotRecord() gets ALL mappings, so we save all of them
            // In the future, we could filter by request journal to get only new ones
            String mode = store.get("mode", String.class);
            File mappingsDir = store.get("mappingsDir", File.class);
            String targetUrl = store.get("targetUrl", String.class);
            
            if ("RECORD".equalsIgnoreCase(mode) && mappingsDir != null && targetUrl != null) {
                ExtensionContext.Store classStore = context.getStore(ExtensionContext.Namespace.create(context.getRequiredTestClass()));
                WireMockServer server = classStore.get("wireMockServer", WireMockServer.class);
                if (server != null) {
                    // Check if there are any serve events (requests) before saving mappings
                    // If there are no serve events, there's nothing to save
                    List<com.github.tomakehurst.wiremock.stubbing.ServeEvent> serveEvents = server.getAllServeEvents();
                    if (serveEvents.isEmpty()) {
                        System.out.println("StableMock: No requests recorded for this test method, skipping mapping save");
                    } else {
                        // Save all mappings (they'll include requests from all test methods in this class)
                        // This is acceptable since each test method gets its own directory
                        saveMappings(server, mappingsDir, targetUrl);
                    }
                }
            }
            // Don't stop the server here - it will be stopped in afterAll
        } else {
            // Non-Spring Boot test - stop the per-test server
            WireMockServer wireMockServer = store.get("wireMockServer", WireMockServer.class);
            String mode = store.get("mode", String.class);
            File mappingsDir = store.get("mappingsDir", File.class);
            String targetUrl = store.get("targetUrl", String.class);

            if (wireMockServer != null) {
                if ("RECORD".equalsIgnoreCase(mode)) {
                    saveMappings(wireMockServer, mappingsDir, targetUrl);
                }
                wireMockServer.stop();
            }
        }

        // Clear thread-local
        WireMockContext.clear();
    }
    
    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        // Clean up class-level WireMock server for Spring Boot tests
        ExtensionContext.Store classStore = context.getStore(ExtensionContext.Namespace.create(context.getRequiredTestClass()));
        WireMockServer server = classStore.get("wireMockServer", WireMockServer.class);
        if (server != null) {
            server.stop();
            classStore.remove("wireMockServer");
            classStore.remove("port");
            System.out.println("StableMock: Stopped class-level WireMock in afterAll");
        }
        
        // Clear system properties
        System.clearProperty("stablemock.port");
        System.clearProperty("stablemock.baseUrl");
        WireMockContext.clear();
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        Class<?> parameterType = parameterContext.getParameter().getType();
        return parameterType == int.class || parameterType == Integer.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        Class<?> parameterType = parameterContext.getParameter().getType();
        if (parameterType == int.class || parameterType == Integer.class) {
            Integer port = extensionContext.getStore(ExtensionContext.Namespace.create(extensionContext.getUniqueId()))
                    .get("port", Integer.class);
            return port != null ? port : 0;
        }
        return null;
    }

    private U findUAnnotation(ExtensionContext context) {
        // Check method-level annotation first
        U methodAnnotation = context.getTestMethod()
                .map(method -> method.getAnnotation(U.class))
                .orElse(null);
        if (methodAnnotation != null) {
            return methodAnnotation;
        }

        // Check class-level annotation
        return context.getRequiredTestClass().getAnnotation(U.class);
    }

    private File findTestResourcesDirectory(ExtensionContext context) {
        // Try to find src/test/resources relative to the test class
        Class<?> testClass = context.getRequiredTestClass();

        // Get the class file location
        String classPath = testClass.getResource(testClass.getSimpleName() + ".class").toString();

        // Extract the base path (remove file: and class name)
        if (classPath.startsWith("file:/")) {
            String path = classPath.substring(6); // Remove "file:/"
            // Handle Windows paths (file:/C:/path vs file:///C:/path)
            if (path.startsWith("/") && path.length() > 3 && path.charAt(2) == ':') {
                path = path.substring(1); // Remove leading slash for Windows
            }
            // Remove /target/classes/ or /build/classes/ and class name
            if (path.contains("/target/classes/")) {
                path = path.substring(0, path.indexOf("/target/classes/"));
                File result = new File(path, "src/test/resources");
                if (result.exists() || result.getParentFile().exists()) {
                    return result;
                }
            } else if (path.contains("/build/classes/")) {
                path = path.substring(0, path.indexOf("/build/classes/"));
                File result = new File(path, "src/test/resources");
                if (result.exists() || result.getParentFile().exists()) {
                    return result;
                }
            } else if (path.contains("\\target\\classes\\")) {
                path = path.substring(0, path.indexOf("\\target\\classes\\"));
                File result = new File(path, "src/test/resources");
                if (result.exists() || result.getParentFile().exists()) {
                    return result;
                }
            } else if (path.contains("\\build\\classes\\")) {
                path = path.substring(0, path.indexOf("\\build\\classes\\"));
                File result = new File(path, "src/test/resources");
                if (result.exists() || result.getParentFile().exists()) {
                    return result;
                }
            }
        }

        // Fallback: use current working directory
        String userDir = System.getProperty("user.dir");
        File fallback = new File(userDir, "src/test/resources");
        if (!fallback.exists()) {
            // Try relative to current directory
            fallback = new File("src/test/resources");
        }
        System.out.println("StableMock: Using test resources directory: " + fallback.getAbsolutePath());
        return fallback;
    }

    private WireMockServer startRecording(int port, File mappingsDir, List<String> targetUrls) {
        if (targetUrls.isEmpty()) {
            throw new IllegalArgumentException("At least one targetUrl must be provided for recording mode");
        }

        if (!mappingsDir.exists() && !mappingsDir.mkdirs()) {
            throw new RuntimeException("Failed to create mappings directory: " + mappingsDir.getAbsolutePath());
        }

        WireMockConfiguration config = WireMockConfiguration.wireMockConfig()
                .port(port)
                .notifier(new com.github.tomakehurst.wiremock.common.ConsoleNotifier(false))
                .usingFilesUnderDirectory(mappingsDir.getAbsolutePath());

        WireMockServer server = new WireMockServer(config);
        server.start();

        // Configure catch-all proxy stub
        String primaryUrl = targetUrls.get(0);
        server.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock.any(
                                com.github.tomakehurst.wiremock.client.WireMock.anyUrl())
                        .willReturn(
                                com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                                        .proxiedFrom(primaryUrl)));

        System.out.println("StableMock: Recording mode on port " + port + ", proxying to " + primaryUrl);
        return server;
    }

    private WireMockServer startPlayback(int port, File mappingsDir) {
        if (!mappingsDir.exists() && !mappingsDir.mkdirs()) {
            // In playback mode, if directory doesn't exist, create it but warn
            System.out.println("StableMock: Warning - mappings directory does not exist: " + mappingsDir.getAbsolutePath());
        }

        // Use WireMock's built-in file loading - it will automatically load mappings from the directory
        WireMockConfiguration config = WireMockConfiguration.wireMockConfig()
                .port(port)
                .notifier(new com.github.tomakehurst.wiremock.common.ConsoleNotifier(false))
                .usingFilesUnderDirectory(mappingsDir.getAbsolutePath());

        WireMockServer server = new WireMockServer(config);
        server.start();

        System.out.println("StableMock: Playback mode on port " + port + ", loading mappings from " + mappingsDir.getAbsolutePath());
        return server;
    }

    private void saveMappings(WireMockServer wireMockServer, File mappingsDir, String targetUrl) throws IOException {
        // Use WireMock's file-based persistence to save mappings in a format that can be reloaded
        // This ensures mappings are saved in WireMock's native format
        File mappingsSubDir = new File(mappingsDir, "mappings");
        File filesSubDir = new File(mappingsDir, "__files");

        if (!mappingsSubDir.exists() && !mappingsSubDir.mkdirs()) {
            throw new IOException("Failed to create mappings subdirectory: " + mappingsSubDir.getAbsolutePath());
        }
        if (!filesSubDir.exists() && !filesSubDir.mkdirs()) {
            throw new IOException("Failed to create __files subdirectory: " + filesSubDir.getAbsolutePath());
        }

        // Use WireMock's snapshotRecord with makeStubsPersistent(true) to get mappings in the right format
        RecordSpecBuilder builder = new RecordSpecBuilder()
                .forTarget(targetUrl)
                .makeStubsPersistent(true)  // Enable persistence format
                .extractTextBodiesOver(255);

        com.github.tomakehurst.wiremock.recording.SnapshotRecordResult result = wireMockServer.snapshotRecord(builder.build());
        List<StubMapping> mappings = result.getStubMappings();

        // Create a temporary WireMock server with the per-test-method directory as file source
        // This ensures mappings are saved in WireMock's native format that can be reloaded
        WireMockConfiguration tempConfig = WireMockConfiguration.wireMockConfig()
                .dynamicPort()  // Use any available port
                .notifier(new com.github.tomakehurst.wiremock.common.ConsoleNotifier(false))
                .usingFilesUnderDirectory(mappingsDir.getAbsolutePath());
        
        WireMockServer tempServer = new WireMockServer(tempConfig);
        tempServer.start();
        
        try {
            // Add only the recorded mappings to temporary server (no proxy stub)
            for (StubMapping mapping : mappings) {
                tempServer.addStubMapping(mapping);
            }
            
            // Save all stub mappings using WireMock's file source (saves to mappingsDir)
            tempServer.saveMappings();
        } finally {
            tempServer.stop();
        }
        
        int savedCount = mappings.size();

        System.out.println("StableMock: Saved " + savedCount + " mappings to " + mappingsSubDir.getAbsolutePath());
    }
    
    private void loadMappingsFromPerTestMethodDirectory(WireMockServer server, File mappingsDir) {
        File mappingsSubDir = new File(mappingsDir, "mappings");
        if (!mappingsSubDir.exists() || !mappingsSubDir.isDirectory()) {
            return; // No mappings for this test method
        }
        
        File[] mappingFiles = mappingsSubDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
        if (mappingFiles == null || mappingFiles.length == 0) {
            return; // No mapping files
        }
        
        // Load mappings directly from files using WireMock's Json.read()
        int loadedCount = 0;
        for (File mappingFile : mappingFiles) {
            try {
                String jsonContent = new String(java.nio.file.Files.readAllBytes(mappingFile.toPath()));
                StubMapping mapping = com.github.tomakehurst.wiremock.common.Json.read(jsonContent, StubMapping.class);
                server.addStubMapping(mapping);
                loadedCount++;
            } catch (Exception e) {
                // Ignore individual mapping failures
            }
        }
        
        if (loadedCount > 0) {
            System.out.println("StableMock: Loaded " + loadedCount + " stub mappings from " + mappingsSubDir.getAbsolutePath());
        }
    }
    
    private void loadAllMappingsFromPerTestMethodDirectories(WireMockServer server, File baseMappingsDir) {
        // Find all per-test-method directories
        File[] testMethodDirs = baseMappingsDir.listFiles(file -> 
            file.isDirectory() && !file.getName().equals("mappings") && !file.getName().equals("__files"));
        if (testMethodDirs == null) {
            System.out.println("StableMock: No per-test-method directories found in " + baseMappingsDir.getAbsolutePath());
            return;
        }
        
        System.out.println("StableMock: Found " + testMethodDirs.length + " per-test-method directories");
        
        int totalLoaded = 0;
        for (File testMethodDir : testMethodDirs) {
            File methodMappingsDir = new File(testMethodDir, "mappings");
            if (!methodMappingsDir.exists() || !methodMappingsDir.isDirectory()) {
                continue;
            }
            
            File[] mappingFiles = methodMappingsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
            if (mappingFiles != null) {
                for (File mappingFile : mappingFiles) {
                    try {
                        // Load mappings saved by WireMock's saveMappings() - format should be correct
                        String jsonContent = new String(java.nio.file.Files.readAllBytes(mappingFile.toPath()));
                        StubMapping mapping = com.github.tomakehurst.wiremock.common.Json.read(jsonContent, StubMapping.class);
                        
                        // Try to add mapping - ignore duplicates and other errors
                        try {
                            server.addStubMapping(mapping);
                            totalLoaded++;
                        } catch (Exception addException) {
                            // Ignore duplicates and other add errors - continue loading other mappings
                        }
                    } catch (Exception e) {
                        // Ignore JSON parsing errors - continue loading other mappings
                    }
                }
            }
        }
        
        System.out.println("StableMock: Loaded " + totalLoaded + " stub mappings from all per-test-method directories");
    }
    
    @SuppressWarnings("unused")
    private void mergePerTestMethodMappings(File baseMappingsDir) {
        // Create class-level mappings directory structure
        File classMappingsDir = new File(baseMappingsDir, "mappings");
        File classFilesDir = new File(baseMappingsDir, "__files");
        
        // Clear existing mappings to avoid duplicates
        if (classMappingsDir.exists()) {
            File[] existingFiles = classMappingsDir.listFiles();
            if (existingFiles != null) {
                for (File file : existingFiles) {
                    file.delete();
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
        
        // Find all per-test-method directories (they are children of baseMappingsDir)
        // Exclude "mappings" and "__files" directories which are class-level
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
            
            // Copy mapping files directly - WireMock should handle its own format
            File[] mappingFiles = methodMappingsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
            if (mappingFiles != null) {
                for (File mappingFile : mappingFiles) {
                    try {
                        // Use test method name as prefix to avoid conflicts
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
            
            // Copy __files if they exist
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
    
    private int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Failed to find free port", e);
        }
    }
}

