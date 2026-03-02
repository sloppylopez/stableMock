# Why StableMock Example Passes but nh-api-bp FullFlowFlexibleIT Gets 404

## Summary

StableMock's own Spring Boot example (e.g. `ParallelParameterizedPlaybackIT`, `ParallelParameterizedIsolationIT`) **passes** in playback with Option A (per-invocation WireMock server). The same pattern in **nh-api-bp** (`FullFlowFlexibleIT`) **fails with 404** from WireMock. This document explains the likely causes and gives a prompt you can use to fix it.

---

## What Works in the StableMock Example

- **Test**: `examples/spring-boot-example` → `ParallelParameterizedPlaybackIT` or `ParallelParameterizedIsolationIT`.
- **Flow**: `@SpringBootTest` + `@U(urls = { "https://postman-echo.com" }, properties = { "app.postmanecho.url" })` + `@DynamicPropertySource` with `autoRegisterProperties(registry, TestClass.class)`.
- **Client**: `PostmanEchoClient` is a **Feign** interface: `@FeignClient(name = "postmanEchoClient", url = "${app.postmanecho.url}")`.
- **Stubs**: Simple GET by path+query (e.g. `/get?id=1`). No body matching; no changing tokens.
- **Result**: Playback passes; each parameterized invocation can use its own WireMock port when Option A is enabled.

So in the example:

1. The base URL comes from a **dynamic property** whose **supplier** calls `WireMockContext.getThreadLocalBaseUrl()` (from `BaseStableMockTest` / `autoRegisterProperties`).
2. The **first time** the app needs `app.postmanecho.url` (e.g. when the Feign client builds a request), the supplier runs and returns the **current** ThreadLocal base URL. With Option A, `beforeEach` has already set that to the per-invocation WireMock port.
3. Stubs are trivial (path + query only), so no body-matching issues.

---

## What Fails in nh-api-bp

- **Test**: `FullFlowFlexibleIT` in `C:\dev\code\nh-api-bp\src\test\java\com\ey\nhhoteles\flow\FullFlowFlexibleIT.java`.
- **Flow**: Same idea — `@U` with multiple `tms4c.*` properties, `@DynamicPropertySource` with `autoRegisterProperties(registry, FullFlowFlexibleIT.class)` (in `BaseOpenApiTestFeature` with path preservation for TMS4C).
- **Client**: TMS4C is called via **Spring WS** (`WebServiceTemplate` / `ConnectivityManagerProvider`), not Feign. The endpoint URL is likely provided by a bean that was configured **once** when the context started.
- **Stubs**: SOAP with **exact body** matching (`equalToXml`). Recorded requests contain fixed `EchoToken`, `TimeStamp`, `TransactionIdentifier`, `UniqueID`, etc. Playback sends **different** values → no match → 404.
- **Observed**: Logs show requests going to **class-level server port** (e.g. `http://localhost:63402/...`) even when Option A has started a **new** server for the current invocation. So the app is not using the per-invocation URL.

So in bp there are **two** separate issues:

1. **URL not updated per invocation (Option A)**  
   The TMS base URL (or full endpoint) is probably resolved **once at context startup** (e.g. `@Value("${tms4c.cloud.booking.endpoint}")` on a field, or equivalent). At that moment only the **class-level** WireMock server exists (e.g. port 63402). Option A then starts a **new** server in `beforeEach` and sets `WireMockContext`, but the existing bean still holds the old URL, so all requests keep going to 63402. The dynamic property **supplier** is correct and would return the right URL if something asked for it **at request time**; the problem is that the TMS client does not re-read the property when sending a request.

2. **Stub body mismatch**  
   Even when a request hits the right WireMock server, the recorded mappings use **exact** `equalToXml` with fixed SOAP (EchoToken, TimeStamp, TransactionIdentifier, UniqueID, ResID_Value, etc.). Playback requests have different values, so WireMock does not match any stub and returns 404. `FullFlowFlexibleIT` already has `@U(ignore = { "xml://*[local-name()='EchoToken']", ... })`, but if the current mappings were recorded **before** that or without placeholders being applied, they still contain literal values.

---

## Differences in One Table

| Aspect | StableMock example (passes) | nh-api-bp (404) |
|--------|----------------------------|------------------|
| Client type | Feign (`@FeignClient(url = "${app.postmanecho.url}")`) | Spring WS / custom (WebServiceTemplate, etc.) |
| When URL is read | When Feign builds the request (property read at use time) | Likely once at bean creation (context init) |
| Stub matching | Path + query only (GET) | SOAP body `equalToXml` with many changing fields |
| Option A | Per-invocation port used because URL is read at request time | Same port (class-level) used because URL was fixed at startup |

---

## What to Verify and Fix in nh-api-bp

1. **Where TMS URL is set**  
   Find where `tms4c.endpoint` / `tms4c.cloud.avail.endpoint` / `tms4c.cloud.booking.endpoint` / etc. are consumed (e.g. `ConnectivityManagerProvider`, `WebServiceTemplate`, or a config bean). If they are injected with `@Value` or equivalent **at construction**, they are fixed at context init. To work with Option A, the **endpoint used for each TMS call must be resolved when the call is made** (e.g. from `Environment.getProperty("tms4c.cloud.booking.endpoint")` or a small helper that reads from Environment/WireMockContext at request time).

2. **Re-record with safe ignore list (after StableMock fix)**  
   Run **stableMockRecord** for `FullFlowFlexibleIT` with `@U(ignore = { ... })` so that playback applies placeholders for truly dynamic fields (EchoToken, TimeStamp, TransactionIdentifier, UniqueID/@ID, ResID_Value, CorrelationID, stay dates, etc.).  
   After the StableMock fix, the XML dynamic-field detector still reports identity/rate fields as dynamic for `OTA_HotelAvailRQ`, but it **never adds them to `ignore_patterns`**, specifically:
   - `POS/Source/RequestorID/@ID`, `@ID_Context`, `@Type`  
   - `RatePlanCandidates/RatePlanCandidate/@RatePlanCode`  
   This ensures playback can still distinguish VIP vs non‑VIP users and different rate plans (e.g. `R_PLAVIP`, `NHWEB`, `NHWEB_NHR`) while remaining fully automatic — no manual edits to `detected-fields.json` are needed for record & replay to work with multiple parameterized examples.

3. **Optional: disable Option A for this test**  
   If you cannot change the app to resolve TMS URL at request time, run playback **without** Option A (e.g. `-Dstablemock.parameterized.playback.reload=false` or the equivalent that uses a single class-level server and reloads mappings per invocation). Then the app keeps using the class-level port (63402) and you only need to ensure reload finds the right invocation dirs and that stubs use placeholders (re-record as above).

---

## Prompt You Can Use

Copy the block below and use it when asking someone (or an agent) to fix the 404 in nh-api-bp:

```
We use StableMock for playback of TMS4C SOAP in our Spring Boot app (nh-api-bp). 
The test FullFlowFlexibleIT (parameterized, Option A per-invocation server) used to fail with 
404 from WireMock. StableMock's own example (ParallelParameterizedPlaybackIT / 
ParallelParameterizedIsolationIT) passes with the same pattern.

Findings:
1. In the example, the external URL is used by a Feign client (url = "${app.postmanecho.url}"); 
   the dynamic property is read when the client builds a request, so Option A's per-invocation 
   port is used.
2. In our app, TMS4C is called via Spring WS (WebServiceTemplate / ConnectivityManagerProvider). 
   The TMS endpoint URL is likely resolved once at context startup (e.g. @Value), so it stays 
   on the class-level WireMock port (63402) even when Option A starts a new server per invocation.
3. Our stubs use equalToXml with exact SOAP bodies; playback sends different EchoToken, 
   TimeStamp, etc., so body matching fails. We already have @U(ignore = { ... }) but mappings 
   may have been recorded before placeholders were applied.

Final generic fix in StableMock:
- Keep Option A but ensure the app resolves the TMS URL **at request time**, not only at context startup, so each parameterized invocation talks to its own WireMock server.
- Change the XML dynamic-field detector so that for `OTA_HotelAvailRQ` it **never auto-ignores** `RequestorID` attributes or `RatePlanCode` in `ignore_patterns`. These fields are still reported as dynamic, but they remain part of the WireMock match key. This makes “record once, replay many (including multiple parameterized examples)” work without any manual tweaking of `detected-fields.json`, and ensures VIP vs non‑VIP and different rate plans always map to the correct recorded stubs.

Tasks:
- Locate where tms4c.endpoint / tms4c.cloud.*.endpoint are read (which beans, @Value or config). 
  Change so the URL is resolved at request time (e.g. from Environment or a request-scoped helper), 
  not fixed at bean creation, so Option A's per-invocation port is used.
- Re-record FullFlowFlexibleIT (stableMockRecord) with the current @U ignore list so stubs 
  contain placeholders for dynamic SOAP fields; then run stableMockPlayback again.
- If we cannot change URL resolution, document running with Option A disabled and ensure 
  reload finds invocation dirs (e.g. build/resources/test fallback) and stubs use placeholders.

Reference: StableMock example in examples/spring-boot-example (ParallelParameterizedPlaybackIT, 
ParallelParameterizedIsolationIT). Our test: FullFlowFlexibleIT; base: BaseE2ETestFeature -> 
BaseOpenApiTestFeature (autoRegisterProperties with path preservation for TMS4C).
```

---

## Files to Inspect in nh-api-bp

- `src/test/java/com/ey/nhhoteles/flow/FullFlowFlexibleIT.java` — @U, @DynamicPropertySource.
- `src/test/java/com/ey/nhhoteles/BaseOpenApiTestFeature.java` — `autoRegisterProperties` and path preservation for `tms4c.*`.
- Application code that builds the TMS4C endpoint URL or configures `WebServiceTemplate` / `ConnectivityManagerProvider` (search for `tms4c.endpoint`, `tms4c.cloud`).
- Mappings under `src/test/resources/stablemock/FullFlowFlexibleIT/` — confirm whether they use placeholders or literal values in `equalToXml`.
