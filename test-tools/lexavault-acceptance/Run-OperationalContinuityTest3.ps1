#requires -Version 5.1
<#
.SYNOPSIS
    LexaVault Operational Continuity Acceptance Test 3 - reusable, Kiro-free runner.

.DESCRIPTION
    Proves that after a package reaches Technical Attention because its processing recovery budget was
    exhausted, Tenant IT's "Reprocess after fix" starts a NEW processing cycle with attempt_count = 1
    (NOT 4), while preserving the previous failure/incident/history.

        attempt 1 -> recovery 2 -> recovery 3 -> attempt-3 exhaustion (FAILED / Technical Attention,
        reason PROCESSING_RECOVERY_EXHAUSTED, incident OPEN)
          -> Tenant IT "Reprocess after fix" (real Support Incident UI action)
          -> NEW cycle attempt_count = 1 (fresh budget), old exhaustion history preserved
          -> cycle continues normally (completes, or Manual Review then completes)

    Driven against the REAL running application (API + DB + UI). No application/backend/frontend code is
    modified and no production test hooks are added. attempt_count is NEVER set by the harness: the
    production recovery scheduler performs 1->2->3 + exhaustion, and the production Support Incident
    REPROCESS action (RetryTechnicalFailureService.executeSupportOverride -> freshCycle) resets to 1.

    Part 1 reuses the verified Test 2 exhaustion flow (accelerated lease expiry). NaturalLeaseExpiry is
    intentionally NOT provided here: Test 2 already independently verified natural-expiry exhaustion.

.PARAMETER SkipBackendManagement
    Diagnostics only; the test must control the backend to simulate node loss for the exhaustion setup.

.PARAMETER KeepTestData
    Retain the disposable package after the run. Default removes it on PASS via Remove-TestPackage.ps1;
    FAIL/BLOCKED always retains it for investigation.

.NOTES
    Configuration + prerequisites are centralized in helpers/Common-Config.ps1. No secrets embedded.
    A transcript is written to the per-run evidence directory; evidence does not depend on terminal buffers.
#>
[CmdletBinding()]
param(
    [switch] $SkipBackendManagement,
    [switch] $KeepTestData
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# ---------------------------------------------------------------------------
# Centralized configuration + helper paths
# ---------------------------------------------------------------------------
$ToolRoot    = $PSScriptRoot
$HelpersDir  = Join-Path $ToolRoot 'helpers'

. (Join-Path $HelpersDir 'Common-Config.ps1')
$Cfg = Get-LvConfig

$ReprocessSpec = 'e2e/operational-continuity/reprocess-after-fix-fresh-budget.spec.ts'
$ReviewSpec    = 'e2e/operational-continuity/recovery-review-preserves-attempt.spec.ts'
$EvidenceDir   = Join-Path $ToolRoot ('evidence\{0}-test3' -f (Get-Date -Format 'yyyyMMdd-HHmmss'))

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

$LeaseDurationMs = [int](Get-LvEnvOrDefault 'SECUREDOC_PROCESSING_LEASE_DURATION_MS' '30000')
$MaxAttempts     = [int](Get-LvEnvOrDefault 'SECUREDOC_PROCESSING_MAX_ATTEMPTS' '3')

# ---------------------------------------------------------------------------
# Evidence + result tracking + file logging (from the start)
# ---------------------------------------------------------------------------
New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
$TranscriptPath = Join-Path $EvidenceDir 'run.log'
try { Start-Transcript -Path $TranscriptPath -Force | Out-Null; $script:Transcribing = $true }
catch { $script:Transcribing = $false }

$script:Result = 'FAIL'
$script:Blocker = $null
$script:PackageId = $null
$script:IncidentId = $null
$script:BackendProcess = $null
$script:Evidence = [ordered]@{
    packageId                 = $null
    incidentId                = $null
    preStatus                 = $null
    preAttempt                = $null
    preReasonCode             = $null
    preIncidentStatus         = $null
    postAttempt               = $null
    postFencing               = $null
    postPackageStatus         = $null
    finalStatus               = $null
    oldExhaustionIncidentKept = $null
    oldReasonDiscoverable     = $null
    incidentStatusAfter       = $null
    playwright                = 'NOT_RUN'
    reviewPlaywright          = 'N/A'
    actionUsed                = 'Support Incident: Reprocess after fix'
    tracePath                 = $null
    screenshotPath            = $null
}

function Write-Step([string] $msg) { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }
function Write-Info([string] $msg) { Write-Host $msg }
function Fail([string] $msg) { throw $msg }

# ---------------------------------------------------------------------------
# Backend lifecycle
# ---------------------------------------------------------------------------
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

# Recover from an expired lease to a target attempt, then stop the backend promptly while PROCESSING.
function Invoke-RecoveryToAttempt([int] $TargetAttempt) {
    Write-Info "  expiring the package lease (only lease timestamps; attempt_count untouched)"
    & $ExpireLease -PackageId $script:PackageId | Out-Null
    Write-Info "  restarting backend; the real recovery scheduler will reclaim -> attempt $TargetAttempt"
    Start-Backend | Out-Null
    $rec = & $WaitState -PackageId $script:PackageId -MinAttemptCount $TargetAttempt `
        -ExpectedStatuses @('PROCESSING','PENDING_REVIEW','COMPLETED','EXPORTED') -TimeoutSeconds 150 -PollIntervalSeconds 2
    if ($rec.attemptCount -ne $TargetAttempt) { Fail "Expected attempt_count=$TargetAttempt but observed $($rec.attemptCount)." }
    if ($rec.status -ne 'PROCESSING') {
        $script:Blocker = "Package left PROCESSING (status=$($rec.status)) at attempt $TargetAttempt before node loss could be simulated. Re-run. No status/attempt faked."
        $script:Result = 'BLOCKED'
        throw [System.OperationCanceledException]::new('BLOCKED')
    }
    Stop-Backend | Out-Null
    Write-Output $rec
}

function Get-IncidentForPackage {
    $rows = @(& $RunQuery -Sql "SELECT id, reason_code, status FROM processing_incidents WHERE package_id = '$($script:PackageId)' ORDER BY last_observed_at DESC;" -AsObjects -Columns @('id','reason_code','status'))
    if ($rows.Count -eq 0) { return $null }
    return $rows[0]
}

# ---------------------------------------------------------------------------
# Main scenario (guarded by finally for cleanup). No tenant policy manipulation required.
# ---------------------------------------------------------------------------
try {
    Write-Step "PHASE 0 - Prerequisites"
    $prereq = Test-LvPrerequisites -Config $Cfg
    foreach ($c in $prereq.checks) { Write-Info ("  [{0}] {1} - {2}" -f $(if ($c.ok) { 'OK  ' } else { 'FAIL' }), $c.name, $c.detail) }
    if (-not $prereq.ok) { Fail ("Prerequisite check failed:`n  - " + ($prereq.failures -join "`n  - ")) }
    if ($Cfg.TesseractPath) { $env:SECUREDOC_OCR_TESSERACT_PATH = $Cfg.TesseractPath }

    Write-Step "PHASE 1 - Drive a fresh package to exhaustion (FAILED / Technical Attention)"
    Start-Backend
    $ids = New-LvTestIdentifiers
    $createBody = @{
        packageType = 'MANUAL_UPLOAD'
        businessIdentifiers = @{ contract_number = $ids.contract_number; member_number = $ids.member_number; service_entity = $ids.service_entity }
        metadata = @{ source = 'operational-continuity-acceptance-test-3' }
    }
    $created = & $InvokeApi -Method POST -Path '/api/v1/packages' -Body $createBody
    $script:PackageId = $created.packageId
    if (-not $script:PackageId) { Fail "Package creation did not return a packageId." }
    $script:Evidence.packageId = $script:PackageId
    Write-Info "  created package $($script:PackageId) (contract $($ids.contract_number))"

    $uploaded = & $SendDoc -PackageId $script:PackageId -FilePath $FixturePath
    Write-Info "  uploaded fixture: documentId=$($uploaded.documentId) status=$($uploaded.status)"
    $submitResp = & $InvokeApi -Method POST -Path "/api/v1/packages/$($script:PackageId)/submit"
    Write-Info "  submitted package, status=$($submitResp.status)"

    $lease1 = & $WaitState -PackageId $script:PackageId -MinAttemptCount 1 `
        -ExpectedStatuses @('PROCESSING','PENDING_REVIEW','COMPLETED','EXPORTED') -TimeoutSeconds 90 -PollIntervalSeconds 2
    if ($lease1.status -ne 'PROCESSING' -or $lease1.attemptCount -ne 1) {
        $script:Blocker = "Package status=$($lease1.status) attempt=$($lease1.attemptCount) before attempt 1 could be frozen. No faking. Re-run."
        $script:Result = 'BLOCKED'; return
    }
    Write-Info "  attempt 1 confirmed: attempt_count=1 fencing=$($lease1.fencingToken)"
    Stop-Backend

    $lease2 = Invoke-RecoveryToAttempt -TargetAttempt 2
    Write-Info "  attempt 2: attempt_count=$($lease2.attemptCount) fencing=$($lease2.fencingToken)"
    $lease3 = Invoke-RecoveryToAttempt -TargetAttempt 3
    Write-Info "  attempt 3: attempt_count=$($lease3.attemptCount) fencing=$($lease3.fencingToken)"

    Write-Info "  expiring attempt-3 lease and restarting to trigger exhaustion (no attempt 4)"
    & $ExpireLease -PackageId $script:PackageId | Out-Null
    Start-Backend

    $failed = & $WaitState -PackageId $script:PackageId -ExpectedStatuses @('FAILED') -TimeoutSeconds 120 -PollIntervalSeconds 2
    if ($failed.attemptCount -ne 3) { Fail "Expected attempt_count=3 at exhaustion, observed $($failed.attemptCount)." }
    $incident = Get-IncidentForPackage
    if ($null -eq $incident) { Fail "No processing_incidents row found after exhaustion." }
    if ($incident.reason_code -ne 'PROCESSING_RECOVERY_EXHAUSTED') { Fail "Expected reason PROCESSING_RECOVERY_EXHAUSTED, got '$($incident.reason_code)'." }
    $script:IncidentId = $incident.id
    $script:Evidence.incidentId = $incident.id
    $script:Evidence.preStatus = $failed.status
    $script:Evidence.preAttempt = $failed.attemptCount
    $script:Evidence.preReasonCode = $incident.reason_code
    $script:Evidence.preIncidentStatus = $incident.status
    Write-Info "  PRE-REPROCESS: status=$($failed.status) attempt=$($failed.attemptCount) reason=$($incident.reason_code) incidentStatus=$($incident.status) incidentId=$($incident.id)"

    Write-Step "PHASE 3 - Playwright: Tenant IT 'Reprocess after fix' via the real Support Incident UI"
    $env:LV_OC_INCIDENT_ID = $script:IncidentId
    Push-Location $FrontendDir
    try {
        & npx playwright test $ReprocessSpec --reporter=list
        $pwExit = $LASTEXITCODE
    } finally {
        Pop-Location
        Remove-Item Env:\LV_OC_INCIDENT_ID -ErrorAction SilentlyContinue
    }
    $pwResults = Join-Path $FrontendDir 'test-results'
    if (Test-Path $pwResults) {
        $trace = Get-ChildItem -Path $pwResults -Recurse -Filter 'trace.zip' -ErrorAction SilentlyContinue | Select-Object -First 1
        $shot  = Get-ChildItem -Path $pwResults -Recurse -Include '*.png' -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($null -ne $trace) { $script:Evidence.tracePath = $trace.FullName }
        if ($null -ne $shot)  { $script:Evidence.screenshotPath = $shot.FullName }
    }
    if ($pwExit -ne 0) { $script:Evidence.playwright = 'FAIL'; Fail "Playwright reprocess spec failed (exit $pwExit). See $pwResults." }
    $script:Evidence.playwright = 'PASS'

    Write-Step "PHASE 4 - Fresh attempt budget assertion: new cycle attempt_count = 1 (never 4/3/null once owned)"
    # After the reprocess action the package leaves FAILED and a fresh cycle acquires a lease.
    $fresh = & $WaitState -PackageId $script:PackageId -MinAttemptCount 1 `
        -ExpectedStatuses @('PROCESSING','PENDING_REVIEW','COMPLETED','EXPORTED') -TimeoutSeconds 150 -PollIntervalSeconds 2
    $script:Evidence.postPackageStatus = $fresh.status
    $script:Evidence.postAttempt = $fresh.attemptCount
    $script:Evidence.postFencing = $fresh.fencingToken
    Write-Info "  POST-REPROCESS: status=$($fresh.status) attempt_count=$($fresh.attemptCount) fencing=$($fresh.fencingToken)"
    if ($null -eq $fresh.attemptCount) { Fail "New cycle attempt_count is null once processing ownership acquired." }
    if ($fresh.attemptCount -ne 1) { Fail "CENTRAL ASSERTION FAILED: new cycle attempt_count=$($fresh.attemptCount), expected 1 (must not be 3 or 4)." }

    # Allow the cycle to continue normally. If it needs Manual Review, complete it via the existing spec.
    Write-Step "PHASE 4b - Allow the new cycle to continue to a terminal state"
    $terminal = $null
    try {
        $terminal = & $WaitState -PackageId $script:PackageId -ExpectedStatuses @('COMPLETED','EXPORTED','READY_FOR_EXPORT','DELIVERED','PENDING_REVIEW') -TimeoutSeconds 180 -PollIntervalSeconds 3
    } catch {
        Write-Info "  (did not reach a terminal/review state within budget: $($_.Exception.Message))"
    }
    if ($null -ne $terminal -and $terminal.status -eq 'PENDING_REVIEW') {
        Write-Info "  new cycle requires Manual Review; completing via the existing review spec"
        $env:LV_OC_PACKAGE_ID = $script:PackageId
        Push-Location $FrontendDir
        try { & npx playwright test $ReviewSpec --reporter=list; $rvExit = $LASTEXITCODE }
        finally { Pop-Location; Remove-Item Env:\LV_OC_PACKAGE_ID -ErrorAction SilentlyContinue }
        $script:Evidence.reviewPlaywright = $(if ($rvExit -eq 0) { 'PASS' } else { 'FAIL' })
        if ($rvExit -ne 0) { Fail "Manual Review completion spec failed (exit $rvExit)." }
        $terminal = & $WaitState -PackageId $script:PackageId -ExpectedStatuses @('COMPLETED','EXPORTED','READY_FOR_EXPORT','DELIVERED') -TimeoutSeconds 180 -PollIntervalSeconds 3
    }
    $latest = & $GetLease -PackageId $script:PackageId
    $script:Evidence.finalStatus = $latest.status
    Write-Info "  final: status=$($latest.status) attempt_count=$($latest.attemptCount)"
    # attempt_count must never regress to 3/4 on the fresh cycle.
    if ($null -ne $latest.attemptCount -and $latest.attemptCount -gt 1) {
        Write-Info "  NOTE: fresh-cycle attempt_count advanced to $($latest.attemptCount) via normal processing (not a reset violation)."
    }

    Write-Step "PHASE 5 - History preservation: old exhaustion incident/history still discoverable"
    $incAfter = Get-IncidentForPackage
    $exhaustEventRows = @(& $RunQuery -Sql "SELECT COUNT(*) FROM processing_incident_events e JOIN processing_incidents i ON i.id = e.incident_id WHERE i.package_id = '$($script:PackageId)' AND (e.reason_code = 'PROCESSING_RECOVERY_EXHAUSTED' OR e.event_type = 'PROCESSING_RECOVERY_EXHAUSTED');")
    $exhaustEventCount = if ($exhaustEventRows.Count -gt 0) { [int]("$($exhaustEventRows[-1])".Trim()) } else { 0 }
    $incidentStillPresent = ($null -ne $incAfter)
    $reasonStillPresent = ($incidentStillPresent -and $incAfter.reason_code -eq 'PROCESSING_RECOVERY_EXHAUSTED') -or ($exhaustEventCount -gt 0)
    $script:Evidence.oldExhaustionIncidentKept = $(if ($incidentStillPresent) { 'YES' } else { 'NO' })
    $script:Evidence.oldReasonDiscoverable = $(if ($reasonStillPresent) { 'YES' } else { 'NO' })
    $script:Evidence.incidentStatusAfter = $(if ($incidentStillPresent) { $incAfter.status } else { '(none)' })
    Write-Info "  incident preserved=$($script:Evidence.oldExhaustionIncidentKept) reasonDiscoverable=$($script:Evidence.oldReasonDiscoverable) incidentStatusAfter=$($script:Evidence.incidentStatusAfter) exhaustionEvents=$exhaustEventCount"
    if (-not $incidentStillPresent) { Fail "HISTORY VIOLATION: exhaustion incident was deleted by reprocess." }
    if (-not $reasonStillPresent) { Fail "HISTORY VIOLATION: PROCESSING_RECOVERY_EXHAUSTED no longer discoverable after reprocess." }

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

    Write-Host "`n========= OPERATIONAL CONTINUITY ACCEPTANCE TEST 3 (REPROCESS -> FRESH BUDGET) =========" -ForegroundColor Cyan
    Write-Host ("RESULT: {0}" -f $script:Result)
    if ($script:Blocker) { Write-Host ("REASON: {0}" -f $script:Blocker) }
    Write-Host "`nPre-reprocess:"
    Write-Host ("  packageId       : {0}" -f $script:Evidence.packageId)
    Write-Host ("  incidentId      : {0}" -f $script:Evidence.incidentId)
    Write-Host ("  status          : {0}" -f $script:Evidence.preStatus)
    Write-Host ("  attempt_count   : {0}" -f $script:Evidence.preAttempt)
    Write-Host ("  reason_code     : {0}" -f $script:Evidence.preReasonCode)
    Write-Host ("  incident status : {0}" -f $script:Evidence.preIncidentStatus)
    Write-Host "`nPost-reprocess:"
    Write-Host ("  attempt_count   : {0}" -f $script:Evidence.postAttempt)
    Write-Host ("  fencing token   : {0}" -f $script:Evidence.postFencing)
    Write-Host ("  package status  : {0}" -f $script:Evidence.postPackageStatus)
    Write-Host ("  final status    : {0}" -f $script:Evidence.finalStatus)
    Write-Host "`nHistory:"
    Write-Host ("  old exhaustion incident preserved : {0}" -f $script:Evidence.oldExhaustionIncidentKept)
    Write-Host ("  old reason still discoverable      : {0}" -f $script:Evidence.oldReasonDiscoverable)
    Write-Host ("  incident status after reprocess    : {0}" -f $script:Evidence.incidentStatusAfter)
    Write-Host "`nPlaywright:"
    Write-Host ("  reprocess action : {0}" -f $script:Evidence.playwright)
    Write-Host ("  action used      : {0}" -f $script:Evidence.actionUsed)
    Write-Host ("  review spec      : {0}" -f $script:Evidence.reviewPlaywright)
    Write-Host ("  trace            : {0}" -f $(if ($script:Evidence.tracePath) { $script:Evidence.tracePath } else { '(none; only on failure)' }))
    Write-Host ("  screenshot       : {0}" -f $(if ($script:Evidence.screenshotPath) { $script:Evidence.screenshotPath } else { '(none; only on failure)' }))
    Write-Host "`nCleanup:"
    Write-Host ("  test data : {0}" -f $testDataDisposition)
    Write-Host ("`nEvidence directory : {0}" -f $EvidenceDir)
    Write-Host "=======================================================================================" -ForegroundColor Cyan

    $summary = [ordered]@{ result = $script:Result; reason = $script:Blocker; evidence = $script:Evidence; testData = $testDataDisposition }
    $summary | ConvertTo-Json -Depth 8 | Set-Content -Path (Join-Path $EvidenceDir 'result.json') -Encoding UTF8

    if ($script:Transcribing) { try { Stop-Transcript | Out-Null } catch { } }

    if ($script:Result -eq 'PASS') { exit 0 }
    elseif ($script:Result -eq 'BLOCKED') { exit 3 }
    else { exit 1 }
}
