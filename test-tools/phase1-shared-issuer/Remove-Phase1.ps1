#Requires -Version 5.1
<#
.SYNOPSIS
  PHASE 1 TEST ENVIRONMENT ONLY. Stops, or removes, everything Phase 1 created, and nothing else.

.PARAMETER KeepContainers
  Stop only the Phase 1 backend (and UI, if one was started). Containers, data and secrets stay,
  so the environment can be started again with Start-Phase1.ps1.

.DESCRIPTION
  Without -KeepContainers:
   - stops the Phase 1 backend/UI by the PIDs in C:\dev\lexavault\phase1-run\run only
     (each is checked to be a java/node process started for Phase 1);
   - docker compose -p lexavault-phase1 down -v --remove-orphans (only that project's containers,
     network and volume);
   - removes any leftover container or volume labelled lexavault.phase=1;
   - deletes C:\dev\lexavault\phase1-run (including the generated secrets).
  It never runs a bare docker compose down and never names the normal containers.
#>
[CmdletBinding()]
param([switch]$KeepContainers)

. (Join-Path $PSScriptRoot 'Phase1-Common.ps1')
function Write-Step([string]$Text) { Write-Host "[phase1] $Text" }

foreach ($name in @('ui', 'backend')) {
    $proc = Get-Phase1OwnedProcess $name
    if ($proc) {
        Write-Step "Stopping Phase 1 $name (PID $($proc.ProcessId))."
        Stop-Process -Id $proc.ProcessId -Force
    }
    $pidFile = Get-Phase1PidFile $name
    if (Test-Path $pidFile) { Remove-Item -LiteralPath $pidFile -Force }
}

if ($KeepContainers) {
    Write-Step 'Backend stopped. Containers, data and secrets kept (run Start-Phase1.ps1 to start again).'
    return
}

& docker info --format '{{.ServerVersion}}' *> $null
if ($LASTEXITCODE -ne 0) { throw 'Docker is not reachable; cannot remove the Phase 1 containers. Start Docker Desktop and run this again.' }

Write-Step 'Normal containers before removal:'
Get-Phase1NormalContainerState | ForEach-Object { Write-Host ("    {0,-20} {1}" -f $_.Name, $_.Status) }

# Compose interpolation needs the variables even for 'down'; use placeholders if the secrets file is gone.
if (-not (Test-Path $Phase1.EnvFile)) {
    $env:PHASE1_DB_PASSWORD = 'unused-for-down'
    $env:PHASE1_KC_ADMIN_PASSWORD = 'unused-for-down'
}
Write-Step 'Removing compose project lexavault-phase1 (containers, network, volume).'
Invoke-Phase1Compose @('down', '-v', '--remove-orphans')

$left = @(& docker ps -a -q --filter "label=$($Phase1.Label)")
if ($left.Count -gt 0) { & docker rm -f $left | Out-Null }
$leftVolumes = @(& docker volume ls -q --filter "label=$($Phase1.Label)")
if ($leftVolumes.Count -gt 0) { & docker volume rm $leftVolumes | Out-Null }

if ($Phase1.RunRoot -ne 'C:\dev\lexavault\phase1-run') { throw 'Refusing to delete an unexpected run folder.' }
if (Test-Path $Phase1.RunRoot) {
    Write-Step "Deleting $($Phase1.RunRoot)."
    Remove-Item -LiteralPath $Phase1.RunRoot -Recurse -Force
}

Write-Step 'Normal containers after removal:'
Get-Phase1NormalContainerState | ForEach-Object { Write-Host ("    {0,-20} {1}" -f $_.Name, $_.Status) }
Write-Step 'Phase 1 environment removed.'
