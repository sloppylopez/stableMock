package com.stablemock;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.stablemock.core.analysis.*;
import com.stablemock.core.config.Constants;
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
import java.util.concurrent.locks.ReentrantLock;

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
                    // Collect ignore patterns from all annotations (for class-level, we use all annotations)
                    List<String> annotationIgnorePatterns = new java.util.ArrayList<>();
                    for (U annotation : annotations) {
                        String[] ignore = annotation.ignore();
                        if (ignore != null) {
                            for (String pattern : ignore) {
                                if (pattern != null && !pattern.isEmpty()) {
                                    annotationIgnorePatterns.add(pattern);
                                }
                            }
                        }
                    }
                    server = WireMockServerManager.startPlayback(port, urlMappingsDir, 
                            testResourcesDir, testClassName, null, annotationIgnorePatterns);
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

            String baseUrl = Constants.LOCALHOST_URL_PREFIX + ports.get(0);
            WireMockContext.setBaseUrl(baseUrl);
            WireMockContext.setPort(ports.get(0));
            String[] baseUrls = new String[ports.size()];
            for (int i = 0; i < ports.size(); i++) {
                baseUrls[i] = com.stablemock.core.config.Constants.LOCALHOST_URL_PREFIX + ports.get(i);
            }
            WireMockContext.setBaseUrls(baseUrls);
            if (StableMockConfig.useGlobalProperties()) {
                System.setProperty(StableMockConfig.BASE_URL_PROPERTY, baseUrl);
                System.setProperty(StableMockConfig.PORT_PROPERTY, String.valueOf(ports.get(0)));
            }
            // Class-scoped properties to avoid global races in parallel Spring tests
            System.setProperty(StableMockConfig.BASE_URL_PROPERTY + "." + testClassName, baseUrl);
            System.setProperty(StableMockConfig.PORT_PROPERTY + "." + testClassName, String.valueOf(ports.get(0)));

            for (int i = 0; i < ports.size(); i++) {
                if (StableMockConfig.useGlobalProperties()) {
                    System.setProperty(StableMockConfig.PORT_PROPERTY + "." + i, String.valueOf(ports.get(i)));
                    System.setProperty(StableMockConfig.BASE_URL_PROPERTY + "." + i, com.stablemock.core.config.Constants.LOCALHOST_URL_PREFIX + ports.get(i));
                }
                // Also class-scoped indexed properties for multi-URL tests
                System.setProperty(StableMockConfig.PORT_PROPERTY + "." + testClassName + "." + i, String.valueOf(ports.get(i)));
                System.setProperty(StableMockConfig.BASE_URL_PROPERTY + "." + testClassName + "." + i, com.stablemock.core.config.Constants.LOCALHOST_URL_PREFIX + ports.get(i));
            }

            logger.info("Started {} WireMock server(s) for {} in {} mode", servers.size(), testClassName, mode);
        } else {
            // Single URL - use existing single server logic
            int port = WireMockServerManager.findFreePort();
            WireMockServer server;

            if (isRecordMode) {
                server = WireMockServerManager.startRecording(port, baseMappingsDir, allUrls);
            } else {
                logger.info("=== PLAYBACK MODE: Merging test method mappings for {} ===", testClassName);
                try {
                    MappingStorage.mergePerTestMethodMappings(baseMappingsDir);
                    logger.info("=== Merge completed successfully for {} ===", testClassName);
                } catch (Exception e) {
                    logger.error("=== ERROR: Merge failed for {}: {} ===", testClassName, e.getMessage(), e);
                    throw new RuntimeException("Failed to merge test method mappings for " + testClassName, e);
                }
                
                // Collect ignore patterns from all annotations (for class-level, we use all annotations)
                List<String> annotationIgnorePatterns = new java.util.ArrayList<>();
                for (U annotation : annotations) {
                    String[] ignore = annotation.ignore();
                    if (ignore != null) {
                        for (String pattern : ignore) {
                            if (pattern != null && !pattern.isEmpty()) {
                                annotationIgnorePatterns.add(pattern);
                            }
                        }
                    }
                }
                server = WireMockServerManager.startPlayback(port, baseMappingsDir, 
                        testResourcesDir, testClassName, null, annotationIgnorePatterns);
            }

            classStore.putServer(server);
            classStore.putPort(port);
            classStore.putMode(mode);
            classStore.putTargetUrl(allUrls.get(0));

            String baseUrl = com.stablemock.core.config.Constants.LOCALHOST_URL_PREFIX + port;
            WireMockContext.setBaseUrl(baseUrl);
            WireMockContext.setPort(port);
            if (StableMockConfig.useGlobalProperties()) {
                System.setProperty(StableMockConfig.BASE_URL_PROPERTY, baseUrl);
                System.setProperty(StableMockConfig.PORT_PROPERTY, String.valueOf(port));
            }
            // Class-scoped properties to avoid global races in parallel Spring tests
            System.setProperty(StableMockConfig.BASE_URL_PROPERTY + "." + testClassName, baseUrl);
            System.setProperty(StableMockConfig.PORT_PROPERTY + "." + testClassName, String.valueOf(port));

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
            if (StableMockConfig.isRecordMode()) {
                ReentrantLock lock = classStore.getOrCreateClassLock();
                lock.lock();
                methodStore.putClassLock(lock);
            }

            Integer port = classStore.getPort();
            String baseUrl = com.stablemock.core.config.Constants.LOCALHOST_URL_PREFIX + port;
            WireMockContext.setBaseUrl(baseUrl);
            WireMockContext.setPort(port);
            List<Integer> portsForThreadLocal = classStore.getPorts();
            if (portsForThreadLocal != null && !portsForThreadLocal.isEmpty()) {
                String[] baseUrls = new String[portsForThreadLocal.size()];
                for (int i = 0; i < portsForThreadLocal.size(); i++) {
                    baseUrls[i] = com.stablemock.core.config.Constants.LOCALHOST_URL_PREFIX + portsForThreadLocal.get(i);
                }
                WireMockContext.setBaseUrls(baseUrls);
            }

            String testClassName = TestContextResolver.getTestClassName(context);
            String testMethodName = TestContextResolver.getTestMethodName(context);
            File testResourcesDir = TestContextResolver.findTestResourcesDirectory(context);
            File mappingsDir = new File(testResourcesDir, "stablemock/" + testClassName + "/" + testMethodName);

            int existingRequestCount = classServer.getAllServeEvents().size();
            List<Integer> existingRequestCounts = null;
            List<WireMockServer> classServers = classStore.getServers();
            if (classServers != null && !classServers.isEmpty()) {
                existingRequestCounts = new ArrayList<>();
                for (WireMockServer server : classServers) {
                    existingRequestCounts.add(server != null ? server.getAllServeEvents().size() : 0);
                }
            }

            methodStore.putServer(classServer);
            methodStore.putPort(port);
            methodStore.putMode(classStore.getMode());
            methodStore.putMappingsDir(mappingsDir);
            methodStore.putTargetUrl(classStore.getTargetUrl());
            methodStore.putUseClassLevelServer(true);
            methodStore.putExistingRequestCount(existingRequestCount);
            if (existingRequestCounts != null) {
                methodStore.putExistingRequestCounts(existingRequestCounts);
            }

            // Refresh class-scoped properties (Spring may have evaluated @DynamicPropertySource early)
            System.setProperty(StableMockConfig.BASE_URL_PROPERTY + "." + testClassName, baseUrl);
            System.setProperty(StableMockConfig.PORT_PROPERTY + "." + testClassName, String.valueOf(port));

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
                        String urlValue = com.stablemock.core.config.Constants.LOCALHOST_URL_PREFIX + ports.get(i);
                        if (StableMockConfig.useGlobalProperties()) {
                            System.setProperty(portProp, String.valueOf(ports.get(i)));
                            System.setProperty(urlProp, urlValue);
                        }
                        System.setProperty(StableMockConfig.PORT_PROPERTY + "." + testClassName + "." + i, String.valueOf(ports.get(i)));
                        System.setProperty(StableMockConfig.BASE_URL_PROPERTY + "." + testClassName + "." + i, urlValue);
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
                if (info.urls().length > 0) {
                    int port = WireMockServerManager.findFreePort();
                    File annotationMappingsDir = new File(mappingsDir, "annotation_" + i);
                    WireMockServer server = WireMockServerManager.startRecording(port, annotationMappingsDir,
                            Arrays.asList(info.urls()));
                    servers.add(server);
                    ports.add(port);
                }
            }

            methodStore.putAnnotationInfos(annotationInfos);
            methodStore.putServer(servers.get(0));
            methodStore.putPort(ports.get(0));
            methodStore.putServers(servers);
            methodStore.putPorts(ports);
            methodStore.putMode(mode);
            methodStore.putMappingsDir(mappingsDir);
            methodStore.putTargetUrl(annotationInfos.get(0).urls()[0]);
            methodStore.putUseClassLevelServer(false);

            String baseUrl = com.stablemock.core.config.Constants.LOCALHOST_URL_PREFIX + ports.get(0);
            WireMockContext.setBaseUrl(baseUrl);
            WireMockContext.setPort(ports.get(0));
            String[] baseUrls = new String[ports.size()];
            for (int i = 0; i < ports.size(); i++) {
                baseUrls[i] = com.stablemock.core.config.Constants.LOCALHOST_URL_PREFIX + ports.get(i);
            }
            WireMockContext.setBaseUrls(baseUrls);
            if (StableMockConfig.useGlobalProperties()) {
                System.setProperty(StableMockConfig.BASE_URL_PROPERTY, baseUrl);
                System.setProperty(StableMockConfig.PORT_PROPERTY, String.valueOf(ports.get(0)));
            }

            for (int i = 0; i < ports.size(); i++) {
                if (StableMockConfig.useGlobalProperties()) {
                    System.setProperty(StableMockConfig.PORT_PROPERTY + "." + i, String.valueOf(ports.get(i)));
                    System.setProperty(StableMockConfig.BASE_URL_PROPERTY + "." + i, com.stablemock.core.config.Constants.LOCALHOST_URL_PREFIX + ports.get(i));
                }
            }
        } else {
            int port = WireMockServerManager.findFreePort();
            WireMockServer wireMockServer;

            if (StableMockConfig.isRecordMode()) {
                wireMockServer = WireMockServerManager.startRecording(port, mappingsDir, allUrls);
            } else {
                // mergePerTestMethodMappings expects class-level directory, not method-level
                File classMappingsDir = mappingsDir.getParentFile();
                MappingStorage.mergePerTestMethodMappings(classMappingsDir);
                // Collect ignore patterns from all annotations (for method-level)
                List<String> annotationIgnorePatterns = new java.util.ArrayList<>();
                for (U annotation : annotations) {
                    String[] ignore = annotation.ignore();
                    if (ignore != null) {
                        for (String pattern : ignore) {
                            if (pattern != null && !pattern.isEmpty()) {
                                annotationIgnorePatterns.add(pattern);
                            }
                        }
                    }
                }
                // After merge, mappings are in class-level directory, so use that for playback
                wireMockServer = WireMockServerManager.startPlayback(port, classMappingsDir, 
                        testResourcesDir, testClassName, testMethodName, annotationIgnorePatterns);
            }

            methodStore.putServer(wireMockServer);
            methodStore.putPort(port);
            methodStore.putMode(mode);
            methodStore.putMappingsDir(mappingsDir);
            methodStore.putTargetUrl(allUrls.get(0));
            methodStore.putUseClassLevelServer(false);

            String baseUrl = com.stablemock.core.config.Constants.LOCALHOST_URL_PREFIX + port;
            WireMockContext.setBaseUrl(baseUrl);
            WireMockContext.setPort(port);
            if (StableMockConfig.useGlobalProperties()) {
                System.setProperty(StableMockConfig.PORT_PROPERTY, String.valueOf(port));
                System.setProperty(StableMockConfig.BASE_URL_PROPERTY, baseUrl);
            }
        }
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        ExtensionContextManager.MethodLevelStore methodStore = new ExtensionContextManager.MethodLevelStore(context);
        Boolean useClassLevelServer = methodStore.getUseClassLevelServer();
        ReentrantLock classLock = methodStore.getClassLock();

        try {
            if (useClassLevelServer != null && useClassLevelServer) {
                File mappingsDir = methodStore.getMappingsDir();
                String targetUrl = methodStore.getTargetUrl();

                if (StableMockConfig.isRecordMode() && mappingsDir != null && targetUrl != null) {
                    ExtensionContextManager.ClassLevelStore classStore = new ExtensionContextManager.ClassLevelStore(
                            context);
                    WireMockServer server = classStore.getServer();
                    if (server != null) {
                        Integer existingRequestCount = methodStore.getExistingRequestCount();
                        List<Integer> existingRequestCounts = methodStore.getExistingRequestCounts();
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
                            boolean hasNewEvents = !serveEvents.isEmpty() && serveEvents.size() > existingRequestCount;
                            List<WireMockServer> allServers = classStore.getServers();
                            if (!hasNewEvents && existingRequestCounts != null && allServers != null) {
                                int maxIndex = Math.min(allServers.size(), existingRequestCounts.size());
                                for (int i = 0; i < maxIndex; i++) {
                                    WireMockServer candidateServer = allServers.get(i);
                                    if (candidateServer != null
                                            && candidateServer.getAllServeEvents().size() > existingRequestCounts.get(i)) {
                                        hasNewEvents = true;
                                        break;
                                    }
                                }
                            }

                            if (hasNewEvents) {
                                MappingStorage.saveMappingsForTestMethodMultipleAnnotations(server, mappingsDir,
                                        baseMappingsDir, annotationInfos, existingRequestCount, existingRequestCounts, allServers);

                                // Track requests and run detection for multiple annotations/URLs
                                // Pass allServers so requests can be tracked per server/URL
                                performDynamicFieldDetectionWithServers(context, server, existingRequestCount,
                                        existingRequestCounts, testResourcesDir, testClassName, annotationInfos, allServers);
                            }
                        } else {
                            if (!serveEvents.isEmpty() && serveEvents.size() > existingRequestCount) {
                                // Check if scenario mode is enabled
                                U[] annotations = TestContextResolver.findAllUAnnotations(context);
                                boolean scenario = false;
                                for (U annotation : annotations) {
                                    if (annotation.scenario()) {
                                        scenario = true;
                                        break;
                                    }
                                }
                                
                                MappingStorage.saveMappingsForTestMethod(server, mappingsDir, baseMappingsDir, targetUrl,
                                        existingRequestCount, scenario);

                                // Track requests and run detection for single annotation
                                performDynamicFieldDetection(context, server, existingRequestCount, null,
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
                        List<WireMockServer> allServers = methodStore.getServers();
                        // For method-level servers, use all servers when available
                        MappingStorage.saveMappingsForTestMethodMultipleAnnotations(wireMockServer, mappingsDir,
                                baseMappingsDir, annotationInfos, 0, null, allServers);

                        // Track requests and run detection for multiple annotations
                        performDynamicFieldDetectionWithServers(context, wireMockServer, 0,
                                null, testResourcesDir, testClassName, annotationInfos, allServers);
                    } else {
                        MappingStorage.saveMappings(wireMockServer, mappingsDir, targetUrl);

                            // Track requests and run detection for single annotation
                            String testClassName = TestContextResolver.getTestClassName(context);
                            File testResourcesDir = TestContextResolver.findTestResourcesDirectory(context);
                            performDynamicFieldDetection(context, wireMockServer, 0, null,
                                    testResourcesDir, testClassName, null);
                    }
                }
                List<WireMockServer> allServers = methodStore.getServers();
                if (allServers != null && !allServers.isEmpty()) {
                    for (WireMockServer server : allServers) {
                        if (server != null) {
                            server.stop();
                        }
                    }
                } else {
                    wireMockServer.stop();
                }
                // Give the server a moment to release the port
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        } finally {
            try {
                if (classLock != null && classLock.isHeldByCurrentThread()) {
                    classLock.unlock();
                }
            } finally {
                WireMockContext.clear();
            }
        }
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
                if (StableMockConfig.useGlobalProperties()) {
                    System.clearProperty(StableMockConfig.PORT_PROPERTY + "." + i);
                    System.clearProperty(StableMockConfig.BASE_URL_PROPERTY + "." + i);
                }
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
            
            // Generate recording report after all recordings are complete (only in record mode)
            if (StableMockConfig.isRecordMode()) {
                try {
                    com.fasterxml.jackson.databind.node.ObjectNode report = 
                            com.stablemock.core.reporting.RecordingReportGenerator.generateReport(testResourcesDir, testClassName);
                    if (report != null) {
                        com.stablemock.core.reporting.RecordingReportGenerator.saveReport(report, testResourcesDir);
                    }
                } catch (RuntimeException e) {
                    logger.error("Failed to generate recording report: {}", e.getMessage(), e);
                }
            }
        }

        if (StableMockConfig.useGlobalProperties()) {
            System.clearProperty(StableMockConfig.PORT_PROPERTY);
            System.clearProperty(StableMockConfig.BASE_URL_PROPERTY);
        }
        WireMockContext.clear();
    }

    /**
     * Performs dynamic field detection by delegating to the orchestrator.
     * 
     * @param context              JUnit extension context
     * @param server               WireMock server with serve events
     * @param existingRequestCount Number of requests that existed before this test
     * @param testResourcesDir     Test resources directory
     * @param testClassName        Test class name
     * @param annotationInfos      Annotation infos for multiple annotation support
     */
    private void performDynamicFieldDetection(ExtensionContext context,
            WireMockServer server,
            Integer existingRequestCount,
            List<Integer> existingRequestCounts,
            File testResourcesDir,
            String testClassName,
            List<WireMockServerManager.AnnotationInfo> annotationInfos) {

        // Get test method name from context
        String testMethodName = TestContextResolver.getTestMethodName(context);

        // Delegate to orchestrator - all logic moved there for better separation of
        // concerns. The orchestrator gets serve events directly from the server.
        DynamicFieldAnalysisOrchestrator.analyzeAndPersist(
                server, existingRequestCount, existingRequestCounts, testResourcesDir, testClassName, testMethodName, annotationInfos, null);
    }
    
    /**
     * Performs dynamic field detection with explicit server list (for multiple URLs/annotations).
     */
    private void performDynamicFieldDetectionWithServers(ExtensionContext context,
            WireMockServer server,
            Integer existingRequestCount,
            List<Integer> existingRequestCounts,
            File testResourcesDir,
            String testClassName,
            List<WireMockServerManager.AnnotationInfo> annotationInfos,
            List<WireMockServer> allServers) {

        // Get test method name from context
        String testMethodName = TestContextResolver.getTestMethodName(context);

        // Delegate to orchestrator with all servers. The orchestrator gets serve events directly from the servers.
        DynamicFieldAnalysisOrchestrator.analyzeAndPersist(
                server, existingRequestCount, existingRequestCounts, testResourcesDir, testClassName, testMethodName, annotationInfos, allServers);
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
