#requires -Version 5.1
<#
.SYNOPSIS
    One-off copy of an existing install's login connection and group grants from LexaVault's old tables into the
    platform idn_ tables (task 0037). The old tables are not changed.

.DESCRIPTION
    Run it after the schema step and after setup (setup stores the customer and its entity slots). Only entities whose
    id is the entity UUID of one of the customer's slots are copied; others are listed as skipped. Safe to run again.
    Database settings in the environment only (never printed):
        LEXAVAULT_MIGRATE_DB_URL, LEXAVAULT_MIGRATE_DB_USER, LEXAVAULT_MIGRATE_DB_PASSWORD
    Exit codes: 0 done, 1 failed, 2 refused, 3 done except conflicts. See docs/install/IDENTITY_MIGRATION.md.

.PARAMETER Command
    plan (default: show, change nothing) or apply.
#>
[CmdletBinding()]
param(
    [ValidateSet('plan', 'apply')]
    [string] $Command = 'plan',
    [string] $JarPath
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\LexaVaultTools.ps1')

$jar = Resolve-LexaVaultJar $JarPath
exit (Invoke-LexaVaultJarMain -Jar $jar -MainClass 'com.securedoc.install.IdentityMigration' -Arguments @($Command))
