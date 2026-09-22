#requires -Version 5.1
<#
.SYNOPSIS
    LexaVault Operational Continuity Acceptance Test 1 - reusable, Kiro-free runner.

.DESCRIPTION
    Validates that a REAL expired-lease / node-loss recovery preserves the durable processing
    attempt count through Manual Review:

        attempt 1
          -> real expired-lease/node-loss recovery
          -> attempt 2
          -> PENDING_REVIEW
          -> Playwright completes Manual Review
          -> processing resumes
          -> attempt_count remains 2
          -> package completes/exports

    The whole scenario is driven against the REAL running application (API + DB + UI). It does not
    modify any application/backend source and adds no production hooks. Lease recovery is genuine:
    the harness never sets attempt_count. By default it accelerates the run by pushing the
    disposable package's lease TIMESTAMP into the past (the real recovery scheduler still performs
    the increment). With -NaturalLeaseExpiry it instead stops the backend and waits for the lease to
    expire on its own, touching no lease columns at all.

    PHASES
      0. Validate prerequisites (centralized via Common-Config.ps1).
      1. Capture + switch tenant ReviewMode to REVIEW_ALL (restored in finally).
      2. Start backend, create disposable package (centralized identifiers), upload fixture, submit.
      3. Confirm PROCESSING attempt_count=1 with an active lease; record lease evidence.
         DRY-RUN GATE: if the package reaches a terminal state on attempt 1 without entering review,
         STOP and report BLOCKED (no production hooks).
      4. Simulate node loss: stop backend, expire lease (accelerated or natural), restart backend,
         let the real recovery scheduler reclaim -> attempt_count=2, fencing token advanced.
      5. Wait for PENDING_REVIEW, run the Playwright review spec, assert attempt_count=2 before
         review, after resume, and at terminal state.
      6. Restore policy (finally), optionally clean up the disposable package, and print
         PASS/FAIL/BLOCKED + evidence + artifact paths.

.PARAMETER SkipBackendManagement
    Do NOT start/stop the backend jar. Use only if you manage the backend externally. Not compatible
    with node-loss simulation (the test must stop the backend), so this is for diagnostics only.

.PARAMETER KeepTestData
    Retain the disposable package (and its rows) after the run for evidence/inspection. By default,
    on a PASS the disposable package is removed via Remove-TestPackage.ps1. On FAIL/BLOCKED the data
    is always retained so it can be investigated.

.PARAMETER NaturalLeaseExpiry
    Instead of accelerating expiry by editing the lease timestamp, stop the backend and wait for the
    real lease to expire naturally (lease-duration + margin). No lease columns are modified.

.NOTES
    Configuration + prerequisites are centralized in helpers/Common-Config.ps1. All settings are
    environment-overridable with local/demo defaults. No secrets are embedded.

    Safe to re-run: each run creates a fresh disposable package with a unique 12-digit contract.
    File-based logging: a transcript is written to the per-run evidence directory from the start;
    evidence does not depend on terminal buffers.
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

$SpecRelPath = 'e2e/operational-continuity/recovery-review-preserves-attempt.spec.ts'
$EvidenceDir = Join-Path $ToolRoot ('evidence\{0}' -f (Get-Date -Format 'yyyyMMdd-HHmmss'))

$InvokeApi     = Join-Path $HelpersDir 'Invoke-TestApi.ps1'
$GetLease      = Join-Path $HelpersDir 'Get-PackageProcessingLease.ps1'
$WaitState     = Join-Path $HelpersDir 'Wait-ForPackageState.ps1'
$SetPolicy     = Join-Path $HelpersDir 'Set-TestReviewPolicy.ps1'
$RestorePolicy = Join-Path $HelpersDir 'Restore-TestReviewPolicy.ps1'
$ExpireLease   = Join-Path $HelpersDir 'Expire-PackageLease.ps1'
$RunQuery      = Join-Path $HelpersDir 'Invoke-TestDbQuery.ps1'
$SendDoc       = Join-Path $HelpersDir 'Send-TestDocument.ps1'
$RemovePackage = Join-Path $HelpersDir 'Remove-TestPackage.ps1'

$BackendUrl  = $Cfg.BackendUrl
$FrontendDir = $Cfg.FrontendDir
$JarPath     = $Cfg.JarPath
$FixturePath = $Cfg.FixturePath

# Lease timing for natural-expiry waits (env-overridable; matches coordination defaults).
$LeaseDurationMs = [int](Get-LvEnvOrDefault 'SECUREDOC_PROCESSING_LEASE_DURATION_MS' '30000')

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
$script:OriginalPolicy = $null
$script:BackendProcess = $null
$script:Evidence = [ordered]@{
    packageId             = $null
    initialAttemptCount   = $null
    recoveredAttemptCount = $null
    fencingTokenBefore    = $null
    fencingTokenAfter     = $null
    statusBeforeReview    = $null
    attemptBeforeReview   = $null
    attemptAfterResume    = $null
    finalAttemptCount     = $null
    finalStatus           = $null
    playwright            = 'NOT_RUN'
    tracePath             = $null
    screenshotPath        = $null
    leaseExpiryMode       = $(if ($NaturalLeaseExpiry) { 'natural' } else { 'accelerated' })
}

function Write-Step([string] $msg) { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }
function Write-Info([string] $msg) { Write-Host $msg }
function Fail([string] $msg) { throw $msg }

# ---------------------------------------------------------------------------
# Backend lifecycle
# ---------------------------------------------------------------------------
function Test-BackendReady {
    try {
        $resp = Invoke-RestMethod -Method GET -Uri "$BackendUrl/actuator/health" -TimeoutSec 5
        return ($resp.status -eq 'UP')
    } catch {
        return $false
    }
}

function Wait-BackendReady([int] $TimeoutSeconds = 180) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-BackendReady) { Write-Info "  backend is UP"; return $true }
        Start-Sleep -Seconds 3
    }
    Fail "Backend did not become ready within ${TimeoutSeconds}s at $BackendUrl/actuator/health"
}

function Wait-BackendStopped([int] $TimeoutSeconds = 60) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (-not (Test-BackendReady)) { Write-Info "  backend is DOWN"; return $true }
        Start-Sleep -Seconds 2
    }
    Fail "Backend still responding after ${TimeoutSeconds}s; expected it to be stopped."
}

function Start-Backend {
    if ($SkipBackendManagement) {
        Write-Info "  (SkipBackendManagement) expecting an externally-managed backend to be UP"
        Wait-BackendReady
        return
    }
    if (Test-BackendReady) {
        Fail "A backend is already running at $BackendUrl. Stop it first so this test can own the process lifecycle, or pass -SkipBackendManagement."
    }
    if (-not (Test-Path $JarPath)) {
        Fail "Backend jar not found: $JarPath (build it first: mvn -pl securedoc-api -am package)."
    }

    $stdout = Join-Path $EvidenceDir 'backend.out.log'
    $stderr = Join-Path $EvidenceDir 'backend.err.log'
    $javaArgs = @('-jar', $JarPath, '--spring.profiles.active=local')
    Write-Info "  starting backend: java $($javaArgs -join ' ')"
    $script:BackendProcess = Start-Process -FilePath 'java' -ArgumentList $javaArgs `
        -WorkingDirectory $Cfg.RepoRoot -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    Wait-BackendReady
}

function Stop-Backend {
    if ($SkipBackendManagement) {
        Fail "SkipBackendManagement is set but the test needs to stop the backend to simulate node loss. Re-run without -SkipBackendManagement."
    }
    if ($null -ne $script:BackendProcess -and -not $script:BackendProcess.HasExited) {
        Write-Info "  stopping backend PID $($script:BackendProcess.Id) (simulated node loss)"
        Stop-Process -Id $script:BackendProcess.Id -Force
        try { $script:BackendProcess.WaitForExit(30000) | Out-Null } catch { }
    }
    Wait-BackendStopped
}

# ---------------------------------------------------------------------------
# Main scenario (guarded by finally for policy restore + cleanup)
# ---------------------------------------------------------------------------
try {
    Write-Step "PHASE 0 - Prerequisites"
    $prereq = Test-LvPrerequisites -Config $Cfg
    foreach ($c in $prereq.checks) {
        $mark = if ($c.ok) { 'OK  ' } else { 'FAIL' }
        Write-Info ("  [{0}] {1} - {2}" -f $mark, $c.name, $c.detail)
    }
    if (-not $prereq.ok) {
        Fail ("Prerequisite check failed:`n  - " + ($prereq.failures -join "`n  - "))
    }
    # Propagate the resolved Tesseract path to the backend process we launch.
    if ($Cfg.TesseractPath) { $env:SECUREDOC_OCR_TESSERACT_PATH = $Cfg.TesseractPath }

    Write-Step "PHASE 1/2 - Start backend"
    Start-Backend

    Write-Step "PHASE 1 - Capture + set tenant ReviewMode = REVIEW_ALL"
    $script:OriginalPolicy = & $SetPolicy -TargetReviewMode 'REVIEW_ALL'
    Write-Info "  original reviewMode captured: $($script:OriginalPolicy.reviewMode)"

    Write-Step "PHASE 2 - Create disposable package, upload fixture, submit"
    $ids = New-LvTestIdentifiers   # centralized, API-valid, unique contract
    $createBody = @{
        packageType = 'MANUAL_UPLOAD'
        businessIdentifiers = @{
            contract_number = $ids.contract_number
            member_number   = $ids.member_number
            service_entity  = $ids.service_entity
        }
        metadata = @{ source = 'operational-continuity-acceptance-test-1' }
    }
    $created = & $InvokeApi -Method POST -Path '/api/v1/packages' -Body $createBody
    $script:PackageId = $created.packageId
    if (-not $script:PackageId) { Fail "Package creation did not return a packageId. Response: $($created | ConvertTo-Json -Compress)" }
    $script:Evidence.packageId = $script:PackageId
    Write-Info "  created package $($script:PackageId) (contract $($ids.contract_number)), status=$($created.status)"

    $uploaded = & $SendDoc -PackageId $script:PackageId -FilePath $FixturePath
    Write-Info "  uploaded fixture: documentId=$($uploaded.documentId) status=$($uploaded.status)"

    $submitResp = & $InvokeApi -Method POST -Path "/api/v1/packages/$($script:PackageId)/submit"
    Write-Info "  submitted package, status=$($submitResp.status)"

    Write-Step "PHASE 3 - Confirm PROCESSING attempt_count=1 with active lease"
    $processing = & $WaitState -PackageId $script:PackageId -ExpectedStatuses @('PROCESSING','PENDING_REVIEW','COMPLETED','EXPORTED') -TimeoutSeconds 90 -PollIntervalSeconds 2
    $lease1 = & $GetLease -PackageId $script:PackageId
    Write-Info "  status=$($lease1.status) attempt_count=$($lease1.attemptCount) owner=$($lease1.ownerId) fencing=$($lease1.fencingToken)"

    if ($null -eq $lease1.attemptCount) {
        Fail "No processing lease/attempt_count recorded for the package; cannot validate recovery."
    }
    $script:Evidence.initialAttemptCount = $lease1.attemptCount
    $script:Evidence.fencingTokenBefore = $lease1.fencingToken

    if ($lease1.attemptCount -ne 1) {
        Fail "Expected initial attempt_count=1 but observed $($lease1.attemptCount)."
    }

    # DRY-RUN GATE: package must be able to reach review. If it already completed with no review,
    # the fixture+policy did not deterministically route a finding to review -> BLOCKED per instructions.
    if ($lease1.status -in @('COMPLETED','EXPORTED')) {
        $findingRows = @(& $RunQuery -Sql "SELECT COUNT(*) FROM findings WHERE package_id = '$($script:PackageId)';")
        $reviewRows  = @(& $RunQuery -Sql "SELECT COUNT(*) FROM review_tasks WHERE package_id = '$($script:PackageId)';")
        $findingCount = if ($findingRows.Count -gt 0) { [int]("$($findingRows[-1])".Trim()) } else { 0 }
        $reviewCount  = if ($reviewRows.Count -gt 0)  { [int]("$($reviewRows[-1])".Trim()) }  else { 0 }
        $script:Blocker = "Fixture reached terminal status '$($lease1.status)' on attempt 1 without entering review (findings=$findingCount, review_tasks=$reviewCount). REVIEW_ALL did not route a finding to PENDING_REVIEW for this fixture. No production hooks added."
        $script:Result = 'BLOCKED'
        return
    }

    Write-Step "PHASE 4 - Simulate real node loss and recover to attempt 2 (mode: $($script:Evidence.leaseExpiryMode))"
    Stop-Backend
    if ($NaturalLeaseExpiry) {
        # Wait for the lease to expire on its own; touch no lease columns.
        $waitSeconds = [int][math]::Ceiling(($LeaseDurationMs / 1000.0)) + 10
        Write-Info "  waiting ~${waitSeconds}s for the real lease to expire naturally (no DB writes)"
        Start-Sleep -Seconds $waitSeconds
    } else {
        Write-Info "  expiring the package lease (only lease timestamps; attempt_count untouched)"
        & $ExpireLease -PackageId $script:PackageId | Out-Null
    }
    Write-Info "  restarting backend; the real recovery scheduler will reclaim the expired lease"
    Start-Backend

    $recovered = & $WaitState -PackageId $script:PackageId -MinAttemptCount 2 `
        -ExpectedStatuses @('PROCESSING','PENDING_REVIEW','COMPLETED','EXPORTED') -TimeoutSeconds 150 -PollIntervalSeconds 3
    $script:Evidence.recoveredAttemptCount = $recovered.attemptCount
    $script:Evidence.fencingTokenAfter = $recovered.fencingToken
    Write-Info "  recovered: attempt_count=$($recovered.attemptCount) fencing=$($recovered.fencingToken) status=$($recovered.status)"

    if ($recovered.attemptCount -ne 2) { Fail "Expected recovered attempt_count=2 but observed $($recovered.attemptCount)." }
    if ($null -ne $lease1.fencingToken -and $null -ne $recovered.fencingToken -and $recovered.fencingToken -le $lease1.fencingToken) {
        Fail "Fencing token did not advance on recovery ($($lease1.fencingToken) -> $($recovered.fencingToken))."
    }

    Write-Step "PHASE 5 - Wait for PENDING_REVIEW, run Playwright, assert attempt_count stays 2"
    $pending = & $WaitState -PackageId $script:PackageId -ExpectedStatuses @('PENDING_REVIEW') -MinAttemptCount 2 -TimeoutSeconds 180 -PollIntervalSeconds 3
    $script:Evidence.statusBeforeReview = $pending.status
    $script:Evidence.attemptBeforeReview = $pending.attemptCount
    Write-Info "  before review: status=$($pending.status) attempt_count=$($pending.attemptCount)"
    if ($pending.attemptCount -ne 2) { Fail "attempt_count changed before review (expected 2, got $($pending.attemptCount))." }

    Write-Info "  running Playwright review spec for package $($script:PackageId)"
    $env:LV_OC_PACKAGE_ID = $script:PackageId
    Push-Location $FrontendDir
    try {
        & npx playwright test $SpecRelPath --reporter=list
        $pwExit = $LASTEXITCODE
    } finally {
        Pop-Location
        Remove-Item Env:\LV_OC_PACKAGE_ID -ErrorAction SilentlyContinue
    }

    # Collect Playwright artifacts (present only on failure per config).
    $pwResults = Join-Path $FrontendDir 'test-results'
    if (Test-Path $pwResults) {
        $trace = Get-ChildItem -Path $pwResults -Recurse -Filter 'trace.zip' -ErrorAction SilentlyContinue | Select-Object -First 1
        $shot  = Get-ChildItem -Path $pwResults -Recurse -Include '*.png' -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($null -ne $trace) { $script:Evidence.tracePath = $trace.FullName }
        if ($null -ne $shot)  { $script:Evidence.screenshotPath = $shot.FullName }
    }

    if ($pwExit -ne 0) {
        $script:Evidence.playwright = 'FAIL'
        Fail "Playwright review spec failed (exit $pwExit). See trace/screenshot in $pwResults."
    }
    $script:Evidence.playwright = 'PASS'

    # After review resume: attempt_count must still be 2.
    $afterResume = & $GetLease -PackageId $script:PackageId
    $script:Evidence.attemptAfterResume = $afterResume.attemptCount
    Write-Info "  after review/resume: status=$($afterResume.status) attempt_count=$($afterResume.attemptCount)"
    if ($afterResume.attemptCount -ne 2) { Fail "attempt_count changed after review/resume (expected 2, got $($afterResume.attemptCount))." }

    # Wait for a terminal state and assert attempt_count still 2.
    $final = & $WaitState -PackageId $script:PackageId -ExpectedStatuses @('COMPLETED','EXPORTED','READY_FOR_EXPORT','DELIVERED') -TimeoutSeconds 180 -PollIntervalSeconds 3
    $script:Evidence.finalStatus = $final.status
    $script:Evidence.finalAttemptCount = $final.attemptCount
    Write-Info "  final: status=$($final.status) attempt_count=$($final.attemptCount)"
    if ($final.attemptCount -ne 2) { Fail "final attempt_count changed (expected 2, got $($final.attemptCount))." }

    $script:Result = 'PASS'
}
catch {
    if ($script:Result -ne 'BLOCKED') {
        $script:Result = 'FAIL'
        $script:Blocker = $_.Exception.Message
    }
    Write-Host "`n[ERROR] $($_.Exception.Message)" -ForegroundColor Red
}
finally {
    Write-Step "CLEANUP - restore tenant policy"
    $policyRestored = 'NO'
    try {
        if (-not (Test-BackendReady)) {
            Write-Info "  backend not ready; attempting to start it to restore policy"
            if (-not $SkipBackendManagement) { try { Start-Backend } catch { Write-Host "  could not start backend for restore: $($_.Exception.Message)" -ForegroundColor Yellow } }
        }
        if (Test-BackendReady) {
            & $RestorePolicy -OriginalPolicy $script:OriginalPolicy | Out-Null
            $policyRestored = 'YES'
        } else {
            Write-Host "  backend unreachable; could NOT restore policy automatically." -ForegroundColor Yellow
        }
    } catch {
        Write-Host "  policy restore failed: $($_.Exception.Message)" -ForegroundColor Yellow
    }

    # Disposable package cleanup:
    #  - PASS + not -KeepTestData -> remove the single disposable package.
    #  - FAIL/BLOCKED, or -KeepTestData -> retain for investigation/evidence.
    $testDataDisposition = 'RETAINED'
    if ($script:PackageId) {
        if ($script:Result -eq 'PASS' -and -not $KeepTestData) {
            try {
                if (Test-BackendReady -or $true) {
                    & $RemovePackage -PackageId $script:PackageId | Out-Null
                    $testDataDisposition = 'REMOVED'
                }
            } catch {
                Write-Host "  cleanup of disposable package failed: $($_.Exception.Message)" -ForegroundColor Yellow
                $testDataDisposition = "RETAINED (cleanup failed)"
            }
        } elseif ($KeepTestData) {
            $testDataDisposition = 'RETAINED (-KeepTestData)'
        } else {
            $testDataDisposition = "RETAINED ($($script:Result) - kept for investigation)"
        }
    } else {
        $testDataDisposition = 'NONE (no package created)'
    }

    # Stop the backend we started, to leave the environment as we found it.
    if (-not $SkipBackendManagement -and $null -ne $script:BackendProcess -and -not $script:BackendProcess.HasExited) {
        Write-Info "  stopping backend PID $($script:BackendProcess.Id)"
        try { Stop-Process -Id $script:BackendProcess.Id -Force } catch { }
    }

    # ----- FINAL REPORT -----
    Write-Host "`n================ OPERATIONAL CONTINUITY ACCEPTANCE TEST 1 ================" -ForegroundColor Cyan
    Write-Host ("RESULT: {0}" -f $script:Result)
    if ($script:Blocker) { Write-Host ("REASON: {0}" -f $script:Blocker) }
    Write-Host "`nPackage evidence:"
    Write-Host ("  packageId              : {0}" -f $script:Evidence.packageId)
    Write-Host ("  initial attempt_count  : {0}" -f $script:Evidence.initialAttemptCount)
    Write-Host ("  recovered attempt_count: {0}" -f $script:Evidence.recoveredAttemptCount)
    Write-Host ("  fencing token before   : {0}" -f $script:Evidence.fencingTokenBefore)
    Write-Host ("  fencing token after    : {0}" -f $script:Evidence.fencingTokenAfter)
    Write-Host ("  status before review   : {0}" -f $script:Evidence.statusBeforeReview)
    Write-Host ("  attempt before review  : {0}" -f $script:Evidence.attemptBeforeReview)
    Write-Host ("  attempt after resume   : {0}" -f $script:Evidence.attemptAfterResume)
    Write-Host ("  final attempt_count    : {0}" -f $script:Evidence.finalAttemptCount)
    Write-Host ("  final package status   : {0}" -f $script:Evidence.finalStatus)
    Write-Host ("  lease expiry mode      : {0}" -f $script:Evidence.leaseExpiryMode)
    Write-Host "`nPlaywright:"
    Write-Host ("  result     : {0}" -f $script:Evidence.playwright)
    Write-Host ("  trace      : {0}" -f $(if ($script:Evidence.tracePath) { $script:Evidence.tracePath } else { '(none; only on failure)' }))
    Write-Host ("  screenshot : {0}" -f $(if ($script:Evidence.screenshotPath) { $script:Evidence.screenshotPath } else { '(none; only on failure)' }))
    Write-Host "`nCleanup:"
    Write-Host ("  original ReviewMode : {0}" -f $(if ($script:OriginalPolicy) { $script:OriginalPolicy.reviewMode } else { '(unknown)' }))
    Write-Host ("  temporary ReviewMode: REVIEW_ALL")
    Write-Host ("  policy restored     : {0}" -f $policyRestored)
    Write-Host ("  test data           : {0}" -f $testDataDisposition)
    Write-Host ("`nEvidence directory   : {0}" -f $EvidenceDir)
    Write-Host "==========================================================================" -ForegroundColor Cyan

    # Persist a machine-readable summary alongside logs.
    $summary = [ordered]@{
        result = $script:Result
        reason = $script:Blocker
        evidence = $script:Evidence
        originalReviewMode = if ($script:OriginalPolicy) { $script:OriginalPolicy.reviewMode } else { $null }
        temporaryReviewMode = 'REVIEW_ALL'
        policyRestored = $policyRestored
        testData = $testDataDisposition
    }
    $summary | ConvertTo-Json -Depth 8 | Set-Content -Path (Join-Path $EvidenceDir 'result.json') -Encoding UTF8

    if ($script:Transcribing) { try { Stop-Transcript | Out-Null } catch { } }

    if ($script:Result -eq 'PASS') { exit 0 }
    elseif ($script:Result -eq 'BLOCKED') { exit 3 }
    else { exit 1 }
}
