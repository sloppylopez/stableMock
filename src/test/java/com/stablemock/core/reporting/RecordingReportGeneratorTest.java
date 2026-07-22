package com.stablemock.core.reporting;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for RecordingReportGenerator to verify report generation works correctly.
 */
class RecordingReportGeneratorTest {

    private static final Logger logger = LoggerFactory.getLogger(RecordingReportGeneratorTest.class);

    @TempDir
    Path tempDir;

    @Test
    void testGenerateReport() throws IOException {
        File testResourcesDir = tempDir.toFile();
        createMinimalRecordingFixture();

        ObjectNode report = RecordingReportGenerator.generateReport(testResourcesDir, "RecordingReportGeneratorTest");

        assertNotNull(report, "Report should be generated");
        assertTrue(report.has("generatedAt"), "Report should have generatedAt timestamp");
        assertTrue(report.has("testClasses"), "Report should have testClasses array");
        assertTrue(report.get("testClasses").size() > 0, "Report should include fixture test class");

        logger.info("Report generated successfully!");
        logger.info("Generated at: {}", report.get("generatedAt").asText());

        RecordingReportGenerator.saveReport(report, testResourcesDir);

        File reportFile = new File(testResourcesDir, "stablemock/recording-report.json");
        assertTrue(reportFile.exists(), "Report file should be created");

        logger.info("Report saved to: {}", reportFile.getAbsolutePath());
    }

    private void createMinimalRecordingFixture() throws IOException {
        Path mappingsDir = tempDir.resolve("stablemock/SampleTest/sampleMethod/mappings");
        Files.createDirectories(mappingsDir);

        String mapping = """
                {
                  "request": {
                    "method": "GET",
                    "url": "/api/users/1"
                  },
                  "response": {
                    "status": 200,
                    "body": "{\\"id\\":1}"
                  }
                }
                """;
        Files.writeString(mappingsDir.resolve("get-sample.json"), mapping);
    }
}
