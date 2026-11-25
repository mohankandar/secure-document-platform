#requires -Version 5.1
<#
.SYNOPSIS
    The erase replay after a restore from a backup (task 0060, docs/install/ERASE_LOG.md).

.DESCRIPTION
    Does every erasure made after the backup again, checks that nothing in the erase log can be read, prints a report and
    exits 0 (done) or 1 (not complete; fix the cause and run it again). Run it with the app's normal settings in the
    environment (database, storage, key service, PLATFORM_ERASELOG_LOCATION) while every LexaVault server is stopped.

.PARAMETER Seal
    Seal an existing erase log once instead (it was written before the key service was set up).

.PARAMETER AppOptions
    Passed to the app as they are, for example --spring.profiles.active=local.
#>
[CmdletBinding()]
param(
    [switch] $Seal,
    [string] $JarPath,
    [string[]] $AppOptions = @()
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\LexaVaultTools.ps1')
$jar = Resolve-LexaVaultJar $JarPath
$option = if ($Seal) { '--erase-log-seal' } else { '--erase-replay' }
# A port of its own: this process serves nobody.
& java -jar $jar $option '--server.port=0' '--spring.main.banner-mode=off' @AppOptions
exit $LASTEXITCODE
