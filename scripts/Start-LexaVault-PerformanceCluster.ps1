param(
    [int[]]$Ports = @(10080, 10081, 10082),
    [string]$AiModel = "",
    [string]$ClusterRoot = ".\performance-cluster",
    [switch]$PullModelIfMissing,
    [switch]$SkipAiWarmup,
    [ValidateSet("INFO", "WARN", "ERROR", "DEBUG")]
    [string]$LogLevel = "INFO"
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$BackendDir = Join-Path $ProjectRoot "backend"
$ApiTargetDir = Join-Path $BackendDir "securedoc-api\target"
$ComposeFile = Join-Path $ProjectRoot "docker\compose\postgres.yml"
$ClusterRoot = [IO.Path]::GetFullPath((Join-Path $ProjectRoot $ClusterRoot))
$RunDir = Join-Path $ClusterRoot ".run"
$LogsRoot = Join-Path $ClusterRoot "logs"
$SharedStorageRoot = Join-Path $ClusterRoot "shared-storage"
$SharedDeliveryRoot = Join-Path $ClusterRoot "delivery-output"
$SharedWatchedRoot = Join-Path $ClusterRoot "watched-intake"
$ManifestPath = Join-Path $RunDir "cluster-manifest.json"

if ($Ports.Count -lt 2) { throw "Performance cluster requires at least two backend ports." }
if (($Ports | Sort-Object -Unique).Count -ne $Ports.Count) { throw "Ports must be unique." }
if (@($Ports | Where-Object { $_ -lt 1024 -or $_ -gt 65535 }).Count -gt 0) { throw "Ports must be between 1024 and 65535." }

$Model = if ($AiModel) { $AiModel } elseif ($env:SECUREDOC_AI_MODEL) { $env:SECUREDOC_AI_MODEL } elseif ($env:SECUREDOC_AI_OLLAMA_MODEL) { $env:SECUREDOC_AI_OLLAMA_MODEL } else { "qwen3.5:4b" }
$AiKeepAlive = if ($env:SECUREDOC_AI_OLLAMA_KEEP_ALIVE) { $env:SECUREDOC_AI_OLLAMA_KEEP_ALIVE } else { "30m" }
$AiParallelism = if ($env:SECUREDOC_AI_PARALLELISM) { $env:SECUREDOC_AI_PARALLELISM } else { "2" }
$AiPageBatchSize = if ($env:SECUREDOC_AI_OLLAMA_PAGE_BATCH_SIZE) { $env:SECUREDOC_AI_OLLAMA_PAGE_BATCH_SIZE } elseif ($Model -eq "qwen3.5:4b") { "4" } else { "2" }
$AiPageBatchMaxCharacters = if ($env:SECUREDOC_AI_OLLAMA_PAGE_BATCH_MAX_CHARACTERS) { $env:SECUREDOC_AI_OLLAMA_PAGE_BATCH_MAX_CHARACTERS } else { "14000" }

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

function Wait-ForHealth {
    param([string]$BaseUrl, [int]$MaxSeconds = 120)
    $deadline = (Get-Date).AddSeconds($MaxSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -Method Get -TimeoutSec 3
            if ($health.status -eq "UP") { return }
        }
        catch { }
        Start-Sleep -Seconds 1
    }
    throw "Backend did not become healthy at $BaseUrl within $MaxSeconds second(s)."
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
        if ($candidate -and (Test-Path $candidate)) { return $candidate }
    }
    return $null
}

function Resolve-ApiJar {
    if (-not (Test-Path $ApiTargetDir)) { return $null }
    return Get-ChildItem -Path $ApiTargetDir -File -Filter "securedoc-api-*.jar" -ErrorAction SilentlyContinue |
        Where-Object {
            $_.Name -notmatch '\.original$' -and
            $_.Name -notmatch '-sources\.jar$' -and
            $_.Name -notmatch '-javadoc\.jar$'
        } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
}

Write-Host ""
Write-Host "=== LexaVault Performance Cluster startup ===" -ForegroundColor Cyan
Write-Host "Project root:  $ProjectRoot"
Write-Host "Cluster root:  $ClusterRoot"
Write-Host "Ports:         $($Ports -join ', ')"
Write-Host "AI model:      $Model"
Write-Host "AI batch:      parallelism=$AiParallelism / pages=$AiPageBatchSize / maxChars=$AiPageBatchMaxCharacters"
Write-Host ""

Require-Command "java"
Require-Command "ollama"
$tesseractExe = Resolve-Tesseract
if (-not $tesseractExe) { throw "Tesseract OCR was not found." }

$apiJar = Resolve-ApiJar
if (-not $apiJar) {
    throw "Built backend JAR was not found. Run .\build-all.cmd first."
}

foreach ($port in $Ports) {
    if (Test-TcpPort -HostName "127.0.0.1" -Port $port) {
        throw "Port $port is already in use. Stop any previous performance cluster before starting a new one."
    }
}

New-Item -ItemType Directory -Force -Path $RunDir, $LogsRoot, $SharedStorageRoot, $SharedDeliveryRoot, $SharedWatchedRoot | Out-Null

# PostgreSQL. Start the existing local compose service only if necessary.
if (-not (Test-TcpPort -HostName "127.0.0.1" -Port 5432)) {
    Require-Command "docker"
    if (-not (Test-Path $ComposeFile)) { throw "PostgreSQL is unavailable and compose file was not found: $ComposeFile" }
    Write-Host "Starting PostgreSQL..."
    & docker compose -f $ComposeFile up -d
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose failed to start PostgreSQL." }
    $deadline = (Get-Date).AddSeconds(45)
    while ((Get-Date) -lt $deadline -and -not (Test-TcpPort -HostName "127.0.0.1" -Port 5432)) { Start-Sleep -Seconds 1 }
    if (-not (Test-TcpPort -HostName "127.0.0.1" -Port 5432)) { throw "PostgreSQL did not become ready." }
}
Write-Host "PostgreSQL ready on localhost:5432." -ForegroundColor Green

# Ollama. This R3 local cluster deliberately shares one measured GPU endpoint so app-tier scaling and AI saturation are visible separately.
if (-not (Test-TcpPort -HostName "127.0.0.1" -Port 11434)) {
    Write-Host "Starting Ollama..."
    Start-Process -FilePath "ollama" -ArgumentList "serve" -WindowStyle Minimized | Out-Null
    $deadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $deadline -and -not (Test-TcpPort -HostName "127.0.0.1" -Port 11434)) { Start-Sleep -Seconds 1 }
    if (-not (Test-TcpPort -HostName "127.0.0.1" -Port 11434)) { throw "Ollama did not become ready." }
}

$tags = Invoke-RestMethod -Uri "http://localhost:11434/api/tags" -Method Get -TimeoutSec 5
$modelNames = @($tags.models | ForEach-Object { $_.name })
if ($modelNames -notcontains $Model) {
    if ($PullModelIfMissing) {
        & ollama pull $Model
        if ($LASTEXITCODE -ne 0) { throw "Failed to pull Ollama model $Model." }
    }
    else {
        throw "Required Ollama model '$Model' is not installed."
    }
}

if (-not $SkipAiWarmup) {
    Write-Host "Warming Ollama model $Model..."
    $body = @{ model=$Model; prompt=""; stream=$false; keep_alive=$AiKeepAlive } | ConvertTo-Json -Compress
    Invoke-RestMethod -Uri "http://localhost:11434/api/generate" -Method Post -ContentType "application/json" -Body $body -TimeoutSec 300 | Out-Null
}
Write-Host "Ollama ready: $Model" -ForegroundColor Green

$nodes = New-Object System.Collections.Generic.List[object]
$jarEscaped = $apiJar.FullName.Replace("'", "''")
$backendEscaped = $BackendDir.Replace("'", "''")
$tessDir = Split-Path -Parent $tesseractExe
$tessdata = Join-Path $tessDir "tessdata"
$effectivePath = "$tessDir;$env:PATH".Replace("'", "''")
$tessdataEscaped = if (Test-Path $tessdata) { $tessdata.Replace("'", "''") } else { "" }
$storageEscaped = $SharedStorageRoot.Replace("'", "''")
$deliveryEscaped = $SharedDeliveryRoot.Replace("'", "''")
$watchedEscaped = $SharedWatchedRoot.Replace("'", "''")

for ($i = 0; $i -lt $Ports.Count; $i++) {
    $port = [int]$Ports[$i]
    $nodeId = "PERF-APP-{0:D2}" -f ($i + 1)
    $baseUrl = "http://localhost:$port"
    $nodeLogDir = Join-Path $LogsRoot $nodeId
    New-Item -ItemType Directory -Force -Path $nodeLogDir | Out-Null
    $logDirEscaped = $nodeLogDir.Replace("'", "''")

    $command = @"
`$Host.UI.RawUI.WindowTitle = 'LexaVault Performance $nodeId :$port'
Set-Location -LiteralPath '$backendEscaped'
`$env:PATH = '$effectivePath'
`$env:TESSDATA_PREFIX = '$tessdataEscaped'
`$env:SECUREDOC_NODE_ID = '$nodeId'
`$env:SECUREDOC_PROCESSING_COORDINATION_ENABLED = 'true'
`$env:SECUREDOC_PROCESSING_LEASE_DURATION_MS = '30000'
`$env:SECUREDOC_PROCESSING_HEARTBEAT_INTERVAL_MS = '10000'
`$env:SECUREDOC_PROCESSING_RECOVERY_SCAN_INTERVAL_MS = '5000'
`$env:SECUREDOC_WATCHED_FOLDER_NODE_ID = '$nodeId'
`$env:SECUREDOC_WATCHED_FOLDER_ENABLED = 'false'
`$env:SECUREDOC_WATCHED_FOLDER_SCHEDULING_ENABLED = 'false'
`$env:SECUREDOC_LIFECYCLE_PURGE_SCHEDULER_ENABLED = 'false'
`$env:SECUREDOC_AI_PROVIDER = 'ollama'
`$env:SECUREDOC_AI_OLLAMA_BASE_URL = 'http://localhost:11434'
`$env:SECUREDOC_AI_OLLAMA_MODEL = '$Model'
`$env:SECUREDOC_AI_OLLAMA_KEEP_ALIVE = '$AiKeepAlive'
`$env:SECUREDOC_AI_PARALLELISM = '$AiParallelism'
`$env:SECUREDOC_AI_OLLAMA_PAGE_BATCH_SIZE = '$AiPageBatchSize'
`$env:SECUREDOC_AI_OLLAMA_PAGE_BATCH_MAX_CHARACTERS = '$AiPageBatchMaxCharacters'
`$env:SECUREDOC_AI_POOL_ENABLED = 'false'
`$env:SECUREDOC_AI_OLLAMA_PRELOAD_ENABLED = 'false'
`$env:SECUREDOC_LOG_DIR = '$logDirEscaped'
`$env:SECUREDOC_LOGGING_CONFIG = 'classpath:logback-json-rolling.xml'
`$env:SECUREDOC_LOG_LEVEL_APP = '$LogLevel'
java -jar '$jarEscaped' --spring.profiles.active=local --spring.main.banner-mode=off --server.port=$port --securedoc.storage.local.root-path='$storageEscaped' --securedoc.delivery.local.output-path='$deliveryEscaped' --securedoc.intake.watched-folder.root-path='$watchedEscaped'
"@

    Write-Host "Starting $nodeId on $baseUrl ..."
    $shell = Start-Process -FilePath "powershell.exe" -ArgumentList @("-NoProfile", "-NoExit", "-Command", $command) -PassThru
    Wait-ForHealth -BaseUrl $baseUrl -MaxSeconds 120

    $appPid = $null
    try {
        $listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction Stop | Select-Object -First 1
        if ($listener) { $appPid = [int]$listener.OwningProcess }
    }
    catch { }

    $nodes.Add([pscustomobject]@{
        nodeId = $nodeId
        port = $port
        baseUrl = $baseUrl
        shellPid = $shell.Id
        appPid = $appPid
        logDir = $nodeLogDir
        logFile = (Join-Path $nodeLogDir "securedoc-platform.json")
    })
    Write-Host "$nodeId healthy (appPid=$appPid)." -ForegroundColor Green
}

$manifest = [ordered]@{
    startedAt = (Get-Date).ToString("o")
    model = $Model
    aiParallelism = [int]$AiParallelism
    aiPageBatchSize = [int]$AiPageBatchSize
    aiPageBatchMaxCharacters = [int]$AiPageBatchMaxCharacters
    sharedAiEndpoint = "http://localhost:11434"
    sharedStorageRoot = $SharedStorageRoot
    sharedDeliveryRoot = $SharedDeliveryRoot
    sharedWatchedRoot = $SharedWatchedRoot
    nodes = $nodes.ToArray()
    note = "R3 local performance cluster. Three app nodes share PostgreSQL, storage and one measured local Ollama GPU endpoint. Watched-folder scheduling and lifecycle purge are disabled to reduce benchmark noise."
}
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $ManifestPath -Encoding UTF8

Write-Host ""
Write-Host "=== Performance cluster ready ===" -ForegroundColor Green
foreach ($node in $nodes) { Write-Host ("{0}: {1} / log={2}" -f $node.nodeId, $node.baseUrl, $node.logFile) }
Write-Host "Manifest: $ManifestPath"
Write-Host "Stop with: .\scripts\Stop-LexaVault-PerformanceCluster.ps1" -ForegroundColor Cyan
Write-Host ""
