#requires -Version 5.1
<#
.SYNOPSIS
    Task 0048: how long one case with the benchmark's 60-page package takes, inline and staged (numbers only).

.DESCRIPTION
    For each mode (inline, then staged): a THROWAWAY Postgres container (lexavault-0048-t-pg, 127.0.0.1:55450), the
    install steps, one server (18050) with export-mode job and the given pipeline-mode, the real local AI model
    (Ollama, default http://localhost:11434), one case holding the package, submitted and timed until it is exported
    (COMPLETED). The step times are read from the audit trail (first text read started to last text read done, and so
    on). The container is removed after each mode. Prints and writes numbers only (no file name, no value).

.PARAMETER PdfPath
    The 60-page package (the benchmark's public-26.pdf).
#>
[CmdletBinding()]
param(
    [string] $JarPath,
    [Parameter(Mandatory = $true)] [string] $PdfPath,
    [string] $RunRoot = (Join-Path $env:TEMP 'lexavault-0048-timing'),
    [int] $DbPort = 55450,
    [int] $AppPort = 18050,
    [string] $Ollama = 'http://localhost:11434',
    [string] $Model = 'qwen3.5:9b',
    [string[]] $Modes = @('inline', 'staged'),
    [int] $TimeoutSeconds = 1800,
    # The demo entity's auto-redact threshold for the run (throwaway database only). 0 sends every located finding
    # straight to Redact, so the timing covers Redact and Export too; empty keeps the demo policy (some go to Review).
    [string] $AutoRedactThreshold = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$here = $PSScriptRoot
$repoRoot = (Resolve-Path (Join-Path $here '..\..')).Path
if (-not $JarPath) { $JarPath = Join-Path $repoRoot 'backend\securedoc-api\target\securedoc-api-0.1.0-SNAPSHOT.jar' }
if (-not (Test-Path -LiteralPath $JarPath)) { throw "Backend jar not found: $JarPath" }
if (-not (Test-Path -LiteralPath $PdfPath)) { throw "Package not found: $PdfPath" }
$Container = 'lexavault-0048-t-pg'
$tenantId = '30216241-b724-5a3a-aef3-3034fd546d17'
$actorId = '00000000-0000-0000-0000-000000000099'
$roles = 'DOCUMENT_PROCESSOR,TENANT_IT_ADMIN'
$backend = "http://localhost:$AppPort"
. (Join-Path $repoRoot 'scripts\lib\LexaVaultTools.ps1')

function Invoke-Sql([string] $sql) {
    $out = docker exec $Container psql -U securedoc -d securedoc -tA -c $sql
    if ($LASTEXITCODE -ne 0) { throw "psql failed" }
    return (($out | Out-String).Trim())
}
function Invoke-Api([string] $Method, [string] $Path, $Body = $null) {
    $headers = @{ 'X-Tenant-Id' = $tenantId; 'X-Actor-Id' = $actorId; 'X-Actor-Roles' = $roles }
    $req = @{ Method = $Method; Uri = "$backend$Path"; Headers = $headers; UseBasicParsing = $true; TimeoutSec = 120 }
    if ($null -ne $Body) { $req.Body = ($Body | ConvertTo-Json -Depth 8); $req.ContentType = 'application/json' }
    return Invoke-RestMethod @req
}
function Span([string] $from, [string] $to) {
    $s = Invoke-Sql "select round(extract(epoch from (max(occurred_at) filter (where action = '$to') - min(occurred_at) filter (where action = '$from')))::numeric, 1) from audit_records"
    if ($s) { return [double]$s } else { return $null }
}

$results = New-Object System.Collections.ArrayList
foreach ($mode in $Modes) {
    $runDir = Join-Path $RunRoot ("$mode-" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    New-Item -ItemType Directory -Force -Path $runDir | Out-Null
    $app = $null
    $started = $false
    try {
        $existing = @(docker ps -a --filter "name=^/$Container$" --format '{{.Names}}')
        if ($existing.Count -gt 0) { throw "Container $Container already exists" }
        $pw = [guid]::NewGuid().ToString('N')
        docker run -d --name $Container -e POSTGRES_DB=securedoc -e POSTGRES_USER=securedoc -e "POSTGRES_PASSWORD=$pw" `
            -p "127.0.0.1:${DbPort}:5432" postgres:16-alpine | Out-Null
        $started = $true
        do { Start-Sleep -Seconds 1; docker exec $Container pg_isready -U securedoc -d securedoc 2>$null | Out-Null } while ($LASTEXITCODE -ne 0)
        Start-Sleep -Seconds 2
        Initialize-LexaVaultDatabase -Jar $JarPath -DbUrl "jdbc:postgresql://localhost:${DbPort}/securedoc" -DbUser 'securedoc' `
            -DbPassword $pw -StateDir (Join-Path $runDir 'install') | Out-Null
        if ($AutoRedactThreshold) {
            Invoke-Sql "update tenant_redaction_policies set auto_redact_threshold = $([double]$AutoRedactThreshold)" | Out-Null
        }

        $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:$DbPort/securedoc"
        $env:SPRING_DATASOURCE_USERNAME = 'securedoc'
        $env:SPRING_DATASOURCE_PASSWORD = $pw
        $env:SERVER_PORT = "$AppPort"
        $env:SECUREDOC_AI_PROVIDER = 'ollama'
        $env:SECUREDOC_AI_OLLAMA_BASE_URL = $Ollama
        $env:SECUREDOC_AI_MODEL = $Model
        $env:SECUREDOC_WATCHED_FOLDER_ENABLED = 'false'
        $env:SECUREDOC_WATCHED_FOLDER_SCHEDULING_ENABLED = 'false'
        $env:SECUREDOC_DELIVERY_LOCAL_OUTPUT_PATH = (Join-Path $runDir 'export-folder')
        $env:SECUREDOC_PROCESSING_PIPELINE_MODE = $mode
        $env:SECUREDOC_PROCESSING_EXPORT_MODE = 'job'
        try {
            $app = Start-Process -FilePath 'java' -PassThru -WindowStyle Hidden -WorkingDirectory $runDir `
                -ArgumentList @('-jar', $JarPath, '--spring.profiles.active=local',
                    "--securedoc.storage.local.root-path=$(Join-Path $runDir 'storage')") `
                -RedirectStandardOutput (Join-Path $runDir 'backend.out.log') -RedirectStandardError (Join-Path $runDir 'backend.err.log')
        } finally { Remove-Item Env:SPRING_DATASOURCE_PASSWORD -ErrorAction SilentlyContinue }
        $deadline = (Get-Date).AddSeconds(180)
        $up = $false
        while (-not $up -and (Get-Date) -lt $deadline) {
            Start-Sleep -Seconds 2
            try { $up = ((Invoke-RestMethod -Uri "$backend/actuator/health" -TimeoutSec 3).status -eq 'UP') } catch { }
        }
        if (-not $up) { throw "server did not start" }

        $suffix = Get-Random -Minimum 100000 -Maximum 999999
        $id = (Invoke-Api POST '/api/v1/packages' @{ packageType = 'MANUAL_UPLOAD'
            businessIdentifiers = @{ contract_number = "880048$suffix"; member_number = "90048$suffix"; service_entity = '001' } }).packageId
        Add-Type -AssemblyName System.Net.Http
        $client = [System.Net.Http.HttpClient]::new()
        try {
            $client.Timeout = [TimeSpan]::FromMinutes(5)
            $client.DefaultRequestHeaders.Add('X-Tenant-Id', $tenantId)
            $client.DefaultRequestHeaders.Add('X-Actor-Id', $actorId)
            $client.DefaultRequestHeaders.Add('X-Actor-Roles', $roles)
            $content = [System.Net.Http.MultipartFormDataContent]::new()
            $file = [System.Net.Http.ByteArrayContent]::new([System.IO.File]::ReadAllBytes($PdfPath))
            $file.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new('application/pdf')
            $content.Add($file, 'file', 'package.pdf')
            $r = $client.PostAsync("$backend/api/v1/packages/$id/documents", $content).GetAwaiter().GetResult()
            if (-not $r.IsSuccessStatusCode) { throw "upload failed: HTTP $([int]$r.StatusCode)" }
        } finally { $client.Dispose() }

        $clock = [System.Diagnostics.Stopwatch]::StartNew()
        Invoke-Api POST "/api/v1/packages/$id/submit" | Out-Null
        $status = $null
        do {
            Start-Sleep -Milliseconds 500
            $status = Invoke-Sql "select status from document_packages where id = '$id'"
        } while (@('COMPLETED', 'FAILED', 'PENDING_REVIEW', 'IN_REVIEW') -notcontains $status -and $clock.Elapsed.TotalSeconds -lt $TimeoutSeconds)
        $clock.Stop()
        $row = [pscustomobject]@{
            mode = $mode
            pages = [int](Invoke-Sql "select coalesce(sum(page_count), 0) from documents where package_id = '$id'")
            endStatus = $status
            submitToEndSeconds = [math]::Round($clock.Elapsed.TotalSeconds, 1)
            textSeconds = Span 'DOCUMENT_EXTRACTION_STARTED' 'DOCUMENT_EXTRACTION_COMPLETED'
            textDoneToFindDoneSeconds = Span 'DOCUMENT_EXTRACTION_COMPLETED' 'DOCUMENT_DETECTION_COMPLETED'
            findDoneToRedactedSeconds = Span 'DOCUMENT_DETECTION_COMPLETED' 'DOCUMENT_REDACTION_VERIFIED'
            findings = [int](Invoke-Sql "select count(*) from findings")
        }
        [void]$results.Add($row)
        Write-Host ("TIMING_0048 mode={0} pages={1} end={2} submitToEnd={3}s text={4}s find={5}s redact={6}s findings={7}" -f `
            $row.mode, $row.pages, $row.endStatus, $row.submitToEndSeconds, $row.textSeconds,
            $row.textDoneToFindDoneSeconds, $row.findDoneToRedactedSeconds, $row.findings)
    } finally {
        if ($app -and -not $app.HasExited) { Stop-Process -Id $app.Id -Force -ErrorAction SilentlyContinue }
        if ($started) { docker rm -f $Container | Out-Null }
        foreach ($n in 'SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME', 'SERVER_PORT', 'SECUREDOC_AI_PROVIDER',
                'SECUREDOC_AI_OLLAMA_BASE_URL', 'SECUREDOC_AI_MODEL', 'SECUREDOC_WATCHED_FOLDER_ENABLED',
                'SECUREDOC_WATCHED_FOLDER_SCHEDULING_ENABLED', 'SECUREDOC_DELIVERY_LOCAL_OUTPUT_PATH',
                'SECUREDOC_PROCESSING_PIPELINE_MODE', 'SECUREDOC_PROCESSING_EXPORT_MODE') {
            Remove-Item "Env:$n" -ErrorAction SilentlyContinue
        }
    }
}
$results | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $RunRoot 'timing.json')
