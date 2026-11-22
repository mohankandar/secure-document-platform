#requires -Version 5.1
<#
.SYNOPSIS
    Task 0016 end to end on a THROWAWAY database: migration 040, old audit rows left unchanged and
    read back empty, new rows carrying the display name and the case ID, the policy-change detail,
    and the audit export (JSON and CSV). It also runs the acceptance-harness API helpers
    (test-tools/lexavault-acceptance/helpers) with their new default roles.

.DESCRIPTION
    1. Starts a throwaway Postgres container (lexavault-0016-pg, 127.0.0.1:55416). It never
       touches securedoc-postgres or any other container named in CLAUDE.md.
    2. Starts the BASE jar (built before task 0016, migrations up to 039) and writes old audit
       rows: a case is created and the sensitive-data policy is changed. Stops it.
    3. Fingerprints every old audit row (md5 of all its original columns).
    4. Starts the NEW jar: Liquibase applies 040. Checks the two new columns and that every old
       row is unchanged, with both new columns empty.
    5. Through the acceptance helpers (default roles, no ADMIN): creates a case, uploads a PDF,
       switches the review mode to REVIEW_ALL and restores it.
    6. Checks the new rows and the audit export (JSON and CSV).
    7. Stops the app and removes only the throwaway container.

    The container password is generated per run and never printed.

.PARAMETER BaseJarPath
    A backend jar built from the commit before task 0016 (for example the merge commit f1d2a74).

.PARAMETER JarPath
    The task 0016 backend jar. Default: backend\securedoc-api\target\securedoc-api-0.1.0-SNAPSHOT.jar.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $BaseJarPath,
    [string] $JarPath,
    [string] $RunRoot = (Join-Path $env:TEMP 'lexavault-0016'),
    [int] $DbPort = 55416,
    [int] $AppPort = 18016
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$here = $PSScriptRoot
$repoRoot = (Resolve-Path (Join-Path $here '..\..')).Path
$helpers = Join-Path $repoRoot 'test-tools\lexavault-acceptance\helpers'
if (-not $JarPath) { $JarPath = Join-Path $repoRoot 'backend\securedoc-api\target\securedoc-api-0.1.0-SNAPSHOT.jar' }
foreach ($j in $BaseJarPath, $JarPath) { if (-not (Test-Path -LiteralPath $j)) { throw "Jar not found: $j" } }

$Container = 'lexavault-0016-pg'
$Protected = @('securedoc-postgres', 'sdv-postgres', 'sdv-vault', 'lexa-keycloak', 'lexa-keycloak-db')
if ($Protected -contains $Container) { throw "Refusing to use a protected container name." }

$tenantId = '30216241-b724-5a3a-aef3-3034fd546d17'
$actorId = '00000000-0000-0000-0000-000000000099'
$backend = "http://localhost:$AppPort"

$runDir = Join-Path $RunRoot (Get-Date -Format 'yyyyMMdd-HHmmss')
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
Write-Host "Run folder: $runDir"

$checks = New-Object System.Collections.ArrayList
function Check([string] $name, [bool] $ok, [string] $evidence = '') {
    [void]$checks.Add([pscustomobject]@{ check = $name; pass = $ok; evidence = $evidence })
    Write-Host ("  [{0}] {1} {2}" -f $(if ($ok) { 'PASS' } else { 'FAIL' }), $name, $evidence)
}

function Sql([string] $query) {
    $out = docker exec $Container psql -U securedoc -d securedoc -tAc $query
    if ($LASTEXITCODE -ne 0) { throw "psql failed: $query" }
    # psql output reaches PowerShell with CRLF line ends; keep LF only so compares are exact.
    return ((($out | Out-String) -replace "`r", "").Trim())
}

$env:SECUREDOC_BACKEND_URL = $backend
$env:SECUREDOC_TENANT_ID = $tenantId
$env:SECUREDOC_ACTOR_ID = $actorId
$invokeApi = Join-Path $helpers 'Invoke-TestApi.ps1'
$sendDoc = Join-Path $helpers 'Send-TestDocument.ps1'

function Start-App([string] $jar, [string] $name, [string] $dbPassword) {
    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:$DbPort/securedoc"
    $env:SPRING_DATASOURCE_USERNAME = 'securedoc'
    $env:SPRING_DATASOURCE_PASSWORD = $dbPassword
    $env:SERVER_PORT = "$AppPort"
    $env:SECUREDOC_AI_OLLAMA_PRELOAD_ENABLED = 'false'
    $env:SECUREDOC_WATCHED_FOLDER_ENABLED = 'false'
    $env:SECUREDOC_WATCHED_FOLDER_SCHEDULING_ENABLED = 'false'
    $env:SECUREDOC_LOG_DIR = (Join-Path $runDir "logs-$name")
    $env:SECUREDOC_PRODUCT_TELEMETRY_OUTPUT_PATH = (Join-Path $runDir "telemetry-$name.jsonl")
    $env:SECUREDOC_DELIVERY_LOCAL_OUTPUT_PATH = (Join-Path $runDir 'securedoc-output')
    try {
        $p = Start-Process -FilePath 'java' -PassThru -WindowStyle Hidden -WorkingDirectory $runDir `
            -ArgumentList @('-jar', $jar, '--spring.profiles.active=local',
                "--securedoc.storage.local.root-path=$(Join-Path $runDir 'securedoc-storage')") `
            -RedirectStandardOutput (Join-Path $runDir "$name.out.log") -RedirectStandardError (Join-Path $runDir "$name.err.log")
    } finally {
        Remove-Item Env:SPRING_DATASOURCE_PASSWORD -ErrorAction SilentlyContinue
    }
    $deadline = (Get-Date).AddSeconds(180)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        if ($p.HasExited) { throw "$name backend exited during startup (see $runDir\$name.out.log)" }
        try { if ((Invoke-RestMethod -Uri "$backend/actuator/health" -TimeoutSec 3).status -eq 'UP') { return $p } } catch { }
    }
    throw "$name backend did not become healthy"
}

function Stop-App($p) {
    if ($p -and -not $p.HasExited) { Stop-Process -Id $p.Id -Force; $p.WaitForExit(15000) | Out-Null }
}

function New-Case([string] $tag) {
    $suffix = (Get-Random -Minimum 100000 -Maximum 999999)
    $body = @{
        packageType = 'MANUAL_UPLOAD'
        businessIdentifiers = @{ contract_number = "880016$suffix"; member_number = "90016$suffix"; service_entity = '001' }
        metadata = @{ source = "task-0016-$tag" }
    }
    return (& $invokeApi -Method POST -Path '/api/v1/packages' -Body $body).packageId
}

$fingerprint = "md5(concat_ws('|', id, tenant_id, actor_id, action, resource_type, resource_id, outcome, correlation_id, detail, occurred_at, created_at))"

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

    # Task 0037: the install steps (schema step -> setup with the demo files -> demo step). The app no longer inserts
    # the demo entity at startup; its UUID is derived from demo slot ENT-DEMO-0001.
    . (Join-Path $repoRoot 'scripts\lib\LexaVaultTools.ps1')
    Initialize-LexaVaultDatabase -Jar $JarPath -DbUrl "jdbc:postgresql://localhost:${DbPort}/securedoc" -DbUser 'securedoc' `
        -DbPassword $dbPassword -StateDir (Join-Path $runDir 'install')

    # --- 2. base jar: old rows ---------------------------------------------------------------
    $app = Start-App $BaseJarPath 'base' $dbPassword
    Check 'base jar: migration 040 not applied' ((Sql "select count(*) from databasechangelog where id like '040-%'") -eq '0')
    $oldCase = New-Case 'old'
    $oldPolicy = & (Join-Path $helpers 'Set-TestReviewPolicy.ps1') -TargetReviewMode REVIEW_ALL
    Stop-App $app; $app = $null
    # A force-stopped node keeps its last heartbeat. Wait until it is outside the 30 s active-node
    # window, so the new jar is the only active node (LOCAL_FILESYSTEM storage refuses two).
    $deadline = (Get-Date).AddSeconds(90)
    while ((Sql "select count(*) from processing_storage_nodes where heartbeat_at >= now() - interval '35 seconds'") -ne '0') {
        if ((Get-Date) -gt $deadline) { throw "the base node still looks active after 90 s" }
        Start-Sleep -Seconds 3
    }

    # --- 3. fingerprint old rows -------------------------------------------------------------
    $oldRows = Sql "select id || ' ' || $fingerprint from audit_records order by id"
    $oldCount = (Sql 'select count(*) from audit_records')
    $oldPolicyDetail = Sql "select detail from audit_records where action = 'SENSITIVE_DATA_POLICY_CHANGED'"
    Write-Host "Old audit rows: $oldCount; old policy detail: $oldPolicyDetail"

    # --- 4. new jar: migration 040 -----------------------------------------------------------
    $app = Start-App $JarPath 'new' $dbPassword
    Check 'migration 040 applied through Liquibase' ((Sql "select count(*) from databasechangelog where id like '040-%'") -eq '1')
    $cols = Sql "select column_name || ':' || data_type || ':' || character_maximum_length || ':' || is_nullable from information_schema.columns where table_name = 'audit_records' and column_name in ('actor_display_name','case_id') order by column_name"
    Check 'new columns: actor_display_name VARCHAR(200), case_id VARCHAR(100), both nullable' `
        ($cols -eq "actor_display_name:character varying:200:YES`ncase_id:character varying:100:YES") ($cols -replace "`n", '; ')
    $oldIds = ($oldRows -split "`n" | ForEach-Object { ($_ -split ' ')[0] }) -join "','"
    $oldRowsAfter = Sql "select id || ' ' || $fingerprint from audit_records where id in ('$oldIds') order by id"
    Check 'old rows unchanged (md5 of every original column)' ($oldRowsAfter -eq $oldRows) "rows=$oldCount"
    Check 'old rows read back with empty display name and case ID' `
        ((Sql "select count(*) from audit_records where id in ('$oldIds') and actor_display_name is null and case_id is null") -eq $oldCount)

    # --- 5. acceptance helpers with their new default roles ---------------------------------
    $newCase = New-Case 'new'
    Check 'Invoke-TestApi (default roles) creates a case' ([bool]$newCase) $newCase
    $m2 = Join-Path $env:USERPROFILE '.m2\repository'
    $cp = @(
        "$m2\org\apache\pdfbox\pdfbox\3.0.2\pdfbox-3.0.2.jar",
        "$m2\org\apache\pdfbox\fontbox\3.0.2\fontbox-3.0.2.jar",
        "$m2\org\apache\pdfbox\pdfbox-io\3.0.2\pdfbox-io-3.0.2.jar",
        (Get-ChildItem "$m2\commons-logging\commons-logging" -Recurse -Filter 'commons-logging-*.jar' | Select-Object -First 1).FullName
    ) -join ';'
    $pdf = Join-Path $runDir 'test.pdf'
    & java -cp $cp (Join-Path $repoRoot 'test-tools\no-redacted-values\MakeFakeValuePdf.java') $pdf
    if ($LASTEXITCODE -ne 0) { throw "test PDF was not written" }
    $uploaded = & $sendDoc -PackageId $newCase -FilePath $pdf
    Check 'Send-TestDocument (default roles) uploads a document' ($null -ne $uploaded)
    $before = & (Join-Path $helpers 'Set-TestReviewPolicy.ps1') -TargetReviewMode UNCERTAIN_ONLY
    & (Join-Path $helpers 'Restore-TestReviewPolicy.ps1') -OriginalPolicy $before | Out-Null
    $strict = & (Join-Path $helpers 'Set-TestReviewPolicy.ps1') -TargetReviewMode REVIEW_ALL
    & (Join-Path $helpers 'Restore-TestReviewPolicy.ps1') -OriginalPolicy $strict | Out-Null

    # --- 6. new rows and export --------------------------------------------------------------
    $created = Sql "select coalesce(actor_display_name,'<null>') || ' ' || coalesce(case_id,'<null>') from audit_records where action = 'PACKAGE_CREATED' and resource_id = '$newCase'"
    Check 'case action records display name (LOCAL: username) and case ID' ($created -eq "demo-user $newCase") $created
    $policyRows = Sql "select coalesce(actor_display_name,'<null>') || ' | ' || detail from audit_records where action = 'SENSITIVE_DATA_POLICY_CHANGED' and actor_display_name is not null order by occurred_at"
    $policyLines = @($policyRows -split "`n")
    Write-Host "New policy-change details:"; $policyLines | ForEach-Object { Write-Host "    $_" }
    Check 'policy change: old keys first, then types, preset and previous' `
        ($policyLines.Count -ge 2 -and @($policyLines | Where-Object { $_ -match '^demo-user \| Sensitive-data policy changed: enabledCount=\d, threshold=0\.\d\d, reviewMode=[A-Z_]+, maskSsnLastFour=(true|false), enabledTypes=[A-Z_,]+, preset=(STRICT|BALANCED|LIGHT|CUSTOM), previous=\[enabledCount=' }).Count -eq $policyLines.Count)
    Check 'policy change: STRICT recorded after BALANCED, then BALANCED after STRICT' `
        (@($policyLines | Where-Object { $_ -match 'reviewMode=REVIEW_ALL.*preset=STRICT, previous=\[.*preset=BALANCED\]$' }).Count -ge 1 -and
         @($policyLines | Where-Object { $_ -match 'reviewMode=UNCERTAIN_ONLY.*preset=BALANCED, previous=\[.*preset=STRICT\]$' }).Count -ge 1)
    $systemNamed = Sql "select count(*) from audit_records where actor_id = '00000000-0000-0000-0000-000000000000' and actor_display_name is not null"
    Check 'no system record carries a display name' ($systemNamed -eq '0')

    $json = & $invokeApi -Method GET -Path '/api/v1/audit-records/export?format=json&includeRoutineActivity=true'
    $json | ConvertTo-Json -Depth 4 | Set-Content -Encoding utf8 (Join-Path $runDir 'audit-export.json')
    $oldInJson = @($json | Where-Object { $oldIds.Contains($_.id) })
    Check 'export JSON: old rows have actorDisplayName and caseId empty' `
        ($oldInJson.Count -eq [int]$oldCount -and @($oldInJson | Where-Object { $null -ne $_.actorDisplayName -or $null -ne $_.caseId }).Count -eq 0) "old rows in export=$($oldInJson.Count)"
    $newInJson = @($json | Where-Object { $_.action -eq 'PACKAGE_CREATED' -and $_.resourceId -eq $newCase })
    Check 'export JSON: new case row has actorDisplayName and caseId' `
        ($newInJson.Count -eq 1 -and $newInJson[0].actorDisplayName -eq 'demo-user' -and $newInJson[0].caseId -eq $newCase)
    $csv = & $invokeApi -Method GET -Path '/api/v1/audit-records/export?format=csv&includeRoutineActivity=true'
    $csv | Set-Content -Encoding utf8 (Join-Path $runDir 'audit-export.csv')
    $header = ($csv -split "`r`n")[0]
    Check 'export CSV: original columns, then actorDisplayName,caseId' `
        ($header -eq 'occurredAt,action,outcome,actorId,resourceType,resourceId,correlationId,detail,actorDisplayName,caseId') $header
    Check 'export CSV: new case row ends with display name and case ID' ($csv -match ('"demo-user","' + $newCase + '"'))

    $failed = @($checks | Where-Object { -not $_.pass }).Count
    [pscustomobject]@{ oldCase = $oldCase; newCase = $newCase; oldAuditRows = $oldCount; oldPolicyDetail = $oldPolicyDetail
        newPolicyDetails = $policyLines; checks = $checks } | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 (Join-Path $runDir 'summary.json')
    Write-Host ""
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
            'SECUREDOC_BACKEND_URL', 'SECUREDOC_TENANT_ID', 'SECUREDOC_ACTOR_ID') {
        Remove-Item "Env:$name" -ErrorAction SilentlyContinue
    }
}
exit $exitCode
