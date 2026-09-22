#requires -Version 5.1
<#
.SYNOPSIS
    Returns the current package_processing_lease + package status for one disposable package.

.DESCRIPTION
    Reusable test-tooling helper. Reads a single package's processing lease and package status
    directly from the local PostgreSQL database. Read-only: performs no writes.

.PARAMETER PackageId
    The disposable package id to inspect.

.OUTPUTS
    PSCustomObject with fields:
      packageId, status, ownerId, fencingToken (int|null), attemptCount (int|null),
      leaseExpiresAt, heartbeatAt, hasLease (bool)
    Returns $null if the package row does not exist.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $PackageId
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# Guard against SQL injection / malformed ids: enforce UUID shape.
if ($PackageId -notmatch '^[0-9a-fA-F-]{36}$') {
    throw "Get-PackageProcessingLease: PackageId '$PackageId' is not a valid UUID."
}

$sql = @"
SELECT p.id, p.status, l.owner_id, l.fencing_token, l.attempt_count, l.lease_expires_at, l.heartbeat_at
FROM document_packages p
LEFT JOIN package_processing_leases l ON l.package_id = p.id
WHERE p.id = '$PackageId';
"@

$columns = @('packageId', 'status', 'ownerId', 'fencingToken', 'attemptCount', 'leaseExpiresAt', 'heartbeatAt')
$rows = @(& (Join-Path $PSScriptRoot 'Invoke-TestDbQuery.ps1') -Sql $sql -AsObjects -Columns $columns)

if ($rows.Count -eq 0) {
    return $null
}

$row = $rows[0]

function ConvertTo-NullableInt([object] $v) {
    if ($null -eq $v -or "$v".Trim() -eq '') { return $null }
    return [int]"$v".Trim()
}

[PSCustomObject]@{
    packageId      = $row.packageId
    status         = $row.status
    ownerId        = if ("$($row.ownerId)".Trim() -eq '') { $null } else { $row.ownerId }
    fencingToken   = ConvertTo-NullableInt $row.fencingToken
    attemptCount   = ConvertTo-NullableInt $row.attemptCount
    leaseExpiresAt = if ("$($row.leaseExpiresAt)".Trim() -eq '') { $null } else { $row.leaseExpiresAt }
    heartbeatAt    = if ("$($row.heartbeatAt)".Trim() -eq '') { $null } else { $row.heartbeatAt }
    hasLease       = ("$($row.ownerId)".Trim() -ne '')
}
