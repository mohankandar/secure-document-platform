# Task 0049: the number of AI requests at once per AI server (SECUREDOC_AI_REQUESTS_AT_ONCE, default 2), shared by the
# start scripts. Dot-sourced. A script that starts Ollama itself gives it the same number (OLLAMA_NUM_PARALLEL); an
# Ollama that is already running is never restarted, and its own setting is named when it differs.

function Get-LexaVaultAiRequestsAtOnce {
    $text = if ($env:SECUREDOC_AI_REQUESTS_AT_ONCE) { $env:SECUREDOC_AI_REQUESTS_AT_ONCE } else { "2" }
    $number = 0
    if (-not [int]::TryParse($text, [ref] $number) -or $number -lt 1 -or $number -gt 64) {
        throw "SECUREDOC_AI_REQUESTS_AT_ONCE must be an integer between 1 and 64."
    }
    return $number
}

# Starts "ollama serve" with OLLAMA_NUM_PARALLEL set for that process only.
function Start-LexaVaultOllama {
    param([Parameter(Mandatory)] [int] $RequestsAtOnce)
    $before = $env:OLLAMA_NUM_PARALLEL
    $env:OLLAMA_NUM_PARALLEL = "$RequestsAtOnce"
    try {
        return Start-Process -FilePath "ollama" -ArgumentList "serve" -PassThru -WindowStyle Minimized
    }
    finally {
        $env:OLLAMA_NUM_PARALLEL = $before
    }
}

# The setting of the Ollama that is running: the last one its log names. $null when it cannot be told.
# 0 is Ollama's own automatic choice.
function Get-RunningOllamaParallel {
    param([string] $LogPath = $(if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA "Ollama\server.log" }))
    if (-not $LogPath -or -not (Test-Path $LogPath)) { return $null }
    $last = Select-String -Path $LogPath -Pattern 'OLLAMA_NUM_PARALLEL:(\d+)' | Select-Object -Last 1
    if (-not $last) { return $null }
    return [int] $last.Matches[0].Groups[1].Value
}

function Write-RunningOllamaParallel {
    param([Parameter(Mandatory)] [int] $RequestsAtOnce, $Running = (Get-RunningOllamaParallel))
    if ($null -eq $Running) {
        Write-Host "LexaVault sends $RequestsAtOnce AI requests at once. The running Ollama's own setting (OLLAMA_NUM_PARALLEL) could not be read; it was not changed." -ForegroundColor Yellow
    }
    elseif ($Running -ne $RequestsAtOnce) {
        $named = if ($Running -eq 0) { "automatic (not set)" } else { "$Running" }
        Write-Host "LexaVault sends $RequestsAtOnce AI requests at once, but the running Ollama has OLLAMA_NUM_PARALLEL $named. It was not restarted; set OLLAMA_NUM_PARALLEL=$RequestsAtOnce and restart Ollama yourself to match." -ForegroundColor Yellow
    }
    else {
        Write-Host "AI requests at once: $RequestsAtOnce (the running Ollama has the same)." -ForegroundColor Green
    }
}
