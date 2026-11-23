<#
.SYNOPSIS
  Task 0033 vision benchmark: build the harness, then generate samples or run the benchmark.

.EXAMPLE
  .\Run-VisionBenchmark.ps1 -Generate                 # part A: make the synthetic samples
  .\Run-VisionBenchmark.ps1 -Set public               # part A: run the benchmark
  .\Run-VisionBenchmark.ps1 -Set customer             # part B: same run on the owner's approved samples

.NOTES
  Needs: Java 21, Maven, Tesseract on PATH, a local Ollama with the models pulled, and the product modules
  installed in the local Maven repository (cd backend; mvn install -DskipTests).
  Samples and results stay under -Root, which must be outside any git repository.
#>
param(
    [string]$Root = "C:\dev\benchmark-samples",
    [ValidateSet("public", "customer")]
    [string]$Set = "public",
    [switch]$Generate,
    [string]$TextModel = "qwen3.5:9b",
    [string]$Models = "qwen2.5vl:7b,qwen3-vl:8b-instruct,minicpm-v:8b",
    [double]$Threshold = 0.75,
    [string]$Ollama = "http://localhost:11434"
)
$ErrorActionPreference = "Stop"
Push-Location $PSScriptRoot
try {
    mvn -q clean package
    if ($LASTEXITCODE -ne 0) { throw "Harness build or tests failed." }
    $cp = "target/classes;target/lib/*"
    if ($Generate) {
        java -cp $cp com.securedoc.benchmark.BenchmarkMain generate --root $Root
    } else {
        java -cp $cp com.securedoc.benchmark.BenchmarkMain run --root $Root --set $Set --text-model $TextModel `
            --models $Models --threshold $Threshold --ollama $Ollama
    }
    if ($LASTEXITCODE -ne 0) { throw "Benchmark stopped (exit $LASTEXITCODE)." }
} finally {
    Pop-Location
}
