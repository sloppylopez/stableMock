package com.stablemock.gradle;

import org.gradle.api.Project;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * Extension for configuring StableMock Gradle plugin.
 */
public class StableMockExtension {
    /**
     * Target URLs for recording mode (e.g., ["<a href="https://api.example.com">...</a>",
     * "<a href="https://another-api.com">...</a>"])
     * Can specify multiple URLs - WireMock will proxy requests matching each URL's
     * domain.
     */
    private final ListProperty<String> targetUrls;

    /**
     * Directory where WireMock mappings are stored/loaded
     */
    private final Property<String> mappingsDir;

    /**
     * Optional port number. If not set, will find a free port automatically.
     */
    private final Property<Integer> port;

    public StableMockExtension(Project project) {
        this.targetUrls = project.getObjects().listProperty(String.class);
        this.mappingsDir = project.getObjects().property(String.class);
        this.port = project.getObjects().property(Integer.class);

        // Default mappings directory
        this.mappingsDir.set(project.getProjectDir() + "/src/test/resources/stablemock");
    }

    public ListProperty<String> getTargetUrls() {
        return targetUrls;
    }

    public Property<String> getMappingsDir() {
        return mappingsDir;
    }

    public Property<Integer> getPort() {
        return port;
    }
}
