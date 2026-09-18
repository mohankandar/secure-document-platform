param(
    [Parameter(Mandatory=$true)]
    [string]$RunDirectory,
    [string]$LogPath = ""
)

$ErrorActionPreference = "Stop"
$runDir = (Resolve-Path -LiteralPath $RunDirectory).Path
$packageCsv = Join-Path $runDir "packages.csv"
$resourceCsv = Join-Path $runDir "resources.csv"
if (-not (Test-Path -LiteralPath $packageCsv)) { throw "Missing packages.csv in $runDir" }
if (-not (Test-Path -LiteralPath $resourceCsv)) { throw "Missing resources.csv in $runDir" }

$packages = @(Import-Csv -LiteralPath $packageCsv)
$resources = @(Import-Csv -LiteralPath $resourceCsv)

function Percentile {
    param([double[]]$Values, [double]$P)
    $sorted = @($Values | Where-Object { $_ -ge 0 } | Sort-Object)
    if ($sorted.Count -eq 0) { return $null }
    if ($sorted.Count -eq 1) { return [double]$sorted[0] }
    $rank = ($sorted.Count - 1) * $P
    $lower = [math]::Floor($rank)
    $upper = [math]::Ceiling($rank)
    if ($lower -eq $upper) { return [double]$sorted[$lower] }
    $weight = $rank - $lower
    return ([double]$sorted[$lower] * (1 - $weight)) + ([double]$sorted[$upper] * $weight)
}

$summary = @()
foreach ($group in ($packages | Group-Object Concurrency | Sort-Object { [int]$_.Name })) {
    $level = [int]$group.Name
    $rows = @($group.Group)
    $successful = @($rows | Where-Object { $_.Status -notin @("ERROR", "TIMEOUT", "JOB_FAILED") })
    $latencies = @($successful | ForEach-Object { [double]$_.TotalClientMs })
    $processing = @($successful | ForEach-Object { [double]$_.ProcessingWaitMs })
    $levelResources = @($resources | Where-Object { [int]$_.Concurrency -eq $level })

    $summary += [pscustomobject]@{
        Concurrency = $level
        Packages = $rows.Count
        SuccessfulOrPaused = $successful.Count
        Completed = @($rows | Where-Object Status -eq "COMPLETED").Count
        PendingReview = @($rows | Where-Object Status -eq "PENDING_REVIEW").Count
        Failed = @($rows | Where-Object Status -eq "FAILED").Count
        TimedOutOrError = @($rows | Where-Object { $_.Status -in @("ERROR", "TIMEOUT", "JOB_FAILED") }).Count
        MedianTotalMs = if ($latencies.Count) { [math]::Round((Percentile $latencies 0.50), 1) } else { $null }
        P95TotalMs = if ($latencies.Count) { [math]::Round((Percentile $latencies 0.95), 1) } else { $null }
        MedianProcessingMs = if ($processing.Count) { [math]::Round((Percentile $processing 0.50), 1) } else { $null }
        P95ProcessingMs = if ($processing.Count) { [math]::Round((Percentile $processing 0.95), 1) } else { $null }
        AvgSystemCpuPercent = if ($levelResources.Count) { [math]::Round((($levelResources | Measure-Object SystemCpuPercent -Average).Average), 1) } else { $null }
        P95SystemCpuPercent = if ($levelResources.Count) { [math]::Round((Percentile @($levelResources | ForEach-Object { [double]$_.SystemCpuPercent }) 0.95), 1) } else { $null }
        MaxSystemCpuPercent = if ($levelResources.Count) { [math]::Round((($levelResources | Measure-Object SystemCpuPercent -Maximum).Maximum), 1) } else { $null }
        AvgAppCpuPercent = if ($levelResources.Count) { [math]::Round((($levelResources | Measure-Object AppCpuPercent -Average).Average), 1) } else { $null }
        P95AppCpuPercent = if ($levelResources.Count) { [math]::Round((Percentile @($levelResources | ForEach-Object { [double]$_.AppCpuPercent }) 0.95), 1) } else { $null }
        MaxAppCpuPercent = if ($levelResources.Count) { [math]::Round((($levelResources | Measure-Object AppCpuPercent -Maximum).Maximum), 1) } else { $null }
        MaxAppWorkingSetMB = if ($levelResources.Count) { [math]::Round((($levelResources | Measure-Object AppWorkingSetMB -Maximum).Maximum), 1) } else { $null }
        MinSystemFreeMemoryMB = if ($levelResources.Count) { [math]::Round((($levelResources | Measure-Object SystemFreeMemoryMB -Minimum).Minimum), 1) } else { $null }
        AvgGpuUtilPercent = if ($levelResources.Count) { [math]::Round((($levelResources | Measure-Object GpuUtilPercent -Average).Average), 1) } else { $null }
        P95GpuUtilPercent = if ($levelResources.Count) { [math]::Round((Percentile @($levelResources | ForEach-Object { [double]$_.GpuUtilPercent }) 0.95), 1) } else { $null }
        MaxGpuUtilPercent = if ($levelResources.Count) { [math]::Round((($levelResources | Measure-Object GpuUtilPercent -Maximum).Maximum), 1) } else { $null }
        MaxGpuMemoryUsedMB = if ($levelResources.Count) { [math]::Round((($levelResources | Measure-Object GpuMemoryUsedMB -Maximum).Maximum), 1) } else { $null }
        MaxOllamaWorkingSetMB = if ($levelResources.Count) { [math]::Round((($levelResources | Measure-Object OllamaWorkingSetMB -Maximum).Maximum), 1) } else { $null }
        MaxPostgresWorkingSetMB = if ($levelResources.Count) { [math]::Round((($levelResources | Measure-Object PostgresWorkingSetMB -Maximum).Maximum), 1) } else { $null }
    }
}

Write-Host ""
Write-Host "=== LexaVault performance baseline summary ===" -ForegroundColor Cyan
$summary | Format-Table -AutoSize
$summary | Export-Csv -LiteralPath (Join-Path $runDir "summary.csv") -NoTypeInformation

if ($LogPath) {
    $resolvedLog = (Resolve-Path -LiteralPath $LogPath -ErrorAction Stop).Path
    $packageConcurrency = @{}
    foreach ($row in $packages) { $packageConcurrency[[string]$row.PackageId] = [int]$row.Concurrency }

    $events = New-Object System.Collections.Generic.List[object]
    Get-Content -LiteralPath $resolvedLog | ForEach-Object {
        $line = $_
        if (-not $line.Trim()) { return }
        $obj = $null
        try { $obj = $line | ConvertFrom-Json -ErrorAction Stop } catch { return }

        $packageId = [string]$obj.packageId
        if (-not $packageId -or -not $packageConcurrency.ContainsKey($packageId)) { return }

        $eventName = [string]$obj.eventName
        $message = [string]$obj.message
        if (-not $eventName) {
            foreach ($prefix in @(
                "DOCUMENT_EXTRACTION_COMPLETED",
                "DOCUMENT_DETECTION_COMPLETED",
                "DOCUMENT_REDACTION_COMPLETED",
                "PACKAGE_DELIVERY_COMPLETED",
                "PACKAGE_PROCESSING_FINISHED")) {
                if ($message.StartsWith($prefix)) { $eventName = $prefix; break }
            }
        }

        if ($eventName -notin @(
            "PACKAGE_PROCESSING_DISPATCH_STARTED",
            "DOCUMENT_EXTRACTION_COMPLETED",
            "AI_ANALYSIS_COMPLETED",
            "DOCUMENT_DETECTION_COMPLETED",
            "DOCUMENT_REDACTION_COMPLETED",
            "PACKAGE_DELIVERY_COMPLETED",
            "DERIVED_ROUTING_DETECTION_COMPLETED",
            "DERIVED_ROUTING_DETECTION_FAILED",
            "DERIVED_ROUTING_COMPLETED",
            "PACKAGE_PROCESSING_FINISHED",
            "AI_INFERENCE_NODE_SELECTED",
            "AI_INFERENCE_POOL_EXHAUSTED")) { return }

        $duration = $obj.durationMs
        if (($null -eq $duration -or [string]$duration -eq "") -and $message -match 'durationMs=(\d+)') {
            $duration = $Matches[1]
        }

        $events.Add([pscustomobject]@{
            Timestamp = [string]$obj.'@timestamp'
            Concurrency = $packageConcurrency[$packageId]
            Event = $eventName
            PackageId = $packageId
            DurationMs = [string]$duration
            QueueWaitMs = [string]$obj.queueWaitMs
            ActiveWorkers = [string]$obj.activeWorkers
            WorkerPoolSize = [string]$obj.workerPoolSize
            WorkerQueueDepth = [string]$obj.workerQueueDepth
            AiNode = [string]$obj.inferenceNodeId
            PageCount = [string]$obj.pageCount
            BatchCount = [string]$obj.batchCount
            ProviderCalls = [string]$obj.providerCalls
            PageRetries = [string]$obj.pageRetries
            Parallelism = [string]$obj.parallelism
            PageBatchSize = [string]$obj.pageBatchSize
            PageBatchMaxCharacters = [string]$obj.pageBatchMaxCharacters
            ProviderLoadMs = [string]$obj.providerLoadMs
            PromptEvalTokens = [string]$obj.promptEvalTokens
            ProviderPromptEvalMs = [string]$obj.providerPromptEvalMs
            OutputEvalTokens = [string]$obj.outputEvalTokens
            ProviderEvalMs = [string]$obj.providerEvalMs
            EnabledRules = [string]$obj.enabledRules
            Documents = [string]$obj.documents
            MatchedRules = [string]$obj.matchedRules
            AmbiguousPages = [string]$obj.ambiguousPages
            Status = [string]$obj.status
            Reason = [string]$obj.reasonCode
        })
    }

    $eventPath = Join-Path $runDir "structured-performance-events.csv"
    $events | Export-Csv -LiteralPath $eventPath -NoTypeInformation
    Write-Host "Structured performance events: $eventPath" -ForegroundColor Green

    $stageSummary = @()
    foreach ($group in ($events | Where-Object { $_.DurationMs -match '^\d+(\.\d+)?$' } | Group-Object Concurrency,Event)) {
        $parts = $group.Name -split ', '
        $durations = @($group.Group | ForEach-Object { [double]$_.DurationMs })
        $stageSummary += [pscustomobject]@{
            Concurrency = [int]$parts[0]
            Event = $parts[1]
            Samples = $durations.Count
            MedianDurationMs = [math]::Round((Percentile $durations 0.50), 1)
            P95DurationMs = [math]::Round((Percentile $durations 0.95), 1)
        }
    }
    if ($stageSummary.Count) {
        Write-Host ""
        Write-Host "=== Stage timing summary ===" -ForegroundColor Cyan
        $stageSummary | Sort-Object Concurrency,Event | Format-Table -AutoSize
        $stageSummary | Sort-Object Concurrency,Event | Export-Csv -LiteralPath (Join-Path $runDir "stage-summary.csv") -NoTypeInformation
        Write-Host "Stage summary CSV: $(Join-Path $runDir 'stage-summary.csv')" -ForegroundColor Green
    }

    $aiBatchSummary = @()
    foreach ($group in ($events | Where-Object { $_.Event -eq "AI_ANALYSIS_COMPLETED" } | Group-Object Concurrency | Sort-Object { [int]$_.Name })) {
        $rows = @($group.Group)
        $durations = @($rows | Where-Object { $_.DurationMs -match '^\d+(\.\d+)?$' } | ForEach-Object { [double]$_.DurationMs })
        $pageCounts = @($rows | Where-Object { $_.PageCount -match '^\d+$' } | ForEach-Object { [double]$_.PageCount })
        $batchCounts = @($rows | Where-Object { $_.BatchCount -match '^\d+$' } | ForEach-Object { [double]$_.BatchCount })
        $providerCalls = @($rows | Where-Object { $_.ProviderCalls -match '^\d+$' } | ForEach-Object { [double]$_.ProviderCalls })
        $promptTokens = @($rows | Where-Object { $_.PromptEvalTokens -match '^\d+$' } | ForEach-Object { [double]$_.PromptEvalTokens })
        $outputTokens = @($rows | Where-Object { $_.OutputEvalTokens -match '^\d+$' } | ForEach-Object { [double]$_.OutputEvalTokens })
        $promptEvalMs = @($rows | Where-Object { $_.ProviderPromptEvalMs -match '^\d+(\.\d+)?$' } | ForEach-Object { [double]$_.ProviderPromptEvalMs })
        $providerEvalMs = @($rows | Where-Object { $_.ProviderEvalMs -match '^\d+(\.\d+)?$' } | ForEach-Object { [double]$_.ProviderEvalMs })
        $loadMs = @($rows | Where-Object { $_.ProviderLoadMs -match '^\d+(\.\d+)?$' } | ForEach-Object { [double]$_.ProviderLoadMs })
        $pagesPerBatch = @()
        $pagesPerCall = @()
        foreach ($row in $rows) {
            if ($row.PageCount -match '^\d+$' -and $row.BatchCount -match '^\d+$' -and [double]$row.BatchCount -gt 0) {
                $pagesPerBatch += ([double]$row.PageCount / [double]$row.BatchCount)
            }
            if ($row.PageCount -match '^\d+$' -and $row.ProviderCalls -match '^\d+$' -and [double]$row.ProviderCalls -gt 0) {
                $pagesPerCall += ([double]$row.PageCount / [double]$row.ProviderCalls)
            }
        }
        $configuredBatchSizes = @($rows | Where-Object { $_.PageBatchSize -match '^\d+$' } | ForEach-Object { [int]$_.PageBatchSize } | Select-Object -Unique)
        $configuredCharCaps = @($rows | Where-Object { $_.PageBatchMaxCharacters -match '^\d+$' } | ForEach-Object { [int]$_.PageBatchMaxCharacters } | Select-Object -Unique)
        $parallelism = @($rows | Where-Object { $_.Parallelism -match '^\d+$' } | ForEach-Object { [int]$_.Parallelism } | Select-Object -Unique)

        $aiBatchSummary += [pscustomobject]@{
            Concurrency = [int]$group.Name
            Samples = $rows.Count
            Parallelism = if ($parallelism.Count -eq 1) { $parallelism[0] } else { ($parallelism -join ',') }
            ConfiguredPageBatchSize = if ($configuredBatchSizes.Count -eq 1) { $configuredBatchSizes[0] } else { ($configuredBatchSizes -join ',') }
            PageBatchMaxCharacters = if ($configuredCharCaps.Count -eq 1) { $configuredCharCaps[0] } else { ($configuredCharCaps -join ',') }
            MedianAiDurationMs = if ($durations.Count) { [math]::Round((Percentile $durations 0.50), 1) } else { $null }
            P95AiDurationMs = if ($durations.Count) { [math]::Round((Percentile $durations 0.95), 1) } else { $null }
            MedianBatchCount = if ($batchCounts.Count) { [math]::Round((Percentile $batchCounts 0.50), 1) } else { $null }
            MedianProviderCalls = if ($providerCalls.Count) { [math]::Round((Percentile $providerCalls 0.50), 1) } else { $null }
            MedianPagesPerBatch = if ($pagesPerBatch.Count) { [math]::Round((Percentile $pagesPerBatch 0.50), 2) } else { $null }
            MedianPagesPerProviderCall = if ($pagesPerCall.Count) { [math]::Round((Percentile $pagesPerCall 0.50), 2) } else { $null }
            MedianPromptEvalTokens = if ($promptTokens.Count) { [math]::Round((Percentile $promptTokens 0.50), 1) } else { $null }
            MedianOutputEvalTokens = if ($outputTokens.Count) { [math]::Round((Percentile $outputTokens 0.50), 1) } else { $null }
            MedianProviderLoadMs = if ($loadMs.Count) { [math]::Round((Percentile $loadMs 0.50), 1) } else { $null }
            MedianProviderPromptEvalMs = if ($promptEvalMs.Count) { [math]::Round((Percentile $promptEvalMs 0.50), 1) } else { $null }
            MedianProviderEvalMs = if ($providerEvalMs.Count) { [math]::Round((Percentile $providerEvalMs 0.50), 1) } else { $null }
        }
    }
    if ($aiBatchSummary.Count) {
        Write-Host ""
        Write-Host "=== AI batch efficiency summary ===" -ForegroundColor Cyan
        $aiBatchSummary | Format-Table -AutoSize
        $aiBatchPath = Join-Path $runDir "ai-batch-efficiency-summary.csv"
        $aiBatchSummary | Export-Csv -LiteralPath $aiBatchPath -NoTypeInformation
        Write-Host "AI batch efficiency summary CSV: $aiBatchPath" -ForegroundColor Green
    }
}

Write-Host "Summary CSV: $(Join-Path $runDir 'summary.csv')" -ForegroundColor Green
