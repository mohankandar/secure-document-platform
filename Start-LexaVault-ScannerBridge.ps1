param(
    [string]$TenantId = "",
    [string]$BridgeId = "",
    [string]$SiteId = "",
    [string]$ScannerId = "",
    [ValidateSet("SIMULATOR", "PHYSICAL")]
    [string]$Mode = "",
    [string]$ClientPrincipal = "",
    [string]$LocalActorId = "",
    [string]$BindAddress = "",
    [int]$Port = 0,
    [string]$InboxPath = "",
    [string]$WorkPath = "",
    [string]$ProblemPath = "",
    [string]$LexaVaultBaseUrl = ""
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$BackendDir = Join-Path $ProjectRoot "backend"
$BridgeTargetDir = Join-Path $BackendDir "securedoc-scan-bridge\target"
$RunDir = Join-Path $ProjectRoot ".run"
$PidFile = Join-Path $RunDir "scanner-bridge-shell.pid"

function Resolve-Setting {
    param(
        [string]$ExplicitValue,
        [string]$EnvironmentName,
        [string]$DefaultValue
    )

    if (-not [string]::IsNullOrWhiteSpace($ExplicitValue)) {
        return $ExplicitValue
    }

    $environmentValue = [Environment]::GetEnvironmentVariable($EnvironmentName)
    if (-not [string]::IsNullOrWhiteSpace($environmentValue)) {
        return $environmentValue
    }

    return $DefaultValue
}

function Resolve-Port {
    param([int]$ExplicitPort)

    if ($ExplicitPort -gt 0) {
        return $ExplicitPort
    }

    $environmentPort = [Environment]::GetEnvironmentVariable("SECUREDOC_SCAN_BRIDGE_PORT")
    $parsed = 0
    if (-not [string]::IsNullOrWhiteSpace($environmentPort) -and [int]::TryParse($environmentPort, [ref]$parsed) -and $parsed -gt 0) {
        return $parsed
    }

    return 10090
}

function Test-LoopbackBindAddress {
    param([string]$HostName)

    if ([string]::IsNullOrWhiteSpace($HostName)) { return $false }
    try {
        $addresses = [System.Net.Dns]::GetHostAddresses($HostName.Trim())
        if (-not $addresses -or $addresses.Count -eq 0) { return $false }
        foreach ($address in $addresses) {
            if (-not [System.Net.IPAddress]::IsLoopback($address)) { return $false }
        }
        return $true
    }
    catch { return $false }
}

function Test-TcpPort {
    param([string]$HostName, [int]$PortNumber, [int]$TimeoutMs = 750)

    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect($HostName, $PortNumber, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne($TimeoutMs, $false)) { return $false }
        $client.EndConnect($async)
        return $true
    }
    catch { return $false }
    finally { $client.Close() }
}

function Wait-ForBridgeHealth {
    param([string]$HostName, [int]$PortNumber, [int]$MaxSeconds = 60)

    $healthUri = "http://$HostName`:$PortNumber/actuator/health"
    $deadline = (Get-Date).AddSeconds($MaxSeconds)

    while ((Get-Date) -lt $deadline) {
        try {
            $health = Invoke-RestMethod -Uri $healthUri -Method Get -TimeoutSec 2
            if ($health.status -eq "UP") {
                return
            }
        }
        catch {
            # Process may still be starting. Retry until the deadline.
        }
        Start-Sleep -Seconds 1
    }

    throw "Scanner Bridge did not become healthy at $healthUri within $MaxSeconds seconds."
}

function Resolve-BridgeJar {
    if (-not (Test-Path $BridgeTargetDir)) { return $null }

    return Get-ChildItem -Path $BridgeTargetDir -File -Filter "securedoc-scan-bridge-*.jar" -ErrorAction SilentlyContinue |
        Where-Object {
            $_.Name -notmatch '\.original$' -and
            $_.Name -notmatch '-sources\.jar$' -and
            $_.Name -notmatch '-javadoc\.jar$'
        } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
}

if (-not (Get-Command "java" -ErrorAction SilentlyContinue)) {
    throw "Required command 'java' was not found in PATH. Java 21 is required for the LexaVault local environment."
}

$effectiveTenantId = Resolve-Setting $TenantId "SECUREDOC_SCAN_BRIDGE_TENANT_ID" "00000000-0000-0000-0000-000000000001"
$effectiveBridgeId = Resolve-Setting $BridgeId "SECUREDOC_SCAN_BRIDGE_ID" "BRIDGE-LOCAL-01"
$effectiveSiteId = Resolve-Setting $SiteId "SECUREDOC_SCAN_BRIDGE_SITE_ID" "HOME-LAB"
$effectiveMode = (Resolve-Setting $Mode "SECUREDOC_SCAN_BRIDGE_MODE" "SIMULATOR").ToUpperInvariant()
if ($effectiveMode -notin @("SIMULATOR", "PHYSICAL")) { throw "SECUREDOC_SCAN_BRIDGE_MODE must be SIMULATOR or PHYSICAL." }
$defaultScannerId = if ($effectiveMode -eq "PHYSICAL") { "SCANNER-PHYSICAL-01" } else { "SCANNER-SIM-01" }
$effectiveScannerId = Resolve-Setting $ScannerId "SECUREDOC_SCAN_BRIDGE_SCANNER_ID" $defaultScannerId
$effectiveClientPrincipal = Resolve-Setting $ClientPrincipal "SECUREDOC_SCAN_BRIDGE_CLIENT_PRINCIPAL" "scanner-bridge-local"
$effectiveLocalActorId = Resolve-Setting $LocalActorId "SECUREDOC_SCAN_BRIDGE_LOCAL_ACTOR_ID" "00000000-0000-0000-0000-000000000099"
$effectiveBearerToken = Resolve-Setting "" "SECUREDOC_SCAN_BRIDGE_AUTH_BEARER_TOKEN" ""
$effectiveBindAddress = Resolve-Setting $BindAddress "SECUREDOC_SCAN_BRIDGE_BIND_ADDRESS" "127.0.0.1"
if (-not (Test-LoopbackBindAddress -HostName $effectiveBindAddress)) {
    throw "Scanner Bridge must bind only to a loopback address. Refusing bind address '$effectiveBindAddress'."
}
$effectivePort = Resolve-Port $Port
$effectiveInboxPath = Resolve-Setting $InboxPath "SECUREDOC_SCAN_BRIDGE_INBOX_PATH" "C:\LexaVaultTest\scanner-inbox"
$effectiveWorkPath = Resolve-Setting $WorkPath "SECUREDOC_SCAN_BRIDGE_WORK_PATH" "C:\LexaVaultTest\scanner-bridge-work"
$effectiveProblemPath = Resolve-Setting $ProblemPath "SECUREDOC_SCAN_BRIDGE_PROBLEM_PATH" "C:\LexaVaultTest\scanner-bridge-problem"
$effectiveLexaVaultBaseUrl = Resolve-Setting $LexaVaultBaseUrl "SECUREDOC_SCAN_BRIDGE_LEXAVAULT_BASE_URL" "http://localhost:8080/api/v1"

Write-Host ""
Write-Host "=== LexaVault Scanner Bridge LOCAL startup ===" -ForegroundColor Cyan
Write-Host "Project root: $ProjectRoot"
Write-Host "Bridge ID:    $effectiveBridgeId"
Write-Host "Site ID:      $effectiveSiteId"
Write-Host "Scanner ID:   $effectiveScannerId"
Write-Host "Mode:         $effectiveMode"
Write-Host "Endpoint:     http://$effectiveBindAddress`:$effectivePort"
if ($effectiveMode -eq "SIMULATOR") { Write-Host "Inbox:        $effectiveInboxPath" }
Write-Host "Work:         $effectiveWorkPath"
Write-Host "Problem:      $effectiveProblemPath"
Write-Host ""

New-Item -ItemType Directory -Force -Path $RunDir | Out-Null
if ($effectiveMode -eq "SIMULATOR") { New-Item -ItemType Directory -Force -Path $effectiveInboxPath | Out-Null }
New-Item -ItemType Directory -Force -Path $effectiveWorkPath | Out-Null
New-Item -ItemType Directory -Force -Path $effectiveProblemPath | Out-Null

if (Test-TcpPort -HostName $effectiveBindAddress -PortNumber $effectivePort) {
    try {
        $health = Invoke-RestMethod -Uri "http://$effectiveBindAddress`:$effectivePort/actuator/health" -Method Get -TimeoutSec 3
        if ($health.status -eq "UP") {
            $configuration = Invoke-RestMethod -Uri "http://$effectiveBindAddress`:$effectivePort/api/v1/configuration" -Method Get -TimeoutSec 3
            $runningMode = [string]$configuration.captureMode
            if (-not [string]::IsNullOrWhiteSpace($runningMode) -and $runningMode.ToUpperInvariant() -ne $effectiveMode) {
                throw "Scanner Bridge is already running in $runningMode mode, but $effectiveMode mode was requested. Stop the running bridge before switching modes."
            }
            Write-Host "Scanner Bridge is already running and healthy in $effectiveMode mode." -ForegroundColor Yellow
            Write-Host "Health: http://$effectiveBindAddress`:$effectivePort/actuator/health"
            exit 0
        }
    }
    catch {
        throw "Port $effectivePort is already in use and the running Scanner Bridge could not be reused: $($_.Exception.Message)"
    }
}

$bridgeJar = Resolve-BridgeJar
if (-not $bridgeJar) {
    Write-Host "Built Scanner Bridge JAR was not found under:" -ForegroundColor Red
    Write-Host "  $BridgeTargetDir"
    Write-Host ""
    Write-Host "Build the project first, for example:"
    Write-Host "  .\build-all.cmd"
    Write-Host ""
    throw "Scanner Bridge has not been built yet."
}

Write-Host "Using Scanner Bridge JAR: $($bridgeJar.FullName)" -ForegroundColor DarkGray

function Escape-SingleQuotedPowerShell {
    param([string]$Value)
    return $Value.Replace("'", "''")
}

$jarEscaped = Escape-SingleQuotedPowerShell $bridgeJar.FullName
$backendEscaped = Escape-SingleQuotedPowerShell $BackendDir
$tenantEscaped = Escape-SingleQuotedPowerShell $effectiveTenantId
$bridgeEscaped = Escape-SingleQuotedPowerShell $effectiveBridgeId
$siteEscaped = Escape-SingleQuotedPowerShell $effectiveSiteId
$scannerEscaped = Escape-SingleQuotedPowerShell $effectiveScannerId
$modeEscaped = Escape-SingleQuotedPowerShell $effectiveMode
$clientPrincipalEscaped = Escape-SingleQuotedPowerShell $effectiveClientPrincipal
$localActorEscaped = Escape-SingleQuotedPowerShell $effectiveLocalActorId
$bindEscaped = Escape-SingleQuotedPowerShell $effectiveBindAddress
$inboxEscaped = Escape-SingleQuotedPowerShell $effectiveInboxPath
$workEscaped = Escape-SingleQuotedPowerShell $effectiveWorkPath
$problemEscaped = Escape-SingleQuotedPowerShell $effectiveProblemPath
$lexaVaultEscaped = Escape-SingleQuotedPowerShell $effectiveLexaVaultBaseUrl

$bridgeCommand = @"
`$Host.UI.RawUI.WindowTitle = 'LexaVault Scanner Bridge - LOCAL'
Set-Location -LiteralPath '$backendEscaped'
`$env:SECUREDOC_SCAN_BRIDGE_ENABLED = 'true'
`$env:SECUREDOC_SCAN_BRIDGE_TENANT_ID = '$tenantEscaped'
`$env:SECUREDOC_SCAN_BRIDGE_ID = '$bridgeEscaped'
`$env:SECUREDOC_SCAN_BRIDGE_SITE_ID = '$siteEscaped'
`$env:SECUREDOC_SCAN_BRIDGE_SCANNER_ID = '$scannerEscaped'
`$env:SECUREDOC_SCAN_BRIDGE_MODE = '$modeEscaped'
`$env:SECUREDOC_SCAN_BRIDGE_CLIENT_PRINCIPAL = '$clientPrincipalEscaped'
`$env:SECUREDOC_SCAN_BRIDGE_LOCAL_ACTOR_ID = '$localActorEscaped'
`$env:SECUREDOC_SCAN_BRIDGE_BIND_ADDRESS = '$bindEscaped'
`$env:SECUREDOC_SCAN_BRIDGE_PORT = '$effectivePort'
`$env:SECUREDOC_SCAN_BRIDGE_INBOX_PATH = '$inboxEscaped'
`$env:SECUREDOC_SCAN_BRIDGE_WORK_PATH = '$workEscaped'
`$env:SECUREDOC_SCAN_BRIDGE_PROBLEM_PATH = '$problemEscaped'
`$env:SECUREDOC_SCAN_BRIDGE_LEXAVAULT_BASE_URL = '$lexaVaultEscaped'
java -jar '$jarEscaped'
"@

Write-Host "Starting Scanner Bridge in a separate PowerShell window..."
# A bearer token is a secret. Never place it in the child command line or print it.
$priorBearerToken = [Environment]::GetEnvironmentVariable("SECUREDOC_SCAN_BRIDGE_AUTH_BEARER_TOKEN", "Process")
try {
    if (-not [string]::IsNullOrWhiteSpace($effectiveBearerToken)) {
        [Environment]::SetEnvironmentVariable("SECUREDOC_SCAN_BRIDGE_AUTH_BEARER_TOKEN", $effectiveBearerToken, "Process")
    } else {
        [Environment]::SetEnvironmentVariable("SECUREDOC_SCAN_BRIDGE_AUTH_BEARER_TOKEN", $null, "Process")
    }
    $bridgeShell = Start-Process -FilePath "powershell.exe" `
        -ArgumentList @("-NoProfile", "-NoExit", "-Command", $bridgeCommand) `
        -PassThru
} finally {
    [Environment]::SetEnvironmentVariable("SECUREDOC_SCAN_BRIDGE_AUTH_BEARER_TOKEN", $priorBearerToken, "Process")
}

Set-Content -Path $PidFile -Value $bridgeShell.Id

try {
    Wait-ForBridgeHealth -HostName $effectiveBindAddress -PortNumber $effectivePort -MaxSeconds 60
}
catch {
    if (-not (Get-Process -Id $bridgeShell.Id -ErrorAction SilentlyContinue)) {
        Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
    }
    throw
}

Write-Host ""
Write-Host "=== LexaVault Scanner Bridge is ready ===" -ForegroundColor Green
Write-Host "Health:        http://$effectiveBindAddress`:$effectivePort/actuator/health"
Write-Host "Configuration: http://$effectiveBindAddress`:$effectivePort/api/v1/configuration"
Write-Host "Mode:          $effectiveMode"
if ($effectiveMode -eq "SIMULATOR") { Write-Host "Inbox:         $effectiveInboxPath" }
Write-Host ""
if ($effectiveMode -eq "SIMULATOR") {
    Write-Host "Open LexaVault -> Scan Documents. After Start Scan, use Select Test PDF; scanner hardware is not required." -ForegroundColor Cyan
} else {
    Write-Host "PHYSICAL mode uses the Windows WIA adapter. Load the complete package in the scanner, then use Start Scan in LexaVault." -ForegroundColor Yellow
}
Write-Host "This development launcher does not delete scanner folders when the bridge is stopped." -ForegroundColor DarkGray
