package com.stablemock.core.config;

import static com.stablemock.core.config.Constants.MODE_PLAYBACK;
import static com.stablemock.core.config.Constants.MODE_RECORD;

/**
 * Configuration constants and utilities for StableMock.
 */
public final class StableMockConfig {
    
    public static final String MODE_PROPERTY = "stablemock.mode";
    public static final String DEFAULT_MODE = MODE_PLAYBACK;
    public static final String BASE_URL_PROPERTY = "stablemock.baseUrl";
    public static final String PORT_PROPERTY = "stablemock.port";
    public static final String GLOBAL_PROPERTIES_PROPERTY = "stablemock.globalProperties";
    public static final String PROXY_TIMEOUT_MS_PROPERTY = "stablemock.wiremock.proxyTimeoutMs";
    public static final int DEFAULT_PROXY_TIMEOUT_MS = 60000;
    public static final String STARTUP_EXTRA_SLEEP_MS_PROPERTY = "stablemock.wiremock.startupExtraSleepMs";
    public static final int DEFAULT_STARTUP_EXTRA_SLEEP_MS = 500;
    
    private StableMockConfig() {
        // utility class
    }
    
    public static String getMode() {
        return System.getProperty(MODE_PROPERTY, DEFAULT_MODE);
    }
    
    public static boolean isRecordMode() {
        return MODE_RECORD.equalsIgnoreCase(getMode());
    }
    
    public static boolean isPlaybackMode() {
        return MODE_PLAYBACK.equalsIgnoreCase(getMode());
    }

    public static boolean useGlobalProperties() {
        return Boolean.parseBoolean(System.getProperty(GLOBAL_PROPERTIES_PROPERTY, "true"));
    }

    /**
     * Returns the WireMock proxy timeout in milliseconds.
     * Defaults to 60 seconds but can be overridden via system property.
     * Values <= 0 are treated as invalid and the default is used instead.
     */
    public static int getProxyTimeoutMs() {
        int value = Integer.getInteger(PROXY_TIMEOUT_MS_PROPERTY, DEFAULT_PROXY_TIMEOUT_MS);
        return value > 0 ? value : DEFAULT_PROXY_TIMEOUT_MS;
    }

    /**
     * Extra sleep after verifying WireMock stub startup, in milliseconds.
     * Defaults to 500ms (tuned for WSL), but can be reduced or increased
     * via system property depending on environment.
     */
    public static int getStartupExtraSleepMs() {
        return Integer.getInteger(STARTUP_EXTRA_SLEEP_MS_PROPERTY, DEFAULT_STARTUP_EXTRA_SLEEP_MS);
    }
}
