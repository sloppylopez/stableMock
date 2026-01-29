package com.stablemock.gradle;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stablemock.core.reporting.RecordingReportGenerator;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;

import java.io.File;

/**
 * Gradle task to generate StableMock recording reports (JSON and HTML).
 */
public class GenerateStableMockReportTask extends DefaultTask {

    private String testResourcesDir = "src/test/resources";

    @Input
    @Option(option = "testResourcesDir", description = "Path to test resources directory")
    public String getTestResourcesDir() {
        return testResourcesDir;
    }

    public void setTestResourcesDir(String testResourcesDir) {
        this.testResourcesDir = testResourcesDir;
    }

    @TaskAction
    public void generateReport() {
        File testResourcesDirFile = getProject().file(testResourcesDir);
        
        if (!testResourcesDirFile.exists()) {
            getLogger().warn("Test resources directory not found: {}", testResourcesDirFile.getAbsolutePath());
            return;
        }

        getLogger().lifecycle("Generating StableMock recording report from: {}", testResourcesDirFile.getAbsolutePath());
        
        ObjectNode report = RecordingReportGenerator.generateReport(testResourcesDirFile, "GradleTask");
        
        // saveReport will handle deleting stale reports if empty
        RecordingReportGenerator.saveReport(report, testResourcesDirFile);
        
        File jsonReport = new File(testResourcesDirFile, "stablemock/recording-report.json");
        File htmlReport = new File(testResourcesDirFile, "stablemock/recording-report.html");
        
        if (jsonReport.exists()) {
            getLogger().lifecycle("Report generated successfully!");
            getLogger().lifecycle("JSON report: {}", jsonReport.getAbsolutePath());
            if (htmlReport.exists()) {
                getLogger().lifecycle("HTML report: {}", htmlReport.getAbsolutePath());
            }
        } else {
            getLogger().lifecycle("No recordings found - no report generated");
        }
    }
}

