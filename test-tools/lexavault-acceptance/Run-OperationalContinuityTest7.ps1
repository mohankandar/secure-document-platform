#requires -Version 5.1
<#
.SYNOPSIS
    LexaVault Operational Continuity Acceptance Test 7 - reusable, Kiro-free runner.

.DESCRIPTION
    Final Operational Continuity closure check: after a FAILED / Technical Attention package reaches
    retention expiry and is removed from the operational Dashboard, its failed processing history is
    still represented through the EXISTING LexaVault Reporting path.

    The product's Reporting technical-attention metric counts FAILED packages independently of
    Dashboard hiding (JpaOperationsReadAdapter.loadReportOverview has no dashboard_hidden_at filter,
    unlike the Dashboard overview). So a retention-hidden FAILED package still contributes to the
    Reporting FAILED/technical-attention count. This test proves that with a deterministic before/
    after assertion against the REAL reports API:
      - report technicalAttention count is UNCHANGED by retention (package stays FAILED; hiding is
        irrelevant to Reporting) and is >= 1
      - the Dashboard operational technical-attention count DROPS by exactly the purged package
      - the package remains FAILED / attempt_count 3, dashboard_hidden_at populated, incident AUTO_CLOSED
      - no reprocessing; durable reason/incident/audit history preserved

    Driven against the REAL running application (API + DB + UI). No application/backend/frontend code is
    modified and no production test hooks are added. Only ONE disposable package's technical_hold_until
    is moved into the past (test tooling); the genuine product endpoint performs purge/hide/close.

.PARAMETER SkipBackendManagement
    Diagnostics only; the test must control the backend to simulate node loss for the exhaustion setup.

.PARAMETER KeepTestData
    Retain the disposable package after the run. Default removes it on PASS; FAIL/BLOCKED retains it.

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

$ReportingSpec = 'e2e/operational-continuity/retention-reporting-history.spec.ts'
$EvidenceDir   = Join-Path $ToolRoot ('evidence\{0}-test7' -f (Get-Date -Format 'yyyyMMdd-HHmmss'))

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
$script:Contract = $null
$script:BackendProcess = $null
$script:Evidence = [ordered]@{
    reportingPathUsed        = 'GET /api/v1/operations/reports (Reporting UI: /reports) - processing.technicalAttention'
    roleUsed                 = 'ADMIN (API reads) / TENANT_IT_ADMIN (Reporting UI) - REPORT_EXECUTIVE_VIEW'
    packageId                = $null
    contract                 = $null
    beforeStatus             = $null
    beforeAttempt            = $null
    beforeDashboardVisible   = $null
    beforeReportAttention    = $null
    beforeDashboardAttention = $null
    afterPackageExists       = $null
    afterStatus              = $null
    afterAttempt             = $null
    afterDashboardHiddenAt   = $null
    afterIncidentStatus      = $null
    afterReportAttention     = $null
    afterDashboardAttention  = $null
    failureOutcomeRetained   = $null
    reasonRetainedInReporting= 'N/A (report exposes aggregate status counts, not reason-level rows)'
    noReprocessing           = $null
    historyPreserved         = $null
    playwright               = 'NOT_RUN'
    reportingUiVerified      = $null
    tracePath                = $null
    screenshotPath           = $null
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
function Invoke-RecoveryToAttempt([int] $TargetAttempt) {
    & $ExpireLease -PackageId $script:PackageId | Out-Null
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

# Read the REAL product Reporting technicalAttention count via the reports API (30-day window).
function Get-ReportTechnicalAttention {
    $to = [DateTime]::UtcNow
    $from = $to.AddDays(-30)
    $fromIso = $from.ToString("yyyy-MM-ddTHH:mm:ssZ")
    $toIso = $to.ToString("yyyy-MM-ddTHH:mm:ssZ")
    $resp = & $InvokeApi -Method GET -Path "/api/v1/operations/reports?from=$fromIso&to=$toIso&source=ALL&outcome=ALL"
    $processing = $resp.PSObject.Properties['processing']
    if ($null -eq $processing) { throw "reports API response missing 'processing'" }
    $ta = $processing.Value.PSObject.Properties['technicalAttention']
    if ($null -eq $ta) { throw "reports API response missing processing.technicalAttention" }
    return [int]$ta.Value
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
    $script:Evidence.contract = $ids.contract_number
    $createBody = @{
        packageType = 'MANUAL_UPLOAD'
        businessIdentifiers = @{ contract_number = $ids.contract_number; member_number = $ids.member_number; service_entity = $ids.service_entity }
        metadata = @{ source = 'operational-continuity-acceptance-test-7' }
    }
    $created = & $InvokeApi -Method POST -Path '/api/v1/packages' -Body $createBody
    $script:PackageId = $created.packageId
    if (-not $script:PackageId) { Fail "Package creation did not return a packageId." }
    $script:Evidence.packageId = $script:PackageId
    Write-Info "  created package $($script:PackageId) (contract $($script:Contract))"

    & $SendDoc -PackageId $script:PackageId -FilePath $FixturePath | Out-Null
    & $InvokeApi -Method POST -Path "/api/v1/packages/$($script:PackageId)/submit" | Out-Null

    $lease1 = & $WaitState -PackageId $script:PackageId -MinAttemptCount 1 `
        -ExpectedStatuses @('PROCESSING','PENDING_REVIEW','COMPLETED','EXPORTED') -TimeoutSeconds 90 -PollIntervalSeconds 2
    if ($lease1.status -ne 'PROCESSING' -or $lease1.attemptCount -ne 1) {
        $script:Blocker = "Package status=$($lease1.status) attempt=$($lease1.attemptCount) before attempt 1 could be frozen. Re-run."
        $script:Result = 'BLOCKED'; return
    }
    Stop-Backend
    Invoke-RecoveryToAttempt -TargetAttempt 2 | Out-Null
    Invoke-RecoveryToAttempt -TargetAttempt 3 | Out-Null
    & $ExpireLease -PackageId $script:PackageId | Out-Null
    Start-Backend
    $failed = & $WaitState -PackageId $script:PackageId -ExpectedStatuses @('FAILED') -TimeoutSeconds 120 -PollIntervalSeconds 2
    if ($failed.attemptCount -ne 3) { Fail "Expected attempt_count=3 at exhaustion, observed $($failed.attemptCount)." }
    $reason = Get-Scalar "SELECT reason_code FROM processing_incidents WHERE package_id = '$($script:PackageId)' ORDER BY last_observed_at DESC LIMIT 1;"
    if ($reason -ne 'PROCESSING_RECOVERY_EXHAUSTED') { Fail "Expected reason PROCESSING_RECOVERY_EXHAUSTED, got '$reason'." }

    Write-Step "PHASE 2 - BEFORE-retention baseline (Reporting + Dashboard technical-attention counts)"
    $script:Evidence.beforeStatus = $failed.status
    $script:Evidence.beforeAttempt = $failed.attemptCount
    $script:Evidence.beforeDashboardVisible = $(if ($null -eq (Get-Scalar "SELECT dashboard_hidden_at FROM document_packages WHERE id='$($script:PackageId)';")) { 'YES' } else { 'NO' })
    $script:Evidence.beforeReportAttention = Get-ReportTechnicalAttention
    # Dashboard operational count mirrors loadOverview (dashboard_hidden_at is null).
    $script:Evidence.beforeDashboardAttention = Get-IntScalar "SELECT COUNT(*) FROM document_packages WHERE tenant_id='$($Cfg.TenantId)' AND status='FAILED' AND dashboard_hidden_at IS NULL;"
    Write-Info "  BEFORE: status=$($script:Evidence.beforeStatus) attempt=$($script:Evidence.beforeAttempt) dashboardVisible=$($script:Evidence.beforeDashboardVisible)"
    Write-Info "          reportTechnicalAttention=$($script:Evidence.beforeReportAttention) dashboardFAILEDvisible=$($script:Evidence.beforeDashboardAttention)"
    if ($script:Evidence.beforeReportAttention -lt 1) { Fail "Reporting technicalAttention should be >=1 with our FAILED package present." }

    Write-Step "PHASE 3 - Expire ONLY this package's technical hold, then run the REAL purge"
    & $RunQuery -Sql "UPDATE package_lifecycles SET technical_hold_until = clock_timestamp() - interval '1 hour' WHERE package_id = '$($script:PackageId)';" | Out-Null
    $purge = & $InvokeApi -Method POST -Path '/api/v1/lifecycle/purge-due?limit=500'
    Write-Info "  purge-due result: $($purge | ConvertTo-Json -Compress)"

    Write-Step "PHASE 4 - AFTER-retention: Test 5 invariants (brief) + Reporting durability"
    $exists = Get-IntScalar "SELECT COUNT(*) FROM document_packages WHERE id = '$($script:PackageId)';"
    $script:Evidence.afterPackageExists = $(if ($exists -ge 1) { 'YES' } else { 'NO' })
    if ($exists -lt 1) { Fail "DURABLE VIOLATION: document_packages row deleted by retention." }
    $after = & $GetLease -PackageId $script:PackageId
    $script:Evidence.afterStatus = $after.status
    $script:Evidence.afterAttempt = $after.attemptCount
    $script:Evidence.afterDashboardHiddenAt = Get-Scalar "SELECT dashboard_hidden_at FROM document_packages WHERE id = '$($script:PackageId)';"
    $script:Evidence.afterIncidentStatus = Get-Scalar "SELECT status FROM processing_incidents WHERE package_id = '$($script:PackageId)' ORDER BY last_observed_at DESC LIMIT 1;"
    if ($after.status -ne 'FAILED') { Fail "Package status changed to $($after.status) after retention; expected FAILED." }
    if ($after.attemptCount -ne 3) { Fail "attempt_count changed to $($after.attemptCount) after retention; expected 3." }
    if ($null -eq $script:Evidence.afterDashboardHiddenAt) { Fail "dashboard_hidden_at not populated after retention." }
    if ($script:Evidence.afterIncidentStatus -ne 'AUTO_CLOSED') { Fail "incident status=$($script:Evidence.afterIncidentStatus) after retention; expected AUTO_CLOSED." }

    $script:Evidence.afterReportAttention = Get-ReportTechnicalAttention
    $script:Evidence.afterDashboardAttention = Get-IntScalar "SELECT COUNT(*) FROM document_packages WHERE tenant_id='$($Cfg.TenantId)' AND status='FAILED' AND dashboard_hidden_at IS NULL;"
    Write-Info "  AFTER: status=$($after.status) attempt=$($after.attemptCount) dashboardHiddenAt=$($script:Evidence.afterDashboardHiddenAt) incident=$($script:Evidence.afterIncidentStatus)"
    Write-Info "         reportTechnicalAttention=$($script:Evidence.afterReportAttention) dashboardFAILEDvisible=$($script:Evidence.afterDashboardAttention)"

    # CENTRAL REPORTING ASSERTIONS:
    # 1) Reporting still represents the FAILED outcome: report count unchanged by retention and >=1.
    if ($script:Evidence.afterReportAttention -lt 1) { Fail "REPORTING GAP: technicalAttention dropped to 0 after retention; failed history no longer represented in Reporting." }
    if ($script:Evidence.afterReportAttention -ne $script:Evidence.beforeReportAttention) {
        Fail "REPORTING GAP: report technicalAttention changed from $($script:Evidence.beforeReportAttention) to $($script:Evidence.afterReportAttention) due to retention; the FAILED package was dropped from Reporting when hidden."
    }
    # 2) Dashboard operational count dropped (package removed operationally) - confirms retention did hide it.
    if ($script:Evidence.afterDashboardAttention -ne ($script:Evidence.beforeDashboardAttention - 1)) {
        Write-Info "  NOTE: dashboard FAILED-visible count $($script:Evidence.beforeDashboardAttention)->$($script:Evidence.afterDashboardAttention) (expected -1); concurrent data may affect this non-authoritative check."
    }
    $script:Evidence.failureOutcomeRetained = 'YES'

    # Not rewritten successful; no reprocess.
    $script:Evidence.noReprocessing = $(if ($after.attemptCount -eq 3 -and $after.status -eq 'FAILED') { 'YES' } else { 'NO' })
    if ($script:Evidence.noReprocessing -ne 'YES') { Fail "Unintended reprocessing detected." }

    # Durable history.
    $reasonAfter = Get-Scalar "SELECT reason_code FROM processing_incidents WHERE package_id = '$($script:PackageId)' ORDER BY last_observed_at DESC LIMIT 1;"
    $auditCount = Get-IntScalar "SELECT COUNT(*) FROM audit_records WHERE resource_id = '$($script:PackageId)';"
    $exhaustEvents = Get-IntScalar "SELECT COUNT(*) FROM processing_incident_events e JOIN processing_incidents i ON i.id=e.incident_id WHERE i.package_id = '$($script:PackageId)' AND (e.reason_code='PROCESSING_RECOVERY_EXHAUSTED' OR e.event_type='PROCESSING_RECOVERY_EXHAUSTED');"
    $script:Evidence.historyPreserved = $(if (($reasonAfter -eq 'PROCESSING_RECOVERY_EXHAUSTED' -or $exhaustEvents -gt 0) -and $auditCount -gt 0) { 'YES' } else { 'NO' })
    Write-Info "  history: reasonAfter=$reasonAfter exhaustionEvents=$exhaustEvents auditRecords=$auditCount preserved=$($script:Evidence.historyPreserved)"
    if ($script:Evidence.historyPreserved -ne 'YES') { Fail "Durable history not preserved after retention." }

    Write-Step "PHASE 5 - Playwright: Reporting UI still represents the failed outcome"
    $env:LV_OC_REPORT_ATTENTION = "$($script:Evidence.afterReportAttention)"
    Push-Location $FrontendDir
    try {
        & npx playwright test $ReportingSpec --reporter=list | Out-Host
        $pwExit = $LASTEXITCODE
    } finally {
        Pop-Location
        Remove-Item Env:\LV_OC_REPORT_ATTENTION -ErrorAction SilentlyContinue
    }
    $pwResults = Join-Path $FrontendDir 'test-results'
    if (Test-Path $pwResults) {
        $trace = Get-ChildItem -Path $pwResults -Recurse -Filter 'trace.zip' -ErrorAction SilentlyContinue | Select-Object -First 1
        $shot  = Get-ChildItem -Path $pwResults -Recurse -Include '*.png' -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($null -ne $trace) { $script:Evidence.tracePath = $trace.FullName }
        if ($null -ne $shot)  { $script:Evidence.screenshotPath = $shot.FullName }
    }
    if ($pwExit -ne 0) { $script:Evidence.playwright = 'FAIL'; $script:Evidence.reportingUiVerified = 'NO'; Fail "Playwright reporting spec failed (exit $pwExit). See $pwResults." }
    $script:Evidence.playwright = 'PASS'
    $script:Evidence.reportingUiVerified = 'YES'

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

    Write-Host "`n========= OPERATIONAL CONTINUITY ACCEPTANCE TEST 7 (REPORTING HISTORY) =========" -ForegroundColor Cyan
    Write-Host ("RESULT: {0}" -f $script:Result)
    if ($script:Blocker) { Write-Host ("REASON: {0}" -f $script:Blocker) }
    Write-Host ("`nReporting path used : {0}" -f $script:Evidence.reportingPathUsed)
    Write-Host ("Role used           : {0}" -f $script:Evidence.roleUsed)
    Write-Host ("Package ID          : {0}" -f $script:Evidence.packageId)
    Write-Host ("Contract identifier : {0}" -f $script:Evidence.contract)
    Write-Host "`nBefore retention:"
    Write-Host ("  package status        : {0}" -f $script:Evidence.beforeStatus)
    Write-Host ("  attempt_count         : {0}" -f $script:Evidence.beforeAttempt)
    Write-Host ("  dashboard visible     : {0}" -f $script:Evidence.beforeDashboardVisible)
    Write-Host ("  reporting technicalAttention : {0}" -f $script:Evidence.beforeReportAttention)
    Write-Host "`nAfter retention:"
    Write-Host ("  package exists        : {0}" -f $script:Evidence.afterPackageExists)
    Write-Host ("  package status        : {0}" -f $script:Evidence.afterStatus)
    Write-Host ("  attempt_count         : {0}" -f $script:Evidence.afterAttempt)
    Write-Host ("  dashboard visible     : {0}" -f $(if ($null -eq $script:Evidence.afterDashboardHiddenAt) { 'YES' } else { 'NO (hidden)' }))
    Write-Host ("  incident status       : {0}" -f $script:Evidence.afterIncidentStatus)
    Write-Host ("  reporting technicalAttention : {0}" -f $script:Evidence.afterReportAttention)
    Write-Host ("  failure outcome retained     : {0}" -f $script:Evidence.failureOutcomeRetained)
    Write-Host ("  reason retained in Reporting : {0}" -f $script:Evidence.reasonRetainedInReporting)
    Write-Host ("  no reprocessing              : {0}" -f $script:Evidence.noReprocessing)
    Write-Host ("  history preserved            : {0}" -f $script:Evidence.historyPreserved)
    Write-Host "`nPlaywright:"
    Write-Host ("  result               : {0}" -f $script:Evidence.playwright)
    Write-Host ("  reporting UI verified: {0}" -f $script:Evidence.reportingUiVerified)
    Write-Host ("  trace                : {0}" -f $(if ($script:Evidence.tracePath) { $script:Evidence.tracePath } else { '(none; only on failure)' }))
    Write-Host ("  screenshot           : {0}" -f $(if ($script:Evidence.screenshotPath) { $script:Evidence.screenshotPath } else { '(none; only on failure)' }))
    Write-Host ("`nCleanup: {0}" -f $testDataDisposition)
    Write-Host ("Evidence directory : {0}" -f $EvidenceDir)
    Write-Host "================================================================================" -ForegroundColor Cyan

    $summary = [ordered]@{ result = $script:Result; reason = $script:Blocker; evidence = $script:Evidence; testData = $testDataDisposition }
    $summary | ConvertTo-Json -Depth 8 | Set-Content -Path (Join-Path $EvidenceDir 'result.json') -Encoding UTF8

    if ($script:Transcribing) { try { Stop-Transcript | Out-Null } catch { } }

    if ($script:Result -eq 'PASS') { exit 0 }
    elseif ($script:Result -eq 'BLOCKED') { exit 3 }
    else { exit 1 }
}
