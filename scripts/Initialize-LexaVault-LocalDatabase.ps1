#requires -Version 5.1
<#
.SYNOPSIS
    Brings a local development database up to date with the real install steps (task 0037), in order:
    the schema step, then (on a fresh database only) setup with the demo input files and the LexaVault demo step.

.DESCRIPTION
    The same steps an installation runs, not different ones (docs/design/LOGIN_CUSTOMER_ENTITY_DESIGN.md, "the same
    steps everywhere"). The start scripts call this before starting the backend. Running it again is safe.

    Database: -DbUrl and -DbUser; the password comes from $env:LEXAVAULT_LOCAL_DB_PASSWORD, or, for the default local
    database only, from the development compose file's value. It is passed to each step in its environment only and is
    never printed.

.PARAMETER JarPath
    The backend jar (default: backend\securedoc-api\target\securedoc-api-0.1.0-SNAPSHOT.jar).
#>
[CmdletBinding()]
param(
    [string] $DbUrl = $(if ($env:LEXAVAULT_LOCAL_DB_URL) { $env:LEXAVAULT_LOCAL_DB_URL } else { 'jdbc:postgresql://localhost:5432/securedoc' }),
    [string] $DbUser = $(if ($env:LEXAVAULT_LOCAL_DB_USER) { $env:LEXAVAULT_LOCAL_DB_USER } else { 'securedoc' }),
    [string] $JarPath
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\LexaVaultTools.ps1')

$jar = Resolve-LexaVaultJar $JarPath
$password = $env:LEXAVAULT_LOCAL_DB_PASSWORD
if (-not $password) {
    if ($DbUrl -ne 'jdbc:postgresql://localhost:5432/securedoc' -or $DbUser -ne 'securedoc') {
        throw "Set LEXAVAULT_LOCAL_DB_PASSWORD for $DbUrl (only the default local database has a known development password)."
    }
    # The development compose file's value (docker/compose/postgres.yml), the same one application-local.yml uses.
    $password = 'securedoc_dev_password'
}

Write-Host "Local database: schema step..." -ForegroundColor Cyan
$schemaEnv = New-LexaVaultDbEnvironment 'LEXAVAULT_SCHEMA' $DbUrl $DbUser $password
$code = Invoke-LexaVaultJarMain -Jar $jar -MainClass 'com.securedoc.install.SchemaStep' -Arguments @('apply') -Environment $schemaEnv
if ($code -ne 0) { throw "The schema step failed (exit $code)." }
