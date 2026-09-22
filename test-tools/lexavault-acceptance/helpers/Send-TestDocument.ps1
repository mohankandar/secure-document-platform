#requires -Version 5.1
<#
.SYNOPSIS
    Uploads one PDF to a package's /documents endpoint as multipart/form-data.

.DESCRIPTION
    Reusable test-tooling helper. Windows PowerShell 5.1 does not support Invoke-WebRequest -Form,
    so this uses System.Net.Http.HttpClient + MultipartFormDataContent, which works on 5.1 and 7+.

    Sends the same LOCAL/demo identity headers as the rest of the harness. No secrets embedded.

.PARAMETER PackageId
    Disposable package id to attach the document to.

.PARAMETER FilePath
    Absolute path to the PDF fixture.

.PARAMETER Roles
    X-Actor-Roles header. Defaults to ADMIN.

.OUTPUTS
    PSCustomObject with the parsed JSON response (documentId, artifactId, status) when available.

.NOTES
    Env vars (defaults): SECUREDOC_BACKEND_URL, SECUREDOC_TENANT_ID, SECUREDOC_ACTOR_ID.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $PackageId,
    [Parameter(Mandatory = $true)][string] $FilePath,
    [string] $Roles = 'ADMIN'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not (Test-Path -LiteralPath $FilePath)) { throw "Send-TestDocument: file not found: $FilePath" }

$backend  = if ($env:SECUREDOC_BACKEND_URL) { $env:SECUREDOC_BACKEND_URL.TrimEnd('/') } else { 'http://localhost:8080' }
$tenantId = if ($env:SECUREDOC_TENANT_ID)   { $env:SECUREDOC_TENANT_ID }   else { '00000000-0000-0000-0000-000000000001' }
$actorId  = if ($env:SECUREDOC_ACTOR_ID)    { $env:SECUREDOC_ACTOR_ID }    else { '00000000-0000-0000-0000-000000000099' }

$uri = "$backend/api/v1/packages/$PackageId/documents"

Add-Type -AssemblyName System.Net.Http

$content = $null
$client = [System.Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds(120)
try {
    $client.DefaultRequestHeaders.Add('X-Tenant-Id', $tenantId)
    $client.DefaultRequestHeaders.Add('X-Actor-Id', $actorId)
    $client.DefaultRequestHeaders.Add('X-Actor-Roles', $Roles)
    $client.DefaultRequestHeaders.Add('X-Correlation-Id', [guid]::NewGuid().ToString())

    $content = [System.Net.Http.MultipartFormDataContent]::new()
    $bytes = [System.IO.File]::ReadAllBytes($FilePath)
    $fileContent = [System.Net.Http.ByteArrayContent]::new($bytes)
    $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new('application/pdf')
    $fileName = [System.IO.Path]::GetFileName($FilePath)
    # Field name must be "file" (matches @RequestParam("file") on the controller).
    $content.Add($fileContent, 'file', $fileName)

    $response = $client.PostAsync($uri, $content).GetAwaiter().GetResult()
    $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()

    if (-not $response.IsSuccessStatusCode) {
        throw "Document upload failed (HTTP $([int]$response.StatusCode)): $body"
    }

    if ([string]::IsNullOrWhiteSpace($body)) { return $null }
    try { return ($body | ConvertFrom-Json) } catch { return $body }
}
finally {
    if ($null -ne $content) { $content.Dispose() }
    $client.Dispose()
}
