param(
    [Parameter(Mandatory=$true)]
    [string[]]$PdfPath,
    [int]$Concurrency = 4,
    [int]$DurationMinutes = 30,
    [string[]]$BaseUrls = @(),
    [string]$ClusterRoot = ".\performance-cluster",
    [int]$PackageTimeoutMinutes = 10,
    [int]$PollIntervalSeconds = 2,
    [int]$ResourceSampleSeconds = 5,
    [string]$OutputRoot = ".\performance-results",
    [string]$RunLabel = "r3-soak"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$ClusterRootFull = [IO.Path]::GetFullPath((Join-Path $ProjectRoot $ClusterRoot))
$ClusterManifestPath = Join-Path $ClusterRootFull ".run\cluster-manifest.json"
if ($Concurrency -lt 1) { throw "Concurrency must be positive." }
if ($DurationMinutes -lt 1) { throw "DurationMinutes must be at least 1." }

if ($BaseUrls.Count -eq 0) {
    if (-not (Test-Path $ClusterManifestPath)) { throw "Cluster manifest not found: $ClusterManifestPath" }
    $clusterManifest = Get-Content -LiteralPath $ClusterManifestPath -Raw | ConvertFrom-Json
    $BaseUrls = @($clusterManifest.nodes | ForEach-Object { [string]$_.baseUrl })
}
$BaseUrls = @($BaseUrls | ForEach-Object { $_.TrimEnd('/') })
if ($BaseUrls.Count -lt 2) { throw "Soak requires at least two cluster nodes." }

$resolvedPdfs=@()
foreach ($candidate in $PdfPath) {
    $resolved=(Resolve-Path -LiteralPath $candidate -ErrorAction Stop).Path
    if ([IO.Path]::GetExtension($resolved).ToLowerInvariant() -ne '.pdf') { throw "PDF files only: $resolved" }
    $resolvedPdfs += $resolved
}

function Get-HealthyUrls {
    param([string[]]$Urls)
    $healthy=New-Object System.Collections.Generic.List[string]
    foreach ($url in $Urls) {
        try {
            $h=Invoke-RestMethod -Uri "$url/actuator/health" -Method Get -TimeoutSec 2
            if ($h.status -eq 'UP') { $healthy.Add($url) }
        }
        catch { }
    }
    return $healthy.ToArray()
}

$initialHealthy=@(Get-HealthyUrls -Urls $BaseUrls)
if ($initialHealthy.Count -lt 2) { throw "At least two healthy nodes are required before starting the soak." }

$runId=((Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ'))+'-'+([Guid]::NewGuid().ToString('N').Substring(0,8))
$runDir=Join-Path $OutputRoot ("{0}-{1}" -f $RunLabel,$runId)
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
$manifest=[ordered]@{
    runId=$runId; workloadType='multi-node-soak'; startedAt=(Get-Date).ToString('o'); durationMinutes=$DurationMinutes; concurrency=$Concurrency;
    baseUrls=$BaseUrls; pdfs=$resolvedPdfs; packageTimeoutMinutes=$PackageTimeoutMinutes; pollIntervalSeconds=$PollIntervalSeconds;
    note='Health-aware new-work routing is used during soak. Mutating requests are not blindly retried. This allows a controlled node stop/restart to expose in-flight recovery behavior without generating duplicate creates.'
}
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $runDir 'run-manifest.json') -Encoding UTF8

function Get-PortPid {
    param([string]$Url)
    try {
        $port=([Uri]$Url).Port
        $listener=Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction Stop | Select-Object -First 1
        if ($listener) { return [int]$listener.OwningProcess }
    }
    catch { }
    return $null
}

function Get-PerfProcessRow {
    param([int]$ProcessId)
    if (-not $ProcessId) { return $null }
    try { return Get-CimInstance Win32_PerfFormattedData_PerfProc_Process -ErrorAction Stop | Where-Object { [int]$_.IDProcess -eq $ProcessId } | Select-Object -First 1 }
    catch { return $null }
}

function Get-ProcessMemoryMB {
    param([string[]]$Name)
    try { $sum=(Get-Process -Name $Name -ErrorAction SilentlyContinue | Measure-Object WorkingSet64 -Sum).Sum; if($null -eq $sum){return 0}; return [math]::Round($sum/1MB,1) }
    catch { return 0 }
}

function Get-ResourceSample {
    param([string[]]$Urls)
    $os=Get-CimInstance Win32_OperatingSystem
    $cpu=$null
    try { $cpu=(Get-CimInstance Win32_PerfFormattedData_PerfOS_Processor -ErrorAction Stop | Where-Object { $_.Name -eq '_Total' } | Select-Object -First 1).PercentProcessorTime } catch { }
    $totalCpu=0.0; $totalWorking=0.0; $appCount=0
    foreach($url in $Urls){
        $appProcessId=Get-PortPid -Url $url
        if($appProcessId){
            $appCount++
            $perf=Get-PerfProcessRow -ProcessId $appProcessId
            if($perf){$totalCpu += [double]$perf.PercentProcessorTime}
            $proc=Get-Process -Id $appProcessId -ErrorAction SilentlyContinue
            if($proc){$totalWorking += $proc.WorkingSet64/1MB}
        }
    }
    $gpuUtil=$null; $gpuMem=$null; $gpuTemp=$null
    if(Get-Command nvidia-smi -ErrorAction SilentlyContinue){
        try{$line=(& nvidia-smi --query-gpu=utilization.gpu,memory.used,temperature.gpu --format=csv,noheader,nounits 2>$null|Select-Object -First 1); if($line){$p=$line.Split(',')|ForEach-Object{$_.Trim()};$gpuUtil=[double]$p[0];$gpuMem=[double]$p[1];$gpuTemp=[double]$p[2]}}catch{}
    }
    [pscustomobject]@{
        Timestamp=(Get-Date).ToString('o'); HealthyNodeCount=@(Get-HealthyUrls -Urls $Urls).Count; AppProcessCount=$appCount; SystemCpuPercent=$cpu;
        SystemFreeMemoryMB=[math]::Round($os.FreePhysicalMemory/1024,1); TotalAppCpuPercent=[math]::Round($totalCpu,1); TotalAppWorkingSetMB=[math]::Round($totalWorking,1);
        OllamaWorkingSetMB=Get-ProcessMemoryMB -Name @('ollama*'); GpuUtilPercent=$gpuUtil; GpuMemoryUsedMB=$gpuMem; GpuTemperatureC=$gpuTemp
    }
}

$workerScript={
    param($BaseUrlsPayload,$PdfPath,$ContractNumber,$MemberNumber,$ServiceEntity,$RunId,$Sequence,$TimeoutMinutes,$PollIntervalSeconds)
    $BaseUrls=@($BaseUrlsPayload -split "\|")
    $ErrorActionPreference='Stop'
    $correlation=[Guid]::NewGuid().ToString(); $headers=@{'X-Actor-Roles'='DOCUMENT_PROCESSOR';'X-Correlation-Id'=$correlation}
    $terminal=@('COMPLETED','PENDING_REVIEW','PENDING_FINAL_APPROVAL','APPROVED','VAULTED','FAILED','REJECTED','CANCELLED')
    $count=$BaseUrls.Count
    $createUrl=$BaseUrls[($Sequence-1)%$count]; $uploadUrl=$BaseUrls[$Sequence%$count]; $submitUrl=$BaseUrls[($Sequence+1)%$count]
    $overall=[Diagnostics.Stopwatch]::StartNew(); $createdAt=(Get-Date).ToString('o'); $packageId=$null; $status='ERROR'; $err=$null; $lastPoll=$null
    try{
        $body=@{packageType='MANUAL_UPLOAD';businessIdentifiers=@{contract_number=$ContractNumber;member_number=$MemberNumber;service_entity=$ServiceEntity};metadata=@{performanceRunId=$RunId;performanceSequence=[string]$Sequence;performanceMode='r3-soak'}}|ConvertTo-Json -Depth 6
        $created=Invoke-RestMethod -Uri "$createUrl/api/v1/packages" -Method Post -Headers $headers -ContentType 'application/json' -Body $body -TimeoutSec 30
        $packageId=[string]$created.packageId
        $curlArgs=@('-sS','-f','-X','POST','-H','X-Actor-Roles: DOCUMENT_PROCESSOR','-H',"X-Correlation-Id: $correlation",'-F',"file=@$PdfPath;type=application/pdf","$uploadUrl/api/v1/packages/$packageId/documents")
        $raw=& curl.exe @curlArgs 2>&1; if ($LASTEXITCODE -ne 0){throw "Upload failed on ${uploadUrl}: $raw"}
        Invoke-RestMethod -Uri "$submitUrl/api/v1/packages/$packageId/submit" -Method Post -Headers $headers -TimeoutSec 30|Out-Null
        $deadline=(Get-Date).AddMinutes($TimeoutMinutes); $pollIndex=0
        while ((Get-Date) -lt $deadline){
            Start-Sleep -Seconds $PollIntervalSeconds
            $got=$false; $lastError=$null
            for ($attempt = 0; $attempt -lt $count; $attempt++){
                $pollUrl=$BaseUrls[($Sequence+$pollIndex+$attempt)%$count]; $lastPoll=$pollUrl
                try{$detail=Invoke-RestMethod -Uri "$pollUrl/api/v1/packages/$packageId" -Method Get -Headers $headers -TimeoutSec 10;$got=$true;break}catch{$lastError=$_.Exception.Message}
            }
            $pollIndex++
            if(-not$got){throw "All poll nodes unavailable: $lastError"}
            $status=[string]$detail.status
            if ($terminal -contains $status){break}
        }
        if (-not ($terminal -contains $status)){$status='TIMEOUT';$err="Package did not reach terminal/pause state in $TimeoutMinutes minute(s)."}
    }catch{$err=$_.Exception.Message}finally{$overall.Stop()}
    [pscustomobject]@{RunId=$RunId;Sequence=$Sequence;PackageId=$packageId;Pdf=[IO.Path]::GetFileName($PdfPath);CreateNode=$createUrl;UploadNode=$uploadUrl;SubmitNode=$submitUrl;LastPollNode=$lastPoll;StartedAt=$createdAt;FinishedAt=(Get-Date).ToString('o');Status=$status;TotalClientMs=$overall.ElapsedMilliseconds;Error=$err}
}

Write-Host ""
Write-Host "=== LexaVault R3 multi-node soak ===" -ForegroundColor Cyan
Write-Host "Run ID:       $runId"
Write-Host "Duration:     $DurationMinutes minute(s)"
Write-Host "Concurrency:  $Concurrency"
Write-Host "Nodes:        $($BaseUrls -join ', ')"
Write-Host "Output:       $runDir"
Write-Host ""
Write-Host "During this soak you may intentionally stop one node with Stop-LexaVault-PerformanceClusterNode.ps1. New work will use healthy nodes; in-flight behavior will be captured." -ForegroundColor Yellow

$endTime=(Get-Date).AddMinutes($DurationMinutes); $jobs=@(); $sequence=0; $results=New-Object System.Collections.Generic.List[object];$resources=New-Object System.Collections.Generic.List[object];$healthRows=New-Object System.Collections.Generic.List[object]
$seed8=[long]([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()%100000000);$nextResource=Get-Date

while ((Get-Date) -lt $endTime -or $jobs.Count -gt 0){
    foreach($job in @($jobs|Where-Object { $_.State -in @('Completed','Failed','Stopped') })){
        $result=Receive-Job -Job $job -ErrorAction SilentlyContinue|Select-Object -Last 1
        if($result){$results.Add($result);Write-Host ("#{0} {1} -> {2} ({3} ms)" -f $result.Sequence, $result.PackageId, $result.Status, $result.TotalClientMs)}
        else{$results.Add([pscustomobject]@{RunId=$runId;Sequence=-1;PackageId=$null;Pdf=$null;CreateNode=$null;UploadNode=$null;SubmitNode=$null;LastPollNode=$null;StartedAt=$null;FinishedAt=(Get-Date).ToString('o');Status='JOB_FAILED';TotalClientMs=0;Error='Worker ended without result.'})}
        Remove-Job -Job $job -Force;$jobs=@($jobs|Where-Object { $_.Id -ne $job.Id })
    }

    if ((Get-Date) -lt $endTime){
        $healthy=@(Get-HealthyUrls -Urls $BaseUrls)
        $healthyPayload=($healthy -join "|")
        foreach($url in $BaseUrls){$healthRows.Add([pscustomobject]@{Timestamp=(Get-Date).ToString('o');BaseUrl=$url;Healthy=($healthy -contains $url)})}
        while ($jobs.Count -lt $Concurrency -and (Get-Date) -lt $endTime){
            if ($healthy.Count -lt 1){Write-Host 'No healthy nodes; waiting...' -ForegroundColor Red;break}
            $sequence++;$pdf=$resolvedPdfs[($sequence-1)%$resolvedPdfs.Count];$contract="9{0:D8}{1:D3}"-f$seed8,($sequence%1000);$memberSeed=$seed8%10000000;$member="8{0:D7}{1:D3}"-f$memberSeed,($sequence%1000);$serviceEntity="{0:D3}"-f(($sequence-1)%6)
            $jobs+=Start-Job -ScriptBlock $workerScript -ArgumentList @($healthyPayload,$pdf,$contract,$member,$serviceEntity,$runId,$sequence,$PackageTimeoutMinutes,$PollIntervalSeconds)
        }
    }

    if ((Get-Date) -ge $nextResource){$resources.Add((Get-ResourceSample -Urls $BaseUrls));$nextResource=(Get-Date).AddSeconds($ResourceSampleSeconds)}
    if ((Get-Date) -ge $endTime -and $jobs.Count -gt 0){Write-Host "Soak launch window ended; waiting for $($jobs.Count) in-flight package(s)..." -ForegroundColor DarkGray}
    Start-Sleep -Seconds 1
}

$results | Export-Csv -LiteralPath (Join-Path $runDir 'packages.csv') -NoTypeInformation
$resources | Export-Csv -LiteralPath (Join-Path $runDir 'resources.csv') -NoTypeInformation
$healthRows | Export-Csv -LiteralPath (Join-Path $runDir 'node-health.csv') -NoTypeInformation
$manifest['completedAt']=(Get-Date).ToString('o');$manifest['packagesLaunched']=$sequence;$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $runDir 'run-manifest.json') -Encoding UTF8
Write-Host ""
Write-Host "Soak complete: $sequence package(s) launched." -ForegroundColor Green
Write-Host "Results: $(Join-Path $runDir 'packages.csv')"
Write-Host "Health:  $(Join-Path $runDir 'node-health.csv')"
Write-Host ""
