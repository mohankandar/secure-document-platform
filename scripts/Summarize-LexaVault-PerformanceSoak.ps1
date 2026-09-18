param(
    [Parameter(Mandatory=$true)]
    [string]$RunDirectory
)

$ErrorActionPreference = "Stop"
$RunDirectory = (Resolve-Path -LiteralPath $RunDirectory).Path
$packagesPath = Join-Path $RunDirectory "packages.csv"
$resourcesPath = Join-Path $RunDirectory "resources.csv"
$healthPath = Join-Path $RunDirectory "node-health.csv"
$manifestPath = Join-Path $RunDirectory "run-manifest.json"
if (-not (Test-Path $packagesPath)) { throw "packages.csv not found: $packagesPath" }

function Get-Percentile {
    param([double[]]$Values, [double]$P)
    $vals = @($Values | Sort-Object)
    if ($vals.Count -eq 0) { return $null }
    if ($vals.Count -eq 1) { return [double]$vals[0] }
    $rank = ($P / 100.0) * ($vals.Count - 1)
    $lo = [math]::Floor($rank); $hi = [math]::Ceiling($rank)
    if ($lo -eq $hi) { return [double]$vals[$lo] }
    return [double]$vals[$lo] + (([double]$vals[$hi] - [double]$vals[$lo]) * ($rank - $lo))
}
function Get-Avg {
    param([object[]]$Values)
    $vals = @($Values | Where-Object { $_ -ne $null -and $_ -ne "" } | ForEach-Object { [double]$_ })
    if ($vals.Count -eq 0) { return $null }
    return ($vals | Measure-Object -Average).Average
}
function Get-MaxValue {
    param([object[]]$Values)
    $vals = @($Values | Where-Object { $_ -ne $null -and $_ -ne "" } | ForEach-Object { [double]$_ })
    if ($vals.Count -eq 0) { return $null }
    return ($vals | Measure-Object -Maximum).Maximum
}
function Get-MinValue {
    param([object[]]$Values)
    $vals = @($Values | Where-Object { $_ -ne $null -and $_ -ne "" } | ForEach-Object { [double]$_ })
    if ($vals.Count -eq 0) { return $null }
    return ($vals | Measure-Object -Minimum).Minimum
}

$packages = @(Import-Csv -LiteralPath $packagesPath)
$manifest = if (Test-Path $manifestPath) { Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json } else { $null }
$successStates = @("COMPLETED", "PENDING_REVIEW", "PENDING_FINAL_APPROVAL", "APPROVED", "VAULTED")
$good = @($packages | Where-Object { $successStates -contains $_.Status })
$failed = @($packages | Where-Object { $_.Status -eq "FAILED" })
$errors = @($packages | Where-Object { $_.Status -in @("TIMEOUT", "ERROR", "JOB_FAILED") })
$durations = @($packages | Where-Object { $_.TotalClientMs -ne "" } | ForEach-Object { [double]$_.TotalClientMs })

$started = $null; $finished = $null
if ($packages.Count -gt 0) {
    $started = ($packages | ForEach-Object { [datetimeoffset]$_.StartedAt } | Sort-Object | Select-Object -First 1)
    $finished = ($packages | ForEach-Object { [datetimeoffset]$_.FinishedAt } | Sort-Object | Select-Object -Last 1)
}
$wallMinutes = if ($started -and $finished) { ($finished - $started).TotalMinutes } else { 0 }

$summary = [pscustomobject]@{
    Packages = $packages.Count
    SuccessfulOrPaused = $good.Count
    Failed = $failed.Count
    TimedOutOrError = $errors.Count
    SuccessPercent = if ($packages.Count) { [math]::Round($good.Count * 100.0 / $packages.Count, 2) } else { 0 }
    MedianTotalMs = if ($durations.Count) { [math]::Round((Get-Percentile $durations 50), 1) } else { $null }
    P95TotalMs = if ($durations.Count) { [math]::Round((Get-Percentile $durations 95), 1) } else { $null }
    WallMinutes = [math]::Round($wallMinutes, 2)
    PackagesPerMinute = if ($wallMinutes -gt 0) { [math]::Round($packages.Count / $wallMinutes, 3) } else { 0 }
    RequestedConcurrency = if ($manifest) { $manifest.concurrency } else { $null }
    RequestedDurationMinutes = if ($manifest) { $manifest.durationMinutes } else { $null }
}
$summary | Export-Csv -LiteralPath (Join-Path $RunDirectory "soak-summary.csv") -NoTypeInformation

$nodeSummary = New-Object System.Collections.Generic.List[object]
foreach ($group in @($packages | Group-Object SubmitNode)) {
    $vals = @($group.Group | ForEach-Object { [double]$_.TotalClientMs })
    $nodeGood = @($group.Group | Where-Object { $successStates -contains $_.Status })
    $nodeSummary.Add([pscustomobject]@{
        SubmitNode = $group.Name
        Packages = $group.Count
        SuccessfulOrPaused = $nodeGood.Count
        MedianTotalMs = if ($vals.Count) { [math]::Round((Get-Percentile $vals 50), 1) } else { $null }
        P95TotalMs = if ($vals.Count) { [math]::Round((Get-Percentile $vals 95), 1) } else { $null }
    })
}
$nodeSummary | Export-Csv -LiteralPath (Join-Path $RunDirectory "soak-node-summary.csv") -NoTypeInformation

if (Test-Path $healthPath) {
    $health = @(Import-Csv -LiteralPath $healthPath)
    $healthSummary = New-Object System.Collections.Generic.List[object]
    foreach ($group in @($health | Group-Object BaseUrl)) {
        $unhealthy = @($group.Group | Where-Object { $_.Healthy -ne "True" })
        $healthSummary.Add([pscustomobject]@{
            BaseUrl = $group.Name
            Samples = $group.Count
            UnhealthySamples = $unhealthy.Count
            AvailabilityPercent = if ($group.Count) { [math]::Round(($group.Count - $unhealthy.Count) * 100.0 / $group.Count, 2) } else { 0 }
        })
    }
    $healthSummary | Export-Csv -LiteralPath (Join-Path $RunDirectory "soak-health-summary.csv") -NoTypeInformation
}

if (Test-Path $resourcesPath) {
    $resources = @(Import-Csv -LiteralPath $resourcesPath)
    $resourceSummary = [pscustomobject]@{
        Samples = $resources.Count
        AvgSystemCpuPercent = [math]::Round((Get-Avg $resources.SystemCpuPercent), 1)
        P95SystemCpuPercent = [math]::Round((Get-Percentile @($resources.SystemCpuPercent | ForEach-Object { [double]$_ }) 95), 1)
        MaxSystemCpuPercent = Get-MaxValue $resources.SystemCpuPercent
        MinSystemFreeMemoryMB = Get-MinValue $resources.SystemFreeMemoryMB
        MaxTotalAppWorkingSetMB = Get-MaxValue $resources.TotalAppWorkingSetMB
        AvgGpuUtilPercent = [math]::Round((Get-Avg $resources.GpuUtilPercent), 1)
        P95GpuUtilPercent = [math]::Round((Get-Percentile @($resources.GpuUtilPercent | Where-Object { $_ -ne "" } | ForEach-Object { [double]$_ }) 95), 1)
        MaxGpuUtilPercent = Get-MaxValue $resources.GpuUtilPercent
        MaxGpuMemoryUsedMB = Get-MaxValue $resources.GpuMemoryUsedMB
        MaxOllamaWorkingSetMB = Get-MaxValue $resources.OllamaWorkingSetMB
    }
    $resourceSummary | Export-Csv -LiteralPath (Join-Path $RunDirectory "soak-resource-summary.csv") -NoTypeInformation
}

# Compare early and late thirds to surface latency drift during a steady soak.
if ($packages.Count -ge 6) {
    $ordered = @($packages | Sort-Object { [datetimeoffset]$_.StartedAt })
    $third = [math]::Floor($ordered.Count / 3)
    $early = @($ordered | Select-Object -First $third)
    $late = @($ordered | Select-Object -Last $third)
    $earlyVals = @($early | ForEach-Object { [double]$_.TotalClientMs })
    $lateVals = @($late | ForEach-Object { [double]$_.TotalClientMs })
    [pscustomobject]@{
        EarlySamples = $early.Count
        EarlyMedianMs = [math]::Round((Get-Percentile $earlyVals 50), 1)
        EarlyP95Ms = [math]::Round((Get-Percentile $earlyVals 95), 1)
        LateSamples = $late.Count
        LateMedianMs = [math]::Round((Get-Percentile $lateVals 50), 1)
        LateP95Ms = [math]::Round((Get-Percentile $lateVals 95), 1)
        MedianDriftPercent = if ((Get-Percentile $earlyVals 50) -gt 0) { [math]::Round((((Get-Percentile $lateVals 50) / (Get-Percentile $earlyVals 50)) - 1) * 100.0, 2) } else { $null }
    } | Export-Csv -LiteralPath (Join-Path $RunDirectory "soak-drift-summary.csv") -NoTypeInformation
}

Write-Host ""
Write-Host "=== LexaVault R3 soak summary ===" -ForegroundColor Cyan
$summary | Format-List
Write-Host "Summary: $(Join-Path $RunDirectory 'soak-summary.csv')"
Write-Host ""
