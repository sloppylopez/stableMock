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

A JUnit 5 extension that uses WireMock to record and replay HTTP requests and responses during tests, with powerful support for ignoring dynamic data.

## Table of Contents

- [Why StableMock?](#why-stablemock)
- [Quick Start](#quick-start)
- [Handling Dynamic Data](#handling-dynamic-data-with-ignore-patterns)
- [Use Cases](#use-cases)
- [Gradle Plugin Tasks](#gradle-plugin-tasks)
- [Parallel Execution](#parallel-execution)
- [Debugging with Match Diffs](#debugging-with-match-diffs)
- [Request Verification](#request-verification)
- [Advanced Configuration](#advanced-configuration)
- [Troubleshooting](#troubleshooting)
- [License](#license)

## Why StableMock?

**StableMock simplifies HTTP mocking for integration tests** by combining the power of WireMock with zero-configuration recording and intelligent pattern matching.

### Comparison with Alternatives

| Feature                           | **StableMock**                                      | WireMock 3                                         | WireMock Cloud                                     |
|-----------------------------------|-----------------------------------------------------|----------------------------------------------------|----------------------------------------------------|
| Setup & Recording                | ✅ *Zero-Config Recording* — just annotate & go     | ⚠️ Manual setup required                           | ✅ SaaS recording & cloud UI                        |
| Dynamic Data Handling            | ✅ *Smart Patterns* that learn what changes         | ❌ Manual matchers needed                           | ✅ Yes (dynamic templates, stateful mocks)          |
| JUnit 5 Integration             | ✅ Native annotation, seamless lifecycle            | ⚠️ Requires manual lifecycle handling               | ✅ Native integration + enterprise CI support       |
| Nested JSON/XML Support         | ✅ Intuitive DSL (e.g., `json:user.session.token`)   | ❌ Custom JSONPath/XPath required                   | ✅ Yes (supports complex formats)                   |
| GraphQL Support                 | ✅ Auto-detected, built-in support                  | ❌ Manual config only                               | ✅ Yes — full GraphQL/gRPC support                   |
| Scenarios / Sequential Responses | ✅ Built-in support for flows & sequences           | ✅ Possible but heavy manual setup                  | ✅ Fully managed scenario/state machine flows       |
| Auto-Detect Dynamic Fields      | ✅ Detects & ignores fluctuating fields automatically| ❌ No built-in detection/learning                   | ⚠️ Offers advanced automation, but SaaS-only       |
| Playback Reliability            | ✅ Stable reproducibility — record once, run anywhere | ⚠️ Depends on custom mappings                      | ⚠️ Cloud-first, offline may be limited               |
| Offline & CI/CD Ready           | ✅ Local files + dedicated Gradle/CI tasks          | ✅ Local use supported                               | ⚠️ Primarily cloud-based; offline workflows limited |
| Free & Open Source              | ✅ MIT License — fully free, unrestricted           | ✅ Apache 2.0 — open source                         | ❌ SaaS model (free tier exists)                    |

### When to Use StableMock

**✅ Perfect for:**
- Mocking external/third-party APIs (services you don't control or can't containerize)
- Fast, lightweight HTTP mocking without Docker overhead
- Tests with dynamic request data (timestamps, IDs, tokens) that need intelligent pattern matching
- Recording and replaying HTTP interactions for reproducible tests
- CI/CD pipelines requiring offline testing without external dependencies
- Testing GraphQL, REST, and SOAP services with zero-config recording

**⚠️ Consider alternatives when:**
- You're mocking internal services (consider Testcontainers to run actual services in containers rather than mocking HTTP responses)

## Quick Start

### 1. Annotate Your Test

Your tests are horses. `@U` puts the horseshoes on 'em — keeps 'em safe and steady.

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

### 2. Record Mode (First Time)

Record HTTP interactions by proxying to the real service:

```bash
./gradlew test "-Dstablemock.mode=RECORD"
```

This will:
- Start WireMock on a dynamic port
- Proxy requests to the target URL
- Save responses as stub mappings in `src/test/resources/stablemock/<TestClass>/<testMethod>/`

**Note**: System properties use the `stablemock.*` prefix.

**Configuring WireMock Base URL:**

By default, StableMock uses `http://localhost:{port}` where `{port}` is the dynamically assigned WireMock port. You can override this to point to a remote WireMock instance:

```bash
./gradlew test "-Dstablemock.baseUrl=http://remote-stablemock:8080"
```

If not set, defaults to `http://localhost:{port}`.

### 3. Playback Mode (Default)

Run tests using recorded mocks:

```bash
./gradlew test
```

Tests will use the saved mappings instead of calling the real service.

## Handling Dynamic Data with Ignore Patterns

Real-world APIs often include dynamic data like timestamps, request IDs, or session tokens. StableMock's `ignore` feature handles this by injecting WireMock placeholders into request patterns.

### How It Works

When you specify ignore patterns, StableMock:
1. **In RECORD mode**: Saves the interaction, then modifies the request pattern to use `equalToJson` or `equalToXml` with placeholders
2. **In PLAYBACK mode**: WireMock matches requests while ignoring the specified dynamic fields

### Syntax

```
@U(urls = { "https://api.example.com" }, 
         ignore = { 
             "json:timestamp",              // Ignore JSON field
             "json:user.sessionId",         // Ignore nested JSON field  
             "gql:variables.cursor",        // Ignore GraphQL variable (auto-detected)
             "gql:variables.timestamp",     // Ignore GraphQL variable
             "xml://MessageID",             // Ignore XML element
             "xml://Header/Timestamp"       // Ignore nested XML element
         })
```

## Use Cases

### Use Case 1: REST API with Timestamps

**Problem**: Your API includes a `timestamp` field in every request:

```json
{
  "action": "createUser",
  "timestamp": 1700000000000,
  "data": { "name": "John" }
}
```

**Solution**:
```java
@U(urls = { "https://api.example.com" }, ignore = { "json:timestamp" })
public class UserApiTest {
    @Test
    public void testCreateUser(int port) {
        String body = String.format(
            "{\"action\":\"createUser\",\"timestamp\":%d,\"data\":{\"name\":\"John\"}}",
            System.currentTimeMillis()  // Different every time!
        );
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/users"))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        
        // Works in both RECORD and PLAYBACK modes
    }
}
```

### Use Case 2: Request IDs and Session Tokens

**Problem**: Multiple dynamic fields in the same request:

```json
{
  "requestId": "uuid-here",
  "sessionToken": "token-here",
  "query": "SELECT * FROM users"
}
```

**Solution**:
```java
@U(urls = { "https://db-api.com" }, 
         ignore = { "json:requestId", "json:sessionToken" })
public class DatabaseTest {
    @Test
    public void testQuery(int port) {
        String body = String.format(
            "{\"requestId\":\"%s\",\"sessionToken\":\"%s\",\"query\":\"SELECT * FROM users\"}",
            UUID.randomUUID(),
            generateSessionToken()
        );
        // Matches despite different IDs each time
    }
}
```

### Use Case 3: Nested JSON Fields

**Problem**: Dynamic data in nested objects:

```json
{
  "user": {
    "id": 123,
    "session": {
      "token": "xyz",
      "expiresAt": 1700000000
    }
  },
  "action": "getData"
}
```

**Solution**:
```
@U(urls = { "https://api.example.com" }, 
         ignore = { "json:user.session.token", "json:user.session.expiresAt" })
```

**Note**: Currently only top-level ignore is fully supported. Nested path support coming soon!

### Use Case 4: SOAP/XML Services

**Problem**: SOAP requests with messageIDs and timestamps:

```xml
<soap:Envelope>
  <soap:Header>
    <MessageID>uuid-here</MessageID>
    <Timestamp>2024-01-01T00:00:00Z</Timestamp>
  </soap:Header>
  <soap:Body>
    <GetUserRequest>
      <UserId>123</UserId>
    </GetUserRequest>
  </soap:Body>
</soap:Envelope>
```

**Solution**:
```java
@U(urls = { "https://soap-service.com" }, 
         ignore = { "xml://MessageID", "xml://Timestamp" })
public class SoapTest {
    @Test
    public void testGetUser(int port) {
        String soapRequest = String.format(
            "<soap:Envelope>..." +
            "  <MessageID>%s</MessageID>" +
            "  <Timestamp>%s</Timestamp>" +
            "...</soap:Envelope>",
            UUID.randomUUID(),
            Instant.now()
        );
        // Matches regardless of MessageID/Timestamp values
    }
}
```

### Use Case 5: Namespaced XML Elements

**Problem**: XML with namespaces:

```xml
<wsa:MessageID xmlns:wsa="http://www.w3.org/2005/08/addressing">
  uuid-here
</wsa:MessageID>
```

**Solution**:
```
@U(urls = { "https://service.com" }, 
         ignore = { "xml://wsa:MessageID" })
```

### Use Case 6: Multiple Dynamic Fields (Combined)

**Problem**: API with timestamps in both JSON and XML:

**Solution**:
```
@U(urls = { "https://api.example.com" }, 
         ignore = { 
             "json:requestTimestamp",
             "json:correlationId",
             "xml://Timestamp",
             "xml://MessageID"
         })
```

### Use Case 7: Ignoring XML Attributes

**Problem**: Dynamic values in XML attributes:

```xml
<Reservation CreateDateTime="2025-01-01T12:00:00Z" Status="Reserved">
  <UniqueID Type="14" ID="12345"/>
</Reservation>
```

**Solution**: Use XPath syntax to target attributes (`/@AttributeName`):

```
@U(urls = { "https://api.example.com" }, 
         ignore = { 
             "xml://Reservation/@CreateDateTime",
             "xml://UniqueID/@ID"
         })
```

### Use Case 8: Ignoring Namespaced XML Elements/Attributes

**Problem**: XML with namespaces where element or attribute names need to be matched regardless of namespace prefix:

```xml
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
  <soap:Body>
    <Response EchoToken="dynamic-value" xmlns="http://example.com/Response">
      <Reservation CreateDateTime="2025-01-01T12:00:00Z"/>
    </Response>
  </soap:Body>
</soap:Envelope>
```

**Solution**: Use XPath `local-name()` to ignore elements/attributes regardless of namespace prefix:

```
@U(urls = { "https://api.example.com" }, 
         ignore = { 
             "xml://*[local-name()='Reservation']/@CreateDateTime",
             "xml://*[local-name()='Response']/@EchoToken"
         })
```

### Use Case 9: GraphQL Requests with Dynamic Variables

**Problem**: GraphQL requests include dynamic variables like cursors, timestamps, or pagination tokens:

```json
{
  "query": "query GetUsers($first: Int!, $after: String, $timestamp: Long) { ... }",
  "variables": {
    "first": 10,
    "after": "cursor-123",
    "timestamp": 1234567890
  }
}
```

**Solution**: Use `ignore` with `gql:variables.X` syntax to ignore specific variables while keeping the query string exact:

```java
@U(urls = { "https://api.github.com/graphql" }, 
         ignore = { "gql:variables.cursor", "gql:variables.timestamp", "gql:variables.after" })
public class GraphQLTest {
    @Test
    public void testGraphQL(int port) {
        String query = "query GetUsers($first: Int!, $after: String) { ... }";
        String variables = String.format(
            "{\"first\":10,\"after\":\"%s\",\"timestamp\":%d}",
            UUID.randomUUID(),
            System.currentTimeMillis()
        );
        
        String graphqlBody = String.format(
            "{\"query\":\"%s\",\"variables\":%s}",
            query.replace("\"", "\\\""),
            variables
        );
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/graphql"))
            .POST(HttpRequest.BodyPublishers.ofString(graphqlBody))
            .header("Content-Type", "application/json")
            .build();
        
        // Variables 'after' and 'timestamp' will be ignored
        // Query string must match exactly
    }
}
```

**Important Notes**:
- GraphQL detection is automatic (checks for `"query"` field in request body)
- Use `gql:variables.X` syntax to ignore GraphQL variables (e.g., `gql:variables.cursor`)
- Also supports `graphql:variables.X` as an alias
- Query string must match exactly (no placeholders)
- Only variables in the `variables` object are ignored
- Works seamlessly with regular JSON field ignoring in the same `ignore` array

## Parallel Execution

Tests run in parallel by default. Each test gets its own isolated WireMock instance on a unique port, so there are no conflicts.

## Debugging with Match Diffs

When developing tests with ignore patterns, you might want to see exactly what's being ignored during request matching. StableMock provides a visual diff feature to help with debugging.

### Enable Match Diffs

Run tests with the `stablemock.showMatches` system property:

```bash
./gradlew test "-Dstablemock.showMatches=true"
```

Or combine with PLAYBACK mode:

```bash
./gradlew test "-Dstablemock.mode=PLAYBACK" "-Dstablemock.showMatches=true"
```

### What It Shows

When enabled, StableMock displays a visual diff for each matched request:

```
========================================
StableMock Match Diff
Request URL: /post
Request Method: POST
========================================

--- Stub Pattern (with placeholders)
+++ Actual Request

@@ ... @@
  <Response
- EchoToken="${xmlunit.ignore}" (stub pattern)
+ EchoToken="9999999999999" (actual request)
  ...
  <Reservation
- CreateDateTime="${xmlunit.ignore}" (stub pattern)
+ CreateDateTime="2025-01-01T12:00:00Z" (actual request)
  ...
```

This clearly shows:
- **Stub pattern**: What's stored in the mapping file with placeholders like `${xmlunit.ignore}` or `${json-unit.ignore}`
- **Actual request**: The real values your test is sending

### When to Use It

- **Debugging failed matches**: See why a request isn't matching a stub
- **Verifying ignore patterns**: Confirm your ignore patterns are working correctly
- **Understanding matching behavior**: See exactly which fields are being ignored vs matched

### Performance Note

The diff feature only activates when explicitly enabled and has minimal overhead. It's safe to use in local development but should be disabled in CI/CD environments.

## Request Verification

StableMock supports request verification, allowing you to verify that specific HTTP requests were made to the mock server. This is useful for testing that your application correctly calls external APIs.

### What is Request Verification?

Request verification lets you assert that:
- A specific request was made (e.g., POST to `/api/users`)
- A request was made a certain number of times (e.g., exactly once, at least 3 times)
- A request was never made
- Requests match specific criteria (URL, method, body, headers)

### Using StableMockVerifier Helper

The easiest way to verify requests is using the `StableMockVerifier` helper class, which provides a fluent API similar to MockServer:

```
@U(urls = { "https://api.example.com" })
public class UserApiTest {
    
    @Test
    public void testCreateUser(int port, StableMockVerifier verifier) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        
        // Make POST request
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/users"))
            .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"John\"}"))
            .header("Content-Type", "application/json")
            .build();
        
        client.send(request, HttpResponse.BodyHandlers.ofString());
        
        // Verify POST request was made exactly once
        verifier.verifyPost("/api/users", WireMock.exactly(1));
    }
}
```

### Available Verification Methods

#### Basic HTTP Method Verification

```
// Verify GET request
verifier.verifyGet("/api/users", WireMock.exactly(1));

// Verify POST request
verifier.verifyPost("/api/users", WireMock.exactly(1));

// Verify PUT request
verifier.verifyPut("/api/users/123", WireMock.exactly(1));

// Verify DELETE request
verifier.verifyDelete("/api/users/123", WireMock.exactly(1));
```

#### Verify Request Count

```
// Verify request was made exactly once
verifier.verifyPost("/api/users", WireMock.exactly(1));

// Count requests
int count = verifier.countRequests(
    WireMock.postRequestedFor(WireMock.urlEqualTo("/api/users"))
);
assertEquals(3, count);
```

#### Verify Request Body

```
// Verify POST with JSON body
verifier.verifyPost("/api/users", 
    WireMock.exactly(1),
    WireMock.equalToJson("{\"name\":\"John\"}")
);

// Verify POST with XML body
verifier.verifyPostXml("/api/soap", 
    WireMock.exactly(1),
    WireMock.equalToXml("<Request><Name>John</Name></Request>")
);
```

#### Verify Request Never Made

```
// Verify DELETE was never called
verifier.verifyNever(
    WireMock.deleteRequestedFor(WireMock.urlEqualTo("/api/users"))
);
```

### Using WireMockServer Directly

For advanced verification scenarios, you can inject `WireMockServer` directly:

```
@U(urls = { "https://api.example.com" })
public class AdvancedTest {
    
    @Test
    public void testAdvancedVerification(int port, WireMockServer wireMockServer) {
        // Make requests...
        
        // Verify using WireMock API directly
        wireMockServer.verify(WireMock.exactly(2), 
            WireMock.getRequestedFor(WireMock.urlMatching("/api/.*"))
        );
        
        // Get all matching requests
        List<LoggedRequest> requests = wireMockServer.findAll(
            WireMock.getRequestedFor(WireMock.urlEqualTo("/api/users"))
        );
        
        assertEquals(2, requests.size());
    }
}
```

### Complete Example

```

@U(urls = {"https://api.example.com"})
public class CompleteVerificationTest {

   @Test
   public void testMultipleRequests(int port, StableMockVerifier verifier) {
      HttpClient client = HttpClient.newHttpClient();
      String baseUrl = "http://localhost:" + port;

      // Make multiple requests
      client.send(HttpRequest.newBuilder()
              .uri(URI.create(baseUrl + "/api/users"))
              .GET()
              .build(), HttpResponse.BodyHandlers.ofString());

      client.send(HttpRequest.newBuilder()
              .uri(URI.create(baseUrl + "/api/users"))
              .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"John\"}"))
              .header("Content-Type", "application/json")
              .build(), HttpResponse.BodyHandlers.ofString());

      client.send(HttpRequest.newBuilder()
              .uri(URI.create(baseUrl + "/api/users"))
              .GET()
              .build(), HttpResponse.BodyHandlers.ofString());

      // Verify GET was called twice
      verifier.verifyGet("/api/users", WireMock.exactly(2));

      // Verify POST was called once
      verifier.verifyPost("/api/users", WireMock.exactly(1));

      // Verify DELETE was never called
      verifier.verifyNever(
              WireMock.deleteRequestedFor(WireMock.urlEqualTo("/api/users"))
      );

      // Count total GET requests
      int getCount = verifier.countRequests(
              WireMock.getRequestedFor(WireMock.urlEqualTo("/api/users"))
      );
      assertEquals(2, getCount);
   }
}
```

### When to Use Request Verification

Request verification is useful when:
- **Testing API clients**: Verify your client makes correct API calls
- **Integration testing**: Ensure services communicate correctly
- **Behavior verification**: Confirm specific endpoints are called
- **Debugging**: Understand which requests were made during a test

### Notes

- Request verification works in both **RECORD** and **PLAYBACK** modes
- Verification happens after requests are made (typically at the end of your test)
- WireMock tracks all requests made to the server, even if they don't match stubs
- Use `countRequests()` to get the count without throwing exceptions

## Advanced Configuration

### Annotation Placement

You can place `@U` on:
- **Test class**: Applies to all test methods
- **Test method**: Overrides class-level configuration

```java
@U(urls = { "https://api.example.com" })  // Default for all tests
public class MyTests {
    
    @Test
    public void test1(int port) { }  // Uses class-level config
    
    @U(urls = { "https://other-api.com" }, ignore = { "json:timestamp" })
    @Test
    public void test2(int port) { }  // Overrides with method-level config
}
```

### Repeatable Annotations

You can use multiple `@U` annotations on the same test method or class. URLs and ignore patterns from all annotations are automatically merged:

```
@U(urls = { "https://api1.com" }, ignore = { "json:timestamp" })
@U(urls = { "https://api2.com" }, ignore = { "json:requestId" })
@U(urls = { "https://api3.com" }, ignore = { "Date", "Connection" })
@Test
public void testMultipleServices(int port) {
    // All URLs are merged: ["https://api1.com", "https://api2.com", "https://api3.com"]
    // All ignore patterns are merged: ["json:timestamp", "json:requestId", "Date", "Connection"]
}
```

**Benefits:**
- **Organize by service**: Group URLs and ignore patterns by service or concern
- **Reuse patterns**: Define common ignore patterns separately and combine them
- **Flexibility**: Mix and match different configurations without duplicating URLs

**Note**: Duplicate URLs and ignore patterns are automatically deduplicated, preserving the order of first occurrence.

## How WireMock Placeholders Work

### JSON Requests
Use `json:` prefix for JSON fields. StableMock transforms:
```
// Original recorded request
{"timestamp": 1700000000, "action": "create"}
```

Into:
```
// Modified request pattern with placeholder
{"timestamp": "${json-unit.ignore}", "action": "create"}
```

WireMock then uses `equalToJson` matching, which ignores the `timestamp` field value.

**Note**: `body:` prefix is still supported for backward compatibility, but `json:` is preferred.

### XML Requests
StableMock transforms:
```xml
<!-- Original -->
<Request><Timestamp>2024-01-01</Timestamp><Action>create</Action></Request>
```

Into:
```xml
<!-- Modified with placeholder -->
<Request><Timestamp>${xmlunit.ignore}</Timestamp><Action>create</Action></Request>
```

WireMock uses `equalToXml` matching, which ignores the `<Timestamp>` element value.

### GraphQL Requests
StableMock transforms:
```
// Original GraphQL request
{
  "query": "query GetUsers($first: Int!, $after: String) { ... }",
  "variables": {
    "first": 10,
    "after": "cursor-123",
    "timestamp": 1234567890
  }
}
```

Into:
```
// Modified with placeholders (only in variables, query stays exact)
{
  "query": "query GetUsers($first: Int!, $after: String) { ... }",
  "variables": {
    "first": 10,
    "after": "${json-unit.ignore}",
    "timestamp": "${json-unit.ignore}"
  }
}
```

WireMock uses `equalToJson` matching with placeholders enabled. The query string must match exactly, while specified variables are ignored.

## Auto-Detect Dynamic Fields

StableMock can automatically detect dynamic fields by comparing request bodies across multiple test runs. This feature helps you identify which fields change between runs and suggests the appropriate ignore patterns.

### How It Works

1. **First Run (RECORD mode)**: StableMock records the request and saves it for analysis
2. **Subsequent Runs (RECORD mode)**: StableMock compares the new request with previous requests for the same endpoint
3. **Detection**: Fields that differ between runs are flagged as dynamic
4. **Suggestions**: StableMock prints formatted suggestions with copy-paste ready ignore patterns

### Enabling Auto-Detect

Auto-detect is **enabled by default** in RECORD mode. To disable it:

```bash
./gradlew test "-Dstablemock.mode=RECORD" "-Dstablemock.autoDetect=false"
```

### Example Usage

Create a test with dynamic fields:

```
@U(urls = { "https://postman-echo.com" })
public class AutoDetectDemoTest {
    
    @Test
    public void testAutoDetect(int port) {
        String jsonBody = String.format(
            "{\"name\":\"John\",\"timestamp\":%d,\"requestId\":\"%s\",\"action\":\"create\"}",
            System.currentTimeMillis(),
            UUID.randomUUID());
        
        // Send request...
    }
}
```

**First run** (RECORD mode):
```bash
./gradlew test "-Dstablemock.mode=RECORD" --tests AutoDetectDemoTest
```
- Records the request
- Saves it to `.stablemock-analysis/AutoDetectDemoTest/testAutoDetect/`

**Second run** (RECORD mode):
```bash
./gradlew test "-Dstablemock.mode=RECORD" --tests AutoDetectDemoTest
```

You'll see output like:

```
========================================
StableMock Auto-Detect Analysis
========================================
Analyzed recordings of POST /post

🔍 Detected Dynamic Fields:

✅ timestamp (Confidence: 95%)
   Values seen: [1763667254570, 1763667301234]
   Suggested pattern: "json:timestamp"

✅ requestId (Confidence: 95%)
   Values seen: [4d4e0471-9e4d-4c10-bce0-61f63014a334, a22a942a-ca0d-4349-a282-e8d3346a83e9]
   Suggested pattern: "json:requestId"

📋 StableMock will auto-ignore these fields. Optional: add to @U annotation:
@U(urls = {...}, ignore = {
    "json:timestamp",
    "json:requestId"
})
========================================
```

### How Detection Works

- **JSON Fields**: Compares field values at each level of the JSON structure
- **XML Elements**: Compares element text content and attributes
- **Confidence Levels**:
  - **95%**: High confidence - field values differ directly
  - **70%**: Medium confidence - arrays differ in size or content
- **Comparison**: Uses the most recent previous request (keeps last 5 for performance)

### Analysis Data Storage

Request analysis data is stored in `.stablemock-analysis/<TestClass>/<testMethod>/`:
- Each request is saved as a timestamped file
- Contains URL, method, content type, and body
- Used for comparison in subsequent runs

### Limitations

- Requires **at least 2 runs** in RECORD mode to detect dynamic fields
- Only compares requests with the **same URL and HTTP method**
- Keeps last 5 previous requests per endpoint for performance
- Works best when dynamic fields have different values between runs

### Auto-Apply Detected Patterns

StableMock automatically applies detected ignore patterns (confidence >= 90%) by default when saving mappings. Detected patterns are merged with annotation patterns, with annotation patterns taking precedence.

To disable auto-apply:

```bash
./gradlew test "-Dstablemock.mode=RECORD" "-Dstablemock.autoApply=false"
```

### Opt-Out: Use Annotation Patterns Only

To disable auto-ignore and use only `@U` annotation patterns, delete the JSON files containing detection results:

```powershell
# Delete detection files for a specific test
Remove-Item -Recurse -Force src/test/resources/stablemock/AutoDetectDemoTest/testAutoDetect/*.json

# Example: Delete detection file for AutoDetectDateFormatsTest
Remove-Item src/test/resources/stablemock/AutoDetectDateFormatsTest/testAutoDetectMultipleDateFormats/*.json
```

When no detection files exist, StableMock will use only the patterns specified in your `@U` annotation. This is useful when you want explicit control over which fields to ignore.

### Manual Pattern Management

If you prefer manual control:
1. Copy the suggested ignore patterns from the console output
2. Add them to your `@U` annotation
3. Delete the detection JSON files to opt-out of auto-apply
4. Re-run in RECORD mode to regenerate mappings with your annotation patterns

## Spring Boot Integration

When using StableMock with Spring Boot applications, you need to configure your application to use the dynamic proxy port instead of the real third-party URL from properties files.

**📚 Complete Example**: See [`examples/spring-boot-example`](examples/spring-boot-example/README.md) for a full working Spring Boot application with tests.

### The Problem

In Spring Boot tests:
- Your test calls Controller A
- Controller A calls Service A
- Service A calls a third-party API (URL from `application.properties`)
- But Service A needs to call `localhost:${stablemock.port}` instead, where the port changes per test

### Solution: Use @DynamicPropertySource

StableMock automatically sets system properties when WireMock starts (in `beforeEach()`):
- `stablemock.port` - The WireMock proxy port
- `stablemock.baseUrl` - `http://localhost:${stablemock.port}`

Use Spring's `@DynamicPropertySource` to override your properties. The supplier is evaluated lazily, so it will read the system property after StableMock starts:

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

**Note**: The supplier (`() -> System.getProperty(...)`) is evaluated lazily when Spring actually needs the property value, not during context initialization. This ensures StableMock has already started and set the system property before Spring reads it.

### Alternative: Read System Property Directly

If you prefer to read the system property directly in your Spring configuration:

```java
@SpringBootTest
@U(urls = { "https://api.thirdparty.com" })
@TestPropertySource(properties = {
    "app.thirdparty.url=${stablemock.baseUrl:http://localhost:8080}"
})
public class MySpringTest {
    // ...
}
```

**Note**: The default value (`http://localhost:8080`) is only used if StableMock hasn't started yet. Once StableMock starts, `stablemock.baseUrl` will be set automatically.

### Example: Service Configuration

Your `application.properties` might have:
```properties
app.thirdparty.url=https://api.thirdparty.com
```

Your service uses this property:
```java
@Service
public class MyService {
    @Value("${app.thirdparty.url}")
    private String thirdPartyUrl;
    
    public void doSomething() {
        // Calls the URL from properties
        restTemplate.getForObject(thirdPartyUrl + "/endpoint", String.class);
    }
}
```

In tests, `@DynamicPropertySource` overrides this to point to StableMock's proxy, so your service automatically uses the mocked endpoint.

### Multiple Third-Party URLs

If you need to mock multiple third-party services:

```java
@SpringBootTest
@U(urls = { "https://api1.com", "https://api2.com" })
public class MultiServiceTest {
    
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        String stablemockBaseUrl = System.getProperty("stablemock.baseUrl");
        // Note: All requests proxy to the first URL in @U annotation
        registry.add("app.api1.url", () -> stablemockBaseUrl);
        registry.add("app.api2.url", () -> stablemockBaseUrl);
    }
}
```

**Important**: When multiple URLs are specified in `@U`, WireMock proxies all requests to the first URL. For different behaviors, use separate test classes with different `@U` annotations.

## Troubleshooting

### Common Issues

#### ❌ "No mapping found for request"

**Symptoms**: Tests work in RECORD mode but fail in PLAYBACK mode with "Request was not matched"

**Solutions**:
1. **Check ignore patterns match your request**:
   ```
   // ❌ Wrong: pattern doesn't match actual field
   ignore = { "json:time" }  // but your field is called "timestamp"
   
   // ✅ Correct:
   ignore = { "json:timestamp" }
   ```

2. **Verify stub mappings exist**:
   ```bash
   # Check this directory exists:
   src/test/resources/stablemock/<TestClass>/<testMethod>/mappings/
   ```

3. **Re-record with ignore patterns**:
   ```bash
   ./gradlew clean test -Dstablemock.mode=RECORD
   ```

#### ❌ "Unsupported class file major version 69"

**Symptoms**: Build fails with Groovy compilation error

**Solution**: Already fixed! The Gradle plugin is now pure Java. Just pull latest changes.

#### ❌ Nested patterns not working

**Symptoms**: `json:user.session.token` not matching

**Solutions**:
1. **Verify the JSON structure**:
   ```
   {
     "user": {           // ← Must be an object
       "session": {      // ← Must be an object  
         "token": "xyz"  // ← This is what gets ignored
       }
     }
   }
   ```

2. **Use dot notation correctly**:
   ```
   // ✅ Correct for nested fields:
   ignore = { "json:user.session.token" }
   
   // ❌ Wrong: trying to ignore parent object:
   ignore = { "json:user.session" }  // This ignores the whole session object
   ```

#### ❌ GraphQL variables not ignored

**Symptoms**: GraphQL tests fail in PLAYBACK even with `gql:` patterns

**Solutions**:
1. **StableMock auto-detects GraphQL** - make sure your request has a `query` field:
   ```
   {
     "query": "query GetUser($id: ID!) { ... }",
     "variables": { "id": "123" }  // ← This gets detected
   }
   ```

2. **Both syntaxes work**:
   ```
   ignore = { "gql:variables.id" }        // Preferred
   ignore = { "gql:id" }                   // Also works
   ```

#### ❌ XML patterns not working

**Symptoms**: XML ignore patterns don't match

**Solutions**:
1. **Use XPath-like syntax**:
   ```
   // ✅ Correct:
   ignore = { "xml://Header/Timestamp" }    // Nested element
   ignore = { "xml://@messageId" }          // Attribute
   
   // ❌ Wrong:
   ignore = { "xml:Timestamp" }             // Missing //
   ```

2. **Check XML structure**:
   ```xml
   <Header>
     <Timestamp>2024-01-01</Timestamp>  ← xml://Header/Timestamp
   </Header>
   ```

#### ❌ Port conflicts

**Symptoms**: "Address already in use" errors

**Solution**: StableMock uses dynamic ports automatically. If you see this error, another WireMock instance may be running:
```
# Find and kill the process (example for Unix):
lsof -i :8080 | grep LISTEN
kill -9 <PID>
```

#### ❌ Parallel test failures

**Symptoms**: Tests fail intermittently when run in parallel

**Solution**: Each test gets its own WireMock instance and port automatically. If issues persist:
```
// Disable parallel execution for StableMock tests:
@Execution(ExecutionMode.SAME_THREAD)
@U(urls = { "https://api.example.com" })
public class MyTest { ... }
```

### Get Help

- **Check examples**: See `examples/` directory for working samples
- **Enable debug logging**: Run with `-Dstablemock.showMatches=true`
- **Open an issue**: [GitHub Issues](https://github.com/sloppylopez/stablemock/issues)

## Contributing

StableMock is open source. Contributions welcome!

## License

MIT License - See LICENSE file for details.

