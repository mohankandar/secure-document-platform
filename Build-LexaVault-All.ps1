param(
    [switch]$SkipTests,
    [switch]$RefreshNodeModules,
    # Task 0054: without it this is the local development build (mvn -Pdev-build), which trusts the demo licence's
    # development key. With it the jars trust production licence keys only (backend/licence-build/production). Every jar
    # that leaves this machine is built with -Production.
    [switch]$Production
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$BackendDir = Join-Path $ProjectRoot "backend"
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

Write-Host ""
Write-Host "platform-core: released com.documentplatform.platform:platform-bom 1.0.0 from GitHub Packages (server github-platform-core in ~/.m2/settings.xml, token from GITHUB_PACKAGES_TOKEN; docs/install/BUILD.md)." -ForegroundColor DarkGray

$profileArgs = @()
if (-not $Production) { $profileArgs += '-Pdev-build' }
Write-Host "Licence keys: $(if ($Production) { 'PRODUCTION build (no development key)' } else { 'development build (-Pdev-build; demo licence accepted; never for a customer)' })"

$overallStart = Get-Date

Push-Location $BackendDir
try {
    if ($SkipTests) {
        Run-Step "BACKEND - Maven clean install (tests skipped)" {
            & mvn clean install -DskipTests @profileArgs
        }
    }
    else {
        Run-Step "BACKEND - Maven clean install (all modules)" {
            & mvn clean install @profileArgs
        }
    }
}
finally {
    Pop-Location
}

# Task 0041: the app's classpath carries platform-core's identity modules (and licensing, read only) and nothing else of
# platform-core. Task 0054 decision 1: and what platform-identity 1.0.0 itself needs (outbound rules, error shape).
# Setup, with platform-crypto and platform-integration, runs from its own jar (securedoc-setup).
Write-Host ""
Write-Host "BACKEND - app jar classpath check (task 0041)" -ForegroundColor Cyan
Add-Type -AssemblyName System.IO.Compression.FileSystem
$appJar = Join-Path $BackendDir 'securedoc-api\target\securedoc-api-0.1.0-SNAPSHOT.jar'
$zip = [System.IO.Compression.ZipFile]::OpenRead($appJar)
try {
    $platformLibs = @($zip.Entries | Where-Object { $_.FullName -like 'BOOT-INF/lib/platform-*' } | ForEach-Object { $_.Name })
}
finally {
    $zip.Dispose()
}
$allowed = '^platform-(identity-core|identity-jdbc|identity-spring|licensing|outbound-spring|outbound|errors)-[0-9][^-]*\.jar$'
$notAllowed = @($platformLibs | Where-Object { $_ -notmatch $allowed })
if ($notAllowed.Count -gt 0) {
    throw "The app jar carries platform-core modules it may not: $($notAllowed -join ', ')."
}
Write-Host "  app jar platform-core modules: $($platformLibs -join ', ')"
if (-not (Test-Path (Join-Path $BackendDir 'securedoc-setup\target\securedoc-setup-0.1.0-SNAPSHOT.jar'))) {
    throw "The setup jar was not built (backend\securedoc-setup)."
}
# Task 0054: both jars say what this build was, and a production jar holds no development key.
foreach ($module in 'securedoc-api', 'securedoc-setup') {
    $jar = Join-Path $BackendDir "$module\target\$module-0.1.0-SNAPSHOT.jar"
    $zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
    try {
        $texts = foreach ($name in 'META-INF/platform/licence-build.properties', 'META-INF/platform/licence-keys.json') {
            $entry = $zip.GetEntry($name)
            if ($entry) {
                $reader = New-Object System.IO.StreamReader($entry.Open())
                try { $reader.ReadToEnd() } finally { $reader.Dispose() }
            }
            elseif ($name -like '*.properties' -or -not $Production) { throw "$module jar: $name is missing." }
            else { Write-Host "  $module jar: no licence-keys.json yet: this build refuses every licence (docs/install/BUILD.md)." -ForegroundColor Yellow }
        }
    }
    finally {
        $zip.Dispose()
    }
    $development = [bool](($texts -join "`n") -match 'development-keys|"development"|dev-lexavault')
    if ($Production -and $development) { throw "$module jar: a production build must not hold a development licence key." }
    if (-not $Production -and -not $development) { throw "$module jar: the development build does not hold the development licence key." }
}
Write-Host "  licence keys checked in both jars"

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

    Run-Step "FRONTEND - UI framework contract" {
        & npm run test:ui-framework
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
