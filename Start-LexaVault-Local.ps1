param(
    [switch]$PullModelIfMissing
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$BackendDir = Join-Path $ProjectRoot "backend"
$ApiTargetDir = Join-Path $BackendDir "securedoc-api\target"
$FrontendDir = Join-Path $ProjectRoot "frontend\securedoc-ui"
$ComposeFile = Join-Path $ProjectRoot "docker\compose\postgres.yml"
$RunDir = Join-Path $ProjectRoot ".run"
$Model = "gemma3:12b"

New-Item -ItemType Directory -Force -Path $RunDir | Out-Null

function Test-TcpPort {
    param([string]$HostName, [int]$Port, [int]$TimeoutMs = 750)

    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne($TimeoutMs, $false)) { return $false }
        $client.EndConnect($async)
        return $true
    }
    catch { return $false }
    finally { $client.Close() }
}

function Wait-ForPort {
    param([string]$Name, [string]$HostName, [int]$Port, [int]$MaxSeconds)

    $deadline = (Get-Date).AddSeconds($MaxSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-TcpPort -HostName $HostName -Port $Port) {
            Write-Host "$Name is ready on $HostName`:$Port" -ForegroundColor Green
            return
        }
        Start-Sleep -Seconds 1
    }
    throw "$Name did not become ready on $HostName`:$Port."
}

function Require-Command {
    param([string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command '$Name' was not found in PATH."
    }
}

function Resolve-Tesseract {
    $cmd = Get-Command "tesseract" -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }

    $candidates = @(
        "C:\Program Files\Tesseract-OCR\tesseract.exe",
        "C:\Program Files (x86)\Tesseract-OCR\tesseract.exe",
        "$env:LOCALAPPDATA\Programs\Tesseract-OCR\tesseract.exe",
        "$env:LOCALAPPDATA\Tesseract-OCR\tesseract.exe"
    )

    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate)) {
            $dir = Split-Path -Parent $candidate
            $env:PATH = "$dir;$env:PATH"
            $tessdata = Join-Path $dir "tessdata"
            if (Test-Path $tessdata) {
                $env:TESSDATA_PREFIX = $tessdata
            }
            return $candidate
        }
    }

    return $null
}

function Resolve-ApiJar {
    if (-not (Test-Path $ApiTargetDir)) { return $null }

    $jars = Get-ChildItem -Path $ApiTargetDir -File -Filter "securedoc-api-*.jar" -ErrorAction SilentlyContinue |
        Where-Object {
            $_.Name -notmatch '\.original$' -and
            $_.Name -notmatch '-sources\.jar$' -and
            $_.Name -notmatch '-javadoc\.jar$'
        } |
        Sort-Object LastWriteTime -Descending

    return $jars | Select-Object -First 1
}

Write-Host ""
Write-Host "=== LexaVault LOCAL startup ===" -ForegroundColor Cyan
Write-Host "Project root: $ProjectRoot"

if (-not (Test-Path $BackendDir)) {
    throw "Backend folder not found: $BackendDir"
}
if (-not (Test-Path $FrontendDir)) {
    throw "Frontend folder not found: $FrontendDir"
}

Require-Command "java"
Require-Command "node"
Require-Command "npm"
Require-Command "ollama"

# Tesseract
$tesseractExe = Resolve-Tesseract
if (-not $tesseractExe) {
    throw "Tesseract OCR was not found. Add its install directory to PATH or install Tesseract."
}
$tesseractVersion = (& $tesseractExe --version 2>&1 | Select-Object -First 1)
Write-Host "Tesseract available: $tesseractVersion" -ForegroundColor Green

# PostgreSQL
if (Test-TcpPort -HostName "localhost" -Port 5432) {
    Write-Host "PostgreSQL already available on localhost:5432." -ForegroundColor Green
}
else {
    Require-Command "docker"
    if (-not (Test-Path $ComposeFile)) {
        throw "PostgreSQL is not running and compose file was not found: $ComposeFile"
    }

    Write-Host "Starting PostgreSQL with Docker Compose..."
    & docker compose -f $ComposeFile up -d
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose failed to start PostgreSQL." }

    Set-Content -Path (Join-Path $RunDir "postgres-started-by-script") -Value "true"
    Wait-ForPort -Name "PostgreSQL" -HostName "localhost" -Port 5432 -MaxSeconds 45
}

# Ollama
if (Test-TcpPort -HostName "localhost" -Port 11434) {
    Write-Host "Ollama already available on localhost:11434." -ForegroundColor Green
}
else {
    Write-Host "Starting Ollama..."
    $ollamaProcess = Start-Process -FilePath "ollama" -ArgumentList "serve" -PassThru -WindowStyle Minimized
    Set-Content -Path (Join-Path $RunDir "ollama.pid") -Value $ollamaProcess.Id
    Set-Content -Path (Join-Path $RunDir "ollama-started-by-script") -Value "true"
    Wait-ForPort -Name "Ollama" -HostName "localhost" -Port 11434 -MaxSeconds 30
}

$tags = Invoke-RestMethod -Uri "http://localhost:11434/api/tags" -Method Get -TimeoutSec 5
$modelNames = @($tags.models | ForEach-Object { $_.name })

if ($modelNames -notcontains $Model) {
    if ($PullModelIfMissing) {
        Write-Host "Pulling required model $Model ..."
        & ollama pull $Model
        if ($LASTEXITCODE -ne 0) { throw "Failed to pull Ollama model $Model." }
    }
    else {
        throw "Required Ollama model '$Model' is not installed. Run 'ollama pull $Model'."
    }
}
Write-Host "Ollama model $Model is installed." -ForegroundColor Green

# Backend - run the already-built executable Spring Boot JAR.
$backendPidFile = Join-Path $RunDir "backend-shell.pid"

if (Test-TcpPort -HostName "localhost" -Port 8080) {
    Write-Host "Backend already appears to be running on http://localhost:8080." -ForegroundColor Yellow
}
else {
    $apiJar = Resolve-ApiJar

    if (-not $apiJar) {
        Write-Host ""
        Write-Host "Built API JAR was not found under:" -ForegroundColor Red
        Write-Host "  $ApiTargetDir"
        Write-Host ""
        Write-Host "Run this first from the project root:"
        Write-Host "  .\build-all.cmd"
        Write-Host ""
        throw "Backend has not been built yet."
    }

    Write-Host "Using backend JAR: $($apiJar.FullName)" -ForegroundColor DarkGray

    $jarPathEscaped = $apiJar.FullName.Replace("'", "''")
    $backendDirEscaped = $BackendDir.Replace("'", "''")
    $effectivePath = $env:PATH.Replace("'", "''")
    $tessdataPrefix = if ($env:TESSDATA_PREFIX) { $env:TESSDATA_PREFIX.Replace("'", "''") } else { "" }

    $backendCommand = @"
`$Host.UI.RawUI.WindowTitle = 'LexaVault Backend - LOCAL'
Set-Location -LiteralPath '$backendDirEscaped'
`$env:PATH = '$effectivePath'
`$env:TESSDATA_PREFIX = '$tessdataPrefix'
`$env:SECUREDOC_AI_PROVIDER = 'ollama'
`$env:SECUREDOC_AI_OLLAMA_BASE_URL = 'http://localhost:11434'
`$env:SECUREDOC_AI_OLLAMA_MODEL = '$Model'
java -jar '$jarPathEscaped' --spring.profiles.active=local
"@

    Write-Host "Starting backend JAR in a separate PowerShell window..."
    $backendShell = Start-Process -FilePath "powershell.exe" `
        -ArgumentList @("-NoProfile", "-NoExit", "-Command", $backendCommand) `
        -PassThru

    Set-Content -Path $backendPidFile -Value $backendShell.Id
    Wait-ForPort -Name "Backend" -HostName "localhost" -Port 8080 -MaxSeconds 120
}

# Frontend
$frontendPidFile = Join-Path $RunDir "frontend-shell.pid"

if (Test-TcpPort -HostName "localhost" -Port 5173) {
    Write-Host "Frontend already appears to be running on http://localhost:5173." -ForegroundColor Yellow
}
else {
    $frontendPathEscaped = $FrontendDir.Replace("'", "''")
    $frontendCommand = @"
`$Host.UI.RawUI.WindowTitle = 'LexaVault Frontend - LOCAL'
Set-Location -LiteralPath '$frontendPathEscaped'
if (-not (Test-Path 'node_modules')) {
    npm ci
    if (`$LASTEXITCODE -ne 0) { throw 'npm ci failed.' }
}
npm run dev
"@

    Write-Host "Starting frontend in a separate PowerShell window..."
    $frontendShell = Start-Process -FilePath "powershell.exe" `
        -ArgumentList @("-NoProfile", "-NoExit", "-Command", $frontendCommand) `
        -PassThru

    Set-Content -Path $frontendPidFile -Value $frontendShell.Id
    Wait-ForPort -Name "Frontend" -HostName "localhost" -Port 5173 -MaxSeconds 60
}

Write-Host ""
Write-Host "=== LexaVault LOCAL is ready ===" -ForegroundColor Green
Write-Host "UI:      http://localhost:5173"
Write-Host "Backend: http://localhost:8080"
Write-Host "AI:      Ollama / $Model"
Write-Host "OCR:     $tesseractExe"
Write-Host ""
