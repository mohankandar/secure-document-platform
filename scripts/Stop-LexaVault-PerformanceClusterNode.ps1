param(
    [Parameter(Mandatory=$true)]
    [string]$NodeId,
    [string]$ClusterRoot = ".\performance-cluster"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$ClusterRoot = [IO.Path]::GetFullPath((Join-Path $ProjectRoot $ClusterRoot))
$ManifestPath = Join-Path $ClusterRoot ".run\cluster-manifest.json"
if (-not (Test-Path $ManifestPath)) { throw "Cluster manifest not found: $ManifestPath" }
$manifest = Get-Content -LiteralPath $ManifestPath -Raw | ConvertFrom-Json
$node = @($manifest.nodes | Where-Object { $_.nodeId -eq $NodeId }) | Select-Object -First 1
if (-not $node) { throw "Node '$NodeId' was not found in the cluster manifest." }

$port = [int]$node.port
$appPid = $null
try {
    $listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction Stop | Select-Object -First 1
    if ($listener) { $appPid = [int]$listener.OwningProcess }
}
catch { }

if ($appPid) {
    Stop-Process -Id $appPid -Force
    Write-Host "Stopped $NodeId application PID $appPid on port $port." -ForegroundColor Green
}
else {
    Write-Host "$NodeId does not currently have a listener on port $port." -ForegroundColor Yellow
}

if ($node.shellPid) {
    try { Stop-Process -Id ([int]$node.shellPid) -Force -ErrorAction SilentlyContinue } catch { }
}
