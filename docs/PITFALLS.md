# StableMock Pitfalls

Common issues encountered during development and their solutions.

---

## 1. WireMock Serve Events Are Returned in Reverse Chronological Order

### Problem

When using class-level WireMock servers shared across multiple test methods, each test method needs to save only its own recorded HTTP interactions. The naive approach tracks an `existingRequestCount` before each test and uses it to slice the serve events list:

```java
// WRONG: This gets the OLDEST events, not the newest
List<ServeEvent> testMethodServeEvents = 
    allServeEvents.subList(existingRequestCount, allServeEvents.size());
```

### Root Cause

**WireMock's `getAllServeEvents()` returns events in REVERSE chronological order** (newest first, oldest last).

Example with 4 test methods running sequentially:
- Test1 runs, makes request A -> `allServeEvents = [A]`
- Test2 runs, makes request B -> `allServeEvents = [B, A]` (B is at index 0!)
- Test3 runs, makes request C -> `allServeEvents = [C, B, A]`
- Test4 runs, makes request D -> `allServeEvents = [D, C, B, A]`

Using `subList(existingRequestCount, size)`:
- Test1: `existingRequestCount=0`, gets `[A]` - correct
- Test2: `existingRequestCount=1`, gets `[A]` - **WRONG!** Should be `[B]`
- Test3: `existingRequestCount=2`, gets `[B, A]` - **WRONG!** Should be `[C]`
- Test4: `existingRequestCount=3`, gets `[C, B, A]` - **WRONG!** Should be `[D]`

### Symptoms

- All test methods record the same mapping (the first test's request)
- Playback fails with 404 errors for requests that should have been recorded
- Mapping files have wrong content (e.g., `testGetUser2ViaController` has `/users/1` instead of `/users/2`)

### Solution

Get elements from the **START** of the list (newest events), not the end:

```java
// CORRECT: Get the newest events from the start of the list
int newEventsCount = allServeEvents.size() - existingRequestCount;
List<ServeEvent> testMethodServeEvents = 
    newEventsCount > 0 
        ? allServeEvents.subList(0, newEventsCount) 
        : new ArrayList<>();
```

### Affected Files

- `SingleAnnotationMappingStorage.java` - `saveMappingsForTestMethod()`
- `MultipleAnnotationMappingStorage.java` - `saveMappingsForTestMethodMultipleAnnotations()`

---

## 2. Gradle `cleanStableMock` Runs Between Multi-Run Recordings

### Status: RESOLVED

**Previous Problem:** The `stableMockRecord` task had `dependsOn("cleanStableMock")`, which caused each invocation to delete recordings from the previous run, preventing dynamic field detection across multiple runs.

**Solution:** The dependency has been removed. `stableMockRecord` no longer automatically cleans recordings. Recordings are now merged between runs, enabling dynamic field detection. To start fresh, run `./gradlew cleanStableMock stableMockRecord`.

---

## 3. Parallel Test Execution with Class-Level Servers

### Problem

When JUnit runs test methods in parallel (via `junit.jupiter.execution.parallel.enabled = true`), all methods start simultaneously with `existingRequestCount = 0`. By the time `afterEach` runs, all serve events from all parallel methods are visible to each method.

### Current Behavior

With the reverse-order fix, each parallel method sees ALL events and saves ALL mappings. This works because the merge phase deduplicates by URL, and WireMock can match any of the duplicate stubs.

### Consideration

For stricter per-method mapping isolation, consider:
- Using method-level servers instead of class-level servers
- Disabling parallel execution for recording (`systemProperty 'junit.jupiter.execution.parallel.enabled', 'false'`)
- Implementing per-method event tracking with synchronized access

---

## 4. Spring Application Context Caching with Dynamic Ports

### Problem

Spring Boot caches `ApplicationContext` between test classes when possible. If Feign clients are configured with WireMock URLs at context creation time, subsequent tests may use stale URLs pointing to non-existent WireMock servers.

### Symptoms

- Tests pass individually but fail when run together
- 404 errors pointing to ports from different test classes
- Intermittent failures in CI but not locally

### Solutions

1. **Force context isolation** with unique properties per test class:
   ```java
   @SpringBootTest(properties = {"stablemock.testClass=MyTest"})
   ```

2. **Use lazy initialization** so Feign clients aren't created until needed:
   ```java
   @SpringBootTest(properties = {"spring.main.lazy-initialization=true"})
   ```

3. **Use ThreadLocal for URL storage** in `WireMockContext` and read from `@DynamicPropertySource`

---

## 5. WSL File System Sync Issues

### Problem

On WSL (Windows Subsystem for Linux), file writes may not be immediately visible to subsequent reads due to file system caching between Windows and Linux.

### Symptoms

- Files written but not found when listing directory
- "0 JSON files found" warnings during merge
- Tests pass on native Windows/Linux but fail on WSL

### Solutions

1. Add delays after file writes:
   ```java
   Thread.sleep(200); // Allow file system sync
   ```

2. Use retry loops with exponential backoff when reading recently-written files

3. Force file system sync by reading file attributes

4. Run on native Linux or Windows when possible

---

## 6. WSL Network Timeout Issues

### Problem

Tests pass gracefully in CI pipelines (native Linux) but fail locally with `SocketTimeoutException` when running in WSL (Windows Subsystem for Linux). This is caused by WSL's network virtualization overhead.

### Root Cause

WSL uses a **virtualized network stack** that routes through the Windows host, adding significant latency and reducing throughput compared to native networking:

1. **Network Stack Virtualization**: WSL network traffic goes through multiple layers (WSL → Windows host → physical network), adding latency
2. **Resource Contention**: Multiple parallel processes competing for the virtualized network stack cause contention
3. **Proxy Request Overhead**: WireMock proxy requests (test → WireMock → external API) take longer in WSL due to network virtualization

### Symptoms

- Tests pass in CI (native Linux) but fail locally in WSL
- `SocketTimeoutException: Read timed out` errors during test execution
- Timeouts occur when Feign clients call WireMock, which then proxies to external APIs
- Errors like: `feign.RetryableException: Read timed out executing GET http://localhost:XXXXX/users/1`

### Solutions

1. **Increase Feign Client Timeouts** (in `src/test/resources/application.properties`):
   ```properties
   # Feign client timeout configuration (important for WSL)
   # These timeouts need to be longer than WireMock's proxy timeout (default 60s)
   feign.client.config.default.readTimeout=200000
   feign.client.config.jsonPlaceholderClient.readTimeout=200000
   feign.client.config.postmanEchoClient.readTimeout=200000
   feign.client.config.graphQLClient.readTimeout=200000
   ```

2. **Increase WireMock Proxy Timeout** (configured in `WireMockServerManager.java` via `StableMockConfig`):
   ```java
   // Default 60000 ms (60s), configurable via -Dstablemock.wiremock.proxyTimeoutMs
   .proxyTimeout(StableMockConfig.getProxyTimeoutMs());
   ```

3. **Timeout Chain**: Ensure timeout hierarchy:
   - Feign client read timeout (200s) > WireMock proxy timeout (default 60s) > actual network request time

4. **Alternative**: Run tests on native Linux or Windows instead of WSL when possible

### Why This Happens

- **CI pipelines** typically run on native Linux VMs with direct network access
- **WSL** adds network virtualization overhead that native Linux doesn't have
- The same tests that work in CI fail locally because WSL's network stack is slower

### Verification

If tests pass in CI but fail locally in WSL with timeouts, this is the issue. The 200-second Feign timeout should resolve it.

### Troubleshooting

If timeouts persist even with increased timeouts, try these steps in order:

1. **Restart WSL**: `wsl --shutdown` (from PowerShell), then run tests again
2. **Restart the computer**: Sometimes WSL's network stack gets into a bad state that only a full reboot fixes
3. **Check Windows network state**: Ensure Windows host networking is working (try `ping google.com` from WSL)
4. **Reduce system load**: Close other applications that might be using network resources

**Note**: WSL network performance is non-deterministic. If tests passed yesterday but fail today with the same code, it's likely a WSL network state issue, not a code problem. Restarting WSL or the computer often resolves it.

---

## 7. WireMock `equalToXml` Bug with SOAP / Namespaced XML

### Problem

When request bodies are SOAP XML (or other namespaced XML) and StableMock has detected **dynamic attributes** to ignore, using WireMock's `equalToXml` matcher can cause an internal error:

- **Symptom**: Playback fails with `feign.FeignException$InternalServerError: [500 Server Error]` and root cause `java.lang.IllegalArgumentException: type: -1`.
- **Cause**: WireMock delegates to XMLUnit for XML comparison. With namespaced SOAP and certain attribute/value comparisons, XMLUnit can throw `IllegalArgumentException: type: -1` (an unknown comparison type). Using `exemptedComparisons` (e.g. `ATTR_VALUE`) does not fix this; the error occurs deeper in the comparison logic.

### Solution (StableMock Workaround)

For **SOAP XML** requests that have **dynamic attribute** ignore patterns, StableMock does **not** use `equalToXml`. Instead it:

1. **Detects SOAP**: Request body contains `soap:Envelope` or `xmlns:soap`.
2. **Replaces the matcher**: Removes `equalToXml` and uses WireMock's **`matchesXPath`** matcher instead.
3. **Builds an XPath**: Parses the recorded XML, finds the SOAP `Body` element and its first child (the operation root, e.g. `SearchAvailabilityRQ`), and generates an XPath that matches by **local name** only, e.g.:
   - `//*[local-name()='Body']/*[local-name()='SearchAvailabilityRQ']`
4. **Effect**: Playback matches requests by “same SOAP operation” (body root element), and does not compare dynamic attributes at all, so the XMLUnit bug is avoided.

Non-SOAP XML with dynamic fields still uses `equalToXml` with `${xmlunit.ignore}` placeholders where applicable; only SOAP + dynamic attributes trigger the `matchesXPath` path.

### Affected Code

- `WireMockServerManager.java`: `applyIgnorePatternsToStubFilesPerMethod` (and the shared body-matcher logic) replaces `equalToXml` with `matchesXPath` when the body is SOAP and there are dynamic attribute patterns.
- `extractSoapXPathMatch(String xml)`: Builds the XPath from the SOAP Body’s first child element using `local-name()`, with fallback to `//*[local-name()='Envelope']` on parse failure.

### Implications

- **Matching is by operation, not full body**: Two requests with the same SOAP body root (e.g. same `Body` child local name) will match the same stub even if other elements/attributes differ. For parameterized SOAP tests that send the same operation type with different dynamic values, this is usually desired.
- **Stricter matching**: If you need to distinguish two SOAP requests that share the same body root but differ in other ways, the current workaround does not support that; you would need method-specific stubs or different operations.

---

## 8. Method Name with Underscores: Ignore Patterns Not Applied (Class-Level Playback)

### Problem

After merging parameterized test mappings, files are named `methodName_originalMappingName.json`. The code used to derive the method name by taking the substring **before the first underscore**. If the test method name itself contains underscores (e.g. `should_make_a_full_flow_until_confirmation_using_flexible_payment`), that yields a wrong prefix (e.g. `"should"`), so no ignore patterns are found for that method and stubs keep strict body matching (e.g. `equalToXml`). Playback then returns 404 for requests whose body differs (e.g. new `transactionId` / `echoToken`).

### Why Some Tests Pass and Others Fail

- **Method without underscores** (e.g. `testSoapFlow`): Merged file `testSoapFlow[0]_post-uuid.json` → substring before first `_` is `testSoapFlow[0]` → matches `patternsByMethod` → patterns applied → playback passes.
- **Method with underscores** (e.g. `should_make_a_full_flow_...`): Merged file `should_make_a_full_flow_..._sap_bc_....json` → substring before first `_` is `should` → no match → patterns not applied → 404.

### Solution

In `applyIgnorePatternsToStubFilesPerMethod`, resolve the method name by matching the file name against **known method names** from `patternsByMethod`: if the file name starts with `knownMethod + "_"`, use that method. Only fall back to “substring before first underscore” when no known method matches. This way method names that contain underscores (e.g. `should_make_a_full_flow_until_confirmation_using_flexible_payment[1]`) are resolved correctly and ignore patterns (and SOAP `matchesXPath` workaround) are applied.

### Affected Code

- `WireMockServerManager.java`: `applyIgnorePatternsToStubFilesPerMethod` – method name extraction from merged mapping file name.

---

## Parameterized Tests: 404 or Wrong Response for One Invocation ([0] or [1])

### Problem

With a class-level server, parameterized invocations ([0], [1], …) share one WireMock and use **scenario state** so each invocation matches only its own stubs. If JUnit runs those invocations **in parallel**, each `beforeEach` sets the scenario state; the last write wins, so another invocation can see the wrong state and get 404 or the wrong stub.

### Symptoms

- One parameterized index fails (e.g. `[0]`) with 404 or wrong data; the other passes.
- Stack points at availability, prereservation, or similar step.
- Logs show different ForkJoinPool workers for [0] and [1].

### Solution

Run the parameterized test in a single thread so scenario state is not overwritten:

```java
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@ParameterizedTest
@MethodSource("yourSource")
@Execution(ExecutionMode.SAME_THREAD)  // required when using class-level server + scenario
void should_make_a_full_flow_until_confirmation_using_flexible_payment(...) {
```

Add `@Execution(ExecutionMode.SAME_THREAD)` on the parameterized test method that uses the class-level StableMock server.

---

## SOAP / XML: Availability vs reservation code mismatch (checkData fails)

### Problem

Auto-detection adds varying SOAP fields (e.g. `RatePlanCode`, `RoomTypeCode`) to `ignore_patterns`. During playback, the **availability** request can then match a stub recorded for a **different** flow (e.g. `NHWEB` instead of `NHWEB_NHR`). The availability response has short codes, the **reservation** response has full codes, and application validation (e.g. `checkData(selectedRoom, reservationData)`) fails because `ratePriceGroupCode` / `ratePlanCode` or `roomCategoryCode` / `roomType` no longer match.

### Symptoms

- Test passes against real TMS but fails in playback with 500 or "DATA ERROR".
- Logs show e.g. `checkRatePlan: false (NHWEB/NHWEB_NHR)` or `checkRoomType: false (STDDBL/SUPDBL)`.

### Solution

**Protect** those fields so they are never added to `ignore_patterns` and remain in the request matcher:

1. **System property** (semicolon-separated paths, anonymized example):
   ```bash
   -Dstablemock.protectedDynamicFields=\"xml:...SamplePlanCandidate']/@*[local-name()='SampleFieldA'];xml:...SamplePlanCandidate']/@*[local-name()='SampleFieldB'];xml:...SampleStayCandidate']/@*[local-name()='SampleFieldC']\"
   ```

2. **Override in test class** (extend `BaseStableMockTest` and override `getProtectedDynamicFields()` with the full XPath-like paths from your `detected-fields.json` for your hotel availability request body / `SamplePlanCandidate` and `SampleStayCandidate`).

Example anonymized paths (adjust namespaces/local-name to match your SOAP):

- SampleFieldA: `.../*[local-name()='SamplePlanCandidates']/*[local-name()='SamplePlanCandidate']/@*[local-name()='SampleFieldA']`
- SampleFieldB: same with `SampleFieldB`
- SampleFieldC: `.../*[local-name()='SampleStayCandidates']/*[local-name()='SampleStayCandidate']/@*[local-name()='SampleFieldC']`

See README "Protected dynamic fields" and `StableMockConfig.PROTECTED_DYNAMIC_FIELDS_PROPERTY`.
