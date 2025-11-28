package com.stablemock.core.analysis;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.stablemock.core.server.WireMockServerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;

/**
 * Orchestrates dynamic field detection analysis across multiple test
 * executions.
 * Coordinates RequestBodyTracker, DynamicFieldDetector, and
 * AnalysisResultStorage
 * to identify and persist automatically-detected ignore patterns.
 */
public final class DynamicFieldAnalysisOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(DynamicFieldAnalysisOrchestrator.class);

    private DynamicFieldAnalysisOrchestrator() {
        // utility class
    }

    /**
     * Performs dynamic field detection analysis for a test method.
     * 
     * This method:
     * 1. Extracts request bodies from WireMock serve events
     * 2. Tracks new requests in the request history
     * 3. Loads historical requests from previous test executions
     * 4. Analyzes requests to detect dynamic fields
     * 5. Saves detection results with auto-generated ignore patterns
     * 
     * @param server               WireMock server with recorded interactions
     * @param existingRequestCount Number of requests before this test execution
     * @param testResourcesDir     Test resources directory (e.g.,
     *                             src/test/resources)
     * @param testClassName        Test class name
     * @param testMethodName       Test method name
     * @param annotationInfos      List of annotation info for the test (nullable
     *                             for single annotation)
     * @param allServers           List of all WireMock servers (for multiple URLs/annotations)
     */
    public static void analyzeAndPersist(
            WireMockServer server,
            Integer existingRequestCount,
            File testResourcesDir,
            String testClassName,
            String testMethodName,
            List<WireMockServerManager.AnnotationInfo> annotationInfos,
            List<WireMockServer> allServers) {

        if (server == null) {
            logger.debug("Skipping dynamic field detection: server not available");
            return;
        }

        logger.info("Starting dynamic field detection for test: {}.{}", testClassName, testMethodName);

        // Get all serve events from this test execution
        List<ServeEvent> allServeEvents = server.getAllServeEvents();
        int newRequestCount = allServeEvents.size() - (existingRequestCount != null ? existingRequestCount : 0);

        if (newRequestCount <= 0) {
            logger.debug("No new requests to analyze");
            return;
        }

        // Process each annotation separately if multiple annotations/URLs exist
        if (annotationInfos != null && !annotationInfos.isEmpty()) {
            for (WireMockServerManager.AnnotationInfo annotationInfo : annotationInfos) {
                Integer annotationIndex = annotationInfo.index;

                logger.debug("Analyzing annotation {} for method {}",
                        annotationIndex, testMethodName);

                try {
                    // Get serve events from the specific server for this annotation index
                    List<ServeEvent> annotationServeEvents = allServeEvents;
                    Integer annotationExistingRequestCount = existingRequestCount;
                    
                    if (allServers != null && !allServers.isEmpty() && annotationIndex < allServers.size()) {
                        // Multiple servers - get events from the specific server for this annotation
                        WireMockServer annotationServer = allServers.get(annotationIndex);
                        if (annotationServer != null) {
                            annotationServeEvents = annotationServer.getAllServeEvents();
                            // For multiple servers, only track existingRequestCount for the first server
                            annotationExistingRequestCount = (annotationIndex == 0) ? existingRequestCount : 0;
                        }
                    }
                    
                    analyzeForAnnotation(annotationServeEvents, annotationExistingRequestCount, testResourcesDir,
                            testClassName, testMethodName, annotationIndex);
                } catch (Exception e) {
                    logger.error("Failed to analyze dynamic fields for {}.{} annotation {}: {}",
                            testClassName, testMethodName, annotationIndex, e.getMessage(), e);
                }
            }
        } else {
            // Single annotation or no annotation info - analyze all requests together
            try {
                analyzeForAnnotation(allServeEvents, existingRequestCount, testResourcesDir,
                        testClassName, testMethodName, null);
            } catch (Exception e) {
                logger.error("Failed to analyze dynamic fields for {}.{}: {}",
                        testClassName, testMethodName, e.getMessage(), e);
            }
        }
    }

    /**
     * Analyzes requests for a specific annotation (or null for single annotation).
     */
    private static void analyzeForAnnotation(
            List<ServeEvent> allServeEvents,
            Integer existingRequestCount,
            File testResourcesDir,
            String testClassName,
            String testMethodName,
            Integer annotationIndex) {

        // Step 1: Track new requests
        trackNewRequests(allServeEvents, existingRequestCount, testResourcesDir,
                testClassName, testMethodName, annotationIndex);

        // Step 2: Load full request history
        List<RequestSnapshot> history = RequestBodyTracker.loadRequestHistory(
                testResourcesDir, testClassName, testMethodName, annotationIndex);

        // Step 3: Analyze for dynamic fields (requires at least 2 requests)
        if (history.size() < 2) {
            logger.info("Need at least 2 request executions for analysis. Current count: {}",
                    history.size());
            return;
        }

        DetectionResult result = DynamicFieldDetector.analyzeRequests(
                history, testClassName, testMethodName);

        // Step 4: Save results
        if (!result.getDynamicFields().isEmpty()) {
            AnalysisResultStorage.save(result, testResourcesDir,
                    testClassName, testMethodName, annotationIndex);

            logger.info("✓ Detected {} dynamic field(s) in {}.{}",
                    result.getDynamicFields().size(), testClassName, testMethodName);
        } else {
            logger.debug("No dynamic fields detected for {}.{}",
                    testClassName, testMethodName);
        }
    }

    /**
     * Tracks new requests from the current test execution.
     */
    private static void trackNewRequests(
            List<ServeEvent> allServeEvents,
            Integer existingRequestCount,
            File testResourcesDir,
            String testClassName,
            String testMethodName,
            Integer annotationIndex) {

        int startIndex = existingRequestCount != null ? existingRequestCount : 0;

        for (int i = startIndex; i < allServeEvents.size(); i++) {
            ServeEvent event = allServeEvents.get(i);
            String url = event.getRequest().getUrl();
            String method = event.getRequest().getMethod().getName();
            String body = event.getRequest().getBodyAsString();

            // Track all requests (including GET requests without bodies)
            // Empty body is fine - it will be stored as null or empty string
            RequestBodyTracker.trackRequest(testResourcesDir, testClassName,
                    testMethodName, url, method, body != null ? body : "", annotationIndex);
        }
    }
}
