package com.stablemock.core.analysis;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisResultStorageDontIgnoreTest {

    @Test
    void dontIgnoreEntriesAreRemovedFromEffectiveIgnoreSet() {
        List<String> autoDetected = new ArrayList<>(Arrays.asList(
                "xml://path/to/autoDynamic",
                "xml://path/to/protectedField"
        ));

        List<String> annotationIgnore = List.of(
                "xml://path/to/explicitDynamic"
        );

        List<String> annotationDontIgnore = List.of(
                "xml://path/to/protectedField",
                "xml://path/to/explicitDynamic"
        );

        List<String> effective = new ArrayList<>(autoDetected);
        if (!annotationIgnore.isEmpty()) {
            effective.removeAll(annotationIgnore);
            effective.addAll(annotationIgnore);
        }
        if (!annotationDontIgnore.isEmpty()) {
            effective.removeAll(annotationDontIgnore);
        }

        assertTrue(effective.contains("xml://path/to/autoDynamic"), "unprotected field should remain ignored");
        assertFalse(effective.contains("xml://path/to/protectedField"), "dontIgnore must remove auto-detected pattern");
        assertFalse(effective.contains("xml://path/to/explicitDynamic"), "dontIgnore must remove explicit annotation ignore");
    }
}

