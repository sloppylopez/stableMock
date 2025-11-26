# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Removed
- Removed Gradle tasks: `stablemockStart`, `stablemockStop`, `stablemockStartRecording`, `stablemockStopRecording` (redundant, use `stableMockRecord` and `stableMockPlayback` instead)

### Changed
- Renamed project from Mockero to StableMock
- Updated all system properties from `mockero.*` to `stablemock.*`
- Updated package names from `com.mockero` to `com.stablemock`
- Updated class names: `MockeroExtension` → `StableMockExtension`, `MockeroVerifier` → `StableMockVerifier`, `MockeroPlugin` → `StableMockPlugin`
- Updated Gradle tasks: `mockeroStart` → `stablemockStart`, `mockeroStop` → `stablemockStop`, etc.
- Updated resource directories: `src/test/resources/mockero` → `src/test/resources/stablemock`

### Added
- Refactored `WireMockContext` into focused components:
  - `WireMockLifecycleManager` for server lifecycle management
  - `MappingRepository` for mapping persistence
  - `PatternInjector` for placeholder injection
  - `RequestAnalyzer` for auto-detection of dynamic fields
- Nested JSON field support (e.g., `json:user.session.token`)
- Unit tests for `JsonPlaceholderInjector` and `XmlPlaceholderInjector`
- Java 25 compatibility by converting Gradle plugin from Groovy to Java
- Comprehensive Javadoc for public APIs
- Code coverage reporting with JaCoCo
- CONTRIBUTING.md guide
- CHANGELOG.md (this file)
- LICENSE file (MIT)

### Changed
- Gradle plugin now written in Java instead of Groovy
- Improved error handling in placeholder injectors
- Build configuration uses Java toolchain with language version 17

### Fixed
- Java 25 compatibility issues with Groovy compiler
- Compilation errors in refactored components

## [1.0.0] - TBD

### Added
- Initial release
- JUnit 5 extension for WireMock-based HTTP mocking
- Support for JSON, XML, and GraphQL ignore patterns
- Auto-detection of dynamic fields
- Gradle plugin for WireMock server management
- RECORD and PLAYBACK modes
- Comprehensive README documentation
