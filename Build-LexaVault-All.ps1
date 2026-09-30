param(
    [switch]$SkipTests,
    [switch]$RefreshNodeModules
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$BackendDir = Join-Path $ProjectRoot "backend"
$SharedIdentityDir = Join-Path $ProjectRoot "shared\enterprise-identity"
$FrontendDir = Join-Path $ProjectRoot "frontend\securedoc-ui"

function Require-Command {
    param([string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command '$Name' was not found in PATH."
    }
}

function Run-Step {
    param(
        [string]$Name,
        [scriptblock]$Command
    )

    Write-Host ""
    Write-Host "============================================================" -ForegroundColor DarkGray
    Write-Host $Name -ForegroundColor Cyan
    Write-Host "============================================================" -ForegroundColor DarkGray

    $started = Get-Date
    & $Command

    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE."
    }

    $elapsed = (Get-Date) - $started
    Write-Host ""
    Write-Host "$Name PASSED ($([math]::Round($elapsed.TotalSeconds, 1)) sec)" -ForegroundColor Green
}

Write-Host ""
Write-Host "=== LexaVault Full Build ===" -ForegroundColor Cyan
Write-Host "Project root: $ProjectRoot"

if (-not (Test-Path (Join-Path $BackendDir "pom.xml"))) {
    throw "Backend pom.xml not found at: $BackendDir"
}
if (-not (Test-Path (Join-Path $FrontendDir "package.json"))) {
    throw "Frontend package.json not found at: $FrontendDir"
}

Require-Command "java"
Require-Command "mvn"
Require-Command "node"
Require-Command "npm"

Write-Host ""
Write-Host "Java:"
& java -version

Write-Host ""
Write-Host "Maven:"
& mvn -version

Write-Host ""
Write-Host "Node: $(& node --version)"
Write-Host "npm:  $(& npm --version)"

$overallStart = Get-Date

Push-Location $SharedIdentityDir
try {
    Run-Step "SHARED IDENTITY - Maven install" {
        & mvn clean install -DskipTests
    }
}
finally {
    Pop-Location
}

Push-Location $BackendDir
try {
    if ($SkipTests) {
        Run-Step "BACKEND - Maven clean install (tests skipped)" {
            & mvn clean install -DskipTests
        }
    }
    else {
        Run-Step "BACKEND - Maven clean install (all modules)" {
            & mvn clean install
        }
    }
}
finally {
    Pop-Location
}

Push-Location $FrontendDir
try {
    if ($RefreshNodeModules -or -not (Test-Path "node_modules")) {
        Run-Step "FRONTEND - npm ci" {
            & npm ci
        }
    }
    else {
        Write-Host ""
        Write-Host "FRONTEND - node_modules already exists; skipping npm ci." -ForegroundColor Yellow
        Write-Host "Use -RefreshNodeModules to force a clean npm ci."
    }

    Run-Step "FRONTEND - TypeScript check" {
        & npx tsc --noEmit
    }

    Run-Step "FRONTEND - Vite production build" {
        & npm run build
    }
}
finally {
    Pop-Location
}

$overallElapsed = (Get-Date) - $overallStart

Write-Host ""
Write-Host "============================================================" -ForegroundColor DarkGray
Write-Host "LEXAVAULT FULL BUILD PASSED" -ForegroundColor Green
Write-Host "============================================================" -ForegroundColor DarkGray
Write-Host "Backend:  Maven clean install - PASS"
Write-Host "Frontend: TypeScript + Vite build - PASS"
Write-Host "Total:    $([math]::Round($overallElapsed.TotalMinutes, 2)) minutes"
Write-Host ""
