param(
    [switch]$SkipBuild,
    [switch]$SkipFrontendAuth,
    [switch]$RequireLocalRuntime,
    [string]$ExpectedAiModel = "qwen3.5:4b",
    [int]$ExpectedLiquibaseChangesets = 70,
    [string]$OutputRoot = ".\release-readiness-results"
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$BackendDir = Join-Path $ProjectRoot "backend"
$FrontendDir = Join-Path $ProjectRoot "frontend\securedoc-ui"
$ChangelogDir = Join-Path $BackendDir "securedoc-infrastructure\src\main\resources\db\changelog"
$RunId = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ") + "-" + ([guid]::NewGuid().ToString("N").Substring(0, 8))
$ResultDir = Join-Path (Join-Path $ProjectRoot $OutputRoot) ("final-v1-preflight-" + $RunId)
New-Item -ItemType Directory -Force -Path $ResultDir | Out-Null

$checks = @()

function Add-Check {
    param(
        [string]$Name,
        [bool]$Passed,
        [string]$Detail
    )
    $script:checks += [pscustomobject]@{
        Check = $Name
        Passed = $Passed
        Detail = $Detail
    }
    if ($Passed) {
        Write-Host ("PASS  " + $Name + " - " + $Detail) -ForegroundColor Green
    }
    else {
        Write-Host ("FAIL  " + $Name + " - " + $Detail) -ForegroundColor Red
    }
}

function Require-Command {
    param([string]$Name)
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    Add-Check -Name ("Tool available: " + $Name) -Passed ([bool]$cmd) -Detail ($(if ($cmd) { $cmd.Source } else { "not found in PATH" }))
    return [bool]$cmd
}

function Test-TcpPort {
    param([string]$HostName, [int]$Port, [int]$TimeoutMs = 750)
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne($TimeoutMs, $false)) { return $false }
        $client.EndConnect($async)
        return $true
    }
    catch { return $false }
    finally { $client.Close() }
}

function Get-HttpStatusFromException {
    param($Exception)
    try {
        if ($Exception.Response -and $Exception.Response.StatusCode) {
            return [int]$Exception.Response.StatusCode
        }
    }
    catch { }
    return 0
}

Write-Host ""
Write-Host "=== LexaVault V1 Final Release-Readiness Preflight ===" -ForegroundColor Cyan
Write-Host "Run ID:       $RunId"
Write-Host "Project root: $ProjectRoot"
Write-Host "Evidence:     $ResultDir"
Write-Host ""

# Tool/source preflight.
$javaOk = Require-Command "java"
$mavenOk = Require-Command "mvn"
$nodeOk = Require-Command "node"
$npmOk = Require-Command "npm"

$requiredFiles = @(
    "backend\pom.xml",
    "frontend\securedoc-ui\package.json",
    "backend\securedoc-infrastructure\src\main\resources\db\changelog\027-package-processing-ha.xml",
    "backend\securedoc-infrastructure\src\main\resources\db\changelog\028-processing-storage-management.xml",
    "backend\securedoc-api\src\main\java\com\securedoc\platform\api\processing\DatabasePackageProcessingDispatchLease.java",
    "backend\securedoc-api\src\main\java\com\securedoc\platform\api\processing\PackageProcessingRecoveryScheduler.java",
    "backend\securedoc-application\src\main\java\com\securedoc\platform\application\processing\service\TriggerPackageProcessingService.java",
    "docs\LEXAVAULT_V1_MASTER_WORK_ITEM_MATRIX.md"
)
foreach ($relative in $requiredFiles) {
    $full = Join-Path $ProjectRoot $relative
    Add-Check -Name ("Required source: " + $relative) -Passed (Test-Path $full -PathType Leaf) -Detail ($(if (Test-Path $full -PathType Leaf) { "present" } else { "missing" }))
}

# Liquibase count from source, independent of database runtime.
$liquibaseCount = 0
try {
    Get-ChildItem -Path $ChangelogDir -File -Filter "*.xml" |
        Where-Object { $_.Name -ne "db.changelog-master.xml" } |
        ForEach-Object {
            [xml]$doc = Get-Content -LiteralPath $_.FullName -Raw
            $nodes = $doc.SelectNodes("//*[local-name()='changeSet']")
            if ($nodes) { $liquibaseCount += $nodes.Count }
        }
    Add-Check -Name "Liquibase source changeSet count" -Passed ($liquibaseCount -eq $ExpectedLiquibaseChangesets) -Detail ("actual=" + $liquibaseCount + ", expected=" + $ExpectedLiquibaseChangesets)
}
catch {
    Add-Check -Name "Liquibase source changeSet count" -Passed $false -Detail $_.Exception.Message
}

# Frozen performance profile/source invariants.
$launcherPath = Join-Path $ProjectRoot "Start-LexaVault-Local.ps1"
$launcherText = if (Test-Path $launcherPath) { Get-Content -LiteralPath $launcherPath -Raw } else { "" }
$batchInvariant = $launcherText.Contains('elseif ($Model -eq "qwen3.5:4b")') -and $launcherText.Contains('$env:SECUREDOC_AI_OLLAMA_PAGE_BATCH_SIZE') -and $launcherText.Contains('"4"')
Add-Check -Name "Accepted LOCAL Qwen page-batch override retained" -Passed $batchInvariant -Detail "expected qwen3.5:4b launcher override to page batch 4"

$appLocal = Join-Path $BackendDir "securedoc-api\src\main\resources\application-local.yml"
$appLocalText = if (Test-Path $appLocal) { Get-Content -LiteralPath $appLocal -Raw } else { "" }
$parallelismInvariant = $appLocalText.Contains('parallelism: ${SECUREDOC_AI_PARALLELISM:2}')
$charCapInvariant = $appLocalText.Contains('ollama-page-batch-max-characters: ${SECUREDOC_AI_OLLAMA_PAGE_BATCH_MAX_CHARACTERS:14000}')
Add-Check -Name "Accepted LOCAL AI parallelism retained" -Passed $parallelismInvariant -Detail "expected default parallelism 2"
Add-Check -Name "Accepted LOCAL AI batch character ceiling retained" -Passed $charCapInvariant -Detail "expected 14000 characters"

$recoveryScheduler = Join-Path $BackendDir "securedoc-api\src\main\java\com\securedoc\platform\api\processing\PackageProcessingRecoveryScheduler.java"
$recoveryText = if (Test-Path $recoveryScheduler) { Get-Content -LiteralPath $recoveryScheduler -Raw } else { "" }
Add-Check -Name "FIX4 recovery dispatch telemetry retained" -Passed ($recoveryText.Contains("PACKAGE_PROCESSING_RECOVERY_DISPATCHED")) -Detail "cross-node orphan recovery event"

# Release packaging hygiene policy. Build/test outputs are expected in an active developer
# workspace after build-all.cmd, so their presence must not fail release readiness. The
# release candidate itself is packaged separately with these paths excluded. Verify the
# repository exclusion policy here instead of requiring a pristine workspace.
$gitignorePath = Join-Path $ProjectRoot ".gitignore"
$gitignoreText = if (Test-Path $gitignorePath) { Get-Content -LiteralPath $gitignorePath -Raw } else { "" }
$requiredIgnorePatterns = @(
    "node_modules/",
    "target/",
    "dist/",
    "build/",
    ".gradle/",
    "performance-results/",
    "performance-logs/",
    "performance-cluster/",
    ".run/",
    "release-readiness-results/"
)
$missingIgnorePatterns = @($requiredIgnorePatterns | Where-Object { -not $gitignoreText.Contains($_) })
$ignorePolicyOk = (Test-Path $gitignorePath -PathType Leaf) -and ($missingIgnorePatterns.Count -eq 0)
Add-Check -Name "Release packaging exclusion policy" -Passed $ignorePolicyOk -Detail ($(if ($ignorePolicyOk) { "generated/cache/result paths are excluded by .gitignore and release packaging policy" } else { "missing .gitignore patterns: " + ($missingIgnorePatterns -join ", ") }))

# Final-gate documentation is evidence, not runtime product source. Report it as a
# non-blocking informational check so a script-only hotfix can still validate the product.
$finalGateReadme = Join-Path $ProjectRoot "FINAL_V1_RELEASE_READINESS_README.md"
$fix4Readme = Join-Path $ProjectRoot "PRODUCTION_PERFORMANCE_R3_FIX4_PACKAGE_PROCESSING_HA_RECOVERY_README.md"
$docsPresent = (Test-Path $finalGateReadme -PathType Leaf) -and (Test-Path $fix4Readme -PathType Leaf)
if ($docsPresent) {
    Add-Check -Name "Release-readiness evidence documentation" -Passed $true -Detail "final-gate and R3 FIX4 evidence READMEs present"
}
else {
    Write-Host "INFO  Release-readiness evidence documentation - one or more optional evidence READMEs are not present in this working tree" -ForegroundColor Yellow
}

# Full build.
if (-not $SkipBuild) {
    if (-not ($javaOk -and $mavenOk -and $nodeOk -and $npmOk)) {
        Add-Check -Name "Full repository build" -Passed $false -Detail "required build tools are missing"
    }
    else {
        $buildLog = Join-Path $ResultDir "build-all.log"
        $buildStdout = Join-Path $ResultDir "build-all.stdout.log"
        $buildStderr = Join-Path $ResultDir "build-all.stderr.log"
        Write-Host ""
        Write-Host "Running full repository build..." -ForegroundColor Cyan
        Push-Location $ProjectRoot
        try {
            # Windows PowerShell 5.1 can turn native stderr (for example java -version)
            # into a terminating ErrorRecord when $ErrorActionPreference is Stop. Run the
            # batch file through Start-Process so stderr is captured as text and only the
            # real process exit code determines build success/failure.
            $buildProcess = Start-Process -FilePath "cmd.exe" `
                -ArgumentList @("/d", "/c", ".\build-all.cmd") `
                -WorkingDirectory $ProjectRoot `
                -Wait -PassThru -NoNewWindow `
                -RedirectStandardOutput $buildStdout `
                -RedirectStandardError $buildStderr

            $buildExit = $buildProcess.ExitCode
            $stdoutText = if (Test-Path $buildStdout) { Get-Content -LiteralPath $buildStdout -Raw } else { "" }
            $stderrText = if (Test-Path $buildStderr) { Get-Content -LiteralPath $buildStderr -Raw } else { "" }
            $combinedText = $stdoutText
            if ($stderrText) {
                if ($combinedText -and -not $combinedText.EndsWith([Environment]::NewLine)) { $combinedText += [Environment]::NewLine }
                $combinedText += $stderrText
            }
            Set-Content -LiteralPath $buildLog -Value $combinedText -Encoding UTF8
            if ($combinedText) { Write-Host $combinedText.TrimEnd() }
            Add-Check -Name "Full repository build" -Passed ($buildExit -eq 0) -Detail ("exitCode=" + $buildExit + "; log=" + $buildLog)
        }
        catch {
            Add-Check -Name "Full repository build" -Passed $false -Detail $_.Exception.Message
        }
        finally { Pop-Location }
    }
}
else {
    Add-Check -Name "Full repository build" -Passed $true -Detail "skipped by caller; prior result must be supplied separately"
}

# Explicit UI authorization regression because production build does not run this script.
if (-not $SkipFrontendAuth) {
    if (-not $npmOk) {
        Add-Check -Name "Frontend authorization tests" -Passed $false -Detail "npm unavailable"
    }
    elseif (-not (Test-Path (Join-Path $FrontendDir "node_modules"))) {
        Add-Check -Name "Frontend authorization tests" -Passed $false -Detail "node_modules missing; run full build first or npm ci"
    }
    else {
        $authLog = Join-Path $ResultDir "frontend-auth.log"
        Write-Host ""
        Write-Host "Running frontend authorization tests..." -ForegroundColor Cyan
        Push-Location $FrontendDir
        try {
            & npm run test:auth 2>&1 | Tee-Object -FilePath $authLog
            $authExit = $LASTEXITCODE
            Add-Check -Name "Frontend authorization tests" -Passed ($authExit -eq 0) -Detail ("exitCode=" + $authExit + "; log=" + $authLog)
        }
        catch {
            Add-Check -Name "Frontend authorization tests" -Passed $false -Detail $_.Exception.Message
        }
        finally { Pop-Location }
    }
}
else {
    Add-Check -Name "Frontend authorization tests" -Passed $true -Detail "skipped by caller; prior result must be supplied separately"
}

# Representative runtime checks. These are intentionally small; frozen long-running evidence is reused.
if ($RequireLocalRuntime) {
    Write-Host ""
    Write-Host "Running representative LOCAL runtime checks..." -ForegroundColor Cyan

    try {
        $health = Invoke-RestMethod -Uri "http://localhost:8080/actuator/health" -Method Get -TimeoutSec 5
        Add-Check -Name "Backend health" -Passed ([string]$health.status -eq "UP") -Detail ("status=" + [string]$health.status)
    }
    catch { Add-Check -Name "Backend health" -Passed $false -Detail $_.Exception.Message }

    $processorHeaders = @{
        "X-Tenant-Id" = "00000000-0000-0000-0000-000000000001"
        "X-Actor-Id" = "00000000-0000-0000-0000-000000000099"
        "X-Actor-Roles" = "DOCUMENT_PROCESSOR"
    }
    try {
        $me = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/me" -Method Get -Headers $processorHeaders -TimeoutSec 5
        $roles = @($me.roles)
        $canonical = $roles -contains "DOCUMENT_PROCESSOR"
        Add-Check -Name "LOCAL canonical identity" -Passed $canonical -Detail ("roles=" + ($roles -join ","))
    }
    catch { Add-Check -Name "LOCAL canonical identity" -Passed $false -Detail $_.Exception.Message }

    $crossTenantHeaders = @{
        "X-Tenant-Id" = "00000000-0000-0000-0000-000000000002"
        "X-Actor-Id" = "00000000-0000-0000-0000-000000000099"
        "X-Actor-Roles" = "BUSINESS_ADMIN"
    }
    $crossTenantStatus = 0
    try {
        Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:8080/api/v1/me" -Method Get -Headers $crossTenantHeaders -TimeoutSec 5 | Out-Null
        $crossTenantStatus = 200
    }
    catch {
        $crossTenantStatus = Get-HttpStatusFromException -Exception $_.Exception
    }
    Add-Check -Name "Unprovisioned tenant fails closed" -Passed ($crossTenantStatus -eq 403) -Detail ("httpStatus=" + $crossTenantStatus)

    try {
        $tags = Invoke-RestMethod -Uri "http://localhost:11434/api/tags" -Method Get -TimeoutSec 5
        $models = @($tags.models | ForEach-Object { $_.name })
        Add-Check -Name "LOCAL reference AI model installed" -Passed ($models -contains $ExpectedAiModel) -Detail ("expected=" + $ExpectedAiModel + "; installed=" + ($models -join ","))
    }
    catch { Add-Check -Name "LOCAL reference AI model installed" -Passed $false -Detail $_.Exception.Message }

    try {
        $scannerHealth = Invoke-RestMethod -Uri "http://127.0.0.1:10090/actuator/health" -Method Get -TimeoutSec 5
        Add-Check -Name "Scanner Bridge health" -Passed ([string]$scannerHealth.status -eq "UP") -Detail ("status=" + [string]$scannerHealth.status)
    }
    catch { Add-Check -Name "Scanner Bridge health" -Passed $false -Detail $_.Exception.Message }

    try {
        $scannerConfig = Invoke-RestMethod -Uri "http://127.0.0.1:10090/api/v1/configuration" -Method Get -TimeoutSec 5
        $scannerEnabled = [bool]$scannerConfig.enabled
        Add-Check -Name "Scanner Bridge configuration" -Passed $scannerEnabled -Detail ("enabled=" + $scannerEnabled + "; captureMode=" + [string]$scannerConfig.captureMode)
    }
    catch { Add-Check -Name "Scanner Bridge configuration" -Passed $false -Detail $_.Exception.Message }
}

$checks | Export-Csv -LiteralPath (Join-Path $ResultDir "preflight-checks.csv") -NoTypeInformation -Encoding UTF8
$summary = [pscustomobject]@{
    runId = $RunId
    generatedAt = (Get-Date).ToString("o")
    projectRoot = $ProjectRoot
    expectedLiquibaseChangesets = $ExpectedLiquibaseChangesets
    actualLiquibaseChangesets = $liquibaseCount
    expectedAiModel = $ExpectedAiModel
    buildSkipped = [bool]$SkipBuild
    frontendAuthSkipped = [bool]$SkipFrontendAuth
    localRuntimeRequired = [bool]$RequireLocalRuntime
    passed = @($checks | Where-Object { $_.Passed }).Count
    failed = @($checks | Where-Object { -not $_.Passed }).Count
    verdict = if (@($checks | Where-Object { -not $_.Passed }).Count -eq 0) { "PASS" } else { "FAIL" }
}
$summary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $ResultDir "preflight-summary.json") -Encoding UTF8

Write-Host ""
Write-Host "=== Final V1 preflight summary ===" -ForegroundColor Cyan
$summary | Format-List
Write-Host ("Checks: " + (Join-Path $ResultDir "preflight-checks.csv"))
Write-Host ("Summary: " + (Join-Path $ResultDir "preflight-summary.json"))

if ($summary.failed -gt 0) { exit 1 }
exit 0
