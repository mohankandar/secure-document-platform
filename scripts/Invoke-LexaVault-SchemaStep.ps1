#requires -Version 5.1
<#
.SYNOPSIS
    LexaVault's schema step (task 0037): the app's tables (Liquibase) and the platform Flyway runs (identity, licensing),
    each with its own history table. The app never changes the database at startup.

.DESCRIPTION
    Run it as the schema owner, before setup and before starting a new release. Running it again is safe.
    The database settings come from the environment, never from options:
        LEXAVAULT_SCHEMA_DB_URL       jdbc:postgresql://dbhost:5432/securedoc
        LEXAVAULT_SCHEMA_DB_USER      the schema owner
        LEXAVAULT_SCHEMA_DB_PASSWORD  its password (never printed)
    See docs/install/SCHEMA_STEP.md.

.PARAMETER Command
    apply (default), status, or customer-installed (exit 0 when setup stored a customer, 4 when not).

.PARAMETER RuntimeUser
    With apply: grant this database user DML on the idn_ and lic_ tables (SELECT on their version tables).

.EXAMPLE
    $env:LEXAVAULT_SCHEMA_DB_URL = 'jdbc:postgresql://localhost:5432/securedoc'; ...; .\scripts\Invoke-LexaVault-SchemaStep.ps1
#>
[CmdletBinding()]
param(
    [ValidateSet('apply', 'status', 'customer-installed')]
    [string] $Command = 'apply',
    [string] $RuntimeUser,
    [string] $JarPath
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\LexaVaultTools.ps1')

$jar = Resolve-LexaVaultJar $JarPath
$arguments = @($Command)
if ($RuntimeUser) { $arguments += @('--runtime-user', $RuntimeUser) }
$code = Invoke-LexaVaultJarMain -Jar $jar -MainClass 'com.securedoc.install.SchemaStep' -Arguments $arguments
exit $code
