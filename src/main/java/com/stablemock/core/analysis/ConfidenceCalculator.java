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
     * Since we now clean the analysis directory to ensure accurate detection,
     * even 2 samples are reliable enough to detect dynamic fields.
     * 
     * @param sampleSize Number of samples analyzed
     * @return Confidence level: "HIGH" (>=3), "MEDIUM" (>=2), or "LOW" (<2, rare)
     */
    public static String calculateConfidence(int sampleSize) {
        if (sampleSize >= 3) {
            return "HIGH";
        } else if (sampleSize >= 2) {
            return "MEDIUM";
        } else {
            return "LOW";
        }
    }
}
