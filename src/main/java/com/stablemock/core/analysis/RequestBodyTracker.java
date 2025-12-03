package com.stablemock.core.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Tracks request bodies across multiple test executions for dynamic field
 * detection.
 * Persists request history to .stablemock-analysis directory.
 */
public final class RequestBodyTracker {

    private static final Logger logger = LoggerFactory.getLogger(RequestBodyTracker.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final int MAX_HISTORY_SIZE = 10; // Keep last 10 executions

    private RequestBodyTracker() {
        // utility class
    }

    /**
     * Tracks a request with optional annotation index for multiple annotation
     * support.
     * 
     * @param testResourcesDir Test resources directory
     * @param testClassName    Name of the test class
     * @param testMethodName   Name of the test method
     * @param url              Request URL
     * @param method           HTTP method
     * @param body             Request body
     * @param annotationIndex  Optional annotation index (null for single
     *                         annotation)
     */
    public static void trackRequest(File testResourcesDir, String testClassName,
            String testMethodName, String url, String method,
            String body, Integer annotationIndex) {
        try {
            File trackingFile = getTrackingFile(testResourcesDir, testClassName,
                    testMethodName, annotationIndex);

            // Load existing history
            List<RequestSnapshot> history = loadRequestHistory(trackingFile);

            // Add new request
            history.add(new RequestSnapshot(url, method, body));

            // Keep only last N requests
            if (history.size() > MAX_HISTORY_SIZE) {
                history = history.subList(history.size() - MAX_HISTORY_SIZE, history.size());
            }

            // Save updated history
            saveRequestHistory(trackingFile, history);

            logger.debug("Tracked request: {} {} (total history: {})", method, url, history.size());

        } catch (Exception e) {
            logger.error("Failed to track request: {}", e.getMessage(), e);
        }
    }

    /**
     * Loads request history with optional annotation index support.
     */
    public static List<RequestSnapshot> loadRequestHistory(File testResourcesDir,
            String testClassName,
            String testMethodName,
            Integer annotationIndex) {
        File trackingFile = getTrackingFile(testResourcesDir, testClassName,
                testMethodName, annotationIndex);
        return loadRequestHistory(trackingFile);
    }

    /**
     * Gets the tracking file for a test method.
     */
    private static File getTrackingFile(File testResourcesDir, String testClassName,
            String testMethodName, Integer annotationIndex) {
        File analysisDir;

        if (annotationIndex != null) {
            // Multiple annotations: .stablemock-analysis/<class>/<method>/annotation_X
            analysisDir = new File(testResourcesDir,
                    ".stablemock-analysis/" + testClassName + "/" + testMethodName +
                            "/annotation_" + annotationIndex);
        } else {
            // Single annotation: .stablemock-analysis/<class>/<method>
            analysisDir = new File(testResourcesDir,
                    ".stablemock-analysis/" + testClassName + "/" + testMethodName);
        }

        if (!analysisDir.exists() && !analysisDir.mkdirs()) {
            throw new RuntimeException("Failed to create analysis directory: " +
                    analysisDir.getAbsolutePath());
        }

        return new File(analysisDir, "requests.json");
    }

    /**
     * Loads request history from a file.
     */
    private static List<RequestSnapshot> loadRequestHistory(File trackingFile) {
        if (!trackingFile.exists()) {
            return new ArrayList<>();
        }

        try {
            String content = new String(Files.readAllBytes(trackingFile.toPath()));
            RequestSnapshot[] snapshots = objectMapper.readValue(content, RequestSnapshot[].class);
            return new ArrayList<>(List.of(snapshots));
        } catch (Exception e) {
            logger.warn("Failed to load request history from {}: {}",
                    trackingFile.getAbsolutePath(), e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Saves request history to a file.
     */
    private static void saveRequestHistory(File trackingFile, List<RequestSnapshot> history)
            throws IOException {
        objectMapper.writeValue(trackingFile, history);
    }
}
