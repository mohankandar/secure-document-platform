param(
    [Parameter(Mandatory=$true)]
    [string]$NodeId,
    [string]$ClusterRoot = ".\performance-cluster",
    [ValidateSet("INFO", "WARN", "ERROR", "DEBUG")]
    [string]$LogLevel = "INFO"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$BackendDir = Join-Path $ProjectRoot "backend"
$ApiTargetDir = Join-Path $BackendDir "securedoc-api\target"
$ClusterRootFull = [IO.Path]::GetFullPath((Join-Path $ProjectRoot $ClusterRoot))
$ManifestPath = Join-Path $ClusterRootFull ".run\cluster-manifest.json"
if (-not (Test-Path $ManifestPath)) { throw "Cluster manifest not found: $ManifestPath" }
$manifest = Get-Content -LiteralPath $ManifestPath -Raw | ConvertFrom-Json
$node = @($manifest.nodes | Where-Object { $_.nodeId -eq $NodeId }) | Select-Object -First 1
if (-not $node) { throw "Node '$NodeId' was not found in the cluster manifest." }

$port = [int]$node.port
try {
    $existing = Invoke-RestMethod -Uri "http://localhost:$port/actuator/health" -Method Get -TimeoutSec 2
    if ($existing.status -eq "UP") {
        Write-Host "$NodeId is already healthy on port $port." -ForegroundColor Yellow
        return
    }
}
catch { }

$apiJar = Get-ChildItem -Path $ApiTargetDir -File -Filter "securedoc-api-*.jar" -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch '\.original$' -and $_.Name -notmatch '-sources\.jar$' -and $_.Name -notmatch '-javadoc\.jar$' } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $apiJar) { throw "Built backend JAR not found. Run .\build-all.cmd first." }

$tesseractCommand = Get-Command tesseract -ErrorAction SilentlyContinue
$tesseractPath = if ($tesseractCommand) { $tesseractCommand.Source } else { $null }
if (-not $tesseractPath) {
    $candidate = "C:\dev\Tesseract-OCR\tesseract.exe"
    if (Test-Path $candidate) { $tesseractPath = $candidate }
}
if (-not $tesseractPath) { throw "Tesseract was not found." }
$tessDir = Split-Path -Parent $tesseractPath
$tessdata = Join-Path $tessDir "tessdata"

$model = [string]$manifest.model
$parallelism = [string]$manifest.aiParallelism
$pageBatch = [string]$manifest.aiPageBatchSize
$maxChars = [string]$manifest.aiPageBatchMaxCharacters
$logDir = [string]$node.logDir
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

function Escape-SingleQuote([string]$Text) { return $Text.Replace("'", "''") }
$backendEscaped = Escape-SingleQuote $BackendDir
$jarEscaped = Escape-SingleQuote $apiJar.FullName
$pathEscaped = Escape-SingleQuote ("$tessDir;$env:PATH")
$tessdataEscaped = if (Test-Path $tessdata) { Escape-SingleQuote $tessdata } else { "" }
$logEscaped = Escape-SingleQuote $logDir
$storageEscaped = Escape-SingleQuote ([string]$manifest.sharedStorageRoot)
$deliveryEscaped = Escape-SingleQuote ([string]$manifest.sharedDeliveryRoot)
$watchedEscaped = Escape-SingleQuote ([string]$manifest.sharedWatchedRoot)

$command = @"
`$Host.UI.RawUI.WindowTitle = 'LexaVault Performance $NodeId :$port'
Set-Location -LiteralPath '$backendEscaped'
`$env:PATH = '$pathEscaped'
`$env:TESSDATA_PREFIX = '$tessdataEscaped'
`$env:SECUREDOC_NODE_ID = '$NodeId'
`$env:SECUREDOC_PROCESSING_COORDINATION_ENABLED = 'true'
`$env:SECUREDOC_PROCESSING_LEASE_DURATION_MS = '30000'
`$env:SECUREDOC_PROCESSING_HEARTBEAT_INTERVAL_MS = '10000'
`$env:SECUREDOC_PROCESSING_RECOVERY_SCAN_INTERVAL_MS = '5000'
`$env:SECUREDOC_WATCHED_FOLDER_NODE_ID = '$NodeId'
`$env:SECUREDOC_WATCHED_FOLDER_ENABLED = 'false'
`$env:SECUREDOC_WATCHED_FOLDER_SCHEDULING_ENABLED = 'false'
`$env:SECUREDOC_LIFECYCLE_PURGE_SCHEDULER_ENABLED = 'false'
`$env:SECUREDOC_AI_PROVIDER = 'ollama'
`$env:SECUREDOC_AI_OLLAMA_BASE_URL = 'http://localhost:11434'
`$env:SECUREDOC_AI_OLLAMA_MODEL = '$model'
`$env:SECUREDOC_AI_OLLAMA_KEEP_ALIVE = '30m'
`$env:SECUREDOC_AI_PARALLELISM = '$parallelism'
`$env:SECUREDOC_AI_OLLAMA_PAGE_BATCH_SIZE = '$pageBatch'
`$env:SECUREDOC_AI_OLLAMA_PAGE_BATCH_MAX_CHARACTERS = '$maxChars'
`$env:SECUREDOC_AI_POOL_ENABLED = 'false'
`$env:SECUREDOC_AI_OLLAMA_PRELOAD_ENABLED = 'false'
`$env:SECUREDOC_LOG_DIR = '$logEscaped'
`$env:SECUREDOC_LOGGING_CONFIG = 'classpath:logback-json-rolling.xml'
`$env:SECUREDOC_LOG_LEVEL_APP = '$LogLevel'
java -jar '$jarEscaped' --spring.profiles.active=local --spring.main.banner-mode=off --server.port=$port --securedoc.storage.local.root-path='$storageEscaped' --securedoc.delivery.local.output-path='$deliveryEscaped' --securedoc.intake.watched-folder.root-path='$watchedEscaped'
"@

$shell = Start-Process -FilePath "powershell.exe" -ArgumentList @("-NoProfile", "-NoExit", "-Command", $command) -PassThru
$deadline = (Get-Date).AddSeconds(120)
$healthy = $false
while ((Get-Date) -lt $deadline) {
    try {
        $h = Invoke-RestMethod -Uri "http://localhost:$port/actuator/health" -Method Get -TimeoutSec 3
        if ($h.status -eq "UP") { $healthy = $true; break }
    }
    catch { }
    Start-Sleep -Seconds 1
}
if (-not $healthy) { throw "$NodeId did not become healthy on port $port." }

$appPid = $null
try {
    $listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction Stop | Select-Object -First 1
    if ($listener) { $appPid = [int]$listener.OwningProcess }
}
catch { }
$node.shellPid = $shell.Id
$node.appPid = $appPid
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $ManifestPath -Encoding UTF8
Write-Host "$NodeId restarted and healthy on http://localhost:$port (appPid=$appPid)." -ForegroundColor Green
