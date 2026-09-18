param(
    [Parameter(Mandatory=$true)]
    [string[]]$PdfPath,
    [int[]]$ConcurrencyLevels = @(1, 2, 4, 6, 8),
    [int]$PackagesPerLevel = 8,
    [string[]]$BaseUrls = @(),
    [string]$ClusterRoot = ".\performance-cluster",
    [int]$TimeoutMinutes = 30,
    [int]$PollIntervalSeconds = 2,
    [int]$CoolDownSeconds = 5,
    [string]$OutputRoot = ".\performance-results",
    [string]$RunLabel = "r3-cluster"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$ClusterRootFull = [IO.Path]::GetFullPath((Join-Path $ProjectRoot $ClusterRoot))
$ClusterManifestPath = Join-Path $ClusterRootFull ".run\cluster-manifest.json"

if ($PackagesPerLevel -lt 1 -or $PackagesPerLevel -gt 999) { throw "PackagesPerLevel must be between 1 and 999." }
if ($ConcurrencyLevels.Count -eq 0 -or ($ConcurrencyLevels | Where-Object { $_ -lt 1 })) { throw "ConcurrencyLevels must contain positive integers." }

if ($BaseUrls.Count -eq 0) {
    if (-not (Test-Path $ClusterManifestPath)) { throw "BaseUrls were not supplied and performance-cluster manifest was not found: $ClusterManifestPath" }
    $clusterManifest = Get-Content -LiteralPath $ClusterManifestPath -Raw | ConvertFrom-Json
    $BaseUrls = @($clusterManifest.nodes | ForEach-Object { [string]$_.baseUrl })
}
$BaseUrls = @($BaseUrls | ForEach-Object { $_.TrimEnd('/') })
if ($BaseUrls.Count -lt 2) { throw "R3 cluster baseline requires at least two BaseUrls." }

$resolvedPdfs = @()
foreach ($candidate in $PdfPath) {
    $resolved = (Resolve-Path -LiteralPath $candidate -ErrorAction Stop).Path
    if ([IO.Path]::GetExtension($resolved).ToLowerInvariant() -ne ".pdf") { throw "PDF files only: $resolved" }
    $resolvedPdfs += $resolved
}

foreach ($baseUrl in $BaseUrls) {
    try {
        $health = Invoke-RestMethod -Uri "$baseUrl/actuator/health" -Method Get -TimeoutSec 5
        if ($health.status -ne "UP") { throw "Health status is $($health.status)" }
    }
    catch { throw "Cluster node is not healthy at $baseUrl. $($_.Exception.Message)" }
}

$runId = ((Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")) + "-" + ([Guid]::NewGuid().ToString("N").Substring(0,8))
$runDir = Join-Path $OutputRoot ("{0}-{1}" -f $RunLabel, $runId)
New-Item -ItemType Directory -Force -Path $runDir | Out-Null

$manifest = [ordered]@{
    runId = $runId
    workloadType = "multi-node-scale"
    startedAt = (Get-Date).ToString("o")
    baseUrls = $BaseUrls
    concurrencyLevels = $ConcurrencyLevels
    packagesPerLevel = $PackagesPerLevel
    pdfs = $resolvedPdfs
    timeoutMinutes = $TimeoutMinutes
    pollIntervalSeconds = $PollIntervalSeconds
    note = "Cross-node API flow: create/upload/submit are intentionally rotated across healthy app nodes; package processing executes on the submit node. Creates disposable local-demo packages."
}
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $runDir "run-manifest.json") -Encoding UTF8

function Get-Percentile {
    param([double[]]$Values, [double]$P)
    if (-not $Values -or $Values.Count -eq 0) { return $null }
    $sorted = @($Values | Sort-Object)
    if ($sorted.Count -eq 1) { return [double]$sorted[0] }
    $rank = ($P / 100.0) * ($sorted.Count - 1)
    $lower = [math]::Floor($rank)
    $upper = [math]::Ceiling($rank)
    if ($lower -eq $upper) { return [double]$sorted[$lower] }
    $weight = $rank - $lower
    return [double]$sorted[$lower] + (([double]$sorted[$upper] - [double]$sorted[$lower]) * $weight)
}

function Get-AppNodeInfo {
    param([string[]]$Urls)
    $rows = New-Object System.Collections.Generic.List[object]
    for ($i = 0; $i -lt $Urls.Count; $i++) {
        $uri = [Uri]$Urls[$i]
        $port = $uri.Port
        $appProcessId = $null
        try {
            $listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction Stop | Select-Object -First 1
            if ($listener) { $appProcessId = [int]$listener.OwningProcess }
        }
        catch { }
        $rows.Add([pscustomobject]@{ NodeIndex=$i; NodeId=("PERF-APP-{0:D2}" -f ($i+1)); BaseUrl=$Urls[$i]; Port=$port; AppPid=$appProcessId })
    }
    return $rows.ToArray()
}

function Get-PerfProcessRow {
    param([int]$ProcessId)
    if (-not $ProcessId) { return $null }
    try {
        return Get-CimInstance Win32_PerfFormattedData_PerfProc_Process -ErrorAction Stop |
            Where-Object { [int]$_.IDProcess -eq $ProcessId } | Select-Object -First 1
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

function Convert-MemoryTextToMB {
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) { return 0 }
    $first = ($Text -split '/')[0].Trim()
    if ($first -match '^([0-9.]+)\s*(KiB|MiB|GiB|KB|MB|GB)$') {
        $value = [double]$matches[1]
        switch ($matches[2]) {
            'KiB' { return [math]::Round($value / 1024.0, 1) }
            'MiB' { return [math]::Round($value, 1) }
            'GiB' { return [math]::Round($value * 1024.0, 1) }
            'KB'  { return [math]::Round($value / 1000.0, 1) }
            'MB'  { return [math]::Round($value, 1) }
            'GB'  { return [math]::Round($value * 1000.0, 1) }
        }
    }
    return 0
}

function Get-PostgresMemoryMB {
    $hostMemory = Get-ProcessMemoryMB -Name @("postgres*")
    if ($hostMemory -gt 0) { return $hostMemory }
    if (Get-Command docker -ErrorAction SilentlyContinue) {
        try {
            $text = (& docker stats securedoc-postgres --no-stream --format "{{.MemUsage}}" 2>$null | Select-Object -First 1)
            return Convert-MemoryTextToMB -Text $text
        }
        catch { }
    }
    return 0
}

function Get-ResourceSamples {
    param([object[]]$Nodes, [int]$Concurrency)
    $os = Get-CimInstance Win32_OperatingSystem
    $cpuTotal = $null
    try {
        $cpuTotal = (Get-CimInstance Win32_PerfFormattedData_PerfOS_Processor -ErrorAction Stop | Where-Object { $_.Name -eq "_Total" } | Select-Object -First 1).PercentProcessorTime
    }
    catch { }

    $gpuUtil = $null; $gpuMemUsed = $null; $gpuMemTotal = $null; $gpuTemp = $null
    if (Get-Command nvidia-smi -ErrorAction SilentlyContinue) {
        try {
            $gpuLine = (& nvidia-smi --query-gpu=utilization.gpu,memory.used,memory.total,temperature.gpu --format=csv,noheader,nounits 2>$null | Select-Object -First 1)
            if ($gpuLine) {
                $parts = $gpuLine.Split(',') | ForEach-Object { $_.Trim() }
                if ($parts.Count -ge 4) { $gpuUtil=[double]$parts[0]; $gpuMemUsed=[double]$parts[1]; $gpuMemTotal=[double]$parts[2]; $gpuTemp=[double]$parts[3] }
            }
        }
        catch { }
    }

    $nodeRows = New-Object System.Collections.Generic.List[object]
    $totalAppCpu = 0.0; $totalWorking = 0.0; $totalPrivate = 0.0; $totalThreads = 0
    foreach ($node in $Nodes) {
        $appProcessId = $node.AppPid
        $perf = Get-PerfProcessRow -ProcessId $appProcessId
        $proc = if ($appProcessId) { Get-Process -Id $appProcessId -ErrorAction SilentlyContinue } else { $null }
        $cpu = if ($perf) { [double]$perf.PercentProcessorTime } else { 0.0 }
        $working = if ($proc) { [math]::Round($proc.WorkingSet64 / 1MB, 1) } else { 0.0 }
        $private = if ($proc) { [math]::Round($proc.PrivateMemorySize64 / 1MB, 1) } else { 0.0 }
        $threads = if ($proc) { $proc.Threads.Count } else { 0 }
        $totalAppCpu += $cpu; $totalWorking += $working; $totalPrivate += $private; $totalThreads += $threads
        $nodeRows.Add([pscustomobject]@{
            Timestamp=(Get-Date).ToString("o"); Concurrency=$Concurrency; NodeId=$node.NodeId; BaseUrl=$node.BaseUrl; Port=$node.Port; AppPid=$appProcessId;
            AppCpuPercent=$cpu; AppWorkingSetMB=$working; AppPrivateMemoryMB=$private; AppThreads=$threads
        })
    }

    $aggregate = [pscustomobject]@{
        Timestamp=(Get-Date).ToString("o"); Concurrency=$Concurrency; AppNodeCount=$Nodes.Count;
        SystemCpuPercent=$cpuTotal; SystemFreeMemoryMB=[math]::Round($os.FreePhysicalMemory / 1024, 1);
        TotalAppCpuPercent=[math]::Round($totalAppCpu,1); TotalAppWorkingSetMB=[math]::Round($totalWorking,1); TotalAppPrivateMemoryMB=[math]::Round($totalPrivate,1); TotalAppThreads=$totalThreads;
        OllamaWorkingSetMB=Get-ProcessMemoryMB -Name @("ollama*"); PostgresWorkingSetMB=Get-PostgresMemoryMB;
        GpuUtilPercent=$gpuUtil; GpuMemoryUsedMB=$gpuMemUsed; GpuMemoryTotalMB=$gpuMemTotal; GpuTemperatureC=$gpuTemp
    }
    return [pscustomobject]@{ Aggregate=$aggregate; NodeRows=$nodeRows.ToArray() }
}

$workerScript = {
    param($BaseUrlsPayload, $PdfPath, $ContractNumber, $MemberNumber, $ServiceEntity, $RunId, $Concurrency, $Sequence, $TimeoutMinutes, $PollIntervalSeconds)
    $BaseUrls = @($BaseUrlsPayload -split "\|")
    $ErrorActionPreference = "Stop"
    $correlation = [Guid]::NewGuid().ToString()
    $headers = @{ "X-Actor-Roles"="DOCUMENT_PROCESSOR"; "X-Correlation-Id"=$correlation }
    $terminal = @("COMPLETED","PENDING_REVIEW","PENDING_FINAL_APPROVAL","APPROVED","VAULTED","FAILED","REJECTED","CANCELLED")
    $count = $BaseUrls.Count
    $createUrl = $BaseUrls[($Sequence - 1) % $count]
    $uploadUrl = $BaseUrls[$Sequence % $count]
    $submitUrl = $BaseUrls[($Sequence + 1) % $count]
    $pollStartIndex = ($Sequence + 2) % $count
    $overall = [Diagnostics.Stopwatch]::StartNew()
    $createdAt = (Get-Date).ToString("o")
    $packageId=$null; $status="ERROR"; $errorText=$null; $createMs=0L; $uploadMs=0L; $submitMs=0L; $processingMs=0L; $lastPollUrl=$null; $pollCount=0

    try {
        $body = @{
            packageType="MANUAL_UPLOAD"
            businessIdentifiers=@{ contract_number=$ContractNumber; member_number=$MemberNumber; service_entity=$ServiceEntity }
            metadata=@{ performanceRunId=$RunId; performanceConcurrency=[string]$Concurrency; performanceSequence=[string]$Sequence; performanceMode="r3-cluster" }
        } | ConvertTo-Json -Depth 6

        $sw=[Diagnostics.Stopwatch]::StartNew()
        $created=Invoke-RestMethod -Uri "$createUrl/api/v1/packages" -Method Post -Headers $headers -ContentType "application/json" -Body $body -TimeoutSec 30
        $sw.Stop(); $createMs=$sw.ElapsedMilliseconds; $packageId=[string]$created.packageId

        $sw.Restart()
        $curlArgs=@("-sS","-f","-X","POST","-H","X-Actor-Roles: DOCUMENT_PROCESSOR","-H","X-Correlation-Id: $correlation","-F","file=@$PdfPath;type=application/pdf","$uploadUrl/api/v1/packages/$packageId/documents")
        $uploadRaw=& curl.exe @curlArgs 2>&1; $curlExit=$LASTEXITCODE
        $sw.Stop(); $uploadMs=$sw.ElapsedMilliseconds
        if ($curlExit -ne 0) { throw "Document upload failed on $uploadUrl (curl exit $curlExit): $uploadRaw" }

        $sw.Restart()
        Invoke-RestMethod -Uri "$submitUrl/api/v1/packages/$packageId/submit" -Method Post -Headers $headers -TimeoutSec 30 | Out-Null
        $sw.Stop(); $submitMs=$sw.ElapsedMilliseconds

        $processing=[Diagnostics.Stopwatch]::StartNew(); $deadline=(Get-Date).AddMinutes($TimeoutMinutes)
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Seconds $PollIntervalSeconds
            $pollUrl=$BaseUrls[($pollStartIndex + $pollCount) % $count]; $lastPollUrl=$pollUrl; $pollCount++
            $detail=Invoke-RestMethod -Uri "$pollUrl/api/v1/packages/$packageId" -Method Get -Headers $headers -TimeoutSec 30
            $status=[string]$detail.status
            if ($terminal -contains $status) { break }
        }
        $processing.Stop(); $processingMs=$processing.ElapsedMilliseconds
        if (-not ($terminal -contains $status)) { $status="TIMEOUT"; $errorText="Package did not reach terminal/pause state within $TimeoutMinutes minute(s)." }
    }
    catch { $errorText=$_.Exception.Message }
    finally { $overall.Stop() }

    [pscustomobject]@{
        RunId=$RunId; Concurrency=$Concurrency; Sequence=$Sequence; PackageId=$packageId; Pdf=[IO.Path]::GetFileName($PdfPath); ContractNumber=$ContractNumber; ServiceEntity=$ServiceEntity;
        CreateNode=$createUrl; UploadNode=$uploadUrl; SubmitNode=$submitUrl; LastPollNode=$lastPollUrl; PollCount=$pollCount;
        StartedAt=$createdAt; FinishedAt=(Get-Date).ToString("o"); Status=$status; CreateMs=$createMs; UploadMs=$uploadMs; SubmitMs=$submitMs; ProcessingWaitMs=$processingMs; TotalClientMs=$overall.ElapsedMilliseconds; Error=$errorText
    }
}

$nodes = @(Get-AppNodeInfo -Urls $BaseUrls)
$baseUrlsPayload = ($BaseUrls -join "|")
Write-Host ""
Write-Host "=== LexaVault R3 multi-node performance baseline ===" -ForegroundColor Cyan
Write-Host "Run ID:      $runId"
Write-Host "Output:      $runDir"
Write-Host "Nodes:       $($BaseUrls -join ', ')"
Write-Host "Concurrency: $($ConcurrencyLevels -join ', ')"
Write-Host "Packages:    $PackagesPerLevel per level"
Write-Host ""
Write-Host "Create/upload/submit/poll calls rotate across nodes to prove shared-state API behavior while applying load." -ForegroundColor Yellow

$allResults=New-Object System.Collections.Generic.List[object]
$allResources=New-Object System.Collections.Generic.List[object]
$allNodeResources=New-Object System.Collections.Generic.List[object]
$globalSequence=0
$seed8=[long]([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() % 100000000)

foreach ($level in $ConcurrencyLevels) {
    Write-Host "--- Concurrency $level ---" -ForegroundColor Cyan
    $levelStart=Get-Date; $jobs=@(); $completed=0; $launched=0
    while ($completed -lt $PackagesPerLevel) {
        while ($launched -lt $PackagesPerLevel -and (@($jobs | Where-Object { $_.State -eq "Running" }).Count -lt $level)) {
            $globalSequence++; $sequence=$globalSequence
            $pdf=$resolvedPdfs[($sequence-1) % $resolvedPdfs.Count]
            $contract="9{0:D8}{1:D3}" -f $seed8, ($sequence % 1000)
            $memberSeed=$seed8 % 10000000
            $member="8{0:D7}{1:D3}" -f $memberSeed, ($sequence % 1000)
            $serviceEntity="{0:D3}" -f (($sequence-1) % 6)
            $jobs += Start-Job -ScriptBlock $workerScript -ArgumentList @($baseUrlsPayload,$pdf,$contract,$member,$serviceEntity,$runId,$level,$sequence,$TimeoutMinutes,$PollIntervalSeconds)
            $launched++
        }

        $sample=Get-ResourceSamples -Nodes $nodes -Concurrency $level
        $allResources.Add($sample.Aggregate)
        foreach ($r in $sample.NodeRows) { $allNodeResources.Add($r) }

        foreach ($job in @($jobs | Where-Object { $_.State -in @("Completed","Failed","Stopped") })) {
            $result=Receive-Job -Job $job -ErrorAction SilentlyContinue | Select-Object -Last 1
            if ($result) {
                $allResults.Add($result)
                Write-Host ("[{0}] {1} -> {2} ({3} ms) submit={4}" -f $level,$result.PackageId,$result.Status,$result.TotalClientMs,$result.SubmitNode)
            }
            else {
                $allResults.Add([pscustomobject]@{RunId=$runId;Concurrency=$level;Sequence=-1;PackageId=$null;Pdf=$null;ContractNumber=$null;ServiceEntity=$null;CreateNode=$null;UploadNode=$null;SubmitNode=$null;LastPollNode=$null;PollCount=0;StartedAt=$null;FinishedAt=(Get-Date).ToString("o");Status="JOB_FAILED";CreateMs=0;UploadMs=0;SubmitMs=0;ProcessingWaitMs=0;TotalClientMs=0;Error="Worker job ended without a result."})
            }
            Remove-Job -Job $job -Force
            $jobs=@($jobs | Where-Object { $_.Id -ne $job.Id })
            $completed++
        }
        Start-Sleep -Seconds 1
    }

    $levelEnd=Get-Date; $wallSeconds=[math]::Round(($levelEnd-$levelStart).TotalSeconds,3)
    [pscustomobject]@{ RunId=$runId;Concurrency=$level;Packages=$PackagesPerLevel;StartedAt=$levelStart.ToString("o");FinishedAt=$levelEnd.ToString("o");WallSeconds=$wallSeconds;PackagesPerMinute=if($wallSeconds -gt 0){[math]::Round($PackagesPerLevel*60.0/$wallSeconds,3)}else{0} } |
        Export-Csv -LiteralPath (Join-Path $runDir ("level-{0}-wall.csv" -f $level)) -NoTypeInformation

    if ($CoolDownSeconds -gt 0 -and $level -ne $ConcurrencyLevels[-1]) { Write-Host "Cooling down for $CoolDownSeconds second(s)..." -ForegroundColor DarkGray; Start-Sleep -Seconds $CoolDownSeconds }
}

$allResults | Export-Csv -LiteralPath (Join-Path $runDir "packages.csv") -NoTypeInformation
$allResources | Export-Csv -LiteralPath (Join-Path $runDir "resources.csv") -NoTypeInformation
$allNodeResources | Export-Csv -LiteralPath (Join-Path $runDir "node-resources.csv") -NoTypeInformation
$manifest["completedAt"]=(Get-Date).ToString("o")
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $runDir "run-manifest.json") -Encoding UTF8

Write-Host ""
Write-Host "R3 cluster baseline complete." -ForegroundColor Green
Write-Host "Packages:       $(Join-Path $runDir 'packages.csv')"
Write-Host "Resources:      $(Join-Path $runDir 'resources.csv')"
Write-Host "Node resources: $(Join-Path $runDir 'node-resources.csv')"
Write-Host "Next: .\scripts\Summarize-LexaVault-PerformanceCluster.ps1 -RunDirectory `"$runDir`" -ClusterRoot `"$ClusterRoot`"" -ForegroundColor Cyan
