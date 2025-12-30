package example;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Base class for Spring Boot tests using StableMock.
 * Provides common functionality for configuring dynamic properties that read from WireMockContext.
 * 
 * WHY @DynamicPropertySource IS REQUIRED:
 * - WireMock ports are DYNAMIC (random free port chosen at runtime)
 * - Tests run in PARALLEL (each test thread gets its own port)
 * - The actual port is only known after WireMock starts in beforeAll/beforeEach
 * - You CANNOT hardcode the port in application.properties because it's different each run
 * 
 * Profile approach (application-stablemock.properties) doesn't work because:
 * - Port is chosen dynamically: WireMockServerManager.findFreePort()
 * - Each parallel test gets a different port (e.g., Test1: 54321, Test2: 61234)
 * - Profile properties are static and loaded before tests run
 * 
 * @DynamicPropertySource is evaluated LAZILY (when Spring needs the value),
 * so it can read the ThreadLocal value set by StableMockExtension after WireMock starts.
 */
public abstract class BaseStableMockTest {

    /**
     * Gets the ThreadLocal base URL from WireMockContext (for single URL tests).
     * Uses reflection to avoid direct dependency on com.stablemock package.
     */
    protected static String getThreadLocalBaseUrl() {
        try {
            Class<?> wireMockContextClass = Class.forName("com.stablemock.WireMockContext");
            java.lang.reflect.Method method = wireMockContextClass.getMethod("getThreadLocalBaseUrl");
            return (String) method.invoke(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Gets the ThreadLocal base URL by index from WireMockContext (for multiple URL tests).
     * Uses reflection to avoid direct dependency on com.stablemock package.
     */
    protected static String getThreadLocalBaseUrlByIndex(int index) {
        try {
            Class<?> wireMockContextClass = Class.forName("com.stablemock.WireMockContext");
            java.lang.reflect.Method method = wireMockContextClass.getMethod("getThreadLocalBaseUrl", int.class);
            return (String) method.invoke(null, index);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Registers a dynamic property with the standard fallback chain.
     * 
     * REQUIRED when using StableMock because WireMock ports are dynamic and chosen at runtime.
     * The port is only known after WireMock starts, so we must read it from ThreadLocal.
     * 
     * Fallback chain:
     * 1. ThreadLocal base URL (set by StableMockExtension after WireMock starts) - REQUIRED for dynamic port
     * 2. Class-scoped system property: stablemock.baseUrl.<ClassName>
     * 3. Global system property: stablemock.baseUrl
     * 4. Default URL (from application.properties if not provided)
     * 
     * @param registry The dynamic property registry
     * @param propertyName The property name to register (e.g., "app.thirdparty.url")
     * @param testClassName The test class name for class-scoped fallback
     * @param defaultUrl The default URL if all fallbacks fail (usually same as application.properties)
     */
    protected static void registerPropertyWithFallback(
            DynamicPropertyRegistry registry,
            String propertyName,
            String testClassName,
            String defaultUrl) {
        registry.add(propertyName, () -> {
            // Only override if StableMock is active (has ThreadLocal value)
            String baseUrl = getThreadLocalBaseUrl();
            if (baseUrl != null && !baseUrl.isEmpty()) {
                return baseUrl; // StableMock is active, use WireMock URL
            }
            // StableMock not active, check system properties as fallback
            baseUrl = System.getProperty("stablemock.baseUrl." + testClassName);
            if (baseUrl == null || baseUrl.isEmpty()) {
                baseUrl = System.getProperty("stablemock.baseUrl");
            }
            // If still no value, return default (which matches application.properties)
            // Spring will use application.properties value if we return null/empty
            return baseUrl != null && !baseUrl.isEmpty() ? baseUrl : defaultUrl;
        });
    }

    /**
     * Registers a dynamic property with the standard fallback chain for multiple URLs.
     * 
     * REQUIRED when using StableMock because WireMock ports are dynamic and chosen at runtime.
     * Each URL index gets its own WireMock server with its own dynamic port.
     * 
     * Fallback chain:
     * 1. ThreadLocal base URL by index (set by StableMockExtension after WireMock starts) - REQUIRED for dynamic port
     * 2. Class-scoped system property: stablemock.baseUrl.<ClassName>.<index>
     * 3. Global system property: stablemock.baseUrl.<index>
     * 4. Default URL (from application.properties if not provided)
     * 
     * @param registry The dynamic property registry
     * @param propertyName The property name to register (e.g., "app.thirdparty.url")
     * @param testClassName The test class name for class-scoped fallback
     * @param index The URL index (0, 1, 2, etc.)
     * @param defaultUrl The default URL if all fallbacks fail (usually same as application.properties)
     */
    protected static void registerPropertyWithFallbackByIndex(
            DynamicPropertyRegistry registry,
            String propertyName,
            String testClassName,
            int index,
            String defaultUrl) {
        registry.add(propertyName, () -> {
            // Only override if StableMock is active (has ThreadLocal value)
            String wireMockUrl = getThreadLocalBaseUrlByIndex(index);
            if (wireMockUrl != null && !wireMockUrl.isEmpty()) {
                return wireMockUrl; // StableMock is active, use WireMock URL
            }
            // StableMock not active, check system properties as fallback
            wireMockUrl = System.getProperty("stablemock.baseUrl." + testClassName + "." + index);
            if (wireMockUrl == null || wireMockUrl.isEmpty()) {
                wireMockUrl = System.getProperty("stablemock.baseUrl." + index);
            }
            // If still no value, return default (which matches application.properties)
            // Spring will use application.properties value if we return null/empty
            return wireMockUrl != null && !wireMockUrl.isEmpty() ? wireMockUrl : defaultUrl;
        });
    }
}
