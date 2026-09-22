#requires -Version 5.1
<#
.SYNOPSIS
    Forces the processing lease of ONE disposable package to be expired, to accelerate real
    node-loss recovery.

.DESCRIPTION
    Reusable test-tooling helper. Simulating true node loss means the crashed node stops sending
    heartbeats and its lease expires after lease-duration-ms (30s by default). To keep the
    acceptance test fast and deterministic, this helper moves ONLY the lease timestamps
    (lease_expires_at, heartbeat_at) into the past for a single, explicitly-provided package id.

    IMPORTANT: This helper does NOT set attempt_count. It never touches attempt_count or
    fencing_token. The attempt_count increment and fencing-token advance are produced ENTIRELY by
    the real PackageProcessingRecoveryScheduler running the production recovery SQL after restart.
    So the recovery being asserted is genuine, not fabricated.

    Scope safety: it only updates the row whose package_id exactly matches -PackageId (validated as
    a UUID). It does not touch any other package or historical data.

.PARAMETER PackageId
    The disposable package id whose lease should be expired.

.OUTPUTS
    Number of lease rows updated (0 or 1).
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $PackageId
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($PackageId -notmatch '^[0-9a-fA-F-]{36}$') {
    throw "Expire-PackageLease: PackageId '$PackageId' is not a valid UUID."
}

$runQuery = Join-Path $PSScriptRoot 'Invoke-TestDbQuery.ps1'

# clock_timestamp() is the same wall clock the recovery scheduler's expiry predicate uses.
$sql = @"
UPDATE package_processing_leases
SET lease_expires_at = clock_timestamp() - interval '1 minute',
    heartbeat_at     = clock_timestamp() - interval '1 minute'
WHERE package_id = '$PackageId';
SELECT COUNT(*) FROM package_processing_leases
WHERE package_id = '$PackageId' AND lease_expires_at <= clock_timestamp();
"@

$result = @(& $runQuery -Sql $sql)
# The last emitted tuple is the COUNT.
$count = 0
if ($result.Count -gt 0) {
    $count = [int]("$($result[-1])".Trim())
}

Write-Host ("  [lease] expired lease for package {0} (expired-row count now={1})" -f $PackageId, $count)
return $count
