package com.stablemock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.recording.RecordSpecBuilder;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import org.junit.jupiter.api.extension.*;

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
public class StableMockExtension implements BeforeEachCallback, AfterEachCallback, ParameterResolver {

    private static final String MODE_PROPERTY = "stablemock.mode";
    private static final String DEFAULT_MODE = "PLAYBACK";

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

        // Set system properties for Spring Boot integration
        // These must be set before Spring context initializes
        String baseUrl = "http://localhost:" + port;
        System.setProperty("stablemock.port", String.valueOf(port));
        System.setProperty("stablemock.baseUrl", baseUrl);
        System.out.println("StableMock: Set stablemock.baseUrl=" + baseUrl + " (port=" + port + ")");

        // Set thread-local for parallel execution support
        WireMockContext.setBaseUrl(baseUrl);
        WireMockContext.setPort(port);
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        ExtensionContext.Store store = context.getStore(ExtensionContext.Namespace.create(context.getUniqueId()));
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

        // Clear thread-local
        WireMockContext.clear();

        // Clear system properties
        System.clearProperty("stablemock.port");
        System.clearProperty("stablemock.baseUrl");
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

        WireMockConfiguration config = WireMockConfiguration.wireMockConfig()
                .port(port)
                .notifier(new com.github.tomakehurst.wiremock.common.ConsoleNotifier(false))
                .usingFilesUnderDirectory(mappingsDir.getAbsolutePath());

        WireMockServer server = new WireMockServer(config);
        server.start();

        System.out.println("StableMock: Playback mode on port " + port);
        return server;
    }

    private void saveMappings(WireMockServer wireMockServer, File mappingsDir, String targetUrl) throws IOException {
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
                .makeStubsPersistent(false)
                .extractTextBodiesOver(255);

        List<StubMapping> mappings = wireMockServer.snapshotRecord(builder.build()).getStubMappings();

        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);

        for (StubMapping mapping : mappings) {
            String fileName = mapping.getName();
            if (fileName == null) {
                fileName = "mapping-" + mapping.getId() + ".json";
            }
            if (!fileName.toLowerCase().endsWith(".json")) {
                fileName += ".json";
            }
            fileName = fileName.replaceAll("[^a-zA-Z0-9.-]", "_");

            File file = new File(mappingsSubDir, fileName);
            mapper.writeValue(file, mapping);
        }

        System.out.println("StableMock: Saved " + mappings.size() + " mappings to " + mappingsSubDir.getAbsolutePath());
    }

    private int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Failed to find free port", e);
        }
    }
}

