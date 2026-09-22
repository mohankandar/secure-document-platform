#requires -Version 5.1
<#
.SYNOPSIS
    Thin wrapper over Invoke-RestMethod that attaches LexaVault local/demo identity headers.

.DESCRIPTION
    Reusable test-tooling helper for driving the real application API in LOCAL security mode.

    LOCAL security mode (application-local.yml: securedoc.security.mode=LOCAL) intentionally trusts
    identity headers for local/demo. This helper does NOT weaken production security: it only sends
    the same headers the local UI already sends. No secrets are embedded; the actor/tenant ids are
    the committed local/demo constants and are configurable via environment variables.

.PARAMETER Method
    HTTP method (GET/POST/PUT/DELETE).

.PARAMETER Path
    API path beginning with '/api/...'. Combined with the backend base URL.

.PARAMETER Body
    Optional object serialized to JSON for POST/PUT.

.PARAMETER Roles
    X-Actor-Roles header value. Defaults to ADMIN for setup calls (all capabilities). The Playwright
    UI review workflow uses the browser default role separately.

.OUTPUTS
    Parsed response object from Invoke-RestMethod.

.NOTES
    Environment variables (with defaults):
      SECUREDOC_BACKEND_URL = http://localhost:8080
      SECUREDOC_TENANT_ID   = 00000000-0000-0000-0000-000000000001
      SECUREDOC_ACTOR_ID    = 00000000-0000-0000-0000-000000000099
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateSet('GET', 'POST', 'PUT', 'DELETE')][string] $Method,
    [Parameter(Mandatory = $true)][string] $Path,
    [object] $Body,
    [string] $Roles = 'ADMIN'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$backend  = if ($env:SECUREDOC_BACKEND_URL) { $env:SECUREDOC_BACKEND_URL.TrimEnd('/') } else { 'http://localhost:8080' }
$tenantId = if ($env:SECUREDOC_TENANT_ID)   { $env:SECUREDOC_TENANT_ID }   else { '00000000-0000-0000-0000-000000000001' }
$actorId  = if ($env:SECUREDOC_ACTOR_ID)    { $env:SECUREDOC_ACTOR_ID }    else { '00000000-0000-0000-0000-000000000099' }

$uri = "$backend$Path"
$headers = @{
    'X-Tenant-Id'     = $tenantId
    'X-Actor-Id'      = $actorId
    'X-Actor-Roles'   = $Roles
    'X-Correlation-Id' = [guid]::NewGuid().ToString()
}

$params = @{
    Method  = $Method
    Uri     = $uri
    Headers = $headers
}

if ($null -ne $Body) {
    $params['ContentType'] = 'application/json'
    $params['Body'] = ($Body | ConvertTo-Json -Depth 12 -Compress)
}

try {
    return Invoke-RestMethod @params
}
catch {
    $detail = $_.Exception.Message
    $resp = $_.Exception.Response
    if ($null -ne $resp) {
        try {
            $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
            $body = $reader.ReadToEnd()
            if (-not [string]::IsNullOrWhiteSpace($body)) { $detail = $body }
        } catch { }
    }
    throw "API $Method $uri failed: $detail"
}
