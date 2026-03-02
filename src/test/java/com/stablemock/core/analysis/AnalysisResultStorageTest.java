package com.stablemock.core.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ensures scoped-request identity/plan filter is applied at load time so different
 * callers and plan types stay distinguishable at playback (no consumer-specific names).
 */
class AnalysisResultStorageTest {

    private static final String PATTERN_CALLER_ID = "xml://*[local-name()='Envelope']/*[local-name()='Body']/*[local-name()='ScopedReq']/*[local-name()='POS']/*[local-name()='Source']/*[local-name()='CallerId']/@*[local-name()='ID']";
    private static final String PATTERN_PLAN_CODE = "xml://*[local-name()='Envelope']/*[local-name()='Body']/*[local-name()='ScopedReq']/*[local-name()='Segments']/*[local-name()='Segment']/*[local-name()='Criteria']/*[local-name()='Criterion']/*[local-name()='PlanCandidates']/*[local-name()='PlanCandidate']/@*[local-name()='PlanCode']";
    private static final String PATTERN_DATE_START = "xml://*[local-name()='Envelope']/*[local-name()='Body']/*[local-name()='ScopedReq']/*[local-name()='Segments']/*[local-name()='Segment']/*[local-name()='Criteria']/*[local-name()='Criterion']/*[local-name()='DateRange']/@*[local-name()='Start']";

    @Test
    void loadIgnorePatterns_filtersOutScopedRequestIdentityAndPlan_keepsDatePatterns(@TempDir Path tempDir) throws Exception {
        Path stablemock = tempDir.resolve("stablemock").resolve("SomeIT").resolve("someMethod__i0");
        Files.createDirectories(stablemock);
        String json = """
            {
              "testClass": "SomeIT",
              "testMethod": "someMethod__i0",
              "ignore_patterns": [
                "%s",
                "%s",
                "%s"
              ]
            }
            """
            .formatted(PATTERN_CALLER_ID, PATTERN_PLAN_CODE, PATTERN_DATE_START);
        Files.writeString(stablemock.resolve("detected-fields.json"), json);

        File testResourcesDir = tempDir.toFile();
        List<String> patterns = AnalysisResultStorage.loadIgnorePatterns(
                testResourcesDir, "SomeIT", "someMethod__i0", null, IdentityFilterMarkers.forAnonymizedTest());

        assertFalse(patterns.contains(PATTERN_CALLER_ID), "Caller ID must be filtered out for scoped request");
        assertFalse(patterns.contains(PATTERN_PLAN_CODE), "Plan code must be filtered out for scoped request");
        assertTrue(patterns.contains(PATTERN_DATE_START), "Date range Start must remain so playback ignores dates");
        assertEquals(1, patterns.size());
    }
}
