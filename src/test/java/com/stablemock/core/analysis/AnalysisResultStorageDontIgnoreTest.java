package com.stablemock.core.analysis;

import com.stablemock.core.server.WireMockServerManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisResultStorageDontIgnoreTest {

    /**
     * Delegates to the production {@code WireMockServerManager.applyDontIgnorePatterns} method
     * via reflection so the tests exercise real runtime logic.
     */
    @SuppressWarnings("unchecked")
    private List<String> applyDontIgnore(List<String> ignorePatterns, List<String> dontIgnorePatterns) throws Exception {
        Method m = WireMockServerManager.class.getDeclaredMethod(
                "applyDontIgnorePatterns", List.class, List.class);
        m.setAccessible(true);
        return (List<String>) m.invoke(null, ignorePatterns, dontIgnorePatterns);
    }

    @Test
    void dontIgnoreRemovesAutoDetectedXmlPattern() throws Exception {
        List<String> ignorePatterns = Arrays.asList(
                "xml://*[local-name()='autoDynamic']",
                "xml://*[local-name()='protectedField']"
        );
        List<String> dontIgnorePatterns = List.of("xml://*[local-name()='protectedField']");

        List<String> effective = applyDontIgnore(ignorePatterns, dontIgnorePatterns);

        assertTrue(effective.contains("xml://*[local-name()='autoDynamic']"),
                "unprotected field should remain ignored");
        assertFalse(effective.contains("xml://*[local-name()='protectedField']"),
                "dontIgnore must remove auto-detected XML pattern");
    }

    @Test
    void dontIgnoreRemovesExplicitAnnotationIgnoreJsonPattern() throws Exception {
        List<String> ignorePatterns = Arrays.asList(
                "xml://*[local-name()='autoDynamic']",
                "json:explicitDynamic"
        );
        List<String> dontIgnorePatterns = List.of("json:explicitDynamic");

        List<String> effective = applyDontIgnore(ignorePatterns, dontIgnorePatterns);

        assertTrue(effective.contains("xml://*[local-name()='autoDynamic']"),
                "unprotected XML field should remain ignored");
        assertFalse(effective.contains("json:explicitDynamic"),
                "dontIgnore must remove explicit annotation ignore JSON pattern");
    }

    @Test
    void dontIgnoreGqlPatternNormalizesToJsonAndRemovesMatchingIgnore() throws Exception {
        List<String> ignorePatterns = List.of("json:variables.cursor");
        List<String> dontIgnorePatterns = List.of("gql:variables.cursor");

        List<String> effective = applyDontIgnore(ignorePatterns, dontIgnorePatterns);

        assertFalse(effective.contains("json:variables.cursor"),
                "gql: dontIgnore entry must be normalized to json: and remove the matching ignore pattern");
    }

    @Test
    void emptyDontIgnoreReturnsPatternsUnchanged() throws Exception {
        List<String> ignorePatterns = List.of("json:timestamp", "xml://*[local-name()='id']");
        List<String> dontIgnorePatterns = List.of();

        List<String> effective = applyDontIgnore(ignorePatterns, dontIgnorePatterns);

        assertEquals(ignorePatterns, effective, "empty dontIgnore must leave ignore patterns unchanged");
    }
}

