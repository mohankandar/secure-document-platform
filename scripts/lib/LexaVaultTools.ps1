# Shared helpers for LexaVault's install commands (task 0037): the schema step, setup, the demo step and the identity
# migration. Each runs a plain Java main class through Spring Boot's PropertiesLauncher: the schema step, the demo step
# and the identity migration from the backend jar; setup from its own jar (securedoc-setup, task 0041), because setup
# brings platform-crypto and platform-integration, which the app's classpath may not carry. Dot-source this file; it
# defines functions only.
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

# The setup wrapper jar (task 0041). Default: the one built next to the given backend jar
# (<backend>\securedoc-api\target\... -> <backend>\securedoc-setup\target\...), so a build in another worktree uses its
# own setup jar; then this checkout's.
function Resolve-LexaVaultSetupJar([string] $SetupJarPath, [string] $ApiJar) {
    if ($SetupJarPath) {
        if (-not (Test-Path -LiteralPath $SetupJarPath)) { throw "Setup jar not found: $SetupJarPath" }
        return (Resolve-Path -LiteralPath $SetupJarPath).Path
    }
    $candidates = @()
    if ($ApiJar) {
        $backend = Split-Path (Split-Path (Split-Path $ApiJar -Parent) -Parent) -Parent
        $candidates += Join-Path $backend 'securedoc-setup\target\securedoc-setup-0.1.0-SNAPSHOT.jar'
    }
    $candidates += Join-Path $script:LexaVaultRepoRoot 'backend\securedoc-setup\target\securedoc-setup-0.1.0-SNAPSHOT.jar'
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) { return (Resolve-Path -LiteralPath $candidate).Path }
    }
    throw "Setup jar not found: $($candidates[0]) (build it first: cd backend; mvn clean install)."
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
#   3. the LexaVault demo step (always; a rerun changes nothing).
# With -OidcIssuer (the local OIDC start, task 0041) setup runs on every start, with a sign-in file that names the local
# login system (written by LocalOidcSignin from the demo one) and --replace-connection, so sign-in goes through the
# issuer this start was given. Platform-core 0.2.0 takes the http issuer on localhost because the demo licence is signed
# by a development key. Setup changes nothing when the connection already matches.
# -StateDir receives setup's config fragment (platform-tenant.properties), its run log and the OIDC sign-in file.
# -SetupJar defaults to the setup jar built next to -Jar. Throws on any failure.
function Initialize-LexaVaultDatabase {
    param(
        [Parameter(Mandatory)] [string] $Jar,
        [Parameter(Mandatory)] [string] $DbUrl,
        [Parameter(Mandatory)] [string] $DbUser,
        [Parameter(Mandatory)] [string] $DbPassword,
        [Parameter(Mandatory)] [string] $StateDir,
        [string] $SetupJar,
        [string] $OidcIssuer,
        [string] $OidcProviderId,
        [string] $OidcAudience,
        [string] $OidcBrowserClientId
    )
    $demo = Join-Path $script:LexaVaultRepoRoot 'install\demo'
    $keys = Join-Path $demo 'development-trusted-keys.json'
    New-Item -ItemType Directory -Force -Path $StateDir | Out-Null
    $setupJarPath = Resolve-LexaVaultSetupJar $SetupJar $Jar

    Write-Host "Database: schema step..." -ForegroundColor Cyan
    $code = Invoke-LexaVaultJarMain -Jar $Jar -MainClass 'com.securedoc.install.SchemaStep' -Arguments @('apply') `
        -Environment (New-LexaVaultDbEnvironment 'LEXAVAULT_SCHEMA' $DbUrl $DbUser $DbPassword)
    if ($code -ne 0) { throw "The schema step failed (exit $code)." }

    $code = Invoke-LexaVaultJarMain -Jar $Jar -MainClass 'com.securedoc.install.DemoStep' -Arguments @('installed') `
        -Environment (New-LexaVaultDbEnvironment 'LEXAVAULT_DEMO' $DbUrl $DbUser $DbPassword)
    if ($code -ne 0 -and $code -ne 4) { throw "The demo step check failed (exit $code)." }
    $fresh = ($code -eq 4)

    if ($fresh -or $OidcIssuer) {
        $signin = Join-Path $demo 'signin-demo.json'
        $extra = @()
        if ($OidcIssuer) {
            if (-not $OidcProviderId -or -not $OidcAudience -or -not $OidcBrowserClientId) {
                throw "-OidcIssuer needs -OidcProviderId, -OidcAudience and -OidcBrowserClientId."
            }
            $signinDir = Join-Path $StateDir 'oidc-signin'
            $code = Invoke-LexaVaultJarMain -Jar $Jar -MainClass 'com.securedoc.install.LocalOidcSignin' -Arguments @(
                '--from', $signin, '--out', $signinDir, '--issuer', $OidcIssuer, '--provider-id', $OidcProviderId,
                '--audience', $OidcAudience, '--browser-client-id', $OidcBrowserClientId)
            if ($code -ne 0) { throw "The local OIDC sign-in file could not be written (exit $code)." }
            $signin = Join-Path $signinDir 'signin-local-oidc.json'
            # This start's login system wins over a connection changed earlier outside setup (by the 0037 demo step).
            $extra = @('--replace-connection')
            Write-Host "Database: setup with the demo files and the local login system $OidcIssuer..." -ForegroundColor Cyan
        }
        else {
            Write-Host "Database: the demo step has not run yet, so setup runs with the demo input files (install\demo)..." -ForegroundColor Cyan
        }
        $setupArgs = @('apply', '--licence', (Join-Path $demo 'licence-demo.json'), '--signin', $signin,
            '--product', 'LEXAVAULT', '--keys', $keys,
            '--product-roles', (Join-Path $script:LexaVaultRepoRoot 'install\product-roles.json'),
            '--config-out', (Join-Path $StateDir 'platform-tenant.properties'),
            '--run-log', (Join-Path $StateDir 'platform-setup-runs.log'),
            # LexaVault encrypts nothing with platform-core yet: product-roles.json says "customerKeys": "optional".
            '--no-customer-keys') + $extra
        $code = Invoke-LexaVaultJarMain -Jar $setupJarPath -MainClass 'com.documentplatform.setup.SetupCommand' -Arguments $setupArgs `
            -Environment (New-LexaVaultDbEnvironment 'PLATFORM_SETUP' $DbUrl $DbUser $DbPassword)
        if ($code -ne 0) { throw "Setup with the demo files failed (exit $code)." }
    }
    else { Write-Host "Database: the demo step has run; setup is not run again." -ForegroundColor DarkGray }

    Write-Host "Database: LexaVault demo step..." -ForegroundColor Cyan
    $demoArgs = @('apply', '--keys', $keys)
    $code = Invoke-LexaVaultJarMain -Jar $Jar -MainClass 'com.securedoc.install.DemoStep' -Arguments $demoArgs `
        -Environment (New-LexaVaultDbEnvironment 'LEXAVAULT_DEMO' $DbUrl $DbUser $DbPassword)
    if ($code -ne 0) { throw "The demo step failed (exit $code)." }
}
