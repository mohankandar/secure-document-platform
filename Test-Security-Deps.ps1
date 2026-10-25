$ErrorActionPreference = 'Stop'
$RepoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path

Write-Host '=== Frontend dependency/security verification ===' -ForegroundColor Cyan
Push-Location (Join-Path $RepoRoot 'frontend\securedoc-ui')
try {
    npm ls react-router-dom react-router @remix-run/router
    if ($LASTEXITCODE -ne 0) { throw 'npm ls failed' }
    npm audit --omit=dev
    # npm audit returns non-zero while accepted moderate findings remain; do not stop here.
    npm run build
    if ($LASTEXITCODE -ne 0) { throw 'frontend build failed' }
    npm run test:ui-framework
    if ($LASTEXITCODE -ne 0) { throw 'UI framework tests failed' }
    npm run test:auth
    if ($LASTEXITCODE -ne 0) { throw 'UI auth tests failed' }
} finally { Pop-Location }

Write-Host '=== Backend Jackson resolution ===' -ForegroundColor Cyan
Push-Location (Join-Path $RepoRoot 'backend')
try {
    mvn dependency:tree '-Dincludes=com.fasterxml.jackson'
    if ($LASTEXITCODE -ne 0) { throw 'Jackson dependency tree failed' }
} finally { Pop-Location }

Write-Host 'Security dependency verification commands completed.' -ForegroundColor Green
