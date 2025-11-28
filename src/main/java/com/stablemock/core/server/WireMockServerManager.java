package com.stablemock.core.server;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.stablemock.core.config.PortFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages WireMock server lifecycle and configuration.
 */
public final class WireMockServerManager {
    
    private static final Logger logger = LoggerFactory.getLogger(WireMockServerManager.class);
    
    private WireMockServerManager() {
        // utility class
    }
    
    public static WireMockServer startRecording(int port, File mappingsDir, List<String> targetUrls) {
        if (targetUrls.isEmpty()) {
            throw new IllegalArgumentException("At least one targetUrl must be provided for recording mode");
        }

        if (!mappingsDir.exists() && !mappingsDir.mkdirs()) {
            throw new RuntimeException("Failed to create mappings directory: " + mappingsDir.getAbsolutePath());
        }

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

        String primaryUrl = targetUrls.get(0);
        server.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock.any(
                                com.github.tomakehurst.wiremock.client.WireMock.anyUrl())
                        .willReturn(
                                com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                                        .proxiedFrom(primaryUrl)));

        logger.info("Recording mode on port {}, proxying to {}", port, primaryUrl);
        return server;
    }
    
    public static List<WireMockServer> startRecordingMultipleAnnotationsSeparateServers(
            File mappingsDir, List<AnnotationInfo> annotationInfos) {
        List<WireMockServer> servers = new ArrayList<>();
        List<Integer> ports = new ArrayList<>();
        
        for (int i = 0; i < annotationInfos.size(); i++) {
            AnnotationInfo info = annotationInfos.get(i);
            if (info.urls.length > 0) {
                int port = findFreePort();
                File annotationMappingsDir = new File(mappingsDir, "annotation_" + i);
                WireMockServer server = startRecording(port, annotationMappingsDir, Arrays.asList(info.urls));
                servers.add(server);
                ports.add(port);
            }
        }
        
        return servers;
    }
    
    public static WireMockServer startRecordingMultipleAnnotations(int port, File mappingsDir, 
            List<AnnotationInfo> annotationInfos) {
        if (annotationInfos.isEmpty()) {
            throw new IllegalArgumentException("At least one annotation must be provided for recording mode");
        }

        if (!mappingsDir.exists() && !mappingsDir.mkdirs()) {
            throw new RuntimeException("Failed to create mappings directory: " + mappingsDir.getAbsolutePath());
        }

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

        List<String> allTargetUrls = new ArrayList<>();
        Map<String, String> hostToTargetUrl = new HashMap<>();
        Map<String, Integer> hostToAnnotationIndex = new HashMap<>();
        
        for (int i = 0; i < annotationInfos.size(); i++) {
            AnnotationInfo info = annotationInfos.get(i);
            for (String url : info.urls) {
                try {
                    URL parsedUrl = new URL(url);
                    String host = parsedUrl.getHost();
                    if (!hostToTargetUrl.containsKey(host)) {
                        hostToTargetUrl.put(host, url);
                        hostToAnnotationIndex.put(host, i);
                        allTargetUrls.add(url);
                    }
                } catch (Exception e) {
                    logger.error("Failed to parse URL {}: {}", url, e.getMessage());
                }
            }
        }
        
        if (allTargetUrls.isEmpty()) {
            throw new IllegalArgumentException("No valid URLs found in annotations");
        }
        
        String primaryUrl = allTargetUrls.get(0);
        
        server.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock.any(
                                com.github.tomakehurst.wiremock.client.WireMock.anyUrl())
                        .willReturn(
                                com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                                        .proxiedFrom(primaryUrl)));
        
        logger.info("Recording mode on port {}, proxying to {} target(s), primary: {}", port, allTargetUrls.size(), primaryUrl);
        return server;
    }
    
    public static class AnnotationInfo {
        public final int index;
        public final String[] urls;
        
        public AnnotationInfo(int index, String[] urls) {
            this.index = index;
            this.urls = urls;
        }
    }
    
    public static WireMockServer startPlayback(int port, File mappingsDir) {
        if (!mappingsDir.exists() && !mappingsDir.mkdirs()) {
            logger.warn("Mappings directory does not exist: {}", mappingsDir.getAbsolutePath());
        }

        WireMockConfiguration config = WireMockConfiguration.wireMockConfig()
                .port(port)
                .notifier(new com.github.tomakehurst.wiremock.common.ConsoleNotifier(false))
                .usingFilesUnderDirectory(mappingsDir.getAbsolutePath());

        WireMockServer server = new WireMockServer(config);
        server.start();

        logger.info("Playback mode on port {}, loading mappings from {}", port, mappingsDir.getAbsolutePath());
        return server;
    }
    
    public static int findFreePort() {
        return PortFinder.findFreePort();
    }
}

