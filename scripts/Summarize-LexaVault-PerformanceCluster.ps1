param(
    [Parameter(Mandatory=$true)]
    [string]$RunDirectory,
    [string]$ClusterRoot = ".\performance-cluster"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$RunDirectory = (Resolve-Path -LiteralPath $RunDirectory).Path
$ClusterRootFull = [IO.Path]::GetFullPath((Join-Path $ProjectRoot $ClusterRoot))
$packagesPath = Join-Path $RunDirectory "packages.csv"
$resourcesPath = Join-Path $RunDirectory "resources.csv"
$nodeResourcesPath = Join-Path $RunDirectory "node-resources.csv"

if (-not (Test-Path $packagesPath)) {
    throw "packages.csv not found: $packagesPath"
}

function Get-Percentile {
    param([double[]]$Values, [double]$P)
    $vals = @($Values | Where-Object { $null -ne $_ } | ForEach-Object { [double]$_ } | Sort-Object)
    if ($vals.Count -eq 0) { return $null }
    if ($vals.Count -eq 1) { return [double]$vals[0] }
    $rank = ($P / 100.0) * ($vals.Count - 1)
    $lo = [math]::Floor($rank)
    $hi = [math]::Ceiling($rank)
    if ($lo -eq $hi) { return [double]$vals[$lo] }
    $weight = $rank - $lo
    return [double]$vals[$lo] + (([double]$vals[$hi] - [double]$vals[$lo]) * $weight)
}

function Get-Median {
    param([double[]]$Values)
    return Get-Percentile -Values $Values -P 50
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
$levels = @(
    $packages |
        Where-Object { $_.Concurrency -ne $null -and $_.Concurrency -ne "" } |
        ForEach-Object { [int]$_.Concurrency } |
        Sort-Object -Unique
)
$successStates = @("COMPLETED", "PENDING_REVIEW", "PENDING_FINAL_APPROVAL", "APPROVED", "VAULTED")

$summary = New-Object System.Collections.Generic.List[object]
foreach ($level in $levels) {
    $rows = @($packages | Where-Object { [int]$_.Concurrency -eq $level })
    $good = @($rows | Where-Object { $successStates -contains $_.Status })
    $failed = @($rows | Where-Object { $_.Status -eq "FAILED" })
    $timeouts = @($rows | Where-Object { $_.Status -in @("TIMEOUT", "ERROR", "JOB_FAILED") })
    $dur = @($rows | Where-Object { $_.TotalClientMs -ne "" } | ForEach-Object { [double]$_.TotalClientMs })
    $proc = @($rows | Where-Object { $_.ProcessingWaitMs -ne "" } | ForEach-Object { [double]$_.ProcessingWaitMs })
    $wallFile = Join-Path $RunDirectory ("level-{0}-wall.csv" -f $level)
    $wall = $null
    if (Test-Path $wallFile) { $wall = Import-Csv -LiteralPath $wallFile | Select-Object -First 1 }

    $summary.Add([pscustomobject]@{
        Concurrency = $level
        Packages = $rows.Count
        SuccessfulOrPaused = $good.Count
        Failed = $failed.Count
        TimedOutOrError = $timeouts.Count
        MedianTotalMs = if ($dur.Count) { [math]::Round((Get-Median $dur), 1) } else { $null }
        P95TotalMs = if ($dur.Count) { [math]::Round((Get-Percentile $dur 95), 1) } else { $null }
        MedianProcessingMs = if ($proc.Count) { [math]::Round((Get-Median $proc), 1) } else { $null }
        P95ProcessingMs = if ($proc.Count) { [math]::Round((Get-Percentile $proc 95), 1) } else { $null }
        WallSeconds = if ($wall) { [double]$wall.WallSeconds } else { $null }
        PackagesPerMinute = if ($wall) { [double]$wall.PackagesPerMinute } else { $null }
    })
}

$summaryPath = Join-Path $RunDirectory "cluster-summary.csv"
$summary | Export-Csv -LiteralPath $summaryPath -NoTypeInformation

$nodeDistribution = New-Object System.Collections.Generic.List[object]
foreach ($level in $levels) {
    $levelRows = @($packages | Where-Object { [int]$_.Concurrency -eq $level })
    foreach ($group in @($levelRows | Group-Object SubmitNode)) {
        $vals = @($group.Group | ForEach-Object { [double]$_.TotalClientMs })
        $good = @($group.Group | Where-Object { $successStates -contains $_.Status })
        $nodeDistribution.Add([pscustomobject]@{
            Concurrency = $level
            SubmitNode = $group.Name
            Packages = $group.Count
            SuccessfulOrPaused = $good.Count
            MedianTotalMs = [math]::Round((Get-Median $vals), 1)
            P95TotalMs = [math]::Round((Get-Percentile $vals 95), 1)
        })
    }
}
$nodeDistribution | Export-Csv -LiteralPath (Join-Path $RunDirectory "node-distribution.csv") -NoTypeInformation

if (Test-Path $resourcesPath) {
    $resources = @(Import-Csv -LiteralPath $resourcesPath)
    $resourceSummary = New-Object System.Collections.Generic.List[object]
    foreach ($level in $levels) {
        $rows = @($resources | Where-Object { [int]$_.Concurrency -eq $level })
        if ($rows.Count -eq 0) { continue }
        $resourceSummary.Add([pscustomobject]@{
            Concurrency = $level
            Samples = $rows.Count
            AvgSystemCpuPercent = [math]::Round((Get-Avg $rows.SystemCpuPercent), 1)
            P95SystemCpuPercent = [math]::Round((Get-Percentile @($rows.SystemCpuPercent) 95), 1)
            MaxSystemCpuPercent = Get-MaxValue $rows.SystemCpuPercent
            AvgTotalAppCpuPercent = [math]::Round((Get-Avg $rows.TotalAppCpuPercent), 1)
            P95TotalAppCpuPercent = [math]::Round((Get-Percentile @($rows.TotalAppCpuPercent) 95), 1)
            MaxTotalAppCpuPercent = Get-MaxValue $rows.TotalAppCpuPercent
            MaxTotalAppWorkingSetMB = Get-MaxValue $rows.TotalAppWorkingSetMB
            MinSystemFreeMemoryMB = Get-MinValue $rows.SystemFreeMemoryMB
            AvgGpuUtilPercent = [math]::Round((Get-Avg $rows.GpuUtilPercent), 1)
            P95GpuUtilPercent = [math]::Round((Get-Percentile @($rows.GpuUtilPercent) 95), 1)
            MaxGpuUtilPercent = Get-MaxValue $rows.GpuUtilPercent
            MaxGpuMemoryUsedMB = Get-MaxValue $rows.GpuMemoryUsedMB
            MaxOllamaWorkingSetMB = Get-MaxValue $rows.OllamaWorkingSetMB
            MaxPostgresWorkingSetMB = Get-MaxValue $rows.PostgresWorkingSetMB
        })
    }
    $resourceSummary | Export-Csv -LiteralPath (Join-Path $RunDirectory "cluster-resource-summary.csv") -NoTypeInformation
}

if (Test-Path $nodeResourcesPath) {
    $nodeRows = @(Import-Csv -LiteralPath $nodeResourcesPath)
    $nodeSummary = New-Object System.Collections.Generic.List[object]
    foreach ($group in @($nodeRows | Group-Object Concurrency, NodeId)) {
        $first = $group.Group | Select-Object -First 1
        $nodeSummary.Add([pscustomobject]@{
            Concurrency = [int]$first.Concurrency
            NodeId = $first.NodeId
            BaseUrl = $first.BaseUrl
            Samples = $group.Count
            AvgAppCpuPercent = [math]::Round((Get-Avg $group.Group.AppCpuPercent), 1)
            P95AppCpuPercent = [math]::Round((Get-Percentile @($group.Group.AppCpuPercent) 95), 1)
            MaxAppCpuPercent = Get-MaxValue $group.Group.AppCpuPercent
            MaxAppWorkingSetMB = Get-MaxValue $group.Group.AppWorkingSetMB
            MaxAppPrivateMemoryMB = Get-MaxValue $group.Group.AppPrivateMemoryMB
            MaxThreads = Get-MaxValue $group.Group.AppThreads
        })
    }
    $nodeSummary | Sort-Object Concurrency, NodeId | Export-Csv -LiteralPath (Join-Path $RunDirectory "node-resource-summary.csv") -NoTypeInformation
}

# Parse only events for package IDs in this run from each node's JSON log.
$packageMap = @{}
foreach ($package in $packages) {
    if ($package.PackageId) { $packageMap[[string]$package.PackageId] = [int]$package.Concurrency }
}

$events = New-Object System.Collections.Generic.List[object]
$interesting = @(
    "PACKAGE_PROCESSING_DISPATCH_STARTED",
    "PACKAGE_PROCESSING_FINISHED",
    "DOCUMENT_EXTRACTION_COMPLETED",
    "AI_ANALYSIS_COMPLETED",
    "DOCUMENT_DETECTION_COMPLETED",
    "DOCUMENT_REDACTION_COMPLETED",
    "PACKAGE_DELIVERY_COMPLETED",
    "DERIVED_ROUTING_DETECTION_COMPLETED",
    "DERIVED_ROUTING_COMPLETED"
)
$logsRoot = Join-Path $ClusterRootFull "logs"
$logFiles = @()
if (Test-Path $logsRoot) {
    $logFiles = @(Get-ChildItem -Path $logsRoot -Recurse -File -Filter "securedoc-platform.json" -ErrorAction SilentlyContinue)
}

foreach ($log in $logFiles) {
    Get-Content -LiteralPath $log.FullName -ReadCount 500 | ForEach-Object {
        foreach ($line in $_) {
            if ([string]::IsNullOrWhiteSpace($line)) { continue }
            try { $eventObject = $line | ConvertFrom-Json -ErrorAction Stop } catch { continue }
            $packageId = [string]$eventObject.packageId
            if (-not $packageId -or -not $packageMap.ContainsKey($packageId)) { continue }
            $eventName = [string]$eventObject.eventName
            if (-not ($interesting -contains $eventName)) { continue }
            $events.Add([pscustomobject]@{
                Timestamp = [string]$eventObject.'@timestamp'
                Concurrency = $packageMap[$packageId]
                NodeId = [string]$eventObject.nodeId
                PackageId = $packageId
                Event = $eventName
                DurationMs = if ($eventObject.durationMs -ne $null) { [double]$eventObject.durationMs } else { $null }
                QueueWaitMs = if ($eventObject.queueWaitMs -ne $null) { [double]$eventObject.queueWaitMs } else { $null }
                ActiveWorkers = if ($eventObject.activeWorkers -ne $null) { [int]$eventObject.activeWorkers } else { $null }
                WorkerQueueDepth = if ($eventObject.workerQueueDepth -ne $null) { [int]$eventObject.workerQueueDepth } else { $null }
                ProviderCalls = if ($eventObject.providerCalls -ne $null) { [int]$eventObject.providerCalls } else { $null }
                BatchCount = if ($eventObject.batchCount -ne $null) { [int]$eventObject.batchCount } else { $null }
                PageBatchSize = if ($eventObject.pageBatchSize -ne $null) { [int]$eventObject.pageBatchSize } else { $null }
                Parallelism = if ($eventObject.parallelism -ne $null) { [int]$eventObject.parallelism } else { $null }
            })
        }
    }
}

if ($events.Count -gt 0) {
    $events | Export-Csv -LiteralPath (Join-Path $RunDirectory "cluster-performance-events.csv") -NoTypeInformation

    $stageSummary = New-Object System.Collections.Generic.List[object]
    foreach ($group in @($events | Where-Object { $_.DurationMs -ne $null } | Group-Object Concurrency, Event)) {
        $first = $group.Group | Select-Object -First 1
        $vals = @($group.Group | ForEach-Object { [double]$_.DurationMs })
        $stageSummary.Add([pscustomobject]@{
            Concurrency = [int]$first.Concurrency
            Event = $first.Event
            Samples = $vals.Count
            MedianDurationMs = [math]::Round((Get-Median $vals), 1)
            P95DurationMs = [math]::Round((Get-Percentile $vals 95), 1)
        })
    }
    $stageSummary | Sort-Object Concurrency, Event | Export-Csv -LiteralPath (Join-Path $RunDirectory "cluster-stage-summary.csv") -NoTypeInformation

    $queueSummary = New-Object System.Collections.Generic.List[object]
    foreach ($level in $levels) {
        $rows = @($events | Where-Object { $_.Concurrency -eq $level -and $_.Event -eq "PACKAGE_PROCESSING_DISPATCH_STARTED" -and $_.QueueWaitMs -ne $null })
        if ($rows.Count -eq 0) { continue }
        $vals = @($rows | ForEach-Object { [double]$_.QueueWaitMs })
        $queueSummary.Add([pscustomobject]@{
            Concurrency = $level
            Samples = $vals.Count
            MedianQueueWaitMs = [math]::Round((Get-Median $vals), 1)
            P95QueueWaitMs = [math]::Round((Get-Percentile $vals 95), 1)
            MaxQueueWaitMs = [math]::Round((Get-MaxValue $vals), 1)
            MaxWorkerQueueDepth = Get-MaxValue $rows.WorkerQueueDepth
            MaxActiveWorkers = Get-MaxValue $rows.ActiveWorkers
        })
    }
    $queueSummary | Export-Csv -LiteralPath (Join-Path $RunDirectory "queue-summary.csv") -NoTypeInformation

    $aiSummary = New-Object System.Collections.Generic.List[object]
    foreach ($level in $levels) {
        $rows = @($events | Where-Object { $_.Concurrency -eq $level -and $_.Event -eq "AI_ANALYSIS_COMPLETED" })
        if ($rows.Count -eq 0) { continue }
        $dur = @($rows | ForEach-Object { [double]$_.DurationMs })
        $calls = @($rows | Where-Object { $_.ProviderCalls -ne $null } | ForEach-Object { [double]$_.ProviderCalls })
        $batchRow = $rows | Where-Object { $_.PageBatchSize -ne $null } | Select-Object -First 1
        $aiSummary.Add([pscustomobject]@{
            Concurrency = $level
            Samples = $rows.Count
            MedianAiDurationMs = [math]::Round((Get-Median $dur), 1)
            P95AiDurationMs = [math]::Round((Get-Percentile $dur 95), 1)
            MedianProviderCalls = if ($calls.Count) { [math]::Round((Get-Median $calls), 1) } else { $null }
            PageBatchSize = if ($batchRow) { $batchRow.PageBatchSize } else { $null }
            Parallelism = if ($batchRow) { $batchRow.Parallelism } else { $null }
        })
    }
    $aiSummary | Export-Csv -LiteralPath (Join-Path $RunDirectory "cluster-ai-summary.csv") -NoTypeInformation
}

Write-Host ""
Write-Host "=== LexaVault R3 cluster summary ===" -ForegroundColor Cyan
$summary | Format-Table -AutoSize
Write-Host ""
Write-Host "Cluster summary:   $summaryPath"
Write-Host "Node distribution: $(Join-Path $RunDirectory 'node-distribution.csv')"
if (Test-Path (Join-Path $RunDirectory "cluster-resource-summary.csv")) { Write-Host "Resources:         $(Join-Path $RunDirectory 'cluster-resource-summary.csv')" }
if (Test-Path (Join-Path $RunDirectory "cluster-stage-summary.csv")) { Write-Host "Stages:            $(Join-Path $RunDirectory 'cluster-stage-summary.csv')" }
if (Test-Path (Join-Path $RunDirectory "queue-summary.csv")) { Write-Host "Queues:            $(Join-Path $RunDirectory 'queue-summary.csv')" }
if (Test-Path (Join-Path $RunDirectory "cluster-ai-summary.csv")) { Write-Host "AI:                $(Join-Path $RunDirectory 'cluster-ai-summary.csv')" }
Write-Host ""
