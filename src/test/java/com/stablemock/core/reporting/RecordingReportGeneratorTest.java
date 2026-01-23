package com.stablemock.core.reporting;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for RecordingReportGenerator to verify report generation works correctly.
 */
class RecordingReportGeneratorTest {

    private static final Logger logger = LoggerFactory.getLogger(RecordingReportGeneratorTest.class);

    @Test
    void testGenerateReport() {
        // Try to find test resources directory - check multiple possible locations
        File testResourcesDir = findTestResourcesDirectory();
        
        Assumptions.assumeTrue(testResourcesDir != null && testResourcesDir.exists(), 
                "Test resources directory not found, skipping test");
        
        ObjectNode report = RecordingReportGenerator.generateReport(testResourcesDir, "RecordingReportGeneratorTest");
        
        assertNotNull(report, "Report should be generated");
        assertTrue(report.has("generatedAt"), "Report should have generatedAt timestamp");
        assertTrue(report.has("testClasses"), "Report should have testClasses array");
        
        logger.info("Report generated successfully!");
        logger.info("Generated at: {}", report.get("generatedAt").asText());
        
        // Save the report
        RecordingReportGenerator.saveReport(report, testResourcesDir);
        
        File reportFile = new File(testResourcesDir, "stablemock/recording-report.json");
        assertTrue(reportFile.exists(), "Report file should be created");
        
        logger.info("Report saved to: {}", reportFile.getAbsolutePath());
    }

    private File findTestResourcesDirectory() {
        // Try multiple possible locations
        String[] possiblePaths = {
            "examples/spring-boot-example/src/test/resources",
            "src/test/resources",
            "../examples/spring-boot-example/src/test/resources"
        };
        
        for (String path : possiblePaths) {
            File dir = new File(path);
            if (dir.exists() && dir.isDirectory()) {
                return dir;
            }
        }
        
        return null;
    }
}

