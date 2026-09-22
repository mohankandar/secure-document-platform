#requires -Version 5.1
<#
.SYNOPSIS
    LexaVault Operational Continuity Acceptance Test 6 - reusable, Kiro-free runner.

.DESCRIPTION
    End-to-end validates the three remaining terminal Tenant-IT dispositions, each on its OWN fresh
    disposable FAILED / Technical Attention package (recovery-exhausted), because they are mutually
    exclusive operator decisions:

        A. Resolve - no reprocess  (RESOLVE_NO_REPROCESS)
        B. Mark superseded         (SUPERSEDED)
        C. Mark duplicate          (DUPLICATE)

    For each: no reprocessing occurs, attempt_count stays 3, the historical FAILED processing outcome
    remains truthful, the disposition is persisted using the product's implementation (processing
    incident -> status RESOLVED, business_outcome = RESOLVED_NO_REPROCESS | SUPERSEDED | DUPLICATE),
    and reason/incident/audit history remains durable.

    Driven against the REAL running application (API + DB + UI). No application/backend/frontend code is
    modified and no production test hooks are added. attempt_count is never set by the harness. The
    disposition is performed via the genuine Support Incident UI (Playwright), not by calling the
    backend endpoint directly. Accelerated exhaustion setup only (Test 2 verified natural expiry).

.PARAMETER SkipBackendManagement
    Diagnostics only; the test must control the backend to simulate node loss for the exhaustion setup.

.PARAMETER KeepTestData
    Retain the disposable packages after the run. Default removes each on PASS via Remove-TestPackage.ps1;
    FAIL/BLOCKED always retains the affected package for investigation.

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

$DispositionSpec = 'e2e/operational-continuity/support-incident-disposition.spec.ts'
$EvidenceDir     = Join-Path $ToolRoot ('evidence\{0}-test6' -f (Get-Date -Format 'yyyyMMdd-HHmmss'))

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
$script:BackendProcess = $null
# Ordered scenario definitions: action -> expected persisted business_outcome.
$script:Scenarios = @(
    [ordered]@{ key = 'RESOLVE';    action = 'RESOLVE_NO_REPROCESS'; expectedOutcome = 'RESOLVED_NO_REPROCESS' },
    [ordered]@{ key = 'SUPERSEDED'; action = 'SUPERSEDED';           expectedOutcome = 'SUPERSEDED' },
    [ordered]@{ key = 'DUPLICATE';  action = 'DUPLICATE';            expectedOutcome = 'DUPLICATE' }
)
$script:Evidence = [ordered]@{}
foreach ($s in $script:Scenarios) {
    $script:Evidence[$s.key] = [ordered]@{
        packageId = $null; incidentId = $null; attemptBefore = $null; attemptAfter = $null;
        packageStatus = $null; incidentStatus = $null; businessOutcome = $null;
        historyPreserved = $null; noReprocessing = $null; playwright = 'NOT_RUN'; cleanup = 'RETAINED'
    }
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
    Write-Info "  starting backend"
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

# Recover an already-created+submitted package's expired lease to a target attempt (PROCESSING), then
# stop the backend promptly. Returns the observed lease object. Uses $pkgId (script scope).
function Invoke-RecoveryToAttempt([string] $PkgId, [int] $TargetAttempt) {
    & $ExpireLease -PackageId $PkgId | Out-Null
    Start-Backend | Out-Null
    $rec = & $WaitState -PackageId $PkgId -MinAttemptCount $TargetAttempt `
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

# Creates a fresh disposable package and drives it to exhaustion (FAILED/attempt3/PROCESSING_RECOVERY_EXHAUSTED).
# Assumes the backend is currently UP. Returns @{ packageId; incidentId }.
function New-ExhaustedPackage {
    $ids = New-LvTestIdentifiers
    $createBody = @{
        packageType = 'MANUAL_UPLOAD'
        businessIdentifiers = @{ contract_number = $ids.contract_number; member_number = $ids.member_number; service_entity = $ids.service_entity }
        metadata = @{ source = 'operational-continuity-acceptance-test-6' }
    }
    $created = & $InvokeApi -Method POST -Path '/api/v1/packages' -Body $createBody
    $pkgId = $created.packageId
    if (-not $pkgId) { Fail "Package creation did not return a packageId." }
    Write-Info "  created package $pkgId (contract $($ids.contract_number))"
    & $SendDoc -PackageId $pkgId -FilePath $FixturePath | Out-Null
    & $InvokeApi -Method POST -Path "/api/v1/packages/$pkgId/submit" | Out-Null

    $lease1 = & $WaitState -PackageId $pkgId -MinAttemptCount 1 `
        -ExpectedStatuses @('PROCESSING','PENDING_REVIEW','COMPLETED','EXPORTED') -TimeoutSeconds 90 -PollIntervalSeconds 2
    if ($lease1.status -ne 'PROCESSING' -or $lease1.attemptCount -ne 1) {
        $script:Blocker = "Package status=$($lease1.status) attempt=$($lease1.attemptCount) before attempt 1 could be frozen. Re-run."
        $script:Result = 'BLOCKED'
        throw [System.OperationCanceledException]::new('BLOCKED')
    }
    Stop-Backend
    Invoke-RecoveryToAttempt -PkgId $pkgId -TargetAttempt 2 | Out-Null
    Invoke-RecoveryToAttempt -PkgId $pkgId -TargetAttempt 3 | Out-Null
    & $ExpireLease -PackageId $pkgId | Out-Null
    Start-Backend
    $failed = & $WaitState -PackageId $pkgId -ExpectedStatuses @('FAILED') -TimeoutSeconds 120 -PollIntervalSeconds 2
    if ($failed.attemptCount -ne 3) { Fail "Expected attempt_count=3 at exhaustion, observed $($failed.attemptCount)." }
    $reason = Get-Scalar "SELECT reason_code FROM processing_incidents WHERE package_id = '$pkgId' ORDER BY last_observed_at DESC LIMIT 1;"
    if ($reason -ne 'PROCESSING_RECOVERY_EXHAUSTED') { Fail "Expected reason PROCESSING_RECOVERY_EXHAUSTED, got '$reason'." }
    $incId = Get-Scalar "SELECT id FROM processing_incidents WHERE package_id = '$pkgId' ORDER BY last_observed_at DESC LIMIT 1;"
    return @{ packageId = $pkgId; incidentId = $incId }
}

function Invoke-DispositionSpec([string] $IncidentId, [string] $Action) {
    $ex = 1
    $env:LV_OC_INCIDENT_ID = $IncidentId
    $env:LV_OC_ACTION = $Action
    Push-Location $FrontendDir
    try {
        & npx playwright test $DispositionSpec --reporter=list | Out-Host
        $ex = $LASTEXITCODE
    } finally {
        Pop-Location
        Remove-Item Env:\LV_OC_INCIDENT_ID -ErrorAction SilentlyContinue
        Remove-Item Env:\LV_OC_ACTION -ErrorAction SilentlyContinue
    }
    return $ex
}

try {
    Write-Step "PHASE 0 - Prerequisites"
    $prereq = Test-LvPrerequisites -Config $Cfg
    foreach ($c in $prereq.checks) { Write-Info ("  [{0}] {1} - {2}" -f $(if ($c.ok) { 'OK  ' } else { 'FAIL' }), $c.name, $c.detail) }
    if (-not $prereq.ok) { Fail ("Prerequisite check failed:`n  - " + ($prereq.failures -join "`n  - ")) }
    if ($Cfg.TesseractPath) { $env:SECUREDOC_OCR_TESSERACT_PATH = $Cfg.TesseractPath }

    Write-Step "Start backend"
    Start-Backend

    foreach ($scenario in $script:Scenarios) {
        $key = $scenario.key
        $ev = $script:Evidence[$key]
        Write-Step "SCENARIO $key - create fresh exhausted package"
        $pkg = New-ExhaustedPackage
        $ev.packageId = $pkg.packageId
        $ev.incidentId = $pkg.incidentId

        $before = & $GetLease -PackageId $pkg.packageId
        $ev.attemptBefore = $before.attemptCount
        Write-Info "  BEFORE: status=$($before.status) attempt=$($before.attemptCount) incidentId=$($pkg.incidentId)"
        if ($before.attemptCount -ne 3 -or $before.status -ne 'FAILED') { Fail "$key precondition wrong (status=$($before.status) attempt=$($before.attemptCount))." }

        Write-Step "SCENARIO $key - perform disposition '$($scenario.action)' via the real Support Incident UI"
        $pwExit = Invoke-DispositionSpec -IncidentId $pkg.incidentId -Action $scenario.action
        $ev.playwright = $(if ($pwExit -eq 0) { 'PASS' } else { 'FAIL' })
        if ($pwExit -ne 0) { Fail "$key Playwright disposition failed (exit $pwExit)." }

        # Assert after-state.
        $after = & $GetLease -PackageId $pkg.packageId
        $ev.attemptAfter = $after.attemptCount
        $ev.packageStatus = $after.status
        $ev.incidentStatus = Get-Scalar "SELECT status FROM processing_incidents WHERE package_id = '$($pkg.packageId)' ORDER BY last_observed_at DESC LIMIT 1;"
        $ev.businessOutcome = Get-Scalar "SELECT business_outcome FROM processing_incidents WHERE package_id = '$($pkg.packageId)' ORDER BY last_observed_at DESC LIMIT 1;"
        $reasonAfter = Get-Scalar "SELECT reason_code FROM processing_incidents WHERE package_id = '$($pkg.packageId)' ORDER BY last_observed_at DESC LIMIT 1;"
        $auditCount = Get-IntScalar "SELECT COUNT(*) FROM audit_records WHERE resource_id = '$($pkg.packageId)';"
        $exhaustEvents = Get-IntScalar "SELECT COUNT(*) FROM processing_incident_events e JOIN processing_incidents i ON i.id=e.incident_id WHERE i.package_id = '$($pkg.packageId)' AND (e.reason_code='PROCESSING_RECOVERY_EXHAUSTED' OR e.event_type='PROCESSING_RECOVERY_EXHAUSTED');"

        $ev.noReprocessing = $(if ($after.attemptCount -eq 3 -and $after.status -eq 'FAILED') { 'YES' } else { 'NO' })
        $ev.historyPreserved = $(if (($reasonAfter -eq 'PROCESSING_RECOVERY_EXHAUSTED' -or $exhaustEvents -gt 0) -and $auditCount -gt 0) { 'YES' } else { 'NO' })
        Write-Info "  AFTER: status=$($after.status) attempt=$($after.attemptCount) incidentStatus=$($ev.incidentStatus) businessOutcome=$($ev.businessOutcome)"
        Write-Info "         reasonAfter=$reasonAfter exhaustionEvents=$exhaustEvents auditRecords=$auditCount noReprocess=$($ev.noReprocessing) history=$($ev.historyPreserved)"

        if ($after.attemptCount -ne 3) { Fail "$key attempt_count changed to $($after.attemptCount) (expected 3)." }
        if ($after.status -ne 'FAILED') { Fail "$key package status changed to $($after.status) (expected FAILED - outcome must stay truthful)." }
        if ($ev.incidentStatus -ne 'RESOLVED') { Fail "$key incident status=$($ev.incidentStatus) (expected RESOLVED)." }
        if ($ev.businessOutcome -ne $scenario.expectedOutcome) { Fail "$key persisted disposition business_outcome='$($ev.businessOutcome)' (expected '$($scenario.expectedOutcome)')." }
        if ($ev.historyPreserved -ne 'YES') { Fail "$key durable history not preserved." }
        if ($ev.noReprocessing -ne 'YES') { Fail "$key unintended reprocessing detected." }

        # Per-scenario cleanup on success (each scenario is independently disposable).
        if (-not $KeepTestData) {
            try { & $RemovePackage -PackageId $pkg.packageId | Out-Null; $ev.cleanup = 'REMOVED' }
            catch { Write-Host "  cleanup failed: $($_.Exception.Message)" -ForegroundColor Yellow; $ev.cleanup = 'RETAINED (cleanup failed)' }
        } else { $ev.cleanup = 'RETAINED (-KeepTestData)' }
    }

    $script:Result = 'PASS'
}
catch {
    if ($script:Result -ne 'BLOCKED') { $script:Result = 'FAIL'; $script:Blocker = $_.Exception.Message }
    Write-Host "`n[$($script:Result)] $($_.Exception.Message)" -ForegroundColor Red
}
finally {
    # Any scenario package not yet cleaned (FAIL/BLOCKED path) is retained for investigation by default.
    foreach ($s in $script:Scenarios) {
        $ev = $script:Evidence[$s.key]
        if ($ev.cleanup -eq 'RETAINED' -and $ev.packageId) {
            $ev.cleanup = "RETAINED ($($script:Result) - kept for investigation)"
        }
    }

    if (-not $SkipBackendManagement -and $null -ne $script:BackendProcess -and -not $script:BackendProcess.HasExited) {
        Write-Info "  stopping backend PID $($script:BackendProcess.Id)"
        try { Stop-Process -Id $script:BackendProcess.Id -Force } catch { }
    }

    Write-Host "`n========= OPERATIONAL CONTINUITY ACCEPTANCE TEST 6 (REMAINING DISPOSITIONS) =========" -ForegroundColor Cyan
    Write-Host ("RESULT: {0}" -f $script:Result)
    if ($script:Blocker) { Write-Host ("REASON: {0}" -f $script:Blocker) }
    foreach ($s in $script:Scenarios) {
        $ev = $script:Evidence[$s.key]
        $label = switch ($s.key) { 'RESOLVE' { 'Resolve scenario' } 'SUPERSEDED' { 'Superseded scenario' } 'DUPLICATE' { 'Duplicate scenario' } default { $s.key } }
        Write-Host "`n$label ($($s.action)):"
        Write-Host ("  package id            : {0}" -f $ev.packageId)
        Write-Host ("  attempt_count before/after : {0} / {1}" -f $ev.attemptBefore, $ev.attemptAfter)
        Write-Host ("  package status        : {0}" -f $ev.packageStatus)
        Write-Host ("  incident status       : {0}" -f $ev.incidentStatus)
        Write-Host ("  persisted disposition : {0}" -f $ev.businessOutcome)
        Write-Host ("  history preserved     : {0}" -f $ev.historyPreserved)
        Write-Host ("  No unintended reprocessing : {0}" -f $ev.noReprocessing)
        Write-Host ("  playwright            : {0}" -f $ev.playwright)
        Write-Host ("  cleanup               : {0}" -f $ev.cleanup)
    }
    Write-Host ("`nEvidence directory : {0}" -f $EvidenceDir)
    Write-Host "=====================================================================================" -ForegroundColor Cyan

    $summary = [ordered]@{ result = $script:Result; reason = $script:Blocker; scenarios = $script:Evidence }
    $summary | ConvertTo-Json -Depth 8 | Set-Content -Path (Join-Path $EvidenceDir 'result.json') -Encoding UTF8

    if ($script:Transcribing) { try { Stop-Transcript | Out-Null } catch { } }

    if ($script:Result -eq 'PASS') { exit 0 }
    elseif ($script:Result -eq 'BLOCKED') { exit 3 }
    else { exit 1 }
}
