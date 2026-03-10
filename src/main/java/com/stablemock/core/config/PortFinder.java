package com.stablemock.core.config;

import java.io.IOException;
import java.net.BindException;
import java.net.ServerSocket;
import java.util.concurrent.TimeoutException;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;


/**
 * Utility for finding free ports.
 */
public final class PortFinder {

    private static final RandomGenerator random = RandomGeneratorFactory.of("XSHROB128").create();
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
                if (attempt > 0) {
                    sleepWithJitter();
                }
                return port;
            } catch (IOException e) {
                if (attempt < MAX_RETRIES - 1) {
                    sleepWithJitter();
                    continue;
                }
                throw new RuntimeException("Failed to find free port after " + MAX_RETRIES + " attempts", e);
            }
        }
        throw new RuntimeException("Failed to find free port after " + MAX_RETRIES + " attempts");
    }

    private static void sleepWithJitter() {
        long delay = (long) RETRY_DELAY_MS + random.nextInt(RETRY_DELAY_MS);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while finding free port", ex);
        }
    }
}
