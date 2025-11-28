package com.stablemock.core.analysis;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents the result of dynamic field detection analysis.
 * Contains information about which fields were detected as dynamic
 * and the suggested ignore patterns.
 */
public class DetectionResult {

    private final String testClassName;
    private final String testMethodName;
    private final String detectedAt;
    private final int analyzedRequestsCount;
    private final List<DynamicField> dynamicFields;
    private final List<String> ignorePatterns;

    public DetectionResult(String testClassName, String testMethodName,
            int analyzedRequestsCount) {
        this.testClassName = testClassName;
        this.testMethodName = testMethodName;
        this.detectedAt = java.time.Instant.now().toString();
        this.analyzedRequestsCount = analyzedRequestsCount;
        this.dynamicFields = new ArrayList<>();
        this.ignorePatterns = new ArrayList<>();
    }

    public void addDynamicField(DynamicField field) {
        this.dynamicFields.add(field);
    }

    public void addIgnorePattern(String pattern) {
        this.ignorePatterns.add(pattern);
    }

    public String getTestClassName() {
        return testClassName;
    }

    public String getTestMethodName() {
        return testMethodName;
    }

    public String getDetectedAt() {
        return detectedAt;
    }

    public int getAnalyzedRequestsCount() {
        return analyzedRequestsCount;
    }

    public List<DynamicField> getDynamicFields() {
        return dynamicFields;
    }

    public List<String> getIgnorePatterns() {
        return ignorePatterns;
    }

    /**
     * Represents a single dynamic field that was detected.
     */
    public static class DynamicField {
        private final String fieldPath;
        private final String confidence;
        private final List<String> sampleValues;

        public DynamicField(String fieldPath, String confidence, List<String> sampleValues) {
            this.fieldPath = fieldPath;
            this.confidence = confidence;
            this.sampleValues = sampleValues;
        }

        public String getFieldPath() {
            return fieldPath;
        }

        public String getConfidence() {
            return confidence;
        }

        public List<String> getSampleValues() {
            return sampleValues;
        }
    }
}
