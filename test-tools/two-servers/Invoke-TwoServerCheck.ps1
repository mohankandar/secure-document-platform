#requires -Version 5.1
<#
.SYNOPSIS
    Task 0048 (staged pipeline P2): two LexaVault servers on one database. No case is processed twice, a server killed
    mid-case is finished by the other, and a late save from the killed server is refused.

.DESCRIPTION
    1. Starts a THROWAWAY Postgres container (lexavault-0048-2s-pg, 127.0.0.1:55449). It never touches
       securedoc-postgres or any other container named in CLAUDE.md. Runs the install steps (schema, setup, demo).
    2. Makes the demo entity's processing storage a SHARED_FILESYSTEM in the run folder: two servers may not use
       LOCAL_FILESYSTEM storage (LexaVault refuses it), and both servers here share one folder, as on one file share.
    3. Starts the fake-value mock Ollama (task 0015) with a delay per answer, so Find jobs take a while.
    4. Starts server A (18048) and server B (18049) from the same jar on that database, pipeline-mode staged,
       export-mode job, claim lease 15 s. Each writes its own logs.
    5. Creates the cases (default 20, 3 documents each: typed, scanned, typed), half through A and half through B,
       one after the other, and submits them.
    6. Waits until server A holds a running Text, Find and Redact job at the same time (read in the database), notes
       those jobs and their claim numbers, and kills A's process (Stop-Process -Force: no shutdown, no clean-up).
    7. Waits until every case is exported. Server B alone does the rest; A's claims run out after 15 s.
    8. Tries A's late saves: the exact fenced update the workers use, with A's old claim numbers. Each must change 0
       rows.
    9. Checks in the database and the audit: every case COMPLETED; per document exactly one TEXT, FIND and REDACT job,
       one set of findings (no duplicate), one redacted copy, one text read, one finding run and one final check (task
       0044) audited; per case one EXPORT job and one delivery; the fake values are in no table (pg_dump).
   10. Stops B and the mock and removes only the throwaway container.

    The container password is generated per run and never printed. The run folder holds fake test values only.

.PARAMETER JarPath
    The backend jar to test.
#>
[CmdletBinding()]
param(
    [string] $JarPath,
    [string] $RunRoot = (Join-Path $env:TEMP 'lexavault-0048'),
    [int] $DbPort = 55449,
    [int] $PortA = 18048,
    [int] $PortB = 18049,
    [int] $OllamaPort = 11548,
    [string] $Model = 'qwen3.5:9b',
    [int] $Cases = 20,
    [int] $AiDelayMs = 2500,
    [int] $ClaimLeaseMs = 15000,
    [int] $FinishTimeoutSeconds = 900
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$here = $PSScriptRoot
$repoRoot = (Resolve-Path (Join-Path $here '..\..')).Path
if (-not $JarPath) { $JarPath = Join-Path $repoRoot 'backend\securedoc-api\target\securedoc-api-0.1.0-SNAPSHOT.jar' }
if (-not (Test-Path -LiteralPath $JarPath)) { throw "Backend jar not found: $JarPath (build it first)." }

$Container = 'lexavault-0048-2s-pg'
$Protected = @('securedoc-postgres', 'sdv-postgres', 'sdv-vault', 'lexa-keycloak', 'lexa-keycloak-db')
if ($Protected -contains $Container) { throw "Refusing to use a protected container name." }

$tenantId = '30216241-b724-5a3a-aef3-3034fd546d17'
$actorId = '00000000-0000-0000-0000-000000000099'
$roles = 'DOCUMENT_PROCESSOR,TENANT_IT_ADMIN'

$runDir = Join-Path $RunRoot (Get-Date -Format 'yyyyMMdd-HHmmss')
$shared = Join-Path $runDir 'shared-storage'
$exportDir = Join-Path $runDir 'export-folder'
New-Item -ItemType Directory -Force -Path $runDir, $shared, $exportDir | Out-Null
Write-Host "Run folder: $runDir"

function Invoke-Sql([string] $sql) {
    $out = docker exec $Container psql -U securedoc -d securedoc -tA -c $sql
    if ($LASTEXITCODE -ne 0) { throw "psql failed: $sql" }
    return (($out | Out-String).Trim())
}

function Invoke-Api([string] $Backend, [string] $Method, [string] $Path, $Body = $null) {
    $headers = @{ 'X-Tenant-Id' = $tenantId; 'X-Actor-Id' = $actorId; 'X-Actor-Roles' = $roles
        'X-Correlation-Id' = [guid]::NewGuid().ToString() }
    $req = @{ Method = $Method; Uri = "$Backend$Path"; Headers = $headers; UseBasicParsing = $true; TimeoutSec = 60 }
    if ($null -ne $Body) { $req.Body = ($Body | ConvertTo-Json -Depth 8); $req.ContentType = 'application/json' }
    return Invoke-RestMethod @req
}

function Send-Document([string] $Backend, [string] $PackageId, [string] $Pdf, [string] $Name) {
    Add-Type -AssemblyName System.Net.Http
    $client = [System.Net.Http.HttpClient]::new()
    try {
        $client.DefaultRequestHeaders.Add('X-Tenant-Id', $tenantId)
        $client.DefaultRequestHeaders.Add('X-Actor-Id', $actorId)
        $client.DefaultRequestHeaders.Add('X-Actor-Roles', $roles)
        $content = [System.Net.Http.MultipartFormDataContent]::new()
        $file = [System.Net.Http.ByteArrayContent]::new([System.IO.File]::ReadAllBytes($Pdf))
        $file.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new('application/pdf')
        $content.Add($file, 'file', $Name)
        $response = $client.PostAsync("$Backend/api/v1/packages/$PackageId/documents", $content).GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) { throw "upload failed: HTTP $([int]$response.StatusCode)" }
    } finally { $client.Dispose() }
}

function Start-Server([string] $Name, [int] $Port, [string] $DbPassword) {
    $logDir = Join-Path $runDir "logs-$Name"
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:$DbPort/securedoc"
    $env:SPRING_DATASOURCE_USERNAME = 'securedoc'
    $env:SPRING_DATASOURCE_PASSWORD = $DbPassword
    $env:SERVER_PORT = "$Port"
    $env:SECUREDOC_NODE_ID = "node-$Name"
    $env:SECUREDOC_AI_PROVIDER = 'ollama'
    $env:SECUREDOC_AI_OLLAMA_BASE_URL = "http://localhost:$OllamaPort"
    $env:SECUREDOC_AI_MODEL = $Model
    $env:SECUREDOC_AI_OLLAMA_PRELOAD_ENABLED = 'false'
    $env:SECUREDOC_WATCHED_FOLDER_ENABLED = 'false'
    $env:SECUREDOC_WATCHED_FOLDER_SCHEDULING_ENABLED = 'false'
    $env:SECUREDOC_LOGGING_CONFIG = 'classpath:logback-json-rolling.xml'
    $env:SECUREDOC_LOG_DIR = $logDir
    $env:SECUREDOC_PRODUCT_TELEMETRY_OUTPUT_PATH = (Join-Path $logDir 'product-telemetry.jsonl')
    $env:SECUREDOC_DELIVERY_LOCAL_OUTPUT_PATH = $exportDir
    $env:SECUREDOC_PROCESSING_PIPELINE_MODE = 'staged'
    $env:SECUREDOC_PROCESSING_EXPORT_MODE = 'job'
    $env:SECUREDOC_STAGE_JOBS_CLAIM_LEASE_MS = "$ClaimLeaseMs"
    $env:SECUREDOC_STAGE_JOBS_POLL_INTERVAL_MS = '500'
    $env:SECUREDOC_EXPORT_JOBS_POLL_INTERVAL_MS = '1000'
    try {
        return Start-Process -FilePath 'java' -PassThru -WindowStyle Hidden -WorkingDirectory $runDir `
            -ArgumentList @('-jar', $JarPath, '--spring.profiles.active=local',
                "--securedoc.storage.local.root-path=$shared") `
            -RedirectStandardOutput (Join-Path $logDir 'backend.out.log') -RedirectStandardError (Join-Path $logDir 'backend.err.log')
    } finally {
        Remove-Item Env:SPRING_DATASOURCE_PASSWORD -ErrorAction SilentlyContinue
    }
}

function Wait-Healthy($Process, [string] $Backend) {
    $deadline = (Get-Date).AddSeconds(180)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        if ($Process.HasExited) { throw "server $Backend exited during startup" }
        try { if ((Invoke-RestMethod -Uri "$Backend/actuator/health" -TimeoutSec 3).status -eq 'UP') { return } } catch { }
    }
    throw "server $Backend did not become healthy"
}

$serverA = $null
$serverB = $null
$ollama = $null
$containerStarted = $false
$exitCode = 1
$checks = New-Object System.Collections.ArrayList
function Add-Check([string] $name, [bool] $ok, [string] $detail) {
    [void]$checks.Add([pscustomobject]@{ check = $name; ok = $ok; detail = $detail })
    Write-Host ("  {0} {1}: {2}" -f $(if ($ok) { 'PASS' } else { 'FAIL' }), $name, $detail)
}

try {
    # --- 1. throwaway database ---------------------------------------------------------------
    $existing = @(docker ps -a --filter "name=^/$Container$" --format '{{.Names}}')
    if ($existing.Count -gt 0) { throw "Container $Container already exists; remove it first (docker rm -f $Container)." }
    $dbPassword = [guid]::NewGuid().ToString('N')
    docker run -d --name $Container -e POSTGRES_DB=securedoc -e POSTGRES_USER=securedoc `
        -e "POSTGRES_PASSWORD=$dbPassword" -p "127.0.0.1:${DbPort}:5432" postgres:16-alpine | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "docker run failed" }
    $containerStarted = $true
    $deadline = (Get-Date).AddSeconds(60)
    do { Start-Sleep -Seconds 1; docker exec $Container pg_isready -U securedoc -d securedoc 2>$null | Out-Null }
    while ($LASTEXITCODE -ne 0 -and (Get-Date) -lt $deadline)
    Start-Sleep -Seconds 2
    . (Join-Path $repoRoot 'scripts\lib\LexaVaultTools.ps1')
    Initialize-LexaVaultDatabase -Jar $JarPath -DbUrl "jdbc:postgresql://localhost:${DbPort}/securedoc" -DbUser 'securedoc' `
        -DbPassword $dbPassword -StateDir (Join-Path $runDir 'install')

    # --- 2. shared processing storage: switched after server A's first start (step 4) creates it ---

    # --- 3. mock AI ----------------------------------------------------------------------------
    $ollama = Start-Process -FilePath 'powershell.exe' -PassThru -WindowStyle Hidden `
        -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
            (Join-Path $repoRoot 'test-tools\no-redacted-values\Start-FakeValueOllama.ps1'),
            '-Port', $OllamaPort, '-Model', $Model, '-DelayMs', $AiDelayMs) `
        -RedirectStandardOutput (Join-Path $runDir 'mock-ollama.out.log') -RedirectStandardError (Join-Path $runDir 'mock-ollama.err.log')
    $deadline = (Get-Date).AddSeconds(30)
    $up = $false
    while (-not $up -and (Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 500
        try { Invoke-RestMethod -Uri "http://127.0.0.1:$OllamaPort/api/tags" -TimeoutSec 2 | Out-Null; $up = $true } catch { }
    }
    if (-not $up) { throw "mock Ollama did not start" }

    # --- test PDFs -------------------------------------------------------------------------------
    $m2 = Join-Path $env:USERPROFILE '.m2\repository'
    $cp = @(
        "$m2\org\apache\pdfbox\pdfbox\3.0.2\pdfbox-3.0.2.jar",
        "$m2\org\apache\pdfbox\fontbox\3.0.2\fontbox-3.0.2.jar",
        "$m2\org\apache\pdfbox\pdfbox-io\3.0.2\pdfbox-io-3.0.2.jar",
        (Get-ChildItem "$m2\commons-logging\commons-logging" -Recurse -Filter 'commons-logging-*.jar' | Select-Object -First 1).FullName
    ) -join ';'
    $typed = Join-Path $runDir 'typed.pdf'
    $scanned = Join-Path $runDir 'scanned.pdf'
    & java -cp $cp (Join-Path $here 'MakeCasePdf.java') $typed typed 2
    & java -cp $cp (Join-Path $here 'MakeCasePdf.java') $scanned scanned 2
    if (-not (Test-Path $typed) -or -not (Test-Path $scanned)) { throw "test PDFs were not written" }

    # --- 4. two servers ---------------------------------------------------------------------------
    $backendA = "http://localhost:$PortA"
    $backendB = "http://localhost:$PortB"
    $serverA = Start-Server 'a' $PortA $dbPassword
    Wait-Healthy $serverA $backendA
    # Server A's first start created the entity's processing storage (LOCAL_FILESYSTEM, the run folder). Two servers
    # need SHARED_FILESYSTEM (LexaVault refuses local storage with several active nodes): the same folder, as a share.
    $providers = Invoke-Sql "select count(*) from processing_storage_providers"
    if ($providers -eq '0') { throw "no processing storage provider after server A's start" }
    Invoke-Sql "update processing_storage_providers set provider_type = 'SHARED_FILESYSTEM'" | Out-Null
    Write-Host "Processing storage switched to SHARED_FILESYSTEM ($providers provider(s), folder $shared)"
    $serverB = Start-Server 'b' $PortB $dbPassword
    Wait-Healthy $serverB $backendB
    Write-Host "Server A up on $backendA (pid $($serverA.Id)); server B up on $backendB (pid $($serverB.Id))"
    Start-Sleep -Seconds 5  # both servers' storage checks see two active nodes

    # --- 5. cases ---------------------------------------------------------------------------------
    $packageIds = New-Object System.Collections.ArrayList
    $suffix = Get-Random -Minimum 100000 -Maximum 999999
    for ($i = 1; $i -le $Cases; $i++) {
        $backend = if ($i % 2 -eq 1) { $backendA } else { $backendB }
        $created = Invoke-Api $backend POST '/api/v1/packages' @{
            packageType = 'MANUAL_UPLOAD'
            # The same lengths as the task 0015 check (12 and 11 digits), unique per case.
            businessIdentifiers = @{ contract_number = ('8848{0:D2}{1}' -f $i, $suffix); member_number = ('948{0:D2}{1}' -f $i, $suffix)
                service_entity = '001' }
            metadata = @{ source = 'task-0048-two-servers' } }
        $id = $created.packageId
        Send-Document $backend $id $typed 'typed-1.pdf'
        Send-Document $backend $id $scanned 'scanned.pdf'
        Send-Document $backend $id $typed 'typed-2.pdf'
        Invoke-Api $backend POST "/api/v1/packages/$id/submit" | Out-Null
        [void]$packageIds.Add($id)
        Start-Sleep -Milliseconds 700
    }
    Write-Host "Submitted $Cases cases ($($Cases * 3) documents)"

    # --- 6. kill server A while it holds Text, Find and Redact claims ------------------------------
    $killed = $null
    $deadline = (Get-Date).AddSeconds(300)
    while ((Get-Date) -lt $deadline) {
        $held = Invoke-Sql "select string_agg(distinct stage, ',' order by stage) from pipeline_jobs where status = 'RUNNING' and claim_owner like 'node-a/%' and stage in ('TEXT','FIND','REDACT')"
        if ($held -eq 'FIND,REDACT,TEXT') {
            $rows = Invoke-Sql "select id || '|' || stage || '|' || claim_token from pipeline_jobs where status = 'RUNNING' and claim_owner like 'node-a/%' and stage in ('TEXT','FIND','REDACT')"
            Stop-Process -Id $serverA.Id -Force
            $killed = @($rows -split "`n" | Where-Object { $_ } | ForEach-Object {
                $p = $_.Split('|'); [pscustomobject]@{ id = $p[0]; stage = $p[1]; token = [long]$p[2] } })
            break
        }
        Start-Sleep -Milliseconds 200
    }
    if (-not $killed) { throw "server A never held a Text, Find and Redact job at the same time (no kill done)" }
    $killedAt = Get-Date
    Write-Host "Killed server A (pid $($serverA.Id)) holding $($killed.Count) claims: $(($killed | ForEach-Object { $_.stage }) -join ', ')"

    # --- 7. server B finishes everything -----------------------------------------------------------
    $ids = ($packageIds | ForEach-Object { "'$_'" }) -join ','
    $deadline = (Get-Date).AddSeconds($FinishTimeoutSeconds)
    $done = 0
    do {
        Start-Sleep -Seconds 3
        $done = [int](Invoke-Sql "select count(*) from document_packages where id in ($ids) and status = 'COMPLETED'")
        $stopped = [int](Invoke-Sql "select count(*) from document_packages where id in ($ids) and status in ('FAILED','CANCELLED','PENDING_REVIEW','IN_REVIEW')")
    } while ($done + $stopped -lt $Cases -and (Get-Date) -lt $deadline)
    $finishedAfter = [int]((Get-Date) - $killedAt).TotalSeconds
    Write-Host "Cases exported: $done of $Cases ($finishedAfter s after the kill)"

    # --- 8. late saves from the killed server --------------------------------------------------------
    $lateSaved = 0
    foreach ($job in $killed) {
        # The workers' fenced save (JdbcPipelineJobRepository.FINISH_SQL's condition), counted by its returned rows.
        $n = Invoke-Sql ("with u as (update pipeline_jobs set status = 'DONE', claim_owner = null, claim_until = null," +
            " updated_at = clock_timestamp() where id = '$($job.id)' and claim_token = $($job.token)" +
            " and status = 'RUNNING' and claim_until > clock_timestamp() returning id) select count(*) from u")
        $lateSaved += [int]$n
    }

    # --- 9. checks ---------------------------------------------------------------------------------------
    Write-Host ""
    Write-Host "Checks:"
    $docIds = "select id from documents where package_id in ($ids)"
    $documentsN = [int](Invoke-Sql "select count(*) from documents where package_id in ($ids)")
    Add-Check 'every case exported' ($done -eq $Cases) "$done of $Cases COMPLETED"
    Add-Check 'server A was killed holding Text, Find and Redact claims' ($killed.Count -ge 3) (($killed | ForEach-Object { "$($_.stage)#$($_.token)" }) -join ', ')
    Add-Check "late saves with A's old claim numbers refused" ($lateSaved -eq 0) "$lateSaved of $($killed.Count) changed a row"
    $takenOver = Invoke-Sql ("select count(*) from pipeline_jobs where id in (" + (($killed | ForEach-Object { "'$($_.id)'" }) -join ',') +
        ") and status = 'DONE' and claim_expiries >= 1 and attempts >= 1")
    Add-Check "A's jobs finished by B after their claims ran out (one attempt each)" ([int]$takenOver -eq $killed.Count) "$takenOver of $($killed.Count)"
    foreach ($stage in 'TEXT', 'FIND', 'REDACT') {
        $bad = Invoke-Sql "select count(*) from (select document_id from pipeline_jobs where package_id in ($ids) and stage = '$stage' group by document_id having count(*) <> 1 or bool_or(status <> 'DONE')) x"
        $n = Invoke-Sql "select count(*) from pipeline_jobs where package_id in ($ids) and stage = '$stage'"
        Add-Check "one $stage job per document, done" ($bad -eq '0' -and [int]$n -eq $documentsN) "$n jobs for $documentsN documents; $bad documents not exactly one done job"
    }
    $dupFindings = Invoke-Sql "select count(*) from (select document_id, entity_type, page_number, start_offset, end_offset from findings where document_id in ($docIds) group by 1,2,3,4,5 having count(*) > 1) d"
    $findingsN = Invoke-Sql "select count(*) from findings where document_id in ($docIds)"
    Add-Check 'no duplicate findings' ($dupFindings -eq '0' -and [int]$findingsN -gt 0) "$findingsN findings, $dupFindings duplicated places"
    $redacted = Invoke-Sql "select count(*) from (select document_id from artifacts where document_id in ($docIds) and artifact_type = 'REDACTED' group by document_id having count(*) = 1) x"
    $redactedRows = Invoke-Sql "select count(*) from artifacts where document_id in ($docIds) and artifact_type = 'REDACTED'"
    Add-Check 'one redacted copy per document' ([int]$redacted -eq $documentsN -and [int]$redactedRows -eq $documentsN) "$redactedRows redacted copies for $documentsN documents"
    $textRows = Invoke-Sql "select count(*) from artifacts where document_id in ($docIds) and artifact_type = 'EXTRACTED_TEXT'"
    Add-Check 'one text read stored per document' ([int]$textRows -eq $documentsN) "$textRows for $documentsN documents"
    foreach ($action in 'DOCUMENT_EXTRACTION_COMPLETED', 'DOCUMENT_DETECTION_COMPLETED', 'DOCUMENT_REDACTION_VERIFIED') {
        $bad = Invoke-Sql "select count(*) from (select d.id from documents d left join audit_records a on a.resource_id = d.id::text and a.action = '$action' where d.package_id in ($ids) group by d.id having count(a.id) <> 1) x"
        Add-Check "audit: one $action per document" ($bad -eq '0') "$bad documents not exactly once"
    }
    $exportBad = Invoke-Sql "select count(*) from (select p.id from document_packages p left join pipeline_jobs j on j.package_id = p.id and j.stage = 'EXPORT' where p.id in ($ids) group by p.id having count(j.id) <> 1 or bool_or(j.status <> 'DONE')) x"
    Add-Check 'one EXPORT job per case, done' ($exportBad -eq '0') "$exportBad cases not exactly one done export"
    $deliveryBad = Invoke-Sql "select count(*) from (select p.id from document_packages p left join delivery_submissions s on s.package_id = p.id where p.id in ($ids) group by p.id having count(s.id) > 1) x"
    Add-Check 'no case delivered twice' ($deliveryBad -eq '0') "$deliveryBad cases with more than one delivery"
    $doneAudits = Invoke-Sql "select count(*) from audit_records where action = 'PIPELINE_JOB_STATUS_CHANGED' and detail like '%from=RUNNING, to=DONE%'"
    Add-Check 'job status changes audited' ([int]$doneAudits -eq ($documentsN * 3)) "$doneAudits RUNNING to DONE records for $($documentsN * 3) jobs (a refused save leaves none)"
    # The fake values never reach a table (as the task 0015 check, on the whole database).
    $inside = '/tmp/two-servers.sql'
    docker exec $Container pg_dump -U securedoc -d securedoc -f $inside | Out-Null
    $dump = Join-Path $runDir 'dump.sql'
    docker cp "${Container}:$inside" $dump | Out-Null
    $dumpText = [System.IO.File]::ReadAllText($dump)
    $valueHits = 0
    foreach ($needle in '412-55-7531', '4000 0566 5566 5556', '03/14/1962', '412557531', '4000056655665556') {
        $valueHits += ([regex]::Matches($dumpText, [regex]::Escape($needle))).Count
    }
    Add-Check 'no redacted value saved (database dump)' ($valueHits -eq 0) "$valueHits matches"

    $failed = @($checks | Where-Object { -not $_.ok }).Count
    [pscustomobject]@{ cases = $Cases; documents = $documentsN; killedClaims = $killed; secondsToFinishAfterKill = $finishedAfter
        checks = $checks } | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 (Join-Path $runDir 'summary.json')
    Write-Host ""
    if ($failed -eq 0) { Write-Host "PASS ($($checks.Count) checks)"; $exitCode = 0 } else { Write-Host "FAIL ($failed of $($checks.Count) checks)" }
}
finally {
    foreach ($p in $serverA, $serverB, $ollama) {
        if ($p -and -not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
    }
    if ($containerStarted) {
        docker rm -f $Container | Out-Null
        Write-Host "Removed throwaway container $Container"
    }
    foreach ($name in 'SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME', 'SERVER_PORT', 'SECUREDOC_NODE_ID',
            'SECUREDOC_AI_PROVIDER', 'SECUREDOC_AI_OLLAMA_BASE_URL', 'SECUREDOC_AI_MODEL', 'SECUREDOC_AI_OLLAMA_PRELOAD_ENABLED',
            'SECUREDOC_WATCHED_FOLDER_ENABLED', 'SECUREDOC_WATCHED_FOLDER_SCHEDULING_ENABLED', 'SECUREDOC_LOGGING_CONFIG',
            'SECUREDOC_LOG_DIR', 'SECUREDOC_PRODUCT_TELEMETRY_OUTPUT_PATH', 'SECUREDOC_DELIVERY_LOCAL_OUTPUT_PATH',
            'SECUREDOC_PROCESSING_PIPELINE_MODE', 'SECUREDOC_PROCESSING_EXPORT_MODE', 'SECUREDOC_STAGE_JOBS_CLAIM_LEASE_MS',
            'SECUREDOC_STAGE_JOBS_POLL_INTERVAL_MS', 'SECUREDOC_EXPORT_JOBS_POLL_INTERVAL_MS') {
        Remove-Item "Env:$name" -ErrorAction SilentlyContinue
    }
}
exit $exitCode
