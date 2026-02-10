package com.stablemock.core.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stablemock.core.util.AtomicFileWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.stablemock.core.config.StableMockConfig;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Stores dynamic field detection analysis results in the test resources folder.
 * Results are saved as JSON for human review and automatic application during
 * playback.
 */
public final class AnalysisResultStorage {

    private static final Logger logger = LoggerFactory.getLogger(AnalysisResultStorage.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private AnalysisResultStorage() {
        // utility class
    }

    /**
     * Saves detection results to the test resources folder.
     * 
     * @param result           Detection result to save
     * @param testResourcesDir Test resources directory (e.g., src/test/resources)
     * @param testClassName    Test class name
     * @param testMethodName   Test method name
     */
    public static void save(DetectionResult result, File testResourcesDir,
            String testClassName, String testMethodName) {
        save(result, testResourcesDir, testClassName, testMethodName, null, null, null);
    }

    /**
     * Saves detection results with optional annotation index (no @U dontIgnore).
     */
    public static void save(DetectionResult result, File testResourcesDir,
            String testClassName, String testMethodName,
            Integer annotationIndex, Class<?> testClass) {
        save(result, testResourcesDir, testClassName, testMethodName, annotationIndex, testClass, null);
    }

    /**
     * Saves detection results with optional annotation index for multiple
     * annotation support.
     *
     * @param result           Detection result to save
     * @param testResourcesDir Test resources directory
     * @param testClassName    Test class name
     * @param testMethodName   Test method name
     * @param annotationIndex  Optional annotation index (null for single annotation)
     * @param testClass        Optional test class for BaseStableMockTest.getProtectedDynamicFields() (null to use config only)
     * @param annotationDontIgnore Optional paths from @U(dontIgnore) to merge into protected set (null ok)
     */
    public static void save(DetectionResult result, File testResourcesDir,
            String testClassName, String testMethodName,
            Integer annotationIndex, Class<?> testClass, Set<String> annotationDontIgnore) {
        try {
            File outputFile = getOutputFile(testResourcesDir, testClassName,
                    testMethodName, annotationIndex);

            // Create parent directories if needed (atomic operation to avoid race conditions)
            File parentDir = outputFile.getParentFile();
            try {
                java.nio.file.Files.createDirectories(parentDir.toPath());
            } catch (java.nio.file.FileAlreadyExistsException e) {
                // Directory already exists, that's fine (another thread may have created it)
                if (!parentDir.isDirectory()) {
                    throw new IOException("Path exists but is not a directory: " + parentDir.getAbsolutePath());
                }
            } catch (Exception e) {
                throw new IOException("Failed to create directory: " + parentDir.getAbsolutePath(), e);
            }

            // Convert to JSON
            ObjectNode json = objectMapper.createObjectNode();
            json.put("testClass", result.getTestClassName());
            json.put("testMethod", result.getTestMethodName());
            json.put("detectedAt", result.getDetectedAt());
            // analyzed_requests_count: Total number of requests in history that were analyzed.
            // This accumulates over multiple test runs (capped at MAX_HISTORY_SIZE=10) and
            // represents the total data used for pattern detection. Higher counts indicate
            // more reliable detection based on more historical data.
            json.put("analyzed_requests_count", result.getAnalyzedRequestsCount());

            // Add dynamic fields
            ArrayNode dynamicFieldsArray = json.putArray("dynamic_fields");
            for (DetectionResult.DynamicField field : result.getDynamicFields()) {
                ObjectNode fieldNode = dynamicFieldsArray.addObject();
                fieldNode.put("field_path", field.fieldPath());

                ArrayNode samplesArray = fieldNode.putArray("sample_values");
                for (String sample : field.sampleValues()) {
                    samplesArray.add(sample);
                }
            }

            // Add ignore patterns (excluding protected fields so they remain in matchers)
            Set<String> protectedPaths = new LinkedHashSet<>(testClass != null ? getProtectedFieldsForTestClass(testClass) : getProtectedFieldsForTestClass(testClassName));
            if (annotationDontIgnore != null && !annotationDontIgnore.isEmpty()) {
                protectedPaths.addAll(annotationDontIgnore);
            }
            List<String> patternsToSave = filterOutProtectedPatterns(result.getIgnorePatterns(), protectedPaths);
            ArrayNode patternsArray = json.putArray("ignore_patterns");
            for (String pattern : patternsToSave) {
                patternsArray.add(pattern);
            }

            AtomicFileWriter.writeAtomically(outputFile, tempPath ->
                    objectMapper.writeValue(tempPath.toFile(), json));

            logger.info("Saved detection results to: {}", outputFile.getAbsolutePath());
            logger.info("Detected {} dynamic fields, {} ignore patterns ({} after protected filter)",
                    result.getDynamicFields().size(), result.getIgnorePatterns().size(), patternsToSave.size());

        } catch (Exception e) {
            logger.error("Failed to save detection results: {}", e.getMessage(), e);
        }
    }

    /**
     * Loads detection results from the test resources folder.
     * 
     * @param testResourcesDir Test resources directory
     * @param testClassName    Test class name
     * @param testMethodName   Test method name
     * @return List of ignore patterns from the detection result, or empty list if
     *         not found
     */
    public static List<String> loadIgnorePatterns(File testResourcesDir,
            String testClassName,
            String testMethodName) {
        return loadIgnorePatternsImpl(testResourcesDir, testClassName, testMethodName, null, null);
    }

    /**
     * Loads detection results with optional annotation index support.
     */
    public static List<String> loadIgnorePatterns(File testResourcesDir,
            String testClassName,
            String testMethodName,
            Integer annotationIndex) {
        return loadIgnorePatternsImpl(testResourcesDir, testClassName, testMethodName, annotationIndex, null);
    }

    /**
     * Loads detection results with optional annotation index and @U dontIgnore support.
     * @param annotationDontIgnore paths from @U(dontIgnore) that must not be in ignore_patterns (merged with protected set)
     */
    public static List<String> loadIgnorePatterns(File testResourcesDir,
            String testClassName,
            String testMethodName,
            Integer annotationIndex,
            Set<String> annotationDontIgnore) {
        return loadIgnorePatternsImpl(testResourcesDir, testClassName, testMethodName, annotationIndex, annotationDontIgnore);
    }

    private static List<String> loadIgnorePatternsImpl(File testResourcesDir,
            String testClassName,
            String testMethodName,
            Integer annotationIndex,
            Set<String> annotationDontIgnore) {
        try {
            File outputFile = getOutputFile(testResourcesDir, testClassName,
                    testMethodName, annotationIndex);
            File methodOrAnnotationDir = outputFile.getParentFile();

            List<String> patterns = new ArrayList<>();

            if (outputFile.exists()) {
                ObjectNode json = (ObjectNode) objectMapper.readTree(outputFile);
                ArrayNode patternsArray = (ArrayNode) json.get("ignore_patterns");
                if (patternsArray != null) {
                    patternsArray.forEach(node -> patterns.add(node.asText()));
                }
            }

            loadIgnorePatternsFromFile(methodOrAnnotationDir, "ignore-patterns.json", patterns);
            File[] methodDirContents = methodOrAnnotationDir.listFiles();
            if (methodDirContents != null) {
                for (File f : methodDirContents) {
                    if (f.isDirectory() && f.getName().startsWith("annotation_")) {
                        loadIgnorePatternsFromFile(f, "ignore-patterns.json", patterns);
                    }
                }
            }

            if (patterns.isEmpty()) {
                logger.debug("No ignore patterns found for {}.{}", testClassName, testMethodName);
                return List.of();
            }

            Set<String> protectedPaths = new LinkedHashSet<>(getProtectedFieldsForTestClass(testClassName));
            if (annotationDontIgnore != null && !annotationDontIgnore.isEmpty()) {
                protectedPaths.addAll(annotationDontIgnore);
            }
            List<String> filtered = filterOutProtectedPatterns(patterns, protectedPaths);
            if (filtered.size() < patterns.size()) {
                logger.info("Filtered {} ignore patterns to {} (protected fields excluded) for {}",
                        patterns.size(), filtered.size(), outputFile.getAbsolutePath());
            }

            logger.info("Loaded {} auto-detected ignore patterns from {}",
                    filtered.size(), outputFile.getAbsolutePath());
            if (logger.isDebugEnabled() && !filtered.isEmpty()) {
                logger.debug("Loaded patterns: {}", filtered);
            }

            return filtered;

        } catch (Exception e) {
            logger.warn("Failed to load detection results from {}: {}", 
                    getOutputFile(testResourcesDir, testClassName, testMethodName, annotationIndex).getAbsolutePath(),
                    e.getMessage());
            return List.of();
        }
    }

    private static void loadIgnorePatternsFromFile(File dir, String fileName, List<String> out) {
        File file = new File(dir, fileName);
        if (!file.exists()) return;
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(file);
            if (node.isArray()) {
                node.forEach(n -> {
                    if (n.isTextual()) {
                        String p = n.asText().trim();
                        if (!p.isEmpty() && !out.contains(p)) out.add(p);
                    }
                });
                logger.info("Loaded {} ignore pattern(s) from {}", node.size(), file.getAbsolutePath());
            }
        } catch (Exception e) {
            logger.warn("Failed to read {} from {}: {}", fileName, dir.getAbsolutePath(), e.getMessage());
        }
    }

    /**
     * Returns the set of protected field paths (config only). Used at load time when we only have test class name.
     */
    static Set<String> getProtectedFieldsForTestClass(String testClassName) {
        return new LinkedHashSet<>(StableMockConfig.getProtectedDynamicFields());
    }

    /**
     * Returns the set of protected field paths for a test class (config + optional BaseStableMockTest override).
     * Used at save time when we have the test Class.
     */
    static Set<String> getProtectedFieldsForTestClass(Class<?> testClass) {
        Set<String> set = new LinkedHashSet<>(StableMockConfig.getProtectedDynamicFields());
        if (testClass == null) {
            return set;
        }
        if (com.stablemock.spring.BaseStableMockTest.class.isAssignableFrom(testClass)) {
            try {
                java.lang.reflect.Method m = testClass.getMethod("getProtectedDynamicFields");
                if (m.getReturnType() == Set.class && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    @SuppressWarnings("unchecked")
                    Set<String> fromTest = (Set<String>) m.invoke(null);
                    if (fromTest != null) {
                        set.addAll(fromTest);
                    }
                }
            } catch (Exception e) {
                logger.debug("Could not get protected fields from test class {}: {}", testClass.getName(), e.getMessage());
            }
        }
        return set;
    }

    /**
     * Removes patterns that match any protected path (equals or prefix match, case-insensitive).
     */
    static List<String> filterOutProtectedPatterns(List<String> patterns, Set<String> protectedPaths) {
        if (patterns == null || patterns.isEmpty()) {
            return patterns == null ? List.of() : new ArrayList<>(patterns);
        }
        if (protectedPaths == null || protectedPaths.isEmpty()) {
            return new ArrayList<>(patterns);
        }
        List<String> out = new ArrayList<>();
        for (String p : patterns) {
            if (p == null) continue;
            if (isProtectedPattern(p, protectedPaths)) {
                logger.debug("Excluding protected pattern from ignore_patterns: {}", p);
                continue;
            }
            out.add(p);
        }
        return out;
    }

    private static boolean isProtectedPattern(String pattern, Set<String> protectedPaths) {
        String pl = pattern.trim().toLowerCase();
        for (String q : protectedPaths) {
            if (q == null) continue;
            String ql = q.trim().toLowerCase();
            if (pl.equals(ql)) return true;
            if (pl.startsWith(ql) || ql.startsWith(pl)) return true;
        }
        return false;
    }

    /**
     * Gets the output file path for detection results.
     */
    private static File getOutputFile(File testResourcesDir, String testClassName,
            String testMethodName, Integer annotationIndex) {
        File resultsDir;

        if (annotationIndex != null) {
            // Multiple annotations:
            // stablemock/<class>/<method>/annotation_X/detected-fields.json
            resultsDir = new File(testResourcesDir,
                    "stablemock/" + testClassName + "/" + testMethodName +
                            "/annotation_" + annotationIndex);
        } else {
            // Single annotation: stablemock/<class>/<method>/detected-fields.json
            resultsDir = new File(testResourcesDir,
                    "stablemock/" + testClassName + "/" + testMethodName);
        }

        return new File(resultsDir, "detected-fields.json");
    }
}
