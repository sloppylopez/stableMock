param(
    [Parameter(Position=0)]
    [string]$Target = "all"
)

$ErrorActionPreference = "Stop"

# Set JDK 17 for local builds
$env:JAVA_HOME = "C:\Users\sergi\.jdks\corretto-17.0.14"
Write-Host "Using JDK 17: $env:JAVA_HOME" -ForegroundColor Green

# Set CI mode to match pipeline behavior (sequential execution, no daemon)
$env:CI = "true"

function Write-Step {
    param([string]$Message)
    Write-Host "=== $Message ===" -ForegroundColor Cyan
}

function Invoke-Gradle {
    param([string]$Arguments)
    & .\gradlew.bat $Arguments.Split(' ')
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle command failed with exit code $LASTEXITCODE"
    }
}

function Invoke-Build {
    Write-Step "Building StableMock library"
    Invoke-Gradle "build -x test"
}

function Invoke-Publish {
    Write-Step "Publishing to Maven Local"
    Invoke-Gradle "publishToMavenLocal -x test"
}

function Invoke-SpringExample {
    Write-Step "Running Spring Boot example tests"
    Push-Location examples/spring-boot-example
    try {
        $gradleArgs = ""
        if ($env:CI) {
            $gradleArgs = "--no-daemon"
        }
        
        Write-Step "Cleaning old recordings"
        & .\gradlew.bat cleanStableMock $gradleArgs
        if ($LASTEXITCODE -ne 0) { throw "Gradle command failed with exit code $LASTEXITCODE" }
        
        Write-Step "Record mode (first time)"
        & .\gradlew.bat stableMockRecord $gradleArgs
        if ($LASTEXITCODE -ne 0) { throw "Gradle command failed with exit code $LASTEXITCODE" }
        
        # Wait a bit longer for afterEach callbacks to complete and files to be flushed
        Start-Sleep -Seconds 3
        
        Write-Step "Verifying recordings from first run"
        # Simple check - if mappings exist, great. If not, playback will fail anyway.
        # Don't fail the build here since timing can vary and tests will catch missing mappings.
        $baseDir = "src\test\resources\stablemock\SpringBootIntegrationTest"
        if (Test-Path $baseDir) {
            $testMethodDirs = Get-ChildItem -Path $baseDir -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -ne "mappings" -and $_.Name -ne "__files" }
            if ($testMethodDirs.Count -gt 0) {
                Write-Host "Found $($testMethodDirs.Count) test method directory(ies)" -ForegroundColor Green
                foreach ($testMethodDir in $testMethodDirs) {
                    $methodMappings = Join-Path $testMethodDir.FullName "mappings"
                    if (Test-Path $methodMappings) {
                        $fileCount = (Get-ChildItem -Path $methodMappings -File -ErrorAction SilentlyContinue).Count
                        Write-Host "  - $($testMethodDir.Name): $fileCount mapping file(s)" -ForegroundColor Green
                    }
                }
            } else {
                Write-Host "WARNING: No test method directories found yet (may still be saving)" -ForegroundColor Yellow
            }
        } else {
            Write-Host "WARNING: SpringBootIntegrationTest directory not found yet (may still be creating)" -ForegroundColor Yellow
        }
        
        Write-Step "Record mode (second time)"
        & .\gradlew.bat stableMockRecord $gradleArgs
        if ($LASTEXITCODE -ne 0) { throw "Gradle command failed with exit code $LASTEXITCODE" }
        
        Write-Step "Playback mode"
        & .\gradlew.bat stableMockPlayback $gradleArgs
        if ($LASTEXITCODE -ne 0) { throw "Gradle command failed with exit code $LASTEXITCODE" }
        
        Write-Step "Verifying recording report was generated"
        $reportJson = "src\test\resources\stablemock\recording-report.json"
        $reportHtml = "src\test\resources\stablemock\recording-report.html"
        if (Test-Path $reportJson) {
            Write-Host "Recording report (JSON) found: $reportJson" -ForegroundColor Green
        } else {
            Write-Host "WARNING: Recording report (JSON) not found at: $reportJson" -ForegroundColor Yellow
        }
        if (Test-Path $reportHtml) {
            Write-Host "Recording report (HTML) found: $reportHtml" -ForegroundColor Green
        } else {
            Write-Host "WARNING: Recording report (HTML) not found at: $reportHtml" -ForegroundColor Yellow
        }
        
        Write-Step "Verifying cleanup - checking for class-level directories"
        Test-StableMockCleanup
    }
    finally {
        Pop-Location
    }
}

function Test-StableMockCleanup {
    $testResourcesDir = "src\test\resources\stablemock"
    if (-not (Test-Path $testResourcesDir)) {
        Write-Host "No stablemock directory found - nothing to check" -ForegroundColor Yellow
        return
    }
    
    $errors = @()
    $testClassDirs = Get-ChildItem -Path $testResourcesDir -Directory
    
    foreach ($testClassDir in $testClassDirs) {
        $mappingsDir = Join-Path $testClassDir.FullName "mappings"
        $filesDir = Join-Path $testClassDir.FullName "__files"
        
        if (Test-Path $mappingsDir) {
            $files = Get-ChildItem -Path $mappingsDir -File -ErrorAction SilentlyContinue
            if ($files -and $files.Count -gt 0) {
                $errors += "Class-level mappings directory exists with files: $mappingsDir"
            } elseif (Test-Path $mappingsDir) {
                $errors += "Class-level mappings directory exists (empty): $mappingsDir"
            }
        }
        
        if (Test-Path $filesDir) {
            $files = Get-ChildItem -Path $filesDir -File -ErrorAction SilentlyContinue
            if ($files -and $files.Count -gt 0) {
                $errors += "Class-level __files directory exists with files: $filesDir"
            } elseif (Test-Path $filesDir) {
                $errors += "Class-level __files directory exists (empty): $filesDir"
            }
        }
    }
    
    if ($errors.Count -gt 0) {
        Write-Host "`nERROR: Class-level directories found after tests!" -ForegroundColor Red
        foreach ($err in $errors) {
            Write-Host "  - $err" -ForegroundColor Red
        }
        Write-Host "`nThese directories should be cleaned up by StableMock afterAll." -ForegroundColor Red
        throw "Cleanup verification failed - class-level directories still exist"
    } else {
        Write-Host "Cleanup verification passed - no class-level directories found" -ForegroundColor Green
    }
}


function Invoke-All {
    Write-Host "`n=== Running Complete Workflow ===" -ForegroundColor Green
    Invoke-Build
    Invoke-Publish
    Invoke-SpringExample
    Write-Host "`n=== Workflow Complete! ===" -ForegroundColor Green
}

# Main execution
switch ($Target.ToLower()) {
    "all" { Invoke-All }
    "build" { Invoke-Build }
    "publish" { Invoke-Publish }
    "spring-example" { Invoke-SpringExample }
    default {
        Write-Host "Unknown target: $Target" -ForegroundColor Red
        Write-Host "`nAvailable targets:" -ForegroundColor Yellow
        Write-Host "  all              - Build, publish, and test Spring Boot example"
        Write-Host "  build            - Build StableMock library (skip tests)"
        Write-Host "  publish          - Publish to Maven Local (skip tests)"
        Write-Host "  spring-example   - Run Spring Boot example tests"
        exit 1
    }
}

