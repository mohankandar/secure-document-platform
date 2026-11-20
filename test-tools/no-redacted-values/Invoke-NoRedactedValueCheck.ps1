#requires -Version 5.1
<#
.SYNOPSIS
    Task 0015, A6 end to end: a known fake value never appears in the database, the app logs or
    the audit export, in full, as digits only, or as its last 4.

.DESCRIPTION
    1. Starts a THROWAWAY Postgres container (lexavault-0015-pg, 127.0.0.1:55415). It never
       touches securedoc-postgres or any other container named in CLAUDE.md.
    2. Starts a mock Ollama whose answer contains the fake values (Start-FakeValueOllama.ps1).
    3. Starts the built backend jar (LOCAL profile) against that database. Liquibase runs every
       migration, including 039. All runtime folders live in the run folder.
    4. pg_dump #1 (before any processing).
    5. Creates a package, uploads a PDF holding the fake values, submits it, waits for the result.
    6. pg_dump #2, audit export (JSON and CSV), and collects the app log files.
    7. Searches them. For the database, only matches that are new since dump #1 count.
    8. Stops the app and the mock, and removes only the throwaway container.

    The container password is generated per run and never printed. Evidence stays in the run
    folder (it holds fake test values only).

.PARAMETER JarPath
    The backend jar to test. Build first: .\Build-LexaVault-All.ps1 or cd backend; mvn clean install.

.PARAMETER RunRoot
    Where to put the run folder. Default: %TEMP%\lexavault-0015.

.OUTPUTS
    Writes summary.json in the run folder and exits 0 only when there are no matches.
#>
[CmdletBinding()]
param(
    [string] $JarPath,
    [string] $RunRoot = (Join-Path $env:TEMP 'lexavault-0015'),
    [int] $DbPort = 55415,
    [int] $AppPort = 18015,
    [int] $OllamaPort = 11499,
    [string] $Model = 'qwen3.5:9b',
    [int] $ProcessingTimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$here = $PSScriptRoot
$repoRoot = (Resolve-Path (Join-Path $here '..\..')).Path
if (-not $JarPath) { $JarPath = Join-Path $repoRoot 'backend\securedoc-api\target\securedoc-api-0.1.0-SNAPSHOT.jar' }
if (-not (Test-Path -LiteralPath $JarPath)) { throw "Backend jar not found: $JarPath (build it first)." }

$Container = 'lexavault-0015-pg'
$Protected = @('securedoc-postgres', 'sdv-postgres', 'sdv-vault', 'lexa-keycloak', 'lexa-keycloak-db')
if ($Protected -contains $Container) { throw "Refusing to use a protected container name." }

$tenantId = '00000000-0000-0000-0000-000000000001'
$actorId = '00000000-0000-0000-0000-000000000099'
$backend = "http://localhost:$AppPort"
# Intake needs DOCUMENT_PROCESSOR; the audit export needs AUDIT_VIEW (TENANT_IT_ADMIN).
$roles = 'DOCUMENT_PROCESSOR,TENANT_IT_ADMIN'

$runDir = Join-Path $RunRoot (Get-Date -Format 'yyyyMMdd-HHmmss')
$logDir = Join-Path $runDir 'logs'
New-Item -ItemType Directory -Force -Path $runDir, $logDir | Out-Null
Write-Host "Run folder: $runDir"

# Fake values (test values only) and the forms searched for.
$values = @(
    @{ name = 'SSN';  written = '412-55-7531'; alt = @() },
    @{ name = 'CARD'; written = '4000 0566 5566 5556'; alt = @('4000-0566-5566-5556') },
    @{ name = 'DOB';  written = '03/14/1962'; alt = @() }
)
$needles = New-Object System.Collections.ArrayList
foreach ($v in $values) {
    $digits = ($v.written -replace '[^0-9]', '')
    [void]$needles.Add([pscustomobject]@{ value = $v.name; form = 'full'; text = $v.written; regex = [regex]::Escape($v.written) })
    foreach ($a in $v.alt) {
        [void]$needles.Add([pscustomobject]@{ value = $v.name; form = 'full'; text = $a; regex = [regex]::Escape($a) })
    }
    # Digit forms are matched only when not part of a longer hex/digit run, so a UUID or a
    # timestamp's fraction that happens to contain the same four digits is not counted.
    [void]$needles.Add([pscustomobject]@{ value = $v.name; form = 'digits'; text = $digits; regex = "(?<![0-9A-Fa-f])$digits(?![0-9A-Fa-f])" })
    $last4 = $digits.Substring($digits.Length - 4)
    [void]$needles.Add([pscustomobject]@{ value = $v.name; form = 'last4'; text = $last4; regex = "(?<![0-9A-Fa-f])$last4(?![0-9A-Fa-f])" })
}

function Get-MatchCounts([string] $text) {
    $counts = @{}
    foreach ($n in $needles) { $counts["$($n.value)/$($n.form)/$($n.text)"] = ([regex]::Matches($text, $n.regex)).Count }
    return $counts
}

function Get-MatchLines([string] $text, [string] $regex, [int] $max = 5) {
    $lines = @($text -split "`n" | Where-Object { $_ -match $regex } | Select-Object -First $max)
    return @($lines | ForEach-Object { $s = $_.Trim(); if ($s.Length -gt 240) { $s.Substring(0, 240) + '...' } else { $s } })
}

function Invoke-Api([string] $Method, [string] $Path, $Body = $null, [string] $OutFile = $null) {
    $headers = @{
        'X-Tenant-Id' = $tenantId; 'X-Actor-Id' = $actorId; 'X-Actor-Roles' = $roles
        'X-Correlation-Id' = [guid]::NewGuid().ToString()
    }
    $req = @{ Method = $Method; Uri = "$backend$Path"; Headers = $headers; UseBasicParsing = $true; TimeoutSec = 60 }
    if ($null -ne $Body) { $req.Body = ($Body | ConvertTo-Json -Depth 8); $req.ContentType = 'application/json' }
    if ($OutFile) { Invoke-WebRequest @req -OutFile $OutFile | Out-Null; return $null }
    return Invoke-RestMethod @req
}

function Get-DbDump([string] $name) {
    $inside = "/tmp/$name.sql"
    docker exec $Container pg_dump -U securedoc -d securedoc -f $inside | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "pg_dump failed" }
    $local = Join-Path $runDir "$name.sql"
    docker cp "${Container}:$inside" $local | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "docker cp failed" }
    return $local
}

$appProcess = $null
$ollamaProcess = $null
$containerStarted = $false
$exitCode = 1
try {
    # --- 1. throwaway database ---------------------------------------------------------------
    $existing = @(docker ps -a --filter "name=^/$Container$" --format '{{.Names}}')
    if ($existing.Count -gt 0) { throw "Container $Container already exists; remove it first (docker rm -f $Container)." }
    $dbPassword = [guid]::NewGuid().ToString('N')
    docker run -d --name $Container -e POSTGRES_DB=securedoc -e POSTGRES_USER=securedoc `
        -e "POSTGRES_PASSWORD=$dbPassword" -p "127.0.0.1:${DbPort}:5432" postgres:16-alpine | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "docker run failed" }
    $containerStarted = $true
    $deadline = (Get-Date).AddSeconds(60)
    do {
        Start-Sleep -Seconds 1
        docker exec $Container pg_isready -U securedoc -d securedoc 2>$null | Out-Null
    } while ($LASTEXITCODE -ne 0 -and (Get-Date) -lt $deadline)
    if ($LASTEXITCODE -ne 0) { throw "throwaway Postgres did not become ready" }
    Start-Sleep -Seconds 2
    Write-Host "Throwaway Postgres ready on 127.0.0.1:$DbPort"

    # --- 2. mock Ollama with the fake values in its answer ----------------------------------
    $ollamaProcess = Start-Process -FilePath 'powershell.exe' -PassThru -WindowStyle Hidden `
        -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $here 'Start-FakeValueOllama.ps1'),
            '-Port', $OllamaPort, '-Model', $Model) `
        -RedirectStandardOutput (Join-Path $runDir 'mock-ollama.out.log') -RedirectStandardError (Join-Path $runDir 'mock-ollama.err.log')
    $deadline = (Get-Date).AddSeconds(30)
    $ollamaUp = $false
    while (-not $ollamaUp -and (Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 500
        try { Invoke-RestMethod -Uri "http://127.0.0.1:$OllamaPort/api/tags" -TimeoutSec 2 | Out-Null; $ollamaUp = $true } catch { }
    }
    if (-not $ollamaUp) { throw "mock Ollama did not start" }
    Write-Host "Mock Ollama up on 127.0.0.1:$OllamaPort"

    # --- test PDF ----------------------------------------------------------------------------
    $m2 = Join-Path $env:USERPROFILE '.m2\repository'
    $cp = @(
        "$m2\org\apache\pdfbox\pdfbox\3.0.2\pdfbox-3.0.2.jar",
        "$m2\org\apache\pdfbox\fontbox\3.0.2\fontbox-3.0.2.jar",
        "$m2\org\apache\pdfbox\pdfbox-io\3.0.2\pdfbox-io-3.0.2.jar",
        (Get-ChildItem "$m2\commons-logging\commons-logging" -Recurse -Filter 'commons-logging-*.jar' | Select-Object -First 1).FullName
    ) -join ';'
    $pdf = Join-Path $runDir 'fake-values.pdf'
    & java -cp $cp (Join-Path $here 'MakeFakeValuePdf.java') $pdf
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $pdf)) { throw "test PDF was not written" }

    # --- 3. backend against the throwaway database ------------------------------------------
    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:$DbPort/securedoc"
    $env:SPRING_DATASOURCE_USERNAME = 'securedoc'
    $env:SPRING_DATASOURCE_PASSWORD = $dbPassword
    $env:SERVER_PORT = "$AppPort"
    $env:SECUREDOC_AI_PROVIDER = 'ollama'
    $env:SECUREDOC_AI_OLLAMA_BASE_URL = "http://localhost:$OllamaPort"
    $env:SECUREDOC_AI_MODEL = $Model
    $env:SECUREDOC_AI_OLLAMA_PRELOAD_ENABLED = 'false'
    $env:SECUREDOC_WATCHED_FOLDER_ENABLED = 'false'
    $env:SECUREDOC_WATCHED_FOLDER_SCHEDULING_ENABLED = 'false'
    $env:SECUREDOC_LOGGING_CONFIG = 'classpath:logback-json-rolling.xml'
    $env:SECUREDOC_LOG_DIR = $logDir
    $env:SECUREDOC_PRODUCT_TELEMETRY_OUTPUT_PATH = (Join-Path $logDir 'product-telemetry.jsonl')
    $env:SECUREDOC_DELIVERY_LOCAL_OUTPUT_PATH = (Join-Path $runDir 'securedoc-output')
    try {
        $appProcess = Start-Process -FilePath 'java' -PassThru -WindowStyle Hidden -WorkingDirectory $runDir `
            -ArgumentList @('-jar', $JarPath, '--spring.profiles.active=local',
                "--securedoc.storage.local.root-path=$(Join-Path $runDir 'securedoc-storage')") `
            -RedirectStandardOutput (Join-Path $logDir 'backend.out.log') -RedirectStandardError (Join-Path $logDir 'backend.err.log')
    } finally {
        Remove-Item Env:SPRING_DATASOURCE_PASSWORD -ErrorAction SilentlyContinue
    }
    $deadline = (Get-Date).AddSeconds(180)
    $appUp = $false
    while (-not $appUp -and (Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        if ($appProcess.HasExited) { throw "backend exited during startup (see $logDir\backend.out.log)" }
        try { $h = Invoke-RestMethod -Uri "$backend/actuator/health" -TimeoutSec 3; $appUp = ($h.status -eq 'UP') } catch { }
    }
    if (-not $appUp) { throw "backend did not become healthy" }
    $migration039 = (docker exec $Container psql -U securedoc -d securedoc -tAc "select count(*) from databasechangelog where id like '039-%'").Trim()
    $snippetColumn = (docker exec $Container psql -U securedoc -d securedoc -tAc "select count(*) from information_schema.columns where table_name='findings' and column_name='snippet'").Trim()
    Write-Host "Backend up on $backend; migration 039 changeSets applied: $migration039; findings.snippet columns: $snippetColumn"

    # --- 4. dump before any processing -------------------------------------------------------
    $dump1 = Get-DbDump 'dump-before'

    # --- 5. process the PDF through the normal path ------------------------------------------
    $suffix = (Get-Random -Minimum 100000 -Maximum 999999)
    $createBody = @{
        packageType = 'MANUAL_UPLOAD'
        businessIdentifiers = @{ contract_number = "880015$suffix"; member_number = "90015$suffix"; service_entity = '001' }
        metadata = @{ source = 'task-0015-no-redacted-values' }
    }
    $created = Invoke-Api POST '/api/v1/packages' $createBody
    $packageId = $created.packageId
    if (-not $packageId) { throw "package was not created" }

    Add-Type -AssemblyName System.Net.Http
    $client = [System.Net.Http.HttpClient]::new()
    try {
        $client.DefaultRequestHeaders.Add('X-Tenant-Id', $tenantId)
        $client.DefaultRequestHeaders.Add('X-Actor-Id', $actorId)
        $client.DefaultRequestHeaders.Add('X-Actor-Roles', $roles)
        $content = [System.Net.Http.MultipartFormDataContent]::new()
        $file = [System.Net.Http.ByteArrayContent]::new([System.IO.File]::ReadAllBytes($pdf))
        $file.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new('application/pdf')
        $content.Add($file, 'file', 'fake-values.pdf')
        $response = $client.PostAsync("$backend/api/v1/packages/$packageId/documents", $content).GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) { throw "upload failed: HTTP $([int]$response.StatusCode)" }
    } finally { $client.Dispose() }
    Invoke-Api POST "/api/v1/packages/$packageId/submit" | Out-Null

    $terminal = @('COMPLETED', 'PENDING_REVIEW', 'IN_REVIEW', 'PENDING_FINAL_APPROVAL', 'APPROVED', 'VAULTED', 'REJECTED', 'FAILED', 'CANCELLED')
    $deadline = (Get-Date).AddSeconds($ProcessingTimeoutSeconds)
    $status = $null
    do {
        Start-Sleep -Seconds 2
        $status = (Invoke-Api GET "/api/v1/packages/$packageId").status
    } while (-not ($terminal -contains $status) -and (Get-Date) -lt $deadline)
    Write-Host "Package $packageId finished processing with status $status"
    Start-Sleep -Seconds 3

    $findingRows = (docker exec $Container psql -U securedoc -d securedoc -tAc "select entity_type || ':' || status || ':' || detector_name from findings order by entity_type").Trim()
    Write-Host "Findings saved: $($findingRows -replace "`n", '; ')"

    # --- 6. dump after, audit export, logs ---------------------------------------------------
    $dump2 = Get-DbDump 'dump-after'
    $auditJson = Join-Path $runDir 'audit-export.json'
    $auditCsv = Join-Path $runDir 'audit-export.csv'
    Invoke-Api GET '/api/v1/audit-records/export?format=json&includeRoutineActivity=true' -OutFile $auditJson
    Invoke-Api GET '/api/v1/audit-records/export?format=csv&includeRoutineActivity=true' -OutFile $auditCsv

    # Stop the app so every log line is flushed before the search.
    if ($appProcess -and -not $appProcess.HasExited) { Stop-Process -Id $appProcess.Id -Force; $appProcess.WaitForExit(15000) | Out-Null }

    # --- 7. search ---------------------------------------------------------------------------
    $results = New-Object System.Collections.ArrayList
    $before = Get-MatchCounts ([System.IO.File]::ReadAllText($dump1))
    $afterText = [System.IO.File]::ReadAllText($dump2)
    $after = Get-MatchCounts $afterText
    foreach ($n in $needles) {
        $key = "$($n.value)/$($n.form)/$($n.text)"
        $new = [Math]::Max(0, $after[$key] - $before[$key])
        [void]$results.Add([pscustomobject]@{ target = 'database (new since dump 1)'; needle = $key; before = $before[$key]; after = $after[$key]; matches = $new
            lines = $(if ($new -gt 0) { Get-MatchLines $afterText $n.regex } else { @() }) })
    }
    $targets = @()
    $targets += Get-ChildItem -LiteralPath $logDir -File | ForEach-Object { @{ name = "log: $($_.Name)"; path = $_.FullName } }
    $targets += @{ name = 'audit export (JSON)'; path = $auditJson }
    $targets += @{ name = 'audit export (CSV)'; path = $auditCsv }
    foreach ($t in $targets) {
        $text = [System.IO.File]::ReadAllText($t.path)
        $counts = Get-MatchCounts $text
        foreach ($n in $needles) {
            $key = "$($n.value)/$($n.form)/$($n.text)"
            [void]$results.Add([pscustomobject]@{ target = $t.name; needle = $key; before = $null; after = $counts[$key]; matches = $counts[$key]
                lines = $(if ($counts[$key] -gt 0) { Get-MatchLines $text $n.regex } else { @() }) })
        }
    }
    $auditCount = @((Get-Content -Raw $auditJson | ConvertFrom-Json)).Count
    $totalMatches = ($results | Measure-Object -Property matches -Sum).Sum
    $summary = [pscustomobject]@{
        packageId = $packageId; packageStatus = $status; findings = $findingRows
        migration039ChangeSets = $migration039; findingsSnippetColumns = $snippetColumn
        auditRecordsExported = $auditCount
        targetsSearched = @('database dump (diff)') + @($targets | ForEach-Object { $_.name })
        totalMatches = $totalMatches
        results = $results
    }
    $summary | ConvertTo-Json -Depth 6 | Set-Content -Encoding utf8 (Join-Path $runDir 'summary.json')

    Write-Host ""
    Write-Host "Searched for: full value, digits only, last 4 (SSN, card, date of birth)"
    $results | Group-Object target | ForEach-Object {
        $sum = ($_.Group | Measure-Object -Property matches -Sum).Sum
        Write-Host ("  {0,-40} matches: {1}" -f $_.Name, $sum)
    }
    foreach ($r in $results | Where-Object { $_.matches -gt 0 }) {
        Write-Host "  MATCH $($r.target) $($r.needle) x$($r.matches)"
        foreach ($l in $r.lines) { Write-Host "      $l" }
    }
    Write-Host "Audit records exported: $auditCount. Total matches: $totalMatches"
    if ($totalMatches -eq 0 -and $snippetColumn -eq '0' -and $migration039 -eq '2' -and $findingRows) {
        Write-Host "PASS"
        $exitCode = 0
    } else {
        Write-Host "FAIL"
    }
}
finally {
    if ($appProcess -and -not $appProcess.HasExited) { Stop-Process -Id $appProcess.Id -Force -ErrorAction SilentlyContinue }
    if ($ollamaProcess -and -not $ollamaProcess.HasExited) { Stop-Process -Id $ollamaProcess.Id -Force -ErrorAction SilentlyContinue }
    if ($containerStarted) {
        docker rm -f $Container | Out-Null
        Write-Host "Removed throwaway container $Container"
    }
    foreach ($name in 'SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME', 'SERVER_PORT', 'SECUREDOC_AI_PROVIDER',
            'SECUREDOC_AI_OLLAMA_BASE_URL', 'SECUREDOC_AI_MODEL', 'SECUREDOC_AI_OLLAMA_PRELOAD_ENABLED',
            'SECUREDOC_WATCHED_FOLDER_ENABLED', 'SECUREDOC_WATCHED_FOLDER_SCHEDULING_ENABLED', 'SECUREDOC_LOGGING_CONFIG',
            'SECUREDOC_LOG_DIR', 'SECUREDOC_PRODUCT_TELEMETRY_OUTPUT_PATH', 'SECUREDOC_DELIVERY_LOCAL_OUTPUT_PATH') {
        Remove-Item "Env:$name" -ErrorAction SilentlyContinue
    }
}
exit $exitCode
