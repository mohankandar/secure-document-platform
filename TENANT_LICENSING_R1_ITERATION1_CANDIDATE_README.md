# LexaVault Tenant Licensing — R1 / Iteration 1 Foundation Candidate (Accepted)

Status: **ACCEPTED — VERIFIED / FROZEN**  
Date: 2026-09-18

> This file preserves the original candidate scope and validation plan. Final accepted runtime evidence is recorded in `TENANT_LICENSING_R1_ITERATION1_VERIFIED_README.md`.

## Scope

This candidate starts from the frozen LexaVault V1 + Platform-Managed Processing Storage baseline and adds the backend foundation for tenant licensing. No Platform Licensing UI is included yet; that is Iteration 2.

### Implemented

- `tenantId` remains the globally unique immutable licensed identity; no hardware/MAC/hostname binding is introduced.
- One deployment can license multiple tenants independently (for example Contract and Title departments).
- Signed offline token format: `LV1.<base64url(payload-json)>.<base64url(Ed25519-signature)>`.
- Token claims: schema version, license ID, tenant ID, customer name, edition, issued/not-before/expiry times, entitlements and optional numeric limits.
- Explicit validation states: `NOT_ENFORCED`, `MISSING`, `ACTIVE`, `NOT_YET_VALID`, `EXPIRED`, `INVALID_SIGNATURE`, `TENANT_MISMATCH`, `UNSUPPORTED_SCHEMA`, `MALFORMED`, `CONFIGURATION_ERROR`.
- PostgreSQL immutable license history plus one current assignment per tenant.
- Dynamic signature/time/tenant re-validation from the stored signed token. Denormalized DB metadata is not trusted as licensing truth.
- `DOCUMENT_PROCESSING` entitlement enforcement at package create, document upload, package submit, package reprocess, and worker processing start.
- Enforced deployments fail closed; Platform Operations APIs remain available so a missing/expired license can be corrected.
- Platform Operations APIs:
  - `GET /api/v1/platform/licensing`
  - `GET /api/v1/platform/licensing/tenants/{tenantId}`
  - `POST /api/v1/platform/licensing/tenants/{tenantId}/licenses`
  - `GET /api/v1/platform/licensing/tenants/{tenantId}/history`
- Signed license tokens are accepted on install but are never returned by APIs or written to audit detail/logs.
- Audit events: `LICENSE_INSTALLED`, `LICENSE_VALIDATION_FAILED`, `LICENSE_ENFORCEMENT_DENIED`.
- LOCAL defaults to licensing `DISABLED` so the frozen developer/demo flow remains unchanged.
- Production/default configuration is `ENFORCED` and trusts the packaged vendor public-key resource only. The private signing key must never ship with LexaVault.
- LOCAL only can use `SECUREDOC_LICENSING_PUBLIC_KEY` as a development override to validate an ephemeral test license.
- Development-only helper: `scripts/testing/GenerateLexaVaultDevelopmentLicense.java`.

## Database

Liquibase adds `029-tenant-licensing.xml`:

- `tenant_license_records`
- `tenant_license_assignments`

Current candidate baseline: **70 changeSets**.

## Important key-management rule

The candidate intentionally does **not** ship a production private key. Production signing belongs in LexaVault-controlled licensing infrastructure. The final production vendor public key must be packaged at:

`classpath:licensing/lexavault-license-public-key.pem`

Until that production key is supplied, non-LOCAL enforced installations correctly report license verification configuration as unavailable. LOCAL validation can use the ephemeral development override described below.

## Local validation

### 1. Build / migration

Run the normal project build. Confirm Liquibase advances to **70** changeSets and creates the two licensing tables.

### 2. Existing LOCAL behavior remains unchanged

Start normally with the `local` profile. Licensing is disabled by default. With Platform Operations headers:

```powershell
$h = @{
  "X-Actor-Roles" = "PLATFORM_OPERATIONS_ADMIN"
  "X-Tenant-Id"   = "00000000-0000-0000-0000-000000000001"
  "X-Actor-Id"    = "00000000-0000-0000-0000-000000000099"
}
Invoke-RestMethod http://localhost:8080/api/v1/platform/licensing -Headers $h | ConvertTo-Json -Depth 8
```

Expected: `enforcementEnabled=false`, tenant status `NOT_ENFORCED`, `processingAllowed=true`.

### 3. Generate an ephemeral development license

```powershell
cd .\scripts\testing
javac .\GenerateLexaVaultDevelopmentLicense.java
$out = java GenerateLexaVaultDevelopmentLicense 00000000-0000-0000-0000-000000000001 7
$publicKey = (($out | Where-Object { $_ -like 'PUBLIC_KEY=*' }) -replace '^PUBLIC_KEY=','')
$licenseToken = (($out | Where-Object { $_ -like 'LICENSE_TOKEN=*' }) -replace '^LICENSE_TOKEN=','')
cd ..\..
```

This helper is development-only and generates an ephemeral signing key. It is not a production licensing mechanism.

### 4. Restart LOCAL in enforced test mode

```powershell
$env:SECUREDOC_LICENSING_ENFORCEMENT="ENFORCED"
$env:SECUREDOC_LICENSING_PUBLIC_KEY=$publicKey
.\start-local.cmd -ScannerMode SIMULATOR -LoggingFormat Readable
```

Before license install, `GET /api/v1/platform/licensing` should show `MISSING` and `processingAllowed=false`. A new manual intake/upload should be rejected with `LICENSE_REQUIRED` and should not write an ORIGINAL artifact.

### 5. Install the signed license

```powershell
$body = @{ licenseToken = $licenseToken } | ConvertTo-Json
Invoke-RestMethod `
  -Method Post `
  -Uri "http://localhost:8080/api/v1/platform/licensing/tenants/00000000-0000-0000-0000-000000000001/licenses" `
  -Headers $h `
  -ContentType "application/json" `
  -Body $body | ConvertTo-Json -Depth 8
```

Expected: `status=ACTIVE`, `processingAllowed=true`, edition `DEVELOPMENT`, and `DOCUMENT_PROCESSING` in entitlements.

### 6. Process a PDF

Create/upload/submit one normal PDF. Expected: normal processing proceeds and existing Processing Storage / export behavior is unchanged.

### 7. History

```powershell
Invoke-RestMethod `
  "http://localhost:8080/api/v1/platform/licensing/tenants/00000000-0000-0000-0000-000000000001/history" `
  -Headers $h | ConvertTo-Json -Depth 8
```

Expected: the installed license appears as the active history item; the signed token itself is absent from the response.

## Assistant-side validation completed

- Java 21 compilation passed for all new domain/application licensing classes and modified create/upload/submit/reprocess/trigger services.
- Infrastructure licensing classes passed Java 21 source compilation against dependency stubs.
- Liquibase XML parses successfully and total count is **70**.
- Development Ed25519 license generator compiles/runs on Java 21.
- Full Maven reactor was not run in the assistant environment because Maven is unavailable; the user's normal local build remains the formal candidate build validation.

## Explicitly unchanged

- Platform-Managed Processing Storage remains VERIFIED / FROZEN.
- SDV master delivery and local/shared master delivery are unchanged.
- Derived-document routing/delivery is unchanged.
- Redaction/OCR/AI/review semantics are unchanged.
- Licensing UI and connected/online licensing service are deferred to Iteration 2.
