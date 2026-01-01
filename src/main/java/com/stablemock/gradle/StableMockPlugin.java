package com.stablemock.gradle;

import com.stablemock.core.config.Constants;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.testing.Test;
import org.jetbrains.annotations.NotNull;

/**
 * Gradle plugin for StableMock that provides test tasks for recording and
 * playback.
 */
@SuppressWarnings("unused")
public class StableMockPlugin implements Plugin<Project> {

    @Override
    public void apply(@NotNull Project project) {
        // Register tasks
        registerCleanStableMockTask(project);
        registerStableMockRecordTask(project);
        registerStableMockPlaybackTask(project);
        registerGenerateReportTask(project);
    }

    private void registerCleanStableMockTask(Project project) {
        project.getTasks().register("cleanStableMock", org.gradle.api.tasks.Delete.class, task -> {
            task.setGroup("verification");
            task.setDescription("Clean StableMock recordings");
            task.delete(project.file("src/test/resources/stablemock"));
        });
    }

    private void registerStableMockRecordTask(Project project) {
        project.getTasks().register("stableMockRecord", Test.class, task -> {
            task.setGroup("verification");
            task.setDescription("Run tests in StableMock RECORD mode (records HTTP interactions)");

            // Clean stablemock recordings before recording new ones
            task.dependsOn("cleanStableMock");

            // Configure to use JUnit Platform
            task.useJUnitPlatform();

            // Set RECORD mode system property
            task.systemProperty("stablemock.mode", Constants.MODE_RECORD);
            task.systemProperty("stablemock.showMatches", System.getProperty("stablemock.showMatches", "false"));
            task.systemProperty("stablemock.debug", System.getProperty("stablemock.debug", "false"));
            task.systemProperty("stablemock.useSharedServer", "true"); // Enable shared server for Spring Boot parallel execution

            // Configure test logging
            task.testLogging(tl -> {
                tl.setEvents(java.util.Arrays.asList("passed", "skipped", "failed", "standardOut", "standardError"));
                tl.setShowStandardStreams(true);
            });

            // Force task to always run (never up-to-date)
            task.getOutputs().upToDateWhen(t -> false);
        });
    }

    private void registerStableMockPlaybackTask(Project project) {
        project.getTasks().register("stableMockPlayback", Test.class, task -> {
            task.setGroup("verification");
            task.setDescription("Run tests in StableMock PLAYBACK mode (uses recorded mocks)");

            // Configure to use JUnit Platform
            task.useJUnitPlatform();

            // Set PLAYBACK mode system property (or leave default)
            task.systemProperty("stablemock.mode", Constants.MODE_PLAYBACK);
            task.systemProperty("stablemock.showMatches", System.getProperty("stablemock.showMatches", "false"));
            task.systemProperty("stablemock.debug", System.getProperty("stablemock.debug", "false"));
            task.systemProperty("stablemock.useSharedServer", "true"); // Enable shared server for Spring Boot parallel execution

            // Configure test logging
            task.testLogging(tl -> {
                tl.setEvents(java.util.Arrays.asList("passed", "skipped", "failed", "standardOut", "standardError"));
                tl.setShowStandardStreams(true);
            });
        });
    }

    private void registerGenerateReportTask(Project project) {
        project.getTasks().register("stableMockReport", GenerateStableMockReportTask.class, task -> {
            task.setGroup("verification");
            task.setDescription("Generate StableMock recording report (JSON and HTML) from existing recordings");
        });
    }
}
