$ErrorActionPreference = "Continue"

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$RunDir = Join-Path $ProjectRoot ".run"
$PidFile = Join-Path $RunDir "scanner-bridge-shell.pid"

Write-Host ""
Write-Host "=== LexaVault Scanner Bridge LOCAL shutdown ===" -ForegroundColor Cyan

if (-not (Test-Path $PidFile)) {
    Write-Host "Scanner Bridge: no PID file. It may already be stopped or may have been started manually." -ForegroundColor Yellow
    exit 0
}

$pidValue = 0
$text = Get-Content $PidFile -ErrorAction SilentlyContinue | Select-Object -First 1

if ([int]::TryParse($text, [ref]$pidValue)) {
    if (Get-Process -Id $pidValue -ErrorAction SilentlyContinue) {
        Write-Host "Stopping Scanner Bridge (PID $pidValue)..."
        & taskkill.exe /PID $pidValue /T /F 2>$null | Out-Null
    }
    else {
        Write-Host "Scanner Bridge process is no longer running." -ForegroundColor DarkGray
    }
}

Remove-Item $PidFile -Force -ErrorAction SilentlyContinue

Write-Host "Scanner Bridge stopped." -ForegroundColor Green
Write-Host "Scanner inbox/work/problem folders were preserved." -ForegroundColor DarkGray
