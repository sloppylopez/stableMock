package com.stablemock.core.analysis;

/**
 * Markers used to identify scoped-request identity/plan patterns that must not be
 * applied as ignore patterns at playback so different callers and plan types stay distinguishable.
 * Production uses default (real payload names); tests use anonymized markers so test code
 * does not reference any specific consumer.
 */
public final class IdentityFilterMarkers {

    private final String rootMarker;
    private final String callerPathMarker;
    private final String callerIdAttr;
    private final String callerContextAttr;
    private final String callerTypeAttr;
    private final String planContainerMarker;
    private final String planCodeAttr;
    private final String planIdAttr;

    private IdentityFilterMarkers(String rootMarker, String callerPathMarker,
            String callerIdAttr, String callerContextAttr, String callerTypeAttr,
            String planContainerMarker, String planCodeAttr, String planIdAttr) {
        this.rootMarker = rootMarker;
        this.callerPathMarker = callerPathMarker;
        this.callerIdAttr = callerIdAttr;
        this.callerContextAttr = callerContextAttr;
        this.callerTypeAttr = callerTypeAttr;
        this.planContainerMarker = planContainerMarker;
        this.planCodeAttr = planCodeAttr;
        this.planIdAttr = planIdAttr;
    }

    /** Production: matches real payload element/path names used by existing consumers. */
    public static IdentityFilterMarkers getDefault() {
        return new IdentityFilterMarkers(
                "ota_hotelavailrq",
                "requestorid",
                "'id']", "'id_context']", "'type']",
                "rateplancandidates",
                "rateplancode']", "rateplanid']");
    }

    /** Unit tests: anonymized markers so tests do not reference consumer-specific names. */
    public static IdentityFilterMarkers forAnonymizedTest() {
        return new IdentityFilterMarkers(
                "scopedreq",
                "callerid",
                "'id']", "'id_context']", "'type']",
                "plancandidates",
                "plancode']", "planid']");
    }

    /** True if pattern is for the scoped-request root we special-case. */
    public boolean isScopedRequestPattern(String patternLower) {
        return patternLower != null && patternLower.contains(rootMarker);
    }

    /** True if pattern is caller-identity (must not be ignored at playback). */
    public boolean isCallerIdentityPattern(String patternLower) {
        if (patternLower == null || !patternLower.contains(callerPathMarker)) {
            return false;
        }
        return patternLower.contains(callerIdAttr) || patternLower.contains(callerContextAttr) || patternLower.contains(callerTypeAttr);
    }

    /** True if pattern is plan-selector (must not be ignored at playback). */
    public boolean isPlanSelectorPattern(String patternLower) {
        if (patternLower == null || !patternLower.contains(planContainerMarker)) {
            return false;
        }
        return patternLower.contains(planCodeAttr) || patternLower.contains(planIdAttr);
    }

    /** True if path is caller-identity (parser path format, e.g. caller_id@id). */
    public boolean isCallerIdentityPath(String pathLower) {
        if (pathLower == null || !pathLower.contains(rootMarker) || !pathLower.contains(callerPathMarker)) {
            return false;
        }
        return pathLower.contains("@id") || pathLower.contains("@id_context") || pathLower.contains("@type");
    }

    /** True if path is plan-selector (parser path format; supports both prod and anonymized attr names). */
    public boolean isPlanSelectorPath(String pathLower) {
        if (pathLower == null || !pathLower.contains(rootMarker) || !pathLower.contains(planContainerMarker)) {
            return false;
        }
        return pathLower.contains("@rateplancode") || pathLower.contains("@rateplanid")
                || pathLower.contains("@plan_code") || pathLower.contains("@plan_id")
                || pathLower.contains("@plancode") || pathLower.contains("@planid");
    }
}
