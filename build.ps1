param(
    [Parameter(Position=0)]
    [string]$Target = "all"
)

$ErrorActionPreference = "Stop"

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
        Write-Step "Record mode (first time)"
        & .\gradlew.bat stableMockRecord
        if ($LASTEXITCODE -ne 0) { throw "Gradle command failed with exit code $LASTEXITCODE" }
        Write-Step "Record mode (second time)"
        & .\gradlew.bat stableMockRecord
        if ($LASTEXITCODE -ne 0) { throw "Gradle command failed with exit code $LASTEXITCODE" }
        Write-Step "Playback mode"
        & .\gradlew.bat stableMockPlayback
        if ($LASTEXITCODE -ne 0) { throw "Gradle command failed with exit code $LASTEXITCODE" }
    }
    finally {
        Pop-Location
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

