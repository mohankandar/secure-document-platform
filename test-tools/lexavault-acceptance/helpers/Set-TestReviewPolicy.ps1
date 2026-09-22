#requires -Version 5.1
<#
.SYNOPSIS
    Reads the current tenant sensitive-data policy and switches ReviewMode to a target value.

.DESCRIPTION
    Reusable test-tooling helper. Uses the real Business Admin API (no direct DB writes) to:
      1. Read the current sensitive-data policy (so the caller can restore it later).
      2. Update ONLY the reviewMode field to -TargetReviewMode, preserving every other policy field
         (enabledEntityTypes, autoRedactThreshold, maskSsnLastFour).

    This is the supported, auditable configuration path. It changes tenant configuration only, not
    application/backend source. The caller is responsible for restoring the original policy in a
    finally block (see Restore-TestReviewPolicy.ps1).

.PARAMETER TargetReviewMode
    'REVIEW_ALL' or 'UNCERTAIN_ONLY'.

.OUTPUTS
    PSCustomObject snapshot of the ORIGINAL policy (before the change), with fields:
      enabledEntityTypes (string[]), autoRedactThreshold (double), reviewMode (string),
      maskSsnLastFour (bool)
    Pass this object to Restore-TestReviewPolicy.ps1 to restore exactly.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateSet('REVIEW_ALL', 'UNCERTAIN_ONLY')][string] $TargetReviewMode
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$invokeApi = Join-Path $PSScriptRoot 'Invoke-TestApi.ps1'

# StrictMode-safe property reader: returns $null when a property is absent instead of throwing.
function Get-Prop([object] $obj, [string] $name) {
    if ($null -eq $obj) { return $null }
    $p = $obj.PSObject.Properties[$name]
    if ($null -eq $p) { return $null }
    return $p.Value
}

$config = & $invokeApi -Method GET -Path '/api/v1/business-admin/configuration'
$policy = Get-Prop $config 'sensitiveDataPolicy'
if ($null -eq $policy) {
    throw "Set-TestReviewPolicy: unable to read current sensitiveDataPolicy from /business-admin/configuration."
}

$original = [PSCustomObject]@{
    enabledEntityTypes  = @(Get-Prop $policy 'enabledEntityTypes')
    autoRedactThreshold = [double](Get-Prop $policy 'autoRedactThreshold')
    reviewMode          = [string](Get-Prop $policy 'reviewMode')
    maskSsnLastFour     = [bool](Get-Prop $policy 'maskSsnLastFour')
}

Write-Host ("  [policy] current reviewMode={0}; setting reviewMode={1} (preserving other fields)" -f `
    $original.reviewMode, $TargetReviewMode)

if ($original.reviewMode -eq $TargetReviewMode) {
    Write-Host "  [policy] reviewMode already at target; no change needed."
    return $original
}

$body = @{
    enabledEntityTypes  = $original.enabledEntityTypes
    autoRedactThreshold = $original.autoRedactThreshold
    reviewMode          = $TargetReviewMode
    maskSsnLastFour     = $original.maskSsnLastFour
}

$updated = & $invokeApi -Method PUT -Path '/api/v1/business-admin/sensitive-data-policy' -Body $body
$applied = Get-Prop (Get-Prop $updated 'sensitiveDataPolicy') 'reviewMode'
if ($applied -ne $TargetReviewMode) {
    throw "Set-TestReviewPolicy: expected reviewMode=$TargetReviewMode but API reports '$applied'."
}
Write-Host "  [policy] reviewMode is now $applied."

return $original
