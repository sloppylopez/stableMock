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
        # Wait for mappings to be saved (afterEach callbacks complete)
        # Retry up to 10 times with increasing delays (total up to ~30 seconds)
        $maxRetries = 10
        $retryDelay = 2
        $baseDir = "src\test\resources\stablemock\SpringBootIntegrationTest"
        $found = $false
        
        for ($i = 0; $i -lt $maxRetries; $i++) {
            Start-Sleep -Seconds $retryDelay
            
            if (Test-Path $baseDir) {
                $testMethodDirs = Get-ChildItem -Path $baseDir -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -ne "mappings" -and $_.Name -ne "__files" }
                if ($testMethodDirs.Count -gt 0) {
                    $found = $true
                    break
                }
            }
            
            if ($i -lt $maxRetries - 1) {
                Write-Host "Waiting for test method directories... (attempt $($i + 1)/$maxRetries, waited $($retryDelay * ($i + 1))s)" -ForegroundColor Yellow
            }
            $retryDelay = [Math]::Min($retryDelay + 1, 5) # Gradually increase delay
        }
        
        if (-not $found) {
            $currentDir = Get-Location
            Write-Host "ERROR: No test method directories found after $maxRetries attempts" -ForegroundColor Red
            Write-Host "Current directory: $currentDir" -ForegroundColor Yellow
            Write-Host "Looking for: $baseDir" -ForegroundColor Yellow
            if (Test-Path "src\test\resources\stablemock") {
                Write-Host "Found stablemock directory, contents:" -ForegroundColor Yellow
                Get-ChildItem -Path "src\test\resources\stablemock" -Directory | ForEach-Object { Write-Host "  - $($_.Name)" -ForegroundColor Yellow }
            }
            if (Test-Path $baseDir) {
                Write-Host "SpringBootIntegrationTest directory exists, contents:" -ForegroundColor Yellow
                Get-ChildItem -Path $baseDir | ForEach-Object { Write-Host "  - $($_.Name) ($($_.PSIsContainer ? 'Directory' : 'File'))" -ForegroundColor Yellow }
            }
            throw "ERROR: No test method directories found after recording!"
        }
        
        $testMethodDirs = Get-ChildItem -Path $baseDir -Directory | Where-Object { $_.Name -ne "mappings" -and $_.Name -ne "__files" }
        
        Write-Host "Found $($testMethodDirs.Count) test method directory(ies):" -ForegroundColor Green
        $hasMappings = $false
        foreach ($testMethodDir in $testMethodDirs) {
            $methodMappings = Join-Path $testMethodDir.FullName "mappings"
            if (Test-Path $methodMappings) {
                $fileCount = (Get-ChildItem -Path $methodMappings -File -ErrorAction SilentlyContinue).Count
                Write-Host "  - $($testMethodDir.Name): $fileCount mapping file(s)" -ForegroundColor Green
                if ($fileCount -gt 0) {
                    $hasMappings = $true
                }
            } else {
                Write-Host "  - $($testMethodDir.Name): mappings directory NOT FOUND" -ForegroundColor Yellow
            }
        }
        
        if (-not $hasMappings) {
            throw "ERROR: No mapping files found in any test method directory!"
        }
        
        # Verify specific test method exists (but don't fail if it doesn't - just warn)
        $expectedPath = "src\test\resources\stablemock\SpringBootIntegrationTest\testCreatePostViaController\mappings"
        if (Test-Path $expectedPath) {
            $fileCount = (Get-ChildItem -Path $expectedPath -File -ErrorAction SilentlyContinue).Count
            Write-Host "testCreatePostViaController mappings found: $fileCount file(s)" -ForegroundColor Green
        } else {
            Write-Host "WARNING: testCreatePostViaController mappings not found (test may not have run)" -ForegroundColor Yellow
        }
        
        Write-Step "Record mode (second time)"
        & .\gradlew.bat stableMockRecord $gradleArgs
        if ($LASTEXITCODE -ne 0) { throw "Gradle command failed with exit code $LASTEXITCODE" }
        
        Write-Step "Playback mode"
        & .\gradlew.bat stableMockPlayback $gradleArgs
        if ($LASTEXITCODE -ne 0) { throw "Gradle command failed with exit code $LASTEXITCODE" }
        
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
        foreach ($error in $errors) {
            Write-Host "  - $error" -ForegroundColor Red
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

