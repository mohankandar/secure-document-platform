#requires -Version 5.1
<#
.SYNOPSIS
    Polls a disposable package until it reaches one of the expected statuses (or times out).

.DESCRIPTION
    Reusable test-tooling helper. Repeatedly reads document_packages.status (via
    Get-PackageProcessingLease.ps1) until the status matches one of -ExpectedStatuses, or an
    optional -MinAttemptCount is reached, or -TimeoutSeconds elapses.

    Read-only against the database. Fails fast with a clear message on timeout.

.PARAMETER PackageId
    Disposable package id to poll.

.PARAMETER ExpectedStatuses
    One or more package statuses that satisfy the wait (for example PROCESSING, PENDING_REVIEW,
    COMPLETED, EXPORTED).

.PARAMETER MinAttemptCount
    Optional. When set, the wait is only satisfied once attempt_count >= this value AND status
    matches (if ExpectedStatuses provided) OR status matches when this is 0.

.PARAMETER TimeoutSeconds
    Maximum time to wait before throwing. Default 120.

.PARAMETER PollIntervalSeconds
    Seconds between polls. Default 3.

.OUTPUTS
    The final lease/status PSCustomObject (from Get-PackageProcessingLease.ps1) once satisfied.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $PackageId,
    [string[]] $ExpectedStatuses = @(),
    [int] $MinAttemptCount = 0,
    [int] $TimeoutSeconds = 120,
    [int] $PollIntervalSeconds = 3
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$getLease = Join-Path $PSScriptRoot 'Get-PackageProcessingLease.ps1'

# Normalize the (possibly empty) status list into a real array for StrictMode-safe .Count/-contains.
$expected = @($ExpectedStatuses)

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$last = $null
$attempts = 0

while ((Get-Date) -lt $deadline) {
    $attempts++
    $last = & $getLease -PackageId $PackageId

    if ($null -ne $last) {
        $statusOk = ($expected.Count -eq 0) -or ($expected -contains $last.status)
        $attemptOk = ($MinAttemptCount -le 0) -or ($null -ne $last.attemptCount -and $last.attemptCount -ge $MinAttemptCount)
        if ($statusOk -and $attemptOk) {
            Write-Host ("  [wait] satisfied after {0} poll(s): status={1} attempt_count={2}" -f `
                $attempts, $last.status, $last.attemptCount)
            return $last
        }
    }

    $desc = if ($null -ne $last) { "status=$($last.status) attempt_count=$($last.attemptCount)" } else { 'package not found yet' }
    Write-Host ("  [wait] {0} (want status in [{1}]{2})" -f `
        $desc, ($expected -join ','), $(if ($MinAttemptCount -gt 0) { " attempt>=$MinAttemptCount" } else { '' }))
    Start-Sleep -Seconds $PollIntervalSeconds
}

$finalDesc = if ($null -ne $last) { "last status=$($last.status), attempt_count=$($last.attemptCount)" } else { 'package never appeared' }
throw ("Wait-ForPackageState timed out after {0}s for package {1}. Wanted status in [{2}]{3}; {4}." -f `
    $TimeoutSeconds, $PackageId, ($expected -join ','), `
    $(if ($MinAttemptCount -gt 0) { " with attempt_count>=$MinAttemptCount" } else { '' }), $finalDesc)
