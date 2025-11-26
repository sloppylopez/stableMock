# Spring Boot Integration Example with Feign Client

This example demonstrates how to integrate StableMock with Spring Boot applications that use **Feign clients** to call third-party APIs. The key challenge solved here is that Feign clients are initialized during Spring context startup, but StableMock starts WireMock in `beforeEach()`, which happens after Spring context initialization.

## The Problem

In Spring Boot applications with Feign clients:
- Your test calls Controller A
- Controller A calls Service A  
- Service A uses a Feign client to call a third-party API (URL from `application.properties`)
- **Challenge**: Feign clients are created during Spring context initialization, but StableMock starts WireMock in `beforeEach()` (after Spring context loads)
- **Previous approaches fail**: `@DynamicPropertySource` runs before `beforeEach()`, so the system property isn't set when Feign client is created

## The Solution: Dynamic URI Parameter

We use **Feign's URI parameter feature** to dynamically override the base URL at runtime:

1. **StableMock sets system properties** when WireMock starts (in `beforeEach()`):
   - `stablemock.port` - The WireMock proxy port
   - `stablemock.baseUrl` - `http://localhost:${stablemock.port}`

2. **Feign client methods accept URI parameter** - An unannotated `URI` parameter allows runtime URL override

3. **Service checks system property** - `ThirdPartyService.getBaseUri()` checks `stablemock.baseUrl` first, then falls back to default

4. **Tests run in parallel** - Each test class gets its own WireMock instance

## Project Structure

```
spring-boot-example/
├── src/
│   ├── main/
│   │   ├── java/example/
│   │   │   ├── SpringBootExampleApplication.java  # Main Spring Boot app with @EnableFeignClients
│   │   │   ├── JsonPlaceholderClient.java          # Feign client interface with URI parameter
│   │   │   ├── ThirdPartyService.java             # Service that checks system property and passes URI
│   │   │   └── UserController.java                # REST controller
│   │   └── resources/
│   │       └── application.properties              # Properties file with third-party URL
│   └── test/
│       └── java/example/
│           ├── SpringBootIntegrationTest.java       # Test class with @U annotation
│           └── ParallelIntegrationTest.java       # Parallel test class
```

## Key Components

### 1. Feign Client Interface (JsonPlaceholderClient.java)

The Feign client methods accept an **unannotated `URI` parameter** as the first parameter. This allows Feign to override the base URL dynamically at runtime:

```java
@FeignClient(name = "jsonPlaceholderClient", url = "${app.thirdparty.url}")
public interface JsonPlaceholderClient {
    
    @GetMapping("/users/{id}")
    String getUser(URI baseUri, @PathVariable int id);
    
    @PostMapping("/posts")
    String createPost(URI baseUri, @RequestBody String body);
}
```

**Key Point**: The `URI baseUri` parameter is unannotated (no `@RequestParam`, `@PathVariable`, etc.). Feign interprets this as a base URL override.

### 2. Service (ThirdPartyService.java)

The service checks the system property set by StableMock and passes the appropriate URI to Feign client methods:

```java
@Service
public class ThirdPartyService {
    private final JsonPlaceholderClient jsonPlaceholderClient;
    
    @Value("${app.thirdparty.url:https://jsonplaceholder.typicode.com}")
    private String defaultThirdPartyUrl;

    public String getUser(int userId) {
        URI baseUri = getBaseUri();
        return jsonPlaceholderClient.getUser(baseUri, userId);
    }
    
    private URI getBaseUri() {
        // Check system property first (set by StableMock in tests), then use default
        String stablemockUrl = System.getProperty("stablemock.baseUrl");
        if (stablemockUrl != null && !stablemockUrl.isEmpty()) {
            return URI.create(stablemockUrl);
        }
        return URI.create(defaultThirdPartyUrl);
    }
}
```

**Key Point**: `getBaseUri()` checks `stablemock.baseUrl` system property first. In tests, StableMock sets this before each test runs, so the service automatically uses WireMock's URL.

### 3. Properties File (application.properties)

```properties
app.thirdparty.url=https://jsonplaceholder.typicode.com
```

This is the default URL used in production. In tests, it's overridden via the system property.

### 4. Test with @U Annotation

```java
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@U(urls = { "https://jsonplaceholder.typicode.com" })
public class SpringBootIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    public void testGetUserViaController(int port) {
        String response = restTemplate.getForObject("/api/users/1", String.class);
        assertNotNull(response);
        assertTrue(response.contains("\"id\":1") || response.contains("\"id\": 1"));
    }
}
```

**Key Points**:
- `@U` annotation triggers StableMock extension
- `@TestInstance(TestInstance.Lifecycle.PER_METHOD)` ensures Spring context is created per test method
- `int port` parameter is injected by StableMock extension (even if unused)

## How It Works

### Execution Flow

1. **Test starts**: JUnit calls `beforeEach()` → StableMock extension starts WireMock
2. **System property set**: StableMock sets `stablemock.baseUrl = http://localhost:{port}` system property
3. **Spring context initializes**: Spring Boot Test initializes application context
   - Feign client is created with default URL from `application.properties`
   - Service beans are created
4. **Test method runs**: 
   - Controller receives request
   - Service calls `getBaseUri()` → checks `stablemock.baseUrl` system property → returns WireMock URL
   - Service calls Feign client with WireMock URI → Feign overrides base URL
   - Request goes to WireMock → WireMock proxies to real API (RECORD) or serves mock (PLAYBACK)

### Why This Works

- **Feign URI parameter**: Feign supports unannotated `URI` parameters to override base URL at runtime
- **System property check**: Service checks system property when method is called (not when bean is created)
- **Timing**: System property is set in `beforeEach()` before test method runs, so it's available when service methods execute

## Running the Example

### Prerequisites

Before running tests, ensure StableMock plugin is published to your local Maven repository:

```bash
# From the project root (K:\dev2\mockero)
./gradlew publishToMavenLocal
```

### First Run (RECORD Mode)

Records HTTP interactions from the real API and saves them as mock files.

**From the example directory:**
```bash
cd examples/spring-boot-example
../../gradlew clean stableMockRecord
```

**From the project root:**
```bash
./gradlew :examples:spring-boot-example:clean :examples:spring-boot-example:stableMockRecord
```

**What it does:**
- Runs all tests in RECORD mode
- Makes real HTTP calls to `https://jsonplaceholder.typicode.com`
- Records request/response pairs
- Saves stub mappings to `src/test/resources/stablemock/`
- Creates separate directories for each test class and method

**Expected output:**
```
> Task :stableMockRecord
SpringBootIntegrationTest > testGetUserViaController(int) PASSED
SpringBootIntegrationTest > testCreatePostViaController(int) PASSED
ParallelIntegrationTest > testGetUserParallel(int) PASSED
ParallelIntegrationTest > testCreatePostParallel(int) PASSED

StableMock: Saved X mappings to .../stablemock/SpringBootIntegrationTest/testGetUserViaController
StableMock: Saved X mappings to .../stablemock/SpringBootIntegrationTest/testCreatePostViaController
...
```

**Note:** Use `clean` to remove old mappings before recording fresh ones. This ensures you get clean recordings without stale data.

### Subsequent Runs (PLAYBACK Mode)

Uses recorded mocks (no real HTTP calls - works offline).

**From the example directory:**
```bash
cd examples/spring-boot-example
../../gradlew stableMockPlayback
```

**From the project root:**
```bash
./gradlew :examples:spring-boot-example:stableMockPlayback
```

**Or use the standard test task:**
```bash
cd examples/spring-boot-example
../../gradlew test
```

**What it does:**
- Runs all tests in PLAYBACK mode (default)
- Uses recorded mocks from `src/test/resources/stablemock/`
- No real HTTP calls - works completely offline
- Faster execution (no network latency)

**Expected output:**
```
> Task :stableMockPlayback
SpringBootIntegrationTest > testGetUserViaController(int) PASSED
SpringBootIntegrationTest > testCreatePostViaController(int) PASSED
ParallelIntegrationTest > testGetUserParallel(int) PASSED
ParallelIntegrationTest > testCreatePostParallel(int) PASSED

4 tests completed, 4 passed
```

### Re-recording After Changes

If you modify your tests or need fresh recordings:

```bash
cd examples/spring-boot-example
# Clean old recordings
../../gradlew clean
# Record fresh mocks
../../gradlew stableMockRecord
```

### System Properties

You can pass system properties to the tasks using `-D` flag:

**Enable debug output (show request matching details):**

**Bash/Linux/Mac:**
```bash
cd examples/spring-boot-example
../../gradlew stableMockRecord -Dstablemock.showMatches=true
../../gradlew stableMockPlayback -Dstablemock.showMatches=true
```

**Windows PowerShell:**
```powershell
cd examples\spring-boot-example
..\..\gradlew stableMockRecord "-Dstablemock.showMatches=true"
# Or use -- to separate Gradle arguments
..\..\gradlew stableMockRecord -- -Dstablemock.showMatches=true
```

**Windows CMD:**
```cmd
cd examples\spring-boot-example
..\..\gradlew stableMockRecord -Dstablemock.showMatches=true
```

**Note:** 
- `stablemock.mode` is automatically set by the tasks (`RECORD` for `stableMockRecord`, `PLAYBACK` for `stableMockPlayback`)
- You don't need to pass `-Dstablemock.mode` - the tasks handle this automatically
- `stablemock.showMatches=true` is useful for debugging when requests don't match expected mocks

**Example with showMatches enabled:**

**PowerShell:**
```powershell
cd examples\spring-boot-example
..\..\gradlew clean stableMockRecord "-Dstablemock.showMatches=true"
```

**Bash:**
```bash
cd examples/spring-boot-example
../../gradlew clean stableMockRecord -Dstablemock.showMatches=true
```

This will show detailed matching information for each request, helpful when troubleshooting why mocks aren't matching.

### Command Reference

| Command | Mode | HTTP Calls | Use Case |
|---------|------|------------|----------|
| `stableMockRecord` | RECORD | ✅ Real API | First run, after test changes |
| `stableMockPlayback` | PLAYBACK | ❌ Mocks only | Normal test runs, CI/CD |
| `test` | PLAYBACK | ❌ Mocks only | Same as playback (default mode) |

**System Properties:**
- `-Dstablemock.showMatches=true` - Enable detailed request matching logs (works with all tasks)
- `stablemock.mode` - Automatically set by tasks (RECORD/PLAYBACK), no need to pass manually
- `-Dstablemock.baseUrl=http://remote-stablemock:8080` - Override WireMock base URL (defaults to `http://localhost:{port}`)

**Windows PowerShell Note:** The commands work the same way. Use `..\..\gradlew` or `../../gradlew` - both work on Windows.

### Parallel Execution

Tests run in parallel by default (configured in `src/test/resources/junit-platform.properties`):

```properties
junit.jupiter.execution.parallel.enabled = true
junit.jupiter.execution.parallel.mode.default = concurrent
junit.jupiter.execution.parallel.mode.classes.default = concurrent
```

Each test class gets its own WireMock instance, so parallel execution works correctly.

## What to Notice

1. **No hardcoded ports**: The service doesn't know about StableMock - it just checks a system property
2. **Dynamic URL override**: Feign client URL is overridden at runtime via URI parameter
3. **Clean separation**: Production code doesn't need to know about testing - it just checks system property
4. **Parallel execution**: Multiple test classes can run simultaneously, each with its own WireMock instance
5. **No @DynamicPropertySource needed**: We bypass Spring's property resolution timing issue by using system properties directly

## Why Not @DynamicPropertySource?

`@DynamicPropertySource` runs **before** Spring context initialization, but StableMock's `beforeEach()` runs **after** Spring context initialization. This timing mismatch means:

- `@DynamicPropertySource` supplier evaluates → `stablemock.baseUrl` is null → uses default URL
- Spring context initializes → Feign client created with default URL
- `beforeEach()` runs → `stablemock.baseUrl` is set (too late!)

By checking the system property directly in the service method (not during bean creation), we avoid this timing issue.

## Additional Test Examples

The project includes additional test classes demonstrating StableMock features:

### AutoIgnoreFeatureTest

Tests demonstrating auto-ignore and manual ignore patterns:

- **Manual ignore patterns**: `@U(ignore = { "json:id", "json:userId" })` - explicitly ignore dynamic fields
- **Auto-detect**: Run tests twice in RECORD mode to see automatic detection of dynamic fields
- **Request verification**: Use `StableMockVerifier` to verify requests were made
- **Multiple requests**: Count and verify multiple requests to the same endpoint

```bash
# Run auto-ignore tests
../../gradlew stableMockRecord --tests AutoIgnoreFeatureTest
```

### AdvancedFeaturesTest

Advanced StableMock features:

- **WireMockServer injection**: Direct access to WireMockServer for advanced operations
- **Custom request patterns**: Verify requests using custom WireMock patterns
- **Find requests**: Search for requests matching specific patterns
- **Error handling**: Verify that failed requests are also recorded

```bash
# Run advanced features tests
../../gradlew stableMockRecord --tests AdvancedFeaturesTest
```

## Troubleshooting

### Feign client still calls real API

**Symptoms**: Tests work but WireMock shows "Saved 0 mappings"

**Solution**: 
1. Verify `getBaseUri()` method checks system property correctly
2. Ensure Feign client methods have `URI baseUri` as first parameter (unannotated)
3. Check that `@U` annotation is present on test class
4. Verify `@TestInstance(TestInstance.Lifecycle.PER_METHOD)` is set

### Port is 0 or property is null

This usually means StableMock hasn't started yet. Make sure:
1. `@U` annotation is present on the test class or method
2. The test method has `int port` parameter (even if you don't use it)
3. StableMock extension is registered (automatic with `@U` annotation)

### Tests fail in parallel

Ensure `@TestInstance(TestInstance.Lifecycle.PER_METHOD)` is set so each test gets its own Spring context and WireMock instance.

## Next Steps

- Try adding more test methods
- Test with dynamic fields using `ignore` patterns
- Experiment with multiple third-party services
- Test with different HTTP methods (PUT, DELETE, etc.)
