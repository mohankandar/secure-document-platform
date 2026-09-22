#requires -Version 5.1
<#
.SYNOPSIS
    Runs a single SQL query against the LexaVault local PostgreSQL database and returns rows.

.DESCRIPTION
    Reusable test-tooling helper. Executes SQL via the running PostgreSQL container using
    `docker exec ... psql`, so no local psql client install is required.

    All connection details are configurable via environment variables so the harness can be
    re-run in other local/demo environments without editing scripts. Defaults match the
    committed local/demo profile (application-local.yml).

    This helper is READ/EXECUTE-only from the caller's perspective: it does not modify any
    application/backend source. It only manipulates disposable test data that the caller passes in.

.PARAMETER Sql
    The SQL statement to execute. Callers are responsible for scoping statements to disposable
    test data (for example, filtering by a specific package id).

.PARAMETER AsObjects
    When set, parses the tuples-only psql output into PSCustomObjects using the provided -Columns.

.PARAMETER Columns
    Ordered column names used to name fields when -AsObjects is set.

.OUTPUTS
    When -AsObjects: array of PSCustomObject. Otherwise: raw trimmed text lines (tuples only).

.NOTES
    Environment variables (with defaults):
      SECUREDOC_DB_CONTAINER  = securedoc-postgres
      SECUREDOC_DB_NAME       = securedoc
      SECUREDOC_DB_USER       = securedoc
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $Sql,
    [switch] $AsObjects,
    [string[]] $Columns = @()
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$container = if ($env:SECUREDOC_DB_CONTAINER) { $env:SECUREDOC_DB_CONTAINER } else { 'securedoc-postgres' }
$dbName    = if ($env:SECUREDOC_DB_NAME)      { $env:SECUREDOC_DB_NAME }      else { 'securedoc' }
$dbUser    = if ($env:SECUREDOC_DB_USER)      { $env:SECUREDOC_DB_USER }      else { 'securedoc' }

# -A unaligned, -t tuples-only, -F '|' field separator, -q quiet. -v ON_ERROR_STOP=1 fails fast.
$psqlArgs = @(
    'exec', '-i', $container,
    'psql', '-U', $dbUser, '-d', $dbName,
    '-v', 'ON_ERROR_STOP=1',
    '-A', '-t', '-F', '|', '-q',
    '-c', $Sql
)

$output = & docker @psqlArgs 2>&1
$exit = $LASTEXITCODE
if ($exit -ne 0) {
    throw "DB query failed (docker exec psql exit=$exit). Container='$container' Db='$dbName' User='$dbUser'.`nSQL: $Sql`nOutput: $($output -join [Environment]::NewLine)"
}

# Always work with an array (single-line output must not unwrap to a scalar under StrictMode).
$lines = @($output | ForEach-Object { "$_".TrimEnd() } | Where-Object { $_ -ne '' })

if (-not $AsObjects) {
    # Return the bare array. A single element unwraps to a scalar, which callers re-wrap with @();
    # an empty result yields nothing. This avoids the empty-array double-wrap that ,$lines causes.
    return $lines
}

if ($Columns.Count -eq 0) {
    throw "Invoke-TestDbQuery: -AsObjects requires -Columns."
}

$rows = foreach ($line in $lines) {
    $parts = @($line -split '\|')
    $obj = [ordered]@{}
    for ($i = 0; $i -lt $Columns.Count; $i++) {
        $value = if ($i -lt $parts.Count) { $parts[$i] } else { $null }
        $obj[$Columns[$i]] = $value
    }
    [PSCustomObject]$obj
}
# Return the bare row set. Callers wrap with @() so single-row output does not scalar-unwrap and
# an empty result stays empty (no phantom element from double array-wrapping).
return $rows
