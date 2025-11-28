.PHONY: all build publish spring-example help

# Default target
all: build publish spring-example
	@echo ""
	@echo "=== Workflow Complete! ==="

# Build StableMock library (skip tests)
build:
	@echo "=== Building StableMock library ==="
	./gradlew build -x test

# Publish to Maven Local (skip tests)
publish:
	@echo "=== Publishing to Maven Local ==="
	./gradlew publishToMavenLocal -x test

# Run Spring Boot example tests
spring-example:
	@echo "=== Running Spring Boot example tests ==="
	@cd examples/spring-boot-example && \
	echo "=== Record mode (first time) ===" && \
	./gradlew stableMockRecord && \
	echo "=== Record mode (second time) ===" && \
	./gradlew stableMockRecord && \
	echo "=== Playback mode ===" && \
	./gradlew stableMockPlayback
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

