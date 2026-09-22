#requires -Version 5.1
<#
.SYNOPSIS
    Centralized configuration, prerequisite validation, and intake-identifier factory for the
    LexaVault acceptance harness.

.DESCRIPTION
    Reusable test-tooling helper. Loading this script (dot-source or & invoke) exposes three
    functions used across the harness so configuration and known-good test data live in ONE place:

      Get-LvConfig            -> resolves all endpoints/paths from env vars with local/demo defaults
      Test-LvPrerequisites    -> validates the known local acceptance prerequisites (fail-fast)
      New-LvTestIdentifiers   -> produces API-valid business identifiers WITHOUT DB rediscovery

    Windows PowerShell 5.1 compatible; safe under Set-StrictMode -Version Latest.

    This file contains NO production code and NO secrets. The DB password is never needed because
    DB access goes through the running container via `docker exec psql`.
#>

Set-StrictMode -Version Latest

# ---------------------------------------------------------------------------
# Centralized intake identifier rules (authoritative constants).
# These mirror the tenant's configured validation so the harness does not need to rediscover
# them from the database on every run. They are only re-validated against the DB if an intake
# call is rejected (see Assert-LvIdentifiersValid).
#   contract_number : exactly 12 numeric digits (Primary; must be unique per business record)
#   member_number   : exactly 11 numeric digits
#   service_entity  : exactly 3 numeric digits, restricted to an allowed-values set
# ---------------------------------------------------------------------------
$script:LV_CONTRACT_LENGTH       = 12
$script:LV_MEMBER_LENGTH         = 11
$script:LV_SERVICE_ENTITY_LENGTH = 3
$script:LV_SERVICE_ENTITY_ALLOWED = @('000', '001', '002', '003', '004', '005')

function Get-LvEnvOrDefault {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string] $Name, [Parameter(Mandatory)][AllowEmptyString()][string] $Default)
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($value)) { return $Default }
    return $value
}

<#
.SYNOPSIS
    Returns a single object with every endpoint, path, and DB coordinate the harness uses.
#>
function Get-LvConfig {
    [CmdletBinding()]
    param()

    # Resolve the repo root relative to this helpers directory (…/test-tools/lexavault-acceptance/helpers).
    $toolRoot = Split-Path -Parent $PSScriptRoot
    $repoRoot = (Resolve-Path (Join-Path $toolRoot '..\..')).Path

    [PSCustomObject]@{
        RepoRoot     = $repoRoot
        ToolRoot     = $toolRoot
        HelpersDir   = $PSScriptRoot
        FrontendDir  = Join-Path $repoRoot 'frontend\securedoc-ui'
        JarPath      = Join-Path $repoRoot 'backend\securedoc-api\target\securedoc-api-0.1.0-SNAPSHOT.jar'
        FixturePath  = Join-Path $repoRoot 'test-data\golden-path-watched-intake-template\02-auto-redaction\02_auto_redaction_credit_score.pdf'

        BackendUrl   = (Get-LvEnvOrDefault 'SECUREDOC_BACKEND_URL'  'http://localhost:8080').TrimEnd('/')
        FrontendUrl  = (Get-LvEnvOrDefault 'SECUREDOC_FRONTEND_URL' 'http://localhost:5173').TrimEnd('/')
        OllamaUrl    = (Get-LvEnvOrDefault 'SECUREDOC_OLLAMA_URL'   'http://localhost:11434').TrimEnd('/')

        TenantId     = Get-LvEnvOrDefault 'SECUREDOC_TENANT_ID' '00000000-0000-0000-0000-000000000001'
        ActorId      = Get-LvEnvOrDefault 'SECUREDOC_ACTOR_ID'  '00000000-0000-0000-0000-000000000099'

        DbContainer  = Get-LvEnvOrDefault 'SECUREDOC_DB_CONTAINER' 'securedoc-postgres'
        DbName       = Get-LvEnvOrDefault 'SECUREDOC_DB_NAME'      'securedoc'
        DbUser       = Get-LvEnvOrDefault 'SECUREDOC_DB_USER'      'securedoc'

        # Tesseract: prefer env; only fall back to the established local path if it exists.
        TesseractPath = $(
            $fromEnv = [Environment]::GetEnvironmentVariable('SECUREDOC_OCR_TESSERACT_PATH')
            if (-not [string]::IsNullOrWhiteSpace($fromEnv)) { $fromEnv }
            elseif (Test-Path 'C:\dev\Tesseract-OCR\tesseract.exe') { 'C:\dev\Tesseract-OCR\tesseract.exe' }
            else { $null }
        )
    }
}

function Test-LvHttpUp {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string] $Url, [int] $TimeoutSec = 5)
    try {
        Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec $TimeoutSec | Out-Null
        return $true
    } catch {
        return $false
    }
}

<#
.SYNOPSIS
    Validates the known local acceptance prerequisites and returns a report object.

.PARAMETER Config
    A config object from Get-LvConfig. Resolved automatically if omitted.

.PARAMETER RequireBackendUp
    When set, a reachable backend is required (default: not required, because the orchestrator
    starts the backend itself). Frontend/Ollama/DB/Java/jar are always required.

.OUTPUTS
    PSCustomObject with .ok (bool), .checks (array), and .failures (string[]). Throws only if
    -ThrowOnFailure is set.
#>
function Test-LvPrerequisites {
    [CmdletBinding()]
    param(
        [object] $Config,
        [switch] $RequireBackendUp,
        [switch] $ThrowOnFailure
    )

    if ($null -eq $Config) { $Config = Get-LvConfig }

    $checks = New-Object System.Collections.ArrayList
    function Add-Check($name, $ok, $detail) {
        [void]$checks.Add([PSCustomObject]@{ name = [string]$name; ok = [bool]$ok; detail = [string]$detail })
    }

    # Windows PowerShell 5.1 (Desktop edition). PS 7+ Core also works, but the harness targets 5.1.
    $psOk = ($PSVersionTable.PSVersion.Major -ge 5)
    Add-Check 'PowerShell >= 5.1' $psOk ("$($PSVersionTable.PSVersion) ($($PSVersionTable.PSEdition))")

    # docker CLI + the postgres container reachable (query through docker exec psql).
    $dockerOk = $false; $dockerDetail = 'docker not found'
    try {
        docker version 1>$null 2>$null
        if ($LASTEXITCODE -eq 0) {
            $ping = @(& (Join-Path $PSScriptRoot 'Invoke-TestDbQuery.ps1') -Sql 'SELECT 1;')
            $dockerOk = ($ping.Count -ge 1)
            $dockerDetail = "container '$($Config.DbContainer)' db '$($Config.DbName)' reachable"
        }
    } catch { $dockerDetail = $_.Exception.Message }
    Add-Check 'PostgreSQL via docker exec' $dockerOk $dockerDetail

    # Frontend :5173
    $feOk = Test-LvHttpUp -Url $Config.FrontendUrl
    Add-Check 'Frontend reachable' $feOk $Config.FrontendUrl

    # Ollama :11434
    $ollamaOk = Test-LvHttpUp -Url "$($Config.OllamaUrl)/api/tags"
    Add-Check 'Ollama reachable' $ollamaOk "$($Config.OllamaUrl)/api/tags"

    # Java 21 on PATH. `java -version` writes to stderr; under StrictMode + ErrorActionPreference=Stop
    # a merged native-stderr stream would surface as a terminating NativeCommandError, so relax the
    # preference locally and coerce every merged line to plain text before matching.
    $javaOk = $false; $javaDetail = 'java not found'
    $prevEap = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $verLines = @(& java -version 2>&1 | ForEach-Object { "$_" })
        $verText = ($verLines -join "`n")
        if ($verLines.Count -gt 0) { $javaDetail = "$($verLines[0])".Trim() }
        if ($verText -match 'version "(\d+)') { $javaOk = ([int]$Matches[1] -ge 21) }
    } catch {
        $javaDetail = $_.Exception.Message
    } finally {
        $ErrorActionPreference = $prevEap
    }
    Add-Check 'Java >= 21' $javaOk $javaDetail

    # Tesseract path resolved (env or established local fallback)
    $tessOk = ($null -ne $Config.TesseractPath) -and (Test-Path $Config.TesseractPath)
    Add-Check 'Tesseract path resolved' $tessOk ($(if ($Config.TesseractPath) { $Config.TesseractPath } else { 'not set (SECUREDOC_OCR_TESSERACT_PATH)' }))

    # Backend jar present
    $jarOk = Test-Path $Config.JarPath
    Add-Check 'Backend jar present' $jarOk $Config.JarPath

    # Fixture present
    $fixOk = Test-Path $Config.FixturePath
    Add-Check 'Fixture present' $fixOk $Config.FixturePath

    # Backend up (only if required)
    if ($RequireBackendUp) {
        $beOk = Test-LvHttpUp -Url "$($Config.BackendUrl)/actuator/health"
        Add-Check 'Backend reachable' $beOk "$($Config.BackendUrl)/actuator/health"
    }

    $checksArr = @($checks.ToArray())
    $failures = New-Object System.Collections.ArrayList
    foreach ($c in $checksArr) {
        if (-not $c.ok) { [void]$failures.Add("$($c.name): $($c.detail)") }
    }
    $failuresArr = @($failures.ToArray())
    $result = [PSCustomObject]@{
        ok       = ($failuresArr.Count -eq 0)
        checks   = $checksArr
        failures = $failuresArr
    }

    if ($ThrowOnFailure -and -not $result.ok) {
        throw ("Prerequisite check failed:`n  - " + ($failuresArr -join "`n  - "))
    }
    return $result
}

<#
.SYNOPSIS
    Returns API-valid business identifiers (contract/member/service_entity) for a fresh intake.

.DESCRIPTION
    Uses the centralized identifier rules; does NOT query the database. contract_number is derived
    from the clock plus randomness so each run creates a distinct business record (Primary must be
    unique) while staying exactly 12 numeric digits.
#>
function New-LvTestIdentifiers {
    [CmdletBinding()]
    param()

    # 12-digit unique contract: '9' + 11 digits from ticks (keeps within 12 and easy to spot).
    $ticks = [string]([DateTime]::UtcNow.Ticks)
    $tail11 = $ticks.Substring($ticks.Length - $script:LV_MEMBER_LENGTH)
    $contract = ('9' + $tail11)
    if ($contract.Length -ne $script:LV_CONTRACT_LENGTH) {
        $contract = $contract.PadLeft($script:LV_CONTRACT_LENGTH, '0').Substring(0, $script:LV_CONTRACT_LENGTH)
    }

    # 11-digit member number.
    $member = ('9' + ('{0:D10}' -f (Get-Random -Minimum 0 -Maximum ([int]::MaxValue))).PadLeft(10, '0').Substring(0, 10))
    $member = $member.Substring(0, $script:LV_MEMBER_LENGTH)

    # service_entity from the allowed set.
    $service = Get-Random -InputObject $script:LV_SERVICE_ENTITY_ALLOWED

    $ids = [PSCustomObject]@{
        contract_number = $contract
        member_number   = $member
        service_entity  = $service
    }
    Assert-LvIdentifiersValid -Identifiers $ids | Out-Null
    return $ids
}

<#
.SYNOPSIS
    Local shape validation of identifiers against the centralized rules (no DB access).
    Throws on violation so callers fail fast before hitting the API.
#>
function Assert-LvIdentifiersValid {
    [CmdletBinding()]
    param([Parameter(Mandatory)][object] $Identifiers)

    $problems = New-Object System.Collections.Generic.List[string]
    if ("$($Identifiers.contract_number)" -notmatch ('^\d{' + $script:LV_CONTRACT_LENGTH + '}$')) {
        $problems.Add("contract_number must be exactly $($script:LV_CONTRACT_LENGTH) digits (got '$($Identifiers.contract_number)')")
    }
    if ("$($Identifiers.member_number)" -notmatch ('^\d{' + $script:LV_MEMBER_LENGTH + '}$')) {
        $problems.Add("member_number must be exactly $($script:LV_MEMBER_LENGTH) digits (got '$($Identifiers.member_number)')")
    }
    if ($script:LV_SERVICE_ENTITY_ALLOWED -notcontains "$($Identifiers.service_entity)") {
        $problems.Add("service_entity must be one of [$($script:LV_SERVICE_ENTITY_ALLOWED -join ',')] (got '$($Identifiers.service_entity)')")
    }
    if ($problems.Count -gt 0) {
        throw ("Invalid test identifiers:`n  - " + (($problems.ToArray()) -join "`n  - "))
    }
    return $true
}
