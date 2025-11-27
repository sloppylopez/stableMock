# StableMock

<div align="center">

[![CI](https://github.com/sloppylopez/stablemock/actions/workflows/ci.yml/badge.svg)](https://github.com/sloppylopez/stablemock/actions/workflows/ci.yml)
[![Version](https://img.shields.io/badge/version-1.0--SNAPSHOT-blue.svg)](https://github.com/sloppylopez/stablemock)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://github.com/sloppylopez/stablemock)

<img src="src/test/resources/images/stableMockLogoTransparent.png" alt="StableMock Logo" width="300" height="300">

## 🌾 "StableMock: Where Your Tests Find Their Rest"

Well howdy there, partner. You remember them dusty days of buildin' mocks by hand — ropin' up JSON files, savin' 'em on disk, and prayin' your transaction IDs didn't go changin' on ya. A bolt of fear withdrew me when them flaky tests came gallopin' through — every test felt like a rodeo, and them wild mustangs you couldn't tame.

Don't chase ghost riders in the sky. Just horseshoe your tests with @U and you're ready to ride.

It spins up its own WireMock corral per test decorated, records them calls, runs the test twice, and ropes down any field that keeps wanderin'. No more patchin' or fiddlin' — your mocks just work. So holster that debug log, take off your hat, and let your tests rest easy.

Old-School Grit. New-School Magic.

</div>

---

A JUnit 5 extension that uses WireMock to record and replay HTTP requests and responses during tests, with support for ignoring dynamic data (timestamps, IDs, tokens).

## Quick Start

### 1. Add Dependency

```gradle
dependencies {
    testImplementation 'com.stablemock:stablemock:1.0-SNAPSHOT'
}
```

### 2. Annotate Your Test

```java
@U(urls = { "https://api.example.com" })
public class MyTest {
    
    @Test
    public void myTest(int port) {
        // Use localhost:port instead of the real URL
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/users"))
                .GET()
                .build();
        
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
    }
}
```

### 3. Record Mode (First Time)

Record HTTP interactions by proxying to the real service:

```bash
./gradlew test "-Dstablemock.mode=RECORD"
```

This saves responses as stub mappings in `src/test/resources/stablemock/<TestClass>/<testMethod>/`.

### 4. Playback Mode (Default)

Run tests using recorded mocks:

```bash
./gradlew test
```

Tests will use the saved mappings instead of calling the real service.

## Spring Boot Integration

When using StableMock with Spring Boot, configure your application to use the dynamic proxy port via `@DynamicPropertySource`.

### Basic Example

```java
@SpringBootTest
@U(urls = { "https://api.thirdparty.com" })
public class MySpringTest {
    
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Supplier reads system property lazily - evaluated when Spring needs the value
        registry.add("app.thirdparty.url", () -> 
            System.getProperty("stablemock.baseUrl", "http://localhost:8080"));
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

2. **@DynamicPropertySource supplier** is evaluated lazily when Spring needs the property value (after StableMock starts)

3. **Your service** reads `app.thirdparty.url` from Spring properties, which now points to WireMock

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

### Handling Dynamic Data

Real-world APIs often include dynamic data like timestamps, request IDs, or session tokens. Use the `ignore` parameter:

```java
@U(urls = { "https://api.example.com" }, 
   ignore = { 
       "json:timestamp",           // Ignore JSON field
       "json:requestId",            // Ignore nested JSON field
       "gql:variables.cursor",      // Ignore GraphQL variable
       "xml://MessageID"            // Ignore XML element
   })
public class MyTest {
    // ...
}
```

### Complete Spring Boot Example

```java
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@U(urls = { "https://jsonplaceholder.typicode.com" },
   ignore = { "json:timestamp" })
class UserServiceTest {

    @Autowired
    private UserService userService;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("app.external.api.url", () -> 
            System.getProperty("stablemock.baseUrl", "https://jsonplaceholder.typicode.com"));
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

This structure supports future extensions for:
- **JSON/XML/GraphQL matching** - Protocol-specific matchers in `core/matching/`
- **gRPC support** - Additional protocol handlers
- **Custom storage backends** - Pluggable storage implementations

## Gradle Plugin Tasks

```bash
# Record mode
./gradlew stableMockRecord

# Playback mode (default)
./gradlew stableMockPlayback

# Clean recordings
./gradlew cleanStableMock
```

## License

MIT License - See LICENSE file for details.