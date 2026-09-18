param(
    [string]$OutputPath = "",
    [string]$Model = ""
)

$ErrorActionPreference = "Stop"

if (-not $Model) {
    if ($env:SECUREDOC_AI_MODEL) { $Model = $env:SECUREDOC_AI_MODEL }
    elseif ($env:SECUREDOC_AI_OLLAMA_MODEL) { $Model = $env:SECUREDOC_AI_OLLAMA_MODEL }
    else { $Model = "qwen3.5:4b" }
}

function Try-CommandOutput {
    param([scriptblock]$Command)
    try { return (& $Command 2>&1 | Out-String).Trim() }
    catch { return "Unavailable: $($_.Exception.Message)" }
}

$cpu = Get-CimInstance Win32_Processor | Select-Object -First 1 Name, NumberOfCores, NumberOfLogicalProcessors
$computer = Get-CimInstance Win32_ComputerSystem
$os = Get-CimInstance Win32_OperatingSystem
$gpus = @(Get-CimInstance Win32_VideoController | Select-Object Name, @{n='AdapterRAMGB';e={if ($_.AdapterRAM) {[math]::Round($_.AdapterRAM / 1GB, 1)} else {$null}}})

$environment = [ordered]@{
    CapturedAt = (Get-Date).ToString("o")
    ComputerName = $env:COMPUTERNAME
    OsCaption = $os.Caption
    OsVersion = $os.Version
    CpuName = $cpu.Name
    CpuCores = $cpu.NumberOfCores
    CpuLogicalProcessors = $cpu.NumberOfLogicalProcessors
    TotalMemoryGB = [math]::Round($computer.TotalPhysicalMemory / 1GB, 1)
    JavaVersion = Try-CommandOutput { java -version }
    OllamaVersion = if (Get-Command ollama -ErrorAction SilentlyContinue) { Try-CommandOutput { ollama --version } } else { "ollama not found" }
    OllamaModel = $Model
    OllamaPs = if (Get-Command ollama -ErrorAction SilentlyContinue) { Try-CommandOutput { ollama ps } } else { "ollama not found" }
    NvidiaSmi = if (Get-Command nvidia-smi -ErrorAction SilentlyContinue) { Try-CommandOutput { nvidia-smi --query-gpu=name,driver_version,memory.total --format=csv,noheader } } else { "nvidia-smi not found" }
    Gpus = $gpus
}

$json = $environment | ConvertTo-Json -Depth 6
if ($OutputPath) {
    $parent = Split-Path -Parent $OutputPath
    if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    $json | Set-Content -LiteralPath $OutputPath -Encoding UTF8
    Write-Host "Performance environment written: $OutputPath" -ForegroundColor Green
}
else {
    $json
}

Write-Host ""
Write-Host "Contains machine/model capability metadata only. Do not add customer document content." -ForegroundColor DarkGray
