<#
.SYNOPSIS
  Phase 1 only: runs the backend's load-role-mappings command against the throwaway Phase 1 database.
.DESCRIPTION
  Uses the jar copied by Start-Phase1.ps1 (or -Jar), the Phase 1 JDBC URL (port 15432 only) and the
  generated database password from the Phase 1 secrets folder, passed to the child process through the
  environment, never on the command line. Prints the command's output and exits with its exit code.
#>
param(
    [Parameter(Mandatory = $true)][string]$File,
    [switch]$DryRun,
    [string]$Jar
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Phase1-Common.ps1')

Assert-Phase1JdbcUrl $Phase1.JdbcUrl
if (-not $Jar) { $Jar = Join-Path $Phase1.RunRoot 'securedoc-api-phase1.jar' }
if (-not (Test-Path -LiteralPath $Jar)) { throw "Jar not found: $Jar. Run Start-Phase1.ps1 first." }
$secrets = Read-Phase1EnvFile

$javaArgs = @(
    '-jar', $Jar,
    '--lexavault.command=load-role-mappings',
    "--file=$File",
    '--spring.profiles.active=local,oidc-local',
    "--spring.datasource.url=$($Phase1.JdbcUrl)",
    "--securedoc.security.oidc.issuer-uri=$($Phase1.Issuer)",
    "--securedoc.security.oidc.audience=$($Phase1.Audience)",
    "--securedoc.security.oidc.provider-id=$($Phase1.ProviderId)",
    '--securedoc.security.oidc.tenant-id=',
    "--securedoc.storage.local.root-path=$(Join-Path $Phase1.RunRoot 'storage')",
    "--securedoc.delivery.local.output-path=$(Join-Path $Phase1.RunRoot 'output')"
)
if ($DryRun) { $javaArgs += '--dry-run' }

$childEnv = @{
    SPRING_DATASOURCE_USERNAME          = $Phase1.DbUser
    SPRING_DATASOURCE_PASSWORD          = $secrets['PHASE1_DB_PASSWORD']
    SECUREDOC_AI_PROVIDER               = 'heuristic'
    SECUREDOC_WATCHED_FOLDER_ENABLED    = 'false'
    SECUREDOC_WATCHED_FOLDER_ROOT_PATH  = (Join-Path $Phase1.RunRoot 'watched-intake')
    SECUREDOC_NODE_ID                   = 'PHASE1-COMMAND'
    SECUREDOC_LOG_DIR                   = $Phase1.LogDir
    SECUREDOC_SECURITY_OIDC_TENANT_ID   = ''
}
$saved = @{}
foreach ($k in $childEnv.Keys) { $saved[$k] = [Environment]::GetEnvironmentVariable($k, 'Process'); [Environment]::SetEnvironmentVariable($k, $childEnv[$k], 'Process') }
try {
    # The JVM writes log lines to stderr; they are output here, not errors of this script.
    $ErrorActionPreference = 'Continue'
    $output = & java @javaArgs 2>&1 | ForEach-Object { "$_" }
    $code = $LASTEXITCODE
} finally {
    $ErrorActionPreference = 'Stop'
    foreach ($k in $saved.Keys) { [Environment]::SetEnvironmentVariable($k, $saved[$k], 'Process') }
}
$output | ForEach-Object { Write-Output $_ }
exit $code
