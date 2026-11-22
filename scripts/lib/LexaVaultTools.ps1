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
