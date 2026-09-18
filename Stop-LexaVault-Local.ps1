param([switch]$IncludeInfrastructure)

$ErrorActionPreference = "Continue"

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$ComposeFile = Join-Path $ProjectRoot "docker\compose\postgres.yml"
$RunDir = Join-Path $ProjectRoot ".run"

function Stop-Tree {
    param([string]$Name, [string]$PidFile)

    if (-not (Test-Path $PidFile)) {
        Write-Host "$Name: no PID file." -ForegroundColor DarkGray
        return
    }

    $pidValue = 0
    $text = Get-Content $PidFile -ErrorAction SilentlyContinue | Select-Object -First 1

    if ([int]::TryParse($text, [ref]$pidValue)) {
        if (Get-Process -Id $pidValue -ErrorAction SilentlyContinue) {
            Write-Host "Stopping $Name (PID $pidValue)..."
            & taskkill.exe /PID $pidValue /T /F 2>$null | Out-Null
        }
    }

    Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
}

Write-Host ""
Write-Host "=== LexaVault LOCAL shutdown ===" -ForegroundColor Cyan

# Stop local scanner acquisition first so it cannot enqueue/deliver while the backend is shutting down.
Stop-Tree "Scanner Bridge" (Join-Path $RunDir "scanner-bridge-shell.pid")
Stop-Tree "Frontend" (Join-Path $RunDir "frontend-shell.pid")
Stop-Tree "Backend" (Join-Path $RunDir "backend-shell.pid")

if ($IncludeInfrastructure) {
    $postgresMarker = Join-Path $RunDir "postgres-started-by-script"
    if (Test-Path $postgresMarker) {
        if (Test-Path $ComposeFile) {
            & docker compose -f $ComposeFile stop
        }
        Remove-Item $postgresMarker -Force -ErrorAction SilentlyContinue
    }

    $ollamaMarker = Join-Path $RunDir "ollama-started-by-script"
    if (Test-Path $ollamaMarker) {
        Stop-Tree "Ollama" (Join-Path $RunDir "ollama.pid")
        Remove-Item $ollamaMarker -Force -ErrorAction SilentlyContinue
    }
}

Write-Host "LexaVault application processes stopped." -ForegroundColor Green
