param(
    [Parameter(Mandatory=$true)]
    [string]$LogPath,
    [string]$CsvPath
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $LogPath)) {
    throw "Log file not found: $LogPath"
}


function Get-Value {
    param([hashtable]$Values, [string]$Key, $Default)
    if ($Values.ContainsKey($Key) -and $null -ne $Values[$Key] -and $Values[$Key] -ne '') {
        return $Values[$Key]
    }
    return $Default
}

function Parse-KeyValues {
    param([string]$Line)
    $values = @{}
    foreach ($match in [regex]::Matches($Line, '(?<key>[A-Za-z][A-Za-z0-9]*)=(?<value>[^\s]+)')) {
        $values[$match.Groups['key'].Value] = $match.Groups['value'].Value.TrimEnd(',')
    }
    return $values
}

$rows = @()
Get-Content -LiteralPath $LogPath | ForEach-Object {
    if ($_ -notmatch 'AI_ANALYSIS_COMPLETED provider=ollama') { return }
    $v = Parse-KeyValues $_
    $rows += [pscustomobject]@{
        Model = $v.model
        Pages = [int](Get-Value $v "pages" 0)
        Batches = [int](Get-Value $v "batches" 0)
        ProviderCalls = [int](Get-Value $v "providerCalls" 0)
        Findings = [int](Get-Value $v "findings" 0)
        Retries = [int](Get-Value $v "pageRetries" 0)
        DurationMs = [long](Get-Value $v "durationMs" 0)
        LoadMs = [long](Get-Value $v "loadMs" 0)
        PromptTokens = [long](Get-Value $v "promptTokens" 0)
        PromptEvalMs = [long](Get-Value $v "promptEvalMs" 0)
        OutputTokens = [long](Get-Value $v "outputTokens" 0)
        OutputEvalMs = [long](Get-Value $v "outputEvalMs" 0)
        OutputTokensPerSecond = [double](Get-Value $v "outputTokensPerSecond" 0)
    }
}

if ($rows.Count -eq 0) {
    throw "No Requirement-5 AI_ANALYSIS_COMPLETED lines were found in $LogPath"
}

Write-Host ""
Write-Host "=== LexaVault AI benchmark summary ===" -ForegroundColor Cyan
$rows | Format-Table -AutoSize

$sumPages = ($rows | Measure-Object Pages -Sum).Sum
$sumCalls = ($rows | Measure-Object ProviderCalls -Sum).Sum
$avgDuration = [math]::Round(($rows | Measure-Object DurationMs -Average).Average, 1)
$avgTps = [math]::Round(($rows | Where-Object OutputTokensPerSecond -gt 0 | Measure-Object OutputTokensPerSecond -Average).Average, 1)
$callReductionRatio = if ($sumPages -gt 0) { [math]::Round($sumCalls / $sumPages, 3) } else { 0 }

Write-Host "Runs: $($rows.Count)"
Write-Host "Total pages: $sumPages"
Write-Host "Total Ollama calls: $sumCalls"
Write-Host "Calls/page: $callReductionRatio"
Write-Host "Average AI duration: $avgDuration ms"
Write-Host "Average output generation rate: $avgTps tokens/sec"
Write-Host ""
Write-Host "Compare speed only after confirming the same expected findings/false-positive guardrails." -ForegroundColor Yellow

if ($CsvPath) {
    $rows | Export-Csv -LiteralPath $CsvPath -NoTypeInformation
    Write-Host "CSV written: $CsvPath" -ForegroundColor Green
}
