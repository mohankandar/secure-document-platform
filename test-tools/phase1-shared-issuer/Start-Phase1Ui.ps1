#Requires -Version 5.1
<#
.SYNOPSIS
  PHASE 1 TEST ENVIRONMENT ONLY. Starts the LexaVault UI on http://localhost:15173 against the Phase 1
  backend (18080) and the throwaway Keycloak (18181), with the newest screen design switched on.
  Stop it with Remove-Phase1.ps1 -KeepContainers (stops the UI and the backend).
#>
[CmdletBinding()]
param([int]$StartupTimeoutSeconds = 90)

. (Join-Path $PSScriptRoot 'Phase1-Common.ps1')
function Write-Step([string]$Text) { Write-Host "[phase1] $Text" }

if (Get-Phase1OwnedProcess 'ui') { throw 'The Phase 1 UI is already running. Run Remove-Phase1.ps1 -KeepContainers first.' }
if (Test-Phase1Port $Phase1.UiPort) { throw "Port $($Phase1.UiPort) is already in use." }
if (-not (Test-Phase1Port $Phase1.BackendPort)) { throw 'The Phase 1 backend is not running on 18080. Run Start-Phase1.ps1 first.' }

$frontend = Join-Path $Phase1.RepoRoot 'frontend\securedoc-ui'
$viteJs = Join-Path $frontend 'node_modules\vite\bin\vite.js'
if (-not (Test-Path $viteJs)) { throw "Vite not found at $viteJs. Run npm ci in frontend\securedoc-ui first." }
$config = Join-Path $Phase1.ToolDir 'vite.phase1.config.mjs'

# Process environment wins over frontend\securedoc-ui\.env.local, which is left untouched.
$childEnv = @{
    VITE_SECURITY_MODE        = 'OIDC'
    VITE_OIDC_ISSUER          = $Phase1.Issuer
    VITE_OIDC_CLIENT_ID       = $Phase1.WebClientId
    VITE_OIDC_REDIRECT_URI    = "http://localhost:$($Phase1.UiPort)/"
    VITE_OIDC_SCOPE           = 'openid profile email'
    VITE_CASE_FILE_UI_ENABLED = 'true'
}
$saved = @{}
foreach ($k in $childEnv.Keys) { $saved[$k] = [Environment]::GetEnvironmentVariable($k, 'Process'); [Environment]::SetEnvironmentVariable($k, $childEnv[$k], 'Process') }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$outLog = Join-Path $Phase1.LogDir "ui-$stamp.out.log"
$errLog = Join-Path $Phase1.LogDir "ui-$stamp.err.log"
try {
    $proc = Start-Process -FilePath 'node' -ArgumentList @($viteJs, '--config', $config) -WorkingDirectory $frontend `
        -RedirectStandardOutput $outLog -RedirectStandardError $errLog -WindowStyle Hidden -PassThru
} finally {
    foreach ($k in $saved.Keys) { [Environment]::SetEnvironmentVariable($k, $saved[$k], 'Process') }
}
Set-Content -LiteralPath (Get-Phase1PidFile 'ui') -Value $proc.Id -Encoding ASCII

$deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
$up = $false
while ((Get-Date) -lt $deadline -and -not $proc.HasExited) {
    try { if ((Invoke-WebRequest -Uri "http://localhost:$($Phase1.UiPort)/" -UseBasicParsing -TimeoutSec 5).StatusCode -eq 200) { $up = $true; break } } catch { }
    Start-Sleep -Seconds 2
}
if ($proc.HasExited -or -not $up) {
    Get-Content -LiteralPath $outLog -Tail 40 -ErrorAction SilentlyContinue
    Get-Content -LiteralPath $errLog -Tail 40 -ErrorAction SilentlyContinue
    throw 'The Phase 1 UI did not start.'
}
Write-Step "Phase 1 UI is up on http://localhost:$($Phase1.UiPort)/ (PID $($proc.Id), newest design ON). Logs: $outLog"
