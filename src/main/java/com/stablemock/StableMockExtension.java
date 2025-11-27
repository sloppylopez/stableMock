package com.stablemock;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.stablemock.core.config.StableMockConfig;
import com.stablemock.core.context.ExtensionContextManager;
import com.stablemock.core.resolver.TestContextResolver;
import com.stablemock.core.server.WireMockServerManager;
import com.stablemock.core.storage.MappingStorage;
import org.junit.jupiter.api.extension.*;

import java.io.File;
import java.util.Arrays;

/**
 * JUnit 5 extension for StableMock that handles WireMock lifecycle per test.
 * Supports both RECORD and PLAYBACK modes with parallel execution.
 */
public class StableMockExtension implements BeforeAllCallback, BeforeEachCallback, AfterEachCallback, AfterAllCallback, ParameterResolver {

    @Override
    public void beforeAll(ExtensionContext context) {
        U annotation = TestContextResolver.findUAnnotation(context);
        if (annotation == null) {
            return;
        }

        String[] urls = annotation.urls();
        if (urls.length == 0) {
            return;
        }

        if (!TestContextResolver.isSpringBootTest(context)) {
            return;
        }

        String mode = StableMockConfig.getMode();
        String testClassName = TestContextResolver.getTestClassName(context);
        File testResourcesDir = TestContextResolver.findTestResourcesDirectory(context);
        File baseMappingsDir = new File(testResourcesDir, "stablemock/" + testClassName);
        
        ExtensionContextManager.ClassLevelStore classStore = new ExtensionContextManager.ClassLevelStore(context);
        int port = WireMockServerManager.findFreePort();
        WireMockServer server;
        
        if (StableMockConfig.isRecordMode()) {
            server = WireMockServerManager.startRecording(port, baseMappingsDir, Arrays.asList(urls));
        } else {
            MappingStorage.mergePerTestMethodMappings(baseMappingsDir);
            WireMockConfiguration config = WireMockConfiguration.wireMockConfig()
                    .port(port)
                    .notifier(new com.github.tomakehurst.wiremock.common.ConsoleNotifier(false))
                    .usingFilesUnderDirectory(baseMappingsDir.getAbsolutePath());
            server = new WireMockServer(config);
            server.start();
        }
        
        classStore.putServer(server);
        classStore.putPort(port);
        classStore.putMode(mode);
        classStore.putTargetUrl(urls[0]);
        
        String baseUrl = "http://localhost:" + port;
        WireMockContext.setBaseUrl(baseUrl);
        WireMockContext.setPort(port);
        System.setProperty(StableMockConfig.BASE_URL_PROPERTY, baseUrl);
        System.setProperty(StableMockConfig.PORT_PROPERTY, String.valueOf(port));
        
        System.out.println("StableMock: Started shared WireMock server in beforeAll for " + testClassName + " on port " + port);
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        U annotation = TestContextResolver.findUAnnotation(context);
        if (annotation == null) {
            return;
        }

        String[] urls = annotation.urls();
        if (urls.length == 0) {
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
            
            System.out.println("StableMock: Using shared server on port " + port + " for test method " + testMethodName);
            return;
        }

        String mode = StableMockConfig.getMode();
        String testClassName = TestContextResolver.getTestClassName(context);
        String testMethodName = TestContextResolver.getTestMethodName(context);
        File testResourcesDir = TestContextResolver.findTestResourcesDirectory(context);
        File mappingsDir = new File(testResourcesDir, "stablemock/" + testClassName + "/" + testMethodName);
        
        System.out.println("StableMock: Mappings directory: " + mappingsDir.getAbsolutePath());

        int port = WireMockServerManager.findFreePort();
        WireMockServer wireMockServer;

        if (StableMockConfig.isRecordMode()) {
            wireMockServer = WireMockServerManager.startRecording(port, mappingsDir, Arrays.asList(urls));
        } else {
            wireMockServer = WireMockServerManager.startPlayback(port, mappingsDir);
        }

        methodStore.putServer(wireMockServer);
        methodStore.putPort(port);
        methodStore.putMode(mode);
        methodStore.putMappingsDir(mappingsDir);
        methodStore.putTargetUrl(urls[0]);
        methodStore.putUseClassLevelServer(false);

        String baseUrl = "http://localhost:" + port;
        WireMockContext.setBaseUrl(baseUrl);
        WireMockContext.setPort(port);
        System.setProperty(StableMockConfig.PORT_PROPERTY, String.valueOf(port));
        System.setProperty(StableMockConfig.BASE_URL_PROPERTY, baseUrl);
        
        System.out.println("StableMock: Started WireMock on port " + port + " for test method " + testMethodName);
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        ExtensionContextManager.MethodLevelStore methodStore = new ExtensionContextManager.MethodLevelStore(context);
        Boolean useClassLevelServer = methodStore.getUseClassLevelServer();
        
        if (useClassLevelServer != null && useClassLevelServer) {
            File mappingsDir = methodStore.getMappingsDir();
            String targetUrl = methodStore.getTargetUrl();
            
            if (StableMockConfig.isRecordMode() && mappingsDir != null && targetUrl != null) {
                ExtensionContextManager.ClassLevelStore classStore = new ExtensionContextManager.ClassLevelStore(context);
                WireMockServer server = classStore.getServer();
                if (server != null) {
                    Integer existingRequestCount = methodStore.getExistingRequestCount();
                    if (existingRequestCount == null) {
                        existingRequestCount = 0;
                    }
                    
                    String testClassName = TestContextResolver.getTestClassName(context);
                    File testResourcesDir = TestContextResolver.findTestResourcesDirectory(context);
                    File baseMappingsDir = new File(testResourcesDir, "stablemock/" + testClassName);
                    
                    java.util.List<com.github.tomakehurst.wiremock.stubbing.ServeEvent> serveEvents = server.getAllServeEvents();
                    if (serveEvents.isEmpty() || serveEvents.size() <= existingRequestCount) {
                        System.out.println("StableMock: No new requests recorded for this test method, skipping mapping save");
                    } else {
                        MappingStorage.saveMappingsForTestMethod(server, mappingsDir, baseMappingsDir, targetUrl, existingRequestCount);
                    }
                }
            }
        } else {
            WireMockServer wireMockServer = methodStore.getServer();
            File mappingsDir = methodStore.getMappingsDir();
            String targetUrl = methodStore.getTargetUrl();

            if (wireMockServer != null) {
                if (StableMockConfig.isRecordMode()) {
                    MappingStorage.saveMappings(wireMockServer, mappingsDir, targetUrl);
                }
                wireMockServer.stop();
            }
        }

        WireMockContext.clear();
    }
    
    @Override
    public void afterAll(ExtensionContext context) {
        ExtensionContextManager.ClassLevelStore classStore = new ExtensionContextManager.ClassLevelStore(context);
        WireMockServer server = classStore.getServer();
        if (server != null) {
            server.stop();
            classStore.removeServer();
            classStore.removePort();
            System.out.println("StableMock: Stopped class-level WireMock in afterAll");
            
            if (StableMockConfig.isRecordMode() || StableMockConfig.isPlaybackMode()) {
                String testClassName = TestContextResolver.getTestClassName(context);
                File testResourcesDir = TestContextResolver.findTestResourcesDirectory(context);
                File baseMappingsDir = new File(testResourcesDir, "stablemock/" + testClassName);
                MappingStorage.cleanupClassLevelDirectory(baseMappingsDir);
            }
        }
        
        System.clearProperty(StableMockConfig.PORT_PROPERTY);
        System.clearProperty(StableMockConfig.BASE_URL_PROPERTY);
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
            ExtensionContextManager.MethodLevelStore methodStore = new ExtensionContextManager.MethodLevelStore(extensionContext);
            Integer port = methodStore.getPort();
            return port != null ? port : 0;
        }
        return null;
    }
}
