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
   # These timeouts need to be longer than WireMock's proxy timeout (60s)
   feign.client.config.default.readTimeout=200000
   feign.client.config.jsonPlaceholderClient.readTimeout=200000
   feign.client.config.postmanEchoClient.readTimeout=200000
   feign.client.config.graphQLClient.readTimeout=200000
   ```

2. **Increase WireMock Proxy Timeout** (already configured in `WireMockServerManager.java`):
   ```java
   .proxyTimeout(60000); // 60 seconds for proxy timeout (important for WSL)
   ```

3. **Timeout Chain**: Ensure timeout hierarchy:
   - Feign client read timeout (200s) > WireMock proxy timeout (60s) > actual network request time

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
