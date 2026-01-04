package com.stablemock.core.config;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Random;

/**
 * Utility for finding free ports.
 */
public final class PortFinder {
    
    private static final Random random = new Random();
    private static final int MAX_RETRIES = 10;
    private static final int RETRY_DELAY_MS = 50;
    
    private PortFinder() {
        // utility class
    }
    
    /**
     * Finds a free port by attempting to bind to it.
     * Uses retry logic with small random delays to reduce race conditions
     * when multiple tests run in parallel.
     * 
     * @return A port number that should be free for use
     */
    public static synchronized int findFreePort() {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try (ServerSocket socket = new ServerSocket(0)) {
                int port = socket.getLocalPort();
                // Add a small random delay to reduce collisions when multiple
                // tests call this method in quick succession
                if (attempt > 0) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS + random.nextInt(RETRY_DELAY_MS));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted while finding free port", ie);
                    }
                }
                return port;
            } catch (IOException e) {
                if (attempt < MAX_RETRIES - 1) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS + random.nextInt(RETRY_DELAY_MS));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted while finding free port", ie);
                    }
                    continue;
                }
                throw new RuntimeException("Failed to find free port after " + MAX_RETRIES + " attempts", e);
            }
        }
        throw new RuntimeException("Failed to find free port after " + MAX_RETRIES + " attempts");
    }
}

