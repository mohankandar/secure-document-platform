#requires -Version 5.1
<#
.SYNOPSIS
    LexaVault Operational Continuity Acceptance Test 2 - reusable, Kiro-free runner.

.DESCRIPTION
    Validates that repeated REAL expired-lease recoveries exhaust the attempt budget and terminate in
    a Technical Attention (FAILED) state without ever silently starting an extra attempt:

        attempt 1
          -> real expired-lease recovery -> attempt 2 (fencing advanced)
          -> real expired-lease recovery -> attempt 3 (fencing advanced)
          -> attempt-3 lease expires
          -> package FAILED / Technical Attention
          -> reason_code PROCESSING_RECOVERY_EXHAUSTED
          -> attempt_count stays 3, NO automatic attempt 4 (re-verified after a delay)

    Driven against the REAL running application (API + DB + UI). No application/backend/frontend code
    is modified and no production test hooks are added. attempt_count is NEVER set by the harness; the
    production PackageProcessingRecoveryScheduler performs every increment and the exhaustion.

    Lease expiry mechanism (identical to Test 1):
      - default: accelerate by pushing the disposable package's lease TIMESTAMP into the past.
      - -NaturalLeaseExpiry: stop the backend and wait for the real lease to expire on its own,
        touching no lease columns (lease_expires_at / heartbeat_at are not written).

    RACE HANDLING: local processing is fast. After each recovery to attempt N is observed while
    PROCESSING, the backend is stopped immediately so normal processing cannot complete. DB polling
    uses short intervals. If the package leaves PROCESSING before the required node loss, the run
    reports BLOCKED with the observed state (no faking of status or attempt_count).

.PARAMETER SkipBackendManagement
    Diagnostics only; the test must control the backend to simulate node loss.

.PARAMETER KeepTestData
    Retain the disposable package after the run. Default removes it on PASS via Remove-TestPackage.ps1;
    FAIL/BLOCKED always retains it for investigation.

.PARAMETER NaturalLeaseExpiry
    Wait for real lease expiry instead of accelerating (no lease-column writes).

.NOTES
    Configuration + prerequisites are centralized in helpers/Common-Config.ps1. No secrets embedded.
    Each run creates a fresh disposable package with a unique 12-digit contract. A full transcript is
    written to the per-run evidence directory; evidence does not depend on terminal buffers.
#>
[CmdletBinding()]
param(
    [switch] $SkipBackendManagement,
    [switch] $KeepTestData,
    [switch] $NaturalLeaseExpiry
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

$SpecRelPath = 'e2e/operational-continuity/recovery-exhaustion-technical-attention.spec.ts'
$EvidenceDir = Join-Path $ToolRoot ('evidence\{0}-test2' -f (Get-Date -Format 'yyyyMMdd-HHmmss'))

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

$LeaseDurationMs   = [int](Get-LvEnvOrDefault 'SECUREDOC_PROCESSING_LEASE_DURATION_MS' '30000')
$RecoveryScanMs    = [int](Get-LvEnvOrDefault 'SECUREDOC_PROCESSING_RECOVERY_SCAN_INTERVAL_MS' '5000')
$MaxAttempts       = [int](Get-LvEnvOrDefault 'SECUREDOC_PROCESSING_MAX_ATTEMPTS' '3')

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
$script:BackendProcess = $null
$script:Evidence = [ordered]@{
    packageId              = $null
    attempt1Count          = $null
    attempt1Fencing        = $null
    attempt2Count          = $null
    attempt2Fencing        = $null
    attempt3Count          = $null
    attempt3Fencing        = $null
    postExhaustionAttempt  = $null
    finalStatus            = $null
    reasonCode             = $null
    incidentStatus         = $null
    delayedRecheckAttempt  = $null
    delayedRecheckStatus   = $null
    delayedRecheckReason   = $null
    playwright             = 'NOT_RUN'
    tracePath              = $null
    screenshotPath         = $null
    leaseExpiryMode        = $(if ($NaturalLeaseExpiry) { 'natural' } else { 'accelerated' })
}

function Write-Step([string] $msg) { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }
function Write-Info([string] $msg) { Write-Host $msg }
function Fail([string] $msg) { throw $msg }

# ---------------------------------------------------------------------------
# Backend lifecycle (same conventions as Test 1)
# ---------------------------------------------------------------------------
function Test-BackendReady {
    try { return ((Invoke-RestMethod -Method GET -Uri "$BackendUrl/actuator/health" -TimeoutSec 5).status -eq 'UP') }
    catch { return $false }
}
function Wait-BackendReady([int] $TimeoutSeconds = 180) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) { if (Test-BackendReady) { Write-Info "  backend is UP"; return $true }; Start-Sleep -Seconds 3 }
    Fail "Backend did not become ready within ${TimeoutSeconds}s at $BackendUrl/actuator/health"
}
function Wait-BackendStopped([int] $TimeoutSeconds = 60) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) { if (-not (Test-BackendReady)) { Write-Info "  backend is DOWN"; return $true }; Start-Sleep -Seconds 2 }
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

# ---------------------------------------------------------------------------
# Lease expiry (accelerated or natural), matching Test 1 semantics.
# ---------------------------------------------------------------------------
function Invoke-LeaseExpiry {
    if ($NaturalLeaseExpiry) {
        $waitSeconds = [int][math]::Ceiling(($LeaseDurationMs / 1000.0)) + 10
        Write-Info "  waiting ~${waitSeconds}s for the real lease to expire naturally (no DB writes)"
        Start-Sleep -Seconds $waitSeconds
    } else {
        Write-Info "  expiring the package lease (only lease timestamps; attempt_count untouched)"
        & $ExpireLease -PackageId $script:PackageId | Out-Null
    }
}

# Recover from an expired lease to a target attempt, then stop the backend PROMPTLY while still
# PROCESSING so normal processing cannot complete. Returns the observed lease object.
function Invoke-RecoveryToAttempt([int] $TargetAttempt) {
    Invoke-LeaseExpiry
    Write-Info "  restarting backend; the real recovery scheduler will reclaim the expired lease -> attempt $TargetAttempt"
    Start-Backend | Out-Null
    $rec = & $WaitState -PackageId $script:PackageId -MinAttemptCount $TargetAttempt `
        -ExpectedStatuses @('PROCESSING','PENDING_REVIEW','COMPLETED','EXPORTED') -TimeoutSeconds 150 -PollIntervalSeconds 2
    if ($rec.attemptCount -ne $TargetAttempt) {
        Fail "Expected attempt_count=$TargetAttempt but observed $($rec.attemptCount)."
    }
    if ($rec.status -ne 'PROCESSING') {
        $script:Blocker = "Package left PROCESSING (status=$($rec.status)) at attempt $TargetAttempt before node loss could be simulated. Local processing outran the harness; re-run. No status/attempt faked."
        $script:Result = 'BLOCKED'
        throw [System.OperationCanceledException]::new('BLOCKED')
    }
    # Stop immediately to freeze this attempt generation before processing finishes.
    Stop-Backend | Out-Null
    # Return ONLY the lease object (suppress any side-effecting function output above).
    Write-Output $rec
}

# ---------------------------------------------------------------------------
# Main scenario (guarded by finally for cleanup). NO tenant policy manipulation is required.
# ---------------------------------------------------------------------------
try {
    Write-Step "PHASE 0 - Prerequisites"
    $prereq = Test-LvPrerequisites -Config $Cfg
    foreach ($c in $prereq.checks) { Write-Info ("  [{0}] {1} - {2}" -f $(if ($c.ok) { 'OK  ' } else { 'FAIL' }), $c.name, $c.detail) }
    if (-not $prereq.ok) { Fail ("Prerequisite check failed:`n  - " + ($prereq.failures -join "`n  - ")) }
    if ($Cfg.TesseractPath) { $env:SECUREDOC_OCR_TESSERACT_PATH = $Cfg.TesseractPath }
    if ($MaxAttempts -ne 3) { Write-Info "  NOTE: max-processing-attempts=$MaxAttempts (test asserts exhaustion at that boundary)" }

    Write-Step "PHASE 1 - Attempt 1: create/upload/submit, confirm PROCESSING attempt_count=1"
    Start-Backend
    $ids = New-LvTestIdentifiers
    $createBody = @{
        packageType = 'MANUAL_UPLOAD'
        businessIdentifiers = @{ contract_number = $ids.contract_number; member_number = $ids.member_number; service_entity = $ids.service_entity }
        metadata = @{ source = 'operational-continuity-acceptance-test-2' }
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

    # Confirm attempt 1 (short polling to catch PROCESSING before it advances).
    $lease1 = & $WaitState -PackageId $script:PackageId -MinAttemptCount 1 `
        -ExpectedStatuses @('PROCESSING','PENDING_REVIEW','COMPLETED','EXPORTED') -TimeoutSeconds 90 -PollIntervalSeconds 2
    if ($null -eq $lease1.attemptCount -or $lease1.attemptCount -ne 1) { Fail "Expected attempt_count=1, observed $($lease1.attemptCount)." }
    if ($lease1.status -ne 'PROCESSING') {
        $script:Blocker = "Package reached status=$($lease1.status) before attempt 1 could be frozen. No faking. Re-run."
        $script:Result = 'BLOCKED'; return
    }
    $script:Evidence.attempt1Count = $lease1.attemptCount
    $script:Evidence.attempt1Fencing = $lease1.fencingToken
    Write-Info "  attempt 1 confirmed: attempt_count=$($lease1.attemptCount) fencing=$($lease1.fencingToken) status=$($lease1.status)"
    # Stop the backend immediately to freeze attempt 1.
    Stop-Backend

    Write-Step "PHASE 2 - Recovery to attempt 2"
    $lease2 = Invoke-RecoveryToAttempt -TargetAttempt 2
    $script:Evidence.attempt2Count = $lease2.attemptCount
    $script:Evidence.attempt2Fencing = $lease2.fencingToken
    if ($null -ne $lease1.fencingToken -and $null -ne $lease2.fencingToken -and $lease2.fencingToken -le $lease1.fencingToken) {
        Fail "Fencing token did not advance for attempt 2 ($($lease1.fencingToken) -> $($lease2.fencingToken))."
    }
    Write-Info "  attempt 2 confirmed: attempt_count=$($lease2.attemptCount) fencing=$($lease2.fencingToken)"

    Write-Step "PHASE 3 - Recovery to attempt 3"
    $lease3 = Invoke-RecoveryToAttempt -TargetAttempt 3
    $script:Evidence.attempt3Count = $lease3.attemptCount
    $script:Evidence.attempt3Fencing = $lease3.fencingToken
    if ($null -ne $lease2.fencingToken -and $null -ne $lease3.fencingToken -and $lease3.fencingToken -le $lease2.fencingToken) {
        Fail "Fencing token did not advance for attempt 3 ($($lease2.fencingToken) -> $($lease3.fencingToken))."
    }
    Write-Info "  attempt 3 confirmed: attempt_count=$($lease3.attemptCount) fencing=$($lease3.fencingToken)"

    Write-Step "PHASE 4 - Exhaust attempt 3: expire lease, restart, expect FAILED / Technical Attention"
    Invoke-LeaseExpiry
    Write-Info "  restarting backend; the real recovery scheduler must EXHAUST (attempt_count>=max=$MaxAttempts), not start attempt 4"
    Start-Backend

    # Wait for the terminal FAILED state produced by the exhaustion path.
    $failed = & $WaitState -PackageId $script:PackageId -ExpectedStatuses @('FAILED') -TimeoutSeconds 120 -PollIntervalSeconds 2
    $script:Evidence.postExhaustionAttempt = $failed.attemptCount
    $script:Evidence.finalStatus = $failed.status
    Write-Info "  exhausted: status=$($failed.status) attempt_count=$($failed.attemptCount)"
    if ($failed.attemptCount -ne 3) { Fail "Expected attempt_count=3 at exhaustion, observed $($failed.attemptCount)." }

    # Verify the processing incident + reason code.
    $incRows = @(& $RunQuery -Sql "SELECT reason_code, status, attempt_count FROM processing_incidents WHERE package_id = '$($script:PackageId)' ORDER BY last_observed_at DESC;" -AsObjects -Columns @('reason_code','status','attempt_count'))
    if ($incRows.Count -eq 0) { Fail "No processing_incidents row found for the package; expected PROCESSING_RECOVERY_EXHAUSTED." }
    $incident = $incRows[0]
    $script:Evidence.reasonCode = $incident.reason_code
    $script:Evidence.incidentStatus = $incident.status
    Write-Info "  incident: reason_code=$($incident.reason_code) status=$($incident.status) attempt_count=$($incident.attempt_count)"
    if ($incident.reason_code -ne 'PROCESSING_RECOVERY_EXHAUSTED') {
        Fail "Expected reason_code=PROCESSING_RECOVERY_EXHAUSTED, observed '$($incident.reason_code)'."
    }

    Write-Step "PHASE 4b - NO ATTEMPT 4 proof: wait >= 2 recovery-scan intervals and re-check"
    $delaySeconds = [int][math]::Ceiling(($RecoveryScanMs / 1000.0)) * 2 + 4
    Write-Info "  waiting ${delaySeconds}s (>= 2 recovery-scan intervals) before re-checking"
    Start-Sleep -Seconds $delaySeconds
    $recheck = & $GetLease -PackageId $script:PackageId
    $reRows = @(& $RunQuery -Sql "SELECT reason_code FROM processing_incidents WHERE package_id = '$($script:PackageId)' ORDER BY last_observed_at DESC;" -AsObjects -Columns @('reason_code'))
    $script:Evidence.delayedRecheckAttempt = $recheck.attemptCount
    $script:Evidence.delayedRecheckStatus = $recheck.status
    $script:Evidence.delayedRecheckReason = if ($reRows.Count -gt 0) { $reRows[0].reason_code } else { $null }
    Write-Info "  delayed re-check: attempt_count=$($recheck.attemptCount) status=$($recheck.status) reason=$($script:Evidence.delayedRecheckReason)"
    if ($recheck.attemptCount -ne 3) { Fail "NO-ATTEMPT-4 violated: attempt_count changed to $($recheck.attemptCount) after exhaustion." }
    if ($recheck.status -ne 'FAILED') { Fail "NO-ATTEMPT-4 violated: status changed to $($recheck.status) after exhaustion." }
    if ($script:Evidence.delayedRecheckReason -ne 'PROCESSING_RECOVERY_EXHAUSTED') { Fail "Reason changed after exhaustion: '$($script:Evidence.delayedRecheckReason)'." }

    Write-Step "PHASE 5 - Playwright: verify Technical Attention experience"
    Write-Info "  running Playwright exhaustion spec for package $($script:PackageId)"
    $env:LV_OC_PACKAGE_ID = $script:PackageId
    Push-Location $FrontendDir
    try {
        & npx playwright test $SpecRelPath --reporter=list
        $pwExit = $LASTEXITCODE
    } finally {
        Pop-Location
        Remove-Item Env:\LV_OC_PACKAGE_ID -ErrorAction SilentlyContinue
    }

    $pwResults = Join-Path $FrontendDir 'test-results'
    if (Test-Path $pwResults) {
        $trace = Get-ChildItem -Path $pwResults -Recurse -Filter 'trace.zip' -ErrorAction SilentlyContinue | Select-Object -First 1
        $shot  = Get-ChildItem -Path $pwResults -Recurse -Include '*.png' -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($null -ne $trace) { $script:Evidence.tracePath = $trace.FullName }
        if ($null -ne $shot)  { $script:Evidence.screenshotPath = $shot.FullName }
    }
    if ($pwExit -ne 0) { $script:Evidence.playwright = 'FAIL'; Fail "Playwright exhaustion spec failed (exit $pwExit). See $pwResults." }
    $script:Evidence.playwright = 'PASS'

    $script:Result = 'PASS'
}
catch {
    if ($script:Result -ne 'BLOCKED') {
        $script:Result = 'FAIL'
        $script:Blocker = $_.Exception.Message
    }
    Write-Host "`n[$($script:Result)] $($_.Exception.Message)" -ForegroundColor Red
}
finally {
    # Disposable package cleanup:
    #  - PASS + not -KeepTestData -> remove the single disposable package (incl. processing_incidents).
    #  - FAIL/BLOCKED, or -KeepTestData -> retain for investigation.
    $testDataDisposition = 'RETAINED'
    if ($script:PackageId) {
        if ($script:Result -eq 'PASS' -and -not $KeepTestData) {
            try { & $RemovePackage -PackageId $script:PackageId | Out-Null; $testDataDisposition = 'REMOVED' }
            catch { Write-Host "  cleanup failed: $($_.Exception.Message)" -ForegroundColor Yellow; $testDataDisposition = 'RETAINED (cleanup failed)' }
        } elseif ($KeepTestData) {
            $testDataDisposition = 'RETAINED (-KeepTestData)'
        } else {
            $testDataDisposition = "RETAINED ($($script:Result) - kept for investigation)"
        }
    } else {
        $testDataDisposition = 'NONE (no package created)'
    }

    # Stop the backend we started.
    if (-not $SkipBackendManagement -and $null -ne $script:BackendProcess -and -not $script:BackendProcess.HasExited) {
        Write-Info "  stopping backend PID $($script:BackendProcess.Id)"
        try { Stop-Process -Id $script:BackendProcess.Id -Force } catch { }
    }

    # ----- FINAL REPORT -----
    Write-Host "`n============ OPERATIONAL CONTINUITY ACCEPTANCE TEST 2 (EXHAUSTION) ============" -ForegroundColor Cyan
    Write-Host ("RESULT: {0}" -f $script:Result)
    if ($script:Blocker) { Write-Host ("REASON: {0}" -f $script:Blocker) }
    Write-Host "`nPackage evidence:"
    Write-Host ("  packageId                 : {0}" -f $script:Evidence.packageId)
    Write-Host ("  attempt 1 count / fencing : {0} / {1}" -f $script:Evidence.attempt1Count, $script:Evidence.attempt1Fencing)
    Write-Host ("  attempt 2 count / fencing : {0} / {1}" -f $script:Evidence.attempt2Count, $script:Evidence.attempt2Fencing)
    Write-Host ("  attempt 3 count / fencing : {0} / {1}" -f $script:Evidence.attempt3Count, $script:Evidence.attempt3Fencing)
    Write-Host ("  post-exhaustion attempt   : {0}" -f $script:Evidence.postExhaustionAttempt)
    Write-Host ("  final package status      : {0}" -f $script:Evidence.finalStatus)
    Write-Host ("  reason_code               : {0}" -f $script:Evidence.reasonCode)
    Write-Host ("  incident status           : {0}" -f $script:Evidence.incidentStatus)
    Write-Host ("  delayed recheck attempt   : {0}" -f $script:Evidence.delayedRecheckAttempt)
    Write-Host ("  delayed recheck status    : {0}" -f $script:Evidence.delayedRecheckStatus)
    Write-Host ("  delayed recheck reason    : {0}" -f $script:Evidence.delayedRecheckReason)
    Write-Host ("  lease expiry mode         : {0}" -f $script:Evidence.leaseExpiryMode)
    Write-Host "`nPlaywright:"
    Write-Host ("  result     : {0}" -f $script:Evidence.playwright)
    Write-Host ("  trace      : {0}" -f $(if ($script:Evidence.tracePath) { $script:Evidence.tracePath } else { '(none; only on failure)' }))
    Write-Host ("  screenshot : {0}" -f $(if ($script:Evidence.screenshotPath) { $script:Evidence.screenshotPath } else { '(none; only on failure)' }))
    Write-Host "`nCleanup:"
    Write-Host ("  test data : {0}" -f $testDataDisposition)
    Write-Host ("`nEvidence directory : {0}" -f $EvidenceDir)
    Write-Host "===============================================================================" -ForegroundColor Cyan

    $summary = [ordered]@{
        result = $script:Result
        reason = $script:Blocker
        evidence = $script:Evidence
        testData = $testDataDisposition
    }
    $summary | ConvertTo-Json -Depth 8 | Set-Content -Path (Join-Path $EvidenceDir 'result.json') -Encoding UTF8

    if ($script:Transcribing) { try { Stop-Transcript | Out-Null } catch { } }

    if ($script:Result -eq 'PASS') { exit 0 }
    elseif ($script:Result -eq 'BLOCKED') { exit 3 }
    else { exit 1 }
}
