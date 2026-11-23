#requires -Version 5.1
<#
.SYNOPSIS
    LexaVault's setup command (task 0037): platform-core's setup for product LEXAVAULT, with the files LexaVault ships
    (trusted keys, product-roles.json) passed for you.

.DESCRIPTION
    Loads your licence, your customer and entity records, your login system and your group-to-role mapping into the
    LexaVault database. It checks everything first, shows a plan, and changes nothing until you run apply. Running it
    again is safe. See docs/install/SETUP.md and platform-core's docs/install/setup.md.

    Database settings in the environment only (never printed):
        PLATFORM_SETUP_DB_URL, PLATFORM_SETUP_DB_USER, PLATFORM_SETUP_DB_PASSWORD

    Wired now: --licence, --signin (with its role mapping file), --keys, --product-roles.
    Setup runs from its own jar (backend\securedoc-setup\target, task 0041): it brings platform-crypto and
    platform-integration, which the app's classpath may not carry. -JarPath names that setup jar.

    Customer keys: --no-customer-keys is passed. LexaVault encrypts nothing with platform-core yet, and
    install\product-roles.json says "customerKeys": "optional" (task 0041), so setup accepts it.

    Not wired yet (placeholders, each comes with its own task):
      - notifications settings: task 0040;
      - integration client bindings (client ID -> entity -> source): a later task;
      - entity time zones: the sign-in file's optional timeZone / entityTimeZones work today; the app uses them with
        task 0039.
      - the licence in the app (platform.licensing.tenant-id from platform-tenant.properties): task 0038.

.PARAMETER Command
    plan (default) or apply.
.PARAMETER Keys
    The release's trusted-keys.json (default install\trusted-keys.json, shipped with task 0038). The demo uses
    install\demo\development-trusted-keys.json, which production never lists.
#>
[CmdletBinding()]
param(
    [ValidateSet('plan', 'apply')]
    [string] $Command = 'plan',
    [Parameter(Mandatory)] [string] $Licence,
    [Parameter(Mandatory)] [string] $Signin,
    [string] $Keys,
    [string] $ProductRoles,
    [string] $ConfigOut,
    [string] $RunLog,
    [switch] $RemoveUnlistedGrants,
    [switch] $ReplaceConnection,
    [string] $JarPath
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\LexaVaultTools.ps1')

$repoRoot = $script:LexaVaultRepoRoot
if (-not $Keys) { $Keys = Join-Path $repoRoot 'install\trusted-keys.json' }
if (-not $ProductRoles) { $ProductRoles = Join-Path $repoRoot 'install\product-roles.json' }
if (-not (Test-Path -LiteralPath $Keys)) {
    throw "Trusted-keys file not found: $Keys. The release's production keys file comes with task 0038; for the demo pass -Keys install\demo\development-trusted-keys.json."
}

$jar = Resolve-LexaVaultSetupJar $JarPath
$arguments = @($Command, '--licence', $Licence, '--signin', $Signin, '--product', 'LEXAVAULT',
    '--keys', $Keys, '--product-roles', $ProductRoles,
    # LexaVault encrypts nothing with platform-core yet ("customerKeys": "optional" in product-roles.json).
    '--no-customer-keys')
if ($ConfigOut) { $arguments += @('--config-out', $ConfigOut) }
if ($RunLog) { $arguments += @('--run-log', $RunLog) }
if ($RemoveUnlistedGrants) { $arguments += '--remove-unlisted-grants' }
if ($ReplaceConnection) { $arguments += '--replace-connection' }
exit (Invoke-LexaVaultJarMain -Jar $jar -MainClass 'com.documentplatform.setup.SetupCommand' -Arguments $arguments)
