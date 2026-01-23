package com.stablemock.spring;

import com.stablemock.WireMockContext;
import com.stablemock.U;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.lang.annotation.Annotation;

/**
 * Base class for Spring Boot tests using StableMock.
 * Provides common functionality for configuring dynamic properties that read
 * from WireMockContext.
 * 
 * WHY @DynamicPropertySource IS REQUIRED:
 * - WireMock ports are DYNAMIC (random free port chosen at runtime)
 * - Tests run in PARALLEL (each test thread gets its own port)
 * - The actual port is only known after WireMock starts in beforeAll/beforeEach
 * - You CANNOT hardcode the port in application.properties because it's
 * different each run
 * 
 * Profile approach (application-stablemock.properties) doesn't work because:
 * - Port is chosen dynamically: WireMockServerManager.findFreePort()
 * - Each parallel test gets a different port (e.g., Test1: 54321, Test2: 61234)
 * - Profile properties are static and loaded before tests run
 * 
 * @DynamicPropertySource is evaluated LAZILY (when Spring needs the value),
 *                        so it can read the ThreadLocal value set by
 *                        StableMockExtension after WireMock starts.
 */
public abstract class BaseStableMockTest {

    /**
     * Gets the ThreadLocal base URL from WireMockContext (for single URL tests).
     */
    protected static String getThreadLocalBaseUrl() {
        return WireMockContext.getThreadLocalBaseUrl();
    }

    /**
     * Gets the ThreadLocal base URL by index from WireMockContext (for multiple URL
     * tests).
     */
    protected static String getThreadLocalBaseUrlByIndex(int index) {
        return WireMockContext.getThreadLocalBaseUrl(index);
    }

    /**
     * Registers a dynamic property with the standard fallback chain.
     * 
     * REQUIRED when using StableMock because WireMock ports are dynamic and chosen
     * at runtime.
     * The port is only known after WireMock starts, so we must read it from
     * ThreadLocal.
     * 
     * Fallback chain:
     * 1. ThreadLocal base URL (set by StableMockExtension after WireMock starts) -
     * REQUIRED for dynamic port
     * 2. Class-scoped system property: stablemock.baseUrl.<ClassName>
     * 3. Global system property: stablemock.baseUrl
     * 4. Default URL (from application.properties if not provided)
     * 
     * @param registry      The dynamic property registry
     * @param propertyName  The property name to register (e.g.,
     *                      "app.thirdparty.url")
     * @param testClassName The test class name for class-scoped fallback
     * @param defaultUrl    The default URL if all fallbacks fail (usually same as
     *                      application.properties)
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
     * Registers a dynamic property with the standard fallback chain for multiple
     * URLs.
     * 
     * REQUIRED when using StableMock because WireMock ports are dynamic and chosen
     * at runtime.
     * Each URL index gets its own WireMock server with its own dynamic port.
     * 
     * Fallback chain:
     * 1. ThreadLocal base URL by index (set by StableMockExtension after WireMock
     * starts) - REQUIRED for dynamic port
     * 2. Class-scoped system property: stablemock.baseUrl.<ClassName>.<index>
     * 3. Global system property: stablemock.baseUrl.<index>
     * 4. Default URL (from application.properties if not provided)
     * 
     * @param registry      The dynamic property registry
     * @param propertyName  The property name to register (e.g.,
     *                      "app.thirdparty.url")
     * @param testClassName The test class name for class-scoped fallback
     * @param index         The URL index (0, 1, 2, etc.)
     * @param defaultUrl    The default URL if all fallbacks fail (usually same as
     *                      application.properties)
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

    /**
     * Automatically registers dynamic properties based on @U annotations on the
     * test class.
     * This method reads the annotations and maps URLs to property names,
     * eliminating the need
     * to manually register each property in @DynamicPropertySource.
     * 
     * Note: While @DynamicPropertySource methods are repetitive across test
     * classes,
     * Spring requires them to be static methods in the test class itself (not
     * inherited).
     * This helper method reduces the boilerplate by handling the annotation reading
     * logic.
     * 
     * Property mapping rules:
     * - If there is 1 URL and multiple properties, all properties map to that URL.
     * Example: urls = {"https://api.com"}, properties = {"app.api.url",
     * "app.api.backup.url"}
     * - If there are multiple URLs, properties map 1:1 (first property to first
     * URL, etc.).
     * Extra properties beyond URLs map to the last URL.
     * Example: urls = {"https://api1.com", "https://api2.com"}, properties =
     * {"app.api1.url", "app.api2.url", "app.api2.backup.url"}
     * 
     * Usage:
     * 
     * <pre>
     * {
     *     &#64;code
     *     &#64;U(urls = { "https://api1.com", "https://api2.com" }, properties = { "app.api1.url", "app.api2.url" })
     *     &#64;SpringBootTest
     *     class MyTest extends BaseStableMockTest {
     *         @DynamicPropertySource
     *         static void configureProperties(DynamicPropertyRegistry registry) {
     *             autoRegisterProperties(registry, MyTest.class);
     *         }
     *     }
     * }
     * </pre>
     * 
     * @param registry  The dynamic property registry
     * @param testClass The test class (pass YourTestClass.class)
     */
    protected static void autoRegisterProperties(DynamicPropertyRegistry registry, Class<?> testClass) {
        U[] annotations = findAllUAnnotations(testClass);

        if (annotations.length == 0) {
            return; // No @U annotations found
        }

        String testClassName = testClass.getSimpleName();

        // Collect all URLs and properties from all @U annotations
        java.util.List<String> allUrls = new java.util.ArrayList<>();
        java.util.List<java.util.List<String>> urlProperties = new java.util.ArrayList<>();

        for (U annotation : annotations) {
            String[] urls = annotation.urls();
            String[] properties = annotation.properties();

            if (urls != null && urls.length > 0) {
                if (urls.length == 1 && properties != null && properties.length > 1) {
                    // Special case: 1 URL with multiple properties - all properties map to same URL
                    allUrls.add(urls[0]);
                    java.util.List<String> propsForUrl = new java.util.ArrayList<>();
                    for (String prop : properties) {
                        if (prop != null && !prop.isEmpty()) {
                            propsForUrl.add(prop);
                        }
                    }
                    urlProperties.add(propsForUrl);
                } else {
                    // Standard case: 1:1 mapping (or multiple URLs with matching properties)
                    for (int i = 0; i < urls.length; i++) {
                        allUrls.add(urls[i]);
                        java.util.List<String> propsForUrl = new java.util.ArrayList<>();

                        // Map property at index i to URL at index i
                        if (properties != null && i < properties.length && properties[i] != null
                                && !properties[i].isEmpty()) {
                            propsForUrl.add(properties[i]);
                        }

                        // If there are extra properties beyond URLs, map them to the last URL
                        if (i == urls.length - 1 && properties != null && properties.length > urls.length) {
                            for (int j = urls.length; j < properties.length; j++) {
                                if (properties[j] != null && !properties[j].isEmpty()) {
                                    propsForUrl.add(properties[j]);
                                }
                            }
                        }

                        urlProperties.add(propsForUrl);
                    }
                }
            }
        }

        // Register properties for each URL
        for (int i = 0; i < allUrls.size() && i < urlProperties.size(); i++) {
            String defaultUrl = allUrls.get(i);
            java.util.List<String> propertiesForUrl = urlProperties.get(i);

            for (String propertyName : propertiesForUrl) {
                if (propertyName != null && !propertyName.isEmpty()) {
                    if (allUrls.size() == 1) {
                        // Single URL - use single URL method
                        registerPropertyWithFallback(registry, propertyName, testClassName, defaultUrl);
                    } else {
                        // Multiple URLs - use indexed method
                        registerPropertyWithFallbackByIndex(registry, propertyName, testClassName, i, defaultUrl);
                    }
                }
            }
        }
    }

    /**
     * Finds keys @U annotations on the test class, including inherited ones if
     * possible?
     * Note: @U is not @Inherited, so getAnnotationsByType might not find it on
     * superclasses
     * properly if we don't recurse. But TestContextResolver does extra work.
     * 
     * Here we just use standard Java reflection. Users should put @U on the leaf
     * class
     * or we should simply check the specific class passed in.
     */
    private static U[] findAllUAnnotations(Class<?> testClass) {
        // Since @U is repeating, we use getAnnotationsByType which handles the
        // container
        return testClass.getAnnotationsByType(U.class);
    }
}
