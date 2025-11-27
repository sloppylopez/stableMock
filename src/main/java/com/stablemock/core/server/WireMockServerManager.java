package com.stablemock.core.server;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.stablemock.core.config.PortFinder;

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

        System.out.println("StableMock: Recording mode on port " + port + ", proxying to " + primaryUrl);
        return server;
    }
    
    public static List<WireMockServer> startRecordingMultipleAnnotationsSeparateServers(
            File mappingsDir, List<AnnotationInfo> annotationInfos) {
        List<WireMockServer> servers = new ArrayList<>();
        
        for (int i = 0; i < annotationInfos.size(); i++) {
            AnnotationInfo info = annotationInfos.get(i);
            if (info.urls.length > 0) {
                int port = findFreePort();
                File annotationMappingsDir = new File(mappingsDir, "annotation_" + i);
                WireMockServer server = startRecording(port, annotationMappingsDir, Arrays.asList(info.urls));
                servers.add(server);
                System.out.println("StableMock: Started WireMock server " + i + " on port " + port + " for " + info.urls[0]);
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
        
        for (int i = 0; i < annotationInfos.size(); i++) {
            AnnotationInfo info = annotationInfos.get(i);
            for (String url : info.urls) {
                try {
                    URL parsedUrl = new URL(url);
                    String host = parsedUrl.getHost();
                    if (!hostToTargetUrl.containsKey(host)) {
                        hostToTargetUrl.put(host, url);
                        allTargetUrls.add(url);
                    }
                } catch (Exception e) {
                    System.err.println("StableMock: Failed to parse URL " + url + ": " + e.getMessage());
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
        
        System.out.println("StableMock: Created catch-all proxy stub -> " + primaryUrl);
        if (allTargetUrls.size() > 1) {
            System.out.println("StableMock: Note - All requests will proxy to primary URL (" + primaryUrl + "). " +
                    "Mappings will be matched to annotations when saving based on request patterns.");
        }

        System.out.println("StableMock: Recording mode on port " + port + ", proxying to " + 
                allTargetUrls.size() + " target(s), primary: " + primaryUrl);
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
            System.out.println("StableMock: Warning - mappings directory does not exist: " + mappingsDir.getAbsolutePath());
        }

        WireMockConfiguration config = WireMockConfiguration.wireMockConfig()
                .port(port)
                .notifier(new com.github.tomakehurst.wiremock.common.ConsoleNotifier(false))
                .usingFilesUnderDirectory(mappingsDir.getAbsolutePath());

        WireMockServer server = new WireMockServer(config);
        server.start();

        System.out.println("StableMock: Playback mode on port " + port + ", loading mappings from " + mappingsDir.getAbsolutePath());
        return server;
    }
    
    public static int findFreePort() {
        return PortFinder.findFreePort();
    }
}

