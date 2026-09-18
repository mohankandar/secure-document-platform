# LexaVault Tenant Licensing — R1 / Iteration 1 Foundation — VERIFIED / FROZEN

**Status:** VERIFIED / FROZEN  
**Accepted:** 2026-09-18  
**Schema baseline:** 70 Liquibase changeSets through `029-tenant-licensing.xml`

## Accepted scope

Tenant Licensing R1 Iteration 1 is the frozen backend licensing foundation for LexaVault. It uses the globally unique immutable `tenantId` as the licensed identity and deliberately does not bind a license to a hostname, MAC address, CPU, application node, or other machine-specific identifier.

The accepted foundation includes:

- Ed25519-signed offline `LV1` license tokens;
- public-key verification only in the customer runtime; no production private signing key ships with LexaVault;
- tenant binding, schema version, issued/not-before/expiry window, edition, entitlements and optional limits;
- immutable PostgreSQL license history plus one current assignment per tenant;
- dynamic revalidation of the stored signed token rather than trusting denormalized database status;
- `DOCUMENT_PROCESSING` entitlement enforcement at create/upload/submit/reprocess/worker-start boundaries;
- fail-closed enforced-mode behavior while Platform Operations licensing endpoints remain available;
- Platform Operations overview/status/install/history APIs;
- audit actions for install, validation failure and enforcement denial;
- signed token secrecy: raw `LV1...` token is accepted on install but not returned by overview/history and is not included in audit detail/logging;
- LOCAL profile enforcement disabled by default for developer/demo compatibility, with an explicit local-only public-key override for focused licensing tests.

## Runtime evidence accepted

### 1. Backward-compatible LOCAL behavior

With licensing enforcement disabled by the LOCAL default, the normal developer/demo processing flow remained available. This preserved the frozen V1/Processing Storage local behavior.

### 2. Missing license — PASS

With enforcement enabled and the verification key configured, the Local Demo Tenant returned:

- `status=MISSING`
- `processingAllowed=false`
- `reasonCode=LICENSE_MISSING`

Manual intake was blocked.

### 3. Tampered signature — PASS

A valid development token was modified in the encoded payload without re-signing. Installation was rejected; the tenant remained `MISSING` and no license record became active.

### 4. Valid signed license — PASS

A correctly signed license for tenant `00000000-0000-0000-0000-000000000001` installed successfully and returned:

- `status=ACTIVE`
- `processingAllowed=true`
- `reasonCode=LICENSE_ACTIVE`
- edition `DEVELOPMENT`
- `DOCUMENT_PROCESSING` and the expected companion entitlements

A manual PDF upload was admitted and entered processing, proving the enforced licensing gate allows the authorized workflow.

### 5. History and raw-token secrecy — PASS

The tenant history endpoint retained license record metadata, claims, install timestamp/user and active status. The raw signed `LV1...` token was absent from the response.

### 6. Wrong-tenant license — PASS

A correctly signed token whose claim tenant was `11111111-1111-1111-1111-111111111111` was submitted against the Local Demo Tenant licensing endpoint. The install was rejected with HTTP 403.

During this focused test the runtime temporarily used the test token's verification key, which correctly made the previously installed original license fail signature verification. Restoring the original public key returned the original stored license to `ACTIVE`, demonstrating dynamic signature revalidation rather than trust in a stored ACTIVE flag.

### 7. Expiration — PASS

A correctly signed 120-second license installed as `ACTIVE` / `processingAllowed=true`. After its signed `expiresAt` elapsed, the same installed record automatically evaluated as:

- `status=EXPIRED`
- `processingAllowed=false`
- `reasonCode=LICENSE_EXPIRED`

No reinstall or application restart was needed for the transition.

### 8. Missing DOCUMENT_PROCESSING entitlement — PASS

A correctly signed, correct-tenant, unexpired license was generated without `DOCUMENT_PROCESSING`. The license itself remained valid:

- `status=ACTIVE`

but authorization correctly returned:

- `processingAllowed=false`
- `reasonCode=DOCUMENT_PROCESSING_ENTITLEMENT_MISSING`

The Upload Documents UI blocked the attempted manual intake with:

`Tenant license does not include DOCUMENT_PROCESSING`

This proves entitlement enforcement is independent from signature/tenant/time validity.

### 9. Restoration — PASS

After restoring the original public verification key and original development license, the tenant returned to:

- `status=ACTIVE`
- `processingAllowed=true`
- `reasonCode=LICENSE_ACTIVE`

with the expected entitlement set.

## Security / architecture conclusions

The accepted runtime evidence demonstrates that Iteration 1 fails closed for missing, invalid, wrong-tenant, expired and under-entitled licenses while allowing a valid entitled tenant license. The signed token remains the licensing truth and is re-evaluated dynamically. Tenant identity is durable and immutable, and no machine binding or customer-held signing secret is introduced.

## Explicitly unchanged

- Platform-Managed Processing Storage remains VERIFIED / FROZEN.
- Final master delivery, SDV delivery and derived-document delivery are unchanged.
- OCR, redaction, review, AI and routing semantics are unchanged.
- Licensing UI is not part of Iteration 1; Platform Operations Licensing UI is Iteration 2.
- Optional connected/online licensing validation remains future scope.

## Final disposition

**Tenant Licensing R1 Iteration 1 Foundation is VERIFIED / FROZEN.** Reopen only for a reproducible defect or an explicitly scoped later licensing enhancement.
