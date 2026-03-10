package com.stablemock.spring;

import com.stablemock.WireMockContext;
import com.stablemock.U;
import com.stablemock.core.config.StableMockConfig;
import com.stablemock.core.resolver.TestContextResolver;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.io.InputStream;
import java.net.URL;
import java.util.Properties;

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
 * @DynamicPropertySource is evaluated LAZILY when Spring reads a property.
 * Suppliers prefer stablemock.baseUrl.&lt;ClassName&gt; (and indexed variants) set by
 * StableMockExtension so @Async and pool threads get the right WireMock port;
 * ThreadLocal is a fallback on the JUnit thread.
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
     * The port is only known after WireMock starts; StableMockExtension publishes it
     * via class-scoped system properties (preferred) and ThreadLocal (fallback).
     * 
     * Fallback chain:
     * 1. Class-scoped system property: stablemock.baseUrl.&lt;ClassName&gt; (set by
     *    StableMockExtension; correct on @Async / pool threads where ThreadLocal is missing or stale)
     * 2. Global system property: stablemock.baseUrl
     * 3. ThreadLocal base URL (test thread only)
     * 4. Default URL (from application.properties if not provided)
     * 
     * Path preservation: Automatically preserves paths from original property values
     * (system properties, application.properties, or defaultUrl) and appends them
     * to the WireMock base URL.
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
        registerPropertyWithFallback(registry, propertyName, testClassName, defaultUrl, null);
    }

    protected static void registerPropertyWithFallback(
            DynamicPropertyRegistry registry,
            String propertyName,
            String testClassName,
            String defaultUrl,
            String pathOverride) {
        final String pathToPreserve = (pathOverride != null && !pathOverride.isEmpty())
                ? pathOverride
                : extractPathFromProperty(propertyName, defaultUrl);
        registry.add(propertyName, () -> {
            String baseUrl = System.getProperty(StableMockConfig.BASE_URL_PROPERTY + "." + testClassName);
            if (baseUrl == null || baseUrl.isEmpty()) {
                baseUrl = System.getProperty(StableMockConfig.BASE_URL_PROPERTY);
            }
            if (baseUrl == null || baseUrl.isEmpty()) {
                baseUrl = getThreadLocalBaseUrl();
            }
            if (baseUrl != null && !baseUrl.isEmpty()) {
                if (pathToPreserve != null && !pathToPreserve.isEmpty()) {
                    return baseUrl + pathToPreserve;
                }
                return baseUrl;
            }
            String fallbackUrl = defaultUrl;
            if (fallbackUrl != null && pathToPreserve != null && !pathToPreserve.isEmpty()) {
                // Extract base URL from fallback and append path
                String fallbackBase = extractBaseUrl(fallbackUrl);
                if (fallbackBase != null) {
                    return fallbackBase + pathToPreserve;
                }
            }
            return fallbackUrl;
        });
    }

    /**
     * Registers a dynamic property with the standard fallback chain for multiple
     * URLs.
     * 
     * REQUIRED when using StableMock because WireMock ports are dynamic and chosen
     * at runtime.
     * Each URL index gets its own WireMock server; ports are published on system properties
     * (preferred for @Async / pools) and ThreadLocal (fallback).
     * 
     * Fallback chain:
     * 1. Class-scoped system property: stablemock.baseUrl.&lt;ClassName&gt;.&lt;index&gt;
     * 2. Global: stablemock.baseUrl.&lt;index&gt;
     * 3. ThreadLocal by index (test thread; index 0 also uses primary ThreadLocal)
     * 4. Default URL (from application.properties if not provided)
     * 
     * Path preservation: Automatically preserves paths from original property values
     * (system properties, application.properties, or defaultUrl) and appends them
     * to the WireMock base URL.
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
        registerPropertyWithFallbackByIndex(registry, propertyName, testClassName, index, defaultUrl, null);
    }

    protected static void registerPropertyWithFallbackByIndex(
            DynamicPropertyRegistry registry,
            String propertyName,
            String testClassName,
            int index,
            String defaultUrl,
            String pathOverride) {
        final String pathToPreserve = (pathOverride != null && !pathOverride.isEmpty())
                ? pathOverride
                : extractPathFromProperty(propertyName, defaultUrl);
        registry.add(propertyName, () -> {
            String wireMockUrl = System.getProperty(StableMockConfig.BASE_URL_PROPERTY + "." + testClassName + "." + index);
            if (wireMockUrl == null || wireMockUrl.isEmpty()) {
                wireMockUrl = System.getProperty(StableMockConfig.BASE_URL_PROPERTY + "." + index);
            }
            if (wireMockUrl == null || wireMockUrl.isEmpty()) {
                wireMockUrl = getThreadLocalBaseUrlByIndex(index);
            }
            if (wireMockUrl != null && !wireMockUrl.isEmpty()) {
                if (pathToPreserve != null && !pathToPreserve.isEmpty()) {
                    return wireMockUrl + pathToPreserve;
                }
                return wireMockUrl;
            }
            String fallbackUrl = defaultUrl;
            if (fallbackUrl != null && pathToPreserve != null && !pathToPreserve.isEmpty()) {
                // Extract base URL from fallback and append path
                String fallbackBase = extractBaseUrl(fallbackUrl);
                if (fallbackBase != null) {
                    return fallbackBase + pathToPreserve;
                }
            }
            return fallbackUrl;
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

        // Path overrides from @U(paths = {"propertyName=/path", ...}) for single-URL multi-property case
        java.util.Map<String, String> pathOverrides = new java.util.HashMap<>();
        for (U annotation : annotations) {
            String[] paths = annotation.paths();
            if (paths != null) {
                for (String entry : paths) {
                    if (entry != null && !entry.isEmpty()) {
                        int eq = entry.indexOf('=');
                        if (eq > 0) {
                            String key = entry.substring(0, eq).trim();
                            String path = entry.substring(eq + 1).trim();
                            if (!key.isEmpty() && !path.isEmpty()) {
                                if (!path.startsWith("/")) {
                                    path = "/" + path;
                                }
                                pathOverrides.put(key, path);
                            }
                        }
                    }
                }
            }
        }

        // Collect all URLs and properties from all @U annotations
        java.util.List<String> allUrls = new java.util.ArrayList<>();
        java.util.List<java.util.List<String>> urlProperties = new java.util.ArrayList<>();

        for (U annotation : annotations) {
            String[] urls = annotation.urls();
            String[] properties = annotation.properties();

            if (urls != null && urls.length > 0) {
                if (urls.length == 1 && properties != null && properties.length >= 1) {
                    // Special case: 1 URL with multiple properties - all properties map to same URL.
                    // Each property may be "name" (base URL only) or "name=/path" (base URL + path).
                    allUrls.add(urls[0]);
                    java.util.List<String> propsForUrl = new java.util.ArrayList<>();
                    for (String prop : properties) {
                        if (prop != null && !prop.isEmpty()) {
                            int eq = prop.indexOf('=');
                            if (eq > 0) {
                                String name = prop.substring(0, eq).trim();
                                String path = prop.substring(eq + 1).trim();
                                if (!name.isEmpty()) {
                                    propsForUrl.add(name);
                                    if (!path.isEmpty()) {
                                        if (!path.startsWith("/")) {
                                            path = "/" + path;
                                        }
                                        pathOverrides.put(name, path);
                                    }
                                }
                            } else {
                                propsForUrl.add(prop.trim());
                            }
                        }
                    }
                    urlProperties.add(propsForUrl);
                } else {
                    // Standard case: 1:1 mapping (or multiple URLs with matching properties)
                    for (int i = 0; i < urls.length; i++) {
                        allUrls.add(urls[i]);
                        java.util.List<String> propsForUrl = new java.util.ArrayList<>();

                        // Map property at index i to URL at index i, honoring inline "name=/path" syntax
                        if (properties != null && i < properties.length && properties[i] != null
                                && !properties[i].isEmpty()) {
                            addPropertyToUrlMapping(propsForUrl, pathOverrides, properties[i]);
                        }

                        // If there are extra properties beyond URLs, map them to the last URL
                        if (i == urls.length - 1 && properties != null && properties.length > urls.length) {
                            for (int j = urls.length; j < properties.length; j++) {
                                if (properties[j] != null && !properties[j].isEmpty()) {
                                    addPropertyToUrlMapping(propsForUrl, pathOverrides, properties[j]);
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
                        // Single URL - use single URL method; optional path from @U(paths=...) or inline "name=/path"
                        String pathOverride = pathOverrides.get(propertyName);
                        registerPropertyWithFallback(registry, propertyName, testClassName, defaultUrl, pathOverride);
                    } else {
                        // Multiple URLs - use indexed method; still honor path overrides and inline "name=/path"
                        String pathOverride = pathOverrides.get(propertyName);
                        registerPropertyWithFallbackByIndex(registry, propertyName, testClassName, i, defaultUrl,
                                pathOverride);
                    }
                }
            }
        }
    }

    /**
     * Finds keys @U annotations on the test class, including inherited ones if
     * possible?
     * Uses the same parent-first class hierarchy walk as {@link TestContextResolver}
     * so URL indices match the WireMock servers started by {@code com.stablemock.StableMockExtension}.
     */
    private static U[] findAllUAnnotations(Class<?> testClass) {
        return TestContextResolver.findAllUDeclaredOnClassHierarchy(testClass);
    }

    /**
     * Adds a property mapping entry for a given raw property string.
     * Supports both simple names ("app.thirdparty.url") and inline path syntax
     * ("propertyName=/path").
     *
     * @param propsForUrl   List of property names for the current URL
     * @param pathOverrides Map of propertyName -> pathOverride ("/path")
     * @param rawProperty   Raw property entry from @U(properties={...})
     */
    private static void addPropertyToUrlMapping(
            java.util.List<String> propsForUrl,
            java.util.Map<String, String> pathOverrides,
            String rawProperty) {
        if (rawProperty == null || rawProperty.isEmpty()) {
            return;
        }
        String name = rawProperty;
        String path = null;
        int eq = rawProperty.indexOf('=');
        if (eq > 0) {
            name = rawProperty.substring(0, eq).trim();
            path = rawProperty.substring(eq + 1).trim();
        }
        if (name.isEmpty()) {
            return;
        }
        propsForUrl.add(name);
        if (path != null && !path.isEmpty()) {
            if (!path.startsWith("/")) {
                path = "/" + path;
            }
            if (pathOverrides != null) {
                pathOverrides.put(name, path);
            }
        }
    }

    /**
     * Extracts the path from a property value by reading from multiple sources.
     * Priority order:
     * 1. System property
     * 2. Classpath properties files (application-{profile}.properties, application.properties)
     * 3. defaultUrl parameter (if it contains a path)
     * 
     * @param propertyName The property name to look up
     * @param defaultUrl   The default URL from @U annotation (fallback source)
     * @return The path component (e.g., "/api/v1/endpoint") or null if no path found
     */
    private static String extractPathFromProperty(String propertyName, String defaultUrl) {
        // 1. Try system property
        String value = System.getProperty(propertyName);
        if (value != null && !value.isEmpty()) {
            String path = extractPath(value);
            if (path != null) {
                return path;
            }
        }

        // 2. Try properties files (including YAML and all active profiles)
        String activeProfileRaw = System.getProperty("spring.profiles.active", "");
        // spring.profiles.active can be a comma-separated list; try each profile
        String[] activeProfiles = activeProfileRaw.isEmpty()
                ? new String[0]
                : activeProfileRaw.split("\\s*,\\s*");
        java.util.List<String> candidateFiles = new java.util.ArrayList<>();
        for (String profile : activeProfiles) {
            if (!profile.isEmpty()) {
                candidateFiles.add("application-" + profile + ".properties");
                candidateFiles.add("application-" + profile + ".yml");
                candidateFiles.add("application-" + profile + ".yaml");
            }
        }
        candidateFiles.add("application.properties");
        candidateFiles.add("application.yml");
        candidateFiles.add("application.yaml");
        String[] possibleFileNames = candidateFiles.toArray(new String[0]);

        for (String fileName : possibleFileNames) {
            String propValue = readPropertyFromClasspath(fileName, propertyName);
            if (propValue != null && !propValue.isEmpty()) {
                String path = extractPath(propValue);
                if (path != null) {
                    return path;
                }
            }
        }

        // 3. Try defaultUrl
        if (defaultUrl != null && !defaultUrl.isEmpty()) {
            return extractPath(defaultUrl);
        }

        return null;
    }

    /**
     * Extracts the path component from a URL string.
     * 
     * @param urlString The URL string (e.g., "https://api.example.com/v1/users")
     * @return The path component (e.g., "/v1/users") or null if no path or parsing fails
     */
    private static String extractPath(String urlString) {
        try {
            URL url = new URL(urlString);
            String path = url.getPath();
            // Also include query string if present
            String query = url.getQuery();
            if (query != null && !query.isEmpty()) {
                path = path + "?" + query;
            }
            // Also include fragment if present
            String fragment = url.getRef();
            if (fragment != null && !fragment.isEmpty()) {
                path = path + "#" + fragment;
            }
            return (path != null && !path.isEmpty()) ? path : null;
        } catch (Exception e) {
            // Graceful degradation: if URL parsing fails, return null
            return null;
        }
    }

    /**
     * Extracts the base URL (protocol + host + port) from a URL string, removing the path.
     * 
     * @param urlString The full URL string
     * @return The base URL (e.g., "https://api.example.com") or null if parsing fails
     */
    private static String extractBaseUrl(String urlString) {
        try {
            URL url = new URL(urlString);
            int port = url.getPort();
            if (port == -1) {
                // Default port for protocol
                return url.getProtocol() + "://" + url.getHost();
            }
            return url.getProtocol() + "://" + url.getHost() + ":" + port;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Reads a property value from a properties file on the classpath.
     * Tries multiple classloaders for robustness.
     * 
     * @param fileName     The properties file name (e.g., "application.properties")
     * @param propertyName The property name to read
     * @return The property value or null if not found
     */
    private static String readPropertyFromClasspath(String fileName, String propertyName) {
        ClassLoader[] classLoaders = {
            Thread.currentThread().getContextClassLoader(),
            BaseStableMockTest.class.getClassLoader(),
            ClassLoader.getSystemClassLoader()
        };

        boolean isYaml = fileName.endsWith(".yml") || fileName.endsWith(".yaml");

        for (ClassLoader classLoader : classLoaders) {
            if (classLoader == null) {
                continue;
            }
            try (InputStream inputStream = classLoader.getResourceAsStream(fileName)) {
                if (inputStream != null) {
                    if (isYaml) {
                        String value = readPropertyFromYaml(inputStream, propertyName);
                        if (value != null && !value.trim().isEmpty()) {
                            return value.trim();
                        }
                    } else {
                        Properties properties = new Properties();
                        properties.load(inputStream);
                        String value = properties.getProperty(propertyName);
                        if (value != null && !value.trim().isEmpty()) {
                            return value.trim();
                        }
                    }
                }
            } catch (Exception e) {
                // Continue to next classloader or file
            }
        }

        return null;
    }

    /**
     * Minimal YAML property reader that handles flat key: value lines and
     * dotted-key notation without requiring a YAML library dependency.
     * Supports simple scalar values only (no anchors, multi-line, etc.).
     */
    private static String readPropertyFromYaml(InputStream inputStream, String propertyName) {
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8));
            // Build a flat properties map from the YAML by tracking indent-based key nesting
            java.util.Deque<String> keyStack = new java.util.ArrayDeque<>();
            java.util.Map<String, String> flat = new java.util.LinkedHashMap<>();
            String line;
            int prevIndent = 0;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty() || line.trim().startsWith("#")) {
                    continue;
                }
                int indent = 0;
                while (indent < line.length() && line.charAt(indent) == ' ') {
                    indent++;
                }
                int colonIdx = line.indexOf(':');
                if (colonIdx < 0) {
                    continue;
                }
                String key = line.substring(indent, colonIdx).trim();
                String val = line.substring(colonIdx + 1).trim();
                // Remove trailing comments from value
                if (val.startsWith("\"") || val.startsWith("'")) {
                    // quoted value — strip quotes
                    char q = val.charAt(0);
                    int end = val.indexOf(q, 1);
                    val = end > 0 ? val.substring(1, end) : val.substring(1);
                } else {
                    int commentIdx = val.indexOf(" #");
                    if (commentIdx >= 0) {
                        val = val.substring(0, commentIdx).trim();
                    }
                }
                // Pop stack entries whose indent is >= current indent
                while (!keyStack.isEmpty()) {
                    // We track as "indentLevel:keyPart" strings
                    String top = keyStack.peek();
                    int topIndent = Integer.parseInt(top.substring(0, top.indexOf(':')));
                    if (topIndent >= indent) {
                        keyStack.pop();
                    } else {
                        break;
                    }
                }
                if (!val.isEmpty()) {
                    // Leaf node: compute full dotted key
                    StringBuilder fullKey = new StringBuilder();
                    for (String part : keyStack) {
                        if (fullKey.length() > 0) fullKey.append('.');
                        fullKey.append(part.substring(part.indexOf(':') + 1));
                    }
                    if (fullKey.length() > 0) fullKey.append('.');
                    fullKey.append(key);
                    flat.put(fullKey.toString(), val);
                } else {
                    // Intermediate node: push onto stack
                    keyStack.push(indent + ":" + key);
                }
                prevIndent = indent;
            }
            return flat.get(propertyName);
        } catch (Exception e) {
            return null;
        }
    }
}
