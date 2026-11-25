#requires -Version 5.1
<#
.SYNOPSIS
    Activates and names the customer's first entity at a new installation (task 0075, docs/install/SETUP.md).

.DESCRIPTION
    Run it after the schema step and setup, with the app's normal settings in the environment (database, storage, licence,
    key service). Running it again with the same values changes nothing. Exit codes: 0 done, 1 failed, 2 refused.

.PARAMETER AppOptions
    Passed to the app as they are, for example --spring.profiles.active=local.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $SlotId,
    [Parameter(Mandatory)] [string] $Name,
    [string] $JarPath,
    [string[]] $AppOptions = @()
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\LexaVaultTools.ps1')
$jar = Resolve-LexaVaultJar $JarPath
# A port of its own: this process serves nobody and takes no work.
& java -jar $jar '--activate-first-entity' "--entity-slot=$SlotId" "--entity-name=$Name" '--server.port=0' '--server.address=127.0.0.1' '--spring.main.banner-mode=off' @AppOptions
exit $LASTEXITCODE
