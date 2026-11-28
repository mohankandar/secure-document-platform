# Task 0120: find a running Java language server (VS Code's Java extension, redhat.java / Eclipse JDT LS). While it runs it
# holds files under the checkout's target\ folders, so a clean build fails halfway ("Failed to delete ...\target\classes").
# Dot-source this file; it defines functions only. It only reports: it never stops a process.

$script:LexaVaultJavaLanguageServerMarkers = @('org.eclipse.jdt.ls', 'jdt.ls', 'redhat.java')

$script:LexaVaultJavaLanguageServerMessage = "VS Code's Java extension is running and locks build files. " +
    "Close VS Code (or run 'Java: Clean Java Language Server Workspace' and close it), then run this again."

# Returns the processes that are Java language servers. -Processes takes objects with Name, ProcessId and CommandLine
# (as Win32_Process gives them); without it, the running java processes are read. A process whose command line can't be
# read is not counted.
function Find-LexaVaultJavaLanguageServer {
    param([object[]] $Processes)
    if (-not $PSBoundParameters.ContainsKey('Processes')) {
        $Processes = @(Get-CimInstance -ClassName Win32_Process -Filter "Name LIKE 'java%'" -ErrorAction SilentlyContinue)
    }
    $found = @()
    foreach ($process in @($Processes)) {
        if ($null -eq $process) { continue }
        $name = [string] $process.Name
        $commandLine = [string] $process.CommandLine
        if ($name -notmatch '^javaw?(\.exe)?$') { continue }
        if ([string]::IsNullOrEmpty($commandLine)) { continue }
        foreach ($marker in $script:LexaVaultJavaLanguageServerMarkers) {
            if ($commandLine.IndexOf($marker, [StringComparison]::OrdinalIgnoreCase) -ge 0) { $found += $process; break }
        }
    }
    return , $found
}

# The message to show, or $null when no Java language server runs.
function Get-LexaVaultJavaLanguageServerRefusal {
    param([object[]] $Processes)
    $found = if (-not $PSBoundParameters.ContainsKey('Processes')) { Find-LexaVaultJavaLanguageServer } else { Find-LexaVaultJavaLanguageServer -Processes $Processes }
    if ($found.Count -eq 0) { return $null }
    $ids = ($found | ForEach-Object { $_.ProcessId }) -join ', '
    return "$($script:LexaVaultJavaLanguageServerMessage) (java process id: $ids)"
}
