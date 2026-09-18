param(
    [Parameter(Mandatory = $true)]
    [string]$PdfPath,

    [string]$InboxPath = 'C:\LexaVaultTest\scanner-inbox',

    [string]$FileName
)

$ErrorActionPreference = 'Stop'

$source = (Resolve-Path $PdfPath).Path
if ([System.IO.Path]::GetExtension($source).ToLowerInvariant() -ne '.pdf') {
    throw 'Scanner Bridge V1 simulator accepts PDF input only.'
}

if (-not $FileName) {
    $FileName = [System.IO.Path]::GetFileName($source)
}
if ([System.IO.Path]::GetExtension($FileName).ToLowerInvariant() -ne '.pdf') {
    throw 'The simulated scanner output filename must end in .pdf.'
}

New-Item -ItemType Directory -Path $InboxPath -Force | Out-Null

$target = Join-Path $InboxPath $FileName
if (Test-Path $target) {
    throw "Scanner inbox target already exists: $target"
}

# Prepare outside the watched inbox and move the completed file into place. This models a scanner
# that exposes the final PDF only after its write has completed. Stable-file detection in the bridge
# still independently verifies the file before claiming it.
$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) 'lexavault-scan-simulator'
New-Item -ItemType Directory -Path $tempRoot -Force | Out-Null
$temp = Join-Path $tempRoot ([guid]::NewGuid().ToString() + '.pdf')

try {
    Copy-Item -LiteralPath $source -Destination $temp
    Move-Item -LiteralPath $temp -Destination $target
    Write-Host "Simulated scan staged: $target"
}
finally {
    if (Test-Path $temp) {
        Remove-Item -LiteralPath $temp -Force -ErrorAction SilentlyContinue
    }
}
