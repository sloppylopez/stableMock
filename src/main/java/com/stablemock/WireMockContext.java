package com.stablemock;

/**
 * Thread-local context for WireMock server information.
 * Used to support parallel test execution where each test has its own WireMock
 * instance.
 */
public final class WireMockContext {

    private static final ThreadLocal<String> threadLocalBaseUrl = new ThreadLocal<>();
    private static final ThreadLocal<Integer> threadLocalPort = new ThreadLocal<>();
    private static final ThreadLocal<String[]> threadLocalBaseUrls = new ThreadLocal<>();

    private WireMockContext() {
        // utility class
    }

    public static void setBaseUrl(String baseUrl) {
        threadLocalBaseUrl.set(baseUrl);
    }

    public static void setPort(int port) {
        threadLocalPort.set(port);
    }

    public static String getThreadLocalBaseUrl() {
        return threadLocalBaseUrl.get();
    }

    /**
     * Sets per-index base URLs for multi-annotation / multi-URL tests.
     * Index corresponds to {@code stablemock.baseUrl.<index>}.
     */
    public static void setBaseUrls(String[] baseUrls) {
        threadLocalBaseUrls.set(baseUrls);
    }

    /**
     * Returns the per-index base URL for multi-annotation / multi-URL tests.
     * Falls back to the single base URL if per-index values are not set.
     */
    public static String getThreadLocalBaseUrl(int index) {
        String[] urls = threadLocalBaseUrls.get();
        if (urls == null || index < 0 || index >= urls.length) {
            return getThreadLocalBaseUrl();
        }
        return urls[index];
    }

    public static Integer getThreadLocalPort() {
        return threadLocalPort.get();
    }

    public static void clear() {
        threadLocalBaseUrl.remove();
        threadLocalPort.remove();
        threadLocalBaseUrls.remove();
    }
}
