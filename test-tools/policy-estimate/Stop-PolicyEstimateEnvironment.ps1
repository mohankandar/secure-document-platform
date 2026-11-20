<#
.SYNOPSIS
    Task 0020 A4: stops what Invoke-PolicyEstimateCheck.ps1 -KeepRunning left running.

.DESCRIPTION
    Reads keep-running.json from -RunRoot, stops the backend and mock Ollama processes it names, and removes the
    throwaway container lexavault-0019-pg. Refuses any other container name, and never touches the containers named
    in CLAUDE.md.
#>
[CmdletBinding()]
param([string] $RunRoot = (Join-Path $env:TEMP 'lexavault-0019'))

$ErrorActionPreference = 'Stop'
$stateFile = Join-Path $RunRoot 'keep-running.json'
if (-not (Test-Path -LiteralPath $stateFile)) { Write-Host "Nothing to stop ($stateFile not found)."; exit 0 }
$state = Get-Content -Raw -LiteralPath $stateFile | ConvertFrom-Json
if ($state.container -ne 'lexavault-0019-pg') { throw "Refusing to remove container '$($state.container)'." }
foreach ($id in @($state.appPid, $state.ollamaPid)) {
    if ($id) { Stop-Process -Id $id -Force -ErrorAction SilentlyContinue }
}
docker rm -f $state.container | Out-Null
Remove-Item -LiteralPath $stateFile
Write-Host "Stopped the backend and mock Ollama; removed $($state.container)."
