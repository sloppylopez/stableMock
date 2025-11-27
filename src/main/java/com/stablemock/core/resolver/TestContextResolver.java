package com.stablemock.core.resolver;

import com.stablemock.U;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * Resolves test context information including annotations, directories, and Spring Boot detection.
 */
public final class TestContextResolver {
    
    private TestContextResolver() {
        // utility class
    }
    
    public static U findUAnnotation(ExtensionContext context) {
        // Check method-level annotation first
        U methodAnnotation = context.getTestMethod()
                .map(method -> method.getAnnotation(U.class))
                .orElse(null);
        if (methodAnnotation != null) {
            return methodAnnotation;
        }

        // Check class-level annotation
        return context.getRequiredTestClass().getAnnotation(U.class);
    }
    
    public static U[] findAllUAnnotations(ExtensionContext context) {
        java.util.List<U> annotations = new java.util.ArrayList<>();
        
        // Check method-level annotations first
        context.getTestMethod().ifPresent(method -> {
            U.List methodList = method.getAnnotation(U.List.class);
            if (methodList != null) {
                java.util.Collections.addAll(annotations, methodList.value());
            } else {
                U methodAnnotation = method.getAnnotation(U.class);
                if (methodAnnotation != null) {
                    annotations.add(methodAnnotation);
                }
            }
        });
        
        // If no method-level annotations, check class-level
        if (annotations.isEmpty()) {
            Class<?> testClass = context.getRequiredTestClass();
            U.List classList = testClass.getAnnotation(U.List.class);
            if (classList != null) {
                java.util.Collections.addAll(annotations, classList.value());
            } else {
                U classAnnotation = testClass.getAnnotation(U.class);
                if (classAnnotation != null) {
                    annotations.add(classAnnotation);
                }
            }
        }
        
        return annotations.toArray(new U[0]);
    }
    
    public static boolean isSpringBootTest(ExtensionContext context) {
        Class<?> testClass = context.getRequiredTestClass();
        try {
            @SuppressWarnings("unchecked")
            Class<? extends java.lang.annotation.Annotation> springBootTestClass = 
                (Class<? extends java.lang.annotation.Annotation>) Class.forName("org.springframework.boot.test.context.SpringBootTest");
            return testClass.isAnnotationPresent(springBootTestClass);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
    
    public static File findTestResourcesDirectory(ExtensionContext context) {
        Class<?> testClass = context.getRequiredTestClass();
        String classPath = Objects.requireNonNull(testClass.getResource(testClass.getSimpleName() + ".class")).toString();

        if (classPath.startsWith("file:/")) {
            String path = classPath.substring(6);
            if (path.startsWith("/") && path.length() > 3 && path.charAt(2) == ':') {
                path = path.substring(1);
            }
            
            if (path.contains("/target/classes/")) {
                path = path.substring(0, path.indexOf("/target/classes/"));
                File result = new File(path, "src/test/resources");
                if (result.exists() || result.getParentFile().exists()) {
                    return result;
                }
            } else if (path.contains("/build/classes/")) {
                path = path.substring(0, path.indexOf("/build/classes/"));
                File result = new File(path, "src/test/resources");
                if (result.exists() || result.getParentFile().exists()) {
                    return result;
                }
            } else if (path.contains("\\target\\classes\\")) {
                path = path.substring(0, path.indexOf("\\target\\classes\\"));
                File result = new File(path, "src/test/resources");
                if (result.exists() || result.getParentFile().exists()) {
                    return result;
                }
            } else if (path.contains("\\build\\classes\\")) {
                path = path.substring(0, path.indexOf("\\build\\classes\\"));
                File result = new File(path, "src/test/resources");
                if (result.exists() || result.getParentFile().exists()) {
                    return result;
                }
            }
        }

        String userDir = System.getProperty("user.dir");
        File fallback = new File(userDir, "src/test/resources");
        if (!fallback.exists()) {
            fallback = new File("src/test/resources");
        }
        System.out.println("StableMock: Using test resources directory: " + fallback.getAbsolutePath());
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
}

