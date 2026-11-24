#requires -Version 5.1
<#
.SYNOPSIS
    Brings a local development database up to date with the real install steps (task 0037), in order: the schema step,
    then, while the demo step has not run, setup with the demo input files (install\demo), then the LexaVault demo step.

.DESCRIPTION
    The same steps an installation runs, not different ones (docs/design/LOGIN_CUSTOMER_ENTITY_DESIGN.md, "the same
    steps everywhere"). The start scripts call this before starting the backend. Running it again is safe: setup runs
    only while the demo step has not made the demo entity (a fresh database, a database from before task 0037, or one
    whose demo step stopped half-way; setup then changes nothing if it ran before), and the schema and demo steps
    change nothing the second time. Existing data, such as the old fixed demo tenant 0000...0001, is never changed.

    Database: -DbUrl and -DbUser; the password comes from $env:LEXAVAULT_LOCAL_DB_PASSWORD, or, for the default local
    database only, from the development compose file's value. It is passed to each step in its environment only and is
    never printed. Setup's config fragment and run log go to .run\ (a local runtime folder).

    With -OidcIssuer (Start-LexaVault-OIDC.ps1; task 0041), setup runs on every start with a sign-in file that names the
    local development login system (http on localhost is taken because the demo licence is signed by a development
    key), so the customer's LexaVault login connection is that issuer. Setup changes nothing when it already is.
    The jars must be a development build (Build-LexaVault-All.ps1 without -Production, or mvn -Pdev-build): only that one
    trusts the demo licence's key (task 0054).
    Setup runs from its own jar, backend\securedoc-setup\target (found next to -JarPath).
#>
[CmdletBinding()]
param(
    [string] $DbUrl = $(if ($env:LEXAVAULT_LOCAL_DB_URL) { $env:LEXAVAULT_LOCAL_DB_URL } else { 'jdbc:postgresql://localhost:5432/securedoc' }),
    [string] $DbUser = $(if ($env:LEXAVAULT_LOCAL_DB_USER) { $env:LEXAVAULT_LOCAL_DB_USER } else { 'securedoc' }),
    [string] $JarPath,
    [string] $StateDir,
    [string] $OidcIssuer,
    [string] $OidcProviderId,
    [string] $OidcAudience,
    [string] $OidcBrowserClientId
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\LexaVaultTools.ps1')

$jar = Resolve-LexaVaultJar $JarPath
if (-not $StateDir) { $StateDir = Join-Path $script:LexaVaultRepoRoot '.run' }
$password = $env:LEXAVAULT_LOCAL_DB_PASSWORD
if (-not $password) {
    if ($DbUrl -ne 'jdbc:postgresql://localhost:5432/securedoc' -or $DbUser -ne 'securedoc') {
        throw "Set LEXAVAULT_LOCAL_DB_PASSWORD for $DbUrl (only the default local database has a known development password)."
    }
    # The development compose file's value (docker/compose/postgres.yml), the same one application-local.yml uses.
    $password = 'securedoc_dev_password'
}

Initialize-LexaVaultDatabase -Jar $jar -DbUrl $DbUrl -DbUser $DbUser -DbPassword $password -StateDir $StateDir `
    -OidcIssuer $OidcIssuer -OidcProviderId $OidcProviderId -OidcAudience $OidcAudience -OidcBrowserClientId $OidcBrowserClientId
