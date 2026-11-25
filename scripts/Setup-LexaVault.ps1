#requires -Version 5.1
<#
.SYNOPSIS
    LexaVault's setup command (task 0037): platform-core's setup for product LEXAVAULT, with the files LexaVault ships
    (product-roles.json) passed for you.

.DESCRIPTION
    Loads your licence, your customer and entity records, your login system and your group-to-role mapping into the
    LexaVault database. It checks everything first, shows a plan, and changes nothing until you run apply. Running it
    again is safe. See docs/install/SETUP.md and platform-core's docs/install/setup.md.

    Database settings in the environment only (never printed):
        PLATFORM_SETUP_DB_URL, PLATFORM_SETUP_DB_USER, PLATFORM_SETUP_DB_PASSWORD

    Wired now: --licence, --signin (with its role mapping file), --product-roles.
    Setup runs from its own jar (backend\securedoc-setup\target, task 0041): it brings platform-crypto and
    platform-integration, which the app's classpath may not carry. -JarPath names that setup jar.

    Licence keys (task 0054, platform-core 0.7.0): they are inside the setup jar; no option or file here adds one. The
    demo licence is taken only by a development build (mvn -Pdev-build; Build-LexaVault-All.ps1 without -Production).

    Customer keys (task 0060): setup creates them in your key service; install\product-roles.json says
    "customerKeys": "required". Its login, in the environment only: PLATFORM_SETUP_VAULT_* (docs/install/KEY_SERVICE.md).

    Not wired yet (placeholders, each comes with its own task):
      - notifications settings: task 0040;
      - integration client bindings (client ID -> entity -> source): a later task;
      - entity time zones: the sign-in file's optional timeZone / entityTimeZones work today; the app uses them with
        task 0039.
      - the licence in the app (platform.licensing.tenant-id from platform-tenant.properties): task 0038.

.PARAMETER Command
    plan (default) or apply.
#>
[CmdletBinding()]
param(
    [ValidateSet('plan', 'apply')]
    [string] $Command = 'plan',
    [Parameter(Mandatory)] [string] $Licence,
    [Parameter(Mandatory)] [string] $Signin,
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
if (-not $ProductRoles) { $ProductRoles = Join-Path $repoRoot 'install\product-roles.json' }

$jar = Resolve-LexaVaultSetupJar $JarPath
$arguments = @($Command, '--licence', $Licence, '--signin', $Signin, '--product', 'LEXAVAULT',
    '--product-roles', $ProductRoles)
if ($ConfigOut) { $arguments += @('--config-out', $ConfigOut) }
if ($RunLog) { $arguments += @('--run-log', $RunLog) }
if ($RemoveUnlistedGrants) { $arguments += '--remove-unlisted-grants' }
if ($ReplaceConnection) { $arguments += '--replace-connection' }
exit (Invoke-LexaVaultJarMain -Jar $jar -MainClass 'com.documentplatform.setup.SetupCommand' -Arguments $arguments)
