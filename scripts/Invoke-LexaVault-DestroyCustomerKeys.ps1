#requires -Version 5.1
<#
.SYNOPSIS
    Customer exit: destroys the customer's keys (task 0060, docs/install/OFFBOARDING.md). It CAN NOT be undone.

.DESCRIPTION
    Platform-core's "crypto destroy-customer-keys", from the setup jar. Every stored document of the customer, backups
    included, becomes unreadable. The key service login (policy platform-crypto-offboard only) is in the environment only:
    PLATFORM_SETUP_VAULT_*. Without -Confirm the tenant ID is asked for on the console.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $TenantId,
    [Parameter(Mandatory)] [string] $Operator,
    [int] $BackupRetentionDays = 35,
    [string] $Confirm,
    [string] $ResultOut,
    [string] $JarPath
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\LexaVaultTools.ps1')
$jar = Resolve-LexaVaultSetupJar $JarPath
$arguments = @('destroy-customer-keys', '--tenant-id', $TenantId, '--operator', $Operator,
    '--backup-retention-days', "$BackupRetentionDays")
if ($Confirm) { $arguments += @('--confirm', $Confirm) }
if ($ResultOut) { $arguments += @('--result-out', $ResultOut) }
exit (Invoke-LexaVaultJarMain -Jar $jar -MainClass 'com.documentplatform.crypto.command.CryptoCommand' -Arguments $arguments)
