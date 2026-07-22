package com.stablemock.core.resolver;

import com.stablemock.U;
import org.junit.jupiter.api.extension.ExtensionContext;


import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Resolves test context information including annotations, directories, and Spring Boot detection.
 */
public final class TestContextResolver {


    private static final String TEST_RESOURCES_PATH = "src/test/resources";
    private static final String TARGET_CLASSES_SEP = "\\target\\classes\\";
    private static final String BUILD_CLASSES_SEP = "\\build\\classes\\";

    private TestContextResolver() {
        // utility class
    }
    
    public static U[] findAllUAnnotations(ExtensionContext context) {
        java.util.List<U> annotations = new java.util.ArrayList<>();

        // Method-level @U wins over class-level (same as before)
        context.getTestMethod().ifPresent(method -> {
            U[] onMethod = method.getAnnotationsByType(U.class);
            if (onMethod.length > 0) {
                Collections.addAll(annotations, onMethod);
            }
        });

        if (annotations.isEmpty()) {
            collectClassHierarchyUAnnotations(context.getRequiredTestClass(), annotations);
        }

        return annotations.toArray(new U[0]);
    }

    /**
     * All {@code @U} on {@code testClass} and superclasses, <b>parent classes first</b>, then the test class.
     * Each declaring class contributes {@link Class#getAnnotationsByType(Class)} order (repeatable-safe).
     * Used by Spring {@code autoRegisterProperties} so URL indices match
     * {@link com.stablemock.StableMockExtension}.
     */
    public static U[] findAllUDeclaredOnClassHierarchy(Class<?> testClass) {
        List<U> annotations = new ArrayList<>();
        collectClassHierarchyUAnnotations(testClass, annotations);
        return annotations.toArray(new U[0]);
    }

    private static void collectClassHierarchyUAnnotations(Class<?> testClass, List<U> out) {
        if (testClass == null) {
            return;
        }
        List<Class<?>> chain = new ArrayList<>();
        for (Class<?> c = testClass; c != null && c != Object.class; c = c.getSuperclass()) {
            chain.add(c);
        }
        Collections.reverse(chain);
        for (Class<?> c : chain) {
            U[] direct = c.getAnnotationsByType(U.class);
            if (direct.length > 0) {
                Collections.addAll(out, direct);
            }
        }
    }
    
    public static boolean isSpringBootTest(ExtensionContext context) {
        return isSpringBootTest(context.getRequiredTestClass());
    }

    /**
     * Returns true if {@code testClass} or any class in its hierarchy is annotated
     * with {@code @SpringBootTest}.
     */
    public static boolean isSpringBootTest(Class<?> testClass) {
        try {
            @SuppressWarnings("unchecked")
            Class<? extends java.lang.annotation.Annotation> springBootTestClass =
                (Class<? extends java.lang.annotation.Annotation>) Class.forName("org.springframework.boot.test.context.SpringBootTest");
            // Walk the class hierarchy — @SpringBootTest is often on a base class,
            // and isAnnotationPresent() only checks the concrete class.
            Class<?> current = testClass;
            while (current != null && current != Object.class) {
                if (current.isAnnotationPresent(springBootTestClass)) {
                    return true;
                }
                current = current.getSuperclass();
            }
            return false;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
    
    public static File findTestResourcesDirectory(ExtensionContext context) {
        Class<?> testClass = context.getRequiredTestClass();
        java.net.URL classResource = testClass.getResource(testClass.getSimpleName() + ".class");
        
        if (classResource == null) {
            // Fallback if resource cannot be found
            return getFallbackTestResourcesDirectory();
        }
        
        String classPath = classResource.toString();

        // Handle JAR URLs (e.g., jar:file:/path/to.jar!/com/example/Test.class)
        // In JDK 17, classes might be loaded from JAR files, causing FileNotFoundException
        if (classPath.startsWith("jar:")) {
            // Extract file path from JAR URL
            int separatorIndex = classPath.indexOf("!/");
            if (separatorIndex > 0) {
                String jarPath = classPath.substring(4, separatorIndex); // Remove "jar:" prefix
                if (jarPath.startsWith("file:/")) {
                    jarPath = jarPath.substring(6);
                    if (jarPath.startsWith("/") && jarPath.length() > 3 && jarPath.charAt(2) == ':') {
                        jarPath = jarPath.substring(1);
                    }
                    // Try to find project root from JAR path
                    File jarFile = new File(jarPath);
                    File projectRoot = findProjectRootFromJar(jarFile);
                    if (projectRoot != null) {
                        File result = new File(projectRoot, TEST_RESOURCES_PATH);
                        if (result.exists() || result.getParentFile().exists()) {
                            return result;
                        }
                    }
                }
            }
            // If JAR URL parsing fails, use fallback
            return getFallbackTestResourcesDirectory();
        }

        if (classPath.startsWith("file:/")) {
            String path = classPath.substring(6);
            if (path.startsWith("/") && path.length() > 3 && path.charAt(2) == ':') {
                path = path.substring(1);
            }
            
            if (path.contains("/target/classes/")) {
                path = path.substring(0, path.indexOf("/target/classes/"));
                File result = new File(path, TEST_RESOURCES_PATH);
                if (result.exists() || result.getParentFile().exists()) {
                    return result;
                }
            } else if (path.contains("/build/classes/")) {
                path = path.substring(0, path.indexOf("/build/classes/"));
                File result = new File(path, TEST_RESOURCES_PATH);
                if (result.exists() || result.getParentFile().exists()) {
                    return result;
                }
            } else if (path.contains(TARGET_CLASSES_SEP)) {
                path = path.substring(0, path.indexOf(TARGET_CLASSES_SEP));
                File result = new File(path, TEST_RESOURCES_PATH);
                if (result.exists() || result.getParentFile().exists()) {
                    return result;
                }
            } else if (path.contains(BUILD_CLASSES_SEP)) {
                path = path.substring(0, path.indexOf(BUILD_CLASSES_SEP));
                File result = new File(path, TEST_RESOURCES_PATH);
                if (result.exists() || result.getParentFile().exists()) {
                    return result;
                }
            }
        }

        return getFallbackTestResourcesDirectory();
    }
    
    /**
     * Attempts to find project root from a JAR file path.
     * Looks for common build output directories to infer project structure.
     */
    private static File findProjectRootFromJar(File jarFile) {
        if (jarFile == null || !jarFile.exists()) {
            return null;
        }
        
        // Common patterns: build/libs/, target/, out/artifacts/, etc.
        File current = jarFile.getParentFile();
        int maxDepth = 10; // Prevent infinite loops
        int depth = 0;
        
        while (current != null && depth < maxDepth) {
            // Check if this looks like a project root (has TEST_RESOURCES_PATH)
            File testResources = new File(current, TEST_RESOURCES_PATH);
            if (testResources.exists()) {
                return current;
            }
            // Check for common build directories that indicate we're in a subdirectory
            String name = current.getName();
            if (name.equals("libs") || name.equals("build") || name.equals("target") || 
                name.equals("out") || name.equals("dist")) {
                File parent = current.getParentFile();
                if (parent != null) {
                    File testResourcesInParent = new File(parent, TEST_RESOURCES_PATH);
                    if (testResourcesInParent.exists()) {
                        return parent;
                    }
                }
            }
            current = current.getParentFile();
            depth++;
        }
        
        return null;
    }
    
    /**
     * Returns fallback test resources directory.
     */
    private static File getFallbackTestResourcesDirectory() {
        String userDir = System.getProperty("user.dir");
        File fallback = new File(userDir, TEST_RESOURCES_PATH);
        if (!fallback.exists()) {
            fallback = new File(TEST_RESOURCES_PATH);
        }
        return fallback;
    }
    
    public static String getTestClassName(ExtensionContext context) {
        return context.getRequiredTestClass().getSimpleName();
    }
    
    public static String getTestMethodName(ExtensionContext context) {
        return context.getTestMethod()
                .map(Method::getName)
                .orElse("unknown");
    }
    
    /**
     * Gets a unique identifier for the test method, suitable for use in file paths.
     * For parameterized tests, this includes the invocation index to ensure uniqueness.
     * For regular tests, this returns the method name.
     * 
     * @param context The extension context
     * @return A unique, filesystem-safe identifier for the test method invocation
     */
    public static String getTestMethodIdentifier(ExtensionContext context) {
        String methodName = getTestMethodName(context);
        
        // For parameterized tests, extract invocation index from unique ID
        // Unique ID format: [engine:junit-jupiter]/[class:...]/[method:...]/[test-template:...]/[test-template-invocation:#N]
        String uniqueId = context.getUniqueId();
        if (uniqueId.contains("test-template-invocation")) {
            try {
                // Try different formats: [test-template-invocation:#N] or [test-template-invocation:N]
                String pattern = "test-template-invocation:";
                int startIdx = uniqueId.indexOf(pattern);
                if (startIdx >= 0) {
                    startIdx += pattern.length();
                    // Skip '#' if present
                    if (startIdx < uniqueId.length() && uniqueId.charAt(startIdx) == '#') {
                        startIdx++;
                    }
                    int endIdx = uniqueId.indexOf("]", startIdx);
                    if (endIdx > startIdx) {
                        String invocationNum = uniqueId.substring(startIdx, endIdx);
                        // Create identifier with invocation index: methodName[0], methodName[1], etc.
                        // Note: JUnit uses 1-based indexing (#1, #2, #3), we convert to 0-based for consistency
                        int index = Integer.parseInt(invocationNum) - 1;
                        return methodName + "[" + index + "]";
                    }
                }
            } catch (NumberFormatException e) {
                // If parsing fails, fall through to display name check
            }
        }
        
        // Fallback: check display name only for parameterized tests (must contain brackets with numbers)
        // Don't use display name for regular tests - it might include parentheses like "testMethod()"
        String displayName = context.getDisplayName();
        if (!displayName.equals(methodName) && displayName.contains(methodName)) {
            // Only use display name if it matches parameterized test pattern: methodName[N] or methodName[N, ...]
            // Pattern: method name followed by [ and a number
            java.util.regex.Pattern paramPattern = java.util.regex.Pattern.compile(
                java.util.regex.Pattern.quote(methodName) + "\\[\\d+");
            if (paramPattern.matcher(displayName).find()) {
                // This is a parameterized test invocation (e.g., "testMethod[0]" or "testMethod[1, arg]")
                // Sanitize: replace characters that are problematic in file paths
                // Keep alphanumeric, underscore, dash, and brackets (for parameterized tests)
                String sanitized = displayName
                        .replaceAll("[^a-zA-Z0-9_\\[\\]\\-\\(\\)]", "_")
                        .replaceAll("_{2,}", "_") // Replace multiple underscores with single
                        .replaceAll("^_|_$", ""); // Remove leading/trailing underscores
                
                // Extract just methodName[N] part if there's extra text after brackets
                // e.g., "testMethod[0, arg]" -> "testMethod[0]"
                java.util.regex.Pattern extractPattern = java.util.regex.Pattern.compile(
                    "(" + java.util.regex.Pattern.quote(methodName) + "\\[\\d+\\])");
                java.util.regex.Matcher matcher = extractPattern.matcher(sanitized);
                if (matcher.find()) {
                    return matcher.group(1);
                }
                
                // Ensure it's not empty and not too long (Windows has 255 char limit for filenames)
                if (!sanitized.isEmpty() && sanitized.length() < 200) {
                    return sanitized;
                }
            }
        }
        
        // Fall back to method name for non-parameterized tests (without parentheses)
        return methodName;
    }
}

