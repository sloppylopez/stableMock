package com.stablemock;

/**
 * Thread-local context for WireMock server information.
 * Used to support parallel test execution where each test has its own WireMock instance.
 */
public class WireMockContext {
    private static final ThreadLocal<String> threadLocalBaseUrl = new ThreadLocal<>();
    private static final ThreadLocal<Integer> threadLocalPort = new ThreadLocal<>();

    public static void setBaseUrl(String baseUrl) {
        threadLocalBaseUrl.set(baseUrl);
    }

    public static String getThreadLocalBaseUrl() {
        return threadLocalBaseUrl.get();
    }

    public static void setPort(int port) {
        threadLocalPort.set(port);
    }

    public static Integer getThreadLocalPort() {
        return threadLocalPort.get();
    }

    public static void clear() {
        threadLocalBaseUrl.remove();
        threadLocalPort.remove();
    }
}

