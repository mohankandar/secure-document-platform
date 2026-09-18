# LexaVault Current State

**Authoritative date:** 2026-09-18  
**Project codename:** LexaVault  
**Repository/code naming:** `secure-document-platform` / `com.securedoc.platform.*`

## Product baseline

**LexaVault V1 is COMPLETE / VERIFIED / FROZEN.** Final V1 Regression / Release Readiness is complete. The accepted V1 baseline includes core PDF/OCR/redaction/review, business identifiers, Document Intelligence foundation, derived routing, local AI optimization, support/incident tooling, structured JSON logging, watched-folder intake, multi-node HA/shared intake coordination, Scanner Intake software, Production AI Inference Tier, Business/Operational Reporting, Production Performance R3 FIX4, and the final consolidated regression gate.

Physical scanner hardware certification and customer-specific production infrastructure/DR/secrets/network validation remain deployment activities, not unfinished V1 product work.

## Latest completed post-V1 work

### Platform-Managed Processing Storage — R1 / Iteration 2 FIX1

Status: **VERIFIED / FROZEN**.

Iteration 1 FIX1 backend/foundation has passed the primary single-node LOCAL runtime validation on 2026-09-17: LOCAL bootstrap/readiness, provider health, normal upload/process/export, managed provider UUID persistence, processing-cycle pinning, provider switch, new-root writes, and historical-provider readability using an artifact still inside the configured 24-hour retention window all passed. Older artifacts already purged by retention are not valid provider-readability evidence.

Iteration 2 adds the Platform Operations `Processing Storage` tab and the operational management workflow:

- readiness and active-node count;
- deployment-default provider view;
- `LOCAL_FILESYSTEM` / `SHARED_FILESYSTEM` candidate creation;
- Test Connection with candidate validation visible before activation;
- per-provider/per-node validation state;
- deployment-default activation;
- provider retirement when unassigned;
- provisioned tenant list plus tenant-specific provider override/clear;
- explicit guidance that filesystem authentication is supplied by the OS/service account, not stored as SMB/NFS credentials in LexaVault.

Runtime validation completed for the Iteration 2 UI and HA flow: provider creation/test/activation, tenant override/clear, four-node shared-filesystem validation/activation, new artifact persistence to `SHARED_FILESYSTEM`, processing-cycle pinning, one-node loss with the remaining 3/3 nodes READY, and a shared-root outage where 0/3 nodes became healthy, readiness changed to NOT READY, manual upload failed before processing, and no artifact appeared in any prior local provider root.

That validation exposed one topology edge: a `LOCAL_FILESYSTEM` provider activated while only one node was present remained globally READY if additional nodes joined later. FIX1 closes that gap at three layers: platform overview readiness becomes NOT READY whenever any assigned local provider exists with more than one active node; per-node processing dispatch readiness rejects the local provider in that topology; and the storage adapter rejects new local writes as defense in depth. Historical reads remain allowed. Liquibase remains **68 changeSets**.

The focused FIX1 topology retest passed on 2026-09-17: one node with `LOCAL_FILESYSTEM` was READY; after a second node joined, Platform Operations changed to NOT READY even though both filesystem probes were healthy; manual upload was blocked with the cluster-safety message and no local artifact was created; after activating a validated `SHARED_FILESYSTEM`, readiness returned to READY with 2/2 nodes healthy and upload succeeded. Combined with the previously accepted single-node, provider-switch, tenant-override, historical-read, four-node shared-storage, node-loss, shared-root outage and no-local-fallback evidence, Platform-Managed Processing Storage is now frozen.


## Latest completed post-V1 work — Tenant Licensing

### Tenant Licensing — R1 / Iteration 1 Foundation

Status: **VERIFIED / FROZEN**.

The foundation treats the globally unique immutable `tenantId` as the licensed identity and adds a tenant-scoped signed-license lifecycle without binding licenses to individual machines or application nodes. The signed token format is `LV1.<base64url(payload-json)>.<base64url(Ed25519-signature)>`; runtime verification uses only the public key and the private signing key is never shipped with LexaVault.

Iteration 1 includes:

- PostgreSQL-backed immutable tenant license records plus a current-license assignment, preserving prior license history;
- Ed25519 signature verification with schema version, tenant binding, validity window, edition, entitlements and optional limits;
- explicit states including `MISSING`, `ACTIVE`, `NOT_YET_VALID`, `EXPIRED`, `INVALID_SIGNATURE`, `TENANT_MISMATCH`, `UNSUPPORTED_SCHEMA`, `MALFORMED` and `CONFIGURATION_ERROR`;
- `DOCUMENT_PROCESSING` entitlement enforcement at package creation, document upload, submit, reprocess and processing-worker start;
- fail-closed behavior for enforced deployments while keeping Platform Operations available for license installation/status;
- Platform Operations APIs for all-tenant status, tenant status, signed-license install/update and history; signed tokens are accepted but never returned;
- license install/validation/enforcement audit events with no raw token logging;
- LOCAL profile licensing disabled by default so developer/demo behavior remains unchanged; LOCAL may opt into enforced-mode testing with an ephemeral development public key; production trusts only the packaged vendor verification key resource;
- development-only Java helper to generate an ephemeral Ed25519 key/token for local validation.

Focused runtime validation completed on 2026-09-18:

- enforced mode with no license returned `MISSING`, `processingAllowed=false`, and blocked manual intake;
- a tampered token was rejected and not persisted;
- a correctly signed license for the Local Demo Tenant returned `ACTIVE`, `processingAllowed=true`, and admitted a manual package into processing;
- history retained the installed license metadata while omitting the raw `LV1...` token;
- a validly signed license for another tenant was rejected with HTTP 403; restoring the original verification key revalidated the stored original license back to `ACTIVE`;
- a short-lived signed license transitioned automatically from `ACTIVE` to `EXPIRED` with `processingAllowed=false` and `LICENSE_EXPIRED` after its signed expiry time, without reinstall/restart;
- a correctly signed, correct-tenant, unexpired license without `DOCUMENT_PROCESSING` remained `ACTIVE` as a license but returned `processingAllowed=false` / `DOCUMENT_PROCESSING_ENTITLEMENT_MISSING`, and the UI blocked manual intake with `Tenant license does not include DOCUMENT_PROCESSING`;
- restoring the original public key/license returned the tenant to `ACTIVE`, `processingAllowed=true`, `LICENSE_ACTIVE`.

Liquibase remains **70 changeSets** through `029-tenant-licensing.xml`. Processing Storage remains VERIFIED / FROZEN and SDV/master/derived delivery behavior is unchanged.

## Latest completed post-V1 work — Tenant Licensing Iteration 2

### Tenant Licensing — R1 / Iteration 2 FIX1 Platform Operations UI + History Hardening

Status: **VERIFIED / FROZEN**.

Iteration 2 added the Platform Operations `Licensing` tab with all-tenant status, enforcement/key readiness, processing-admission state, edition/expiry visibility, expiry warnings, entitlement/limit details, signed `LV1...` install/update, and immutable history UX. Focused local validation confirmed malformed-token rejection, valid/blocked entitlement presentation and automatic expiry behavior, but exposed two gaps: a license with only minutes remaining was displayed as `Expires in 1 day` and was not counted in `Needs attention` until expiry; and historical records installed under a previously configured/test key rendered as `INVALID SIGNATURE`, `Unknown edition` and zero entitlements after the key changed.

FIX1 changes only the Iteration 2 presentation/audit-history behavior. Sub-day expiry is rendered in minutes/hours, `Needs attention` includes ACTIVE licenses expiring within 30 days, and history can recover display-only claims for previously verified persisted records by checking the stored payload SHA-256 plus tenant/license/schema identity. Recovered history is explicitly labeled `Verified at install`. Inactive still-valid records render `SUPERSEDED`; expired history remains `EXPIRED`. **Active/current authorization is not weakened:** the active signed token is still verified against the currently configured Ed25519 public key every time, and a key mismatch remains `INVALID_SIGNATURE` / processing blocked.

No schema migration is added; Liquibase remains **70 changeSets**. Raw signed tokens are still never returned by overview/history. Optional connected/online validation remains deferred pending a remote licensing-service contract, authentication policy and air-gap behavior.

Focused local FIX1 validation completed on 2026-09-18 and passed: malformed-token installation was rejected without disturbing the active license; a valid license without `DOCUMENT_PROCESSING` showed `ACTIVE` but Processing Blocked and `Needs attention=1`; a near-expiry active license was counted in `Needs attention`; a 120-second license rendered `Expires in less than 1 minute` rather than `1 day`, then automatically transitioned without manual refresh to `EXPIRED`, Processing Blocked, Active licenses `0`, Processing allowed `0`, and Needs attention `1`; history retained edition/expiry/entitlements across verification-key changes with `Verified at install` plus `SUPERSEDED`/`EXPIRED` labels; restoring the normal public key/license returned the Local Demo Tenant to `ACTIVE` / Processing Allowed. The earlier FIX1 test-source helper compile mismatch was corrected by widening the common test helper parameter to `LicenseTokenVerifier`; runtime validation shown here is from the corrected candidate.

Tenant Licensing R1 Iteration 2 FIX1 is therefore **VERIFIED / FROZEN**.

## Planned post-V1 work

1. **UI/UX Modernization — NEXT**
   - Modernize the application shell, navigation, forms, tables, cards, status treatments and workflow consistency without changing frozen business behavior.
   - Make the primary user and administration workflows responsive and touch-friendly for iPad/tablet use, including landscape and portrait layouts where practical.
   - Define the detailed visual system and acceptance matrix before implementation.

2. **On-Prem Installer & First-Run Setup — AFTER UI/UX MODERNIZATION**
   - Guided installation/commissioning with minimal YAML/environment editing.
   - Automated preflight/service/bootstrap checks and a first-run Platform Operations workflow.

## Core frozen architectural rules

- Multi-tenant isolation remains mandatory; `tenantId` is present on tenant-owned records.
- OIDC remains the production trust boundary and fails closed.
- Review occurs only when policy/confidence/unsupported/failure conditions require it unless tenant policy forces review.
- Original documents remain protected internal artifacts; redacted output is exposed/delivered by default.
- Final master delivery and derived-document delivery are separate from processing-storage configuration.
- No new post-V1 adjustment should silently reopen frozen V1 behavior outside its explicit scope.

## Current execution status

Processing Storage, Tenant Licensing R1 Iteration 1, and Tenant Licensing R1 Iteration 2 FIX1 are **VERIFIED / FROZEN**. The next planned workstream is **UI/UX Modernization with responsive iPad/tablet support**. On-Prem Installer / First-Run Setup follows after that workstream.
