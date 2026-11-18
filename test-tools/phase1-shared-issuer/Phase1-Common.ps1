#Requires -Version 5.1
# PHASE 1 TEST ENVIRONMENT ONLY. Shared constants and guards for the Phase 1 scripts.
# Dot-source this file; do not run it directly. Contains no passwords or tokens.

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$Phase1 = [ordered]@{
    ToolDir          = $PSScriptRoot
    RepoRoot         = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
    RunRoot          = 'C:\dev\lexavault\phase1-run'
    ProjectName      = 'lexavault-phase1'
    Label            = 'lexavault.phase=1'
    PostgresName     = 'lexavault-phase1-postgres'
    KeycloakName     = 'lexavault-phase1-keycloak'
    VolumeName       = 'lexavault-phase1-pgdata'
    DbName           = 'lexavault_phase1'
    DbUser           = 'phase1'
    DbPort           = 15432
    KeycloakPort     = 18181
    BackendPort      = 18080
    UiPort           = 15173
    Realm            = 'lexavault-phase1'
    OtherRealm       = 'lexavault-phase1-other'
    Issuer           = 'http://localhost:18181/realms/lexavault-phase1'
    Audience         = 'lexavault-phase1-api'
    ProviderId       = 'phase1-oidc'
    WebClientId      = 'lexavault-phase1-web'
    ChecksClientId   = 'lexavault-phase1-checks'
    NoAudClientId    = 'lexavault-phase1-noaud'
    OtherAudClientId = 'lexavault-phase1-otheraud'
    OtherClientId    = 'lexavault-phase1-other-checks'
    TenantA          = 'a1a1a1a1-0000-4000-8000-00000000000a'
    TenantB          = 'b2b2b2b2-0000-4000-8000-00000000000b'
    DemoTenant       = '00000000-0000-0000-0000-000000000001'
    Users            = @('alice.a', 'bob.b', 'carol.ab', 'dave.none')
    OtherUsers       = @('eve.other')
    # The normal containers. Phase 1 scripts only ever read their name and status.
    NormalContainers = @('securedoc-postgres', 'sdv-postgres', 'sdv-vault', 'lexa-keycloak', 'lexa-keycloak-db')
}
$Phase1.ComposeFile = Join-Path $Phase1.ToolDir 'compose.yml'
$Phase1.SecretsDir  = Join-Path $Phase1.RunRoot 'secrets'
$Phase1.EnvFile     = Join-Path $Phase1.SecretsDir 'phase1.env'
$Phase1.UsersFile   = Join-Path $Phase1.SecretsDir 'users.json'
$Phase1.PidDir      = Join-Path $Phase1.RunRoot 'run'
$Phase1.LogDir      = Join-Path $Phase1.RunRoot 'logs'
$Phase1.ResultsDir  = Join-Path $Phase1.RunRoot 'results'
$Phase1.JdbcUrl     = 'jdbc:postgresql://127.0.0.1:15432/lexavault_phase1'
$Phase1.BackendUrl  = 'http://localhost:18080'

function Assert-Phase1JdbcUrl([string]$Url) {
    if ($Url -notmatch '^jdbc:postgresql://127\.0\.0\.1:15432/lexavault_phase1$') {
        throw "Phase 1 refused: JDBC URL '$Url' is not the throwaway database (127.0.0.1:15432/lexavault_phase1)."
    }
}

function Test-Phase1Port([int]$Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $task = $client.ConnectAsync('127.0.0.1', $Port)
        return ($task.Wait(500) -and $client.Connected)
    } catch { return $false } finally { $client.Dispose() }
}

function Invoke-Phase1Compose {
    # Pass arguments as ONE array, e.g. Invoke-Phase1Compose @('up', '-d', 'keycloak').
    # (Loose arguments would let PowerShell read -d as -Debug and -v as -Verbose.)
    param([Parameter(Mandatory = $true)][string[]]$ComposeArgs)
    if ($Phase1.ProjectName -ne 'lexavault-phase1') { throw 'Phase 1 refused: unexpected compose project name.' }
    $envArgs = @()
    if (Test-Path $Phase1.EnvFile) { $envArgs = @('--env-file', $Phase1.EnvFile) }
    & docker compose @envArgs -p $Phase1.ProjectName -f $Phase1.ComposeFile @ComposeArgs
    if ($LASTEXITCODE -ne 0) { throw "docker compose $($ComposeArgs -join ' ') failed with exit code $LASTEXITCODE" }
}

function Invoke-Phase1Sql([string]$Sql) {
    # Runs inside the throwaway container over its local socket (no password on any command line).
    $guarded = "do `$g`$ begin if current_database() <> 'lexavault_phase1' then raise exception 'wrong database'; end if; end `$g`$; " + $Sql
    $out = $guarded | & docker exec -i $Phase1.PostgresName psql -U $Phase1.DbUser -d $Phase1.DbName -v ON_ERROR_STOP=1 -At -q -f -
    if ($LASTEXITCODE -ne 0) { throw "psql failed in $($Phase1.PostgresName)" }
    return $out
}

function Get-Phase1NormalContainerState {
    $rows = @()
    foreach ($name in $Phase1.NormalContainers) {
        $status = & docker ps -a --filter "name=^/$name`$" --format '{{.Status}}' 2>$null
        if (-not $status) { $status = '(not present)' }
        $rows += [pscustomobject]@{ Name = $name; Status = ($status | Select-Object -First 1) }
    }
    return $rows
}

function New-Phase1Secret([int]$Length = 32) {
    $chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'.ToCharArray()
    $bytes = New-Object byte[] $Length
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })
}

function Read-Phase1EnvFile {
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Phase1.EnvFile) {
        if ($line -match '^\s*([A-Z0-9_]+)=(.*)$') { $values[$Matches[1]] = $Matches[2] }
    }
    return $values
}

function Get-Phase1PidFile([string]$Name) { Join-Path $Phase1.PidDir "$Name.pid" }

function Sync-Phase1KeycloakClients {
    # The realm import ignores realms that already exist. This makes the running throwaway Keycloak match the
    # realm files for clients: creates missing clients, and updates redirect URIs, web origins and the
    # post-logout redirect of existing ones. The realm files stay the single source. Never prints a secret.
    $secrets = Read-Phase1EnvFile
    $kc = "http://localhost:$($Phase1.KeycloakPort)"
    $adminToken = (Invoke-RestMethod -Method Post -Uri "$kc/realms/master/protocol/openid-connect/token" `
        -ContentType 'application/x-www-form-urlencoded' `
        -Body @{ grant_type = 'password'; client_id = 'admin-cli'; username = 'phase1-admin'; password = $secrets['PHASE1_KC_ADMIN_PASSWORD'] }).access_token
    $headers = @{ Authorization = "Bearer $adminToken" }
    try {
        foreach ($realmFile in Get-ChildItem -LiteralPath (Join-Path $Phase1.ToolDir 'keycloak') -Filter '*.json') {
            $realmRep = Get-Content -LiteralPath $realmFile.FullName -Raw | ConvertFrom-Json
            foreach ($client in @($realmRep.clients)) {
                # Assign first, then wrap: Windows PowerShell 5.1 passes a JSON array from Invoke-RestMethod as ONE object.
                $haveRaw = Invoke-RestMethod -Uri "$kc/admin/realms/$($realmRep.realm)/clients?clientId=$($client.clientId)" -Headers $headers
                $have = @($haveRaw)
                if ($have.Count -eq 0) {
                    Invoke-RestMethod -Method Post -Uri "$kc/admin/realms/$($realmRep.realm)/clients" -Headers $headers `
                        -ContentType 'application/json' -Body ($client | ConvertTo-Json -Depth 10) | Out-Null
                    Write-Host "[phase1] Created missing Keycloak client $($client.clientId) in realm $($realmRep.realm)."
                    continue
                }
                # Only clients whose realm-file entry lists redirect URIs (the browser client) are compared.
                if ($client.PSObject.Properties.Name -notcontains 'redirectUris') { continue }
                $current = $have[0]
                function Get-Phase1Prop($obj, [string]$name) { if ($null -ne $obj -and $obj.PSObject.Properties.Name -contains $name) { $obj.$name } else { $null } }
                $wantRedirects = @(Get-Phase1Prop $client 'redirectUris') -join '|'
                $wantOrigins = @(Get-Phase1Prop $client 'webOrigins') -join '|'
                $wantLogout = [string](Get-Phase1Prop (Get-Phase1Prop $client 'attributes') 'post.logout.redirect.uris')
                $haveLogout = [string](Get-Phase1Prop (Get-Phase1Prop $current 'attributes') 'post.logout.redirect.uris')
                $haveRedirects = @(Get-Phase1Prop $current 'redirectUris') -join '|'
                $haveOrigins = @(Get-Phase1Prop $current 'webOrigins') -join '|'
                if ($haveRedirects -ne $wantRedirects -or $haveOrigins -ne $wantOrigins -or ($wantLogout -and $haveLogout -ne $wantLogout)) {
                    $current | Add-Member -NotePropertyName 'redirectUris' -NotePropertyValue @($client.redirectUris) -Force
                    $current | Add-Member -NotePropertyName 'webOrigins' -NotePropertyValue @(Get-Phase1Prop $client 'webOrigins') -Force
                    if ($wantLogout) { $current.attributes | Add-Member -NotePropertyName 'post.logout.redirect.uris' -NotePropertyValue $wantLogout -Force }
                    Invoke-RestMethod -Method Put -Uri "$kc/admin/realms/$($realmRep.realm)/clients/$($current.id)" -Headers $headers `
                        -ContentType 'application/json' -Body ($current | ConvertTo-Json -Depth 10) | Out-Null
                    Write-Host "[phase1] Updated redirect URIs and web origins of Keycloak client $($client.clientId) in realm $($realmRep.realm)."
                }
            }
        }
    } finally {
        $adminToken = $null; $headers = $null
    }
}

function Get-Phase1OwnedProcess([string]$Name) {
    # Returns the process only if it is still the one Phase 1 started (java/node with phase1-run on its command line).
    $pidFile = Get-Phase1PidFile $Name
    if (-not (Test-Path $pidFile)) { return $null }
    $procId = [int](Get-Content -LiteralPath $pidFile -TotalCount 1)
    $proc = Get-CimInstance Win32_Process -Filter "ProcessId = $procId" -ErrorAction SilentlyContinue
    if ($null -eq $proc) { return $null }
    if ($proc.Name -notin @('java.exe', 'node.exe')) { return $null }
    if ($proc.CommandLine -notlike '*phase1-run*' -and $proc.CommandLine -notlike '*phase1*') { return $null }
    return $proc
}
