# Shared helpers for LexaVault's install commands (task 0037): the schema step, setup, the demo step and the identity
# migration. Each runs a plain Java main class from the backend jar through Spring Boot's PropertiesLauncher, so the
# jar that serves the app also carries its install steps. Dot-source this file; it defines functions only.
#
# Database settings are passed in the child process environment only (never on a command line), and are never
# printed.

$script:LexaVaultRepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

function Resolve-LexaVaultJar([string] $JarPath) {
    if ($JarPath) {
        if (-not (Test-Path -LiteralPath $JarPath)) { throw "Backend jar not found: $JarPath" }
        return (Resolve-Path -LiteralPath $JarPath).Path
    }
    $default = Join-Path $script:LexaVaultRepoRoot 'backend\securedoc-api\target\securedoc-api-0.1.0-SNAPSHOT.jar'
    if (-not (Test-Path -LiteralPath $default)) {
        throw "Backend jar not found: $default (build it first: cd backend; mvn clean install)."
    }
    return $default
}

# Runs one install command. -Environment holds the settings for the child process only; the caller's environment is
# restored afterwards. Returns the command's exit code; its output goes to the console.
function Invoke-LexaVaultJarMain {
    param(
        [Parameter(Mandatory)] [string] $Jar,
        [Parameter(Mandatory)] [string] $MainClass,
        [string[]] $Arguments = @(),
        [hashtable] $Environment = @{}
    )
    $saved = @{}
    foreach ($name in $Environment.Keys) {
        $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, [string]$Environment[$name], 'Process')
    }
    try {
        $javaArgs = @('-cp', $Jar, "-Dloader.main=$MainClass", 'org.springframework.boot.loader.launch.PropertiesLauncher') + $Arguments
        # Out-Host: the command's output goes to the console, so this function returns only the exit code. Its log
        # lines on stderr are shown, not treated as a failure (Windows PowerShell would stop on them); the exit code
        # alone says whether the command worked.
        $ErrorActionPreference = 'Continue'
        & java @javaArgs 2>&1 | ForEach-Object { "$_" } | Out-Host
        return $LASTEXITCODE
    }
    finally {
        foreach ($name in $saved.Keys) {
            [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process')
        }
    }
}

# The database settings of one install command, under its own prefix (LEXAVAULT_SCHEMA, PLATFORM_SETUP, ...).
function New-LexaVaultDbEnvironment([string] $Prefix, [string] $Url, [string] $User, [string] $Password) {
    return @{ "${Prefix}_DB_URL" = $Url; "${Prefix}_DB_USER" = $User; "${Prefix}_DB_PASSWORD" = $Password }
}

# The demo entity: the entity UUID derived from demo slot ENT-DEMO-0001 (platform-identity EntityUuid.fromSlotId).
$script:LexaVaultDemoEntityId = '30216241-b724-5a3a-aef3-3034fd546d17'

# A development or test database brought up with the real install steps, in order (task 0037):
#   1. the schema step (always; safe to rerun),
#   2. while the demo step has not made the demo entity yet ("fresh" in this sense, task 0037 fix 1; it covers a
#      database from before 0037 and one whose demo step stopped half-way): setup apply with the demo input files
#      (install\demo); setup is safe to rerun and then changes nothing,
#   3. the LexaVault demo step (always; a rerun changes nothing), with the local OIDC login connection when -OidcIssuer
#      is given.
# -StateDir receives setup's config fragment (platform-tenant.properties) and run log. Throws on any failure.
function Initialize-LexaVaultDatabase {
    param(
        [Parameter(Mandatory)] [string] $Jar,
        [Parameter(Mandatory)] [string] $DbUrl,
        [Parameter(Mandatory)] [string] $DbUser,
        [Parameter(Mandatory)] [string] $DbPassword,
        [Parameter(Mandatory)] [string] $StateDir,
        [string] $OidcIssuer,
        [string] $OidcProviderId,
        [string] $OidcAudience,
        [string] $OidcBrowserClientId
    )
    $demo = Join-Path $script:LexaVaultRepoRoot 'install\demo'
    $keys = Join-Path $demo 'development-trusted-keys.json'
    New-Item -ItemType Directory -Force -Path $StateDir | Out-Null

    Write-Host "Database: schema step..." -ForegroundColor Cyan
    $code = Invoke-LexaVaultJarMain -Jar $Jar -MainClass 'com.securedoc.install.SchemaStep' -Arguments @('apply') `
        -Environment (New-LexaVaultDbEnvironment 'LEXAVAULT_SCHEMA' $DbUrl $DbUser $DbPassword)
    if ($code -ne 0) { throw "The schema step failed (exit $code)." }

    $code = Invoke-LexaVaultJarMain -Jar $Jar -MainClass 'com.securedoc.install.DemoStep' -Arguments @('installed') `
        -Environment (New-LexaVaultDbEnvironment 'LEXAVAULT_DEMO' $DbUrl $DbUser $DbPassword)
    if ($code -eq 4) {
        Write-Host "Database: the demo step has not run yet, so setup runs with the demo input files (install\demo)..." -ForegroundColor Cyan
        $setupArgs = @('apply', '--licence', (Join-Path $demo 'licence-demo.json'), '--signin', (Join-Path $demo 'signin-demo.json'),
            '--product', 'LEXAVAULT', '--keys', $keys,
            '--product-roles', (Join-Path $script:LexaVaultRepoRoot 'install\product-roles.json'),
            '--config-out', (Join-Path $StateDir 'platform-tenant.properties'),
            '--run-log', (Join-Path $StateDir 'platform-setup-runs.log'),
            '--no-customer-keys')
        $code = Invoke-LexaVaultJarMain -Jar $Jar -MainClass 'com.documentplatform.setup.SetupCommand' -Arguments $setupArgs `
            -Environment (New-LexaVaultDbEnvironment 'PLATFORM_SETUP' $DbUrl $DbUser $DbPassword)
        if ($code -ne 0) { throw "Setup with the demo files failed (exit $code)." }
    }
    elseif ($code -ne 0) { throw "The demo step check failed (exit $code)." }
    else { Write-Host "Database: the demo step has run; setup is not run again." -ForegroundColor DarkGray }

    Write-Host "Database: LexaVault demo step..." -ForegroundColor Cyan
    $demoArgs = @('apply', '--keys', $keys)
    if ($OidcIssuer) {
        $demoArgs += @('--oidc-issuer', $OidcIssuer, '--oidc-provider-id', $OidcProviderId, '--oidc-audience', $OidcAudience)
        if ($OidcBrowserClientId) { $demoArgs += @('--oidc-browser-client-id', $OidcBrowserClientId) }
    }
    $code = Invoke-LexaVaultJarMain -Jar $Jar -MainClass 'com.securedoc.install.DemoStep' -Arguments $demoArgs `
        -Environment (New-LexaVaultDbEnvironment 'LEXAVAULT_DEMO' $DbUrl $DbUser $DbPassword)
    if ($code -ne 0) { throw "The demo step failed (exit $code)." }
}
