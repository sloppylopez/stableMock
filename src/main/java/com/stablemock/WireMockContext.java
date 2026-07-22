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
     * Index order must match {@link com.stablemock.StableMockExtension} merged {@code allUrls} /
     * {@code url_0}, {@code url_1}, ... and {@link com.stablemock.spring.BaseStableMockTest#autoRegisterProperties}.
     * Pass {@code null} to clear (single-URL tests after a multi-URL run).
     */
    public static void setBaseUrls(String[] baseUrls) {
        if (baseUrls == null) {
            threadLocalBaseUrls.remove();
        } else {
            threadLocalBaseUrls.set(baseUrls);
        }
    }

    /**
     * Returns the per-index base URL for multi-annotation / multi-URL tests.
     * <ul>
     *   <li>If a {@link #setBaseUrls(String[])} array exists and {@code index} is in range, returns that element.</li>
     *   <li>Index {@code 0} with no array (or empty array) falls back to {@link #getThreadLocalBaseUrl()} for
     *       single-WireMock setups.</li>
     *   <li>Index {@code >= 1} never falls back to the primary URL — returns {@code null} so callers use
     *       class-scoped system properties instead of hitting the wrong WireMock.</li>
     * </ul>
     */
    public static String getThreadLocalBaseUrl(int index) {
        if (index < 0) {
            return null;
        }
        String[] urls = threadLocalBaseUrls.get();
        if (urls != null && index < urls.length) {
            return urls[index];
        }
        if (index == 0) {
            return getThreadLocalBaseUrl();
        }
        return null;
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
