param(
    [string]$ScannerMode = "",
    [switch]$PullModelIfMissing,
    [switch]$SkipAiWarmup,
    [string]$AiModel = "",
    [ValidateSet("Readable", "Json")]
    [string]$LoggingFormat = "Json",
    [ValidateSet("TRACE", "DEBUG", "INFO", "WARN", "ERROR")]
    [string]$LogLevel = "DEBUG",
    [switch]$EnableRollingFileLog,
    # Start the backend with the AI model off: the existing provider setting (securedoc.ai.provider) is set to
    # "heuristic", the built-in rules-only reader, so Ollama is neither needed nor called. LOCAL testing only.
    [switch]$AiOff
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$BackendDir = Join-Path $ProjectRoot "backend"
$ApiTargetDir = Join-Path $BackendDir "securedoc-api\target"
$FrontendDir = Join-Path $ProjectRoot "frontend\securedoc-ui"
$ComposeFile = Join-Path $ProjectRoot "docker\compose\postgres.yml"
$RunDir = Join-Path $ProjectRoot ".run"
$WatchedIntakeDir = Join-Path $BackendDir "watched-intake"
$ScannerBridgeStartScript = Join-Path $ProjectRoot "Start-LexaVault-ScannerBridge.ps1"
$ScannerBridgeStopScript = Join-Path $ProjectRoot "Stop-LexaVault-ScannerBridge.ps1"
$ScannerBridgePidFile = Join-Path $RunDir "scanner-bridge-shell.pid"
$scannerBridgePortText = if ($env:SECUREDOC_SCAN_BRIDGE_PORT) { $env:SECUREDOC_SCAN_BRIDGE_PORT } else { "10090" }
$ScannerBridgePort = 0
if (-not [int]::TryParse($scannerBridgePortText, [ref]$ScannerBridgePort) -or $ScannerBridgePort -le 0) {
    throw "SECUREDOC_SCAN_BRIDGE_PORT must be a positive integer."
}
$scannerModeCandidate = if ($ScannerMode) { $ScannerMode } elseif ($env:SECUREDOC_SCAN_BRIDGE_MODE) { $env:SECUREDOC_SCAN_BRIDGE_MODE } else { "SIMULATOR" }
$scannerModeCandidate = $scannerModeCandidate.ToUpperInvariant()
if ($scannerModeCandidate -eq "PRINTER") { $scannerModeCandidate = "PHYSICAL" }
if ($scannerModeCandidate -notin @("SIMULATOR", "PHYSICAL")) {
    throw "Scanner mode must be SIMULATOR, PHYSICAL, or PRINTER."
}
$EffectiveScannerMode = $scannerModeCandidate
$Model = if ($AiModel) { $AiModel } elseif ($env:SECUREDOC_AI_MODEL) { $env:SECUREDOC_AI_MODEL } elseif ($env:SECUREDOC_AI_OLLAMA_MODEL) { $env:SECUREDOC_AI_OLLAMA_MODEL } else { "qwen3.5:4b" }
. (Join-Path $PSScriptRoot "scripts\LexaVault-OllamaParallel.ps1")
$AiRequestsAtOnce = Get-LexaVaultAiRequestsAtOnce
$AiKeepAlive = if ($env:SECUREDOC_AI_OLLAMA_KEEP_ALIVE) { $env:SECUREDOC_AI_OLLAMA_KEEP_ALIVE } else { "30m" }
$aiParallelismText = if ($env:SECUREDOC_AI_PARALLELISM) { $env:SECUREDOC_AI_PARALLELISM } else { "2" }
$AiParallelism = 0
if (-not [int]::TryParse($aiParallelismText, [ref]$AiParallelism) -or $AiParallelism -lt 1 -or $AiParallelism -gt 16) {
    throw "SECUREDOC_AI_PARALLELISM must be an integer between 1 and 16."
}
$aiPageBatchSizeText = if ($env:SECUREDOC_AI_OLLAMA_PAGE_BATCH_SIZE) {
    $env:SECUREDOC_AI_OLLAMA_PAGE_BATCH_SIZE
} elseif ($Model -eq "qwen3.5:4b") {
    "4"
} else {
    "2"
}
$AiPageBatchSize = 0
if (-not [int]::TryParse($aiPageBatchSizeText, [ref]$AiPageBatchSize) -or $AiPageBatchSize -lt 1 -or $AiPageBatchSize -gt 8) {
    throw "SECUREDOC_AI_OLLAMA_PAGE_BATCH_SIZE must be an integer between 1 and 8."
}
$aiPageBatchMaxCharactersText = if ($env:SECUREDOC_AI_OLLAMA_PAGE_BATCH_MAX_CHARACTERS) { $env:SECUREDOC_AI_OLLAMA_PAGE_BATCH_MAX_CHARACTERS } else { "14000" }
$AiPageBatchMaxCharacters = 0
if (-not [int]::TryParse($aiPageBatchMaxCharactersText, [ref]$AiPageBatchMaxCharacters) -or $AiPageBatchMaxCharacters -lt 1000 -or $AiPageBatchMaxCharacters -gt 200000) {
    throw "SECUREDOC_AI_OLLAMA_PAGE_BATCH_MAX_CHARACTERS must be an integer between 1000 and 200000."
}
if ($EnableRollingFileLog -and $LoggingFormat -ne "Json") {
    throw "-EnableRollingFileLog requires -LoggingFormat Json."
}
$LoggingConfig = if ($LoggingFormat -eq "Readable") {
    "classpath:logback-local.xml"
}
elseif ($EnableRollingFileLog) {
    "classpath:logback-json-rolling.xml"
}
else {
    "classpath:logback-json.xml"
}
$BannerMode = if ($LoggingFormat -eq "Json") { "off" } else { "console" }
$AiProvider = if ($AiOff) { "heuristic" } else { "ollama" }
# Task 0110 (hardening): the text model is pinned by digest, as the vision model is. A model without an approved digest
# here (or in SECUREDOC_AI_MODEL_DIGEST) is not started; a change of model means the model, its digest and a benchmark.
$ApprovedTextModelDigests = @{
    "qwen3.5:9b" = "56671c2ab9385f9cfcb404638e32cd62d88e3501d44822208363c010179a3c90"
}
$TextModelDigest = if ($env:SECUREDOC_AI_MODEL_DIGEST) { $env:SECUREDOC_AI_MODEL_DIGEST } elseif ($ApprovedTextModelDigests.ContainsKey($Model)) { $ApprovedTextModelDigests[$Model] } else { "" }
if (-not $AiOff -and -not $TextModelDigest) {
    throw "The text model $Model has no approved digest. Use -AiModel qwen3.5:9b, or set SECUREDOC_AI_MODEL_DIGEST to its 64-character digest after its benchmark."
}
$AiSummary = if ($AiOff) { "off (heuristic reader, no Ollama)" } else { "Ollama / $Model" }

New-Item -ItemType Directory -Force -Path $RunDir | Out-Null
New-Item -ItemType Directory -Force -Path $WatchedIntakeDir | Out-Null

function Test-TcpPort {
    param([string]$HostName, [int]$Port, [int]$TimeoutMs = 750)

    # Try IPv4 and IPv6. TcpClient() on Windows PowerShell is IPv4 only, while Vite (Node 22) may listen on ::1
    # alone for "localhost"; checking only IPv4 gave the false "Frontend did not become ready" stop (U004, U018).
    $addresses = if ($HostName -eq "localhost") {
        @([System.Net.IPAddress]::Loopback, [System.Net.IPAddress]::IPv6Loopback)
    }
    else {
        try { @([System.Net.Dns]::GetHostAddresses($HostName)) } catch { @() }
    }
    foreach ($address in $addresses) {
        $client = New-Object System.Net.Sockets.TcpClient($address.AddressFamily)
        try {
            $async = $client.BeginConnect($address, $Port, $null, $null)
            if ($async.AsyncWaitHandle.WaitOne($TimeoutMs, $false)) {
                $client.EndConnect($async)
                return $true
            }
        }
        catch { }
        finally { $client.Close() }
    }
    return $false
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

function Get-ScannerBridgeConfiguration {
    param([int]$PortNumber)

    try {
        return Invoke-RestMethod -Uri "http://127.0.0.1:$PortNumber/api/v1/configuration" -Method Get -TimeoutSec 3
    }
    catch {
        return $null
    }
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
Write-Host "Logging:      $LoggingFormat ($LoggingConfig)"
Write-Host "App log level: $LogLevel"
Write-Host "Rolling file:  $EnableRollingFileLog"
Write-Host "Scanner mode:  $EffectiveScannerMode"
Write-Host "AI:            $AiSummary"

if (-not (Test-Path $BackendDir)) {
    throw "Backend folder not found: $BackendDir"
}
if (-not (Test-Path $FrontendDir)) {
    throw "Frontend folder not found: $FrontendDir"
}

Require-Command "java"
Require-Command "node"
Require-Command "npm"
if (-not $AiOff) { Require-Command "ollama" }

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
    # Task 0055: an existing container is started as it is, never made again for a changed setting.
    & docker compose -f $ComposeFile up -d --no-recreate
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose failed to start PostgreSQL." }

    Set-Content -Path (Join-Path $RunDir "postgres-started-by-script") -Value "true"
    Wait-ForPort -Name "PostgreSQL" -HostName "localhost" -Port 5432 -MaxSeconds 45
}

# Ollama (skipped with -AiOff)
if ($AiOff) {
    Write-Host "AI is off: Ollama is not checked, started or warmed up." -ForegroundColor Yellow
}
elseif (Test-TcpPort -HostName "localhost" -Port 11434) {
    Write-Host "Ollama already available on localhost:11434." -ForegroundColor Green
    Write-RunningOllamaParallel -RequestsAtOnce $AiRequestsAtOnce
}
else {
    Write-Host "Starting Ollama..."
    $ollamaProcess = Start-LexaVaultOllama -RequestsAtOnce $AiRequestsAtOnce
    Set-Content -Path (Join-Path $RunDir "ollama.pid") -Value $ollamaProcess.Id
    Set-Content -Path (Join-Path $RunDir "ollama-started-by-script") -Value "true"
    Wait-ForPort -Name "Ollama" -HostName "localhost" -Port 11434 -MaxSeconds 30
}

if (-not $AiOff) {
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
    $tags = Invoke-RestMethod -Uri "http://localhost:11434/api/tags" -Method Get -TimeoutSec 5
    $installedDigest = @($tags.models | Where-Object { $_.name -eq $Model } | ForEach-Object { $_.digest }) | Select-Object -First 1
    if (("$installedDigest" -replace "^sha256:", "").ToLowerInvariant() -ne $TextModelDigest.ToLowerInvariant()) {
        throw "The installed text model $Model is not the approved one (digest differs). Pull the approved model again (ollama pull $Model)."
    }
    Write-Host "Ollama model $Model is the approved one (digest checked)." -ForegroundColor Green

    if (-not $SkipAiWarmup) {
        Write-Host "Preloading Ollama model $Model (keep_alive=$AiKeepAlive)..."
        $warmupBody = @{ model = $Model; prompt = ""; stream = $false; keep_alive = $AiKeepAlive } | ConvertTo-Json -Compress
        try {
            Invoke-RestMethod -Uri "http://localhost:11434/api/generate" -Method Post `
                -ContentType "application/json" -Body $warmupBody -TimeoutSec 300 | Out-Null
            Write-Host "Ollama model $Model is warm and resident." -ForegroundColor Green
        }
        catch {
            throw "Ollama model warmup failed for '$Model': $($_.Exception.Message)"
        }
    }
    else {
        Write-Host "Skipping Ollama model warmup by request." -ForegroundColor Yellow
    }
}

# Backend - run the already-built executable Spring Boot JAR.
$backendPidFile = Join-Path $RunDir "backend-shell.pid"

if (Test-TcpPort -HostName "localhost" -Port 8080) {
    if ($AiOff) {
        # Fail closed: a backend that is already running keeps its own AI setting, so -AiOff would not take effect.
        throw "-AiOff needs a fresh backend, but one is already running on localhost:8080. Run .Stop-LexaVault-Local.ps1 first."
    }
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

    # Task 0037: the schema step, and on a fresh database setup and the demo step, before the backend starts.
    & (Join-Path $ProjectRoot "scripts\Initialize-LexaVault-LocalDatabase.ps1") -JarPath $apiJar.FullName

    $jarPathEscaped = $apiJar.FullName.Replace("'", "''")
    $backendDirEscaped = $BackendDir.Replace("'", "''")
    $effectivePath = $env:PATH.Replace("'", "''")
    $tessdataPrefix = if ($env:TESSDATA_PREFIX) { $env:TESSDATA_PREFIX.Replace("'", "''") } else { "" }

    $backendCommand = @"
`$Host.UI.RawUI.WindowTitle = 'LexaVault Backend - LOCAL'
Set-Location -LiteralPath '$backendDirEscaped'
`$env:PATH = '$effectivePath'
`$env:TESSDATA_PREFIX = '$tessdataPrefix'
`$env:SECUREDOC_AI_PROVIDER = '$AiProvider'
`$env:SECUREDOC_AI_OLLAMA_BASE_URL = 'http://localhost:11434'
`$env:SECUREDOC_AI_OLLAMA_MODEL = '$Model'
`$env:SECUREDOC_AI_MODEL_DIGEST = '$TextModelDigest'
`$env:SECUREDOC_AI_OLLAMA_KEEP_ALIVE = '$AiKeepAlive'
`$env:SECUREDOC_AI_PARALLELISM = '$AiParallelism'
`$env:SECUREDOC_AI_REQUESTS_AT_ONCE = '$AiRequestsAtOnce'
`$env:SECUREDOC_AI_OLLAMA_PAGE_BATCH_SIZE = '$AiPageBatchSize'
`$env:SECUREDOC_AI_OLLAMA_PAGE_BATCH_MAX_CHARACTERS = '$AiPageBatchMaxCharacters'
# This launcher already performs (or explicitly skips) warmup above; prevent a duplicate in-app preload.
`$env:SECUREDOC_AI_OLLAMA_PRELOAD_ENABLED = 'false'
`$env:SECUREDOC_LOGGING_CONFIG = '$LoggingConfig'
`$env:SECUREDOC_LOG_LEVEL_APP = '$LogLevel'
java -jar '$jarPathEscaped' --spring.profiles.active=local --spring.main.banner-mode=$BannerMode
"@

    Write-Host "Starting backend JAR in a separate PowerShell window..."
    $backendShell = Start-Process -FilePath "powershell.exe" `
        -ArgumentList @("-NoProfile", "-NoExit", "-Command", $backendCommand) `
        -PassThru

    Set-Content -Path $backendPidFile -Value $backendShell.Id
    Wait-ForPort -Name "Backend" -HostName "localhost" -Port 8080 -MaxSeconds 120
}

# Scanner Bridge - one-command local startup for simulator or physical-scanner mode.
if (-not (Test-Path $ScannerBridgeStartScript)) {
    throw "Scanner Bridge startup script not found: $ScannerBridgeStartScript"
}

$runningBridgeConfig = Get-ScannerBridgeConfiguration -PortNumber $ScannerBridgePort
if ($runningBridgeConfig) {
    $runningMode = [string]$runningBridgeConfig.captureMode
    if (-not [string]::IsNullOrWhiteSpace($runningMode) -and $runningMode.ToUpperInvariant() -ne $EffectiveScannerMode) {
        if (Test-Path $ScannerBridgePidFile) {
            Write-Host "Scanner Bridge is running in $runningMode mode; restarting it in $EffectiveScannerMode mode..." -ForegroundColor Yellow
            & $ScannerBridgeStopScript
            Start-Sleep -Milliseconds 750
        }
        else {
            throw "Scanner Bridge is already running in $runningMode mode on port $ScannerBridgePort and was not started by this project. Stop it before switching to $EffectiveScannerMode mode."
        }
    }
}
elseif (Test-TcpPort -HostName "127.0.0.1" -Port $ScannerBridgePort) {
    throw "Port $ScannerBridgePort is in use, but the LexaVault Scanner Bridge configuration endpoint is not available."
}

Write-Host "Starting Scanner Bridge in $EffectiveScannerMode mode as part of local startup..."
& $ScannerBridgeStartScript -Mode $EffectiveScannerMode

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
Write-Host "AI:      $AiSummary"
Write-Host "AI batch: parallelism=$AiParallelism / pages=$AiPageBatchSize / maxChars=$AiPageBatchMaxCharacters"
Write-Host "OCR:     $tesseractExe"
Write-Host "Logging: $LoggingFormat / app=$LogLevel / rollingFile=$EnableRollingFileLog"
Write-Host "Watched intake: $WatchedIntakeDir (LOCAL automatic scan; default every 10 seconds)"
Write-Host "Scanner Bridge: http://127.0.0.1:$ScannerBridgePort / $EffectiveScannerMode"
if ($EffectiveScannerMode -eq "SIMULATOR") {
    Write-Host "Scanner usage: Start Scan -> Select Test PDF." -ForegroundColor Cyan
}
else {
    Write-Host "Scanner usage: PHYSICAL mode selected. The bridge will use Windows WIA to acquire the paper package and consolidate it into one PDF." -ForegroundColor Yellow
}
Write-Host ""
