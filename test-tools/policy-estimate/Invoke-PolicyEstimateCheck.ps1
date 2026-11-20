<#
.SYNOPSIS
    Task 0019 B3/B4: the policy estimate on a throwaway Postgres.

.DESCRIPTION
    1. Starts a throwaway Postgres container (lexavault-0019-pg) and the mock Ollama from task 0015.
    2. Starts the backend jar against it (LOCAL profile), saves the policy UNCERTAIN_ONLY at 0.99 (so the mock's 0.97 findings wait for a person).
    3. Sends real cases through the normal intake and processing path: PDFs holding the mock's fake values (found and
       located), PDFs with other text (the mock's values are not on the page, so nothing is kept), and blank PDFs (no
       findings). Nobody reviews them, so what processing left behind is the real outcome under the saved policy.
    4. Checks that the estimate for the saved policy equals those real outcomes (cases waiting for review, findings
       still DETECTED), and the REVIEW_ALL and 0.80 sides against independent SQL; validation; who may read it; that
       reading it writes no audit record and returns no found value or file name.
    5. Seeds a second entity by SQL with 2,000 cases and 50,000 findings in the window (plus older cases outside it),
       checks the first entity's estimate does not change, times the endpoint for the second entity, and saves the
       query plan (EXPLAIN ANALYZE of the adapter's own SQL, read from its source file).
    Removes only its own container and processes at the end. Never touches securedoc-postgres or any container
    named in CLAUDE.md. The container password is generated per run and never printed.
#>
[CmdletBinding()]
param(
    [string] $JarPath,
    [string] $RunRoot = (Join-Path $env:TEMP 'lexavault-0019'),
    [int] $DbPort = 55419,
    [int] $AppPort = 18019,
    [int] $OllamaPort = 11519,
    [string] $Model = 'qwen3.5:9b',
    [int] $FakeCases = 9,
    [int] $PlainCases = 6,
    [int] $BlankCases = 6,
    [int] $PerfCases = 2000,
    [int] $PerfFindings = 50000,
    [int] $ProcessingTimeoutSeconds = 900,
    # Task 0020 A4: leave the container, mock Ollama and backend running for the UI check; stop them with
    # Stop-PolicyEstimateEnvironment.ps1 (the process ids are written to keep-running.json in -RunRoot).
    [switch] $KeepRunning
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$here = $PSScriptRoot
$repoRoot = (Resolve-Path (Join-Path $here '..\..')).Path
if (-not $JarPath) { $JarPath = Join-Path $repoRoot 'backend\securedoc-api\target\securedoc-api-0.1.0-SNAPSHOT.jar' }
if (-not (Test-Path -LiteralPath $JarPath)) { throw "Backend jar not found: $JarPath (build it first)." }
$adapterSource = Join-Path $repoRoot 'backend\securedoc-infrastructure\src\main\java\com\securedoc\platform\infrastructure\business\JpaPolicyEstimateReadAdapter.java'

$Container = 'lexavault-0019-pg'
$Protected = @('securedoc-postgres', 'sdv-postgres', 'sdv-vault', 'lexa-keycloak', 'lexa-keycloak-db')
if ($Protected -contains $Container) { throw "Refusing to use a protected container name." }

$tenantId = '00000000-0000-0000-0000-000000000001'
$perfTenant = '00000000-0000-0000-0000-0000000019ff'
$actorId = '00000000-0000-0000-0000-000000000099'
$backend = "http://localhost:$AppPort"
$fakeValues = @('412-55-7531', '412557531', '4000 0566 5566 5556', '4000056655665556', '03/14/1962')

$runDir = Join-Path $RunRoot (Get-Date -Format 'yyyyMMdd-HHmmss')
$logDir = Join-Path $runDir 'logs'
New-Item -ItemType Directory -Force -Path $runDir, $logDir | Out-Null
Write-Host "Run folder: $runDir"

$checks = New-Object System.Collections.ArrayList
function Check([string] $name, [bool] $ok, [string] $evidence = '') {
    [void]$checks.Add([pscustomobject]@{ name = $name; ok = $ok; evidence = $evidence })
    Write-Host ("  [{0}] {1} {2}" -f $(if ($ok) { 'PASS' } else { 'FAIL' }), $name, $evidence)
}

function Sql([string] $query) {
    $out = $query | docker exec -i $Container psql -U securedoc -d securedoc -v ON_ERROR_STOP=1 -tA
    if ($LASTEXITCODE -ne 0) { throw "psql failed: $query" }
    return ((($out | Out-String) -replace "`r", "").Trim())
}

Add-Type -AssemblyName System.Net.Http
$http = New-Object System.Net.Http.HttpClient
$http.Timeout = [TimeSpan]::FromMinutes(2)

function Send([string] $method, [string] $path, [string] $roles, [string] $tenant = $tenantId, [string] $json = $null) {
    $req = New-Object System.Net.Http.HttpRequestMessage((New-Object System.Net.Http.HttpMethod($method)), "$backend$path")
    $req.Headers.Add('X-Tenant-Id', $tenant)
    $req.Headers.Add('X-Actor-Id', $actorId)
    $req.Headers.Add('X-Actor-Roles', $roles)
    $req.Headers.Add('X-Correlation-Id', [guid]::NewGuid().ToString())
    if ($json) { $req.Content = New-Object System.Net.Http.StringContent($json, [System.Text.Encoding]::UTF8, 'application/json') }
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    $res = $http.SendAsync($req).GetAwaiter().GetResult()
    $body = $res.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $watch.Stop()
    return [pscustomobject]@{ status = [int]$res.StatusCode; body = $body; ms = $watch.Elapsed.TotalMilliseconds }
}

function Api([string] $method, [string] $path, $bodyObject = $null, [string] $roles = 'DOCUMENT_PROCESSOR,BUSINESS_ADMIN') {
    $json = if ($null -ne $bodyObject) { $bodyObject | ConvertTo-Json -Depth 8 -Compress } else { $null }
    $r = Send $method $path $roles $tenantId $json
    if ($r.status -lt 200 -or $r.status -ge 300) { throw "$method $path -> HTTP $($r.status)" }
    if ($r.body) { return ($r.body | ConvertFrom-Json) }
    return $null
}

function Estimate([string] $query, [string] $tenant = $tenantId, [string] $roles = 'BUSINESS_ADMIN') {
    return Send 'GET' "/api/v1/business-admin/sensitive-data-policy/estimate?$query" $roles $tenant
}

function Upload([string] $packageId, [string] $pdf, [string] $name) {
    $client = [System.Net.Http.HttpClient]::new()
    try {
        $client.DefaultRequestHeaders.Add('X-Tenant-Id', $tenantId)
        $client.DefaultRequestHeaders.Add('X-Actor-Id', $actorId)
        $client.DefaultRequestHeaders.Add('X-Actor-Roles', 'DOCUMENT_PROCESSOR')
        $content = [System.Net.Http.MultipartFormDataContent]::new()
        $file = [System.Net.Http.ByteArrayContent]::new([System.IO.File]::ReadAllBytes($pdf))
        $file.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new('application/pdf')
        $content.Add($file, 'file', $name)
        $response = $client.PostAsync("$backend/api/v1/packages/$packageId/documents", $content).GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) { throw "upload failed: HTTP $([int]$response.StatusCode)" }
    } finally { $client.Dispose() }
}

$appProcess = $null
$ollamaProcess = $null
$containerStarted = $false
$exitCode = 1
$summary = [ordered]@{}
try {
    # --- 1. throwaway database and mock Ollama ----------------------------------------------
    $existing = @(docker ps -a --filter "name=^/$Container$" --format '{{.Names}}')
    if ($existing.Count -gt 0) { throw "Container $Container already exists; remove it first (docker rm -f $Container)." }
    $dbPassword = [guid]::NewGuid().ToString('N')
    docker run -d --name $Container -e POSTGRES_DB=securedoc -e POSTGRES_USER=securedoc `
        -e "POSTGRES_PASSWORD=$dbPassword" -p "127.0.0.1:${DbPort}:5432" postgres:16-alpine | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "docker run failed" }
    $containerStarted = $true
    $deadline = (Get-Date).AddSeconds(60)
    do {
        Start-Sleep -Seconds 1
        docker exec $Container pg_isready -U securedoc -d securedoc 2>$null | Out-Null
    } while ($LASTEXITCODE -ne 0 -and (Get-Date) -lt $deadline)
    if ($LASTEXITCODE -ne 0) { throw "throwaway Postgres did not become ready" }
    Start-Sleep -Seconds 2
    Write-Host "Throwaway Postgres ready on 127.0.0.1:$DbPort"

    $ollamaProcess = Start-Process -FilePath 'powershell.exe' -PassThru -WindowStyle Hidden `
        -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
            (Join-Path $repoRoot 'test-tools\no-redacted-values\Start-FakeValueOllama.ps1'), '-Port', $OllamaPort, '-Model', $Model) `
        -RedirectStandardOutput (Join-Path $logDir 'mock-ollama.out.log') -RedirectStandardError (Join-Path $logDir 'mock-ollama.err.log')
    $deadline = (Get-Date).AddSeconds(30)
    $ollamaUp = $false
    while (-not $ollamaUp -and (Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 500
        try { Invoke-RestMethod -Uri "http://127.0.0.1:$OllamaPort/api/tags" -TimeoutSec 2 | Out-Null; $ollamaUp = $true } catch { }
    }
    if (-not $ollamaUp) { throw "mock Ollama did not start" }

    # --- 2. test PDFs --------------------------------------------------------------------------
    $m2 = Join-Path $env:USERPROFILE '.m2\repository'
    $cp = @(
        "$m2\org\apache\pdfbox\pdfbox\3.0.2\pdfbox-3.0.2.jar",
        "$m2\org\apache\pdfbox\fontbox\3.0.2\fontbox-3.0.2.jar",
        "$m2\org\apache\pdfbox\pdfbox-io\3.0.2\pdfbox-io-3.0.2.jar",
        (Get-ChildItem "$m2\commons-logging\commons-logging" -Recurse -Filter 'commons-logging-*.jar' | Select-Object -First 1).FullName
    ) -join ';'
    $pdfs = @{ fake = (Join-Path $runDir 'fake-values.pdf'); plain = (Join-Path $runDir 'plain.pdf'); blank = (Join-Path $runDir 'blank.pdf') }
    & java -cp $cp (Join-Path $repoRoot 'test-tools\no-redacted-values\MakeFakeValuePdf.java') $pdfs.fake
    if ($LASTEXITCODE -ne 0) { throw "fake-value PDF was not written" }
    foreach ($kind in 'plain', 'blank') {
        & java -cp $cp (Join-Path $here 'MakeEstimatePdf.java') $kind $pdfs[$kind]
        if ($LASTEXITCODE -ne 0) { throw "$kind PDF was not written" }
    }

    # --- 3. backend ------------------------------------------------------------------------------
    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:$DbPort/securedoc"
    $env:SPRING_DATASOURCE_USERNAME = 'securedoc'
    $env:SPRING_DATASOURCE_PASSWORD = $dbPassword
    $env:SERVER_PORT = "$AppPort"
    $env:SECUREDOC_AI_PROVIDER = 'ollama'
    $env:SECUREDOC_AI_OLLAMA_BASE_URL = "http://localhost:$OllamaPort"
    $env:SECUREDOC_AI_MODEL = $Model
    $env:SECUREDOC_AI_OLLAMA_PRELOAD_ENABLED = 'false'
    $env:SECUREDOC_WATCHED_FOLDER_ENABLED = 'false'
    $env:SECUREDOC_WATCHED_FOLDER_SCHEDULING_ENABLED = 'false'
    $env:SECUREDOC_LOG_DIR = $logDir
    $env:SECUREDOC_PRODUCT_TELEMETRY_OUTPUT_PATH = (Join-Path $logDir 'product-telemetry.jsonl')
    $env:SECUREDOC_DELIVERY_LOCAL_OUTPUT_PATH = (Join-Path $runDir 'securedoc-output')
    try {
        $appProcess = Start-Process -FilePath 'java' -PassThru -WindowStyle Hidden -WorkingDirectory $runDir `
            -ArgumentList @('-jar', $JarPath, '--spring.profiles.active=local',
                '--securedoc.lifecycle.purge-scheduler-enabled=false',
                "--securedoc.storage.local.root-path=$(Join-Path $runDir 'securedoc-storage')") `
            -RedirectStandardOutput (Join-Path $logDir 'backend.out.log') -RedirectStandardError (Join-Path $logDir 'backend.err.log')
    } finally {
        Remove-Item Env:SPRING_DATASOURCE_PASSWORD -ErrorAction SilentlyContinue
    }
    $deadline = (Get-Date).AddSeconds(180)
    $appUp = $false
    while (-not $appUp -and (Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        if ($appProcess.HasExited) { throw "backend exited during startup (see $logDir\backend.out.log)" }
        try { $h = Invoke-RestMethod -Uri "$backend/actuator/health" -TimeoutSec 3; $appUp = ($h.status -eq 'UP') } catch { }
    }
    if (-not $appUp) { throw "backend did not become healthy" }
    Write-Host "Backend up on $backend"

    # --- 4. saved policy, and the estimate with no cases ------------------------------------------
    Api PUT '/api/v1/business-admin/sensitive-data-policy' @{
        enabledEntityTypes = @('CREDIT_SCORE', 'SSN', 'DATE_OF_BIRTH', 'CREDIT_CARD_NUMBER')
        autoRedactThreshold = 0.99; reviewMode = 'UNCERTAIN_ONLY'; maskSsnLastFour = $true } 'BUSINESS_ADMIN' | Out-Null
    $e0 = Estimate 'reviewMode=REVIEW_ALL'
    $j0 = $e0.body | ConvertFrom-Json
    Check 'under 20 cases: enoughData=false, the case count, no other counts' `
        ($e0.status -eq 200 -and $j0.enoughData -eq $false -and $j0.cases -eq 0 -and
         -not ($j0.current.PSObject.Properties.Name -contains 'casesToPerson') -and
         -not ($j0.proposed.PSObject.Properties.Name -contains 'findingsToPerson')) $e0.body

    # --- 5. real cases through the normal path -----------------------------------------------------
    $plan = @()
    for ($i = 0; $i -lt $FakeCases; $i++) { $plan += 'fake' }
    for ($i = 0; $i -lt $PlainCases; $i++) { $plan += 'plain' }
    for ($i = 0; $i -lt $BlankCases; $i++) { $plan += 'blank' }
    $cases = @()
    $n = 0
    foreach ($kind in $plan) {
        $n++
        $created = Api POST '/api/v1/packages' @{
            packageType = 'MANUAL_UPLOAD'
            businessIdentifiers = @{ contract_number = "880019$(100000 + $n * 1000 + (Get-Random -Maximum 999))"; member_number = "90019$(100000 + $n)"; service_entity = '001' }
            metadata = @{ source = "task-0019-$kind" } } 'DOCUMENT_PROCESSOR'
        Upload $created.packageId $pdfs[$kind] "$kind-$n.pdf"
        Api POST "/api/v1/packages/$($created.packageId)/submit" $null 'DOCUMENT_PROCESSOR' | Out-Null
        $cases += [pscustomobject]@{ id = $created.packageId; kind = $kind }
    }
    Write-Host "Submitted $($cases.Count) cases ($FakeCases fake-value, $PlainCases plain, $BlankCases blank); waiting for processing"
    $terminal = @('COMPLETED', 'PENDING_REVIEW', 'IN_REVIEW', 'PENDING_FINAL_APPROVAL', 'APPROVED', 'VAULTED', 'REJECTED', 'FAILED', 'CANCELLED')
    $deadline = (Get-Date).AddSeconds($ProcessingTimeoutSeconds)
    do {
        Start-Sleep -Seconds 3
        $open = @($cases | Where-Object { $terminal -notcontains (Api GET "/api/v1/packages/$($_.id)" $null 'DOCUMENT_PROCESSOR').status })
    } while ($open.Count -gt 0 -and (Get-Date) -lt $deadline)
    if ($open.Count -gt 0) { throw "$($open.Count) cases did not finish processing in time" }
    Start-Sleep -Seconds 3
    $byStatus = Sql "select coalesce(string_agg(status || '=' || n, ', ' order by status), '') from (select status, count(*) n from document_packages where tenant_id='$tenantId' group by status) s"
    Write-Host "Case outcomes: $byStatus"
    $kindOutcome = foreach ($kind in 'fake', 'plain', 'blank') {
        $ids = (@($cases | Where-Object { $_.kind -eq $kind } | ForEach-Object { "'$($_.id)'" }) -join ',')
        "$kind`: " + (Sql "select coalesce(string_agg(status || '=' || n, ', ' order by status), '') from (select status, count(*) n from document_packages where id in ($ids) group by status) s")
    }
    Write-Host "By kind: $($kindOutcome -join ' | ')"
    $summary.caseOutcomes = $byStatus
    $summary.byKind = $kindOutcome
    Check 'every real case finished without a technical failure' (-not ($byStatus -match 'FAILED')) $byStatus

    # Real outcomes under the saved policy (nobody reviewed anything).
    $realCases = [int](Sql "select count(*) from document_packages where tenant_id='$tenantId' and status in ('PENDING_REVIEW','IN_REVIEW')")
    $pausedAudit = [int](Sql "select count(distinct resource_id) from audit_records where tenant_id='$tenantId' and action='PACKAGE_PROCESSING_COMPLETED' and detail='Package processing paused: manual review required'")
    $realFindings = [int](Sql "select count(*) from findings f join documents d on d.id=f.document_id where d.tenant_id='$tenantId' and f.status='DETECTED' and f.detector_name<>'MANUAL_FALLBACK'")
    $allFindings = [int](Sql "select count(*) from findings f join documents d on d.id=f.document_id where d.tenant_id='$tenantId' and f.detector_name<>'MANUAL_FALLBACK'")
    $casesWithFindings = [int](Sql "select count(distinct d.package_id) from findings f join documents d on d.id=f.document_id where d.tenant_id='$tenantId' and f.detector_name<>'MANUAL_FALLBACK'")
    $unlocated = "(f.bbox_left is null or f.bbox_top is null or f.bbox_width is null or f.bbox_height is null)"
    $find80 = [int](Sql "select count(*) from findings f join documents d on d.id=f.document_id where d.tenant_id='$tenantId' and f.detector_name<>'MANUAL_FALLBACK' and ($unlocated or f.confidence < 0.80)")
    $cases80 = [int](Sql "select count(distinct d.package_id) from findings f join documents d on d.id=f.document_id where d.tenant_id='$tenantId' and f.detector_name<>'MANUAL_FALLBACK' and ($unlocated or f.confidence < 0.80)")
    $findingRows = Sql "select coalesce(string_agg(x, '; '), '') from (select f.status || ' conf=' || f.confidence || ' located=' || (not $unlocated) || ' x' || count(*) as x from findings f join documents d on d.id=f.document_id where d.tenant_id='$tenantId' group by f.status, f.confidence, (not $unlocated) order by 1) s"
    Write-Host "Stored findings: $findingRows"
    $summary.findings = $findingRows
    $summary.real = [ordered]@{ casesToPerson = $realCases; pausedAuditCases = $pausedAudit; findingsToPerson = $realFindings }
    Check 'the real outcome has both kinds of case (some to a person, some straight through)' ($realCases -gt 0 -and $realCases -lt $cases.Count) "toPerson=$realCases of $($cases.Count)"
    Check 'the paused-for-review audit records agree with the case statuses' ($pausedAudit -eq $realCases) "audit=$pausedAudit, status=$realCases"

    $before = [int](Sql "select count(*) from audit_records where tenant_id='$tenantId'")
    $e1 = Estimate 'reviewMode=UNCERTAIN_ONLY&threshold=0.99'
    $j1 = $e1.body | ConvertFrom-Json
    $summary.estimateSaved = $j1
    Check 'B4: the estimate for the saved policy equals the real outcomes of the processed cases' `
        ($j1.enoughData -and $j1.cases -eq $cases.Count -and $j1.current.casesToPerson -eq $realCases -and $j1.current.findingsToPerson -eq $realFindings) `
        "estimate cases=$($j1.current.casesToPerson) findings=$($j1.current.findingsToPerson); real cases=$realCases findings=$realFindings"
    Check 'the proposed side, with the same values, equals the current side' `
        ($j1.proposed.casesToPerson -eq $j1.current.casesToPerson -and $j1.proposed.findingsToPerson -eq $j1.current.findingsToPerson -and $j1.current.preset -eq 'CUSTOM')

    $e2 = Estimate 'reviewMode=REVIEW_ALL&threshold=abc'
    $j2 = $e2.body | ConvertFrom-Json
    $summary.estimateStrict = $j2
    Check 'REVIEW_ALL: every case with a finding and every finding; cases with no findings still go straight through; threshold ignored' `
        ($e2.status -eq 200 -and $j2.proposed.casesToPerson -eq $casesWithFindings -and $j2.proposed.findingsToPerson -eq $allFindings -and
         $j2.proposed.preset -eq 'STRICT' -and $null -eq $j2.proposed.threshold -and $casesWithFindings -lt $cases.Count) `
        "cases=$($j2.proposed.casesToPerson)/$casesWithFindings of $($cases.Count), findings=$($j2.proposed.findingsToPerson)/$allFindings"
    $e3 = Estimate 'reviewMode=UNCERTAIN_ONLY&threshold=0.80'
    $j3 = $e3.body | ConvertFrom-Json
    Check 'UNCERTAIN_ONLY at 0.80 matches independent SQL (unlocated or below 0.80)' `
        ($j3.proposed.casesToPerson -eq $cases80 -and $j3.proposed.findingsToPerson -eq $find80 -and $j3.proposed.preset -eq 'BALANCED') `
        "cases=$($j3.proposed.casesToPerson)/$cases80 findings=$($j3.proposed.findingsToPerson)/$find80"
    $after = [int](Sql "select count(*) from audit_records where tenant_id='$tenantId'")
    Check 'reading the estimate writes no audit record' ($after -eq $before) "before=$before after=$after"
    $bodies = "$($e1.body)$($e2.body)$($e3.body)"
    $hits = @($fakeValues + @('fake-', 'plain-', 'blank-', '.pdf', '880019') | Where-Object { $bodies.Contains($_) })
    Check 'the response holds counts only: no found value, file name or business identifier' ($hits.Count -eq 0) ($hits -join ', ')

    # Validation and who may read it.
    foreach ($bad in @('threshold=0.8', 'reviewMode=&threshold=0.8', 'reviewMode=AUTO&threshold=0.8', 'reviewMode=UNCERTAIN_ONLY',
                       'reviewMode=UNCERTAIN_ONLY&threshold=0.49', 'reviewMode=UNCERTAIN_ONLY&threshold=1', 'reviewMode=UNCERTAIN_ONLY&threshold=x')) {
        $r = Estimate $bad
        Check "validation: $bad is refused with 400" ($r.status -eq 400) "status=$($r.status)"
    }
    foreach ($role in 'DOCUMENT_PROCESSOR', 'DOCUMENT_SUPERVISOR', 'REPORT_VIEWER', 'TENANT_IT_ADMIN', 'PLATFORM_OPERATIONS_ADMIN') {
        $r = Estimate 'reviewMode=REVIEW_ALL' $tenantId $role
        Check "capability: $role is refused (403)" ($r.status -eq 403) "status=$($r.status)"
    }

    # --- 6. a second entity with 2,000 cases and 50,000 findings ---------------------------------------
    $perfDocs = $PerfCases * 2
    $seed = @"
create temp table tt as select * from tenants where id='$tenantId';
update tt set id='$perfTenant', slug='perf-0019', name='Perf entity 0019';
insert into tenants select * from tt;
create temp table tpl_p as select * from document_packages where tenant_id='$tenantId' limit 1;
create temp table tpl_d as select * from documents where tenant_id='$tenantId' limit 1;
create temp table tpl_f as select * from findings where tenant_id='$tenantId' and bbox_left is not null limit 1;
insert into document_packages
select (jsonb_populate_record(null::document_packages, to_jsonb(t) || jsonb_build_object(
    'id', gen_random_uuid(), 'tenant_id', '$perfTenant', 'business_record_id', null, 'intake_fingerprint', null,
    'source_reference', null, 'status', 'COMPLETED',
    'created_at', now() - (case when g <= $PerfCases then random() * 29.9 else 31 + random() * 30 end) * interval '1 day',
    'updated_at', now()))).*
  from tpl_p t, generate_series(1, $PerfCases + 200) g;
insert into documents
select (jsonb_populate_record(null::documents, to_jsonb(t) || jsonb_build_object(
    'id', gen_random_uuid(), 'tenant_id', '$perfTenant', 'package_id', p.id, 'checksum_value', md5(p.id::text || k),
    'file_name', 'seed.pdf'))).*
  from tpl_d t, document_packages p, generate_series(1, 2) k where p.tenant_id='$perfTenant';
with docs as (
    select d.id, row_number() over (order by d.id) - 1 as rn
      from documents d join document_packages p on p.id=d.package_id
     where d.tenant_id='$perfTenant' and p.created_at > now() - interval '30 days'),
old as (
    select d.id, row_number() over (order by d.id) - 1 as rn
      from documents d join document_packages p on p.id=d.package_id
     where d.tenant_id='$perfTenant' and p.created_at <= now() - interval '30 days'),
pick as (
    select g, (g * 7919) % $perfDocs as rn, random() < 0.10 as unloc, random() < 0.02 as manual,
           round((0.40 + random() * 0.60)::numeric, 2)::float8 as conf
      from generate_series(0, $PerfFindings - 1) g)
insert into findings
select (jsonb_populate_record(null::findings, to_jsonb(t) || jsonb_build_object(
    'id', gen_random_uuid(), 'tenant_id', '$perfTenant', 'document_id', docs.id, 'confidence', pick.conf,
    'status', 'DETECTED', 'detector_name', case when pick.manual then 'MANUAL_FALLBACK' else 'seed' end,
    'bbox_left', case when pick.unloc then null else 0.1 end, 'bbox_top', case when pick.unloc then null else 0.1 end,
    'bbox_width', case when pick.unloc then null else 0.2 end, 'bbox_height', case when pick.unloc then null else 0.05 end))).*
  from tpl_f t, pick join docs on docs.rn = pick.rn
union all
select (jsonb_populate_record(null::findings, to_jsonb(t) || jsonb_build_object(
    'id', gen_random_uuid(), 'tenant_id', '$perfTenant', 'document_id', old.id, 'confidence', 0.5, 'status', 'DETECTED',
    'detector_name', 'seed', 'bbox_left', null))).*
  from tpl_f t, old;
analyze document_packages; analyze documents; analyze findings;
"@
    Sql $seed | Out-Null
    $perfInWindow = [int](Sql "select count(*) from document_packages where tenant_id='$perfTenant' and created_at > now() - interval '30 days'")
    $perfFindingsInWindow = [int](Sql "select count(*) from findings f join documents d on d.id=f.document_id join document_packages p on p.id=d.package_id where p.tenant_id='$perfTenant' and p.created_at > now() - interval '30 days'")
    $perfOld = [int](Sql "select count(*) from document_packages where tenant_id='$perfTenant' and created_at <= now() - interval '30 days'")
    $totals = Sql "select (select count(*) from document_packages) || ' cases, ' || (select count(*) from documents) || ' documents, ' || (select count(*) from findings) || ' findings in the database'"
    Write-Host "Seeded entity $perfTenant`: $perfInWindow cases and $perfFindingsInWindow findings in the window, $perfOld older cases; $totals"
    $summary.perfSeed = [ordered]@{ casesInWindow = $perfInWindow; findingsInWindow = $perfFindingsInWindow; olderCases = $perfOld; totals = $totals }

    $e4 = Estimate 'reviewMode=UNCERTAIN_ONLY&threshold=0.99'
    Check 'other entities never count: the first entity''s estimate is unchanged after seeding the second' ($e4.body -eq $e1.body) $e4.body

    $perfQuery = 'reviewMode=REVIEW_ALL'
    $warm = Estimate $perfQuery $perfTenant
    $jp = $warm.body | ConvertFrom-Json
    $summary.estimatePerf = $jp
    $expectStrictFindings = [int](Sql "select count(*) from findings f join documents d on d.id=f.document_id join document_packages p on p.id=d.package_id where p.tenant_id='$perfTenant' and p.created_at > now() - interval '30 days' and f.detector_name<>'MANUAL_FALLBACK'")
    $expectCurrentCases = [int](Sql "select count(distinct p.id) from findings f join documents d on d.id=f.document_id join document_packages p on p.id=d.package_id where p.tenant_id='$perfTenant' and p.created_at > now() - interval '30 days' and f.detector_name<>'MANUAL_FALLBACK' and ($unlocated or f.confidence < 0.80)")
    Check 'second entity: only its own cases in the window count (2,000; the older ones and the first entity''s are left out)' `
        ($warm.status -eq 200 -and $jp.cases -eq $PerfCases) "cases=$($jp.cases)"
    Check 'second entity: Strict findings = every non-manual finding in the window; Balanced cases match independent SQL' `
        ($jp.proposed.findingsToPerson -eq $expectStrictFindings -and $jp.current.casesToPerson -eq $expectCurrentCases) `
        "strict findings=$($jp.proposed.findingsToPerson)/$expectStrictFindings, balanced cases=$($jp.current.casesToPerson)/$expectCurrentCases"
    $times = @()
    foreach ($q in @('reviewMode=REVIEW_ALL', 'reviewMode=UNCERTAIN_ONLY&threshold=0.80', 'reviewMode=UNCERTAIN_ONLY&threshold=0.70',
                     'reviewMode=UNCERTAIN_ONLY&threshold=0.95', 'reviewMode=REVIEW_ALL')) {
        $r = Estimate $q $perfTenant
        if ($r.status -ne 200) { throw "perf estimate failed: HTTP $($r.status)" }
        $times += [math]::Round($r.ms, 1)
    }
    $max = ($times | Measure-Object -Maximum).Maximum
    $summary.timingsMs = $times
    Check 'B3: the estimate answers in under 1 second for 2,000 cases and 50,000 findings (5 timed calls)' ($max -lt 1000) "ms=$($times -join ', ')"

    # Query plan of the adapter's own SQL (read from its source), with this entity and window.
    $javaText = [System.IO.File]::ReadAllText($adapterSource)
    $m = [regex]::Match($javaText, 'static final String SQL = """(?s)(.*?)""";')
    if (-not $m.Success) { throw "could not read the adapter SQL" }
    $planSql = $m.Groups[1].Value.Replace(':tenant', "'$perfTenant'::uuid").Replace(':since', "now() - interval '30 days'").Replace(':until', 'now()').Replace(':manual', "'MANUAL_FALLBACK'")
    $plan = Sql "explain (analyze, buffers) $planSql"
    $planFile = Join-Path $runDir 'estimate-query-plan.txt'
    [System.IO.File]::WriteAllText($planFile, $plan)
    Write-Host "Query plan saved: $planFile"
    $execMs = [regex]::Match($plan, 'Execution Time: ([0-9.]+) ms').Groups[1].Value
    $summary.planExecutionMs = $execMs
    Check 'B3: EXPLAIN ANALYZE of the adapter SQL runs in under 1 second' ($execMs -and [double]$execMs -lt 1000) "execution=$execMs ms"

    $failed = @($checks | Where-Object { -not $_.ok }).Count
    Write-Host ""
    Write-Host ("{0} checks, {1} failed" -f $checks.Count, $failed)
    if ($failed -eq 0) { Write-Host 'PASS'; $exitCode = 0 } else { Write-Host 'FAIL' }
} finally {
    $summary.checks = $checks
    ($summary | ConvertTo-Json -Depth 8) | Set-Content -Encoding utf8 (Join-Path $runDir 'summary.json')
    if ($KeepRunning -and $containerStarted) {
        $keep = [ordered]@{ container = $Container; backend = $backend; appPid = $(if ($appProcess) { $appProcess.Id }); ollamaPid = $(if ($ollamaProcess) { $ollamaProcess.Id }); runDir = $runDir }
        ($keep | ConvertTo-Json) | Set-Content -Encoding utf8 (Join-Path $RunRoot 'keep-running.json')
        Write-Host "Left running for the UI check: $backend (stop with Stop-PolicyEstimateEnvironment.ps1)"
        $appProcess = $null; $ollamaProcess = $null; $containerStarted = $false
    }
    if ($appProcess -and -not $appProcess.HasExited) { Stop-Process -Id $appProcess.Id -Force -ErrorAction SilentlyContinue }
    if ($ollamaProcess -and -not $ollamaProcess.HasExited) { Stop-Process -Id $ollamaProcess.Id -Force -ErrorAction SilentlyContinue }
    if ($containerStarted) { docker rm -f $Container | Out-Null }
    foreach ($name in 'SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME', 'SERVER_PORT', 'SECUREDOC_AI_PROVIDER',
            'SECUREDOC_AI_OLLAMA_BASE_URL', 'SECUREDOC_AI_MODEL', 'SECUREDOC_AI_OLLAMA_PRELOAD_ENABLED',
            'SECUREDOC_WATCHED_FOLDER_ENABLED', 'SECUREDOC_WATCHED_FOLDER_SCHEDULING_ENABLED', 'SECUREDOC_LOG_DIR',
            'SECUREDOC_PRODUCT_TELEMETRY_OUTPUT_PATH', 'SECUREDOC_DELIVERY_LOCAL_OUTPUT_PATH') {
        Remove-Item "Env:$name" -ErrorAction SilentlyContinue
    }
    $http.Dispose()
    Write-Host "Evidence: $runDir"
}
exit $exitCode
