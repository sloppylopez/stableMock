package com.stablemock.gradle;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.github.tomakehurst.wiremock.recording.RecordSpecBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.stablemock.core.config.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;

/**
 * Manages WireMock server lifecycle for Gradle tasks.
 */
public class WireMockServerManager {
    private static final Logger logger = LoggerFactory.getLogger(WireMockServerManager.class);
    
    private WireMockServer wireMockServer;
    private int port;
    private String mode;
    private String mappingsDir;
    private String targetUrl; // Stored as comma-separated string for display

    /**
     * Find a free port on the system.
     */
    public static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Failed to find free port", e);
        }
    }

    /**
     * Start WireMock server in playback mode.
     */
    public void startPlayback(String mappingsDirPath, Integer configuredPort) {
        this.mappingsDir = mappingsDirPath;
        this.mode = Constants.MODE_PLAYBACK;
        this.port = configuredPort != null ? configuredPort : findFreePort();

        File mappingDir = new File(mappingsDirPath);
        if (!mappingDir.exists() && !mappingDir.mkdirs()) {
            throw new RuntimeException("Failed to create mappings directory: " + mappingDir.getAbsolutePath());
        }

        WireMockConfiguration config = WireMockConfiguration.wireMockConfig()
                .port(port)
                .notifier(new com.github.tomakehurst.wiremock.common.ConsoleNotifier(false))
                .usingFilesUnderDirectory(mappingDir.getAbsolutePath());

        wireMockServer = new WireMockServer(config);
        wireMockServer.start();

        logger.info("WireMock started in PLAYBACK mode on port: {}, mappings directory: {}", port, mappingDir.getAbsolutePath());
    }

    /**
     * Start WireMock server in recording mode.
     * Supports multiple target URLs - configures proxy stubs for each.
     */
    public void startRecording(String mappingsDirPath, List<String> targetUrls, Integer configuredPort) {
        this.mappingsDir = mappingsDirPath;
        this.targetUrl = String.join(", ", targetUrls);
        this.mode = Constants.MODE_RECORD;
        this.port = configuredPort != null ? configuredPort : findFreePort();

        if (targetUrls.isEmpty()) {
            throw new IllegalArgumentException("At least one targetUrl must be provided for recording mode");
        }

        File mappingDir = new File(mappingsDirPath);
        if (!mappingDir.exists() && !mappingDir.mkdirs()) {
            throw new RuntimeException("Failed to create mappings directory: " + mappingDir.getAbsolutePath());
        }

        // WireMock 3.x requires __files and mappings directories to exist before recording starts
        File mappingsSubDir = new File(mappingDir, "mappings");
        File filesSubDir = new File(mappingDir, "__files");
        if (!mappingsSubDir.exists() && !mappingsSubDir.mkdirs()) {
            throw new RuntimeException("Failed to create mappings subdirectory: " + mappingsSubDir.getAbsolutePath());
        }
        if (!filesSubDir.exists() && !filesSubDir.mkdirs()) {
            throw new RuntimeException("Failed to create __files subdirectory: " + filesSubDir.getAbsolutePath());
        }

        WireMockConfiguration config = WireMockConfiguration.wireMockConfig()
                .port(port)
                .notifier(new com.github.tomakehurst.wiremock.common.ConsoleNotifier(false))
                .usingFilesUnderDirectory(mappingDir.getAbsolutePath());

        wireMockServer = new WireMockServer(config);
        wireMockServer.start();

        // Configure catch-all proxy stub
        String primaryUrl = targetUrls.get(0);
        wireMockServer.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock.any(
                        com.github.tomakehurst.wiremock.client.WireMock.anyUrl()).willReturn(
                                com.github.tomakehurst.wiremock.client.WireMock.aResponse()                                        .proxiedFrom(primaryUrl)));

        String baseUrl = System.getProperty("stablemock.baseUrl");
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = "http://localhost:" + port;
        }
        logger.info("WireMock started in RECORD mode on port: {}, proxying from {} -> {}, mappings will be saved to: {}", 
                port, baseUrl, primaryUrl, mappingDir.getAbsolutePath());
    }

    /**
     * Stop WireMock server and save mappings if in recording mode.
     */
    public void stop() {
        if (wireMockServer == null) {
            return;
        }

        if (Constants.MODE_RECORD.equals(mode)) {
            try {
                saveMappings();
            } catch (Exception e) {
                logger.error("Error saving mappings: {}", e.getMessage(), e);
            }
        }

        try {
            wireMockServer.stop();
            logger.info("WireMock stopped on port: {}", port);
        } catch (Exception e) {
            logger.error("Error stopping WireMock server: {}", e.getMessage(), e);
        } finally {
            wireMockServer = null;
        }
    }

    /**
     * Save recorded mappings to files.
     */
    private void saveMappings() throws IOException {
        File mappingDir = new File(mappingsDir);
        File mappingsSubDir = new File(mappingDir, "mappings");
        File filesSubDir = new File(mappingDir, "__files");

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

        logger.info("Saved {} mappings to {}", mappings.size(), mappingsSubDir.getAbsolutePath());
    }

    /**
     * Get current port.
     */
    public int getPort() {
        return port;
    }
}
