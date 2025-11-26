# Quarkus Example with StableMock

This example demonstrates how to use StableMock with Quarkus to record and replay HTTP interactions for third-party APIs.

## Setup

1. Build and publish StableMock to local Maven repository:
```bash
cd ../..
./gradlew publishToMavenLocal
```

2. Run tests in RECORD mode (first time):
```bash
./gradlew stableMockRecord
```

3. Run tests in PLAYBACK mode (default):
```bash
./gradlew test
```

Or use the StableMock tasks:
```bash
./gradlew stableMockPlayback
```

## How It Works

- `ThirdPartyService` checks for `stablemock.baseUrl` system property (set by StableMock)
- If set, it uses the WireMock proxy URL; otherwise uses the default URL from `application.properties`
- Tests use `@U` annotation to configure StableMock
- WireMock records/replays HTTP interactions automatically

## Test Files

- `QuarkusIntegrationTest.java` - Basic integration tests
- `QuarkusDynamicPropertyTest.java` - Tests using dynamic property resolution

## Notes

- StableMock sets `stablemock.baseUrl` system property automatically
- The service reads this property to route requests to WireMock proxy
- No `@DynamicPropertySource` needed - service checks system property directly


