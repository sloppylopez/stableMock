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
                // Merge mappings temporarily to class-level for WireMock to load them
                // (WireMock needs files in a known location to resolve bodyFileName references)
                // We'll clean up the class-level directory in afterAll
                String testClassName = testClass.getSimpleName();
                File testResourcesDir = findTestResourcesDirectory(context);
                File baseMappingsDir = new File(testResourcesDir, "stablemock/" + testClassName);
                
                int port = findFreePort();
                
                // Merge all per-test-method mappings into class-level directory for WireMock to load
                mergePerTestMethodMappings(baseMappingsDir);
                
                // Configure server to load from the merged directory
                WireMockConfiguration config = WireMockConfiguration.wireMockConfig()
                        .port(port)
                        .notifier(new com.github.tomakehurst.wiremock.common.ConsoleNotifier(false))
                        .usingFilesUnderDirectory(baseMappingsDir.getAbsolutePath());
                
                WireMockServer server = new WireMockServer(config);
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
                    // Get the request count at the start of this test method
                    Integer existingRequestCount = store.get("existingRequestCount", Integer.class);
                    if (existingRequestCount == null) {
                        existingRequestCount = 0;
                    }
                    
                    // Get the class-level directory to copy __files from
                    String testClassName = context.getRequiredTestClass().getSimpleName();
                    File testResourcesDir = findTestResourcesDirectory(context);
                    File baseMappingsDir = new File(testResourcesDir, "stablemock/" + testClassName);
                    
                    // Check if there are any serve events (requests) before saving mappings
                    List<com.github.tomakehurst.wiremock.stubbing.ServeEvent> serveEvents = server.getAllServeEvents();
                    if (serveEvents.isEmpty() || serveEvents.size() <= existingRequestCount) {
                        System.out.println("StableMock: No new requests recorded for this test method, skipping mapping save");
                    } else {
                        // Save only mappings for requests made during this test method
                        saveMappingsForTestMethod(server, mappingsDir, baseMappingsDir, targetUrl, existingRequestCount);
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
            String mode = classStore.get("mode", String.class);
            server.stop();
            classStore.remove("wireMockServer");
            classStore.remove("port");
            System.out.println("StableMock: Stopped class-level WireMock in afterAll");
            
            // Clean up class-level directory files in both RECORD and PLAYBACK modes
            // In RECORD: Files have been copied to test-method directories during afterEach
            // In PLAYBACK: Files were merged temporarily for WireMock to load, but are duplicates
            // This ensures no duplication and keeps the structure clean
            if ("RECORD".equalsIgnoreCase(mode) || "PLAYBACK".equalsIgnoreCase(mode)) {
                String testClassName = context.getRequiredTestClass().getSimpleName();
                File testResourcesDir = findTestResourcesDirectory(context);
                File baseMappingsDir = new File(testResourcesDir, "stablemock/" + testClassName);
                File baseMappingsSubDir = new File(baseMappingsDir, "mappings");
                File baseFilesDir = new File(baseMappingsDir, "__files");
                
                int deletedMappings = 0;
                int deletedFiles = 0;
                
                // Remove class-level mappings (they're duplicated in test-method directories)
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
                
                // Remove class-level __files (they've been copied to test-method directories)
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
                
                // Delete the empty directories themselves
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

        // WireMock 3.x requires __files and mappings directories to exist before recording starts
        File mappingsSubDir = new File(mappingsDir, "mappings");
        File filesSubDir = new File(mappingsDir, "__files");
        if (!mappingsSubDir.exists() && !mappingsSubDir.mkdirs()) {
            throw new RuntimeException("Failed to create mappings subdirectory: " + mappingsSubDir.getAbsolutePath());
        }
        if (!filesSubDir.exists() && !filesSubDir.mkdirs()) {
            throw new RuntimeException("Failed to create __files subdirectory: " + filesSubDir.getAbsolutePath());
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
    
    private void saveMappingsForTestMethod(WireMockServer wireMockServer, File testMethodMappingsDir, 
            File baseMappingsDir, String targetUrl, int existingRequestCount) throws IOException {
        // Create test-method-specific directories
        File testMethodMappingsSubDir = new File(testMethodMappingsDir, "mappings");
        File testMethodFilesSubDir = new File(testMethodMappingsDir, "__files");

        if (!testMethodMappingsSubDir.exists() && !testMethodMappingsSubDir.mkdirs()) {
            throw new IOException("Failed to create mappings subdirectory: " + testMethodMappingsSubDir.getAbsolutePath());
        }
        if (!testMethodFilesSubDir.exists() && !testMethodFilesSubDir.mkdirs()) {
            throw new IOException("Failed to create __files subdirectory: " + testMethodFilesSubDir.getAbsolutePath());
        }

        // Get all serve events and filter to only those that occurred during this test method
        List<com.github.tomakehurst.wiremock.stubbing.ServeEvent> allServeEvents = wireMockServer.getAllServeEvents();
        List<com.github.tomakehurst.wiremock.stubbing.ServeEvent> testMethodServeEvents = 
            allServeEvents.subList(existingRequestCount, allServeEvents.size());
        
        if (testMethodServeEvents.isEmpty()) {
            System.out.println("StableMock: No new requests recorded for this test method, skipping mapping save");
            return;
        }

        // Use WireMock's snapshotRecord with makeStubsPersistent(true) to get mappings in the right format
        RecordSpecBuilder builder = new RecordSpecBuilder()
                .forTarget(targetUrl)
                .makeStubsPersistent(true)  // Enable persistence format
                .extractTextBodiesOver(255);

        com.github.tomakehurst.wiremock.recording.SnapshotRecordResult result = wireMockServer.snapshotRecord(builder.build());
        List<StubMapping> allMappings = result.getStubMappings();

        // Filter mappings to only those that correspond to requests made during this test method
        // Use WireMock's request matching to match mappings to serve events
        List<StubMapping> testMethodMappings = new java.util.ArrayList<>();
        for (StubMapping mapping : allMappings) {
            // Check if this mapping matches any request from this test method
            // Use WireMock's request matching to check if mapping would match any serve event
            boolean matches = false;
            for (com.github.tomakehurst.wiremock.stubbing.ServeEvent serveEvent : testMethodServeEvents) {
                try {
                    // Use WireMock's request matching to check if this mapping matches the serve event's request
                    if (mapping.getRequest().match(serveEvent.getRequest()).isExactMatch()) {
                        matches = true;
                        break;
                    }
                } catch (Exception e) {
                    // If matching fails, try simple string comparison as fallback
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

        // Copy body files from class-level directory to test-method directory BEFORE creating temp server
        // This ensures WireMock's saveMappings() will find them in the right place
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
        
        // Create a temporary WireMock server with the per-test-method directory as file source
        // WireMock will save mappings and use the __files that are already in the test-method directory
        WireMockConfiguration tempConfig = WireMockConfiguration.wireMockConfig()
                .dynamicPort()  // Use any available port
                .notifier(new com.github.tomakehurst.wiremock.common.ConsoleNotifier(false))
                .usingFilesUnderDirectory(testMethodMappingsDir.getAbsolutePath());
        
        WireMockServer tempServer = new WireMockServer(tempConfig);
        tempServer.start();
        
        try {
            // Add only the filtered mappings to temporary server
            for (StubMapping mapping : testMethodMappings) {
                tempServer.addStubMapping(mapping);
            }
            
            // Save stub mappings using WireMock's file source (saves to testMethodMappingsDir)
            // The body files are already in the test-method __files directory, so WireMock will reference them correctly
            tempServer.saveMappings();
        } finally {
            tempServer.stop();
        }
        
        System.out.println("StableMock: Saved " + testMethodMappings.size() + " mappings to " + testMethodMappingsSubDir.getAbsolutePath());
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
                        System.err.println("StableMock: Failed to load mapping from " + mappingFile.getName() + ": " + e.getMessage());
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

