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
}

