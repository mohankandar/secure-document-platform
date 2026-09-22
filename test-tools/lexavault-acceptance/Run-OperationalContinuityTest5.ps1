#requires -Version 5.1
<#
.SYNOPSIS
    LexaVault Operational Continuity Acceptance Test 5 - reusable, Kiro-free runner.

.DESCRIPTION
    Proves that when a FAILED / Technical Attention package reaches the end of its configured
    technical retention period, the REAL lifecycle maintenance:
      - purges internal processing artifacts
      - hides the package from the operational Dashboard (dashboard_hidden_at populated)
      - auto-closes the active processing/support incident (RETENTION_EXPIRED -> AUTO_CLOSED)
    while the durable record remains truthful and discoverable:
      - document_packages row still exists, status still FAILED, attempt_count still 3
      - the PROCESSING_RECOVERY_EXHAUSTED reason / incident timeline / audit history remain
      - no processing retry/reprocess occurs

    Driven against the REAL running application (API + DB + UI). No application/backend/frontend code is
    modified and no production test hooks are added. Only ONE disposable package's technical_hold_until
    is moved into the past (test tooling), then the genuine product endpoint performs the purge/hide/
    incident-close. Tenant-wide policy is not changed; status/artifacts/dashboard/incident are never
    changed by hand.

    Part 1 reuses the verified Test 2 exhaustion flow (accelerated lease expiry).

.PARAMETER SkipBackendManagement
    Diagnostics only; the test must control the backend to simulate node loss for the exhaustion setup.

.PARAMETER KeepTestData
    Retain the disposable package after the run. Default removes it on PASS via Remove-TestPackage.ps1;
    FAIL/BLOCKED always retains it for investigation.

.NOTES
    Configuration + prerequisites are centralized in helpers/Common-Config.ps1. No secrets embedded.
#>
[CmdletBinding()]
param(
    [switch] $SkipBackendManagement,
    [switch] $KeepTestData
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$ToolRoot    = $PSScriptRoot
$HelpersDir  = Join-Path $ToolRoot 'helpers'

. (Join-Path $HelpersDir 'Common-Config.ps1')
$Cfg = Get-LvConfig

$HistorySpec = 'e2e/operational-continuity/technical-attention-retention-history.spec.ts'
$EvidenceDir = Join-Path $ToolRoot ('evidence\{0}-test5' -f (Get-Date -Format 'yyyyMMdd-HHmmss'))

$InvokeApi     = Join-Path $HelpersDir 'Invoke-TestApi.ps1'
$GetLease      = Join-Path $HelpersDir 'Get-PackageProcessingLease.ps1'
$WaitState     = Join-Path $HelpersDir 'Wait-ForPackageState.ps1'
$ExpireLease   = Join-Path $HelpersDir 'Expire-PackageLease.ps1'
$RunQuery      = Join-Path $HelpersDir 'Invoke-TestDbQuery.ps1'
$SendDoc       = Join-Path $HelpersDir 'Send-TestDocument.ps1'
$RemovePackage = Join-Path $HelpersDir 'Remove-TestPackage.ps1'

$BackendUrl  = $Cfg.BackendUrl
$FrontendDir = $Cfg.FrontendDir
$JarPath     = $Cfg.JarPath
$FixturePath = $Cfg.FixturePath

New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
$TranscriptPath = Join-Path $EvidenceDir 'run.log'
try { Start-Transcript -Path $TranscriptPath -Force | Out-Null; $script:Transcribing = $true }
catch { $script:Transcribing = $false }

$script:Result = 'FAIL'
$script:Blocker = $null
$script:PackageId = $null
$script:IncidentId = $null
$script:Contract = $null
$script:BackendProcess = $null
$script:Evidence = [ordered]@{
    packageId              = $null
    incidentId             = $null
    beforeStatus           = $null
    beforeAttempt          = $null
    beforeReason           = $null
    beforeHold             = $null
    beforeArtifactsTotal   = $null
    beforeArtifactsLive    = $null
    beforeDashboardHidden  = $null
    beforeIncidentStatus   = $null
    afterPackageExists     = $null
    afterStatus            = $null
    afterAttempt           = $null
    afterArtifactsLive     = $null
    afterArtifactsDeleted  = $null
    afterDashboardHiddenAt = $null
    afterIncidentStatus    = $null
    reasonPreserved        = $null
    auditPreserved         = $null
    incidentHistoryKept    = $null
    noReprocess            = $null
    playwright             = 'NOT_RUN'
    dashboardDisappeared   = $null
    supportHistoryVisible  = $null
    tracePath              = $null
    screenshotPath         = $null
}

function Write-Step([string] $msg) { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }
function Write-Info([string] $msg) { Write-Host $msg }
function Fail([string] $msg) { throw $msg }

function Test-BackendReady {
    try { return ((Invoke-RestMethod -Method GET -Uri "$BackendUrl/actuator/health" -TimeoutSec 5).status -eq 'UP') }
    catch { return $false }
}
function Wait-BackendReady([int] $TimeoutSeconds = 180) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) { if (Test-BackendReady) { Write-Info "  backend is UP"; return } ; Start-Sleep -Seconds 3 }
    Fail "Backend did not become ready within ${TimeoutSeconds}s at $BackendUrl/actuator/health"
}
function Wait-BackendStopped([int] $TimeoutSeconds = 60) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) { if (-not (Test-BackendReady)) { Write-Info "  backend is DOWN"; return } ; Start-Sleep -Seconds 2 }
    Fail "Backend still responding after ${TimeoutSeconds}s; expected it to be stopped."
}
function Start-Backend {
    if ($SkipBackendManagement) { Write-Info "  (SkipBackendManagement) expecting externally-managed backend UP"; Wait-BackendReady; return }
    if (Test-BackendReady) { Fail "A backend is already running at $BackendUrl. Stop it first, or pass -SkipBackendManagement." }
    if (-not (Test-Path $JarPath)) { Fail "Backend jar not found: $JarPath (build it first)." }
    $stdout = Join-Path $EvidenceDir 'backend.out.log'
    $stderr = Join-Path $EvidenceDir 'backend.err.log'
    $javaArgs = @('-jar', $JarPath, '--spring.profiles.active=local')
    Write-Info "  starting backend: java $($javaArgs -join ' ')"
    $script:BackendProcess = Start-Process -FilePath 'java' -ArgumentList $javaArgs `
        -WorkingDirectory $Cfg.RepoRoot -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    Wait-BackendReady
}
function Stop-Backend {
    if ($SkipBackendManagement) { Fail "SkipBackendManagement is set but the test must stop the backend to simulate node loss." }
    if ($null -ne $script:BackendProcess -and -not $script:BackendProcess.HasExited) {
        Write-Info "  stopping backend PID $($script:BackendProcess.Id) (simulated node loss)"
        Stop-Process -Id $script:BackendProcess.Id -Force
        try { $script:BackendProcess.WaitForExit(30000) | Out-Null } catch { }
    }
    Wait-BackendStopped
}
function Invoke-RecoveryToAttempt([int] $TargetAttempt) {
    Write-Info "  expiring the package lease (only lease timestamps; attempt_count untouched)"
    & $ExpireLease -PackageId $script:PackageId | Out-Null
    Write-Info "  restarting backend; the real recovery scheduler will reclaim -> attempt $TargetAttempt"
    Start-Backend | Out-Null
    $rec = & $WaitState -PackageId $script:PackageId -MinAttemptCount $TargetAttempt `
        -ExpectedStatuses @('PROCESSING','PENDING_REVIEW','COMPLETED','EXPORTED') -TimeoutSeconds 150 -PollIntervalSeconds 2
    if ($rec.attemptCount -ne $TargetAttempt) { Fail "Expected attempt_count=$TargetAttempt but observed $($rec.attemptCount)." }
    if ($rec.status -ne 'PROCESSING') {
        $script:Blocker = "Package left PROCESSING (status=$($rec.status)) at attempt $TargetAttempt before node loss. Re-run. No faking."
        $script:Result = 'BLOCKED'
        throw [System.OperationCanceledException]::new('BLOCKED')
    }
    Stop-Backend | Out-Null
    Write-Output $rec
}

function Get-Scalar([string] $sql) {
    $rows = @(& $RunQuery -Sql $sql)
    if ($rows.Count -eq 0) { return $null }
    $v = "$($rows[-1])".Trim()
    if ($v -eq '') { return $null }
    return $v
}
function Get-IntScalar([string] $sql) {
    $v = Get-Scalar $sql
    if ($null -eq $v) { return 0 }
    return [int]$v
}

try {
    Write-Step "PHASE 0 - Prerequisites"
    $prereq = Test-LvPrerequisites -Config $Cfg
    foreach ($c in $prereq.checks) { Write-Info ("  [{0}] {1} - {2}" -f $(if ($c.ok) { 'OK  ' } else { 'FAIL' }), $c.name, $c.detail) }
    if (-not $prereq.ok) { Fail ("Prerequisite check failed:`n  - " + ($prereq.failures -join "`n  - ")) }
    if ($Cfg.TesseractPath) { $env:SECUREDOC_OCR_TESSERACT_PATH = $Cfg.TesseractPath }

    Write-Step "PHASE 1 - Drive a fresh package to exhaustion (FAILED / Technical Attention)"
    Start-Backend
    $ids = New-LvTestIdentifiers
    $script:Contract = $ids.contract_number
    $createBody = @{
        packageType = 'MANUAL_UPLOAD'
        businessIdentifiers = @{ contract_number = $ids.contract_number; member_number = $ids.member_number; service_entity = $ids.service_entity }
        metadata = @{ source = 'operational-continuity-acceptance-test-5' }
    }
    $created = & $InvokeApi -Method POST -Path '/api/v1/packages' -Body $createBody
    $script:PackageId = $created.packageId
    if (-not $script:PackageId) { Fail "Package creation did not return a packageId." }
    $script:Evidence.packageId = $script:PackageId
    Write-Info "  created package $($script:PackageId) (contract $($script:Contract))"

    $uploaded = & $SendDoc -PackageId $script:PackageId -FilePath $FixturePath
    Write-Info "  uploaded fixture: documentId=$($uploaded.documentId) status=$($uploaded.status)"
    $submitResp = & $InvokeApi -Method POST -Path "/api/v1/packages/$($script:PackageId)/submit"
    Write-Info "  submitted, status=$($submitResp.status)"

    $lease1 = & $WaitState -PackageId $script:PackageId -MinAttemptCount 1 `
        -ExpectedStatuses @('PROCESSING','PENDING_REVIEW','COMPLETED','EXPORTED') -TimeoutSeconds 90 -PollIntervalSeconds 2
    if ($lease1.status -ne 'PROCESSING' -or $lease1.attemptCount -ne 1) {
        $script:Blocker = "Package status=$($lease1.status) attempt=$($lease1.attemptCount) before attempt 1 could be frozen. Re-run."
        $script:Result = 'BLOCKED'; return
    }
    Stop-Backend
    $lease2 = Invoke-RecoveryToAttempt -TargetAttempt 2
    Write-Info "  attempt 2: attempt_count=$($lease2.attemptCount) fencing=$($lease2.fencingToken)"
    $lease3 = Invoke-RecoveryToAttempt -TargetAttempt 3
    Write-Info "  attempt 3: attempt_count=$($lease3.attemptCount) fencing=$($lease3.fencingToken)"

    Write-Info "  expiring attempt-3 lease and restarting to trigger exhaustion"
    & $ExpireLease -PackageId $script:PackageId | Out-Null
    Start-Backend
    $failed = & $WaitState -PackageId $script:PackageId -ExpectedStatuses @('FAILED') -TimeoutSeconds 120 -PollIntervalSeconds 2
    if ($failed.attemptCount -ne 3) { Fail "Expected attempt_count=3 at exhaustion, observed $($failed.attemptCount)." }

    Write-Step "PHASE 2 - Record BEFORE-retention evidence"
    $script:IncidentId = Get-Scalar "SELECT id FROM processing_incidents WHERE package_id = '$($script:PackageId)' ORDER BY last_observed_at DESC LIMIT 1;"
    $script:Evidence.incidentId = $script:IncidentId
    $script:Evidence.beforeStatus = $failed.status
    $script:Evidence.beforeAttempt = $failed.attemptCount
    $script:Evidence.beforeReason = Get-Scalar "SELECT reason_code FROM processing_incidents WHERE package_id = '$($script:PackageId)' ORDER BY last_observed_at DESC LIMIT 1;"
    $script:Evidence.beforeHold = Get-Scalar "SELECT technical_hold_until FROM package_lifecycles WHERE package_id = '$($script:PackageId)';"
    $script:Evidence.beforeArtifactsTotal = Get-IntScalar "SELECT COUNT(*) FROM artifacts a JOIN documents d ON d.id=a.document_id WHERE d.package_id = '$($script:PackageId)';"
    $script:Evidence.beforeArtifactsLive  = Get-IntScalar "SELECT COUNT(*) FROM artifacts a JOIN documents d ON d.id=a.document_id WHERE d.package_id = '$($script:PackageId)' AND a.status <> 'DELETED';"
    $script:Evidence.beforeDashboardHidden = Get-Scalar "SELECT dashboard_hidden_at FROM document_packages WHERE id = '$($script:PackageId)';"
    $script:Evidence.beforeIncidentStatus = Get-Scalar "SELECT status FROM processing_incidents WHERE package_id = '$($script:PackageId)' ORDER BY last_observed_at DESC LIMIT 1;"
    Write-Info "  BEFORE: status=$($script:Evidence.beforeStatus) attempt=$($script:Evidence.beforeAttempt) reason=$($script:Evidence.beforeReason) hold=$($script:Evidence.beforeHold)"
    Write-Info "          artifacts total=$($script:Evidence.beforeArtifactsTotal) live=$($script:Evidence.beforeArtifactsLive) dashboardHidden=$($script:Evidence.beforeDashboardHidden) incident=$($script:Evidence.beforeIncidentStatus)"
    if ($script:Evidence.beforeReason -ne 'PROCESSING_RECOVERY_EXHAUSTED') { Fail "Expected reason PROCESSING_RECOVERY_EXHAUSTED before retention." }
    if ($null -ne $script:Evidence.beforeDashboardHidden) { Fail "Package already hidden from Dashboard before retention." }
    if ($script:Evidence.beforeArtifactsLive -lt 1) { Fail "Expected at least one live processing artifact before retention." }

    Write-Step "PHASE 3 - Accelerate ONLY this package's technical hold into the past, then run the REAL purge"
    # Move only this disposable package's technical_hold_until into the past. Status/artifacts/dashboard/
    # incident are NOT touched by hand; the product performs all cleanup via the real endpoint.
    & $RunQuery -Sql "UPDATE package_lifecycles SET technical_hold_until = clock_timestamp() - interval '1 hour' WHERE package_id = '$($script:PackageId)';" | Out-Null
    Write-Info "  technical_hold_until moved into the past for $($script:PackageId)"
    $purge = & $InvokeApi -Method POST -Path '/api/v1/lifecycle/purge-due?limit=500'
    Write-Info "  purge-due result: $($purge | ConvertTo-Json -Compress)"

    Write-Step "PHASE 4 - Assertions AFTER retention"
    $exists = Get-IntScalar "SELECT COUNT(*) FROM document_packages WHERE id = '$($script:PackageId)';"
    $script:Evidence.afterPackageExists = $(if ($exists -ge 1) { 'YES' } else { 'NO' })
    if ($exists -lt 1) { Fail "DURABLE VIOLATION: document_packages row was deleted by retention purge." }

    $after = & $GetLease -PackageId $script:PackageId
    $script:Evidence.afterStatus = $after.status
    $script:Evidence.afterAttempt = $after.attemptCount
    $script:Evidence.afterArtifactsLive    = Get-IntScalar "SELECT COUNT(*) FROM artifacts a JOIN documents d ON d.id=a.document_id WHERE d.package_id = '$($script:PackageId)' AND a.status <> 'DELETED';"
    $script:Evidence.afterArtifactsDeleted = Get-IntScalar "SELECT COUNT(*) FROM artifacts a JOIN documents d ON d.id=a.document_id WHERE d.package_id = '$($script:PackageId)' AND a.status = 'DELETED';"
    $script:Evidence.afterDashboardHiddenAt = Get-Scalar "SELECT dashboard_hidden_at FROM document_packages WHERE id = '$($script:PackageId)';"
    $script:Evidence.afterIncidentStatus = Get-Scalar "SELECT status FROM processing_incidents WHERE package_id = '$($script:PackageId)' ORDER BY last_observed_at DESC LIMIT 1;"
    Write-Info "  AFTER: status=$($after.status) attempt=$($after.attemptCount) artifactsLive=$($script:Evidence.afterArtifactsLive) artifactsDeleted=$($script:Evidence.afterArtifactsDeleted) dashboardHiddenAt=$($script:Evidence.afterDashboardHiddenAt) incident=$($script:Evidence.afterIncidentStatus)"

    if ($after.status -ne 'FAILED') { Fail "Package status changed to $($after.status) after retention; expected FAILED (durable outcome)." }
    if ($after.attemptCount -ne 3) { Fail "attempt_count changed to $($after.attemptCount) after retention; expected 3." }
    if ($script:Evidence.afterArtifactsLive -ne 0) { Fail "Processing artifacts were not purged (live=$($script:Evidence.afterArtifactsLive))." }
    if ($null -eq $script:Evidence.afterDashboardHiddenAt) { Fail "dashboard_hidden_at not populated; package not hidden from operational Dashboard." }

    # No reprocess: attempt stays 3 and status FAILED (never re-entered PROCESSING).
    $script:Evidence.noReprocess = $(if ($after.attemptCount -eq 3 -and $after.status -eq 'FAILED') { 'YES' } else { 'NO' })
    if ($script:Evidence.noReprocess -ne 'YES') { Fail "Unintended reprocessing detected after retention." }

    # Durable history checks.
    $reasonAfter = Get-Scalar "SELECT reason_code FROM processing_incidents WHERE package_id = '$($script:PackageId)' ORDER BY last_observed_at DESC LIMIT 1;"
    $incidentPresent = ($null -ne (Get-Scalar "SELECT id FROM processing_incidents WHERE package_id = '$($script:PackageId)' LIMIT 1;"))
    $exhaustEvents = Get-IntScalar "SELECT COUNT(*) FROM processing_incident_events e JOIN processing_incidents i ON i.id=e.incident_id WHERE i.package_id = '$($script:PackageId)' AND (e.reason_code='PROCESSING_RECOVERY_EXHAUSTED' OR e.event_type='PROCESSING_RECOVERY_EXHAUSTED');"
    $auditCount = Get-IntScalar "SELECT COUNT(*) FROM audit_records WHERE resource_id = '$($script:PackageId)';"
    $script:Evidence.reasonPreserved = $(if ($reasonAfter -eq 'PROCESSING_RECOVERY_EXHAUSTED' -or $exhaustEvents -gt 0) { 'YES' } else { 'NO' })
    $script:Evidence.incidentHistoryKept = $(if ($incidentPresent) { 'YES' } else { 'NO' })
    $script:Evidence.auditPreserved = $(if ($auditCount -gt 0) { 'YES' } else { 'NO' })
    Write-Info "  history: reasonPreserved=$($script:Evidence.reasonPreserved) incidentKept=$($script:Evidence.incidentHistoryKept) auditRecords=$auditCount exhaustionEvents=$exhaustEvents"
    if ($script:Evidence.reasonPreserved -ne 'YES') { Fail "DURABLE VIOLATION: PROCESSING_RECOVERY_EXHAUSTED no longer discoverable after retention." }
    if ($script:Evidence.incidentHistoryKept -ne 'YES') { Fail "DURABLE VIOLATION: incident record deleted by retention." }
    if ($script:Evidence.auditPreserved -ne 'YES') { Fail "DURABLE VIOLATION: audit history for the package was deleted by retention." }

    Write-Step "PHASE 5 - Playwright: Dashboard disappearance + Support history durability"
    $env:LV_OC_CONTRACT = $script:Contract
    $env:LV_OC_INCIDENT_ID = $script:IncidentId
    Push-Location $FrontendDir
    try {
        & npx playwright test $HistorySpec --reporter=list | Out-Host
        $pwExit = $LASTEXITCODE
    } finally {
        Pop-Location
        Remove-Item Env:\LV_OC_CONTRACT -ErrorAction SilentlyContinue
        Remove-Item Env:\LV_OC_INCIDENT_ID -ErrorAction SilentlyContinue
    }
    $pwResults = Join-Path $FrontendDir 'test-results'
    if (Test-Path $pwResults) {
        $trace = Get-ChildItem -Path $pwResults -Recurse -Filter 'trace.zip' -ErrorAction SilentlyContinue | Select-Object -First 1
        $shot  = Get-ChildItem -Path $pwResults -Recurse -Include '*.png' -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($null -ne $trace) { $script:Evidence.tracePath = $trace.FullName }
        if ($null -ne $shot)  { $script:Evidence.screenshotPath = $shot.FullName }
    }
    if ($pwExit -ne 0) {
        $script:Evidence.playwright = 'FAIL'
        $script:Evidence.dashboardDisappeared = 'NO'
        $script:Evidence.supportHistoryVisible = 'UNKNOWN'
        Fail "Playwright retention/history spec failed (exit $pwExit). See $pwResults."
    }
    $script:Evidence.playwright = 'PASS'
    $script:Evidence.dashboardDisappeared = 'YES'
    $script:Evidence.supportHistoryVisible = 'YES'

    $script:Result = 'PASS'
}
catch {
    if ($script:Result -ne 'BLOCKED') { $script:Result = 'FAIL'; $script:Blocker = $_.Exception.Message }
    Write-Host "`n[$($script:Result)] $($_.Exception.Message)" -ForegroundColor Red
}
finally {
    $testDataDisposition = 'RETAINED'
    if ($script:PackageId) {
        if ($script:Result -eq 'PASS' -and -not $KeepTestData) {
            try { & $RemovePackage -PackageId $script:PackageId | Out-Null; $testDataDisposition = 'REMOVED' }
            catch { Write-Host "  cleanup failed: $($_.Exception.Message)" -ForegroundColor Yellow; $testDataDisposition = 'RETAINED (cleanup failed)' }
        } elseif ($KeepTestData) { $testDataDisposition = 'RETAINED (-KeepTestData)' }
        else { $testDataDisposition = "RETAINED ($($script:Result) - kept for investigation)" }
    } else { $testDataDisposition = 'NONE (no package created)' }

    if (-not $SkipBackendManagement -and $null -ne $script:BackendProcess -and -not $script:BackendProcess.HasExited) {
        Write-Info "  stopping backend PID $($script:BackendProcess.Id)"
        try { Stop-Process -Id $script:BackendProcess.Id -Force } catch { }
    }

    Write-Host "`n========= OPERATIONAL CONTINUITY ACCEPTANCE TEST 5 (RETENTION EXPIRY) =========" -ForegroundColor Cyan
    Write-Host ("RESULT: {0}" -f $script:Result)
    if ($script:Blocker) { Write-Host ("REASON: {0}" -f $script:Blocker) }
    Write-Host ("`nPackage ID: {0}" -f $script:Evidence.packageId)
    Write-Host "`nBefore retention:"
    Write-Host ("  status               : {0}" -f $script:Evidence.beforeStatus)
    Write-Host ("  attempt_count        : {0}" -f $script:Evidence.beforeAttempt)
    Write-Host ("  technical_hold_until : {0}" -f $script:Evidence.beforeHold)
    Write-Host ("  artifacts total/live : {0} / {1}" -f $script:Evidence.beforeArtifactsTotal, $script:Evidence.beforeArtifactsLive)
    Write-Host ("  dashboard visible    : {0}" -f $(if ($null -eq $script:Evidence.beforeDashboardHidden) { 'YES (not hidden)' } else { 'NO' }))
    Write-Host ("  incident status      : {0}" -f $script:Evidence.beforeIncidentStatus)
    Write-Host "`nAfter retention:"
    Write-Host ("  package row exists   : {0}" -f $script:Evidence.afterPackageExists)
    Write-Host ("  status               : {0}" -f $script:Evidence.afterStatus)
    Write-Host ("  attempt_count        : {0}" -f $script:Evidence.afterAttempt)
    Write-Host ("  artifacts live/deleted: {0} / {1}" -f $script:Evidence.afterArtifactsLive, $script:Evidence.afterArtifactsDeleted)
    Write-Host ("  dashboard_hidden_at  : {0}" -f $script:Evidence.afterDashboardHiddenAt)
    Write-Host ("  dashboard visible    : {0}" -f $(if ($null -eq $script:Evidence.afterDashboardHiddenAt) { 'YES' } else { 'NO (hidden)' }))
    Write-Host ("  incident status      : {0}" -f $script:Evidence.afterIncidentStatus)
    Write-Host ("  reason/history kept  : {0}" -f $script:Evidence.reasonPreserved)
    Write-Host ("  audit/history kept   : {0}" -f $script:Evidence.auditPreserved)
    Write-Host ("  incident record kept : {0}" -f $script:Evidence.incidentHistoryKept)
    Write-Host ("`nNo reprocessing : {0}" -f $script:Evidence.noReprocess)
    Write-Host "`nPlaywright:"
    Write-Host ("  result                       : {0}" -f $script:Evidence.playwright)
    Write-Host ("  dashboard disappearance      : {0}" -f $script:Evidence.dashboardDisappeared)
    Write-Host ("  historical support visibility: {0}" -f $script:Evidence.supportHistoryVisible)
    Write-Host ("  trace                        : {0}" -f $(if ($script:Evidence.tracePath) { $script:Evidence.tracePath } else { '(none; only on failure)' }))
    Write-Host ("  screenshot                   : {0}" -f $(if ($script:Evidence.screenshotPath) { $script:Evidence.screenshotPath } else { '(none; only on failure)' }))
    Write-Host ("`nCleanup: {0}" -f $testDataDisposition)
    Write-Host ("Evidence directory : {0}" -f $EvidenceDir)
    Write-Host "===============================================================================" -ForegroundColor Cyan

    $summary = [ordered]@{ result = $script:Result; reason = $script:Blocker; evidence = $script:Evidence; testData = $testDataDisposition }
    $summary | ConvertTo-Json -Depth 8 | Set-Content -Path (Join-Path $EvidenceDir 'result.json') -Encoding UTF8

    if ($script:Transcribing) { try { Stop-Transcript | Out-Null } catch { } }

    if ($script:Result -eq 'PASS') { exit 0 }
    elseif ($script:Result -eq 'BLOCKED') { exit 3 }
    else { exit 1 }
}
