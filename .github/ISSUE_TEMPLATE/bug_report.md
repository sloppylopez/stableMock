---
name: Bug Report
about: Report a bug in Mockero
title: '[BUG] '
labels: bug
assignees: ''
---

## Describe the Bug
A clear and concise description of what the bug is.

## To Reproduce
Steps to reproduce the behavior:
1. Configure annotation with '...'
2. Run test with '...'
3. See error

## Expected Behavior
A clear description of what you expected to happen.

## Actual Behavior
What actually happened.

## Code Sample
```java
@Mockero(urls = { "https://api.example.com" }, ignore = { "json:timestamp" })
public class MyTest {
    @Test
    public void myTest(int port) {
        // Your test code
    }
}
```

## Environment
- **StableMock Version**: [e.g., 1.0.0]
- **Java Version**: [e.g., Java 17]
- **OS**: [e.g., Windows 11, macOS 14, Ubuntu 22.04]
- **Build Tool**: [e.g., Gradle 8.5, Maven 3.9]

## Error Logs/Stack Trace
```
Paste relevant error logs or stack traces here
```

## Additional Context
Add any other context about the problem here.
