package com.stablemock.core.analysis;

/**
 * Utility class for calculating confidence levels based on sample sizes.
 */
public final class ConfidenceCalculator {

    private ConfidenceCalculator() {
        // utility class
    }

    /**
     * Calculates confidence level based on sample size.
     * 
     * @param sampleSize Number of samples analyzed
     * @return Confidence level: "HIGH" (>=5), "MEDIUM" (>=3), or "LOW" (<3)
     */
    public static String calculateConfidence(int sampleSize) {
        if (sampleSize >= 5) {
            return "HIGH";
        } else if (sampleSize >= 3) {
            return "MEDIUM";
        } else {
            return "LOW";
        }
    }
}
