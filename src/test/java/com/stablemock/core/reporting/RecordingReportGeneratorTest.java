package com.stablemock.core.reporting;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for RecordingReportGenerator to verify report generation works correctly.
 */
class RecordingReportGeneratorTest {

    @Test
    void testGenerateReport() {
        // Use the spring-boot-example test resources directory
        File testResourcesDir = new File("examples/spring-boot-example/src/test/resources");
        
        if (!testResourcesDir.exists()) {
            System.out.println("Test resources directory not found, skipping test");
            return;
        }
        
        ObjectNode report = RecordingReportGenerator.generateReport(testResourcesDir, "RecordingReportGeneratorTest");
        
        assertNotNull(report, "Report should be generated");
        assertTrue(report.has("generatedAt"), "Report should have generatedAt timestamp");
        assertTrue(report.has("testClasses"), "Report should have testClasses array");
        
        System.out.println("Report generated successfully!");
        System.out.println("Generated at: " + report.get("generatedAt").asText());
        
        // Save the report
        RecordingReportGenerator.saveReport(report, testResourcesDir);
        
        File reportFile = new File(testResourcesDir, "stablemock/recording-report.json");
        assertTrue(reportFile.exists(), "Report file should be created");
        
        System.out.println("Report saved to: " + reportFile.getAbsolutePath());
    }
}

