# Contributing to StableMock

Thank you for your interest in contributing to StableMock!

## Getting Started

1. Fork the repository (when public)
2. Clone your fork: `git clone https://github.com/your-username/stablemock.git`
3. Create a branch: `git checkout -b feature/your-feature-name`
4. Make your changes
5. Run tests: `./gradlew test`
6. Commit and push
7. Open a Pull Request

## Development Setup

### Prerequisites
- Java 17 or higher
- Gradle (wrapper included)

### Building
```bash
./gradlew build
```

### Running Tests
```bash
# All tests
./gradlew test

# Specific test
./gradlew test --tests com.stablemock.JsonPlaceholderInjectorTest

# With coverage
./gradlew test jacocoTestReport
```

## Code Style

- Follow standard Java conventions
- Use meaningful variable and method names
- Add Javadoc for public APIs
- Include unit tests for new features
- Keep test coverage above 80%

## Commit Messages

Use clear, descriptive commit messages:
- `feat: add support for nested XML patterns`
- `fix: resolve null pointer in RequestAnalyzer`
- `docs: update README with GraphQL examples`
- `test: add unit tests for PatternInjector`

## Testing

- Write unit tests for all new functionality
- Ensure all existing tests pass
- Test with both RECORD and PLAYBACK modes
- Include integration tests for new features

## Reporting Issues

Use GitHub Issues to report bugs or request features. Include:
- StableMock version
- Java version
- Operating system
- Steps to reproduce
- Expected vs actual behavior
- Relevant code snippets or logs

## Questions?

Feel free to open a discussion or issue if you have questions about contributing.
