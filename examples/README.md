# StableMock Examples

This directory contains runnable example projects demonstrating StableMock integration with different frameworks.

## Spring Boot Example

### [spring-boot-example](spring-boot-example/README.md)
**Spring Boot integration** - Most common use case for real-world applications.

**Features demonstrated:**
- Integrating StableMock with Spring Boot tests
- Using Feign clients with dynamic URI parameters
- Services reading URLs from `application.properties`
- Automatic proxy URL injection via system properties
- Complete Spring Boot application with controller, service, and tests
- Parallel test execution with isolated WireMock instances
- **Automatic dynamic field detection** - StableMock detects changing fields (timestamps, IDs) automatically
- Manual ignore patterns for JSON, XML, and GraphQL
- Request verification

## Quarkus Example

### [quarkus-example](quarkus-example/README.md)
**Quarkus integration** - Minimal example with 2 tests.

**Features demonstrated:**
- Integrating StableMock with Quarkus tests
- Using MicroProfile REST Client with dynamic base URI
- Services reading URLs from `application.properties`
- Automatic proxy URL injection via system properties

## Running the Examples

### Spring Boot (Gradle)
```bash
cd spring-boot-example
./gradlew stableMockRecord  # First run: record HTTP interactions
./gradlew stableMockPlayback # Subsequent runs: playback from mocks
```

### Quarkus (Gradle)
```bash
cd quarkus-example
./gradlew stableMockRecord  # First run: record HTTP interactions
./gradlew stableMockPlayback # Subsequent runs: playback from mocks
```

## Adding StableMock to Your Project

### Gradle
```groovy
repositories {
    mavenCentral()
}

dependencies {
    testImplementation 'com.stablemock:stablemock:1.1.0'
}
```

**Direct Download:**
You can also download the JAR directly from Maven Central:
- https://repo1.maven.org/maven2/com/stablemock/stablemock/1.1.0/
- https://search.maven.org/artifact/com.stablemock/stablemock/1.1.0/jar

Or build from source using `./gradlew publishToMavenLocal` from the root directory.

## Why These Examples?

Most StableMock features (JSON ignoring, XML patterns, GraphQL variables, request verification, etc.) are comprehensively covered in the unit tests within the main StableMock project. These examples focus on framework-specific integration scenarios.
