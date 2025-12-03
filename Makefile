.PHONY: all build publish spring-example help

# Set CI mode to match pipeline behavior (sequential execution, no daemon)
export CI=true

# Default target
all: build publish spring-example
	@echo ""
	@echo "=== Workflow Complete! ==="

# Build StableMock library (skip tests)
build:
	@echo "=== Building StableMock library ==="
	@if [ -z "$$JAVA_HOME" ]; then \
		if command -v java >/dev/null 2>&1; then \
			java_path=$$(which java); \
			if [ -L "$$java_path" ]; then \
				java_path=$$(readlink -f "$$java_path"); \
			fi; \
			export JAVA_HOME=$$(dirname $$(dirname "$$java_path")); \
			echo "Auto-detected JAVA_HOME: $$JAVA_HOME"; \
		else \
			echo "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH."; \
			echo "Please set the JAVA_HOME variable or install Java."; \
			exit 1; \
		fi; \
	fi
	@gradle_args=""; \
	if [ -n "$$CI" ]; then \
		gradle_args="--no-daemon"; \
	fi; \
	./gradlew build -x test $$gradle_args

# Publish to Maven Local (skip tests)
publish:
	@echo "=== Publishing to Maven Local ==="
	@if [ -z "$$JAVA_HOME" ]; then \
		if command -v java >/dev/null 2>&1; then \
			java_path=$$(which java); \
			if [ -L "$$java_path" ]; then \
				java_path=$$(readlink -f "$$java_path"); \
			fi; \
			export JAVA_HOME=$$(dirname $$(dirname "$$java_path")); \
		fi; \
	fi
	@gradle_args=""; \
	if [ -n "$$CI" ]; then \
		gradle_args="--no-daemon"; \
	fi; \
	./gradlew publishToMavenLocal -x test $$gradle_args

# Run Spring Boot example tests
spring-example:
	@echo "=== Running Spring Boot example tests ==="
	@if [ -z "$$JAVA_HOME" ]; then \
		if command -v java >/dev/null 2>&1; then \
			java_path=$$(which java); \
			if [ -L "$$java_path" ]; then \
				java_path=$$(readlink -f "$$java_path"); \
			fi; \
			export JAVA_HOME=$$(dirname $$(dirname "$$java_path")); \
		fi; \
	fi
	@gradle_args=""; \
	if [ -n "$$CI" ]; then \
		gradle_args="--no-daemon"; \
	fi; \
	cd examples/spring-boot-example && \
	echo "=== Cleaning old recordings ===" && \
	./gradlew cleanStableMock $$gradle_args && \
	echo "=== Verifying cleanup completed ===" && \
	if [ -d "src/test/resources/stablemock" ]; then \
		echo "WARNING: stablemock directory still exists after cleanup - forcing removal"; \
		rm -rf src/test/resources/stablemock; \
	fi && \
	echo "=== Waiting for file system to process deletions (important for JDK 17 on Linux) ===" && \
	sleep 0.5 && \
	echo "=== Record mode (first time) ===" && \
	./gradlew stableMockRecord $$gradle_args && \
	echo "=== Verifying recordings from first run ===" && \
	if [ ! -d "src/test/resources/stablemock/SpringBootIntegrationTest/testCreatePostViaController/mappings" ]; then \
		echo "ERROR: testCreatePostViaController mappings not found after recording!"; \
		exit 1; \
	fi && \
	echo "All expected test method mappings found" && \
	echo "=== Record mode (second time) ===" && \
	./gradlew stableMockRecord $$gradle_args && \
	echo "=== Playback mode ===" && \
	./gradlew stableMockPlayback $$gradle_args
	@echo "=== Verifying cleanup - checking for class-level directories ==="
	@$(MAKE) verify-cleanup

# Verify cleanup of class-level directories
verify-cleanup:
	@test_resources_dir="examples/spring-boot-example/src/test/resources/stablemock"; \
	if [ ! -d "$$test_resources_dir" ]; then \
		echo "No stablemock directory found - nothing to check"; \
		exit 0; \
	fi; \
	errors=0; \
	for test_class_dir in $$test_resources_dir/*/; do \
		if [ -d "$$test_class_dir" ]; then \
			mappings_dir="$$test_class_dir/mappings"; \
			files_dir="$$test_class_dir/__files"; \
			if [ -d "$$mappings_dir" ]; then \
				file_count=$$(find "$$mappings_dir" -type f 2>/dev/null | wc -l); \
				if [ $$file_count -gt 0 ]; then \
					echo "ERROR: Class-level mappings directory exists with files: $$mappings_dir"; \
					errors=1; \
				elif [ -d "$$mappings_dir" ]; then \
					echo "ERROR: Class-level mappings directory exists (empty): $$mappings_dir"; \
					errors=1; \
				fi; \
			fi; \
			if [ -d "$$files_dir" ]; then \
				file_count=$$(find "$$files_dir" -type f 2>/dev/null | wc -l); \
				if [ $$file_count -gt 0 ]; then \
					echo "ERROR: Class-level __files directory exists with files: $$files_dir"; \
					errors=1; \
				elif [ -d "$$files_dir" ]; then \
					echo "ERROR: Class-level __files directory exists (empty): $$files_dir"; \
					errors=1; \
				fi; \
			fi; \
		fi; \
	done; \
	if [ $$errors -eq 1 ]; then \
		echo ""; \
		echo "ERROR: Class-level directories found after tests!"; \
		echo "These directories should be cleaned up by StableMock afterAll."; \
		exit 1; \
	else \
		echo "Cleanup verification passed - no class-level directories found"; \
	fi

# Help target
help:
	@echo "Available targets:"
	@echo "  all              - Build, publish, and test Spring Boot example"
	@echo "  build            - Build StableMock library (skip tests)"
	@echo "  publish          - Publish to Maven Local (skip tests)"
	@echo "  spring-example   - Run Spring Boot example tests"
	@echo "  verify-cleanup   - Verify cleanup of class-level directories"

