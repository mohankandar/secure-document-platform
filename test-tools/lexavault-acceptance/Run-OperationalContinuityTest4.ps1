#requires -Version 5.1
<#
.SYNOPSIS
    LexaVault Operational Continuity Acceptance Test 4 - reusable, Kiro-free runner.

.DESCRIPTION
    For a disposable FAILED / Technical Attention package (recovery-exhausted), proves the Tenant-IT
    Preserve-for-Investigation -> Release-Hold lifecycle:

        FAILED / PROCESSING_RECOVERY_EXHAUSTED (attempt_count = 3, incident OPEN)
          -> Preserve for Investigation (7 days): technical_hold_until moves FORWARD
          -> Release Hold: special investigation extension ends, normal Technical Attention retention
             window resumes (technical_hold_until reset to now + tenant never-exported-hold window)

    Invariants across both actions: package stays FAILED, attempt_count stays 3, NO reprocessing (no
    new processing lease/cycle), and the exhaustion incident/history remains intact.

    Driven against the REAL running application (API + DB + UI). No application/backend/frontend code is
    modified and no production test hooks are added. attempt_count is never set by the harness. The
    Preserve/Release actions are performed via the genuine Support Incident UI (Playwright), not by
    calling the backend endpoint directly.

    Part 1 reuses the verified Test 2 exhaustion flow (accelerated lease expiry). NaturalLeaseExpiry is
    intentionally not provided here (Test 2 already verified natural-expiry exhaustion).

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

$HoldSpec    = 'e2e/operational-continuity/preserve-release-hold.spec.ts'
$EvidenceDir = Join-Path $ToolRoot ('evidence\{0}-test4' -f (Get-Date -Format 'yyyyMMdd-HHmmss'))

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
    packageId            = $null
    incidentId           = $null
    beforeStatus         = $null
    beforeAttempt        = $null
    beforeHold           = $null
    afterPreserveHold    = $null
    afterPreserveStatus  = $null
    afterPreserveAttempt = $null
    afterPreserveIncident= $null
    afterReleaseHold     = $null
    afterReleaseStatus   = $null
    afterReleaseAttempt  = $null
    afterReleaseIncident = $null
    noReprocessing       = $null
    historyPreserved     = $null
    playwrightPreserve   = 'NOT_RUN'
    playwrightRelease    = 'NOT_RUN'
    tracePath            = $null
    screenshotPath       = $null
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

# Read a single scalar cell (StrictMode/array-safe).
function Get-Scalar([string] $sql) {
    $rows = @(& $RunQuery -Sql $sql)
    if ($rows.Count -eq 0) { return $null }
    $v = "$($rows[-1])".Trim()
    if ($v -eq '') { return $null }
    return $v
}

function Get-TechnicalHold {
    return Get-Scalar "SELECT technical_hold_until FROM package_lifecycles WHERE package_id = '$($script:PackageId)';"
}
function Get-IncidentStatus {
    return Get-Scalar "SELECT status FROM processing_incidents WHERE package_id = '$($script:PackageId)' ORDER BY last_observed_at DESC LIMIT 1;"
}
function Get-IncidentId {
    return Get-Scalar "SELECT id FROM processing_incidents WHERE package_id = '$($script:PackageId)' ORDER BY last_observed_at DESC LIMIT 1;"
}
function ConvertTo-Utc([string] $ts) {
    if ($null -eq $ts) { return $null }
    # psql prints e.g. '2026-09-22 01:23:45.678+00'; DateTime parse handles it.
    return [datetime]::Parse($ts, [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::AdjustToUniversal)
}

function Invoke-HoldSpec([string] $Step) {
    $env:LV_OC_INCIDENT_ID = $script:IncidentId
    $env:LV_OC_STEP = $Step
    $ex = 1
    Push-Location $FrontendDir
    try {
        # Pipe npx output to the host so it is transcribed but does NOT enter this function's return
        # stream (otherwise the returned value becomes an array of [output..., exitcode]).
        & npx playwright test $HoldSpec --reporter=list | Out-Host
        $ex = $LASTEXITCODE   # capture immediately; later commands would overwrite $LASTEXITCODE
    }
    finally {
        Pop-Location
        Remove-Item Env:\LV_OC_INCIDENT_ID -ErrorAction SilentlyContinue
        Remove-Item Env:\LV_OC_STEP -ErrorAction SilentlyContinue
    }
    $pwResults = Join-Path $FrontendDir 'test-results'
    if (Test-Path $pwResults) {
        $trace = Get-ChildItem -Path $pwResults -Recurse -Filter 'trace.zip' -ErrorAction SilentlyContinue | Select-Object -First 1
        $shot  = Get-ChildItem -Path $pwResults -Recurse -Include '*.png' -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($null -ne $trace) { $script:Evidence.tracePath = $trace.FullName }
        if ($null -ne $shot)  { $script:Evidence.screenshotPath = $shot.FullName }
    }
    return $ex
}

# ---------------------------------------------------------------------------
# Main scenario
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
        metadata = @{ source = 'operational-continuity-acceptance-test-4' }
    }
    $created = & $InvokeApi -Method POST -Path '/api/v1/packages' -Body $createBody
    $script:PackageId = $created.packageId
    if (-not $script:PackageId) { Fail "Package creation did not return a packageId." }
    $script:Evidence.packageId = $script:PackageId
    Write-Info "  created package $($script:PackageId) (contract $($ids.contract_number))"

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
    $reason = Get-Scalar "SELECT reason_code FROM processing_incidents WHERE package_id = '$($script:PackageId)' ORDER BY last_observed_at DESC LIMIT 1;"
    if ($reason -ne 'PROCESSING_RECOVERY_EXHAUSTED') { Fail "Expected reason PROCESSING_RECOVERY_EXHAUSTED, got '$reason'." }
    $script:IncidentId = Get-IncidentId
    $script:Evidence.incidentId = $script:IncidentId

    $script:Evidence.beforeStatus = $failed.status
    $script:Evidence.beforeAttempt = $failed.attemptCount
    $script:Evidence.beforeHold = Get-TechnicalHold
    $beforeHoldUtc = ConvertTo-Utc $script:Evidence.beforeHold
    Write-Info "  BEFORE: status=$($failed.status) attempt=$($failed.attemptCount) technical_hold_until=$($script:Evidence.beforeHold) incidentId=$($script:IncidentId)"
    if ($null -eq $beforeHoldUtc) { Fail "technical_hold_until is null before preserve; expected a never-exported hold from exhaustion." }

    Write-Step "PHASE 2 - Preserve for Investigation (7 days) via the real Support Incident UI"
    $pwPreserve = Invoke-HoldSpec -Step 'PRESERVE'
    $script:Evidence.playwrightPreserve = $(if ($pwPreserve -eq 0) { 'PASS' } else { 'FAIL' })
    if ($pwPreserve -ne 0) { Fail "Playwright PRESERVE step failed (exit $pwPreserve)." }

    $preserveLease = & $GetLease -PackageId $script:PackageId
    $script:Evidence.afterPreserveHold = Get-TechnicalHold
    $script:Evidence.afterPreserveStatus = $preserveLease.status
    $script:Evidence.afterPreserveAttempt = $preserveLease.attemptCount
    $script:Evidence.afterPreserveIncident = Get-IncidentStatus
    $preserveHoldUtc = ConvertTo-Utc $script:Evidence.afterPreserveHold
    Write-Info "  AFTER PRESERVE: status=$($preserveLease.status) attempt=$($preserveLease.attemptCount) hold=$($script:Evidence.afterPreserveHold) incident=$($script:Evidence.afterPreserveIncident)"
    if ($preserveLease.status -ne 'FAILED') { Fail "Preserve changed package status to $($preserveLease.status); expected FAILED." }
    if ($preserveLease.attemptCount -ne 3) { Fail "Preserve changed attempt_count to $($preserveLease.attemptCount); expected 3." }
    if ($null -eq $preserveHoldUtc) { Fail "technical_hold_until became null after preserve." }
    if (-not ($preserveHoldUtc -gt $beforeHoldUtc)) { Fail "Preserve did not move technical_hold_until forward ($($script:Evidence.beforeHold) -> $($script:Evidence.afterPreserveHold))." }

    Write-Step "PHASE 3 - Release Hold via the real Support Incident UI"
    $pwRelease = Invoke-HoldSpec -Step 'RELEASE'
    $script:Evidence.playwrightRelease = $(if ($pwRelease -eq 0) { 'PASS' } else { 'FAIL' })
    if ($pwRelease -ne 0) { Fail "Playwright RELEASE step failed (exit $pwRelease)." }

    $releaseLease = & $GetLease -PackageId $script:PackageId
    $script:Evidence.afterReleaseHold = Get-TechnicalHold
    $script:Evidence.afterReleaseStatus = $releaseLease.status
    $script:Evidence.afterReleaseAttempt = $releaseLease.attemptCount
    $script:Evidence.afterReleaseIncident = Get-IncidentStatus
    $releaseHoldUtc = ConvertTo-Utc $script:Evidence.afterReleaseHold
    Write-Info "  AFTER RELEASE: status=$($releaseLease.status) attempt=$($releaseLease.attemptCount) hold=$($script:Evidence.afterReleaseHold) incident=$($script:Evidence.afterReleaseIncident)"
    if ($releaseLease.status -ne 'FAILED') { Fail "Release changed package status to $($releaseLease.status); expected FAILED." }
    if ($releaseLease.attemptCount -ne 3) { Fail "Release changed attempt_count to $($releaseLease.attemptCount); expected 3." }
    if ($null -eq $releaseHoldUtc) { Fail "technical_hold_until became null after release; product resets it to the normal window (non-null)." }
    # Normal retention resumes: the special (far-future) investigation extension is withdrawn, so the
    # released hold is earlier than the preserved hold, and still in the future.
    if (-not ($releaseHoldUtc -lt $preserveHoldUtc)) {
        Fail "Release did not withdraw the investigation extension (release hold $($script:Evidence.afterReleaseHold) not earlier than preserve hold $($script:Evidence.afterPreserveHold))."
    }
    if (-not ($releaseHoldUtc -gt (Get-Date).ToUniversalTime())) {
        Fail "Released technical_hold_until is not in the future ($($script:Evidence.afterReleaseHold))."
    }

    Write-Step "PHASE 4 - Invariants: no reprocessing + history preserved"
    # No reprocessing: attempt_count never left 3 and package never re-entered PROCESSING during the actions.
    $noReproc = ($releaseLease.attemptCount -eq 3 -and $preserveLease.attemptCount -eq 3 `
        -and $releaseLease.status -eq 'FAILED' -and $preserveLease.status -eq 'FAILED')
    $script:Evidence.noReprocessing = $(if ($noReproc) { 'YES' } else { 'NO' })
    if (-not $noReproc) { Fail "Unintended reprocessing detected during preserve/release." }

    # History preserved: incident row + exhaustion reason/events still discoverable.
    $incidentStillPresent = ($null -ne (Get-IncidentId))
    $exhaustRows = @(& $RunQuery -Sql "SELECT COUNT(*) FROM processing_incident_events e JOIN processing_incidents i ON i.id = e.incident_id WHERE i.package_id = '$($script:PackageId)' AND (e.reason_code = 'PROCESSING_RECOVERY_EXHAUSTED' OR e.event_type = 'PROCESSING_RECOVERY_EXHAUSTED');")
    $exhaustCount = if ($exhaustRows.Count -gt 0) { [int]("$($exhaustRows[-1])".Trim()) } else { 0 }
    $reasonStill = Get-Scalar "SELECT reason_code FROM processing_incidents WHERE package_id = '$($script:PackageId)' ORDER BY last_observed_at DESC LIMIT 1;"
    $historyOk = ($incidentStillPresent -and ($reasonStill -eq 'PROCESSING_RECOVERY_EXHAUSTED' -or $exhaustCount -gt 0))
    $script:Evidence.historyPreserved = $(if ($historyOk) { 'YES' } else { 'NO' })
    Write-Info "  no-reprocessing=$($script:Evidence.noReprocessing) history-preserved=$($script:Evidence.historyPreserved) (incident present=$incidentStillPresent, exhaustionEvents=$exhaustCount, reason=$reasonStill)"
    if (-not $historyOk) { Fail "HISTORY VIOLATION: exhaustion incident/history not preserved after preserve/release." }

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

    Write-Host "`n======= OPERATIONAL CONTINUITY ACCEPTANCE TEST 4 (PRESERVE -> RELEASE HOLD) =======" -ForegroundColor Cyan
    Write-Host ("RESULT: {0}" -f $script:Result)
    if ($script:Blocker) { Write-Host ("REASON: {0}" -f $script:Blocker) }
    Write-Host ("`nPackage ID: {0}" -f $script:Evidence.packageId)
    Write-Host "`nBefore preserve:"
    Write-Host ("  status               : {0}" -f $script:Evidence.beforeStatus)
    Write-Host ("  attempt_count        : {0}" -f $script:Evidence.beforeAttempt)
    Write-Host ("  technical_hold_until : {0}" -f $script:Evidence.beforeHold)
    Write-Host "`nAfter Preserve:"
    Write-Host ("  technical_hold_until : {0}" -f $script:Evidence.afterPreserveHold)
    Write-Host ("  status               : {0}" -f $script:Evidence.afterPreserveStatus)
    Write-Host ("  attempt_count        : {0}" -f $script:Evidence.afterPreserveAttempt)
    Write-Host ("  incident status      : {0}" -f $script:Evidence.afterPreserveIncident)
    Write-Host "`nAfter Release:"
    Write-Host ("  technical_hold_until : {0}" -f $script:Evidence.afterReleaseHold)
    Write-Host ("  status               : {0}" -f $script:Evidence.afterReleaseStatus)
    Write-Host ("  attempt_count        : {0}" -f $script:Evidence.afterReleaseAttempt)
    Write-Host ("  incident status      : {0}" -f $script:Evidence.afterReleaseIncident)
    Write-Host ("`nNo unintended reprocessing : {0}" -f $script:Evidence.noReprocessing)
    Write-Host ("History preserved          : {0}" -f $script:Evidence.historyPreserved)
    Write-Host "`nPlaywright:"
    Write-Host ("  preserve : {0}" -f $script:Evidence.playwrightPreserve)
    Write-Host ("  release  : {0}" -f $script:Evidence.playwrightRelease)
    Write-Host ("  trace    : {0}" -f $(if ($script:Evidence.tracePath) { $script:Evidence.tracePath } else { '(none; only on failure)' }))
    Write-Host ("  screenshot: {0}" -f $(if ($script:Evidence.screenshotPath) { $script:Evidence.screenshotPath } else { '(none; only on failure)' }))
    Write-Host ("`nCleanup: {0}" -f $testDataDisposition)
    Write-Host ("Evidence directory : {0}" -f $EvidenceDir)
    Write-Host "===================================================================================" -ForegroundColor Cyan

    $summary = [ordered]@{ result = $script:Result; reason = $script:Blocker; evidence = $script:Evidence; testData = $testDataDisposition }
    $summary | ConvertTo-Json -Depth 8 | Set-Content -Path (Join-Path $EvidenceDir 'result.json') -Encoding UTF8

    if ($script:Transcribing) { try { Stop-Transcript | Out-Null } catch { } }

    if ($script:Result -eq 'PASS') { exit 0 }
    elseif ($script:Result -eq 'BLOCKED') { exit 3 }
    else { exit 1 }
}
