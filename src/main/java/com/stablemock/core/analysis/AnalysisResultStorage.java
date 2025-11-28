package com.stablemock.core.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;

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
        save(result, testResourcesDir, testClassName, testMethodName, null);
    }

    /**
     * Saves detection results with optional annotation index for multiple
     * annotation support.
     * 
     * @param result           Detection result to save
     * @param testResourcesDir Test resources directory
     * @param testClassName    Test class name
     * @param testMethodName   Test method name
     * @param annotationIndex  Optional annotation index (null for single
     *                         annotation)
     */
    public static void save(DetectionResult result, File testResourcesDir,
            String testClassName, String testMethodName,
            Integer annotationIndex) {
        try {
            File outputFile = getOutputFile(testResourcesDir, testClassName,
                    testMethodName, annotationIndex);

            // Create parent directories if needed
            File parentDir = outputFile.getParentFile();
            if (!parentDir.exists() && !parentDir.mkdirs()) {
                throw new IOException("Failed to create directory: " + parentDir.getAbsolutePath());
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
                fieldNode.put("field_path", field.getFieldPath());
                fieldNode.put("confidence", field.getConfidence());

                ArrayNode samplesArray = fieldNode.putArray("sample_values");
                for (String sample : field.getSampleValues()) {
                    samplesArray.add(sample);
                }
            }

            // Add ignore patterns
            ArrayNode patternsArray = json.putArray("ignore_patterns");
            for (String pattern : result.getIgnorePatterns()) {
                patternsArray.add(pattern);
            }

            // Write to file
            objectMapper.writeValue(outputFile, json);

            logger.info("Saved detection results to: {}", outputFile.getAbsolutePath());
            logger.info("Detected {} dynamic fields: {}",
                    result.getDynamicFields().size(), result.getIgnorePatterns());

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
        return loadIgnorePatterns(testResourcesDir, testClassName, testMethodName, null);
    }

    /**
     * Loads detection results with optional annotation index support.
     */
    public static List<String> loadIgnorePatterns(File testResourcesDir,
            String testClassName,
            String testMethodName,
            Integer annotationIndex) {
        try {
            File outputFile = getOutputFile(testResourcesDir, testClassName,
                    testMethodName, annotationIndex);

            if (!outputFile.exists()) {
                return List.of();
            }

            ObjectNode json = (ObjectNode) objectMapper.readTree(outputFile);
            ArrayNode patternsArray = (ArrayNode) json.get("ignore_patterns");

            if (patternsArray == null) {
                return List.of();
            }

            List<String> patterns = new java.util.ArrayList<>();
            patternsArray.forEach(node -> patterns.add(node.asText()));

            logger.debug("Loaded {} auto-detected ignore patterns from {}",
                    patterns.size(), outputFile.getAbsolutePath());

            return patterns;

        } catch (Exception e) {
            logger.debug("No detection results found or failed to load: {}", e.getMessage());
            return List.of();
        }
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
