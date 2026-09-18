param(
    [Parameter(Mandatory=$true)]
    [string[]]$PdfPath,
    [int[]]$ConcurrencyLevels = @(1, 2, 4),
    [int]$PackagesPerLevel = 4,
    [string]$BaseUrl = "http://localhost:8080",
    [int]$TimeoutMinutes = 30,
    [int]$PollIntervalSeconds = 2,
    [int]$CoolDownSeconds = 5,
    [string]$OutputRoot = ".\performance-results",
    [string]$RunLabel = "baseline"
)

$ErrorActionPreference = "Stop"

if ($PackagesPerLevel -lt 1 -or $PackagesPerLevel -gt 999) {
    throw "PackagesPerLevel must be between 1 and 999."
}
if ($ConcurrencyLevels.Count -eq 0 -or ($ConcurrencyLevels | Where-Object { $_ -lt 1 })) {
    throw "ConcurrencyLevels must contain positive integers."
}

$resolvedPdfs = @()
foreach ($candidate in $PdfPath) {
    $resolved = (Resolve-Path -LiteralPath $candidate -ErrorAction Stop).Path
    if ([IO.Path]::GetExtension($resolved).ToLowerInvariant() -ne ".pdf") {
        throw "Performance baseline accepts PDF files only: $resolved"
    }
    $resolvedPdfs += $resolved
}

try {
    $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -Method Get -TimeoutSec 5
    if ($health.status -ne "UP") { throw "Health status is $($health.status)" }
}
catch {
    throw "LexaVault backend is not ready at $BaseUrl. Start the app first. $($_.Exception.Message)"
}

$runId = ((Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")) + "-" + ([Guid]::NewGuid().ToString("N").Substring(0,8))
$runDir = Join-Path $OutputRoot ("{0}-{1}" -f $RunLabel, $runId)
New-Item -ItemType Directory -Force -Path $runDir | Out-Null

$manifest = [ordered]@{
    runId = $runId
    startedAt = (Get-Date).ToString("o")
    baseUrl = $BaseUrl
    concurrencyLevels = $ConcurrencyLevels
    packagesPerLevel = $PackagesPerLevel
    pdfs = $resolvedPdfs
    timeoutMinutes = $TimeoutMinutes
    pollIntervalSeconds = $PollIntervalSeconds
    note = "Creates disposable local-demo packages. It does not delete them automatically."
}
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $runDir "run-manifest.json") -Encoding UTF8

function Get-AppPid {
    try {
        $listener = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction Stop | Select-Object -First 1
        if ($listener) { return [int]$listener.OwningProcess }
    }
    catch { }
    return $null
}

function Get-PerfProcessRow {
    param([int]$ProcessId)
    if (-not $ProcessId) { return $null }
    try {
        return Get-CimInstance Win32_PerfFormattedData_PerfProc_Process -ErrorAction Stop |
            Where-Object { [int]$_.IDProcess -eq $ProcessId } |
            Select-Object -First 1
    }
    catch { return $null }
}

function Get-ProcessMemoryMB {
    param([string[]]$Name)
    try {
        $sum = (Get-Process -Name $Name -ErrorAction SilentlyContinue | Measure-Object WorkingSet64 -Sum).Sum
        if ($null -eq $sum) { return 0 }
        return [math]::Round($sum / 1MB, 1)
    }
    catch { return 0 }
}

function Get-ResourceSample {
    param([int]$AppPid, [int]$Concurrency)

    $appPerf = Get-PerfProcessRow -ProcessId $AppPid
    $appProcess = if ($AppPid) { Get-Process -Id $AppPid -ErrorAction SilentlyContinue } else { $null }
    $os = Get-CimInstance Win32_OperatingSystem
    $cpuTotal = $null
    try {
        $cpuTotal = (Get-CimInstance Win32_PerfFormattedData_PerfOS_Processor -ErrorAction Stop |
            Where-Object { $_.Name -eq "_Total" } | Select-Object -First 1).PercentProcessorTime
    }
    catch { }

    $gpuUtil = $null
    $gpuMemUsed = $null
    $gpuMemTotal = $null
    $gpuTemp = $null
    if (Get-Command nvidia-smi -ErrorAction SilentlyContinue) {
        try {
            $gpuLine = (& nvidia-smi --query-gpu=utilization.gpu,memory.used,memory.total,temperature.gpu --format=csv,noheader,nounits 2>$null | Select-Object -First 1)
            if ($gpuLine) {
                $parts = $gpuLine.Split(',') | ForEach-Object { $_.Trim() }
                if ($parts.Count -ge 4) {
                    $gpuUtil = [double]$parts[0]
                    $gpuMemUsed = [double]$parts[1]
                    $gpuMemTotal = [double]$parts[2]
                    $gpuTemp = [double]$parts[3]
                }
            }
        }
        catch { }
    }

    [pscustomobject]@{
        Timestamp = (Get-Date).ToString("o")
        Concurrency = $Concurrency
        SystemCpuPercent = $cpuTotal
        SystemFreeMemoryMB = [math]::Round($os.FreePhysicalMemory / 1024, 1)
        AppPid = $AppPid
        AppCpuPercent = if ($appPerf) { [double]$appPerf.PercentProcessorTime } else { $null }
        AppWorkingSetMB = if ($appProcess) { [math]::Round($appProcess.WorkingSet64 / 1MB, 1) } else { $null }
        AppPrivateMemoryMB = if ($appProcess) { [math]::Round($appProcess.PrivateMemorySize64 / 1MB, 1) } else { $null }
        AppThreads = if ($appProcess) { $appProcess.Threads.Count } else { $null }
        # Include Ollama runner/server children and Windows PostgreSQL service-name variants.
        OllamaWorkingSetMB = Get-ProcessMemoryMB -Name @("ollama*")
        PostgresWorkingSetMB = Get-ProcessMemoryMB -Name @("postgres*")
        GpuUtilPercent = $gpuUtil
        GpuMemoryUsedMB = $gpuMemUsed
        GpuMemoryTotalMB = $gpuMemTotal
        GpuTemperatureC = $gpuTemp
    }
}

$workerScript = {
    param($BaseUrl, $PdfPath, $ContractNumber, $MemberNumber, $ServiceEntity,
          $RunId, $Concurrency, $Sequence, $TimeoutMinutes, $PollIntervalSeconds)

    $ErrorActionPreference = "Stop"
    $correlation = [Guid]::NewGuid().ToString()
    $headers = @{
        "X-Actor-Roles" = "DOCUMENT_PROCESSOR"
        "X-Correlation-Id" = $correlation
    }
    $terminal = @("COMPLETED", "PENDING_REVIEW", "PENDING_FINAL_APPROVAL", "APPROVED", "VAULTED", "FAILED", "REJECTED", "CANCELLED")
    $overall = [Diagnostics.Stopwatch]::StartNew()
    $createdAt = (Get-Date).ToString("o")
    $packageId = $null
    $status = "ERROR"
    $errorText = $null
    $createMs = 0L
    $uploadMs = 0L
    $submitMs = 0L
    $processingMs = 0L

    try {
        $body = @{
            packageType = "MANUAL_UPLOAD"
            businessIdentifiers = @{
                contract_number = $ContractNumber
                member_number = $MemberNumber
                service_entity = $ServiceEntity
            }
            metadata = @{
                performanceRunId = $RunId
                performanceConcurrency = [string]$Concurrency
                performanceSequence = [string]$Sequence
            }
        } | ConvertTo-Json -Depth 6

        $sw = [Diagnostics.Stopwatch]::StartNew()
        $created = Invoke-RestMethod -Uri "$BaseUrl/api/v1/packages" -Method Post -Headers $headers -ContentType "application/json" -Body $body -TimeoutSec 30
        $sw.Stop(); $createMs = $sw.ElapsedMilliseconds
        $packageId = [string]$created.packageId

        $sw.Restart()
        $curlArgs = @(
            "-sS", "-f", "-X", "POST",
            "-H", "X-Actor-Roles: DOCUMENT_PROCESSOR",
            "-H", "X-Correlation-Id: $correlation",
            "-F", "file=@$PdfPath;type=application/pdf",
            "$BaseUrl/api/v1/packages/$packageId/documents"
        )
        $uploadRaw = & curl.exe @curlArgs 2>&1
        $curlExit = $LASTEXITCODE
        $sw.Stop(); $uploadMs = $sw.ElapsedMilliseconds
        if ($curlExit -ne 0) { throw "Document upload failed (curl exit $curlExit): $uploadRaw" }

        $sw.Restart()
        Invoke-RestMethod -Uri "$BaseUrl/api/v1/packages/$packageId/submit" -Method Post -Headers $headers -TimeoutSec 30 | Out-Null
        $sw.Stop(); $submitMs = $sw.ElapsedMilliseconds

        $processing = [Diagnostics.Stopwatch]::StartNew()
        $deadline = (Get-Date).AddMinutes($TimeoutMinutes)
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Seconds $PollIntervalSeconds
            $detail = Invoke-RestMethod -Uri "$BaseUrl/api/v1/packages/$packageId" -Method Get -Headers $headers -TimeoutSec 30
            $status = [string]$detail.status
            if ($terminal -contains $status) { break }
        }
        $processing.Stop(); $processingMs = $processing.ElapsedMilliseconds
        if (-not ($terminal -contains $status)) {
            $status = "TIMEOUT"
            $errorText = "Package did not reach an automatic terminal/pause state within $TimeoutMinutes minute(s)."
        }
    }
    catch {
        $errorText = $_.Exception.Message
    }
    finally {
        $overall.Stop()
    }

    [pscustomobject]@{
        RunId = $RunId
        Concurrency = $Concurrency
        Sequence = $Sequence
        PackageId = $packageId
        Pdf = [IO.Path]::GetFileName($PdfPath)
        ContractNumber = $ContractNumber
        ServiceEntity = $ServiceEntity
        StartedAt = $createdAt
        FinishedAt = (Get-Date).ToString("o")
        Status = $status
        CreateMs = $createMs
        UploadMs = $uploadMs
        SubmitMs = $submitMs
        ProcessingWaitMs = $processingMs
        TotalClientMs = $overall.ElapsedMilliseconds
        Error = $errorText
    }
}

$appPid = Get-AppPid
Write-Host ""
Write-Host "=== LexaVault performance baseline ===" -ForegroundColor Cyan
Write-Host "Run ID:      $runId"
Write-Host "Output:      $runDir"
Write-Host "App PID:     $appPid"
Write-Host "PDF count:   $($resolvedPdfs.Count)"
Write-Host "Concurrency: $($ConcurrencyLevels -join ', ')"
Write-Host ""
Write-Host "This creates test packages in the current LOCAL/demo database. Use non-production test PDFs only." -ForegroundColor Yellow

$allResults = New-Object System.Collections.Generic.List[object]
$allResources = New-Object System.Collections.Generic.List[object]
$globalSequence = 0
$seed8 = [long]([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() % 100000000)

foreach ($level in $ConcurrencyLevels) {
    Write-Host "--- Concurrency $level ---" -ForegroundColor Cyan
    $levelStart = Get-Date
    $jobs = @()
    $completed = 0
    $launched = 0

    while ($completed -lt $PackagesPerLevel) {
        while ($launched -lt $PackagesPerLevel -and (@($jobs | Where-Object { $_.State -eq "Running" }).Count -lt $level)) {
            $globalSequence++
            $sequence = $globalSequence
            $pdf = $resolvedPdfs[($sequence - 1) % $resolvedPdfs.Count]
            $contract = "9{0:D8}{1:D3}" -f $seed8, ($sequence % 1000)
            $memberSeed = $seed8 % 10000000
            $member = "8{0:D7}{1:D3}" -f $memberSeed, ($sequence % 1000)
            $serviceEntity = "{0:D3}" -f (($sequence - 1) % 6)

            $job = Start-Job -ScriptBlock $workerScript -ArgumentList @(
                $BaseUrl, $pdf, $contract, $member, $serviceEntity,
                $runId, $level, $sequence, $TimeoutMinutes, $PollIntervalSeconds)
            $jobs += $job
            $launched++
        }

        $allResources.Add((Get-ResourceSample -AppPid $appPid -Concurrency $level))

        foreach ($job in @($jobs | Where-Object { $_.State -in @("Completed", "Failed", "Stopped") })) {
            $result = Receive-Job -Job $job -ErrorAction SilentlyContinue | Select-Object -Last 1
            if ($result) {
                $allResults.Add($result)
                Write-Host ("[{0}] {1} -> {2} ({3} ms)" -f $level, $result.PackageId, $result.Status, $result.TotalClientMs)
            }
            else {
                $allResults.Add([pscustomobject]@{
                    RunId=$runId; Concurrency=$level; Sequence=-1; PackageId=$null; Pdf=$null; ContractNumber=$null;
                    ServiceEntity=$null; StartedAt=$null; FinishedAt=(Get-Date).ToString("o"); Status="JOB_FAILED";
                    CreateMs=0; UploadMs=0; SubmitMs=0; ProcessingWaitMs=0; TotalClientMs=0;
                    Error="Worker job ended without a result."
                })
            }
            Remove-Job -Job $job -Force
            $jobs = @($jobs | Where-Object { $_.Id -ne $job.Id })
            $completed++
        }

        Start-Sleep -Seconds 1
    }

    $levelEnd = Get-Date
    $wallSeconds = [math]::Round(($levelEnd - $levelStart).TotalSeconds, 3)
    [pscustomobject]@{
        RunId = $runId
        Concurrency = $level
        Packages = $PackagesPerLevel
        StartedAt = $levelStart.ToString("o")
        FinishedAt = $levelEnd.ToString("o")
        WallSeconds = $wallSeconds
        PackagesPerMinute = if ($wallSeconds -gt 0) { [math]::Round($PackagesPerLevel * 60.0 / $wallSeconds, 3) } else { 0 }
    } | Export-Csv -LiteralPath (Join-Path $runDir ("level-{0}-wall.csv" -f $level)) -NoTypeInformation

    if ($CoolDownSeconds -gt 0 -and $level -ne $ConcurrencyLevels[-1]) {
        Write-Host "Cooling down for $CoolDownSeconds second(s)..." -ForegroundColor DarkGray
        Start-Sleep -Seconds $CoolDownSeconds
    }
}

$allResults | Export-Csv -LiteralPath (Join-Path $runDir "packages.csv") -NoTypeInformation
$allResources | Export-Csv -LiteralPath (Join-Path $runDir "resources.csv") -NoTypeInformation

$manifest["completedAt"] = (Get-Date).ToString("o")
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $runDir "run-manifest.json") -Encoding UTF8

Write-Host ""
Write-Host "Baseline capture complete." -ForegroundColor Green
Write-Host "Package results:  $(Join-Path $runDir 'packages.csv')"
Write-Host "Resource samples: $(Join-Path $runDir 'resources.csv')"
Write-Host ""
Write-Host "Next: .\scripts\Summarize-LexaVault-PerformanceBaseline.ps1 -RunDirectory `"$runDir`"" -ForegroundColor Cyan
