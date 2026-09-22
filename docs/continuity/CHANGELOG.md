## 2026-09-21 — Operational Continuity R1 FIX2 attempt-budget continuity

- Focused Kiro verification: B HA/fencing PASS, C legacy PROCESSING PASS, D Technical Attention retention PASS, E privacy telemetry PASS; A processing attempt budget FAIL on post-review continuation reset.
- Root cause: PostgreSQL attempt lease upsert reset every non-recovery conflict to attempt 1, so recovered attempt 2 could return from human review as attempt 1.
- FIX2 distinguishes ordinary continuation from explicit fresh redrive. Continuation preserves durable attempt count; recovery increments; Tenant IT/explicit reprocess resets to attempt 1 for a new cycle.
- Added focused regression tests; no schema change, Liquibase remains 73.
- Local full build + focused attempt-2 → review → continuation runtime proof required before freeze.


## 2026-09-21 — Operational Continuity R1 FIX1 startup wiring

- User full local build PASS; Liquibase applied `031-01-processing-attempt-budget` and `031-02-technical-attention-default`, reaching 73 total changeSets.
- LOCAL API startup then failed creating `SupportIncidentController`: multiple constructors were present and no constructor was explicitly selected for dependency injection, causing Spring to look for a default constructor.
- FIX1 marks the 4-argument production constructor (including `SupportIncidentActionService`) with `@Autowired` and adds a focused regression assertion.
- No recovery, retention, Technical Attention, telemetry, security, API contract, frontend, or schema semantics changed.
- Status: BUILD PASS / STARTUP FIX IMPLEMENTED / LOCAL RUNTIME REVALIDATION REQUIRED.

## 2026-09-21 — Operational Continuity, Technical Attention & Privacy Telemetry R1 candidate

- Added configurable package-processing recovery budget: 3 total attempts by default; recovery scans do not count, acquired processing-lease generations do.
- Added safe recovery eligibility for legacy/pre-HA `PROCESSING` packages with no lease after a configurable grace; repeated recovery exhaustion transitions to Technical Attention with `PROCESSING_RECOVERY_EXHAUSTED`.
- Added lifecycle-driven Technical Attention cleanup: default/new policy 168h, processing artifact purge, operational Dashboard hide, Support Incident auto-close on retention expiry, durable package/audit/reporting/support history retained. Existing tenant-configured hold values are not rewritten.
- Added exceptional Tenant IT support actions: reprocess, 7/14/30-day investigation hold, release hold, resolve without reprocess, superseded and duplicate.
- Added privacy-preserving product telemetry foundation: OFF/BASIC/ENHANCED (default OFF), pseudonymous identifiers, explicit allow-list, dedicated JSONL output, no document/OCR/extracted/business/customer-path/prompt/model-response/actor content; telemetry remains non-authoritative.
- Added `031-operational-continuity.xml`; candidate schema is **73 changeSets**.
- Corrected manual lifecycle maintenance documentation to maximum supported `POST /api/v1/lifecycle/purge-due?limit=500`.
- Assistant checks: Java 21 domain/application compile **457 sources PASS**; changed processing persistence, scheduler/API, support/telemetry compile checks PASS; Liquibase parse PASS; focused tests **18/18 PASS**. Full local Maven/frontend/runtime validation required.
- Status: **IMPLEMENTED CANDIDATE / LOCAL VALIDATION REQUIRED**.

## 2026-09-21 — Dashboard Retention Visibility Synchronization FIX2 candidate

- Local FIX1 runtime validation exposed an upgrade/backfill gap: future retention events were correct, but completed exports already marked final-purged before FIX1 were excluded from `findDueFinalPurge(...)` and could remain visible on Dashboard indefinitely.
- FIX2 retains the FIX1 future path and adds bounded backend reconciliation for successfully exported `COMPLETED` packages with `purged_at` populated and `dashboard_hidden_at` still null.
- Reconciliation uses the authoritative existing `purged_at` timestamp for Dashboard hiding and does not recalculate 24h, rerun artifact deletion, or rewrite completed retention history.
- Failed visibility reconciliation is audited and retryable on a later maintenance cycle; never-exported technical-hold packages remain excluded.
- No scheduler change, frontend timer, package deletion or migration; Liquibase remains **71 changeSets** through `030-ui-branding.xml`.
- Assistant checks: Java 21 domain/application compile **453 sources PASS**; changed persistence compile PASS with compatibility stubs; focused retention harness **5/5 PASS**; affected test-source compile PASS. Maven/full local build still required.
- Status: **IMPLEMENTED CANDIDATE / LOCAL BACKEND + RUNTIME VALIDATION REQUIRED**.

## 2026-09-20 — Dashboard Retention Visibility Synchronization FIX1 candidate

- Corrects post-freeze Dashboard lifecycle behavior: completed/exported packages remain visible until the tenant-configured Business Administration `originalRetentionHours` deadline reaches the normal final retention purge.
- The same successful final processing-retention purge now hides the completed/exported package from normal Dashboard rows/KPIs through the existing dashboard visibility metadata/port.
- No separate Dashboard timer and no hard-coded 24h behavior; a 48h focused test proves policy-driven timing.
- Durable package rows, reporting facts, audit history and export history remain; operational reporting continues to include Dashboard-hidden packages.
- Never-exported technical-hold purges are not auto-hidden by this fix; existing manual Approver removal remains unchanged.
- Assistant checks: Java 21 domain/application source compilation PASS; focused retention harness 3/3 PASS. Maven/full local build not run because Maven is unavailable in the assistant runtime.
- No migration; Liquibase remains **71 changeSets** through `030-ui-branding.xml`.
- Status: **IMPLEMENTED CANDIDATE / LOCAL BACKEND + RUNTIME VALIDATION REQUIRED**.

## 2026-09-20 — UI/UX Modernization R2–R5 final acceptance — VERIFIED / FROZEN

- Accepted the combined R2–R5 application modernization on the frozen R1 foundation after broad user runtime walkthroughs plus focused FIX1–FIX4 validation.
- User reported both backend and frontend builds successful for the combined release.
- Operator flows accepted: Dashboard, Upload, Scanner Intake, Package Details and Manual Review, including durable scanner handoff, normal lifecycle continuation, fresh review rendering and automatic resume after review completion.
- Administration accepted: Business Administration persistence and role boundaries; section-based summary-first editing; Integrations purpose groups; purpose locking; provider scoping; on-demand editor; final FIX4 group-switch editor reset; Technical Administration frozen R1 branding/storage/licensing behavior unchanged.
- Reports/Support/Audit accepted: Executive/Usage/Operational reports, Support Incident progressive filters, responsive layouts, and default suppression of routine zero-discovery scheduled watched-folder audit events while preserving access through `Show routine activity`.
- Responsive validation covered representative desktop plus 1180×820 landscape and 820×1180 portrait behavior.
- Final assistant checks for the Integrations correction: TypeScript/TSX syntax transpile PASS and frontend authorization tests 8/8 PASS.
- No backend API/schema/security/storage/licensing/scanner/retention/delivery semantic changes were introduced by the final FIX4 correction; Liquibase remains **71 changeSets** through `030-ui-branding.xml`.
- Status: **VERIFIED / FROZEN**. Next workstream: **On-Prem Installer & First-Run Setup**.

## 2026-09-20 — UI/UX Modernization R2–R5 FIX4 candidate

- Fixed Integrations draft leakage across purpose-group tabs: switching groups now closes/resets the open create/edit editor.
- Purpose remains locked for Intake Sources, Master Delivery and Derived Delivery regardless of transient draft state.
- Provider choices are constrained to combinations valid for the active purpose group; All Connections remains unrestricted and purpose-selectable.
- Assistant validation: `IntegrationsPage.tsx` syntax transpile PASS; frontend authorization tests 8/8 PASS.
- No backend/API/schema/security/storage/licensing/scanner/retention/delivery changes; Liquibase remains 71.

## 2026-09-20 — UI/UX Modernization R2–R5 FIX3 candidate

- Focused FIX2 runtime validation confirmed Intake Sources, Master Delivery, Derived Delivery and All Connections grouping, counts, editor behavior and derived-attention scoping.
- Final UX guardrail: Purpose is now locked/read-only when creating or editing from a purpose-specific group, preventing accidental cross-category configuration.
- All Connections intentionally keeps Purpose selectable as the neutral inventory/creation surface.
- No backend/API/schema/security or delivery semantics changed; Liquibase remains **71 changeSets** through `030-ui-branding.xml`.
- Status: **IMPLEMENTED CANDIDATE / FRONTEND BUILD + NARROW INTEGRATIONS VALIDATION REQUIRED**.

## 2026-09-20 — UI/UX Modernization R2–R5 FIX2 candidate

- User runtime validation of FIX1-era surfaces is broadly PASS: Upload/Scanner/package lifecycle, Manual Review, Business Administration persistence and role boundaries, Reporting, Support Incidents, Audit routine-activity filtering, Package Details document count, and responsive shell behavior were exercised successfully.
- Remaining UX concern is isolated to Integrations information architecture: intake, master-delivery and derived-delivery connections were still mixed in one configured-connections list.
- Integrations now has purpose-group navigation: **Intake Sources**, **Master Delivery**, **Derived Delivery**, and **All Connections**, each with counts and purpose-specific descriptions.
- New Connection inherits the active purpose group; routine review keeps the editor closed, while New/Edit opens it intentionally. Editor Reset restores the edited connection or the current group defaults.
- Derived-delivery attention appears only in Derived Delivery / All Connections. CRUD, provider execution, health tests, polling, enable/disable, retry, secrets, authorization and backend APIs are unchanged.
- Assistant checks: frontend authorization tests **8/8 PASS**; modified `IntegrationsPage.tsx` parses with no syntax diagnostics; CSS brace balance PASS. Full local build + focused Integrations visual validation remain required before freeze.
- No migration; Liquibase remains **71 changeSets** through `030-ui-branding.xml`.
- Status: **IMPLEMENTED CANDIDATE / LOCAL BUILD + INTEGRATIONS FOCUSED VALIDATION REQUIRED**.

## 2026-09-20 — UI/UX Modernization R2–R5 FIX1 candidate

- Broad local runtime walkthrough of the original combined R2–R5 candidate passed core Upload, Scanner simulator, package workflow, Manual Review, role-boundary, Reporting and tablet-shell behavior, but exposed information-density and presentation defects in administration/support surfaces.
- Business Administration now uses one-at-a-time section tabs instead of a single long settings page. Existing business identifiers are compact collapsible rows, new identifiers open directly for editing, and derived-routing configuration follows summary-first Add/Edit behavior.
- Integrations now uses collapsible configured-connection summaries and keeps the large technical editor closed until New/Edit is selected. Existing CRUD/test/poll/enable-disable/retry/secret semantics are unchanged.
- Support Incidents now presents business identifier, incident ID and status as primary triage controls, with correlation/package/document/stage/reason/continuity/retry/date fields under Advanced Filters. Collapsed advanced filters retain an active-filter count.
- Audit keeps all durable records but hides routine `WATCHED_FOLDER_SCAN_TRIGGERED` events where `mode=SCHEDULED` and `discovered=0` from the default timeline; operators can reveal them with `Show routine activity`.
- Package Details now derives a missing detail-response `documentCount` from the returned document list, removing the visible `Documents (undefined)` defect without changing backend API/schema behavior.
- Assistant checks: TS/TSX syntax parse **PASS across 34 source files**, authorization tests **8/8 PASS**, global/Reports CSS brace balance PASS. Full local build and focused runtime validation are required before R2–R5 freeze.
- No migration; Liquibase remains **71 changeSets** through `030-ui-branding.xml`.
- Status: **IMPLEMENTED CANDIDATE / LOCAL BUILD + FOCUSED VALIDATION REQUIRED**.

## 2026-09-20 — UI/UX Modernization R1 FIX1 VERIFIED / FROZEN

- Accepted the modern design-system/app-shell/white-label/iPad foundation after focused desktop and tablet runtime validation.
- 1180×820 landscape: compact rail default, reduced Dashboard operational columns, no page-level horizontal overflow, Branding editor/preview stacking and tenant actions PASS.
- 820×1180 portrait: drawer shell, adaptive Dashboard package cards, 2×2 metrics, wrapped controls, Branding form/preview/tenant override layout and bottom Save/Reset actions PASS.
- Deployment/tenant branding inheritance/reset, accessible theme colors, primary/compact logo and favicon upload, and preview/shell logo fallback rules PASS.
- No backend/schema change in FIX1; frozen post-V1 baseline remains 71 Liquibase changeSets through `030-ui-branding.xml`.

## 2026-09-18 — Tenant Licensing R1 Iteration 2 FIX1 final acceptance — VERIFIED / FROZEN

- Platform Operations `Licensing` UI and FIX1 history/expiry hardening accepted after focused local runtime/UI validation.
- Malformed `LV1.invalid.invalid` install was rejected with a clear error while the existing valid license remained ACTIVE / processing allowed and no bogus replacement became current.
- Valid correct-tenant license without `DOCUMENT_PROCESSING` remained license-status ACTIVE but correctly showed Processing Blocked, `DOCUMENT_PROCESSING` missing, Processing allowed `0`, and Needs attention `1`.
- Near-expiry active licenses now count in Needs attention; the restored normal license expiring in seven days shows ACTIVE / Allowed with Needs attention `1`.
- Sub-day rendering passed with a 120-second license: the UI displayed `Expires in less than 1 minute` rather than rounding to `1 day`.
- Automatic expiry refresh passed without manual Refresh: the same page transitioned to EXPIRED / Blocked, Active licenses `0`, Processing allowed `0`, Needs attention `1`, and the current history row changed to EXPIRED.
- Historical records installed under temporary/previous keys now retain readable edition, expiry and entitlement metadata, are labeled `Verified at install`, and render inactive valid rows as SUPERSEDED and elapsed rows as EXPIRED. Active/current authorization remains cryptographically fail-closed under the currently configured Ed25519 key.
- Normal public key/license restoration passed: Local Demo Tenant returned to ACTIVE / Allowed with the expected six entitlements; raw `LV1...` tokens remain absent from overview/history.
- An initial FIX1 test-source compile mismatch (`HistoricalVerifier` vs helper parameter `ConfigurableVerifier`) was corrected by accepting the shared `LicenseTokenVerifier` interface; no runtime licensing semantics changed.
- No database migration; Liquibase remains **70 changeSets** through `029-tenant-licensing.xml`.
- Next planned workstream is **UI/UX Modernization with responsive iPad/tablet support**, followed by On-Prem Installer / First-Run Setup.

# Continuity Changelog

## 2026-09-20 — UI/UX Modernization R2–R5 combined candidate

- Started the combined post-R1 application modernization program from the **UI/UX Modernization R1 FIX1 VERIFIED / FROZEN** baseline. One codebase/release is used with hard internal checkpoints A–E.
- Added shared `WorkflowStepper`, `StatusBadge` and `InfoGrid` UI primitives for consistent progress, semantic state and responsive detail presentation.
- Core operator workflows: Upload and Scan now expose explicit three-step progress; Package Details is reorganized into a modern responsive intake workspace with contextual action, Overview/Documents/Delivery & retention/Derived routing tabs and responsive cards. Existing package APIs, approval, reprocess, manual-continuation and derived-delivery behavior remain unchanged.
- Human review: Manual Review adopts the shared page foundation plus portrait decision access; Manual Processing preserves the existing page/redaction tools while adopting the modern header/responsive foundation.
- Administration: Business Administration gains sticky/scrollable section navigation across major policy areas; Integrations gains operational summary cards and shared page treatment without changing connection behavior. Frozen Processing Storage/Licensing/Branding functionality is untouched.
- Reports/Support/Audit: report interactions are aligned to brand tokens without semantic changes; Support results become responsive record cards on tablet/portrait and detail pages gain summary treatment; Audit results become responsive event cards instead of a wide table while retaining privacy-safe identifiers/correlation fields.
- Responsive styling covers desktop, tablet landscape, tablet portrait and smaller fallbacks while preserving R1 shell/branding behavior.
- No backend/schema change. Liquibase remains **71 changeSets** through `030-ui-branding.xml`.
- Assistant checks: TS/TSX syntax transpile PASS across 34 source files; frontend authorization **8/8 PASS**; global/Reports CSS brace balance PASS. Full local build and checkpoint runtime validation are required before freeze.
- Status: **IMPLEMENTED CANDIDATE / LOCAL VALIDATION REQUIRED**.

## 2026-09-18 — UI/UX Modernization R1 FIX1 candidate

- Full local R1 build passed. Focused desktop plus 1180×820 and 820×1180 validation confirmed deployment/tenant branding, controlled theme colors, logo/favicon assets, portrait drawer navigation, portrait Dashboard package cards and responsive Technical Administration behavior.
- FIX1 removes duplicate/truncated application-name text when a configured primary logo is already occupying the expanded sidebar or tablet drawer brand slot; compact rail/mobile top bar continue to use the compact logo/short name where appropriate.
- Branding live preview now applies the same primary-logo versus compact-logo fallback rules as the real shell.
- 900–1199 px tablet landscape now defaults to a compact rail but remains temporarily expandable by the user.
- Dashboard tablet landscape now uses a separate responsive column preference initialized to an intentional reduced set (primary identifier, one searchable secondary identifier, Stage, Documents, Intake Source, Last Updated); the existing Columns menu can still add approved fields without changing desktop preferences.
- Branding editor/live-preview stacking now occurs earlier on constrained desktop/tablet widths to avoid right-edge clipping.
- Long tenant branding overrides now provide Save/Reset actions at the bottom of the form, with portrait-friendly reachability.
- No database/backend behavior change; Liquibase remains **71 changeSets** through `030-ui-branding.xml`. Frozen processing, licensing, storage, security, scanner and delivery behavior is unchanged.
- Status: **IMPLEMENTED CANDIDATE / LOCAL BUILD + FOCUSED RUNTIME VALIDATION REQUIRED**.


## 2026-09-18 — UI/UX Modernization R1 candidate

- Added a modern responsive application shell with grouped navigation, desktop collapse/expand, tablet portrait drawer behavior, touch-friendly controls and shared design tokens/page-header/icon foundations.
- Added database-backed white-label branding resolution: built-in codename/default -> deployment override -> tenant override. Visible application/short/organization names, page title, accent/secondary colors, primary/compact logos and favicon are configurable without changing tenant/licensing identity.
- Added Platform Operations-only `Technical Administration -> Branding` with deployment and tenant editing, inherit/reset semantics, PNG/WebP assets, contrast validation and live responsive preview.
- Added branding APIs and audit event `BRANDING_CONFIGURATION_CHANGED`; all-tenant overview omits binary tenant asset payloads and loads the selected tenant detail on demand.
- Primary accent is server-validated for WCAG-AA white-text contrast; arbitrary CSS and semantic status-color overrides are not supported.
- Modernized Dashboard hierarchy and added intentional tablet-portrait package cards; Upload, Scan and Technical Administration adopt the shared page header. Scanner Intake now uses resolved product naming and explains the workstation Scanner Bridge boundary for tablet/iPad use.
- Added `030-ui-branding.xml`; current post-V1 candidate baseline is **71 Liquibase changeSets**. Frozen V1 and all licensing/processing-storage/delivery semantics remain unchanged.
- Assistant-environment checks: branding application-layer javac PASS, API/infrastructure branding syntax javac PASS against framework stubs, Liquibase XML parse PASS, and frontend TS/TSX syntax transpile PASS. Full local build/runtime/iPad/security regression is still required.
- Status: **IMPLEMENTED CANDIDATE / LOCAL VALIDATION REQUIRED**.

## 2026-09-18 — Tenant Licensing R1 Iteration 2 FIX1 candidate

- Focused Iteration 2 UI validation confirmed malformed-token rejection, DOCUMENT_PROCESSING entitlement blocking, valid-license presentation and automatic expiry behavior.
- Found two non-enforcement gaps: sub-day licenses were rounded up to `Expires in 1 day` and were not counted in `Needs attention` before expiry; historical licenses installed under temporary/previous signing keys lost readable claims when the configured verification key changed.
- FIX1 now renders sub-day expiry in minutes/hours, refreshes relative-time presentation every 30 seconds, and counts otherwise ACTIVE/allowed licenses expiring within 30 days as attention.
- History recovery is audit-only: for records already verified before persistence, LexaVault validates the stored payload SHA-256 plus tenant/license/schema identity and can render edition/expiry/entitlements as `Verified at install` even when the current key no longer verifies the historical signature.
- Inactive time-valid history is shown as `SUPERSEDED`; expired history remains `EXPIRED`. Active/current authorization still uses current-key Ed25519 verification and remains fail-closed on key mismatch.
- No database migration; Liquibase remains 70 changeSets through `029-tenant-licensing.xml`.
- Status: **IMPLEMENTED CANDIDATE / LOCAL VALIDATION REQUIRED**.


## 2026-09-18 — Tenant Licensing R1 Iteration 2 active candidate

- Platform Operations Licensing UI candidate implemented; focused local UI/security/runtime validation is required before freeze.
- See the detailed Iteration 2 entry in the milestone section below and `TENANT_LICENSING_R1_ITERATION2_CANDIDATE_README.md`.
- No backend/schema change; Liquibase remains 70 changeSets.

## 2026-09-18 — Tenant Licensing R1 Iteration 1 final acceptance — VERIFIED / FROZEN

- Focused enforced-mode runtime validation completed against the Local Demo Tenant.
- Missing-license fail-closed passed: overview returned `MISSING`, `processingAllowed=false`, `LICENSE_MISSING`, and manual intake was blocked.
- Tamper protection passed: a payload-modified `LV1` token was rejected and no license record became active.
- Valid-license admission passed: the correct Ed25519-signed tenant license returned `ACTIVE`, `processingAllowed=true`, `LICENSE_ACTIVE`, and manual upload entered processing.
- License history/privacy passed: history retained safe metadata/claims while the raw signed `LV1...` token was not returned.
- Tenant binding passed: a validly signed license for another tenant was rejected with HTTP 403. Restoring the original verification key dynamically revalidated the previously stored original license back to `ACTIVE`.
- Expiry enforcement passed: a 120-second correctly signed license first returned `ACTIVE`, then automatically transitioned to `EXPIRED`, `processingAllowed=false`, `LICENSE_EXPIRED` after its signed `expiresAt` without reinstall or restart.
- Entitlement enforcement passed: a correctly signed, correct-tenant, unexpired license without `DOCUMENT_PROCESSING` remained license-status `ACTIVE` but returned `processingAllowed=false`, `DOCUMENT_PROCESSING_ENTITLEMENT_MISSING`; the Upload Documents UI blocked submission with `Tenant license does not include DOCUMENT_PROCESSING`.
- Restoration passed: after the original public key/license was restored, overview returned `ACTIVE`, `processingAllowed=true`, `LICENSE_ACTIVE` with the expected entitlement set.
- LOCAL licensing remains disabled by default for developer/demo compatibility. Production/enforced deployments remain fail-closed.
- No licensing private key is packaged with the product; runtime verification uses the configured/vendor public key only.
- Liquibase baseline remains **70 changeSets** through `029-tenant-licensing.xml`. Processing Storage and final master/SDV/derived delivery behavior remain unchanged.
- Final status: **Tenant Licensing R1 Iteration 1 Foundation — VERIFIED / FROZEN**.

## 2026-09-18 — Tenant Licensing R1 Iteration 1 foundation candidate

- Started Tenant Licensing after Platform-Managed Processing Storage was VERIFIED / FROZEN.
- Added PostgreSQL tenant-license history/current-assignment schema (`029-tenant-licensing.xml`); current candidate baseline is **70 Liquibase changeSets**.
- Added compact `LV1` Ed25519-signed offline license format with tenant binding, schema version, validity window, edition, entitlements and optional limits.
- Production verification trusts only the packaged vendor public-key resource; the private signing key never ships. LOCAL alone may use an ephemeral development-key override for testing.
- Added explicit validation states and dynamic re-verification so expiry/tampering/tenant mismatch cannot be hidden by database metadata changes.
- Added fail-closed `DOCUMENT_PROCESSING` entitlement checks at create/upload/submit/reprocess/worker-start boundaries.
- Added Platform Operations licensing APIs for overview, per-tenant status, install/update and history. Signed tokens are never returned or logged.
- Added license install/validation/enforcement audit actions and focused application/crypto tests plus a development-only Ed25519 license generator.
- LOCAL remains licensing-unenforced by default so the frozen developer/demo flow is unchanged; enforced-mode runtime validation is still required before freeze.
- Processing Storage and final master/SDV/derived delivery behavior are unchanged.
- Status: **IMPLEMENTED CANDIDATE / LOCAL VALIDATION REQUIRED**.


## 2026-09-17 — Processing Storage R1 Iteration 2 FIX1 final acceptance — VERIFIED / FROZEN

- Focused HA topology retest passed with one node using `LOCAL_FILESYSTEM`: Processing Storage was READY and 1/1 node healthy.
- After a second application node joined, Platform Operations changed to NOT READY even though the local filesystem probe was healthy on 2/2 nodes, correctly enforcing that node-local storage is not cluster-safe.
- Manual upload was blocked with `LOCAL_FILESYSTEM processing storage is not permitted while multiple application nodes are active; configure SHARED_FILESYSTEM`, and no local processing artifact was created.
- After activating the previously validated `SHARED_FILESYSTEM` provider, readiness returned to READY with 2/2 active nodes healthy and document upload succeeded.
- Evidence carried forward: single-node LOCAL bootstrap/process/export, managed provider UUID persistence, cycle pinning, provider switching, tenant override apply/clear, historical-provider reads inside retention, 4-node shared-provider validation/activation, shared artifact persistence, 4→3 node-loss continuity, shared-root outage fail-closed, and no-local-fallback.
- Final status: **Platform-Managed Processing Storage — VERIFIED / FROZEN**.
- Liquibase remains **68 changeSets**; final master/SDV delivery and derived-document delivery remain unchanged.

## 2026-09-17 — Processing Storage R1 Iteration 2 FIX1 HA topology hardening

- LOCAL UI validation passed: provider create/test/activate, deployment default switch, tenant override and clear, and historical-provider reads inside retention.
- Four-node shared-filesystem runtime validation passed: all 4 nodes validated the candidate, activation succeeded, new ORIGINAL/EXTRACTED_TEXT/REDACTED artifacts used the `SHARED_FILESYSTEM` provider, and processing-cycle pinning persisted the shared provider.
- Node-loss validation passed: after one node aged out, the remaining 3/3 nodes stayed healthy and Processing Storage remained READY.
- Shared-root outage validation passed: readiness became NOT READY with 0/3 nodes healthy; manual upload failed with `Shared processing storage root is unavailable`; processing did not start; no artifact appeared in any previous local provider root, proving no silent local fallback.
- Validation exposed one HA topology gap: an already-active `LOCAL_FILESYSTEM` provider could remain globally READY if additional nodes joined later.
- FIX1 makes any assigned `LOCAL_FILESYSTEM` topology with more than one active node NOT READY, rejects per-node processing dispatch in that state, and blocks new local artifact writes as defense in depth. Historical reads are unchanged.
- Platform health/UI messaging now explains that multi-node deployments require `SHARED_FILESYSTEM`.
- Added focused regression coverage for existing-local-provider topology transition and local-write fail-closed behavior.
- No Liquibase change; baseline remains 68 changeSets. Final delivery/SDV/derived delivery remain unchanged.
- Status: **IMPLEMENTED CANDIDATE / FOCUSED TOPOLOGY RETEST REQUIRED**.

## 2026-09-17 — Processing Storage R1 Iteration 2 Platform Operations UI candidate

- Iteration 1 FIX1 primary single-node LOCAL validation passed: bootstrap/readiness, healthy node validation, normal upload/process/export, managed provider UUID persistence, cycle pinning, provider switching/new-root writes, and historical-provider readability for an artifact still retained.
- Added `Platform Operations -> Processing Storage` tab for `PLATFORM_OPERATIONS` users.
- Added provider create/test/activate/retire workflow, deployment-default status, active-node health counts, per-node validation details, and tenant override management.
- Added provisioned tenant summaries for Platform Operations storage assignment.
- Processing-storage overview now exposes validation for all non-retired providers, including unassigned candidates, fixing the R1 usability gap where Test Connection succeeded but candidate validation was hidden until assignment.
- Filesystem authentication remains OS/service-account based; no SMB/NFS credentials are stored by LexaVault.
- No Liquibase change; candidate baseline remains 68 changeSets.
- SDV/master delivery and derived-document delivery remain unchanged.
- Status: **IMPLEMENTED CANDIDATE / LOCAL UI + MULTI-NODE VALIDATION REQUIRED**.

## 2026-09-17 — Processing Storage R1 Iteration 1 FIX1

- LOCAL validation confirmed Spring profile `local` and successful Liquibase migration to 68 total changeSets.
- `ProcessingStorageNodeMonitor` and LOCAL storage bootstrap both failed with `BadSqlGrammarException` even though equivalent PostgreSQL schema and heartbeat SQL succeeded manually.
- Root cause isolated to passing `java.time.Instant` directly through positional `JdbcTemplate` parameters for PostgreSQL `TIMESTAMP WITH TIME ZONE` columns.
- FIX1 converts processing-storage JDBC timestamp parameters to `java.sql.Timestamp` for provider writes, assignments, cycle pins, node heartbeat, node validation, and active-node cutoff queries.
- Failure logging now retains the exception stack/root message for future database diagnostics.
- No Liquibase schema change. Baseline remains 68 changeSets.
- No changes to SDV/master delivery, derived delivery, redaction semantics, retention semantics, or frontend UI.
- Status: **IMPLEMENTED CANDIDATE / LOCAL VALIDATION REQUIRED**.

## 2026-09-11 — Business / Operational Reporting R1 UX + timing candidate

- Marked Production AI Inference Tier R3 VERIFIED / FROZEN after targeted Kiro PASS WITH MINOR FINDINGS.
- Activated Business / Operational Reporting as the current implementation unit.
- Replaced compact reporting UX with Executive, Business Admin, Usage & Adoption and Operational Details experiences.
- Added touch-first period/source/outcome controls with modern presets plus custom date/month/quarter/year selection.
- Narrowed REPORT_VIEWER to executive/aggregate access only; no export/operational detail by role alone.
- Added structured active-processing, review-wait, review-handling, end-to-end turnaround and backlog-aging measures.
- Expanded aggregate CSV export with timing, automation, source mix, backlog aging and reprocessing measures.
- Durable package-independent historical reporting facts and tenant-approved business dimensions remain within the active reporting unit before freeze.

## 2026-09-11 — Production AI Inference Tier R3 saturation-classification hardening

- Carried forward R2 runtime PASS evidence for distinct-endpoint distribution, dead+good failover and all-nodes-down fail-closed behavior.
- Corrected a saturation edge case where a competing request could synchronously re-probe an already-healthy node with active leased work and falsely classify it as unavailable.
- Request-path health refresh is now deferred for healthy nodes while `inflight > 0`; the active inference success/failure remains authoritative for liveness/circuit state.
- Added a focused regression test that forces a later readiness probe to fail while the first chat request is active and requires bounded `QUEUE_TIMEOUT` classification.
- Reworked `Start-Mock-Ollama-Node.ps1` to serve connections concurrently so DELAY chat traffic does not block `/api/tags`.
- R3 requires one normal build plus only the focused saturation/backpressure rerun before the targeted Kiro checkpoint.


# LexaVault V1 — Milestone Changelog

This is a concise milestone history. Detailed implementation and verification records are preserved under `../archive/`.


## 2026-09-18 — Tenant Licensing R1 Iteration 2 Platform Operations UI candidate

- Added `Platform Operations -> Licensing` tab using the frozen Iteration 1 licensing APIs.
- Added enforcement/public-key readiness, all-tenant summary counts and per-tenant license/processing status.
- Added edition, validity-window and expiration visibility with 30-day and 7-day warning states.
- Added tenant detail UX for entitlements and numeric limits.
- Added signed `LV1...` install/update workflow; the raw token is not returned by APIs/history and the UI clears it after successful install.
- Added immutable license-history UX with current-record indication, dynamic validation status, expiry, entitlements and installer identity.
- Added explicit offline Ed25519 verification-mode messaging. Connected/online validation remains unconfigured until an external licensing-service contract and air-gap policy are defined.
- No backend or Liquibase change; baseline remains **70 changeSets** through `029-tenant-licensing.xml`. Processing Storage and final master/SDV/derived delivery are unchanged.
- Status: **IMPLEMENTED CANDIDATE / LOCAL UI VALIDATION REQUIRED**.


## 2026-09-11 — Scanner-E2E R5 software freeze + Production AI Inference Tier R1 candidate

- Added privacy-safe inference-node operational fields to the structured-log allow-list; dead-node health probes now open the same bounded cooldown circuit used by runtime infrastructure failures.

- Accepted focused Scanner-E2E R5 verification as **PASS WITH FINDINGS — SOFTWARE READY / HARDWARE CERTIFICATION PENDING**. Scanner software is now VERIFIED / FROZEN; real target hardware remains a deployment certification gate.
- Confirmed R5 physical acquisition is real Windows WIA software with safe no-device failure, no simulator fallback, multi-page consolidation, durable common-pipeline handoff, idempotency/provenance and crash-window recovery.
- Activated **Production AI Inference Tier — HA, Load Balancing & Horizontal Scaling**.
- Added opt-in homogeneous Ollama inference pools with stable logical node IDs, exact configured-model readiness checks, least-inflight routing with rotating ties, bounded per-node concurrency, bounded queue wait, targeted infrastructure failover, circuit cooldown/rejoin and safe node-ID observability.
- Preserved the legacy single-endpoint path when pool mode is disabled. Tenant deployment profiles may independently enable a pool, preserving the future SaaS shared/dedicated inference boundary.
- Made Ollama startup preload pool-aware: fail-closed startup requires at least one ready pool member rather than every configured member.
- Made Platform technical health pool-aware with aggregate configured/reachable/model-ready counts.
- Added focused R1 tests for distribution, failover, wrong-model exclusion, bounded saturation, recovered-node rejoin, configuration parsing and partial-node preload.
- Status: **R1 IMPLEMENTED CANDIDATE / LOCAL VALIDATION REQUIRED**. Broad performance/load tuning remains intentionally deferred to the final Production Performance workstream.

## 2026-09-11 — Scanner-E2E-R5 physical Windows WIA candidate

- Accepted Kiro's preceding Scanner-E2E verdict as **PARTIAL — SIMULATOR READY / PHYSICAL PENDING**; common simulator/durable/idempotency/security/lifecycle behavior is evidenced, while real physical acquisition remained the single functional gap.
- Replaced the fail-closed physical stub with a Windows WIA acquisition adapter; PHYSICAL mode does not fall back to simulator.
- Added configurable WIA device selection, `AUTO|FEEDER|FLATBED`, optional duplex, DPI, page limit and acquisition timeout.
- Added a bridge-owned PowerShell/WIA process boundary that captures physical pages without accepting arbitrary browser filesystem paths.
- Added PDFBox consolidation of acquired page images into exactly one durable PDF, then reuses the existing Scanner Intake READY/Submit/durable queue/idempotent delivery path.
- Added crash-window recovery for a finalized physical PDF published immediately before durable READY persistence and focused physical-adapter unit coverage.
- Updated unified-local startup/operator text for real PHYSICAL mode.
- Status remains **IMPLEMENTED CANDIDATE / LOCAL BUILD + REAL-HARDWARE E2E VERIFICATION REQUIRED**. Do not freeze Scanner Intake until the target Windows scanner successfully completes paper -> bridge -> PDF -> LexaVault -> normal-processing verification.

## 2026-09-10 — Scanner-E2E-R4 FIX2 unified LOCAL startup

- Folded Scanner Bridge startup into the normal `start-local.cmd` / `Start-LexaVault-Local.ps1` flow and Scanner Bridge shutdown into `stop-local.cmd`.
- `start-local.cmd` now defaults to `SIMULATOR`; `SIMULATOR` can be explicit, and `PHYSICAL` / `PRINTER` select the physical acquisition boundary.
- If a project-managed bridge is already running in the other mode, the launcher restarts it in the requested mode. Standalone Scanner Bridge scripts remain available for troubleshooting.
- Physical mode remains intentionally fail-closed because no certified TWAIN/WIA/vendor acquisition adapter is present in R4. There is no silent simulator fallback.
- Product Owner local smoke test reports the unified startup working. Full Scanner Intake E2E checklist completion and focused independent verification remain pending before freeze.

## 2026-09-10 — Scanner-E2E-R4 FIX1 Start Scan browser/preflight correction

- Corrected the defect where Scanner Intake could show **Bridge unavailable** on `Start Scan` even while bridge health/status GETs were green.
- Trusted CORS preflight is now answered directly at the Scanner Bridge security-filter boundary, including Chromium private/local-network preflight when requested, while preserving exact Origin allow-listing, loopback-only binding, and the per-process mutation token.
- UI error handling now distinguishes a browser-blocked mutation from an actually stopped bridge.
- Simulator guidance now makes the safe sequence explicit: enter identifiers -> **Start Scan** -> **Select Test PDF** -> wait for **DOCUMENT RECEIVED** -> **Submit**. Pre-existing inbox files remain intentionally rejected by the stale-file/misassociation guard.

## 2026-09-10 — Scanner-E2E-R3 loopback trust-boundary correction

- Senior review of the R2 software-completion candidate found that `server.address` / `SECUREDOC_SCAN_BRIDGE_BIND_ADDRESS` could still expose Scanner Bridge on `0.0.0.0`, `::`, or a LAN interface, violating the loopback-only assumption behind the browser request nonce.
- Added a runtime `LoopbackBindAddressGuard` that fails application startup unless every resolved bind address is loopback, including protection against direct Spring property overrides outside the launcher.
- Added launcher preflight validation so invalid non-loopback binds are rejected before a child Scanner Bridge process is started.
- Added focused regression coverage for IPv4/IPv6 loopback, localhost, wildcard, LAN, blank, and unresolvable bind addresses.
- Removed document SHA-256 fingerprints from Scanner Bridge logs; durable hashing, idempotency/checksum validation, and immutable LexaVault scanner provenance are unchanged.
- R3 remains **IMPLEMENTED CANDIDATE / LOCAL BUILD + E2E VALIDATION REQUIRED**. All R2 deferred production gates remain deferred and unchanged.

## 2026-09-10 — Scanner-E2E-R2 software-completion candidate

- Scanner-E2E-R1 build gate reported PASS by the Product Owner.
- Added strict browser-to-loopback Origin enforcement plus a per-process request nonce for every mutating Scanner Bridge API call; nonce rotates on bridge restart and is never stored in React state or logs.
- Added bridge process instance/build provenance so UI/support can distinguish a restarted bridge from stale browser state and refresh the local request nonce automatically.
- Added local durable-storage admission checks and a configurable maximum scan-PDF size; simulator test-PDF ingestion streams through a `.part` file, fsyncs, enforces the limit while writing, and atomically publishes only a complete candidate.
- Added privacy-safe local delivery diagnostics and explicit requeue for retained `ATTENTION_REQUIRED` work using the same submission/source identity and idempotent LexaVault reconciliation.
- Preserved the simple operator flow: tenant-configured business identifiers -> Start Scan -> one consolidated PDF -> Submit -> Start Next Package. No scanner-specific downstream processing and no future Document Intelligence extraction were introduced.
- R2 remains **IMPLEMENTED CANDIDATE / LOCAL BUILD + E2E VALIDATION REQUIRED**. Physical scanner adapter/certification, Windows Service/installer + service-account ACL hardening, production hosted-origin/private-network browser validation, trusted per-human operator delegation, and final throughput/capacity certification remain later production gates.


## 2026-09-09 — Multi-node HA freeze + Scanner Bridge SB-1/2/3 verification

- R7 High Availability / Multi-Node Intake Coordination & Shared Intake Storage accepted as VERIFIED / FROZEN after Kiro PASS plus completion of crash/recovery/shared-root/DB-time evidence.
- Scanner Bridge SB-1/2/3 focused Kiro verification returned PASS WITH MINOR FINDINGS. Core bridge startup/health, tenant identifier workflow, scanner simulator, stable/PDF-only intake, SHA-256, stale-file protection, one-session-one-document, sequential IDs, no extraction/AI and privacy-safe logs passed.
- Two Scanner Bridge corrections remain before freeze: remove bridge-owned First Name/Last Name/generic business metadata fields; make bridge availability status reflect live connectivity after stop/restart.
- UX direction clarified: dedicated `Scan Documents` is the scanner workflow; manual `Upload Documents` no longer carries a second scanner file-picker action.

## 2026-09-03 — Requirement 5 local AI performance + final hardening candidate

- Requirement 5 R2: added in-application Ollama model preload with fail-closed LOCAL behavior, an explicit `local-qwen` benchmark overlay, and `Start-LexaVault-Local.ps1 -AiModel` support after Kiro showed Gemma3:12b exceeded the benchmark laptop VRAM/performance target.
- Kiro R2 benchmark accepted `qwen3.5:4b` as the V1 LOCAL/demo reference model on the current 6 GB VRAM laptop; `gemma3:12b` remains an explicit larger-hardware option with no silent fallback.
- Added Ollama startup preload/30-minute keep-alive for LOCAL startup.
- Added compact tenant-policy prompt + JSON-schema structured output and `think=false`.
- Added configurable `num_ctx`, `num_predict`, page-batch size and max batch-character safety cap at deployment and tenant profile levels.
- LOCAL defaults batch two moderate pages per provider call while retaining exact page-number validation; oversized pages are isolated rather than truncated.
- Added safe Ollama performance telemetry: batches/provider calls, load duration, prompt/output tokens/evaluation durations and output tokens/sec.
- Added benchmark summary script and benchmark/quality-gate protocol.
- Added focused performance-profile test while preserving existing retry and credit-card precision contracts.

- Final hardening candidate promotes Qwen to the actual `local`/launcher default, adds safe malformed-body and technical-dependency API mappings, preserves polling partial-retrieval counts, and adds focused polling/SDV/derived-routing regression tests.
- Added dependency-free final-runtime fixtures for the carried-forward marker → derived PDF → HTTP/FILE_SHARE regression closure.

| Date | Milestone | Status |
| --- | --- | --- |
| 2026-08-29 | Unit 16 — AI-first digital PDF redaction | Approved |
| 2026-08-29 | Unit 17 — scanned/mixed PDF OCR | Approved |
| 2026-08-29 | Unit 18 — confidence + exception handling | Approved |
| 2026-08-29 | Unit 19 — focused Manual Review | Approved |
| 2026-08-29 | Unit 20 — Business Record + unified intake | Approved |
| 2026-08-29 | Unit 21 — Business Admin configuration | Approved |
| 2026-08-29 | Unit 22 — delivery + lifecycle/retention/reprocess | Approved |
| 2026-08-30 | Unit 23 — shared Dashboard/reporting foundation | Approved |
| 2026-08-30 | Unit 24 — tenant/platform technical administration | Approved |
| 2026-08-30 | Unit 25 — production identity/platform hardening | Approved |
| 2026-08-31 | Pre-acceptance reliability/precision/runtime corrections | Accepted into baseline |
| 2026-09-01 | Batch 2 — one-page multi-PDF intake | Accepted |
| 2026-09-01 | Batch 3 — breadcrumb/responsive navigation polish | Accepted |
| 2026-09-01 | Batch 4 — Executive + Operational Reports | Accepted |
| 2026-09-01 | Role/capability simplification | Accepted; manual + independent + adversarial verification passed |
| 2026-09-01 | Documentation consolidation | Current documentation separated from historical archive |
| 2026-09-03 | Integration Unit 1 foundation + manifest-first Push API | Accepted; full Kiro Maven/Liquibase/runtime/API verification PASS |
| 2026-09-03 | Requirement 2 scheduled polling / pull ingestion | Accepted; Kiro runtime verification PASS WITH MINOR FINDINGS, one-line Spring constructor wiring fix folded into baseline |
| 2026-09-03 | Requirement 3 redacted master delivery to SDV | Accepted; Kiro PASS WITH MINOR FINDINGS, no source changes |
| 2026-09-03 | Requirement 4 derived split/routing | Accepted; Kiro PASS WITH MINOR FINDINGS, no source changes |
| 2026-09-03 | Requirement 5 local AI performance | Accepted; Qwen3.5:4b benchmark PASS, no source changes |
| 2026-09-03 | Final V1 hardening/regression | Candidate; no new product scope |
| 2026-09-09 | HA / Multi-Node Intake Coordination R7 | VERIFIED / FROZEN |
| 2026-09-09 | Scanner Bridge SB-1/2/3 | Kiro PASS WITH MINOR FINDINGS; corrections carried into Scanner E2E |
| 2026-09-10 | Scanner E2E R4 FIX2 | Unified LOCAL startup smoke confirmed; full E2E + focused Kiro verification pending |

The archive is historical evidence, not an alternative current specification.

### 2026-09-09 — Scanner Bridge SB-1/2/3 findings-correction candidate

- Removed Scanner Bridge-local `firstName` / `lastName` and the generic bridge-owned business metadata-field schema.
- Scanner Intake now renders business fields only from LexaVault tenant Business Identifier Definitions.
- Bridge session transport now carries business identifier values only; LexaVault remains authoritative for business semantics and validation.
- Scanner Intake now polls live bridge status and fails/recover safely across local bridge stop/restart; stale in-memory sessions are cleared with an operator-safe restart message.
- Removed the legacy `Scan Documents` file-picker action from manual Upload Documents; dedicated Scanner Intake is the single scanner workflow.
- Status remains not frozen: local build/runtime validation and focused Kiro findings-closure verification are required.

### 2026-09-10 — Scanner Bridge SB-1/2/3 reconnect UX hardening

- Senior review confirmed the current findings-closure source already removes the manual Upload Documents `Scan Documents` action and handles stale local sessions; screenshots showing otherwise indicate an older/stale frontend runtime.
- Hardened the reconnect edge so an unreachable bridge shows the waiting scan as disconnected, and a missing session falls back to operator-safe “no longer active” wording rather than a raw technical not-found message.
- Scope remains SB-1/2/3 only; no durable restart recovery (SB-4) or LexaVault submission behavior (SB-5) was introduced.


## 2026-09-10 — Enterprise Scanner Intake end-to-end implementation authorized

- Accepted one scan job = one consolidated PDF semantics for high-throughput paper packages.
- Accepted startup-selectable `SIMULATOR` / `PHYSICAL` acquisition modes; simulator is QA/dev only and never automatic fallback.
- Accepted explicit Submit backed by durable local queue/recovery and asynchronous idempotent/reconcilable LexaVault delivery.
- Accepted common downstream Business Record/validation/processing/Manual Review/approval/export lifecycle with no scanner-specific processing pipeline.
- Accepted `SCANNER_INTAKE` source classification and immutable scanner provenance for reporting.
- Physical scanner adapter/certification and future Document Intelligence auto-population remain deferred.