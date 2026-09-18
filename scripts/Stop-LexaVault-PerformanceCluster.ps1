param(
    [string]$ClusterRoot = ".\performance-cluster"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$ClusterRoot = [IO.Path]::GetFullPath((Join-Path $ProjectRoot $ClusterRoot))
$ManifestPath = Join-Path $ClusterRoot ".run\cluster-manifest.json"

if (-not (Test-Path $ManifestPath)) {
    Write-Host "No performance-cluster manifest found: $ManifestPath" -ForegroundColor Yellow
    return
}

$manifest = Get-Content -LiteralPath $ManifestPath -Raw | ConvertFrom-Json
Write-Host ""
Write-Host "=== Stopping LexaVault Performance Cluster ===" -ForegroundColor Cyan

foreach ($node in @($manifest.nodes)) {
    $nodeId = [string]$node.nodeId
    $port = [int]$node.port
    $appPid = $null
    try {
        $listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction Stop | Select-Object -First 1
        if ($listener) { $appPid = [int]$listener.OwningProcess }
    }
    catch { }

    if ($appPid) {
        try {
            Stop-Process -Id $appPid -Force -ErrorAction Stop
            Write-Host "Stopped $nodeId app PID $appPid." -ForegroundColor Green
        }
        catch { Write-Host "Could not stop $nodeId app PID ${appPid}: $($_.Exception.Message)" -ForegroundColor Yellow }
    }

    $shellPid = 0
    if ($node.shellPid) { $shellPid = [int]$node.shellPid }
    if ($shellPid -gt 0) {
        try { Stop-Process -Id $shellPid -Force -ErrorAction SilentlyContinue } catch { }
    }
}

Write-Host "PostgreSQL and Ollama were intentionally left running." -ForegroundColor DarkGray
Write-Host "Cluster data/logs remain under: $ClusterRoot"
Write-Host ""
