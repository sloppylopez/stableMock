package com.stablemock.core.storage;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.stablemock.core.server.WireMockServerManager;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Facade for mapping storage operations that delegates to appropriate handlers
 * based on single vs multiple annotation cases.
 */
public final class MappingStorage {
    
    private MappingStorage() {
        // utility class
    }
    
    /**
     * Saves mappings for a single annotation case.
     */
    public static void saveMappings(WireMockServer wireMockServer, File mappingsDir, String targetUrl) throws IOException {
        SingleAnnotationMappingStorage.saveMappings(wireMockServer, mappingsDir, targetUrl);
    }
    
    /**
     * Saves mappings for a test method with single annotation case.
     *
     * @param wireMockServer        the WireMock server instance from which to read recorded mappings
     * @param testMethodMappingsDir the directory where the test method specific mappings will be stored
     * @param baseMappingsDir       the base directory containing existing class-level mappings
     * @param targetUrl             the target URL that the recorded mappings correspond to
     * @param existingRequestCount  the number of existing recorded requests for this test method
     * @param scenario              if {@code true}, creates WireMock scenario mappings for sequential responses when
     *                              the same endpoint is called multiple times. When enabled, multiple requests
     *                              to the same endpoint will be saved as separate mappings with proper scenario
     *                              state transitions (Started -> state-2 -> state-3 -> ...)
     * @throws IOException if an I/O error occurs while saving mappings
     */
    public static void saveMappingsForTestMethod(WireMockServer wireMockServer, File testMethodMappingsDir, 
            File baseMappingsDir, String targetUrl, int existingRequestCount, boolean scenario) throws IOException {
        SingleAnnotationMappingStorage.saveMappingsForTestMethod(wireMockServer, testMethodMappingsDir, 
                baseMappingsDir, targetUrl, existingRequestCount, scenario);
    }
    
    /**
     * Saves mappings for a test method with multiple annotations case.
     */
    public static void saveMappingsForTestMethodMultipleAnnotations(WireMockServer wireMockServer, 
            File testMethodMappingsDir, File baseMappingsDir, 
            List<WireMockServerManager.AnnotationInfo> annotationInfos, int existingRequestCount,
            List<Integer> existingRequestCounts,
            List<WireMockServer> allServers) throws IOException {
        MultipleAnnotationMappingStorage.saveMappingsForTestMethodMultipleAnnotations(wireMockServer, 
                testMethodMappingsDir, baseMappingsDir, annotationInfos, existingRequestCount, existingRequestCounts, allServers);
    }
    
    /**
     * Merges per-test-method mappings for single annotation case.
     */
    public static void mergePerTestMethodMappings(File baseMappingsDir) {
        SingleAnnotationMappingStorage.mergePerTestMethodMappings(baseMappingsDir);
    }
    
    /**
     * Merges annotation mappings for a specific URL index (multiple annotation case).
     */
    public static void mergeAnnotationMappingsForUrlIndex(File baseMappingsDir, int urlIndex) {
        MultipleAnnotationMappingStorage.mergeAnnotationMappingsForUrlIndex(baseMappingsDir, urlIndex);
    }
    
    /**
     * Cleans up class-level mapping directories.
     */
    public static void cleanupClassLevelDirectory(File baseMappingsDir) {
        BaseMappingStorage.cleanupClassLevelDirectory(baseMappingsDir);
    }
}
