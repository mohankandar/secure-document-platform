param(
    [Parameter(Mandatory=$true)]
    [string]$ScaleRunDirectory,
    [string]$SoakRunDirectory = "",
    [double]$ExpectedPeakPackagesPerMinute = 0,
    [double]$HeadroomPercent = 30,
    [string]$OutputPath = ""
)

$ErrorActionPreference = "Stop"
$ScaleRunDirectory = (Resolve-Path -LiteralPath $ScaleRunDirectory).Path
$summaryPath = Join-Path $ScaleRunDirectory "cluster-summary.csv"
$resourcePath = Join-Path $ScaleRunDirectory "cluster-resource-summary.csv"
$nodeResourcePath = Join-Path $ScaleRunDirectory "node-resource-summary.csv"
if (-not (Test-Path $summaryPath)) { throw "Run Summarize-LexaVault-PerformanceCluster.ps1 first. Missing: $summaryPath" }

$summary = @(Import-Csv -LiteralPath $summaryPath | Sort-Object { [int]$_.Concurrency })
$successful = @($summary | Where-Object { [int]$_.Failed -eq 0 -and [int]$_.TimedOutOrError -eq 0 -and [double]$_.PackagesPerMinute -gt 0 })
if ($successful.Count -eq 0) { throw "No successful scale level is available for sizing guidance." }

$peak = $successful | Sort-Object { [double]$_.PackagesPerMinute } -Descending | Select-Object -First 1
$peakThroughput = [double]$peak.PackagesPerMinute
$threshold = $peakThroughput * 0.95
$efficient = $successful | Where-Object { [double]$_.PackagesPerMinute -ge $threshold } | Sort-Object { [int]$_.Concurrency } | Select-Object -First 1

$maxNodeMemoryMB = $null
if (Test-Path $nodeResourcePath) {
    $nodeResources = @(Import-Csv -LiteralPath $nodeResourcePath)
    $maxNodeMemoryMB = ($nodeResources | Measure-Object MaxAppWorkingSetMB -Maximum).Maximum
}
$minFreeMemoryMB = $null; $maxGpuMemoryMB = $null; $p95Gpu = $null; $p95Cpu = $null
if (Test-Path $resourcePath) {
    $resources = @(Import-Csv -LiteralPath $resourcePath)
    $minFreeMemoryMB = ($resources | Measure-Object MinSystemFreeMemoryMB -Minimum).Minimum
    $maxGpuMemoryMB = ($resources | Measure-Object MaxGpuMemoryUsedMB -Maximum).Maximum
    $p95Gpu = ($resources | Measure-Object P95GpuUtilPercent -Maximum).Maximum
    $p95Cpu = ($resources | Measure-Object P95SystemCpuPercent -Maximum).Maximum
}

$headroomFactor = 1.0 + ($HeadroomPercent / 100.0)
$measuredAppRamWithHeadroomGB = if ($maxNodeMemoryMB) { [math]::Ceiling(([double]$maxNodeMemoryMB * $headroomFactor) / 1024.0 * 10.0) / 10.0 } else { $null }
$requiredClusterEquivalent = $null
if ($ExpectedPeakPackagesPerMinute -gt 0) {
    $requiredClusterEquivalent = [math]::Ceiling(($ExpectedPeakPackagesPerMinute * $headroomFactor) / $peakThroughput)
}

$soakText = "No soak directory supplied. Complete the R3 soak before final production sign-off."
if ($SoakRunDirectory) {
    $SoakRunDirectory = (Resolve-Path -LiteralPath $SoakRunDirectory).Path
    $soakSummaryPath = Join-Path $SoakRunDirectory "soak-summary.csv"
    $driftPath = Join-Path $SoakRunDirectory "soak-drift-summary.csv"
    if (Test-Path $soakSummaryPath) {
        $soak = Import-Csv -LiteralPath $soakSummaryPath | Select-Object -First 1
        $soakText = "Soak: $($soak.Packages) packages, $($soak.SuccessPercent)% successful/paused, median $($soak.MedianTotalMs) ms, P95 $($soak.P95TotalMs) ms, observed $($soak.PackagesPerMinute) packages/min."
        if (Test-Path $driftPath) {
            $drift = Import-Csv -LiteralPath $driftPath | Select-Object -First 1
            $soakText += " Early-to-late median drift: $($drift.MedianDriftPercent)%."
        }
    }
}

if (-not $OutputPath) { $OutputPath = Join-Path $ScaleRunDirectory "production-sizing-recommendation.md" }

$targetSection = if ($ExpectedPeakPackagesPerMinute -gt 0) {
@"
### Capacity target calculation

Requested peak: **$ExpectedPeakPackagesPerMinute packages/min**. Applying **$HeadroomPercent%** headroom against this measured local cluster envelope gives approximately **$requiredClusterEquivalent reference-cluster-equivalents**. This is a capacity-planning ratio, not a recommendation to clone the laptop topology; production app and AI tiers should be scaled independently.
"@
} else {
@"
### Capacity target calculation

No business peak-throughput target was supplied, so this guide intentionally does **not** invent a production node count. Use:

required capacity = expected peak packages/min x (1 + headroom) / measured sustainable packages/min

Then validate the resulting topology with production-like documents and infrastructure.
"@
}

$ramText = if ($measuredAppRamWithHeadroomGB) { "$measuredAppRamWithHeadroomGB GiB per app process equivalent based on measured working set plus $HeadroomPercent% headroom" } else { "app-node RAM could not be derived from the run" }

$markdown = @"
# LexaVault V1 - Production Performance R3 Sizing Guide

Generated: $(Get-Date -Format o)

## Measured reference envelope

- Peak measured cluster throughput: **$peakThroughput packages/min** at concurrency **$($peak.Concurrency)**.
- Lowest concurrency within 95% of peak throughput: **$($efficient.Concurrency)** with **$($efficient.PackagesPerMinute) packages/min** and P95 latency **$($efficient.P95TotalMs) ms**.
- Highest measured P95 system CPU: **$p95Cpu%**.
- Highest measured P95 GPU utilization: **$p95Gpu%**; max GPU memory used **$maxGpuMemoryMB MB**.
- Minimum observed free system memory: **$minFreeMemoryMB MB**.
- App memory planning observation: **$ramText**.
- $soakText

## Topology guidance

For production/on-prem HA, keep **at least three application nodes** behind a health-aware load balancer so one node can be unavailable without eliminating redundancy. Application nodes remain stateless with tenant state in PostgreSQL/shared storage. Do not treat the local three-process laptop test as proof that three app nodes share one production GPU efficiently.

The AI tier must be sized separately from the application tier. The local R3 harness intentionally shares one RTX 1000 Ada endpoint across all app nodes to expose the AI saturation knee. Production should use the already-frozen AI inference-pool architecture and add independent inference capacity when AI latency/GPU saturation, rather than app CPU/queues, becomes the limiting stage.

Use the accepted R2 settings as the starting profile for qwen3.5:4b: page batch **4**, AI parallelism **2**, 14,000-character batch ceiling. Do not increase package-worker or AI concurrency merely because more app nodes are added; require measured evidence.

$targetSection

## Release gate

Before final V1 production sizing is declared, require: zero unexplained package loss, bounded queue growth, no sustained memory drift, recovery after a node is removed from rotation, and a soak run long enough to expose resource leakage or latency drift. Production hardware sizing remains deployment-specific and must be validated with the customer's representative PDF mix (native, mixed, and OCR-heavy).
"@

Set-Content -LiteralPath $OutputPath -Value $markdown -Encoding UTF8
Write-Host "Production sizing guide written: $OutputPath" -ForegroundColor Green
