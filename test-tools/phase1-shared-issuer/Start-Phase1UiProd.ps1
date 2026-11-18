#Requires -Version 5.1
<#
.SYNOPSIS
  PHASE 1 TEST ENVIRONMENT ONLY. Builds the LexaVault UI for production (vite build, into
  C:\dev\lexavault\phase1-run\ui-dist, outside the repository) and serves it with vite preview on
  http://localhost:15174/, with the same Phase 1 settings as the dev server (OIDC against the throwaway Keycloak,
  newest screen design on, /api proxied to the Phase 1 backend on 18080).
  Runs beside the dev server on 15173. Stop it with Remove-Phase1.ps1 -KeepContainers.
#>
[CmdletBinding()]
param([int]$StartupTimeoutSeconds = 90)

. (Join-Path $PSScriptRoot 'Phase1-Common.ps1')
function Write-Step([string]$Text) { Write-Host "[phase1] $Text" }

$prodPort = 15174
if (Get-Phase1OwnedProcess 'ui-prod') { throw 'The Phase 1 production-build UI is already running. Run Remove-Phase1.ps1 -KeepContainers first.' }
if (Test-Phase1Port $prodPort) { throw "Port $prodPort is already in use." }
if (-not (Test-Phase1Port $Phase1.BackendPort)) { throw 'The Phase 1 backend is not running on 18080. Run Start-Phase1.ps1 first.' }

$frontend = Join-Path $Phase1.RepoRoot 'frontend\securedoc-ui'
$viteJs = Join-Path $frontend 'node_modules\vite\bin\vite.js'
if (-not (Test-Path $viteJs)) { throw "Vite not found at $viteJs. Run npm ci in frontend\securedoc-ui first." }
$config = Join-Path $Phase1.ToolDir 'vite.phase1.config.mjs'

# The browser client must accept http://localhost:15174/ as a redirect address (from the realm file).
Sync-Phase1KeycloakClients

# Build-time settings (baked into the build). Process environment wins over frontend\securedoc-ui\.env.local.
$childEnv = @{
    VITE_SECURITY_MODE        = 'OIDC'
    VITE_OIDC_ISSUER          = $Phase1.Issuer
    VITE_OIDC_CLIENT_ID       = $Phase1.WebClientId
    VITE_OIDC_REDIRECT_URI    = "http://localhost:$prodPort/"
    VITE_OIDC_SCOPE           = 'openid profile email'
    VITE_CASE_FILE_UI_ENABLED = 'true'
}
$saved = @{}
foreach ($k in $childEnv.Keys) { $saved[$k] = [Environment]::GetEnvironmentVariable($k, 'Process'); [Environment]::SetEnvironmentVariable($k, $childEnv[$k], 'Process') }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$buildLog = Join-Path $Phase1.LogDir "ui-prod-build-$stamp.log"
$outLog = Join-Path $Phase1.LogDir "ui-prod-$stamp.out.log"
$errLog = Join-Path $Phase1.LogDir "ui-prod-$stamp.err.log"
try {
    Write-Step 'Building the UI for production (vite build) into C:\dev\lexavault\phase1-run\ui-dist.'
    Push-Location $frontend
    try {
        & node $viteJs build --config $config *> $buildLog
        if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath $buildLog -Tail 30; throw 'vite build failed.' }
    } finally { Pop-Location }
    $proc = Start-Process -FilePath 'node' -ArgumentList @($viteJs, 'preview', '--config', $config) -WorkingDirectory $frontend `
        -RedirectStandardOutput $outLog -RedirectStandardError $errLog -WindowStyle Hidden -PassThru
} finally {
    foreach ($k in $saved.Keys) { [Environment]::SetEnvironmentVariable($k, $saved[$k], 'Process') }
}
Set-Content -LiteralPath (Get-Phase1PidFile 'ui-prod') -Value $proc.Id -Encoding ASCII

$deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
$up = $false
while ((Get-Date) -lt $deadline -and -not $proc.HasExited) {
    try { if ((Invoke-WebRequest -Uri "http://localhost:$prodPort/" -UseBasicParsing -TimeoutSec 5).StatusCode -eq 200) { $up = $true; break } } catch { }
    Start-Sleep -Seconds 2
}
if ($proc.HasExited -or -not $up) {
    Get-Content -LiteralPath $outLog -Tail 40 -ErrorAction SilentlyContinue
    Get-Content -LiteralPath $errLog -Tail 40 -ErrorAction SilentlyContinue
    throw 'The Phase 1 production-build UI did not start.'
}
Write-Step "Phase 1 production-build UI is up on http://localhost:$prodPort/ (PID $($proc.Id), newest design ON). Logs: $outLog"
