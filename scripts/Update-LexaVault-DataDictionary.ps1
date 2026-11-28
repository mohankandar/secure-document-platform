#requires -Version 5.1
<#
.SYNOPSIS
    Task 0118: regenerate docs/data/DATA-DICTIONARY.md and docs/data/RELATIONSHIPS.md from the live schema.

.DESCRIPTION
    Starts a throwaway PostgreSQL 16 container (its own name, a free local port, a random password that is never
    printed), runs the schema step (every migration and the platform comments), reads the schema back, writes the two
    files and removes the container. It never touches any other database or container.
    Needs Docker and the backend jar (cd backend; mvn -Pdev-build clean install), or -JarPath.

.PARAMETER Check
    Write nothing; exit 1 when a table or column has no comment or the committed files are out of date.

.EXAMPLE
    .\scripts\Update-LexaVault-DataDictionary.ps1
#>
[CmdletBinding()]
param(
    [switch] $Check,
    [string] $JarPath
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\LexaVaultTools.ps1')

$jar = Resolve-LexaVaultJar $JarPath
$repoRoot = Split-Path $PSScriptRoot -Parent
$name = "lv-data-dictionary-$PID-$(Get-Random)"
$bytes = New-Object byte[] 24
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$password = -join ($bytes | ForEach-Object { $_.ToString('x2') })

try {
    $null = & docker run -d --name $name -e POSTGRES_USER=lvdict -e "POSTGRES_PASSWORD=$password" -e POSTGRES_DB=lvdict `
        -p 127.0.0.1::5432 postgres:16-alpine
    if ($LASTEXITCODE -ne 0) { throw "docker run failed for the throwaway database $name" }
    $ready = $false
    # Windows PowerShell stops on a native command's stderr under 'Stop': the probe's "connection refused" is expected.
    $ErrorActionPreference = 'Continue'
    for ($i = 0; $i -lt 60 -and -not $ready; $i++) {
        # pg_isready answers during the image's first, temporary start too: wait for a real query over TCP.
        $null = & docker exec $name psql -h 127.0.0.1 -U lvdict -d lvdict -Atc 'select 1' 2>&1
        if ($LASTEXITCODE -eq 0) { $ready = $true } else { Start-Sleep -Seconds 1 }
    }
    $ErrorActionPreference = 'Stop'
    if (-not $ready) { throw "the throwaway database $name did not start" }
    $port = ((& docker port $name 5432/tcp) | Select-Object -First 1) -replace '.*:', ''

    $environment = New-LexaVaultDbEnvironment -Prefix 'LEXAVAULT_SCHEMA' -Url "jdbc:postgresql://127.0.0.1:$port/lvdict" `
        -User 'lvdict' -Password $password
    $code = Invoke-LexaVaultJarMain -Jar $jar -MainClass 'com.securedoc.install.SchemaStep' -Arguments @('apply') `
        -Environment $environment
    if ($code -ne 0) { throw "the schema step failed (exit $code)" }
    $mode = if ($Check) { 'check' } else { 'write' }
    $code = Invoke-LexaVaultJarMain -Jar $jar -MainClass 'com.securedoc.install.DataDictionary' `
        -Arguments @($mode, (Join-Path $repoRoot 'docs\data')) -Environment $environment
    exit $code
}
finally {
    $ErrorActionPreference = 'Continue'
    $null = & docker rm -f $name 2>$null
}
