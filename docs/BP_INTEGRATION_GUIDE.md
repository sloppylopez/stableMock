# StableMock Integration Guide for BP Project

## Overview

This guide explains how to integrate StableMock in the BP project, specifically addressing the multi-level inheritance scenario and troubleshooting 404 errors in playback mode.

## Problem Statement

When using StableMock with multi-level inheritance (e.g., `GetAvailabilityV2Test` → `BaseTestFeature` → `BaseOpenApiTestFeature` → `BaseStableMockTest`), you may encounter:

- **RECORD mode**: Works correctly - StableMock records requests and creates `detected-fields.json`
- **PLAYBACK mode**: Fails with 404 - WireMock doesn't match requests because dynamic fields have different values than hardcoded values in mapping files

## Root Cause

The issue occurs when:
1. Your test class has multiple levels of inheritance
2. Dynamic fields (like `TransactionIdentifier`, `EchoToken`, `TimeStamp`) change between test runs
3. Ignore patterns from `detected-fields.json` are not being applied during playback

## Integration Steps

### 1. Add StableMock Dependency

In your `build.gradle` or `pom.xml`:

**Gradle:**
```gradle
dependencies {
    testImplementation('com.stablemock:stablemock:1.1.1') {
        exclude group: 'org.slf4j', module: 'slf4j-simple'
    }
}
```

**Maven:**
```xml
<dependency>
    <groupId>com.stablemock</groupId>
    <artifactId>stablemock</artifactId>
    <version>1.1.1</version>
    <scope>test</scope>
</dependency>
```

### 2. Extend BaseStableMockTest

Your base test class should extend `BaseStableMockTest`:

```java
package com.ey.nhhoteles;

import com.stablemock.spring.BaseStableMockTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

public abstract class BaseTestFeature extends BaseOpenApiTestFeature {
    // Your custom test feature code
}

public abstract class BaseOpenApiTestFeature extends BaseStableMockTest {
    // Your OpenAPI test feature code
}
```

### 3. Annotate Your Test Class

Use the `@U` annotation on your test class (not on parent classes):

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@U(urls = {"http://connectivitypre.minor-hotels.com:8000"},
   properties = {"tms4c.endpoint",
                 "tms4c.cloud.avail.endpoint",
                 "tms4c.cloud.booking.endpoint",
                 "tms4c.cloud.read.endpoint",
                 "tms4c.cloud.zavalanendpoint"})
public class GetAvailabilityV2Test extends BaseTestFeature {
    
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        autoRegisterProperties(registry, GetAvailabilityV2Test.class);
    }
    
    // Your test methods
}
```

**Important**: Always put `@U` annotation on the **leaf test class**, not on parent classes. StableMock will traverse the inheritance hierarchy to find it.

### 4. Configure Dynamic Properties

Use `autoRegisterProperties` from `BaseStableMockTest`:

```java
@DynamicPropertySource
static void configureProperties(DynamicPropertyRegistry registry) {
    autoRegisterProperties(registry, YourTestClass.class);
}
```

This automatically maps URLs from `@U` annotations to Spring properties.

## Recording Mode

### First Time Setup

1. **Set mode to RECORD**:
   ```powershell
   $env:stablemock.mode = "RECORD"
   ```

2. **Run your tests**:
   ```powershell
   .\gradlew test -Dstablemock.mode=RECORD
   ```

3. **Verify recordings**:
   - Check `src/test/resources/stablemock/YourTestClass/yourTestMethod/detected-fields.json`
   - Verify it contains ignore patterns for dynamic fields

### Example detected-fields.json

```json
{
  "testClass": "GetAvailabilityV2Test",
  "testMethod": "get_availability_v2_with_fixture_snapshot",
  "ignore_patterns": [
    "xml://*[local-name()='TransactionIdentifier']",
    "xml://*[local-name()='EchoToken']",
    "xml://*[local-name()='TimeStamp']"
  ]
}
```

## Playback Mode

### Running Tests in Playback Mode

1. **Set mode to PLAYBACK** (or omit for default):
   ```powershell
   $env:stablemock.mode = "PLAYBACK"
   # Or simply don't set it (PLAYBACK is default)
   ```

2. **Run your tests**:
   ```powershell
   .\gradlew test -Dstablemock.mode=PLAYBACK
   ```

### Expected Behavior

- StableMock loads `detected-fields.json`
- Applies ignore patterns to WireMock mappings
- Transforms `equalToXml` matchers to use `${xmlunit.ignore}` placeholders
- Requests with different dynamic field values should match

## Troubleshooting 404 Errors in Playback Mode

### Step 1: Verify detected-fields.json Exists

Check that the file exists at:
```
src/test/resources/stablemock/YourTestClass/yourTestMethod/detected-fields.json
```

### Step 2: Verify Ignore Patterns Are Correct

Open `detected-fields.json` and verify it contains patterns for your dynamic fields:

```json
{
  "ignore_patterns": [
    "xml://*[local-name()='TransactionIdentifier']",
    "xml://*[local-name()='EchoToken']",
    "xml://*[local-name()='TimeStamp']"
  ]
}
```

### Step 3: Check WireMock Mappings

Look at the mapping files in:
```
src/test/resources/stablemock/YourTestClass/yourTestMethod/mappings/
```

**Before StableMock applies patterns** (original recording):
```json
{
  "request": {
    "bodyPatterns": [{
      "equalToXml": "<soap:Envelope>...<TransactionIdentifier>1769082706594</TransactionIdentifier>..."
    }]
  }
}
```

**After StableMock applies patterns** (during playback):
```json
{
  "request": {
    "bodyPatterns": [{
      "equalToXml": "<soap:Envelope>...<TransactionIdentifier>${xmlunit.ignore}</TransactionIdentifier>...",
      "enablePlaceholders": true,
      "ignoreWhitespace": true
    }]
  }
}
```

### Step 4: Enable Debug Logging

Add logging to see what StableMock is doing:

**In your test resources, create or update `logback-test.xml`:**

```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <logger name="com.stablemock" level="DEBUG"/>
    <logger name="com.github.tomakehurst.wiremock" level="INFO"/>
    
    <root level="INFO">
        <appender-ref ref="STDOUT" />
    </root>
</configuration>
```

### Step 5: Verify Extension Registration

Check that `StableMockExtension` is being registered. The `@U` annotation automatically registers it via `@ExtendWith(StableMockExtension.class)`.

**Verify in logs:**
```
INFO  com.stablemock.StableMockExtension - Started WireMock server for GetAvailabilityV2Test on port 49588 in PLAYBACK mode
INFO  com.stablemock.core.server.WireMockServerManager - Applying 3 ignore patterns to stub files for GetAvailabilityV2Test.get_availability_v2_with_fixture_snapshot
```

### Step 6: Check Inheritance Hierarchy

Ensure your inheritance chain is correct:

```
YourTestClass
  → BaseTestFeature
    → BaseOpenApiTestFeature
      → BaseStableMockTest
```

**Important**: 
- Put `@U` annotation on `YourTestClass` (leaf class), not on parent classes
- StableMock 1.1.1+ automatically traverses inheritance hierarchy to find `@U` annotations

### Step 7: Verify Test Class Name Resolution

StableMock uses `TestContextResolver.getTestClassName()` which returns the simple class name. Ensure your test class name matches the directory structure:

```
src/test/resources/stablemock/GetAvailabilityV2Test/get_availability_v2_with_fixture_snapshot/
```

### Step 8: Check for Path Preservation Logic

If your `BaseStableMockTest` has custom path preservation logic (different from StableMock examples), ensure it doesn't interfere with StableMock's URL handling.

Compare your `BaseStableMockTest` with the library version:
- Library version: `com.stablemock.spring.BaseStableMockTest`
- Your version: `com.ey.nhhoteles.BaseStableMockTest`

If you have a custom version, ensure it doesn't override critical methods.

## Common Issues and Solutions

### Issue 1: Extension Not Registered

**Symptoms**: No WireMock server starts, no logs from StableMock

**Solution**: 
- Ensure `@U` annotation is on the test class (not parent)
- Verify `@ExtendWith` is not explicitly added (it's auto-added by `@U`)
- Check that Spring Boot Test dependencies are present

### Issue 2: detected-fields.json Not Found

**Symptoms**: Logs show "No detection results found"

**Solution**:
- Run in RECORD mode first to generate the file
- Verify file path matches: `stablemock/{TestClass}/{testMethod}/detected-fields.json`
- Check file permissions

### Issue 3: Ignore Patterns Not Applied

**Symptoms**: 404 errors, mappings still have hardcoded values

**Solution**:
- Verify StableMock version is 1.1.1+ (includes inheritance traversal fix)
- Check logs for "Applying X ignore patterns" message
- Verify `testMethodName` is correctly resolved (not null)
- Ensure patterns match your XML structure (use correct XPath)

### Issue 4: Multiple Inheritance Levels

**Symptoms**: Works in examples but fails in BP project

**Solution**:
- Ensure you're using StableMock 1.1.1+ (fixes inheritance traversal)
- Put `@U` annotation on leaf test class
- Verify `TestContextResolver.findAllUAnnotations` can find the annotation

## Debugging Checklist

- [ ] StableMock version is 1.1.1 or higher
- [ ] `@U` annotation is on the test class (not parent)
- [ ] `detected-fields.json` exists and contains correct patterns
- [ ] WireMock mappings show `${xmlunit.ignore}` placeholders in playback
- [ ] Logs show "Applying X ignore patterns" message
- [ ] Test class name matches directory structure
- [ ] Inheritance chain is correct (extends BaseStableMockTest)
- [ ] `autoRegisterProperties` is called in `@DynamicPropertySource`
- [ ] No custom BaseStableMockTest overriding critical methods

## Example Working Test Structure

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@U(urls = {"https://api.example.com"},
   properties = {"app.api.url"})
public class GetAvailabilityV2Test extends BaseTestFeature {
    
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        autoRegisterProperties(registry, GetAvailabilityV2Test.class);
    }
    
    @Test
    void get_availability_v2_with_fixture_snapshot() {
        // Your test code
        // Dynamic fields (TransactionIdentifier, EchoToken, TimeStamp) 
        // will be automatically ignored in playback mode
    }
}
```

## Getting Help

If you're still experiencing issues:

1. **Check StableMock version**: Ensure you're using 1.1.1+
2. **Enable debug logging**: See Step 4 above
3. **Compare with working example**: See `MultiLevelInheritanceTest` in StableMock examples
4. **Review logs**: Look for "Applying ignore patterns" messages
5. **Verify file paths**: Ensure detected-fields.json is in the correct location

## Related Files

- StableMock Examples: `examples/spring-boot-example/src/test/java/example/inheritance/MultiLevelInheritanceTest.java`
- BaseStableMockTest: `src/main/java/com/stablemock/spring/BaseStableMockTest.java`
- TestContextResolver: `src/main/java/com/stablemock/core/resolver/TestContextResolver.java`
