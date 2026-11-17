#Requires -Version 5.1
<#
.SYNOPSIS
  PHASE 1 TEST ENVIRONMENT ONLY. Scripted checks for design section 11 points 1 and 2 and the
  refusal checks (plan sections 5 and 6), against the Phase 1 backend on port 18080.

.DESCRIPTION
  Gets tokens from the throwaway Keycloak with the password grant on the test-only client and
  calls the API. Tokens are held in memory only: never printed, logged or written to disk.
  Results record status codes and PASS/FAIL, written to C:\dev\lexavault\phase1-run\results.
  The two refusal toggles (entity B's login-system record, entity B's status) are always restored.
#>
[CmdletBinding()]
param(
    [int]$CacheWaitSeconds = 31
)

. (Join-Path $PSScriptRoot 'Phase1-Common.ps1')
Add-Type -AssemblyName System.Net.Http

$A = $Phase1.TenantA
$B = $Phase1.TenantB
$results = New-Object System.Collections.Generic.List[object]
$http = New-Object System.Net.Http.HttpClient
$http.Timeout = [TimeSpan]::FromSeconds(60)
$userPasswords = Get-Content -LiteralPath $Phase1.UsersFile -Raw | ConvertFrom-Json

function Get-Token([string]$Realm, [string]$ClientId, [string]$User) {
    try {
        $r = Invoke-RestMethod -Method Post -Uri "http://localhost:$($Phase1.KeycloakPort)/realms/$Realm/protocol/openid-connect/token" `
            -ContentType 'application/x-www-form-urlencoded' `
            -Body @{ grant_type = 'password'; client_id = $ClientId; username = $User; password = $userPasswords.$User; scope = 'openid' }
        return $r.access_token
    } catch {
        $code = 0; try { $code = [int]$_.Exception.Response.StatusCode } catch { }
        throw "Could not get a token for $User from realm $Realm / client $ClientId (HTTP $code)."
    }
}

function Invoke-Api {
    param([string]$Method = 'GET', [string]$Path, [string]$Token, [hashtable]$Headers = @{}, $Body = $null)
    $req = New-Object System.Net.Http.HttpRequestMessage ([System.Net.Http.HttpMethod]::new($Method)), ($Phase1.BackendUrl + $Path)
    if ($Token) { $req.Headers.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue 'Bearer', $Token }
    foreach ($k in $Headers.Keys) { [void]$req.Headers.TryAddWithoutValidation($k, [string]$Headers[$k]) }
    if ($null -ne $Body) {
        $req.Content = New-Object System.Net.Http.StringContent (($Body | ConvertTo-Json -Depth 10 -Compress), [Text.Encoding]::UTF8, 'application/json')
    }
    $resp = $http.SendAsync($req).GetAwaiter().GetResult()
    $status = [int]$resp.StatusCode
    $text = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $json = $null
    if ($text) { try { $json = $text | ConvertFrom-Json } catch { } }
    $req.Dispose(); $resp.Dispose()
    return [pscustomobject]@{ Status = $status; Json = $json }
}

function Sel([string]$TenantId) { @{ 'X-Selected-Tenant-Id' = $TenantId } }

function Add-Check([string]$Id, [string]$Name, [string]$Expected, [string]$Actual, [bool]$Pass) {
    $results.Add([pscustomobject]@{ Id = $Id; Check = $Name; Expected = $Expected; Actual = $Actual; Result = $(if ($Pass) { 'PASS' } else { 'FAIL' }) })
    $mark = if ($Pass) { 'PASS' } else { 'FAIL' }
    Write-Host ("[{0}] {1,-6} {2}  (expected: {3}; actual: {4})" -f $mark, $Id, $Name, $Expected, $Actual)
}

function Format-Tenants($resp) {
    if ($resp.Status -ne 200) { return "HTTP $($resp.Status)" }
    $items = @($resp.Json)
    if ($items.Count -eq 0) { return 'HTTP 200 []' }
    $parts = foreach ($t in ($items | Sort-Object slug)) { "$($t.slug){$((@($t.roles) | Sort-Object) -join ',')}" }
    return "HTTP 200 [" + ($parts -join '; ') + "]"
}
function Format-Me($resp) {
    if ($resp.Status -ne 200) { return "HTTP $($resp.Status)" }
    return "HTTP 200 $($resp.Json.tenantSlug){$((@($resp.Json.roles) | Sort-Object) -join ',')}"
}
function Get-PackageIds($resp) {
    if ($resp.Status -ne 200) { return @() }
    return @(@($resp.Json) | ForEach-Object { $_.packageId })
}

function Check-Tenants([string]$Id, [string]$Name, [string]$Token, [string]$Expected) {
    $r = Invoke-Api -Path '/api/v1/me/tenants' -Token $Token
    $actual = Format-Tenants $r
    Add-Check $Id $Name $Expected $actual ($actual -eq $Expected)
}
function Check-Me([string]$Id, [string]$Name, [string]$Token, [hashtable]$Headers, [string]$Expected) {
    $r = Invoke-Api -Path '/api/v1/me' -Token $Token -Headers $Headers
    $actual = Format-Me $r
    Add-Check $Id $Name $Expected $actual ($actual -eq $Expected)
}
function Check-Status([string]$Id, [string]$Name, [string]$Path, [string]$Token, [hashtable]$Headers, [int[]]$Allowed) {
    $r = Invoke-Api -Path $Path -Token $Token -Headers $Headers
    $exp = 'HTTP ' + ($Allowed -join ' or ')
    Add-Check $Id $Name $exp "HTTP $($r.Status)" ($Allowed -contains $r.Status)
}
function Check-PackageList([string]$Id, [string]$Name, [string]$Token, [hashtable]$Headers, [string]$MustHave, [string]$MustNotHave) {
    $r = Invoke-Api -Path '/api/v1/packages?limit=100' -Token $Token -Headers $Headers
    $ids = Get-PackageIds $r
    $has = $ids -contains $MustHave; $hasNot = $ids -contains $MustNotHave
    $actual = "HTTP $($r.Status), own package listed=$has, other entity's package listed=$hasNot"
    Add-Check $Id $Name 'HTTP 200, own package listed=True, other entity''s package listed=False' $actual (($r.Status -eq 200) -and $has -and -not $hasNot)
}

$roleSuper = 'DOCUMENT_SUPERVISOR'
$roleAdmin = 'BUSINESS_ADMIN,DOCUMENT_SUPERVISOR,TENANT_IT_ADMIN'

# ---------------------------------------------------------------- Setup: identifiers and one package per entity
function Ensure-EntityPackage([string]$Token, [string]$Label) {
    $cfg = Invoke-Api -Path '/api/v1/business-admin/configuration' -Token $Token
    if ($cfg.Status -ne 200) { throw "Setup: GET business-admin/configuration for $Label returned HTTP $($cfg.Status)" }
    $keys = @(@($cfg.Json.identifiers) | ForEach-Object { $_.key })
    if ($keys -notcontains 'contract_number') {
        $body = @{ identifiers = @(
            @{ id = $null; label = 'Contract Number'; required = $true; primary = $true; searchable = $true; extractable = $true; displayable = $true;
               validationType = 'NUMERIC'; exactLength = 12; minLength = $null; maxLength = $null; caseNormalization = 'PRESERVE'; stripSeparators = $false; allowedValues = @(); customPattern = $null },
            @{ id = $null; label = 'Member Number'; required = $true; primary = $false; searchable = $true; extractable = $true; displayable = $true;
               validationType = 'NUMERIC'; exactLength = 11; minLength = $null; maxLength = $null; caseNormalization = 'PRESERVE'; stripSeparators = $false; allowedValues = @(); customPattern = $null },
            @{ id = $null; label = 'Service Entity'; required = $true; primary = $false; searchable = $true; extractable = $true; displayable = $true;
               validationType = 'NUMERIC'; exactLength = 3; minLength = $null; maxLength = $null; caseNormalization = 'PRESERVE'; stripSeparators = $false;
               allowedValues = @('000', '001', '002', '003', '004', '005'); customPattern = $null }
        ) }
        $put = Invoke-Api -Method 'PUT' -Path '/api/v1/business-admin/identifiers' -Token $Token -Body $body
        if ($put.Status -ne 200) { throw "Setup: PUT identifiers for $Label returned HTTP $($put.Status)" }
    }
    $contract = '9' + (Get-Date -Format 'MMddHHmmss') + $(if ($Label -eq 'A') { '1' } else { '2' })
    $pkgBody = @{ packageType = 'MANUAL_UPLOAD'; businessIdentifiers = @{ contract_number = $contract; member_number = '10000000001'; service_entity = '001' };
                  metadata = @{ phase1 = "setup-$Label" } }
    $pkg = Invoke-Api -Method 'POST' -Path '/api/v1/packages' -Token $Token -Body $pkgBody
    if ($pkg.Status -ne 201) { throw "Setup: POST packages for $Label returned HTTP $($pkg.Status)" }
    return $pkg.Json.packageId
}

Write-Host '[phase1] Getting tokens (held in memory only).'
$tAlice = Get-Token $Phase1.Realm $Phase1.ChecksClientId 'alice.a'
$tBob   = Get-Token $Phase1.Realm $Phase1.ChecksClientId 'bob.b'

$pkgA = $null; $pkgB = $null
try {
    $pkgA = Ensure-EntityPackage $tAlice 'A'
    $pkgB = Ensure-EntityPackage $tBob 'B'
    Add-Check 'S-01' 'Setup: one package created in each entity by its own admin' 'A and B package ids' 'A and B package ids' $true
} catch {
    Add-Check 'S-01' 'Setup: one package created in each entity by its own admin' 'A and B package ids' $_.Exception.Message $false
}
if (-not $pkgA) { $pkgA = [guid]::NewGuid().ToString() }
if (-not $pkgB) { $pkgB = [guid]::NewGuid().ToString() }

# ---------------------------------------------------------------- Point 1
$tCarol = Get-Token $Phase1.Realm $Phase1.ChecksClientId 'carol.ab'
$tDave  = Get-Token $Phase1.Realm $Phase1.ChecksClientId 'dave.none'

Check-Tenants 'P1-01' 'alice: /me/tenants lists only entity A' $tAlice "HTTP 200 [phase1-entity-a{$roleAdmin}]"
Check-Me      'P1-02' 'alice: /me without a choice opens entity A' $tAlice @{} "HTTP 200 phase1-entity-a{$roleAdmin}"
Check-Me      'P1-03' 'alice: /me choosing entity B is refused' $tAlice (Sel $B) 'HTTP 403'
Check-Status  'P1-04' "alice: open B's package without a choice" "/api/v1/packages/$pkgB" $tAlice @{} @(403, 404)
Check-Status  'P1-05' "alice: open B's package choosing B" "/api/v1/packages/$pkgB" $tAlice (Sel $B) @(403)
Check-PackageList 'P1-06' 'alice: package list shows A, not B' $tAlice @{} $pkgA $pkgB
Check-Tenants 'P1-07' 'bob: /me/tenants lists only entity B' $tBob "HTTP 200 [phase1-entity-b{$roleAdmin}]"
Check-Me      'P1-08' 'bob: /me without a choice opens entity B' $tBob @{} "HTTP 200 phase1-entity-b{$roleAdmin}"
Check-Me      'P1-09' 'bob: /me choosing entity A is refused' $tBob (Sel $A) 'HTTP 403'
Check-Status  'P1-10' "bob: open A's package without a choice" "/api/v1/packages/$pkgA" $tBob @{} @(403, 404)
Check-Status  'P1-11' "bob: open A's package choosing A" "/api/v1/packages/$pkgA" $tBob (Sel $A) @(403)
Check-PackageList 'P1-12' 'bob: package list shows B, not A' $tBob @{} $pkgB $pkgA

# ---------------------------------------------------------------- Point 2
Check-Tenants 'P2-01' 'carol: /me/tenants lists A and B' $tCarol "HTTP 200 [phase1-entity-a{$roleSuper}; phase1-entity-b{$roleSuper}]"
Check-Me      'P2-02' 'carol: /me without a choice is refused (no implicit pick)' $tCarol @{} 'HTTP 403'
Check-Me      'P2-03' 'carol: choose A' $tCarol (Sel $A) "HTTP 200 phase1-entity-a{$roleSuper}"
Check-PackageList 'P2-04' 'carol in A: package list shows A, not B' $tCarol (Sel $A) $pkgA $pkgB
Check-Me      'P2-05' 'carol: switch to B (same token)' $tCarol (Sel $B) "HTTP 200 phase1-entity-b{$roleSuper}"
Check-PackageList 'P2-06' 'carol in B: package list shows B, not A' $tCarol (Sel $B) $pkgB $pkgA
Check-Me      'P2-07' 'carol: switch back to A' $tCarol (Sel $A) "HTTP 200 phase1-entity-a{$roleSuper}"
Check-Status  'P2-08' "carol in A: open B's package" "/api/v1/packages/$pkgB" $tCarol (Sel $A) @(403, 404)
Check-Status  'P2-09' "carol in B: open A's package" "/api/v1/packages/$pkgA" $tCarol (Sel $B) @(403, 404)
Check-Status  'P2-10' 'carol in A: business-admin settings refused (no admin role leaks in)' '/api/v1/business-admin/configuration' $tCarol (Sel $A) @(403)

# ---------------------------------------------------------------- Refusals
Check-Me 'R-01' 'carol: choose the demo tenant (exists, ACTIVE, no grant)' $tCarol (Sel $Phase1.DemoTenant) 'HTTP 403'
Check-Me 'R-02' 'alice: choose a random UUID' $tAlice (Sel ([guid]::NewGuid().ToString())) 'HTTP 403'
Check-Me 'R-03' 'alice: malformed choice value' $tAlice @{ 'X-Selected-Tenant-Id' = 'not-a-uuid' } 'HTTP 403'
Check-Tenants 'R-04' 'dave (no entity): /me/tenants is empty' $tDave 'HTTP 200 []'
Check-Me 'R-05' 'dave (no entity): /me refused' $tDave @{} 'HTTP 403'
Check-Status 'R-06' 'dave (no entity): package list refused' '/api/v1/packages' $tDave @{} @(403)
Check-Me 'R-07' 'alice: LOCAL-mode headers are ignored in OIDC mode' $tAlice @{ 'X-Tenant-Id' = $B; 'X-Actor-Id' = [guid]::NewGuid().ToString(); 'X-Actor-Roles' = 'PLATFORM_OPERATIONS_ADMIN' } "HTTP 200 phase1-entity-a{$roleAdmin}"

$tNoAud = Get-Token $Phase1.Realm $Phase1.NoAudClientId 'alice.a'
Check-Status 'R-08' 'token without the API audience' '/api/v1/me' $tNoAud @{} @(401)
$tNoAud = $null
Check-Status 'R-09' 'no token' '/api/v1/me' $null @{} @(401)
$tEve = Get-Token $Phase1.OtherRealm $Phase1.OtherClientId 'eve.other'
Check-Status 'R-10' 'wrong issuer (other realm, same group name lv-a-staff): /me' '/api/v1/me' $tEve @{} @(401)
Check-Status 'R-11' 'wrong issuer: /me/tenants' '/api/v1/me/tenants' $tEve @{} @(401)
$tEve = $null
Check-Status 'R-12' 'malformed bearer token' '/api/v1/me' 'not-a-jwt' @{} @(401)
$tOtherAud = Get-Token $Phase1.Realm $Phase1.OtherAudClientId 'alice.a'
Check-Status 'R-15' 'token with an aud claim that is not the LexaVault API' '/api/v1/me' $tOtherAud @{} @(401)
$tOtherAud = $null

# ---------------------------------------------------------------- Point 3: different redaction rules per entity
# A redacts SSN only; B redacts SSN, card number and date of birth. REVIEW_ALL keeps every enabled finding
# in the review list, so the result can be read through the API (there is no document download).
$policyA = @('SSN')
$policyB = @('CREDIT_CARD_NUMBER', 'DATE_OF_BIRTH', 'SSN')
$samplesRoot = Join-Path $Phase1.RepoRoot 'test-data\golden-path-watched-intake-template'
$samplePdfs = @(
    (Join-Path $samplesRoot '04-approval\04_approval_flow.pdf'),
    (Join-Path $samplesRoot '05-reprocess\05_reprocess_cycle.pdf'),
    (Join-Path $samplesRoot '07-export\07_export_delivery.pdf')
)

function Set-EntityPolicy([string]$Token, [string[]]$Types) {
    $body = @{ enabledEntityTypes = $Types; autoRedactThreshold = 0.8; reviewMode = 'REVIEW_ALL'; maskSsnLastFour = $true }
    return Invoke-Api -Method 'PUT' -Path '/api/v1/business-admin/sensitive-data-policy' -Token $Token -Body $body
}
function Get-PolicyTypes($resp) {
    if ($resp.Status -ne 200) { return "HTTP $($resp.Status)" }
    return 'HTTP 200 ' + ((@($resp.Json.sensitiveDataPolicy.enabledEntityTypes) | Sort-Object) -join ',') + " $($resp.Json.sensitiveDataPolicy.reviewMode)"
}
function Send-Pdf([string]$Token, [string]$PackageId, [string]$Path) {
    $req = New-Object System.Net.Http.HttpRequestMessage ([System.Net.Http.HttpMethod]::Post), ($Phase1.BackendUrl + "/api/v1/packages/$PackageId/documents")
    $req.Headers.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue 'Bearer', $Token
    $form = New-Object System.Net.Http.MultipartFormDataContent
    $file = New-Object System.Net.Http.ByteArrayContent (, [System.IO.File]::ReadAllBytes($Path))
    $file.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse('application/pdf')
    $form.Add($file, 'file', [System.IO.Path]::GetFileName($Path))
    $req.Content = $form
    $resp = $http.SendAsync($req).GetAwaiter().GetResult()
    $status = [int]$resp.StatusCode
    $req.Dispose(); $resp.Dispose()
    return $status
}
function Invoke-EntityRun([string]$Token, [string]$Label) {
    $contract = '8' + (Get-Date -Format 'MMddHHmmss') + $(if ($Label -eq 'A') { '1' } else { '2' })
    $pkg = Invoke-Api -Method 'POST' -Path '/api/v1/packages' -Token $Token -Body @{
        packageType = 'MANUAL_UPLOAD'; businessIdentifiers = @{ contract_number = $contract; member_number = '10000000003'; service_entity = '001' }
        metadata = @{ phase1 = "point3-$Label" } }
    if ($pkg.Status -ne 201) { throw "create package for $Label returned HTTP $($pkg.Status)" }
    $id = $pkg.Json.packageId
    foreach ($pdf in $samplePdfs) {
        $s = Send-Pdf $Token $id $pdf
        if ($s -lt 200 -or $s -gt 299) { throw "upload $([System.IO.Path]::GetFileName($pdf)) for $Label returned HTTP $s" }
    }
    $sub = Invoke-Api -Method 'POST' -Path "/api/v1/packages/$id/submit" -Token $Token
    if ($sub.Status -lt 200 -or $sub.Status -gt 299) { throw "submit for $Label returned HTTP $($sub.Status)" }
    $deadline = (Get-Date).AddSeconds(240); $status = ''
    while ((Get-Date) -lt $deadline) {
        $d = Invoke-Api -Path "/api/v1/packages/$id" -Token $Token
        $status = [string]$d.Json.status
        if ($status -and $status -notin @('CREATED', 'SUBMITTED', 'PROCESSING')) { break }
        Start-Sleep -Seconds 3
    }
    $items = Invoke-Api -Path "/api/v1/review-items?packageId=$id&limit=200" -Token $Token
    $types = @()
    if ($items.Status -eq 200) { $types = @(@($items.Json.items) | ForEach-Object { $_.entityType } | Sort-Object -Unique) }
    $counts = @{}
    if ($items.Status -eq 200) { foreach ($it in @($items.Json.items)) { $counts[$it.entityType] = 1 + [int]$counts[$it.entityType] } }
    return [pscustomobject]@{ PackageId = $id; Status = $status; ItemsStatus = $items.Status; Types = $types; Counts = $counts }
}
function Format-Counts($run) { (($run.Counts.Keys | Sort-Object) | ForEach-Object { "$_=$($run.Counts[$_])" }) -join ', ' }

$tAlice = Get-Token $Phase1.Realm $Phase1.ChecksClientId 'alice.a'
$tBob   = Get-Token $Phase1.Realm $Phase1.ChecksClientId 'bob.b'
$tCarol = Get-Token $Phase1.Realm $Phase1.ChecksClientId 'carol.ab'

$putA = Set-EntityPolicy $tAlice $policyA
$putB = Set-EntityPolicy $tBob $policyB
Add-Check 'P3-01' 'alice sets entity A policy (SSN only, REVIEW_ALL)' 'HTTP 200' "HTTP $($putA.Status)" ($putA.Status -eq 200)
Add-Check 'P3-02' 'bob sets entity B policy (SSN, card, date of birth, REVIEW_ALL)' 'HTTP 200' "HTTP $($putB.Status)" ($putB.Status -eq 200)
$readA = Get-PolicyTypes (Invoke-Api -Path '/api/v1/business-admin/configuration' -Token $tAlice)
$readB = Get-PolicyTypes (Invoke-Api -Path '/api/v1/business-admin/configuration' -Token $tBob)
Add-Check 'P3-03' 'entity A policy reads back as set' 'HTTP 200 SSN REVIEW_ALL' $readA ($readA -eq 'HTTP 200 SSN REVIEW_ALL')
Add-Check 'P3-04' 'entity B policy reads back as set' 'HTTP 200 CREDIT_CARD_NUMBER,DATE_OF_BIRTH,SSN REVIEW_ALL' $readB ($readB -eq 'HTTP 200 CREDIT_CARD_NUMBER,DATE_OF_BIRTH,SSN REVIEW_ALL')
Check-Status 'P3-05' "bob (B admin): read entity A's policy" '/api/v1/business-admin/configuration' $tBob (Sel $A) @(403)
Check-Status 'P3-06' "alice (A admin): read entity B's policy" '/api/v1/business-admin/configuration' $tAlice (Sel $B) @(403)
$crossPut = Invoke-Api -Method 'PUT' -Path '/api/v1/business-admin/sensitive-data-policy' -Token $tBob -Headers (Sel $A) -Body @{ enabledEntityTypes = @('CREDIT_SCORE'); autoRedactThreshold = 0.8; reviewMode = 'REVIEW_ALL'; maskSsnLastFour = $true }
Add-Check 'P3-07' "bob (B admin): change entity A's policy" 'HTTP 403' "HTTP $($crossPut.Status)" ($crossPut.Status -eq 403)

$runA = $null; $runB = $null
try {
    $runA = Invoke-EntityRun $tAlice 'A'
    $runB = Invoke-EntityRun $tBob 'B'
    Add-Check 'P3-08' 'same three PDFs processed in each entity' 'both packages PENDING_REVIEW' "A=$($runA.Status), B=$($runB.Status)" (($runA.Status -eq 'PENDING_REVIEW') -and ($runB.Status -eq 'PENDING_REVIEW'))
} catch {
    Add-Check 'P3-08' 'same three PDFs processed in each entity' 'both packages PENDING_REVIEW' $_.Exception.Message $false
}
if ($runA -and $runB) {
    $typesA = ($runA.Types -join ',')
    $typesB = ($runB.Types -join ',')
    Add-Check 'P3-09' 'entity A findings: SSN only' 'SSN' "$typesA ($(Format-Counts $runA))" ($typesA -eq 'SSN')
    $bCore = ($runB.Types -contains 'SSN') -and ($runB.Types -contains 'CREDIT_CARD_NUMBER') -and (@($runB.Types | Where-Object { $_ -notin $policyB }).Count -eq 0)
    Add-Check 'P3-10' 'entity B findings: SSN and card number, nothing outside its policy' 'includes SSN and CREDIT_CARD_NUMBER; only B policy types' "$typesB ($(Format-Counts $runB))" $bCore
    if ($runB.Types -contains 'DATE_OF_BIRTH') {
        Add-Check 'P3-11' 'entity B also finds the date of birth' 'DATE_OF_BIRTH present' 'present' $true
    } else {
        # Task 0006: if the rule-based detector misses the date of birth, report it; point 3 still passes on the card number.
        $results.Add([pscustomobject]@{ Id = 'P3-11'; Check = 'entity B also finds the date of birth'; Expected = 'DATE_OF_BIRTH present'; Actual = 'not found by the rule-based detector'; Result = 'NOTE' })
        Write-Host '[NOTE] P3-11  entity B also finds the date of birth  (expected: DATE_OF_BIRTH present; actual: not found by the rule-based detector)'
    }
    $cross = Invoke-Api -Path "/api/v1/review-items?packageId=$($runB.PackageId)&limit=200" -Token $tCarol -Headers (Sel $A)
    $crossCount = if ($cross.Status -eq 200) { @($cross.Json.items).Count } else { -1 }
    Add-Check 'P3-12' "carol in A: B's review items not visible" 'HTTP 403/404, or HTTP 200 with 0 items' "HTTP $($cross.Status), items=$crossCount" (($cross.Status -in 403, 404) -or ($cross.Status -eq 200 -and $crossCount -eq 0))
}

# ---------------------------------------------------------------- Toggles on entity B (always restored)
$sqlRestore = "update enterprise_identity_connections set status = 'ACTIVE', updated_at = now() where connection_id = 'b2b2b2b2-0000-4000-8000-0000000c0002'; update tenants set status = 'ACTIVE', updated_at = now() where id = '$B';"
function Restore-B { Invoke-Phase1Sql $sqlRestore | Out-Null }

function Run-BToggle([string]$Prefix, [string]$What, [string]$Sql) {
    try {
        Invoke-Phase1Sql $Sql | Out-Null
        Write-Host "[phase1] $What - waiting $CacheWaitSeconds s for any cache."
        Start-Sleep -Seconds $CacheWaitSeconds
        $tb = Get-Token $Phase1.Realm $Phase1.ChecksClientId 'bob.b'
        $tc = Get-Token $Phase1.Realm $Phase1.ChecksClientId 'carol.ab'
        Check-Tenants "$Prefix-1" "$What`: bob /me/tenants is empty" $tb 'HTTP 200 []'
        Check-Me      "$Prefix-2" "$What`: bob /me refused" $tb @{} 'HTTP 403'
        Check-Tenants "$Prefix-3" "$What`: carol /me/tenants lists only A" $tc "HTTP 200 [phase1-entity-a{$roleSuper}]"
        Check-Me      "$Prefix-4" "$What`: carol choosing B refused" $tc (Sel $B) 'HTTP 403'
        Check-Me      "$Prefix-5" "$What`: carol choosing A still works" $tc (Sel $A) "HTTP 200 phase1-entity-a{$roleSuper}"
    } finally {
        Restore-B
    }
    Start-Sleep -Seconds $CacheWaitSeconds
    $tb2 = Get-Token $Phase1.Realm $Phase1.ChecksClientId 'bob.b'
    Check-Me "$Prefix-6" "$What`: after restore, bob opens B again" $tb2 @{} "HTTP 200 phase1-entity-b{$roleAdmin}"
}

Run-BToggle 'R-13' "B's login-system record DISABLED" "update enterprise_identity_connections set status = 'DISABLED', updated_at = now() where connection_id = 'b2b2b2b2-0000-4000-8000-0000000c0002';"
Run-BToggle 'R-14' 'Entity B SUSPENDED' "update tenants set status = 'SUSPENDED', updated_at = now() where id = '$B';"

$tAlice = $null; $tBob = $null; $tCarol = $null; $tDave = $null
$http.Dispose()

# ---------------------------------------------------------------- Results (no tokens)
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$file = Join-Path $Phase1.ResultsDir "checks-$stamp.json"
$summary = [ordered]@{
    run      = $stamp
    backend  = $Phase1.BackendUrl
    issuer   = $Phase1.Issuer
    passed   = @($results | Where-Object Result -eq 'PASS').Count
    failed   = @($results | Where-Object Result -eq 'FAIL').Count
    notes    = @($results | Where-Object Result -eq 'NOTE').Count
    checks   = $results
}
$summary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $file -Encoding UTF8
Write-Host ''
Write-Host ("[phase1] {0} passed, {1} failed, {2} notes. Results: {3}" -f $summary.passed, $summary.failed, $summary.notes, $file)
if ($summary.failed -gt 0) { exit 1 }
