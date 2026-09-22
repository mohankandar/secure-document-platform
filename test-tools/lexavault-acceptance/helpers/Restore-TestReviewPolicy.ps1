#requires -Version 5.1
<#
.SYNOPSIS
    Restores a previously-captured tenant sensitive-data policy snapshot.

.DESCRIPTION
    Reusable test-tooling helper. Writes back the exact policy snapshot captured by
    Set-TestReviewPolicy.ps1 using the real Business Admin API. Intended to be called from a
    finally block so the tenant policy is restored regardless of PASS/FAIL.

    Safe to call even if the backend was restarted between capture and restore, as long as the
    backend is reachable. If -OriginalPolicy is $null, this is a no-op (nothing to restore).

.PARAMETER OriginalPolicy
    The snapshot object returned by Set-TestReviewPolicy.ps1.

.OUTPUTS
    $true if the policy was confirmed restored (or no-op), otherwise throws.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $false)][object] $OriginalPolicy
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Get-Prop([object] $obj, [string] $name) {
    if ($null -eq $obj) { return $null }
    $p = $obj.PSObject.Properties[$name]
    if ($null -eq $p) { return $null }
    return $p.Value
}

if ($null -eq $OriginalPolicy) {
    Write-Host "  [policy] no original policy snapshot to restore (no-op)."
    return $true
}

$invokeApi = Join-Path $PSScriptRoot 'Invoke-TestApi.ps1'

$body = @{
    enabledEntityTypes  = @(Get-Prop $OriginalPolicy 'enabledEntityTypes')
    autoRedactThreshold = [double](Get-Prop $OriginalPolicy 'autoRedactThreshold')
    reviewMode          = [string](Get-Prop $OriginalPolicy 'reviewMode')
    maskSsnLastFour     = [bool](Get-Prop $OriginalPolicy 'maskSsnLastFour')
}

$updated = & $invokeApi -Method PUT -Path '/api/v1/business-admin/sensitive-data-policy' -Body $body
$applied = Get-Prop (Get-Prop $updated 'sensitiveDataPolicy') 'reviewMode'
if ($applied -ne $OriginalPolicy.reviewMode) {
    throw "Restore-TestReviewPolicy: expected reviewMode=$($OriginalPolicy.reviewMode) but API reports '$applied'."
}
Write-Host "  [policy] restored reviewMode=$applied."
return $true
