param()

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$frontend = Join-Path $root "frontend\securedoc-ui"

if (-not (Test-Path $frontend)) {
    throw "LexaVault frontend not found at $frontend"
}

Push-Location $frontend
try {
    Write-Host "Running SecureDocs UI Framework contract..." -ForegroundColor Cyan
    & npm run test:ui-framework
    if ($LASTEXITCODE -ne 0) {
        throw "SecureDocs UI Framework contract failed with exit code $LASTEXITCODE"
    }
    Write-Host "PASS - SecureDocs UI Framework contract" -ForegroundColor Green
}
finally {
    Pop-Location
}
