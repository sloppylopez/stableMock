# Why Path Preservation Worked Before Base-Class Change and Broke After

## Before (custom base class)

- A project-specific base class had:
  - **KNOWN_PATHS**: map of property name → path (e.g. `app.backend.get.url` → `/api/v1/get`).
  - **Custom `autoRegisterProperties`**: for each property from `@U`, registered a supplier that did **`getThreadLocalBaseUrl() + pathFromKnownPaths`**.
- **Test** used **one `@U`**: 1 URL (base only), 5 properties.
- **StableMockExtension** saw a single URL → started **one** WireMock server, called `setBaseUrl(baseUrl)` (no `setBaseUrls`).
- **Result**: All 5 properties pointed to the **same** port; each got `http://localhost:{port}` + its known path. Path was **guaranteed by project code**, independent of library or `application.properties`.

## After (library-only)

- Custom logic was **removed**; test now uses **library** `BaseStableMockTest.autoRegisterProperties`.
- Test was changed to **five `@U`** (one per endpoint), each with a **full URL** (base + path), so that the library’s `extractPathFromProperty(propertyName, defaultUrl)` could get the path from `defaultUrl`.
- **StableMockExtension** sees **5 URLs** → starts **5** WireMock servers, calls **`setBaseUrls(baseUrls)`**.
- **Library** registers each property with **`registerPropertyWithFallbackByIndex(..., index, defaultUrl)`**, so each property uses `getThreadLocalBaseUrlByIndex(i)` + path from `defaultUrl`.

## Why it can break

1. **Wrong URL used as `defaultUrl` for a property**  
   If the annotation parsing ever maps a property to the wrong URL (e.g. base-only URL instead of full URL), `extractPath(defaultUrl)` returns null and the injected value has **no path** → 404.

2. **`threadLocalBaseUrls` never set**  
   If the test runs in a way where **StableMockExtension** does **not** run (e.g. pure JUnit 4 with no extension), `setBaseUrls` is never called. Then `getThreadLocalBaseUrlByIndex(index)` falls back to `getThreadLocalBaseUrl()`. If that’s also null, the supplier falls back to system props / `defaultUrl`. So either the app talks to the real backend (no WireMock) or gets wrong base; path can still come from `defaultUrl` in fallback, but the base might be wrong.

3. **Path from application.properties overrides `defaultUrl`**  
   `extractPathFromProperty` prefers system property, then **classpath properties** (e.g. `application.properties`). If the project has `app.backend.url = <base-only URL>` in properties, we use that value’s path (null) and **never** reach `defaultUrl`. So path is lost. **Fix**: in the library, when the value from properties has **no path**, fall back to `defaultUrl` for path (already implemented: we only return when `path != null`, so we fall through to step 3 and use `defaultUrl`).

4. **403 from upstream**  
   If the path is correct (logs show full URL with path) but the response is **403**, the failure is from the **upstream** (e.g. TMS/BSP), not from missing path. Then “before worked” might mean a different env/auth; path preservation is already correct.

## Idea to build on

- **Keep path preservation in the library** and make it robust:
  - **Per-property `defaultUrl`**: For multiple `@U`, the library already assigns each property the URL from its annotation, so `defaultUrl` has the path. No change needed if parsing is correct.
  - **Fallback when properties file has no path**: Prefer path from `defaultUrl` when the value read from classpath has no path (current logic already does this by only returning when `path != null`).
- **Avoid project-specific path maps** (no KNOWN_PATHS in the consumer project):
  - **Option A (current)**: Use **multiple `@U`** with **full URLs** so each property gets the right `defaultUrl` and path. Requires the extension to run (JUnit 5 / extension lifecycle) and to call `setBaseUrls` when there are multiple URLs.
  - **Option B (future)**: Support **one URL + multiple properties** with **path overrides** (e.g. optional `paths = {"app.backend.get.url=/api/v1/get"}` or a small DSL in `@U`) so a single WireMock server can still serve all endpoints with different paths without five separate `@U`.
- **Ensure extension runs**: If the test is JUnit 4 only, WireMock and `setBaseUrl`/`setBaseUrls` are never set by StableMock. Then either migrate to JUnit 5 for these tests or provide a Spring/JUnit 4–friendly way to start the server and set the context (e.g. TestExecutionListener or static init that uses the same startup logic as the extension).

## Summary

| Aspect | Before | After |
|--------|--------|--------|
| Who sets path | Project (KNOWN_PATHS + custom supplier) | Library (`defaultUrl` from `@U`) |
| Servers | 1 | 5 (one per `@U`) |
| Base URL source | `getThreadLocalBaseUrl()` | `getThreadLocalBaseUrlByIndex(i)` |
| Path source | KNOWN_PATHS | `extractPathFromProperty(..., defaultUrl)` |

Path preservation **works** after the change **if** (1) each property’s `defaultUrl` is the full URL from its `@U`, and (2) the extension runs and sets `setBaseUrls`. If something still fails, the next step is to confirm in logs which URL is injected for each property and whether the failure is 404 (path/port) or 403 (upstream).

---

## Solution without manual config in your project

**Library:** `@U` supports optional `paths()` so one base URL + multiple properties get per-property paths without project-specific Java (no KNOWN_PATHS).

**In your project:** Use **one** `@U` with base URL and your properties (with optional `paths` or inline `"name=/path"`). One WireMock server; paths only in the annotation.

Simplified form: use `properties = {"name", "name=/path", ...}` so path is in the same array (no separate `paths`):

```java
@U(
    urls = {"http://example.com:8000"},
    properties = {
        "app.backend.get.url=/api/v1/get",
        "app.backend.post.url=/api/v1/post",
        "app.backend.put.url=/api/v1/put",
        "app.backend.patch.url=/api/v1/patch"
    },
    ignore = { ... }
)
```

No custom base class, no KNOWN_PATHS, no multiple `@U`. Optional: `paths = {"name=/path", ...}` is still supported if you prefer a separate array.

**Important:** Use **JUnit 5** for Spring Boot tests that use `@DynamicPropertySource` with StableMock (use `@ExtendWith(SpringExtension.class)` and do **not** use `@RunWith(SpringRunner.class)`). That way `StableMockExtension.beforeAll` runs **before** the Spring context is created, so dynamic property suppliers see the WireMock base URL and you only list the properties you actually use (no dummy "first" property). See `examples/spring-boot-example` (e.g. `PathPreservationTest`, `MultiplePropertiesSingleUrlTest`) for the same pattern.
