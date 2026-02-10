package com.stablemock.core.analysis;

import com.stablemock.core.config.StableMockConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that protected dynamic fields are excluded from ignore_patterns (save and load).
 * Same thread to avoid system property cross-test pollution.
 */
@Execution(ExecutionMode.SAME_THREAD)
class AnalysisResultStorageProtectedFieldsTest {

    private static final String RATE_PLAN_CODE_PATH = "xml:*[local-name()='Envelope']/*[local-name()='Body']"
            + "/*[local-name()='SampleRQ']/*[local-name()='SampleSegments']"
            + "/*[local-name()='SampleSegment']/*[local-name()='SampleSearchCriteria']"
            + "/*[local-name()='SampleCriterion']/*[local-name()='SamplePlanCandidates']"
            + "/*[local-name()='SamplePlanCandidate']/@*[local-name()='SampleFieldA']";
    private static final String ROOM_TYPE_PATH = "xml:*[local-name()='Envelope']/*[local-name()='Body']"
            + "/*[local-name()='SampleRQ']/*[local-name()='SampleStayCandidates']"
            + "/*[local-name()='SampleStayCandidate']/@*[local-name()='SampleFieldB']";
    private static final String ECHO_TOKEN_PATH = "xml:*[local-name()='Envelope']/*[local-name()='Body']"
            + "/*[local-name()='SampleRQ']/@*[local-name()='SampleFieldC']";

    @AfterEach
    void clearProtectedProperty() {
        System.clearProperty(StableMockConfig.PROTECTED_DYNAMIC_FIELDS_PROPERTY);
    }

    @Test
    void filterOutProtectedPatterns_emptyProtected_returnsAll() {
        List<String> patterns = List.of(RATE_PLAN_CODE_PATH, ECHO_TOKEN_PATH);
        List<String> out = AnalysisResultStorage.filterOutProtectedPatterns(patterns, Set.of());
        assertEquals(2, out.size());
        assertTrue(out.contains(RATE_PLAN_CODE_PATH));
        assertTrue(out.contains(ECHO_TOKEN_PATH));
    }

    @Test
    void filterOutProtectedPatterns_exactMatch_excludesPattern() {
        List<String> patterns = List.of(RATE_PLAN_CODE_PATH, ECHO_TOKEN_PATH);
        Set<String> protectedPaths = Set.of(RATE_PLAN_CODE_PATH);
        List<String> out = AnalysisResultStorage.filterOutProtectedPatterns(patterns, protectedPaths);
        assertEquals(1, out.size());
        assertEquals(ECHO_TOKEN_PATH, out.get(0));
    }

    @Test
    void filterOutProtectedPatterns_prefixMatch_excludesPattern() {
        String prefixProtected = "xml:*[local-name()='Envelope']/*[local-name()='Body']/*[local-name()='SampleRQ']/*[local-name()='SampleSegments']";
        List<String> patterns = List.of(RATE_PLAN_CODE_PATH, ECHO_TOKEN_PATH);
        Set<String> protectedPaths = Set.of(prefixProtected);
        List<String> out = AnalysisResultStorage.filterOutProtectedPatterns(patterns, protectedPaths);
        assertEquals(1, out.size());
        assertEquals(ECHO_TOKEN_PATH, out.get(0));
    }

    @Test
    void filterOutProtectedPatterns_caseInsensitive_excludesPattern() {
        List<String> patterns = List.of(RATE_PLAN_CODE_PATH.toUpperCase(), ECHO_TOKEN_PATH);
        Set<String> protectedPaths = Set.of(RATE_PLAN_CODE_PATH.toLowerCase());
        List<String> out = AnalysisResultStorage.filterOutProtectedPatterns(patterns, protectedPaths);
        assertEquals(1, out.size());
        assertEquals(ECHO_TOKEN_PATH, out.get(0));
    }

    @Test
    void filterOutProtectedPatterns_multipleProtected_excludesAllMatching() {
        List<String> patterns = List.of(RATE_PLAN_CODE_PATH, ROOM_TYPE_PATH, ECHO_TOKEN_PATH);
        Set<String> protectedPaths = Set.of(RATE_PLAN_CODE_PATH, ROOM_TYPE_PATH);
        List<String> out = AnalysisResultStorage.filterOutProtectedPatterns(patterns, protectedPaths);
        assertEquals(1, out.size());
        assertEquals(ECHO_TOKEN_PATH, out.get(0));
    }

    @Test
    void filterOutProtectedPatterns_nullOrEmpty_returnsCopyOrEmpty() {
        List<String> empty = AnalysisResultStorage.filterOutProtectedPatterns(List.of(), Set.of("x"));
        assertTrue(empty.isEmpty());
        List<String> fromNull = AnalysisResultStorage.filterOutProtectedPatterns(null, Set.of("x"));
        assertTrue(fromNull.isEmpty());
        List<String> withNullProtected = AnalysisResultStorage.filterOutProtectedPatterns(List.of("a"), null);
        assertEquals(1, withNullProtected.size());
        assertEquals("a", withNullProtected.get(0));
    }

    @Test
    void getProtectedFieldsForTestClass_withoutSystemProperty_returnsEmpty() {
        System.clearProperty(StableMockConfig.PROTECTED_DYNAMIC_FIELDS_PROPERTY);
        Set<String> set = AnalysisResultStorage.getProtectedFieldsForTestClass((String) null);
        assertTrue(set.isEmpty(), "Expected empty set when property not set, got: " + set);
    }

    @Test
    void getProtectedFieldsForTestClass_withSystemProperty_returnsParsedSet() {
        System.setProperty(StableMockConfig.PROTECTED_DYNAMIC_FIELDS_PROPERTY,
                RATE_PLAN_CODE_PATH + ";" + ROOM_TYPE_PATH);
        try {
            Set<String> set = AnalysisResultStorage.getProtectedFieldsForTestClass((String) null);
            assertEquals(2, set.size());
            assertTrue(set.contains(RATE_PLAN_CODE_PATH));
            assertTrue(set.contains(ROOM_TYPE_PATH));
        } finally {
            System.clearProperty(StableMockConfig.PROTECTED_DYNAMIC_FIELDS_PROPERTY);
        }
    }

    @Test
    void savedIgnorePatterns_excludeProtected() throws Exception {
        System.setProperty(StableMockConfig.PROTECTED_DYNAMIC_FIELDS_PROPERTY, RATE_PLAN_CODE_PATH);
        java.io.File dir = java.nio.file.Files.createTempDirectory("stablemock-protected-test").toFile();
        try {
            DetectionResult result = new DetectionResult("SomeTest", "testMethod", 2);
            result.addDynamicField(new DetectionResult.DynamicField(RATE_PLAN_CODE_PATH, List.of("CODE_A", "CODE_B")));
            result.addIgnorePattern(RATE_PLAN_CODE_PATH);
            result.addDynamicField(new DetectionResult.DynamicField(ECHO_TOKEN_PATH, List.of("1", "2")));
            result.addIgnorePattern(ECHO_TOKEN_PATH);

            AnalysisResultStorage.save(result, dir, "SomeTest", "testMethod", null, null);

            java.io.File outFile = new java.io.File(dir, "stablemock/SomeTest/testMethod/detected-fields.json");
            assertTrue(outFile.exists());
            List<String> loaded = AnalysisResultStorage.loadIgnorePatterns(dir, "SomeTest", "testMethod");
            assertFalse(loaded.contains(RATE_PLAN_CODE_PATH), "Protected pattern must not appear in loaded ignore_patterns");
            assertTrue(loaded.contains(ECHO_TOKEN_PATH), "Non-protected pattern must still appear");
        } finally {
            try {
                java.nio.file.Files.walk(dir.toPath())
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) { }
                        });
            } catch (Exception ignored) { }
        }
        System.clearProperty(StableMockConfig.PROTECTED_DYNAMIC_FIELDS_PROPERTY);
    }
}
