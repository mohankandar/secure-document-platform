#requires -Version 5.1
<#
.SYNOPSIS
    Task 0035 (test layer L5): the export end to end against the folder destination, on a running LexaVault in
    export job mode, with the proof pictures.

.DESCRIPTION
    1. Starts a THROWAWAY PostgreSQL container (lexavault-0035-pg, 127.0.0.1:55435) with a password generated for
       this run (never printed). It never touches securedoc-postgres or any other container named in CLAUDE.md.
    2. Runs the @e2e-export Playwright suite (frontend/securedoc-ui/playwright.export-e2e.config.ts). The suite starts
       a mock Ollama that finds nothing, a Vite dev server (LOCAL mode, Case File UI) and the backend jar itself,
       with the "export-e2e" test profile from test-tools/export-e2e (shortened timings; the defaults are unchanged).
       Scenario 6 stops and starts the backend in the middle.
    3. The proof pictures go to -ProofDir (default docs/work/0037-proof/; tasks 0035 and 0036 kept theirs in docs/work/0035-proof/ and 0036-proof/).
       Logs, the export folder and the stored copies stay in the run folder (test values only).
    4. Stops the backend if it is still running and removes only the throwaway container.

.PARAMETER JarPath
    The backend jar. Build first: cd backend; mvn clean install -Pdev-build.

.PARAMETER RunRoot
    Where the run folder goes. Default: %TEMP%\lexavault-0035.

.PARAMETER Grep
    Optional: run only the tests whose title matches (for example "scenario 3").

.OUTPUTS
    Exits 0 only when every test passed. Writes results.json (Playwright's own report) in the run folder.
#>
[CmdletBinding()]
param(
    [string] $JarPath,
    [string] $RunRoot = (Join-Path $env:TEMP 'lexavault-0035'),
    [int] $DbPort = 55435,
    [int] $AppPort = 18035,
    [int] $OllamaPort = 11535,
    [string] $Grep,
    # Where the proof pictures go (task 0036: a folder per task, so the earlier pictures stay as they were).
    [string] $ProofDir,
    # Diagnostics: the throwaway database logs every write statement; its log is saved as postgres.log in the run folder.
    [switch] $LogDatabaseWrites
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$ui = Join-Path $repoRoot 'frontend\securedoc-ui'
if (-not $ProofDir) { $ProofDir = Join-Path $repoRoot 'docs\work\0037-proof' }
if (-not $JarPath) { $JarPath = Join-Path $repoRoot 'backend\securedoc-api\target\securedoc-api-0.1.0-SNAPSHOT.jar' }
if (-not (Test-Path -LiteralPath $JarPath)) { throw "Backend jar not found: $JarPath (build it first: cd backend; mvn clean install -Pdev-build)." }
$JarPath = (Resolve-Path -LiteralPath $JarPath).Path

$Container = 'lexavault-0035-pg'
$Protected = @('securedoc-postgres', 'sdv-postgres', 'sdv-vault', 'lexa-keycloak', 'lexa-keycloak-db')
if ($Protected -contains $Container) { throw "Refusing to use a protected container name." }

$runDir = Join-Path $RunRoot (Get-Date -Format 'yyyyMMdd-HHmmss')
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
Write-Host "Run folder: $runDir"

$envNames = @('LEXAVAULT_E2E_RUN_DIR', 'LEXAVAULT_E2E_JAR', 'LEXAVAULT_E2E_PROFILE_DIR', 'LEXAVAULT_E2E_DB_CONTAINER',
    'LEXAVAULT_E2E_DB_PORT', 'LEXAVAULT_E2E_DB_PASSWORD', 'LEXAVAULT_E2E_APP_PORT', 'LEXAVAULT_E2E_OLLAMA_PORT',
    'LEXAVAULT_E2E_PROOF_DIR', 'LEXAVAULT_E2E_RESULTS')
$containerStarted = $false
$exitCode = 1
$started = Get-Date
try {
    # --- 1. throwaway database ---------------------------------------------------------------
    $existing = @(docker ps -a --filter "name=^/$Container$" --format '{{.Names}}')
    if ($existing.Count -gt 0) { throw "Container $Container already exists; remove it first (docker rm -f $Container)." }
    $dbPassword = [guid]::NewGuid().ToString('N')
    $pgArgs = @()
    if ($LogDatabaseWrites) { $pgArgs = @("-c", "log_statement=mod", "-c", "log_line_prefix=%m [%p] ") }
    docker run -d --name $Container -e POSTGRES_DB=securedoc -e POSTGRES_USER=securedoc `
        -e "POSTGRES_PASSWORD=$dbPassword" -p "127.0.0.1:${DbPort}:5432" postgres:16-alpine @pgArgs | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "docker run failed" }
    $containerStarted = $true
    $deadline = (Get-Date).AddSeconds(60)
    do {
        Start-Sleep -Seconds 1
        docker exec $Container pg_isready -U securedoc -d securedoc 2>$null | Out-Null
    } while ($LASTEXITCODE -ne 0 -and (Get-Date) -lt $deadline)
    if ($LASTEXITCODE -ne 0) { throw "throwaway Postgres did not become ready" }
    Start-Sleep -Seconds 2
    Write-Host "Throwaway Postgres ready on 127.0.0.1:$DbPort"

    # --- 1b. the install steps (task 0037): schema step -> setup with the demo files -> demo step -------
    # The same start-up path as a fresh local start; the app inserts nothing at startup any more.
    . (Join-Path $repoRoot 'scripts\lib\LexaVaultTools.ps1')
    Initialize-LexaVaultDatabase -Jar $JarPath -DbUrl "jdbc:postgresql://localhost:$DbPort/securedoc" -DbUser 'securedoc' `
        -DbPassword $dbPassword -StateDir (Join-Path $runDir 'install')

    # --- 2. the suite ------------------------------------------------------------------------
    $env:LEXAVAULT_E2E_RUN_DIR = $runDir
    $env:LEXAVAULT_E2E_JAR = $JarPath
    $env:LEXAVAULT_E2E_PROFILE_DIR = (Join-Path $repoRoot 'test-tools\export-e2e')
    $env:LEXAVAULT_E2E_DB_CONTAINER = $Container
    $env:LEXAVAULT_E2E_DB_PORT = "$DbPort"
    $env:LEXAVAULT_E2E_DB_PASSWORD = $dbPassword
    $env:LEXAVAULT_E2E_APP_PORT = "$AppPort"
    $env:LEXAVAULT_E2E_OLLAMA_PORT = "$OllamaPort"
    $env:LEXAVAULT_E2E_PROOF_DIR = $ProofDir
    $env:LEXAVAULT_E2E_RESULTS = (Join-Path $runDir 'results.json')
    $playwrightArgs = @('node_modules/@playwright/test/cli.js', 'test', '--config', 'playwright.export-e2e.config.ts')
    if ($Grep) { $playwrightArgs += @('--grep', $Grep) }
    Push-Location $ui
    # Windows PowerShell 5.1 turns a native program's stderr lines into errors; the suite's own exit code decides.
    $ErrorActionPreference = 'Continue'
    try {
        & node @playwrightArgs 2>&1 | ForEach-Object { "$_" }
        $testExit = $LASTEXITCODE
    } finally { Pop-Location; $ErrorActionPreference = 'Stop' }

    # --- 3. summary --------------------------------------------------------------------------
    if (Test-Path $env:LEXAVAULT_E2E_RESULTS) {
        $results = Get-Content -Raw $env:LEXAVAULT_E2E_RESULTS | ConvertFrom-Json
        Write-Host ""
        Write-Host "Scenario results:"
        function Show-Suite($suite) {
            foreach ($spec in @($suite.specs)) {
                foreach ($t in @($spec.tests)) {
                    $r = @($t.results)[-1]
                    Write-Host ("  {0,-8} {1,7:N1} s  {2}" -f $r.status, ($r.duration / 1000), $spec.title)
                    $notes = @($t.annotations | Where-Object { $_ -and $_.type -like 'db-clock*' } | ForEach-Object { "$($_.type): $($_.description)" })
                    if ($notes.Count -gt 0) { Write-Host ("                     " + ($notes -join '; ')) }
                }
            }
            if ($suite.PSObject.Properties.Name -contains 'suites') { foreach ($s in @($suite.suites)) { Show-Suite $s } }
        }
        foreach ($s in @($results.suites)) { Show-Suite $s }
    }
    $minutes = [Math]::Round(((Get-Date) - $started).TotalMinutes, 1)
    if ($testExit -eq 0) { Write-Host "PASS ($minutes min)"; $exitCode = 0 } else { Write-Host "FAIL ($minutes min, playwright exit $testExit)" }
}
finally {
    $pidFile = Join-Path $runDir 'backend.pid'
    if (Test-Path $pidFile) {
        $backendPid = [int](Get-Content $pidFile)
        Stop-Process -Id $backendPid -Force -ErrorAction SilentlyContinue
        Write-Host "Stopped the backend left running (pid $backendPid)"
    }
    if ($containerStarted -and $LogDatabaseWrites) {
        $pgLog = Join-Path $runDir "postgres.log"
        cmd /c "docker logs $Container > `"$pgLog`" 2>&1"
    }
    if ($containerStarted) {
        docker rm -f $Container | Out-Null
        Write-Host "Removed throwaway container $Container"
    }
    foreach ($name in $envNames) { Remove-Item "Env:$name" -ErrorAction SilentlyContinue }
}
exit $exitCode
