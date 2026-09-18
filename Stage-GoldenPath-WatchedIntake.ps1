param(
    [ValidateSet("All", "01-clean", "02-auto-redaction", "03-manual-review", "04-approval", "05-reprocess", "06-duplicate-a", "06-duplicate-b", "07-export")]
    [string]$Case = "All"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$TemplateRoot = Join-Path $ProjectRoot "test-data\golden-path-watched-intake-template"
$WatchedRoot = Join-Path $ProjectRoot "backend\watched-intake"
$StagingRoot = Join-Path $ProjectRoot ".run\watched-intake-staging"

$cases = @(
    "01-clean",
    "02-auto-redaction",
    "03-manual-review",
    "04-approval",
    "05-reprocess",
    "06-duplicate-a",
    "06-duplicate-b",
    "07-export"
)
if ($Case -ne "All") { $cases = @($Case) }

New-Item -ItemType Directory -Force -Path $WatchedRoot | Out-Null
New-Item -ItemType Directory -Force -Path $StagingRoot | Out-Null

foreach ($name in $cases) {
    $source = Join-Path $TemplateRoot $name
    if (-not (Test-Path $source -PathType Container)) {
        throw "Golden-path template not found: $source"
    }

    $target = Join-Path $WatchedRoot $name
    if (Test-Path $target) {
        throw "Watched-intake target already exists: $target. Let the watcher consume it or rename/remove it before staging again."
    }

    $stage = Join-Path $StagingRoot ("{0}-{1}" -f $name, [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path $stage | Out-Null

    # Copy all document payloads first. The manifest is deliberately copied last.
    Get-ChildItem -LiteralPath $source -File | Where-Object { $_.Name -ne "manifest.json" } | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $stage $_.Name)
    }
    Copy-Item -LiteralPath (Join-Path $source "manifest.json") -Destination (Join-Path $stage "manifest.json")

    # Publish the complete directory into the watched root in one move.
    Move-Item -LiteralPath $stage -Destination $target
    Write-Host "Staged: $name -> $target" -ForegroundColor Green
}

Write-Host ""
Write-Host "LexaVault LOCAL scans backend\watched-intake automatically (default every 10 seconds)."
Write-Host "Accepted/duplicate folders move to _archive; rejected folders move to _problem."
