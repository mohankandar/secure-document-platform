param(
    [string]$Model = ""
)

$ErrorActionPreference = "Stop"

if (-not $Model) {
    if ($env:SECUREDOC_AI_MODEL) {
        $Model = $env:SECUREDOC_AI_MODEL
    }
    elseif ($env:SECUREDOC_AI_OLLAMA_MODEL) {
        $Model = $env:SECUREDOC_AI_OLLAMA_MODEL
    }
    else {
        $Model = "qwen3.5:4b"
    }
}

Write-Host "=== LexaVault AI environment ===" -ForegroundColor Cyan

$cpu = Get-CimInstance Win32_Processor | Select-Object -First 1 Name, NumberOfCores, NumberOfLogicalProcessors
$mem = Get-CimInstance Win32_ComputerSystem | Select-Object @{n='TotalMemoryGB';e={[math]::Round($_.TotalPhysicalMemory / 1GB, 1)}}
$gpus = Get-CimInstance Win32_VideoController | Select-Object Name, @{n='AdapterRAMGB';e={if ($_.AdapterRAM) {[math]::Round($_.AdapterRAM / 1GB, 1)} else {$null}}}

$cpu | Format-List
$mem | Format-List
$gpus | Format-Table -AutoSize

if (Get-Command ollama -ErrorAction SilentlyContinue) {
    Write-Host ""
    & ollama --version
    Write-Host ""
    Write-Host "ollama ps:" -ForegroundColor Cyan
    & ollama ps
    Write-Host ""
    Write-Host "Model details: $Model" -ForegroundColor Cyan
    & ollama show $Model
}
else {
    Write-Host "ollama not found in PATH" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "This report contains machine/model capability information only; do not add customer document content." -ForegroundColor DarkGray
