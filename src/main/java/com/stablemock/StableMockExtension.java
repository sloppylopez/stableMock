package com.stablemock;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.stablemock.core.analysis.*;
import com.stablemock.core.config.StableMockConfig;
import com.stablemock.core.context.ExtensionContextManager;
import com.stablemock.core.resolver.TestContextResolver;
import com.stablemock.core.server.WireMockServerManager;
import com.stablemock.core.storage.MappingStorage;
import org.junit.jupiter.api.extension.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * JUnit 5 extension for StableMock that handles WireMock lifecycle per test.
 * Supports both RECORD and PLAYBACK modes with parallel execution.
 */
public class StableMockExtension
        implements BeforeAllCallback, BeforeEachCallback, AfterEachCallback, AfterAllCallback, ParameterResolver {

    private static final Logger logger = LoggerFactory.getLogger(StableMockExtension.class);

    @Override
    public void beforeAll(ExtensionContext context) {
        U[] annotations = TestContextResolver.findAllUAnnotations(context);
        if (annotations.length == 0) {
            return;
        }

        // Collect ALL URLs from ALL annotations
        List<String> allUrls = new ArrayList<>();
        for (U annotation : annotations) {
            String[] urls = annotation.urls();
            if (urls != null) {
                for (String url : urls) {
                    if (url != null && !url.isEmpty()) {
                        allUrls.add(url);
                    }
                }
            }
        }

        if (allUrls.isEmpty()) {
            return;
        }

        boolean isSpringBootTest = TestContextResolver.isSpringBootTest(context);
        if (!isSpringBootTest) {
            return;
        }

        String mode = StableMockConfig.getMode();
        String testClassName = TestContextResolver.getTestClassName(context);
        File testResourcesDir = TestContextResolver.findTestResourcesDirectory(context);
        File baseMappingsDir = new File(testResourcesDir, "stablemock/" + testClassName);

        ExtensionContextManager.ClassLevelStore classStore = new ExtensionContextManager.ClassLevelStore(context);

        boolean isRecordMode = StableMockConfig.isRecordMode();
        boolean hasMultipleUrls = allUrls.size() > 1;

        if (hasMultipleUrls) {
            // Create separate WireMock server for each URL
            List<WireMockServer> servers = new java.util.ArrayList<>();
            List<Integer> ports = new java.util.ArrayList<>();

            for (int i = 0; i < allUrls.size(); i++) {
                String targetUrl = allUrls.get(i);
                int port = WireMockServerManager.findFreePort();
                File urlMappingsDir = new File(baseMappingsDir, "url_" + i);

                WireMockServer server;
                if (isRecordMode) {
                    server = WireMockServerManager.startRecording(port, urlMappingsDir,
                            Collections.singletonList(targetUrl));
                } else {
                    // In playback mode, merge all test methods' annotation_X mappings for this URL
                    // index
                    MappingStorage.mergeAnnotationMappingsForUrlIndex(baseMappingsDir, i);
                    server = WireMockServerManager.startPlayback(port, urlMappingsDir, 
                            testResourcesDir, testClassName, null);
                }

                servers.add(server);
                ports.add(port);
            }

            classStore.putServers(servers);
            classStore.putPorts(ports);
            classStore.putServer(servers.get(0));
            classStore.putPort(ports.get(0));
            classStore.putMode(mode);
            classStore.putTargetUrl(allUrls.get(0));

            String baseUrl = "http://localhost:" + ports.get(0);
            WireMockContext.setBaseUrl(baseUrl);
            WireMockContext.setPort(ports.get(0));
            System.setProperty(StableMockConfig.BASE_URL_PROPERTY, baseUrl);
            System.setProperty(StableMockConfig.PORT_PROPERTY, String.valueOf(ports.get(0)));

            for (int i = 0; i < ports.size(); i++) {
                System.setProperty(StableMockConfig.PORT_PROPERTY + "." + i, String.valueOf(ports.get(i)));
                System.setProperty(StableMockConfig.BASE_URL_PROPERTY + "." + i, "http://localhost:" + ports.get(i));
            }

            logger.info("Started {} WireMock server(s) for {} in {} mode", servers.size(), testClassName, mode);
        } else {
            // Single URL - use existing single server logic
            int port = WireMockServerManager.findFreePort();
            WireMockServer server;

            if (isRecordMode) {
                server = WireMockServerManager.startRecording(port, baseMappingsDir, allUrls);
            } else {
                MappingStorage.mergePerTestMethodMappings(baseMappingsDir);
                server = WireMockServerManager.startPlayback(port, baseMappingsDir, 
                        testResourcesDir, testClassName, null);
            }

            classStore.putServer(server);
            classStore.putPort(port);
            classStore.putMode(mode);
            classStore.putTargetUrl(allUrls.get(0));

            String baseUrl = "http://localhost:" + port;
            WireMockContext.setBaseUrl(baseUrl);
            WireMockContext.setPort(port);
            System.setProperty(StableMockConfig.BASE_URL_PROPERTY, baseUrl);
            System.setProperty(StableMockConfig.PORT_PROPERTY, String.valueOf(port));

            logger.info("Started WireMock server for {} on port {} in {} mode", testClassName, port, mode);
        }
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        U[] annotations = TestContextResolver.findAllUAnnotations(context);
        if (annotations.length == 0) {
            return;
        }

        // Collect ALL URLs from ALL annotations
        List<String> allUrls = new java.util.ArrayList<>();
        for (U annotation : annotations) {
            String[] urls = annotation.urls();
            if (urls != null) {
                for (String url : urls) {
                    if (url != null && !url.isEmpty()) {
                        allUrls.add(url);
                    }
                }
            }
        }

        if (allUrls.isEmpty()) {
            return;
        }

        ExtensionContextManager.ClassLevelStore classStore = new ExtensionContextManager.ClassLevelStore(context);
        WireMockServer classServer = classStore.getServer();

        ExtensionContextManager.MethodLevelStore methodStore = new ExtensionContextManager.MethodLevelStore(context);

        if (classServer != null) {
            Integer port = classStore.getPort();
            String baseUrl = "http://localhost:" + port;
            WireMockContext.setBaseUrl(baseUrl);
            WireMockContext.setPort(port);

            String testClassName = TestContextResolver.getTestClassName(context);
            String testMethodName = TestContextResolver.getTestMethodName(context);
            File testResourcesDir = TestContextResolver.findTestResourcesDirectory(context);
            File mappingsDir = new File(testResourcesDir, "stablemock/" + testClassName + "/" + testMethodName);

            int existingRequestCount = classServer.getAllServeEvents().size();

            methodStore.putServer(classServer);
            methodStore.putPort(port);
            methodStore.putMode(classStore.getMode());
            methodStore.putMappingsDir(mappingsDir);
            methodStore.putTargetUrl(classStore.getTargetUrl());
            methodStore.putUseClassLevelServer(true);
            methodStore.putExistingRequestCount(existingRequestCount);

            if (allUrls.size() > 1) {
                // Create AnnotationInfo objects for saving mappings
                List<WireMockServerManager.AnnotationInfo> annotationInfos = new ArrayList<>();
                for (int i = 0; i < allUrls.size(); i++) {
                    String url = allUrls.get(i);
                    annotationInfos.add(new WireMockServerManager.AnnotationInfo(i, new String[] { url }));
                }
                methodStore.putAnnotationInfos(annotationInfos);

                List<Integer> ports = classStore.getPorts();
                if (ports == null || ports.isEmpty()) {
                    List<WireMockServer> servers = classStore.getServers();
                    if (servers != null && !servers.isEmpty()) {
                        ports = new java.util.ArrayList<>();
                        for (WireMockServer server : servers) {
                            if (server != null) {
                                ports.add(server.port());
                            }
                        }
                    }
                }

                if (ports != null && !ports.isEmpty()) {
                    for (int i = 0; i < ports.size(); i++) {
                        String portProp = StableMockConfig.PORT_PROPERTY + "." + i;
                        String urlProp = StableMockConfig.BASE_URL_PROPERTY + "." + i;
                        String urlValue = "http://localhost:" + ports.get(i);
                        System.setProperty(portProp, String.valueOf(ports.get(i)));
                        System.setProperty(urlProp, urlValue);
                    }
                } else {
                    logger.warn("No ports found for multiple URLs in beforeEach");
                }
            }
            return;
        }

        String mode = StableMockConfig.getMode();
        String testClassName = TestContextResolver.getTestClassName(context);
        String testMethodName = TestContextResolver.getTestMethodName(context);
        File testResourcesDir = TestContextResolver.findTestResourcesDirectory(context);
        File mappingsDir = new File(testResourcesDir, "stablemock/" + testClassName + "/" + testMethodName);

        if (annotations.length > 1 && StableMockConfig.isRecordMode()) {
            List<WireMockServerManager.AnnotationInfo> annotationInfos = new ArrayList<>();
            for (int i = 0; i < annotations.length; i++) {
                annotationInfos.add(new WireMockServerManager.AnnotationInfo(i, annotations[i].urls()));
            }

            List<WireMockServer> servers = new java.util.ArrayList<>();
            List<Integer> ports = new java.util.ArrayList<>();

            for (int i = 0; i < annotationInfos.size(); i++) {
                WireMockServerManager.AnnotationInfo info = annotationInfos.get(i);
                if (info.urls.length > 0) {
                    int port = WireMockServerManager.findFreePort();
                    File annotationMappingsDir = new File(mappingsDir, "annotation_" + i);
                    WireMockServer server = WireMockServerManager.startRecording(port, annotationMappingsDir,
                            Arrays.asList(info.urls));
                    servers.add(server);
                    ports.add(port);
                }
            }

            methodStore.putAnnotationInfos(annotationInfos);
            methodStore.putServer(servers.get(0));
            methodStore.putPort(ports.get(0));
            methodStore.putMode(mode);
            methodStore.putMappingsDir(mappingsDir);
            methodStore.putTargetUrl(annotationInfos.get(0).urls[0]);
            methodStore.putUseClassLevelServer(false);

            String baseUrl = "http://localhost:" + ports.get(0);
            WireMockContext.setBaseUrl(baseUrl);
            WireMockContext.setPort(ports.get(0));
            System.setProperty(StableMockConfig.BASE_URL_PROPERTY, baseUrl);
            System.setProperty(StableMockConfig.PORT_PROPERTY, String.valueOf(ports.get(0)));

            for (int i = 0; i < ports.size(); i++) {
                System.setProperty(StableMockConfig.PORT_PROPERTY + "." + i, String.valueOf(ports.get(i)));
                System.setProperty(StableMockConfig.BASE_URL_PROPERTY + "." + i, "http://localhost:" + ports.get(i));
            }
        } else {
            int port = WireMockServerManager.findFreePort();
            WireMockServer wireMockServer;

            if (StableMockConfig.isRecordMode()) {
                wireMockServer = WireMockServerManager.startRecording(port, mappingsDir, allUrls);
            } else {
                MappingStorage.mergePerTestMethodMappings(mappingsDir);
                wireMockServer = WireMockServerManager.startPlayback(port, mappingsDir, 
                        testResourcesDir, testClassName, testMethodName);
            }

            methodStore.putServer(wireMockServer);
            methodStore.putPort(port);
            methodStore.putMode(mode);
            methodStore.putMappingsDir(mappingsDir);
            methodStore.putTargetUrl(allUrls.get(0));
            methodStore.putUseClassLevelServer(false);

            String baseUrl = "http://localhost:" + port;
            WireMockContext.setBaseUrl(baseUrl);
            WireMockContext.setPort(port);
            System.setProperty(StableMockConfig.PORT_PROPERTY, String.valueOf(port));
            System.setProperty(StableMockConfig.BASE_URL_PROPERTY, baseUrl);
        }
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        ExtensionContextManager.MethodLevelStore methodStore = new ExtensionContextManager.MethodLevelStore(context);
        Boolean useClassLevelServer = methodStore.getUseClassLevelServer();

        if (useClassLevelServer != null && useClassLevelServer) {
            File mappingsDir = methodStore.getMappingsDir();
            String targetUrl = methodStore.getTargetUrl();

            if (StableMockConfig.isRecordMode() && mappingsDir != null && targetUrl != null) {
                ExtensionContextManager.ClassLevelStore classStore = new ExtensionContextManager.ClassLevelStore(
                        context);
                WireMockServer server = classStore.getServer();
                if (server != null) {
                    Integer existingRequestCount = methodStore.getExistingRequestCount();
                    if (existingRequestCount == null) {
                        existingRequestCount = 0;
                    }

                    String testClassName = TestContextResolver.getTestClassName(context);
                    File testResourcesDir = TestContextResolver.findTestResourcesDirectory(context);
                    File baseMappingsDir = new File(testResourcesDir, "stablemock/" + testClassName);

                    List<WireMockServerManager.AnnotationInfo> annotationInfos = methodStore
                            .getAnnotationInfos();
                    List<com.github.tomakehurst.wiremock.stubbing.ServeEvent> serveEvents = server
                            .getAllServeEvents();
                    if (annotationInfos != null && annotationInfos.size() > 1) {
                        if (!serveEvents.isEmpty() && serveEvents.size() > existingRequestCount) {
                            List<WireMockServer> allServers = classStore.getServers();
                            MappingStorage.saveMappingsForTestMethodMultipleAnnotations(server, mappingsDir,
                                    baseMappingsDir, annotationInfos, existingRequestCount, allServers);

                            // Track requests and run detection for multiple annotations/URLs
                            // Pass allServers so requests can be tracked per server/URL
                            performDynamicFieldDetectionWithServers(context, server, serveEvents, existingRequestCount,
                                    testResourcesDir, testClassName, annotationInfos, allServers);
                        }
                    } else {
                        if (!serveEvents.isEmpty() && serveEvents.size() > existingRequestCount) {
                            MappingStorage.saveMappingsForTestMethod(server, mappingsDir, baseMappingsDir, targetUrl,
                                    existingRequestCount);

                            // Track requests and run detection for single annotation
                            performDynamicFieldDetection(context, server, serveEvents, existingRequestCount,
                                    testResourcesDir, testClassName, null);
                        }
                    }
                }
            }
        } else {
            WireMockServer wireMockServer = methodStore.getServer();
            File mappingsDir = methodStore.getMappingsDir();
            String targetUrl = methodStore.getTargetUrl();

            if (wireMockServer != null) {
                if (StableMockConfig.isRecordMode()) {
                    List<WireMockServerManager.AnnotationInfo> annotationInfos = methodStore
                            .getAnnotationInfos();
                    if (annotationInfos != null && annotationInfos.size() > 1) {
                        String testClassName = TestContextResolver.getTestClassName(context);
                        File testResourcesDir = TestContextResolver.findTestResourcesDirectory(context);
                        File baseMappingsDir = new File(testResourcesDir, "stablemock/" + testClassName);
                        // For method-level servers, we only have one server, so pass null
                        MappingStorage.saveMappingsForTestMethodMultipleAnnotations(wireMockServer, mappingsDir,
                                baseMappingsDir, annotationInfos, 0, null);

                        // Track requests and run detection for multiple annotations
                        // For method-level, we only have one server, so pass null for allServers
                        List<com.github.tomakehurst.wiremock.stubbing.ServeEvent> serveEvents = wireMockServer
                                .getAllServeEvents();
                        performDynamicFieldDetectionWithServers(context, wireMockServer, serveEvents, 0,
                                testResourcesDir, testClassName, annotationInfos, null);
                    } else {
                        MappingStorage.saveMappings(wireMockServer, mappingsDir, targetUrl);

                        // Track requests and run detection for single annotation
                        String testClassName = TestContextResolver.getTestClassName(context);
                        File testResourcesDir = TestContextResolver.findTestResourcesDirectory(context);
                        List<com.github.tomakehurst.wiremock.stubbing.ServeEvent> serveEvents = wireMockServer
                                .getAllServeEvents();
                        performDynamicFieldDetection(context, wireMockServer, serveEvents, 0,
                                testResourcesDir, testClassName, null);
                    }
                }
                wireMockServer.stop();
            }
        }

        WireMockContext.clear();
    }

    @Override
    public void afterAll(ExtensionContext context) {
        ExtensionContextManager.ClassLevelStore classStore = new ExtensionContextManager.ClassLevelStore(context);
        List<WireMockServer> servers = classStore.getServers();

        if (servers != null && !servers.isEmpty()) {
            for (int i = 0; i < servers.size(); i++) {
                WireMockServer server = servers.get(i);
                if (server != null) {
                    server.stop();
                }
                System.clearProperty(StableMockConfig.PORT_PROPERTY + "." + i);
                System.clearProperty(StableMockConfig.BASE_URL_PROPERTY + "." + i);
            }
            classStore.putServers(null);
            classStore.putPorts(null);
        }

        WireMockServer server = classStore.getServer();
        if (server != null) {
            server.stop();
            classStore.removeServer();
            classStore.removePort();
        }

        if (StableMockConfig.isRecordMode() || StableMockConfig.isPlaybackMode()) {
            String testClassName = TestContextResolver.getTestClassName(context);
            File testResourcesDir = TestContextResolver.findTestResourcesDirectory(context);
            File baseMappingsDir = new File(testResourcesDir, "stablemock/" + testClassName);
            MappingStorage.cleanupClassLevelDirectory(baseMappingsDir);

            // Clean up temporary url_X directories used for playback
            File[] urlDirs = baseMappingsDir.listFiles(file -> file.isDirectory() && file.getName().startsWith("url_"));
            if (urlDirs != null) {
                for (File urlDir : urlDirs) {
                    try (Stream<Path> stream = java.nio.file.Files
                            .walk(urlDir.toPath())) {
                        stream.sorted(Comparator.reverseOrder())
                                .map(java.nio.file.Path::toFile)
                                .forEach(file -> {
                                    if (!file.delete()) {
                                        logger.warn("Failed to delete file: {}", file.getPath());
                                    }
                                });
                    } catch (Exception e) {
                        logger.error("Failed to clean up {}: {}", urlDir.getName(), e.getMessage());
                    }
                }
            }
        }

        System.clearProperty(StableMockConfig.PORT_PROPERTY);
        System.clearProperty(StableMockConfig.BASE_URL_PROPERTY);
        WireMockContext.clear();
    }

    /**
     * Performs dynamic field detection by delegating to the orchestrator.
     * 
     * @param context              JUnit extension context
     * @param server               WireMock server with serve events
     * @param serveEvents          List of serve events (unused, orchestrator uses
     *                             server.getAllServeEvents())
     * @param existingRequestCount Number of requests that existed before this test
     * @param testResourcesDir     Test resources directory
     * @param testClassName        Test class name
     * @param annotationInfos      Annotation infos for multiple annotation support
     */
    private void performDynamicFieldDetection(ExtensionContext context,
            WireMockServer server,
            List<ServeEvent> serveEvents,
            Integer existingRequestCount,
            File testResourcesDir,
            String testClassName,
            List<WireMockServerManager.AnnotationInfo> annotationInfos) {

        // Get test method name from context
        String testMethodName = TestContextResolver.getTestMethodName(context);

        // Delegate to orchestrator - all logic moved there for better separation of
        // concerns
        DynamicFieldAnalysisOrchestrator.analyzeAndPersist(
                server, existingRequestCount, testResourcesDir, testClassName, testMethodName, annotationInfos, null);
    }
    
    /**
     * Performs dynamic field detection with explicit server list (for multiple URLs/annotations).
     */
    private void performDynamicFieldDetectionWithServers(ExtensionContext context,
            WireMockServer server,
            List<ServeEvent> serveEvents,
            Integer existingRequestCount,
            File testResourcesDir,
            String testClassName,
            List<WireMockServerManager.AnnotationInfo> annotationInfos,
            List<WireMockServer> allServers) {

        // Get test method name from context
        String testMethodName = TestContextResolver.getTestMethodName(context);

        // Delegate to orchestrator with all servers
        DynamicFieldAnalysisOrchestrator.analyzeAndPersist(
                server, existingRequestCount, testResourcesDir, testClassName, testMethodName, annotationInfos, allServers);
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        Class<?> parameterType = parameterContext.getParameter().getType();
        return parameterType == int.class || parameterType == Integer.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        Class<?> parameterType = parameterContext.getParameter().getType();
        if (parameterType == int.class || parameterType == Integer.class) {
            ExtensionContextManager.MethodLevelStore methodStore = new ExtensionContextManager.MethodLevelStore(
                    extensionContext);
            Integer port = methodStore.getPort();
            return port != null ? port : 0;
        }
        return null;
    }
}
