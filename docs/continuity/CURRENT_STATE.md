# LexaVault Current State

**Authoritative date:** 2026-09-21  
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


## Latest completed post-V1 work — UI/UX Modernization R1

### Design System + Responsive App Shell + Branding Foundation + iPad Support

Status: **VERIFIED / FROZEN — FIX1**.

R1 establishes a new modern-enterprise presentation foundation without changing frozen processing/security semantics. The visible product identity is now configuration-driven: `LexaVault` remains the built-in development codename/default brand, while deployment branding and optional tenant overrides can set application/short/organization names, page title, primary/secondary brand colors, primary/compact logos and favicon. Branding is explicitly independent from immutable tenantId/licensing identity and does not change exported PDFs, delivery payloads, audit semantics or legal/vendor identity.

The frontend now has a responsive application shell with grouped navigation, desktop collapse/expand, tablet portrait drawer navigation, touch-friendly controls and shared page/design tokens. Dashboard portrait intentionally uses package cards rather than a squeezed wide table. Upload, Scan and Technical Administration use the shared page-header foundation; Scanner Intake explains the workstation Scanner Bridge boundary for iPad/tablet users. Technical Administration adds a Platform Operations-only `Branding` tab with deployment defaults, tenant overrides/reset, controlled theme colors, PNG/WebP assets and live responsive preview.

Branding persistence is PostgreSQL-backed through `030-ui-branding.xml`; the current candidate schema baseline is **71 Liquibase changeSets**. Primary accent values are validated server-side for six-digit hex syntax and WCAG-AA contrast against white button text; logo bytes are size/type/magic-checked and remain configuration data rather than processing-storage artifacts. The all-tenant branding overview avoids returning every tenant's binary logo payload and loads a selected tenant override on demand. Branding updates emit `BRANDING_CONFIGURATION_CHANGED`.

The user completed the full local build successfully and focused runtime validation of the R1 candidate on desktop plus representative iPad landscape/portrait viewports. Deployment branding, tenant override/inheritance/reset, primary/compact logo and favicon upload, theme application, portrait drawer navigation, portrait Dashboard package cards, and Technical Administration portrait layouts all passed. Validation exposed a small presentation-only cleanup set: duplicate/truncated app-name text beside configured primary logos, preview/logo-rule drift, expanded tablet-landscape navigation consuming work area, overly wide Dashboard columns at 1180 px, a branding editor/preview breakpoint that should stack earlier, and a long tenant-override form whose save action was only at the top.

FIX1 addresses those findings without backend/schema changes: configured primary logos own the expanded/drawer brand slot without repeated app-name text; preview follows the same primary/compact fallback rules; 900–1199 px defaults to a compact but temporarily expandable rail; Dashboard gets a separate tablet-landscape column preference initialized to the primary identifier, one searchable secondary identifier, Stage, Documents, Intake Source and Last Updated while preserving the existing Columns control; Branding stacks editor/preview earlier; and tenant overrides gain a bottom save/reset action area. Liquibase remains **71 changeSets**.

Focused FIX1 acceptance completed on representative **1180×820 landscape** and **820×1180 portrait** tablet viewports. Dashboard landscape defaulted to the compact rail and fit the reduced operational column set without a horizontal scrollbar; portrait retained the drawer shell, 2×2 overview metrics, wrapped controls and adaptive package cards. Branding landscape/portrait retained usable two-column fields, stacked editor/preview at constrained widths, consistent primary/compact logo rules, touch-usable asset controls, and bottom tenant Save/Reset actions. The drawer, deployment branding, tenant inheritance/reset, theme colors, primary/compact logo and favicon, Processing Storage and Licensing presentation all remained intact.

R1 FIX1 is therefore **VERIFIED / FROZEN**. See `UI_UX_MODERNIZATION_R1_VERIFIED_README.md` for accepted evidence and `UI_UX_MODERNIZATION_R1_CANDIDATE_README.md` / `UI_UX_MODERNIZATION_R1_FIX1_CANDIDATE_README.md` for original scope.

## Latest completed post-V1 work — UI/UX Modernization R2–R5

### Full Application Modernization — VERIFIED / FROZEN

Status: **VERIFIED / FROZEN**.

The combined R2–R5 modernization is accepted on top of the frozen R1 foundation. User runtime validation covered desktop plus representative **1180×820 landscape** and **820×1180 portrait** layouts across Dashboard, Upload, Scanner Intake, Package Details, Manual Review, Business Administration, Integrations, Reporting, Support Incidents and Audit. Role visibility/authorization checks for Business Administration and Integrations passed; Business Administration configuration persisted across tab changes; package document-count presentation was corrected; scanner simulator intake reached durable submission and the normal package lifecycle; fresh Manual Review content rendered and review completion resumed processing; reporting and support/audit presentation remained operational.

The validation-driven cleanup is included in the frozen baseline:

- Business Administration is section-based rather than one long settings page; identifiers and routing rules are compact summary rows with on-demand Add/Edit detail.
- Integrations is separated into `Intake Sources`, `Master Delivery`, `Derived Delivery`, and `All Connections`, with counts and purpose-specific descriptions.
- Purpose is locked/read-only in purpose-specific groups; provider options are constrained to valid combinations for that group.
- Switching Integration groups closes/resets any open New/Edit editor so stale drafts cannot leak across categories. `All Connections` remains the neutral place where Purpose is intentionally selectable.
- Support Incidents keeps business identifier, incident ID and status primary while secondary fields live under Advanced Filters.
- Audit hides routine `WATCHED_FOLDER_SCAN_TRIGGERED` events where `mode=SCHEDULED` and `discovered=0` by default while retaining them behind `Show routine activity`.
- Package Details defensively derives a missing document count from the returned document list.
- Responsive shell behavior remains accepted for compact landscape rail and portrait drawer/card layouts.

Build/verification evidence: the user reported both backend and frontend builds successful for the combined release; final browser validation confirmed the FIX4 Integrations state-isolation defect is resolved. Assistant validation for the final Integrations change included TypeScript/TSX syntax transpile PASS and frontend authorization tests **8/8 PASS**. No backend/API/database migration was added; the frozen post-V1 schema remains **71 Liquibase changeSets** through `030-ui-branding.xml`.

UI/UX Modernization R2–R5 is therefore **VERIFIED / FROZEN**. See `UI_UX_MODERNIZATION_R2_R5_VERIFIED_README.md`. Candidate/FIX readmes remain as implementation history.

## Dashboard Retention Visibility Synchronization FIX2 — IMPLEMENTED CANDIDATE / LOCAL VALIDATION REQUIRED

A post-freeze Dashboard lifecycle defect was identified after UI/UX R2–R5 acceptance. Completed/exported packages must remain operationally visible until the same tenant-configured processing-retention lifecycle clears internal processing storage; 24h is only the current/default `TenantLifecyclePolicy.originalRetentionHours` value and is not a Dashboard constant.

FIX1 correctly wired future final purge events to the existing Dashboard visibility metadata. User local runtime validation then exposed the upgrade/backfill gap: many older completed exports already had `purged_at` populated before FIX1, so `findDueFinalPurge(...)` would never select them again and they remained visible. FIX2 preserves the FIX1 future path and adds a bounded reconciliation query for `COMPLETED`, successfully exported, already-final-purged packages whose `dashboard_hidden_at` is still null. Reconciliation sets Dashboard visibility from the existing final-purge lifecycle timestamp and does not rerun processing-artifact deletion.

No package row is deleted; reporting/audit/export/support history remain durable; reporting queries remain independent of Dashboard visibility; never-exported technical-hold history is excluded; manual Approver removal remains unchanged. No new timer, hard-coded 24h rule, schema change or scheduler change is introduced. The normal retention scheduler continues to run at its configured interval and the reconciliation uses the same bounded purge-maintenance command.

Assistant-side Java 21 domain/application compilation passed (**453 sources**), changed persistence files passed compatibility-stub compile, and the focused Dashboard-retention harness passed **5/5**, including already-purged upgrade reconciliation and retry behavior without repurge. Full Maven/local runtime validation remains required because Maven is unavailable in the assistant runtime. Schema remains **71 Liquibase changeSets** through `030-ui-branding.xml`. See `DASHBOARD_RETENTION_VISIBILITY_FIX2_CANDIDATE_README.md`.

## Planned post-V1 work

1. **On-Prem Installer & First-Run Setup — NEXT**
   - Guided installation/commissioning with minimal YAML/environment editing.
   - Automated preflight/service/bootstrap checks and a first-run Platform Operations workflow.
   - Database/bootstrap, deployment identity, tenant, processing storage, licensing, AI/OCR/scanner, delivery/integration checks, and an explicit final READY / NOT READY result.

## Core frozen architectural rules

- Multi-tenant isolation remains mandatory; `tenantId` is present on tenant-owned records.
- OIDC remains the production trust boundary and fails closed.
- Review occurs only when policy/confidence/unsupported/failure conditions require it unless tenant policy forces review.
- Original documents remain protected internal artifacts; redacted output is exposed/delivered by default.
- Final master delivery and derived-document delivery are separate from processing-storage configuration.
- No new post-V1 adjustment should silently reopen frozen V1 behavior outside its explicit scope.


## Operational Continuity, Technical Attention & Privacy Telemetry R1 FIX2 — IMPLEMENTED CANDIDATE / LOCAL REVALIDATION REQUIRED

This candidate follows the Dashboard Retention FIX2 validation work and addresses the broader operational-continuity issues identified during the same runtime review. Existing HA lease/heartbeat recovery remains in place, but package ownership is now bounded by a configurable processing-attempt budget: **3 total package-processing attempts by default**. Recovery scans do not consume attempts; one acquired processing-lease generation does. When the budget is exhausted and the package is still `PROCESSING`, LexaVault moves it to `FAILED` / Technical Attention with `PROCESSING_RECOVERY_EXHAUSTED`. Legacy/pre-HA `PROCESSING` rows with no lease are eligible for recovery after a configurable orphan grace instead of remaining indefinitely stuck.

Technical Attention cleanup is lifecycle-driven. New/default tenant policy uses **168 hours / 7 days** for never-exported Technical Attention retention while preserving existing tenant-configured values. At expiry, processing artifacts are purged, failed packages are removed from the normal operational Dashboard, and active processing incidents are auto-closed as retention-expired; durable package, Support Incident, Audit and Reporting history remains. Tenant IT receives exceptional actions for reprocess, investigation hold (7/14/30 days), release hold, resolve-no-reprocess, superseded and duplicate without rewriting historical package outcomes.

Privacy-preserving product telemetry foundation is included with `OFF / BASIC / ENHANCED` modes, default **OFF** for on-prem, pseudonymous installation/tenant identity, an explicit field allow-list and a dedicated JSONL stream. Customer/document content, OCR/extracted values, business identifiers, customer filenames/paths, prompts/model responses and actor identity are excluded. Telemetry is non-authoritative and cannot block processing or support/lifecycle behavior. No outbound fleet collector is configured until a separate authenticated service contract exists.

Schema candidate baseline is now **73 Liquibase changeSets** through `031-operational-continuity.xml`. The migration adds processing-lease `attempt_count` and changes only the database default for new/default never-exported technical holds to 168 hours; existing stored tenant values are preserved. Prior FIX1 local full build/startup passed and focused Kiro verification returned HA/fencing, legacy PROCESSING recovery, Technical Attention retention and privacy telemetry PASS while identifying the post-review attempt reset. FIX2 assistant-side checks compile the domain/application contracts and changed application sources successfully, compile the changed JDBC/API lease classes with compatibility stubs, and compile the two new focused regression tests. A fresh local full build plus the focused attempt-2 → review → continuation runtime case is required before freeze. See `OPERATIONAL_CONTINUITY_R1_FIX2_README.md`.

## Current execution status

Processing Storage, Tenant Licensing R1 Iteration 1, Tenant Licensing R1 Iteration 2 FIX1, **UI/UX Modernization R1 FIX1**, and **UI/UX Modernization R2–R5 are VERIFIED / FROZEN**. Dashboard Retention Visibility FIX2 remains part of the active post-freeze validation line. **Operational Continuity, Technical Attention & Privacy Telemetry R1 FIX2 is the current implementation candidate** with a 73-changeSet schema baseline through `031-operational-continuity.xml`. Focused Kiro verification passed HA/fencing, legacy PROCESSING recovery, Technical Attention retention and privacy telemetry, but found that post-review continuation could reset a recovered package attempt counter to 1. FIX2 preserves the durable attempt count across ordinary continuations while explicit human redrive starts a fresh budget; local build and the focused attempt-2 → review → continuation runtime case remain required before freeze. On-Prem Installer & First-Run Setup remains the next major workstream after this candidate is accepted.
