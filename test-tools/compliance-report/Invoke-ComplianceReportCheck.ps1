#requires -Version 5.1
<#
.SYNOPSIS
    Task 0017 end to end on a THROWAWAY database: the compliance report PDF, both editions, every role,
    the hashes against the audit record, and the printed numbers against the seeded data and Reports.

.DESCRIPTION
    1. Starts a throwaway Postgres container (lexavault-0017-pg, 127.0.0.1:55417). It never touches
       securedoc-postgres or any other container named in CLAUDE.md, and removes only its own container.
    2. Starts the task 0017 jar (purge scheduler off, so seeded deletions stay as seeded).
    3. Seeds entity 00000000-0000-0000-0000-000000000001 through the API: sensitive-data policy changes
       (and one save with no change), a lifecycle change (and one with no change), 6 cases whose business
       identifier values and file name hold markers, a Reports CSV export and an audit CSV export.
    4. Moves those records 35 days back (SQL on the throwaway database only), so they fall in a past period,
       then seeds deliveries, purges, review facts and audit rows inside that period by SQL, including audit
       details holding a file-name marker and a loan-number marker.
    5. Creates both editions through POST /api/v1/compliance-reports as each allowed role and is refused as
       every other role; checks the 403s are audited.
    6. Checks the X-Report-File-SHA256 header against the bytes and the audit record, and the printed
       fingerprint against the audit record.
    7. Extracts the PDF text (PDFBox) and checks the numbers against the seeded data and GET /operations/reports,
       and that no marker or fake value appears in either PDF, the report audit records or the new log lines.
    8. Saves one PDF of each edition under test-tools\compliance-report\output\ (git-ignored).

    Task 0018 additions: the entity name alone without a licence, then a throwaway test licence (MakeTestLicence.java,
    a new key pair per run, private key never written) and the licence's customer name on the cover and header line
    (A1); a 403 from the capability check recorded as AUTHORIZATION_FAILED with capability, method and route pattern
    only (A2); no "Rejected" rows (A3); lifecycle previous= values and the "Changes during the period" table (A4);
    the note under section 3 (A5); single spaces in the footer fingerprint (A7).

    The container password is generated per run and never printed.
#>
[CmdletBinding()]
param(
    [string] $JarPath,
    [string] $RunRoot = (Join-Path $env:TEMP 'lexavault-0017'),
    [int] $DbPort = 55417,
    [int] $AppPort = 18017
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$here = $PSScriptRoot
$repoRoot = (Resolve-Path (Join-Path $here '..\..')).Path
$helpers = Join-Path $repoRoot 'test-tools\lexavault-acceptance\helpers'
if (-not $JarPath) { $JarPath = Join-Path $repoRoot 'backend\securedoc-api\target\securedoc-api-0.1.0-SNAPSHOT.jar' }
if (-not (Test-Path -LiteralPath $JarPath)) { throw "Jar not found: $JarPath" }

$Container = 'lexavault-0017-pg'
$Protected = @('securedoc-postgres', 'sdv-postgres', 'sdv-vault', 'lexa-keycloak', 'lexa-keycloak-db')
if ($Protected -contains $Container) { throw "Refusing to use a protected container name." }

$tenantId = '00000000-0000-0000-0000-000000000001'
$actorId = '00000000-0000-0000-0000-000000000099'
$system = '00000000-0000-0000-0000-000000000000'
$backend = "http://localhost:$AppPort"
$outputDir = Join-Path $here 'output'
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null

$runDir = Join-Path $RunRoot (Get-Date -Format 'yyyyMMdd-HHmmss')
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
Write-Host "Run folder: $runDir"

# Markers where a borrower's file name, loan number or business identifier value would appear if leaked.
$suffix = (Get-Random -Minimum 100000 -Maximum 999999)
$fileMarker = "MARKERFILE$suffix"
$idMarker = "99170${suffix}"
$idMarkers = @()
$loanMarker = "LOANMARK$suffix"
$fakeValues = @('412-55-7531', '412557531', '7531', '4000 0566 5566 5556', '4000056655665556', '03/14/1962')

$checks = New-Object System.Collections.ArrayList
function Check([string] $name, [bool] $ok, [string] $evidence = '') {
    [void]$checks.Add([pscustomobject]@{ check = $name; pass = $ok; evidence = $evidence })
    Write-Host ("  [{0}] {1} {2}" -f $(if ($ok) { 'PASS' } else { 'FAIL' }), $name, $evidence)
}

function Sql([string] $query) {
    $out = docker exec $Container psql -U securedoc -d securedoc -v ON_ERROR_STOP=1 -tAc $query
    if ($LASTEXITCODE -ne 0) { throw "psql failed: $query" }
    return ((($out | Out-String) -replace "`r", "").Trim())
}

$env:SECUREDOC_BACKEND_URL = $backend
$env:SECUREDOC_TENANT_ID = $tenantId
$env:SECUREDOC_ACTOR_ID = $actorId
$invokeApi = Join-Path $helpers 'Invoke-TestApi.ps1'
$sendDoc = Join-Path $helpers 'Send-TestDocument.ps1'

Add-Type -AssemblyName System.Net.Http
$http = New-Object System.Net.Http.HttpClient
$http.Timeout = [TimeSpan]::FromMinutes(5)

function New-Report([string] $roles, [bool] $full, [string] $from, [string] $to) {
    $req = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, "$backend/api/v1/compliance-reports")
    $req.Headers.Add('X-Tenant-Id', $tenantId)
    $req.Headers.Add('X-Actor-Id', $actorId)
    $req.Headers.Add('X-Actor-Roles', $roles)
    $req.Headers.Add('X-Correlation-Id', [guid]::NewGuid().ToString())
    $json = @{ period = 'CUSTOM'; from = $from; to = $to; timeZone = 'UTC'; includeFullAuditTrail = $full;
               tenantId = '11111111-1111-1111-1111-111111111111' } | ConvertTo-Json -Compress
    $req.Content = New-Object System.Net.Http.StringContent($json, [System.Text.Encoding]::UTF8, 'application/json')
    $res = $http.SendAsync($req).GetAwaiter().GetResult()
    $bytes = $res.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
    $sha = $null
    if ($res.Headers.Contains('X-Report-File-SHA256')) { $sha = @($res.Headers.GetValues('X-Report-File-SHA256'))[0] }
    $disposition = $null
    if ($res.Content.Headers.ContentDisposition) { $disposition = $res.Content.Headers.ContentDisposition.FileName }
    return [pscustomobject]@{ status = [int]$res.StatusCode; bytes = $bytes; sha = $sha; fileName = $disposition
        contentType = "$($res.Content.Headers.ContentType)" }
}

function Invoke-Raw([string] $method, [string] $path, [string] $roles) {
    $req = New-Object System.Net.Http.HttpRequestMessage((New-Object System.Net.Http.HttpMethod($method)), "$backend$path")
    $req.Headers.Add('X-Tenant-Id', $tenantId)
    $req.Headers.Add('X-Actor-Id', $actorId)
    $req.Headers.Add('X-Actor-Roles', $roles)
    $req.Headers.Add('X-Correlation-Id', [guid]::NewGuid().ToString())
    $res = $http.SendAsync($req).GetAwaiter().GetResult()
    return [int]$res.StatusCode
}

function Get-Sha256([byte[]] $bytes) {
    $alg = [System.Security.Cryptography.SHA256]::Create()
    return (($alg.ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') }) -join '')
}

$m2 = Join-Path $env:USERPROFILE '.m2\repository'
$pdfboxCp = @(
    "$m2\org\apache\pdfbox\pdfbox\3.0.2\pdfbox-3.0.2.jar",
    "$m2\org\apache\pdfbox\fontbox\3.0.2\fontbox-3.0.2.jar",
    "$m2\org\apache\pdfbox\pdfbox-io\3.0.2\pdfbox-io-3.0.2.jar",
    (Get-ChildItem "$m2\commons-logging\commons-logging" -Recurse -Filter 'commons-logging-*.jar' | Select-Object -First 1).FullName
) -join ';'

function Get-PdfText([string] $pdfPath) {
    $txt = "$pdfPath.txt"
    & java -cp $pdfboxCp (Join-Path $here 'ExtractPdfText.java') $pdfPath $txt
    if ($LASTEXITCODE -ne 0) { throw "text extraction failed for $pdfPath" }
    return [System.IO.File]::ReadAllText($txt, [System.Text.Encoding]::UTF8)
}

function Flat([string] $s) { return (($s -replace '\s+', ' ').Trim()) }

function Start-App([string] $dbPassword) {
    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:$DbPort/securedoc"
    $env:SPRING_DATASOURCE_USERNAME = 'securedoc'
    $env:SPRING_DATASOURCE_PASSWORD = $dbPassword
    $env:SERVER_PORT = "$AppPort"
    $env:SECUREDOC_AI_OLLAMA_PRELOAD_ENABLED = 'false'
    $env:SECUREDOC_WATCHED_FOLDER_ENABLED = 'false'
    $env:SECUREDOC_WATCHED_FOLDER_SCHEDULING_ENABLED = 'false'
    $env:SECUREDOC_LOG_DIR = (Join-Path $runDir 'logs')
    $env:SECUREDOC_PRODUCT_TELEMETRY_OUTPUT_PATH = (Join-Path $runDir 'telemetry.jsonl')
    $env:SECUREDOC_DELIVERY_LOCAL_OUTPUT_PATH = (Join-Path $runDir 'securedoc-output')
    try {
        $p = Start-Process -FilePath 'java' -PassThru -WindowStyle Hidden -WorkingDirectory $runDir `
            -ArgumentList @('-jar', $JarPath, '--spring.profiles.active=local',
                '--securedoc.lifecycle.purge-scheduler-enabled=false',
                "--securedoc.storage.local.root-path=$(Join-Path $runDir 'securedoc-storage')") `
            -RedirectStandardOutput (Join-Path $runDir 'app.out.log') -RedirectStandardError (Join-Path $runDir 'app.err.log')
    } finally {
        Remove-Item Env:SPRING_DATASOURCE_PASSWORD -ErrorAction SilentlyContinue
    }
    $deadline = (Get-Date).AddSeconds(180)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        if ($p.HasExited) { throw "backend exited during startup (see $runDir\app.out.log)" }
        try { if ((Invoke-RestMethod -Uri "$backend/actuator/health" -TimeoutSec 3).status -eq 'UP') { return $p } } catch { }
    }
    throw "backend did not become healthy"
}

function Stop-App($p) {
    if ($p -and -not $p.HasExited) { Stop-Process -Id $p.Id -Force; $p.WaitForExit(15000) | Out-Null }
}

function New-Case([string] $tag, [int] $index) {
    $n = (Get-Random -Minimum 100000 -Maximum 999999)
    $contract = "99170$index$suffix"
    $script:idMarkers += $contract
    $body = @{
        packageType = 'MANUAL_UPLOAD'
        businessIdentifiers = @{ contract_number = $contract; member_number = "90017$n"; service_entity = '001' }
        metadata = @{ source = "task-0017-$tag" }
    }
    return (& $invokeApi -Method POST -Path '/api/v1/packages' -Body $body).packageId
}

function Set-Policy([string[]] $types, [double] $threshold, [string] $mode, [bool] $mask) {
    $body = @{ enabledEntityTypes = $types; autoRedactThreshold = $threshold; reviewMode = $mode; maskSsnLastFour = $mask }
    & $invokeApi -Method PUT -Path '/api/v1/business-admin/sensitive-data-policy' -Body $body -Roles 'BUSINESS_ADMIN' | Out-Null
}

function Set-Lifecycle([int] $orig, [int] $redacted, [int] $hold, [bool] $approval) {
    $body = @{ originalRetentionHours = $orig; redactedCacheHours = $redacted; neverExportedHoldHours = $hold; finalApprovalRequired = $approval }
    & $invokeApi -Method PUT -Path '/api/v1/business-admin/lifecycle-policy' -Body $body -Roles 'BUSINESS_ADMIN' | Out-Null
}

function Count-Audit([string] $where) { return [int](Sql "select count(*) from audit_records where tenant_id='$tenantId' and $where") }

$app = $null
$containerStarted = $false
$exitCode = 1
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
    do {
        Start-Sleep -Seconds 1
        docker exec $Container pg_isready -U securedoc -d securedoc 2>$null | Out-Null
    } while ($LASTEXITCODE -ne 0 -and (Get-Date) -lt $deadline)
    if ($LASTEXITCODE -ne 0) { throw "throwaway Postgres did not become ready" }
    Start-Sleep -Seconds 2
    Write-Host "Throwaway Postgres ready on 127.0.0.1:$DbPort"

    # --- 2. app ------------------------------------------------------------------------------
    # A1: a throwaway test licence; the app trusts its public key through the LOCAL development-key override.
    $customerName = 'Acme Test Lending Group'
    $pubFile = Join-Path $runDir 'test-licence-public-key.txt'
    $tokenFile = Join-Path $runDir 'test-licence-token.txt'
    & java (Join-Path $here 'MakeTestLicence.java') $tenantId $customerName $pubFile $tokenFile
    if ($LASTEXITCODE -ne 0) { throw "test licence was not made" }
    $env:SECUREDOC_LICENSING_PUBLIC_KEY = [System.IO.File]::ReadAllText($pubFile).Trim()
    $app = Start-App $dbPassword
    $tenantName = Sql "select name from tenants where id='$tenantId'"
    Write-Host "Entity: $tenantName"

    # --- 3. seed through the API -------------------------------------------------------------
    $v1 = @('SSN', 'DATE_OF_BIRTH', 'CREDIT_CARD_NUMBER', 'CREDIT_SCORE')
    Set-Policy $v1 0.80 'UNCERTAIN_ONLY' $true                         # first save: previous=none
    Set-Policy $v1 0.70 'UNCERTAIN_ONLY' $true                         # Balanced -> Light
    $beforeNoChange = Count-Audit "action='SENSITIVE_DATA_POLICY_CHANGED'"
    Set-Policy @('CREDIT_SCORE', 'SSN', 'DATE_OF_BIRTH', 'CREDIT_CARD_NUMBER') 0.70 'UNCERTAIN_ONLY' $true  # no change
    $afterNoChange = Count-Audit "action='SENSITIVE_DATA_POLICY_CHANGED'"
    Check 'A4: a sensitive-data policy save with no change writes no record' ($afterNoChange -eq $beforeNoChange) "before=$beforeNoChange after=$afterNoChange"
    Set-Policy @('SSN', 'DATE_OF_BIRTH', 'CREDIT_CARD_NUMBER') 0.70 'UNCERTAIN_ONLY' $true  # credit score off
    $policyChangeCount = Count-Audit "action='SENSITIVE_DATA_POLICY_CHANGED'"
    Check 'the 0.70 save and the credit-score save are both recorded' ($policyChangeCount -eq $afterNoChange + 1 -and $beforeNoChange -ge 1) "records=$policyChangeCount"
    $firstSave = (Count-Audit "action='SENSITIVE_DATA_POLICY_CHANGED' and detail like '%previous=none'") -eq 1
    Set-Lifecycle 72 24 168 $false
    Set-Lifecycle 72 24 168 $false                                     # no change
    Check 'A4: a lifecycle save with no change writes no record' ((Count-Audit "action='LIFECYCLE_POLICY_CHANGED'") -eq 1)
    Set-Lifecycle 48 24 168 $false                                     # 0018 A4: 72 -> 48 hours
    $lifecycleDetail = Sql "select detail from audit_records where tenant_id='$tenantId' and action='LIFECYCLE_POLICY_CHANGED' order by occurred_at desc limit 1"
    Check '0018 A4: LIFECYCLE_POLICY_CHANGED keeps its keys first and adds previous=[...]' `
        ($lifecycleDetail -eq ('Lifecycle policy changed: originalRetentionHours=48, redactedCacheHours=24, neverExportedHoldHours=168, ' +
            'finalApprovalRequired=false, previous=[originalRetentionHours=72, redactedCacheHours=24, neverExportedHoldHours=168, ' +
            'finalApprovalRequired=false]')) $lifecycleDetail
    $lifecycleFirst = Sql "select detail from audit_records where tenant_id='$tenantId' and action='LIFECYCLE_POLICY_CHANGED' order by occurred_at asc limit 1"
    Check '0018 A4: the first lifecycle save records previous=none' ($lifecycleFirst -like '*finalApprovalRequired=false, previous=none') $lifecycleFirst

    # 0018 A2: a 403 from the capability check is recorded with capability, method and route pattern only.
    $refusedStatus = Invoke-Raw 'GET' "/api/v1/operations/reports/export?marker=$loanMarker" 'REPORT_VIEWER'
    $authFailed = Sql "select coalesce(string_agg(outcome || ' ' || resource_id || ' ' || detail, ' | '), '') from audit_records where tenant_id='$tenantId' and action='AUTHORIZATION_FAILED'"
    Check '0018 A2: a refused export (403) is recorded once as DENIED AUTHORIZATION_FAILED, route pattern only' `
        ($refusedStatus -eq 403 -and $authFailed -eq 'DENIED /api/v1/operations/reports/export capability=REPORT_EXPORT, method=GET, route=/api/v1/operations/reports/export') `
        "status=$refusedStatus record=$authFailed"

    $cases = @{}
    $i = 0
    foreach ($c in 'C1', 'C2', 'C3', 'C4', 'C5', 'C6') { $i++; $cases[$c] = New-Case $c $i }
    $pdf = Join-Path $runDir "$fileMarker-loan-file.pdf"
    & java -cp $pdfboxCp (Join-Path $repoRoot 'test-tools\no-redacted-values\MakeFakeValuePdf.java') $pdf
    if ($LASTEXITCODE -ne 0) { throw "test PDF was not written" }
    $uploaded = & $sendDoc -PackageId $cases['C1'] -FilePath $pdf
    Check 'a document named with the file marker is stored' ((Sql "select count(*) from documents where file_name like '$fileMarker%'") -eq '1')
    Check 'A1: DOCUMENT_UPLOADED carries the case ID' ((Count-Audit "action='DOCUMENT_UPLOADED' and case_id='$($cases['C1'])'") -eq 1)
    & $invokeApi -Method GET -Path '/api/v1/operations/reports/export' -Roles 'BUSINESS_ADMIN' | Out-Null
    & $invokeApi -Method GET -Path '/api/v1/audit-records/export?format=csv' -Roles 'TENANT_IT_ADMIN' | Out-Null

    # --- 4. move into a past period and seed the rest by SQL ---------------------------------
    $today = (Get-Date).ToUniversalTime().Date
    $from = $today.AddDays(-40).ToString('yyyy-MM-dd')
    $to = $today.AddDays(-31).ToString('yyyy-MM-dd')
    $endWords = $today.AddDays(-31).ToString('MMM d', [System.Globalization.CultureInfo]::GetCultureInfo('en-US'))
    Sql "update audit_records set occurred_at = occurred_at - interval '35 days' where tenant_id='$tenantId'" | Out-Null
    Sql "update document_packages set created_at = created_at - interval '35 days' where tenant_id='$tenantId'" | Out-Null
    $receivedInPeriod = [int](Sql "select count(*) from reporting_package_facts where tenant_id='$tenantId' and received_at >= '$from' and received_at < date '$to' + 1")
    Check 'the 6 cases now fall in the period (Reports facts follow the package)' ($receivedInPeriod -eq 6) "received=$receivedInPeriod"

    $T = "(timestamptz '$from' + interval '5 days')"
    function Fact([string] $case, [string] $set) { Sql "update reporting_package_facts set $set where tenant_id='$tenantId' and package_id='$case'" | Out-Null }
    Fact $cases['C1'] "first_delivered_at = $T, status='COMPLETED', reviewed_flag=false"
    Fact $cases['C2'] "first_delivered_at = $T, status='COMPLETED', reviewed_flag=true"
    Fact $cases['C3'] "first_delivered_at = $T, status='COMPLETED', reviewed_flag=true"
    Fact $cases['C4'] "status='REJECTED'"
    Fact $cases['C6'] "first_delivered_at = timestamptz '$to' + interval '20 hours', status='COMPLETED', reviewed_flag=false"
    function Lifecycle([string] $case, [string] $exported, [string] $due, [string] $purged) {
        Sql ("insert into package_lifecycles (package_id, tenant_id, successful_export_cycles, last_exported_at, original_delete_at, " +
             "redacted_delete_at, original_purged_at, purged_at, final_approval_granted, updated_at, version) values " +
             "('$case', '$tenantId', 1, $exported, $due, $due, $purged, $purged, false, now(), 0) " +
             "on conflict (package_id) do update set successful_export_cycles=1, last_exported_at=excluded.last_exported_at, " +
             "original_delete_at=excluded.original_delete_at, original_purged_at=excluded.original_purged_at, purged_at=excluded.purged_at") | Out-Null
    }
    $due = "($T + interval '72 hours')"
    Lifecycle $cases['C1'] $T $due "($due + interval '1 hour')"
    Lifecycle $cases['C2'] $T $due "($due + interval '30 hours')"
    Lifecycle $cases['C3'] $T $due 'null'
    Lifecycle $cases['C6'] "(timestamptz '$to' + interval '20 hours')" "(timestamptz '$to' + interval '92 hours')" 'null'
    function Audit([string] $action, [string] $outcome, [string] $actor, [string] $type, [string] $resource, [string] $detail, [string] $at, [string] $caseId) {
        $d = if ($null -eq $detail) { 'null' } else { "'" + $detail.Replace("'", "''") + "'" }
        $c = if ($caseId) { "'$caseId'" } else { 'null' }
        Sql ("insert into audit_records (id, tenant_id, actor_id, action, resource_type, resource_id, outcome, correlation_id, detail, occurred_at, created_at, case_id) " +
             "values (gen_random_uuid(), '$tenantId', '$actor', '$action', '$type', '$resource', '$outcome', gen_random_uuid(), $d, $at, now(), $c)") | Out-Null
    }
    Audit 'ORIGINAL_ARTIFACT_PURGED' 'SUCCESS' $system 'DocumentPackage' $cases['C1'] 'Original and remaining internal working artifacts purged' "($due + interval '1 hour')" $cases['C1']
    Audit 'ORIGINAL_ARTIFACT_PURGED' 'SUCCESS' $system 'DocumentPackage' $cases['C2'] 'Original and remaining internal working artifacts purged' "($due + interval '30 hours')" $cases['C2']
    Audit 'PACKAGE_PURGED' 'FAILURE' $system 'DocumentPackage' $cases['C3'] 'Package artifact purge failed: dependency=STORAGE, code=STORAGE_IO, retryable=true, attempts=1' "($due + interval '1 hour')" $cases['C3']
    Audit 'BUSINESS_MANUAL_FALLBACK_SUBMITTED' 'SUCCESS' $actorId 'DocumentPackage' $cases['C3'] 'Business manual fallback submitted: documents=1, manualRegions=2, businessReviewAttested=true' $T $cases['C3']
    Audit 'FINDING_CONFIRMED' 'SUCCESS' $actorId 'Finding' ([guid]::NewGuid()) 'Finding confirmed: type=SSN' $T $cases['C2']
    Audit 'FINDING_CONFIRMED' 'SUCCESS' $actorId 'Finding' ([guid]::NewGuid()) 'Finding confirmed: type=CREDIT_CARD_NUMBER' $T $cases['C2']
    Audit 'FINDING_DISMISSED' 'SUCCESS' $actorId 'Finding' ([guid]::NewGuid()) 'Finding dismissed: type=DATE_OF_BIRTH' $T $cases['C3']
    Audit 'FINDING_CONFIRMED' 'SUCCESS' $system 'Finding' ([guid]::NewGuid()) 'Finding confirmed: type=SSN' $T $cases['C1']
    Audit 'PACKAGE_FINAL_APPROVED' 'SUCCESS' $actorId 'DocumentPackage' $cases['C2'] 'Final delivery approved' $T $cases['C2']
    Audit 'DOCUMENT_DOWNLOADED' 'DENIED' $actorId 'Artifact' ([guid]::NewGuid()) 'Original artifact access denied: no approved request' $T $cases['C1']
    Audit 'DOCUMENT_EXPORTED' 'SUCCESS' $system 'Document' ([guid]::NewGuid()) "Document exported: destination=LOCAL_FOLDER, filename=$fileMarker-loan-file.pdf" $T $cases['C1']
    Audit 'POLLING_RUN_FAILED' 'FAILURE' $system 'IntegrationConnection' ([guid]::NewGuid()) "Polling run failed: detail=loan $loanMarker for $($idMarkers[0]) not found" $T $null

    # Independent expectations, straight from the seeded tables.
    $expect = [ordered]@{ received = 6; delivered = 4; rejected = 1; open = 1; straight = 2; reviewed = 2; byHand = 1
        due = 3; onTime = 1; late = 2; stillWithin = 1; policyChanges = $policyChangeCount; lifecycleChanges = 2; findings = 3
        approved = 1; originals = 2; exports = 2; compliance = 0; refused = 2 }
    $fromInstant = "$($from)T00:00:00Z"; $toInstant = "$($today.AddDays(-30).ToString('yyyy-MM-dd'))T00:00:00Z"
    $reports = & $invokeApi -Method GET -Path "/api/v1/operations/reports?from=$fromInstant&to=$toInstant" -Roles 'BUSINESS_ADMIN'
    Check 'Reports agrees with the seed: received, delivered, reviewed, no review needed' `
        ($reports.executive.batches -eq $expect.received -and $reports.executive.delivered -eq $expect.delivered -and
         $reports.usage.reviewedPackages -eq $expect.reviewed -and $reports.usage.straightThroughPackages -eq $expect.straight) `
        "batches=$($reports.executive.batches) delivered=$($reports.executive.delivered) reviewed=$($reports.usage.reviewedPackages) straight=$($reports.usage.straightThroughPackages)"

    # --- 5. every role, both editions --------------------------------------------------------
    $logFiles = @(Get-ChildItem -Path $runDir -Recurse -Filter '*.log' | ForEach-Object { $_.FullName })
    $logOffsets = @{}
    foreach ($f in $logFiles) { $logOffsets[$f] = (Get-Item $f).Length }
    $deniedBefore = Count-Audit "action='COMPLIANCE_REPORT_CREATED' and outcome='DENIED'"
    $roles = @('DOCUMENT_PROCESSOR', 'DOCUMENT_SUPERVISOR', 'BUSINESS_ADMIN', 'REPORT_VIEWER', 'TENANT_IT_ADMIN', 'PLATFORM_OPERATIONS_ADMIN')
    $made = @{}
    foreach ($role in $roles) {
        foreach ($full in $false, $true) {
            $allowed = if ($full) { $role -eq 'TENANT_IT_ADMIN' } else { $role -eq 'BUSINESS_ADMIN' }
            $r = New-Report $role $full $from $to
            $label = "$role $(if ($full) { 'full' } else { 'summary' })"
            if ($allowed) {
                Check "allowed: $label gets a PDF" ($r.status -eq 200 -and $r.contentType -eq 'application/pdf' -and $r.bytes.Length -gt 1000) "status=$($r.status)"
                $made[$(if ($full) { 'FULL' } else { 'SUMMARY' })] = $r
            } else {
                Check "refused: $label gets 403" ($r.status -eq 403) "status=$($r.status)"
            }
        }
    }
    $deniedAfter = Count-Audit "action='COMPLIANCE_REPORT_CREATED' and outcome='DENIED'"
    Check 'every refusal is audited as a refused COMPLIANCE_REPORT_CREATED' (($deniedAfter - $deniedBefore) -eq 10) "denied=$($deniedAfter - $deniedBefore)"
    Check '0018 A2: compliance report refusals are not recorded twice (no AUTHORIZATION_FAILED for them)' `
        ((Count-Audit "action='AUTHORIZATION_FAILED'") -eq 1)
    $bad = New-Report 'BUSINESS_ADMIN' $false $today.ToString('yyyy-MM-dd') $today.ToString('yyyy-MM-dd')
    Check 'a period that does not end before today is refused with 400' ($bad.status -eq 400) "status=$($bad.status)"
    Check 'the failure is audited by category only' ((Count-Audit "action='COMPLIANCE_REPORT_CREATED' and outcome='FAILURE' and detail='period=invalid, edition=SUMMARY, failure=VALIDATION'") -eq 1)

    # --- 6. hashes ---------------------------------------------------------------------------
    $texts = @{}
    foreach ($edition in 'SUMMARY', 'FULL') {
        $r = $made[$edition]
        $sha = Get-Sha256 $r.bytes
        $detail = Sql "select detail from audit_records where tenant_id='$tenantId' and action='COMPLIANCE_REPORT_CREATED' and outcome='SUCCESS' and detail like '%edition=$edition,%' order by occurred_at desc limit 1"
        Check "$edition`: X-Report-File-SHA256 is the SHA-256 of the bytes" ($r.sha -eq $sha) $sha
        Check "$edition`: the audit record holds the same file SHA-256" ($detail -like "*fileSha256=$sha") $detail
        Check "$edition`: file name" ($r.fileName -match "^`"?compliance-report-[a-z0-9-]+-$from-$to\.pdf`"?$") $r.fileName
        $out = Join-Path $outputDir ("compliance-report-$($edition.ToLower())-sample.pdf")
        [System.IO.File]::WriteAllBytes($out, $r.bytes)
        $text = Get-PdfText $out
        $texts[$edition] = $text
        $fp = [regex]::Match($detail, 'fingerprint=([0-9a-f]{64})').Groups[1].Value
        $grouped = (($fp -split '(.{4})' | Where-Object { $_ }) -join ' ')
        Check "$edition`: the cover prints the recorded content fingerprint" ((Flat $text).Contains($grouped)) $grouped
        $footer = "Fingerprint $($fp.Substring(0,4)) $($fp.Substring(4,4)) $([char]0x2026) $($fp.Substring(60,4)) $([char]0x00B7) created by LexaVault"
        Check "$edition`: 0018 A7: footer fingerprint with single spaces" ($text.Contains($footer) -and -not $text.Contains("$($fp.Substring(0,4))  ")) $footer
        Check "$edition`: 0018 A1: no licence yet: no customer name" (-not $text.Contains($customerName))
    }

    # --- 7. numbers, wording and leaks -------------------------------------------------------
    $s = Flat $texts['SUMMARY']
    $expectedLines = @(
        "Received $($expect.received) 100%", "Delivered $($expect.delivered) 67%",
        "Still open on $endWords $($expect.open) 17%", "No review needed $($expect.straight) 50%", "Checked by a reviewer $($expect.reviewed) 50%",
        "Finished by hand after a processing stop $($expect.byHand) 25%",
        "Originals due for deletion $($expect.due) Deleted on time $($expect.onTime) Late or failed $($expect.late)",
        "$($expect.stillWithin) case delivered in the last 48 hours of the period was still within its keep time on $endWords",
        "Redaction policy changes $($expect.policyChanges)", "Keeping and deleting changes $($expect.lifecycleChanges)",
        "Findings decided by a reviewer $($expect.findings)", "Cases approved for delivery $($expect.approved)",
        "Originals deleted $($expect.originals)", "Report exports (CSV) $($expect.exports)",
        "Compliance reports created $($expect.compliance)", "Access refused (wrong role) $($expect.refused)",
        "Preset: Balanced $([char]0x2192) Light", "Redacted without review at: 80% $([char]0x2192) 70%",
        "Credit score turned off", "Social Security Number, Date of birth, Credit card number", "70% confidence or higher",
        "Originals kept for 48 hours after delivery", "Stopped cases kept for 7 days", "Supervisor approval before delivery Not required",
        "COMPLIANCE REPORT $([char]0x00B7) SUMMARY EDITION", "$tenantName only. Other entities are never included.",
        # 0018 A4: the lifecycle changes table in section 2.
        "Originals kept for: 72 $([char]0x2192) 48 hours",
        # 0018 A5: the note under the first table of section 3.
        "Delivered counts cases first delivered in the period, whenever they were received, as on the Reports page. Shares are of cases received.")
    if ($firstSave) { $expectedLines += "Policy saved for the first time" }
    foreach ($line in $expectedLines) { Check "summary prints: $line" ($s.Contains($line)) }
    $section2 = $s.Substring($s.IndexOf("2 $([char]0x00B7) Keeping and deleting"))
    $section2 = $section2.Substring(0, $section2.IndexOf('Proof that deletions ran'))
    Check '0018 A4: section 2 has a "Changes during the period" table' ($section2.Contains('Changes during the period') -and $section2.Contains('WHAT CHANGED')) $section2
    Check '0018 A3: no "Rejected" rows in either edition' (-not ($s -match '(?i)rejected') -and -not ($texts['FULL'] -match 'Rejected by a supervisor|Cases rejected'))
    Check '0018 A2: the full edition appendix prints the AUTHORIZATION_FAILED detail' `
        (($texts['FULL'] -replace '\s+', '').Contains('capability=REPORT_EXPORT,method=GET,route=/api/v1/operations/reports/export'))
    $f = Flat $texts['FULL']
    Check 'full edition: appendix with every record of the period, oldest first' `
        ($f.Contains("Appendix $([char]0x00B7) Full audit trail") -and $f.Contains('Exported a document') -and $f.Contains('Could not check a connection for documents'))
    Check 'full edition: details printed only for allowed actions' `
        ($f.Contains('Business manual fallback submitted: documents=1, manualRegions=2') -and -not $f.Contains('filename='))
    $periodRecords = Count-Audit "occurred_at >= '$from' and occurred_at < date '$to' + 1"
    Check 'full edition: one appendix row per record in the period' `
        (([regex]::Matches($texts['FULL'], '\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}')).Count -eq $periodRecords) "records=$periodRecords, rows=$(([regex]::Matches($texts['FULL'], '\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}')).Count)"

    $needles = @($fileMarker, $loanMarker) + $idMarkers + $fakeValues
    foreach ($edition in 'SUMMARY', 'FULL') {
        $plain = $texts[$edition]; $joined = $plain -replace '\s+', ''
        $hits = @($needles | Where-Object { $plain.Contains($_) -or $joined.Contains(($_ -replace '\s+', '')) })
        Check "$edition`: no marker or fake value in the PDF text" ($hits.Count -eq 0) ($hits -join ', ')
    }
    $reportAudit = Sql "select coalesce(string_agg(detail, ' | '), '') from audit_records where tenant_id='$tenantId' and action='COMPLIANCE_REPORT_CREATED'"
    $auditHits = @($needles | Where-Object { $reportAudit.Contains($_) })
    Check 'no marker or fake value in the report audit records' ($auditHits.Count -eq 0) ($auditHits -join ', ')
    $logHits = @()
    foreach ($file in $logOffsets.Keys) {
        $fs = New-Object System.IO.FileStream($file, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
        try { $all = New-Object byte[] $fs.Length; [void]$fs.Read($all, 0, $all.Length) } finally { $fs.Dispose() }
        $newPart = [System.Text.Encoding]::UTF8.GetString($all, [int]$logOffsets[$file], $all.Length - [int]$logOffsets[$file])
        $logHits += @($needles | Where-Object { $newPart.Contains($_) })
    }
    Check 'no marker or fake value in the log lines written while reports were created' ($logHits.Count -eq 0) ($logHits -join ', ')

    # --- 8. 0018 A1: with an installed licence, its customer name is on the cover and in the header line ----
    $token = [System.IO.File]::ReadAllText($tokenFile).Trim()
    $install = & $invokeApi -Method POST -Path "/api/v1/platform/licensing/tenants/$tenantId/licenses" `
        -Body @{ licenseToken = $token } -Roles 'PLATFORM_OPERATIONS_ADMIN'
    Remove-Item -LiteralPath $tokenFile -Force
    Check '0018 A1: the throwaway test licence installs' ($null -ne $install) "status=$($install.status)"
    foreach ($edition in 'SUMMARY', 'FULL') {
        $role = if ($edition -eq 'FULL') { 'TENANT_IT_ADMIN' } else { 'BUSINESS_ADMIN' }
        $r = New-Report $role ($edition -eq 'FULL') $from $to
        $out = Join-Path $outputDir ("compliance-report-$($edition.ToLower())-licensed-sample.pdf")
        [System.IO.File]::WriteAllBytes($out, $r.bytes)
        $lt = Get-PdfText $out
        $pages = $lt -split "Page \d+ of \d+"
        $cover = Flat $pages[0]
        Check "$edition`: 0018 A1: the cover shows the licence's customer name above the entity" `
            ($r.status -eq 200 -and $cover.Contains("$customerName $tenantName")) "status=$($r.status)"
        $header = "Compliance report $([char]0x00B7) $tenantName $([char]0x00B7)"
        $headerLines = @(($lt -split "`n") | Where-Object { $_.StartsWith($header) })
        $withCustomer = @($headerLines | Where-Object { $_.TrimEnd().EndsWith($customerName) })
        Check "$edition`: 0018 A1: every header line ends with the customer name" `
            ($headerLines.Count -ge 2 -and $withCustomer.Count -eq $headerLines.Count) "headers=$($headerLines.Count) withCustomer=$($withCustomer.Count)"
    }

    $failed = @($checks | Where-Object { -not $_.pass }).Count
    [pscustomobject]@{ period = "$from..$to"; cases = $cases; expect = $expect; checks = $checks } |
        ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 (Join-Path $runDir 'summary.json')
    Write-Host ""
    Write-Host "Sample PDFs: $outputDir"
    Write-Host ("Checks: {0}, failed: {1}" -f $checks.Count, $failed)
    if ($failed -eq 0) { Write-Host 'PASS'; $exitCode = 0 } else { Write-Host 'FAIL' }
}
finally {
    Stop-App $app
    if ($containerStarted) {
        docker rm -f $Container | Out-Null
        Write-Host "Removed throwaway container $Container"
    }
    foreach ($name in 'SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME', 'SERVER_PORT', 'SECUREDOC_AI_OLLAMA_PRELOAD_ENABLED',
            'SECUREDOC_WATCHED_FOLDER_ENABLED', 'SECUREDOC_WATCHED_FOLDER_SCHEDULING_ENABLED', 'SECUREDOC_LOG_DIR',
            'SECUREDOC_PRODUCT_TELEMETRY_OUTPUT_PATH', 'SECUREDOC_DELIVERY_LOCAL_OUTPUT_PATH',
            'SECUREDOC_BACKEND_URL', 'SECUREDOC_TENANT_ID', 'SECUREDOC_ACTOR_ID', 'SECUREDOC_LICENSING_PUBLIC_KEY') {
        Remove-Item "Env:$name" -ErrorAction SilentlyContinue
    }
}
exit $exitCode
