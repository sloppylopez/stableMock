# StableMock

<div align="center">

[![CI](https://github.com/sloppylopez/stablemock/actions/workflows/ci.yml/badge.svg)](https://github.com/sloppylopez/stablemock/actions/workflows/ci.yml)
[![Version](https://img.shields.io/badge/version-1.0--SNAPSHOT-blue.svg)](https://github.com/sloppylopez/stablemock)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://github.com/sloppylopez/stablemock)

<img src="src/test/resources/images/stablemock-logo-transparent-outline.png" alt="StableMock Logo" width="300" height="300">

</div>

---

**StableMock**

A JUnit 5 extension for zero-config HTTP mocking that auto-records external APIs to WireMock stubs.

Stop hand-writing mocks for flaky external APIs. StableMock is a JUnit 5 extension that records real HTTP calls during your tests, automatically converts them to WireMock stubs, and replays them reliably — even when request data changes. Perfect for offline integration tests that mock external APIs without configuration.

Built for JUnit 5. Works offline. Free & open source.

## Quick Start

### 1. Add Dependency

**Note:** StableMock is currently in active development and testing. The jar has not been deployed to Maven Central yet. We are ensuring everything works correctly and fixing bugs before the initial release. For now, you'll need to build from source or use a local installation.

**Gradle:**
```gradle
dependencies {
    testImplementation 'com.stablemock:stablemock:1.0-SNAPSHOT'
}
```

**Maven:**
```xml
<dependencies>
    <dependency>
        <groupId>com.stablemock</groupId>
        <artifactId>stablemock</artifactId>
        <version>1.0-SNAPSHOT</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

### 2. Record Mode (First Time)

Record HTTP interactions by proxying to the real service:

```bash
./gradlew test "-Dstablemock.mode=RECORD"
```

This automatically generates WireMock stubs and saves them as stub mappings in `src/test/resources/stablemock/<TestClass>/<testMethod>/`.

### 3. Playback Mode (Default)

Run offline integration tests using recorded WireMock stubs:

```bash
./gradlew test
```

Tests will use the saved WireMock stubs instead of calling the real service, enabling fast, reliable offline integration tests that mock external APIs.

## Pure JUnit 5 Usage (Non-Spring)

StableMock works as a general JUnit 5 extension without requiring Spring Boot. For pure JUnit tests, you access WireMock URLs via system properties.

### Minimal Example

```java
import com.stablemock.U;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@U(urls = { "https://api.example.com" })
class MyPureJUnitTest {

    @Test
    void testApiCall(int port) {
        // Port is injected as a parameter, or get base URL from system property
        String baseUrl = System.getProperty("stablemock.baseUrl");
        
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/endpoint"))
                .GET()
                .build();
        
        HttpResponse<String> response = client.send(request, 
                HttpResponse.BodyHandlers.ofString());
        
        assertEquals(200, response.statusCode());
    }
}
```

### How It Works

1. **StableMock starts WireMock** in `beforeEach()` and sets:
   - `stablemock.port` - The WireMock proxy port
   - `stablemock.baseUrl` - `http://localhost:${stablemock.port}`

2. **Test method receives port** as a parameter (optional)

3. **Your code** reads `stablemock.baseUrl` from system properties or uses the injected port

**Note:** The `properties` attribute is optional for non-Spring Boot tests. It's only needed when using Spring Boot with `autoRegisterProperties()`.

## Spring Boot Integration

When using StableMock with Spring Boot, configure your application to use the dynamic proxy port via `@DynamicPropertySource`.

### The `properties` Attribute

The `@U` annotation includes a `properties` attribute that maps URLs to Spring property names. This is **required when using Spring Boot tests** with `autoRegisterProperties()`.

**How it works:**
- The `properties` array must match the order of URLs in the `urls` array
- First property maps to first URL, second property to second URL, etc.
- When using `autoRegisterProperties()`, these properties are automatically registered with WireMock URLs

**Example:**
```java
@U(urls = { "https://api1.com", "https://api2.com" },
   properties = { "app.api1.url", "app.api2.url" })
```

**Note:** The `properties` attribute is optional for non-Spring Boot tests, but **required** when using Spring Boot with `autoRegisterProperties()`.

### Basic Example

```java
@SpringBootTest
@U(urls = { "https://api.thirdparty.com" },
   properties = { "app.thirdparty.url" })
public class MySpringTest extends BaseStableMockTest {
    
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        autoRegisterProperties(registry, MySpringTest.class);
    }
    
    @Autowired
    private MyService myService;
    
    @Test
    public void testService(int port) {
        // Test your service - it will call localhost:port instead of real API
        myService.doSomething();
    }
}
```

### How It Works

1. **StableMock starts WireMock** in `beforeAll()` (for Spring Boot tests) and sets:
   - `stablemock.port` - The WireMock proxy port
   - `stablemock.baseUrl` - `http://localhost:${stablemock.port}`

2. **`autoRegisterProperties()`** automatically registers properties from `@U` annotations, reading WireMock URLs from ThreadLocal (set after WireMock starts)

3. **@DynamicPropertySource supplier** is evaluated lazily when Spring needs the property value (after StableMock starts)

4. **Your service** reads `app.thirdparty.url` from Spring properties, which now points to WireMock

**Note:** Extend `BaseStableMockTest` to use `autoRegisterProperties()`, which automatically maps URLs from `@U` annotations to Spring properties. This eliminates the need to manually register each property.

### Service Configuration Example

Your `application.properties`:
```properties
app.thirdparty.url=https://api.thirdparty.com
```

Your service:
```java
@Service
public class MyService {
    @Value("${app.thirdparty.url}")
    private String thirdPartyUrl;
    
    public void doSomething() {
        restTemplate.getForObject(thirdPartyUrl + "/endpoint", String.class);
    }
}
```

In tests, `@DynamicPropertySource` overrides this to point to StableMock's proxy.

### Multiple URLs in Single Annotation

You can specify multiple URLs in a single `@U` annotation:

```java
@SpringBootTest
@U(urls = { 
    "https://api.example.com",
    "https://api.another-service.com"
},
   properties = {
    "app.example.url",
    "app.another-service.url"
})
public class MyTest extends BaseStableMockTest {
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        autoRegisterProperties(registry, MyTest.class);
    }
    // ...
}
```

When using multiple URLs, StableMock creates separate WireMock servers for each URL. System properties are set for each URL:
- `stablemock.baseUrl.0` - First URL's WireMock base URL
- `stablemock.baseUrl.1` - Second URL's WireMock base URL
- `stablemock.port.0` - First URL's WireMock port
- `stablemock.port.1` - Second URL's WireMock port

**Note:** When using Spring Boot, you must provide a `properties` array matching the order of URLs.

### Multiple @U Annotations

The `@U` annotation is `@Repeatable`, allowing you to use multiple annotations on the same test class or method:

```java
@SpringBootTest
@U(urls = { "https://api.service1.com" },
   properties = { "app.service1.url" })
@U(urls = { "https://api.service2.com" },
   properties = { "app.service2.url" })
public class MyMultiServiceTest extends BaseStableMockTest {
    
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        autoRegisterProperties(registry, MyMultiServiceTest.class);
    }
    
    @Test
    void testMultipleServices(int port) {
        // port parameter returns the first server's port
        // Use system properties for other servers
        String port1 = System.getProperty("stablemock.port.0");
        String port2 = System.getProperty("stablemock.port.1");
        // ...
    }
}
```

Each annotation gets its own WireMock server instance, allowing you to mock multiple services independently.

### Scenario Mode

Enable scenario mode for sequential responses when the same request should return different responses over time (useful for pagination, polling, or retry logic):

```java
@SpringBootTest
@U(urls = { "https://api.example.com" },
   properties = { "app.example.url" },
   scenario = true)
public class PaginationTest extends BaseStableMockTest {
    
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        autoRegisterProperties(registry, PaginationTest.class);
    }
    
    @Test
    void testPagination(int port) {
        // First call returns page 1
        // Second call returns page 2
        // Third call returns empty result
    }
}
```

When `scenario = true`, StableMock uses WireMock scenarios to return responses sequentially for the same endpoint.

### Handling Dynamic Data

Real-world APIs often include dynamic data like timestamps, request IDs, or session tokens. StableMock provides two ways to handle this:

#### 1. Auto-Detection (Recommended)

StableMock automatically detects changing fields by comparing requests across multiple test runs. **This feature is enabled by default** and requires no configuration.

**How it works:**
1. During recording, StableMock tracks request bodies for each test method
2. After multiple runs, it compares requests to identify fields that change between runs
3. Detected fields are automatically ignored using WireMock 3's `${json-unit.ignore}` placeholders
4. Results are saved to `.stablemock-analysis/<TestClass>/<testMethod>/detected-fields.json`

**Example:**
```java
@SpringBootTest
@U(urls = { "https://api.example.com" },
   properties = { "app.example.url" })
public class MyTest extends BaseStableMockTest {
    
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        autoRegisterProperties(registry, MyTest.class);
    }
    
    @Test
    void testCreatePost() {
        // First run: Records request with timestamp="2025-01-01T10:00:00Z"
        // Second run: Records request with timestamp="2025-01-01T10:00:01Z"
        // StableMock automatically detects "timestamp" as a dynamic field
        // Third run: Playback works even with different timestamp values
    }
}
```

**Detection results:**
After running tests multiple times, check `.stablemock-analysis/<TestClass>/<testMethod>/detected-fields.json`:
```json
{
  "testClass": "MyTest",
  "testMethod": "testCreatePost",
  "analyzed_requests_count": 4,
  "dynamic_fields": [
    {
      "field_path": "timestamp",
      "sample_values": ["2025-01-01T10:00:00Z", "2025-01-01T10:00:01Z", "2025-01-01T10:00:02Z"]
    }
  ],
  "ignore_patterns": ["json:timestamp"]
}
```

#### 2. Manual Ignore Patterns

You can also manually specify fields to ignore using the `ignore` parameter:

```java
@SpringBootTest
@U(urls = { "https://api.example.com" },
   properties = { "app.example.url" },
   ignore = { 
       "json:timestamp",           // Ignore JSON field
       "json:requestId",            // Ignore nested JSON field
       "json:metadata.requestId",   // Ignore nested JSON field with dot notation
       "gql:variables.cursor",      // Ignore GraphQL variable
       "xml://*[local-name()='MessageID']"  // Ignore XML element (XPath)
   })
public class MyTest extends BaseStableMockTest {
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        autoRegisterProperties(registry, MyTest.class);
    }
    // ...
}
```

**Pattern syntax:**
- **JSON**: `"json:fieldName"` or `"json:nested.field"` - Uses WireMock 3's `${json-unit.ignore}` placeholder
- **GraphQL**: `"gql:variables.fieldName"` or `"graphql:variables.fieldName"`
- **XML**: `"xml://XPathExpression"` - Uses WireMock 3's `${xmlunit.ignore}` placeholder

**Note:** Auto-detection and manual ignore patterns work together. Manual patterns are always applied, and auto-detected patterns are added automatically.

### Complete Spring Boot Example

```java
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@U(urls = { "https://jsonplaceholder.typicode.com" },
   properties = { "app.external.api.url" },
   ignore = { "json:timestamp" })
class UserServiceTest extends BaseStableMockTest {

    @Autowired
    private UserService userService;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        autoRegisterProperties(registry, UserServiceTest.class);
    }

    @Test
    void testGetUser(int port) {
        User user = userService.getUser(1);
        assertNotNull(user);
        assertEquals(1, user.getId());
    }
}
```

## Architecture

StableMock is organized into modular packages for maintainability and extensibility:

```
com.stablemock/
├── core/
│   ├── config/          # Configuration constants and utilities
│   ├── context/         # ExtensionContext store management
│   ├── resolver/        # Test context resolution (Spring Boot detection, etc.)
│   ├── server/          # WireMock server lifecycle management
│   └── storage/          # Mapping file operations (save/load/merge)
├── gradle/              # Gradle plugin classes
├── StableMockExtension.java  # Main JUnit extension (orchestrator)
├── U.java               # Annotation
└── WireMockContext.java # Thread-local context
```

## Gradle Plugin Tasks

```bash
# Record mode
./gradlew stableMockRecord

# Playback mode (default)
./gradlew stableMockPlayback

# Clean recordings
./gradlew cleanStableMock

# Generate recording report
./gradlew generateStableMockReport
```

## Recording Report

StableMock automatically generates a comprehensive report after tests run in RECORD mode. The report provides insights into your recorded requests, detected dynamic fields, and generated ignore patterns.

### Automatic Generation

The report is automatically generated after each test run in RECORD mode and saved to:
- `src/test/resources/stablemock/recording-report.json` - Machine-readable JSON format
- `src/test/resources/stablemock/recording-report.html` - Human-readable HTML format

### Report Contents

The report includes:
- **Test Classes & Methods**: All recorded test classes and their methods
- **Request Information**: HTTP method, URL, request count per endpoint
- **Detected Dynamic Fields**: Fields automatically detected as changing between test runs
- **Ignore Patterns**: Generated patterns used to ignore dynamic data
- **Mutating Fields**: Fields that change values, mapped to specific endpoints

### Manual Generation

You can also generate the report manually using the Gradle task:

```bash
./gradlew generateStableMockReport
```

This is useful when you want to regenerate the report without running tests, or to generate reports for existing recordings.

### Viewing the Report

Open `src/test/resources/stablemock/recording-report.html` in your browser to view a formatted, interactive report of all your recordings. The HTML report provides a clear overview of:
- Which endpoints were recorded
- How many times each endpoint was called
- Which fields were detected as dynamic
- The ignore patterns being used

The JSON report (`recording-report.json`) can be used for programmatic analysis or integration with other tools.

## System Properties Reference

StableMock sets the following system properties that you can use in your tests:

### Single URL / Single Annotation
- `stablemock.port` - WireMock proxy port
- `stablemock.baseUrl` - `http://localhost:${stablemock.port}`

### Multiple URLs / Multiple Annotations
- `stablemock.port.0` - First URL's WireMock port
- `stablemock.port.1` - Second URL's WireMock port
- `stablemock.baseUrl.0` - First URL's WireMock base URL
- `stablemock.baseUrl.1` - Second URL's WireMock base URL
- (continues for additional URLs/annotations)

### Configuration Properties
- `stablemock.mode` - Set to `RECORD` or `PLAYBACK` (automatically set by Gradle tasks)
- `stablemock.showMatches` - Set to `true` to enable detailed request matching logs for debugging

## Debugging and Troubleshooting

### Enable Request Matching Logs

When requests don't match expected mocks, enable detailed matching information:

```bash
# PowerShell
./gradlew stableMockRecord "-Dstablemock.showMatches=true"

# Bash/Linux/Mac
./gradlew stableMockRecord -Dstablemock.showMatches=true
```

This will show detailed matching information for each request, helpful when troubleshooting why mocks aren't matching.

### Common Issues

**Issue: Tests fail with "No matching stub mapping found"**
- Ensure you've run in RECORD mode first: `./gradlew stableMockRecord`
- Check that the request URL and method match what was recorded
- Enable `stablemock.showMatches=true` to see matching details

**Issue: Dynamic fields causing test failures**
- Add fields to the `ignore` parameter in your `@U` annotation
- Use JSON path syntax: `"json:fieldName"` or `"json:nested.field"`
- For GraphQL: `"gql:variables.fieldName"`
- For XML: `"xml://XPathExpression"`

**Issue: Multiple annotations not working**
- Ensure you're using `@U` multiple times (not `@U.List`)
- Check system properties: `stablemock.baseUrl.0`, `stablemock.baseUrl.1`, etc.
- Verify `@DynamicPropertySource` uses the correct index for each service

## License

MIT License - See LICENSE file for details.

## Disclaimer

**NO WARRANTIES**

This software is provided "as is" without warranty of any kind, express or implied, including but not limited to warranties of merchantability, fitness for a particular purpose, non-infringement, or that the software will meet your requirements or operate without interruption or error. The authors and contributors of StableMock make no representations or warranties regarding the accuracy, reliability, completeness, or suitability of this software for any purpose.

**LIMITATION OF LIABILITY**

To the fullest extent permitted by law, the authors, contributors, and maintainers of StableMock shall not be liable for any direct, indirect, incidental, special, consequential, or punitive damages, or any loss of profits, revenue, data, use, goodwill, or other intangible losses, resulting from:
- Your use or inability to use StableMock
- Any unauthorized access to or use of your servers, systems, or data
- Any bugs, errors, or defects in the software
- Any interruption or cessation of transmission to or from the software
- Any conduct or content of third parties using the software
- Any other matter relating to the software

**USER RESPONSIBILITY**

You are solely responsible for:
- Ensuring that your use of StableMock complies with all applicable local, state, national, and international laws and regulations
- Complying with the terms of service, privacy policies, and acceptable use policies of any third-party services, APIs, or systems you interact with through StableMock
- Obtaining all necessary permissions, licenses, and authorizations before using StableMock to interact with third-party services
- Protecting your systems, data, and credentials from unauthorized access
- Verifying the accuracy and appropriateness of any recorded mock data before using it in production or production-like environments
- Ensuring that your use of StableMock does not violate any intellectual property rights, privacy rights, or other rights of third parties

**THIRD-PARTY SERVICES**

StableMock may be used to record and replay interactions with third-party services. The authors and contributors are not responsible for:
- The availability, accuracy, or reliability of any third-party services
- Any changes to third-party APIs that may affect the functionality of recorded mocks
- Any violations of third-party terms of service that may occur through the use of StableMock
- Any data breaches, security incidents, or unauthorized access that may occur when interacting with third-party services

**SECURITY AND DATA PROTECTION**

You acknowledge that:
- StableMock records HTTP requests and responses, which may contain sensitive, confidential, or personal information
- You are responsible for securing any recorded mock data and ensuring it is stored and handled in compliance with applicable data protection laws (such as GDPR, CCPA, etc.)
- You should not commit sensitive data, credentials, or personal information to version control systems
- The authors are not responsible for any data breaches, unauthorized access, or mishandling of data recorded or stored through the use of StableMock

**TESTING AND PRODUCTION USE**

StableMock is intended for testing purposes. While it may be used in various environments, you acknowledge that:
- The software is provided without guarantees of production-readiness or suitability for critical systems
- You should thoroughly test and validate any implementation using StableMock before deploying to production
- The authors are not responsible for any production incidents, downtime, or failures that may result from the use of StableMock

**MODIFICATIONS AND DISTRIBUTION**

If you modify or distribute StableMock, you acknowledge that:
- You do so at your own risk
- The original authors and contributors are not responsible for any issues arising from modified versions
- You must comply with the MIT License terms when distributing modified versions

**NO ENDORSEMENT**

The use of StableMock does not constitute an endorsement by the authors of any third-party services, APIs, or systems that you may interact with through the software.

**GENERAL**

This disclaimer applies to the fullest extent permitted by law. If any portion of this disclaimer is found to be unenforceable, the remaining portions shall remain in full force and effect. By using StableMock, you acknowledge that you have read, understood, and agree to be bound by this disclaimer.