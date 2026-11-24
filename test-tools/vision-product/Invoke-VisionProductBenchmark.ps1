#requires -Version 5.1
<#
.SYNOPSIS
    Task 0064: the vision benchmark's samples (tools/vision-benchmark, part A) through the product, end to end.

.DESCRIPTION
    A THROWAWAY Postgres container (lexavault-0064-vb-pg), the install steps, one server with pipeline-mode staged and
    export-mode job, the real local Ollama with the text model and the pinned vision model. Every redaction kind is
    switched on. Each sample is one case, one after the other, timed from submit until it waits for a person or is
    exported. The findings (page, kind, box, path; never a value) are scored against the answer files by score.mjs.
    Results: ids, kinds, boxes, counts and seconds only. Samples and results stay outside the repository.
#>
[CmdletBinding()]
param(
    [string] $JarPath,
    [string] $Root = 'C:\dev\benchmark-samples',
    # Comma-separated sample ids, in this order; empty runs all.
    [string] $Only = '',
    [string] $RunRoot = (Join-Path $env:TEMP 'lexavault-0064-vision'),
    [int] $DbPort = 55465,
    [int] $AppPort = 18064,
    [string] $Ollama = 'http://localhost:11434',
    [string] $Model = 'qwen3.5:9b',
    [int] $VisionWorkers = 2,
    [switch] $VisionOff,
    [int] $TimeoutSeconds = 1800
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$here = $PSScriptRoot
$repoRoot = (Resolve-Path (Join-Path $here '..\..')).Path
if (-not $JarPath) { $JarPath = Join-Path $repoRoot 'backend\securedoc-api\target\securedoc-api-0.1.0-SNAPSHOT.jar' }
if (-not (Test-Path -LiteralPath $JarPath)) { throw "Backend jar not found: $JarPath" }
$Container = 'lexavault-0064-vb-pg'
$tenantId = '30216241-b724-5a3a-aef3-3034fd546d17'
$actorId = '00000000-0000-0000-0000-000000000099'
$roles = 'DOCUMENT_PROCESSOR,TENANT_IT_ADMIN'
$backend = "http://localhost:$AppPort"
. (Join-Path $repoRoot 'scripts\lib\LexaVaultTools.ps1')
Add-Type -AssemblyName System.Net.Http

function Invoke-Sql([string] $sql) {
    $out = docker exec $Container psql -U securedoc -d securedoc -tA -c $sql
    if ($LASTEXITCODE -ne 0) { throw 'psql failed' }
    return (($out | Out-String).Trim())
}
function Invoke-Api([string] $Method, [string] $Path, $Body = $null) {
    $headers = @{ 'X-Tenant-Id' = $tenantId; 'X-Actor-Id' = $actorId; 'X-Actor-Roles' = $roles }
    $req = @{ Method = $Method; Uri = "$backend$Path"; Headers = $headers; UseBasicParsing = $true; TimeoutSec = 120 }
    if ($null -ne $Body) { $req.Body = ($Body | ConvertTo-Json -Depth 8); $req.ContentType = 'application/json' }
    return Invoke-RestMethod @req
}
function Send-Sample([string] $PackageId, $Sample) {
    $client = [System.Net.Http.HttpClient]::new()
    try {
        $client.Timeout = [TimeSpan]::FromMinutes(5)
        $client.DefaultRequestHeaders.Add('X-Tenant-Id', $tenantId)
        $client.DefaultRequestHeaders.Add('X-Actor-Id', $actorId)
        $client.DefaultRequestHeaders.Add('X-Actor-Roles', $roles)
        $content = [System.Net.Http.MultipartFormDataContent]::new()
        $file = [System.Net.Http.ByteArrayContent]::new([System.IO.File]::ReadAllBytes($Sample.FullName))
        $file.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new('application/pdf')
        $content.Add($file, 'file', ($Sample.BaseName + '.pdf'))
        $r = $client.PostAsync("$backend/api/v1/packages/$PackageId/documents", $content).GetAwaiter().GetResult()
        if (-not $r.IsSuccessStatusCode) { throw "upload failed: HTTP $([int]$r.StatusCode)" }
    } finally { $client.Dispose() }
}

$samples = @(Get-ChildItem -LiteralPath (Join-Path $Root 'public') -Filter 'public-*.pdf' | Sort-Object Name)
if ($Only) { $samples = @($Only.Split(',') | ForEach-Object { $id = $_; $samples | Where-Object { $_.BaseName -eq $id } }) }
if ($samples.Count -eq 0) { throw 'No sample found' }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$runDir = Join-Path $RunRoot $stamp
$outDir = Join-Path $Root "results\product-$stamp"
New-Item -ItemType Directory -Force -Path $runDir, $outDir | Out-Null
$app = $null
$started = $false
try {
    $existing = @(docker ps -a --filter "name=^/$Container$" --format '{{.Names}}')
    if ($existing.Count -gt 0) { throw "Container $Container already exists" }
    $pw = [guid]::NewGuid().ToString('N')
    docker run -d --name $Container -e POSTGRES_DB=securedoc -e POSTGRES_USER=securedoc -e "POSTGRES_PASSWORD=$pw" `
        -p "127.0.0.1:${DbPort}:5432" postgres:16-alpine | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'docker run failed' }
    $started = $true
    do { Start-Sleep -Seconds 1; docker exec $Container pg_isready -U securedoc -d securedoc 2>$null | Out-Null } while ($LASTEXITCODE -ne 0)
    Start-Sleep -Seconds 2
    Initialize-LexaVaultDatabase -Jar $JarPath -DbUrl "jdbc:postgresql://localhost:${DbPort}/securedoc" -DbUser 'securedoc' `
        -DbPassword $pw -StateDir (Join-Path $runDir 'install') | Out-Null
    # Every configurable kind on, as in the benchmark.
    foreach ($kind in 'BANK_ROUTING_NUMBER', 'BANK_ACCOUNT_NUMBER', 'ITIN', 'EIN', 'DRIVERS_LICENSE_NUMBER', 'PASSPORT_NUMBER', 'SIGNATURE', 'FACE_PHOTO') {
        Invoke-Sql ("insert into tenant_redaction_catalog (tenant_id, kind_key, enabled, updated_at) values ('$tenantId', '$kind', true, now()) " +
            'on conflict (tenant_id, kind_key) do update set enabled = true') | Out-Null
    }
    if ($VisionOff) { Invoke-Sql 'update tenant_redaction_policies set vision_enabled = false' | Out-Null }

    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:$DbPort/securedoc"
    $env:SPRING_DATASOURCE_USERNAME = 'securedoc'
    $env:SPRING_DATASOURCE_PASSWORD = $pw
    $env:SERVER_PORT = "$AppPort"
    $env:SECUREDOC_AI_PROVIDER = 'ollama'
    $env:SECUREDOC_AI_OLLAMA_BASE_URL = $Ollama
    $env:SECUREDOC_AI_VISION_BASE_URL = $Ollama
    $env:SECUREDOC_AI_MODEL = $Model
    $env:SECUREDOC_WATCHED_FOLDER_ENABLED = 'false'
    $env:SECUREDOC_WATCHED_FOLDER_SCHEDULING_ENABLED = 'false'
    $env:SECUREDOC_DELIVERY_LOCAL_OUTPUT_PATH = (Join-Path $runDir 'export-folder')
    $env:SECUREDOC_PROCESSING_PIPELINE_MODE = 'staged'
    $env:SECUREDOC_PROCESSING_EXPORT_MODE = 'job'
    $env:SECUREDOC_STAGE_JOBS_VISION_WORKERS = "$VisionWorkers"
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
        if ($app.HasExited) { throw 'the server exited during startup' }
        try { $up = ((Invoke-RestMethod -Uri "$backend/actuator/health" -TimeoutSec 3).status -eq 'UP') } catch { }
    }
    if (-not $up) { throw 'server did not start' }

    $cases = New-Object System.Collections.ArrayList
    foreach ($sample in $samples) {
        $suffix = Get-Random -Minimum 100000 -Maximum 999999
        $id = (Invoke-Api POST '/api/v1/packages' @{ packageType = 'MANUAL_UPLOAD'
            businessIdentifiers = @{ contract_number = "880064$suffix"; member_number = "90064$suffix"; service_entity = '001' } }).packageId
        Send-Sample $id $sample
        $clock = [System.Diagnostics.Stopwatch]::StartNew()
        Invoke-Api POST "/api/v1/packages/$id/submit" | Out-Null
        do {
            Start-Sleep -Milliseconds 500
            $status = Invoke-Sql "select status from document_packages where id = '$id'"
        } while (@('COMPLETED', 'FAILED', 'PENDING_REVIEW', 'IN_REVIEW') -notcontains $status -and $clock.Elapsed.TotalSeconds -lt $TimeoutSeconds)
        $clock.Stop()
        $seconds = [math]::Round($clock.Elapsed.TotalSeconds, 1)
        [void]$cases.Add([pscustomobject]@{ sample = $sample.BaseName; status = $status; seconds = $seconds })
        Write-Host "$($sample.BaseName): $status in $seconds s"
    }
    $findings = Invoke-Sql ("select coalesce(json_agg(json_build_object('doc', replace(d.file_name, '.pdf', ''), 'page', f.page_number, 'type', f.entity_type, " +
        "'box', case when f.bbox_left is null then null else json_build_array(f.bbox_left, f.bbox_top, f.bbox_width, f.bbox_height) end, " +
        "'confidence', f.confidence, 'status', f.status, 'foundBy', f.found_by, 'models', f.ai_models)), '[]') from findings f join documents d on d.id = f.document_id")
    $routes = Invoke-Sql ("select coalesce(json_agg(json_build_object('doc', replace(d.file_name, '.pdf', ''), 'page', r.page_number, 'path', r.path, " +
        "'reasons', r.reasons, 'ocr', r.ocr_confidence)), '[]') from document_page_routes r join documents d on d.id = r.document_id")
    $logs = @(Get-ChildItem -LiteralPath $runDir -Filter '*.log' -Recurse) + @(Get-ChildItem -LiteralPath $runDir -Filter '*.jsonl' -Recurse)
    $visionMs = @($logs | ForEach-Object { Select-String -LiteralPath $_.FullName -Pattern 'VISION_PAGE_READ document.*durationMs=(\d+)' } |
        ForEach-Object { [int]$_.Matches[0].Groups[1].Value })
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText((Join-Path $outDir 'findings.json'), $findings, $utf8)
    [System.IO.File]::WriteAllText((Join-Path $outDir 'routes.json'), $routes, $utf8)
    [System.IO.File]::WriteAllText((Join-Path $outDir 'cases.json'), (ConvertTo-Json @($cases)), $utf8)
    [System.IO.File]::WriteAllText((Join-Path $outDir 'vision-ms.json'), $(if ($visionMs.Count) { ConvertTo-Json @($visionMs) } else { '[]' }), $utf8)
    Write-Host "Results: $outDir"
    node (Join-Path $here 'score.mjs') (Join-Path $Root 'public') $outDir
    if ($LASTEXITCODE -ne 0) { throw 'scoring failed' }
} finally {
    if ($app -and -not $app.HasExited) { Stop-Process -Id $app.Id -Force -ErrorAction SilentlyContinue }
    if ($started) { docker rm -f $Container | Out-Null }
    foreach ($name in 'SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME', 'SERVER_PORT', 'SECUREDOC_AI_PROVIDER',
            'SECUREDOC_AI_OLLAMA_BASE_URL', 'SECUREDOC_AI_VISION_BASE_URL', 'SECUREDOC_AI_MODEL', 'SECUREDOC_WATCHED_FOLDER_ENABLED',
            'SECUREDOC_WATCHED_FOLDER_SCHEDULING_ENABLED', 'SECUREDOC_DELIVERY_LOCAL_OUTPUT_PATH', 'SECUREDOC_STAGE_JOBS_VISION_WORKERS',
            'SECUREDOC_PROCESSING_PIPELINE_MODE', 'SECUREDOC_PROCESSING_EXPORT_MODE') {
        Remove-Item "Env:$name" -ErrorAction SilentlyContinue
    }
}
