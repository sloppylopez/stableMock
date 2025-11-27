package com.stablemock.core.config;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * Utility for finding free ports.
 */
public final class PortFinder {
    
    private PortFinder() {
        // utility class
    }
    
    public static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Failed to find free port", e);
        }
    }
}

