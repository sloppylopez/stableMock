package com.stablemock.core.server;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.stablemock.core.config.PortFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;

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

