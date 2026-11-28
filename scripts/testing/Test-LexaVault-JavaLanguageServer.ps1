<#
.SYNOPSIS
    Task 0120: checks the Java language server detection used by Update-And-Start.ps1, with fake process lists.
    Needs nothing running. Exit code 0 when every case passes.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\testing\Test-LexaVault-JavaLanguageServer.ps1
#>
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot '..\lib\LexaVault-JavaLanguageServer.ps1')

$script:failures = 0
function Check([string] $Name, [bool] $Ok) {
    if ($Ok) { Write-Host "PASS  $Name" } else { Write-Host "FAIL  $Name" -ForegroundColor Red; $script:failures++ }
}
function Fake([int] $Id, [string] $Name, [string] $CommandLine) {
    [pscustomobject] @{ ProcessId = $Id; Name = $Name; CommandLine = $CommandLine }
}

$vsCode = Fake 4242 'java.exe' ('"C:\Users\me\.vscode\extensions\redhat.java-1.40.0-win32-x64\jre\21\bin\java.exe" ' +
    '-Declipse.application=org.eclipse.jdt.ls.core.id1 -jar ' +
    'c:\Users\me\.vscode\extensions\redhat.java-1.40.0-win32-x64\server\plugins\org.eclipse.equinox.launcher_1.6.900.jar')
$jdtOnly = Fake 4343 'javaw.exe' 'javaw -jar C:\tools\jdt.ls\plugins\launcher.jar -data C:\ws'
$backend = Fake 5000 'java.exe' 'java -jar backend\securedoc-api\target\securedoc-api-0.1.0-SNAPSHOT.jar --spring.profiles.active=local'
$maven = Fake 5001 'java.exe' 'java -classpath C:\maven\boot\plexus-classworlds.jar org.codehaus.plexus.classworlds.launcher.Launcher verify'
$unreadable = Fake 5002 'java.exe' $null
$notJava = Fake 6000 'Code.exe' 'Code.exe --type=utility redhat.java org.eclipse.jdt.ls'

# Found
$found = Find-LexaVaultJavaLanguageServer -Processes @($backend, $vsCode, $maven)
Check 'VS Code redhat.java language server is found' ($found.Count -eq 1 -and $found[0].ProcessId -eq 4242)
$found = Find-LexaVaultJavaLanguageServer -Processes @($jdtOnly)
Check 'a javaw jdt.ls server is found' ($found.Count -eq 1 -and $found[0].ProcessId -eq 4343)
$found = Find-LexaVaultJavaLanguageServer -Processes @($vsCode, $jdtOnly)
Check 'two servers are both found' ($found.Count -eq 2)
$refusal = Get-LexaVaultJavaLanguageServerRefusal -Processes @($backend, $vsCode)
Check 'the refusal gives the wanted message' ($null -ne $refusal -and
    $refusal.StartsWith("VS Code's Java extension is running and locks build files. Close VS Code (or run 'Java: Clean Java Language Server Workspace' and close it), then run this again."))
Check 'the refusal names the process id' ($null -ne $refusal -and $refusal.Contains('4242'))

# Not found
$found = Find-LexaVaultJavaLanguageServer -Processes @($backend, $maven)
Check 'the LexaVault backend and Maven are not counted' ($found.Count -eq 0)
$found = Find-LexaVaultJavaLanguageServer -Processes @($notJava)
Check 'a process that is not java is not counted' ($found.Count -eq 0)
$found = Find-LexaVaultJavaLanguageServer -Processes @($unreadable)
Check 'a java process without a readable command line is not counted' ($found.Count -eq 0)
$found = Find-LexaVaultJavaLanguageServer -Processes @()
Check 'an empty list finds nothing' ($found.Count -eq 0)
Check 'no refusal when nothing is found' ($null -eq (Get-LexaVaultJavaLanguageServerRefusal -Processes @($backend)))

# The real process list can be read (whatever runs on this machine)
$live = Find-LexaVaultJavaLanguageServer
Check 'the live process list is read without an error' ($null -ne $live)

if ($script:failures -gt 0) { Write-Host "$script:failures failed" -ForegroundColor Red; exit 1 }
Write-Host 'All passed' -ForegroundColor Green
exit 0
