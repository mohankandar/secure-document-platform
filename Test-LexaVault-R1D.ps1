$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$uiRoot = Join-Path $repoRoot 'frontend\securedoc-ui'

if (-not (Test-Path (Join-Path $uiRoot 'package.json'))) {
    throw "LexaVault frontend was not found at $uiRoot"
}

Push-Location $uiRoot
try {
    Write-Host "R1D 1/3 - Frontend production build" -ForegroundColor Cyan
    npm run build
    if ($LASTEXITCODE -ne 0) { throw "Frontend build failed with exit code $LASTEXITCODE." }

    Write-Host "R1D 2/3 - SecureDocs UI framework contract" -ForegroundColor Cyan
    npm run test:ui-framework
    if ($LASTEXITCODE -ne 0) { throw "UI framework contract failed with exit code $LASTEXITCODE." }

    Write-Host "R1D 3/3 - UI authorization contract" -ForegroundColor Cyan
    npm run test:auth
    if ($LASTEXITCODE -ne 0) { throw "UI authorization contract failed with exit code $LASTEXITCODE." }

    Write-Host "" 
    Write-Host "Automated R1D frontend checks PASS." -ForegroundColor Green
    Write-Host "Manual finishing gate still required at 1440px, 1024px and representative iPad portrait/landscape." -ForegroundColor Yellow
}
finally {
    Pop-Location
}
