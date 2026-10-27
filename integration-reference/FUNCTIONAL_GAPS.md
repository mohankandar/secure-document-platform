# Case File frontend integration — functional gap register

Baseline: production `main` at `1b69432`; approved prototype `984604a`.
Integration branch: `ui/lexavault-casefile-integration-review`.

Status legend: OPEN = functionality not demonstrated in prototype; REVIEW = owner decision needed; VERIFIED = parity proven in real runtime. Do not close entries on visual evidence alone. Never silently delete production functionality. No changes to `main` without explicit approval.

| ID | Severity | Production workflow | Prototype observation | Decision needed | Status |
| --- | --- | --- | --- | --- | --- |
| LV-FG-001 | High | Support Incidents list and incident detail (`/support/incidents`, `/support/incidents/:id`) | Compatibility routes and Support navigation added in `8136641`; legacy pages reused pending Case File shell runtime acceptance | Preserve as accessible Case File support surface or obtain explicit disposition before old UI removal | OPEN |
| LV-FG-002 | High | Technical issue detail and recovery (`/technical-issues/:id` or existing actual production route) | Existing `/technical-exceptions/:packageId` route included in Case File shell in `8136641`; runtime and deep links unverified | Map production route/actions and provide a working replacement | OPEN |
| LV-FG-003 | High | Manual fallback processing at `/packages/:packageId/manual-processing` | Existing manual fallback route now preserved in Case File shell in `8136641`; runtime and navigation still unverified | Integrate the workflow and preserve deep links | OPEN |
| LV-FG-004 | Medium | Deployment and tenant branding propagates to production shell via `BrandingProvider` | Prototype shell hardcodes LexaVault mark and does not use BrandingProvider | Wire effective branding into Case File shell without altering security semantics | OPEN |
| LV-FG-005 | Medium | Operational role with `TECHNICAL_EXCEPTION_VIEW` can access support incidents | Case File shell includes `TECHNICAL_EXCEPTION_VIEW` for support-only access in `8136641`; requires role-specific runtime test | Preserve support-only role entry and capability boundaries | OPEN |
| LV-FG-006 | Medium | Production integration environment and auth lifecycle | Case File prototype is mounted with demo data and simulated server | Replace demo transport with actual authenticated API; remove demo controls from production | OPEN |
| LV-FG-007 | Medium | Browser validation across Admin checkpoints A–E | Only partial screen recording; several controls never exercised in browser | Run real API positive/negative path and permission tests | OPEN |
| LV-FG-008 | Medium | UI framework packaging contract | Three known prototype packaging tests fail baseline | Reconcile after real frontend placement, without weakening guards | OPEN |

## Integration checkpoints

1. Preserve backend and `frontend/securedoc-ui` dependencies, security, existing routes and branding until replacement is functionally mapped.
2. Adapt Case File shell to production `CurrentUser`, real capabilities, real API client, OIDC session lifecycle, and branding.
3. Migrate Desk/Intake/Review, including fallback and technical/support deep links.
4. Migrate Reports and Admin A–E.
5. Verify capability denial, tenant isolation, operation safety, responsive layouts, and real API behavior. Remove old UI only once replacements have been verified.
6. Record each gap's resolution commit and runtime evidence before marking VERIFIED.

This is a ledger, not approval to omit or defer high-severity workflows from final cutover.

## Integration checkpoint — compatibility route staging (`8136641`)

The Case File shell now retains the existing Support Incidents list/detail, technical-exception detail, and manual-fallback React routes using existing production pages. Navigation for support-only capability was added, and legacy intake redirects are capability gated. This does **not** imply that the Case File shell is mounted in `App.tsx`, that these pages are styled or tested within the new layout, or that functional parity is VERIFIED. Keep LV-FG-001, 002, 003, and 005 OPEN pending build, runtime, negative-permission tests, and deep-link validation. Do not remove corresponding legacy routes.

## Integration checkpoint — opt-in shell (`a72ff55`, `5fb9b3f`)

The production `App.tsx` continues to handle OIDC callback, `/me` session verification, capability assignment, expiration and sign-out. An explicitly enabled `VITE_CASE_FILE_UI_ENABLED=true` build mounts the Case File shell only after a non-null authenticated `CurrentUser` is available. The flag is off by default so the legacy shell remains active. The Case File shell takes that authenticated principal as a prop rather than loading `/me` independently; tenant/deployment branding is sourced from the existing `BrandingProvider` and the sign-out control delegates to the existing App handler. No demo role controls are shown inside the Case File shell.

**Not verified:** TypeScript/build, actual OIDC browser flow, local-mode capabilities, CSS theme interactions, desktop/iPad screenshots, permission-denial and deep-link coverage, all Case File workflows and negative security tests. LV-FG-004 and 006 remain OPEN until runtime proof. Never enable this flag in production or delete legacy routes before acceptance.

## Integration checkpoint — deep-link continuity (`cbc8266`)

The opt-in Case File shell now maps legacy Administration (`/administration`, `/business-admin`, `/integrations`, `/authentication-admin`, `/platform-admin`) and Audit (`/audit`) URLs to corresponding Case File tabs based on the authenticated principal's capabilities; users without the necessary capability fall back to an authorized home route. Reactive Desk active-route styling now uses the router's location. Existing support, technical-exception and manual-processing routes remain retained.

**Verification gate:** No CI checks or PR workflow runs were reported for `0cfd173` when queried, and network access from the local execution container to GitHub is unavailable. Thus no claim of a clean TypeScript build, browser runtime, successful OIDC login or production parity is made. Validate `npm ci`, `npm run build`, `npm run test:auth`, `npm run test:ui-framework`, plus OIDC and capability-specific browser routes with the preview flag. Keep the legacy shell default and all gaps OPEN until proven.

## Runtime visual defect — 2026-10-25 (`16eae16`)

User's LOCAL-mode Desk screenshot showed the Case File structure and live package data, but missing intended color, borders, stage accents and contrast. Root cause traced to the exact approved Case File `--cf-*` variables being present in prototype `src/tokens.css` but absent from production frontend `src/tokens.css` and ported `src/studio/studio.css`. The original light/dark `.cf` token blocks have now been copied verbatim into the ported `studio.css`; they are scoped to the Case File subtree and do not modify legacy `:root` tokens. **Source fix committed; browser visual acceptance PENDING.** Follow-up: compare Desk plus each permitted role/screen against approved prototype; verify theme toggle and existing tenant branding, and investigate case labels displaying as 'Untitled case'. LOCAL role selector is not yet available within Case File shell; production OIDC role switching must remain prohibited.

## LOCAL role parity follow-up (`63f16ac`, `58d3cd5`, `f9143fe`)

The preview Case File header now displays a role selector only when the existing `App` runs in LOCAL security mode. It is wired to the existing `handleRoleChange` handler that resets capabilities and re-requests `/me`; OIDC mode does not render it. Runtime tests must confirm every `ROLE_OPTIONS` entry gets the correct effective capabilities and that no stale privileged navigation remains across transitions. No OIDC demo-role switching was introduced. This is an integration candidate, not VERIFIED parity.

## Canonical CSS contract correction (`3c3c68d`, `8c1d9f8`)

The approved prototype's Case File light/dark `--cf-*` token declarations were moved unchanged from `src/studio/studio.css` to the existing canonical `src/tokens.css`. This addresses the local `uiFrameworkContract.test.ts` failure requiring raw hex color declarations to reside only in `tokens.css`. Production legacy root tokens, test assertions, and backend were not modified. **Test rerun pending:** execute `npm run test:ui-framework` and `npm run build` locally; only mark PASS after actual execution. Full UI visual parity is separately pending.

## Needs attention — Document Processor checkpoint (`4dd0937`)

Source review found a real manual-fallback navigation defect: the Case File drawer invoked `startManualFallback` but then navigated to `/review?case=...` rather than the retained production `/packages/:packageId/manual-processing` workflow. Corrected route. Empty finding queues now say 'No open findings in the current queue' rather than falsely claiming 'Review decisions recorded'. Both are source fixes only; backend operation success, runtime navigation, role denial, failed-state eligibility, and manual redaction remain **UNVERIFIED**. Inspect the `FAILED` sample with a test-safe document; never infer reprocessing safety from `FAILED` alone. Legacy records without business identifiers display 'Untitled case' by current data mapping; verify `/api/v1/packages` and per-package `/api/v1` detail response before deciding whether to change labels or migrate data. Also investigate `needsYou` counting `PENDING_REVIEW`/`IN_REVIEW` even when current queue has no open findings and whether fallback-active packages are represented correctly. Preserve all existing tests and legacy pages.

## Deferred — Needs attention manual recovery and demo-data reset (2026-10-25)

**Decision: DEFER, do not fix now.** During LOCAL-mode testing, a failed sample case (`u9.pdf`, 9-byte FAILED document) displayed **Continue manually**. The backend rejected the request: “Manual processing is available only when sensitive-data analysis failed and the PDF remains safe to render and redact.” The UI must not offer manual fallback solely based on `FAILED` plus `MANUAL_REVIEW`; investigate authoritative backend eligibility, technical retry eligibility, and distinguish “Needs attention” from an actually executable “Needs you” action. Preserve the backend's fail-closed safety and authorization behavior. The earlier fix that navigates to `/packages/:packageId/manual-processing` after *successful* start remains in place; this eligibility gap is **OPEN / DEFERRED**, not verified or resolved.

**Future test-data plan (NOT EXECUTED):** After integration is ready, retire/reset disposable LOCAL test records and create a fresh, curated demo dataset consisting of valid test PDFs and representative success, review, approval, failure/recovery, scanner, and retention cases. Before any reset, inventory the environment and records, determine exactly which data and stored artifacts are disposable, preserve schemas/migrations/configuration/tenant identity and other required baseline state, arrange a backup or reproducible seed, and obtain explicit approval of the concrete destructive scope. Do not delete databases, stored documents, object storage, secrets, real records, or environments now. Revalidate manual-fallback eligibility with safe, intentionally constructed demo failure cases only.


## Approved direction — Administration redesign backlog (2026-10-26)

These are **approved requirements, not implemented changes**. Implement one item at a time on the review branch; do not silently change backend authorizations or merge to main. Separate role assignment from additive capability grants.

| ID | Priority | Item | Acceptance criteria | Status |
|---|---|---|---|---|
| LV-ADM-01 | P0 | Business Administrator navigation and least privilege | A user with **only** BUSINESS_ADMIN sees **Admin and Reports** as the primary navigation; **Desk, Intake, Review** are neither displayed nor accessible through direct routes/actions. Confirm reporting authorization is granted as intended; no implicit document intake/review grants. Negative capability tests and LOCAL/OIDC checks. | OPEN |
| LV-ADM-02 | P0 | Additive multi-role access | A user assigned BUSINESS_ADMIN **plus DOCUMENT_PROCESSOR or DOCUMENT_SUPERVISOR** keeps the union of *authorized* role capabilities: operational Desk/Intake/Review as appropriate **and** Business Admin/Reports. No role switching as a substitute for real multi-role assignments. Test backend enforcement, UI navigation, direct URLs and OIDC role claims, and preserve supervisor-specific capabilities. | OPEN |
| LV-ADM-03 | P0 | Three administrator personas / retire System Administrator | Business Admin, IT Admin, Platform Admin are the only user-facing admin personas. Inventory ADMIN/System Administrator role usages in backend, LOCAL roles, OIDC mappings, bootstrap/emergency recovery, migrations and tests; retire or remap safely after approval. Preserve internal recovery functionality, deny unauthorized cross-tenant actions. | OPEN |
| LV-ADM-04 | P1 | IT Admin boundaries and Platform Operations visibility | IT Admin owns intake sources, master delivery, derived delivery/routing, Sign-in; may **view** platform operations health where entitled but cannot mutate platform-only storage/licensing/branding controls. Platform Admin retains platform operations management; define exact capability matrix, positive/negative tests. | OPEN |
| LV-ADM-05 | P1 | Guided New Integration wizard and terminology | First choice **Receive documents** or **Send documents** (business document flow), then push listener / HTTP pull polling / watched folder / scanner, or master/derived delivery as applicable. Explain which system initiates the connection; internally set direction, acknowledgement, schedule defaults; never require users to infer HTTP polling is OUTBOUND while document flow is inbound. Show only relevant URL/auth/schedule/limits. Keep existing backend behavior and audit. | OPEN |
| LV-ADM-06 | P1 | Tenant business identifier contract across intake | Business Admin owns field definitions, required flags and validation. IT Admin source configuration displays required fields and provides source-to-field mappings where supported; reject missing/invalid mandatory identifiers safely. Audit whether mapping APIs exist; do not simulate or silently bypass policy. | OPEN |
| LV-ADM-07 | P1 | Watched-folder responsibility split | Tenant IT manages intake source integration status, observations and authorized scan; Platform Admin owns watcher/worker/lease/storage infrastructure health. Current watched-folder filesystem properties may not support tenant-editable per-source configuration—inventory backend gaps before exposing editable controls. | OPEN |
| LV-ADM-08 | P1 | HTTP polling and API push functional parity | Preserve existing inbound machine-to-machine push contract and outbound-initiated HTTP polling (documents arrive inbound), including Test Connection, Run Now, interval/daily schedule, idempotency, durable receipt and source acknowledgement. Test with controlled LOCAL feeder, no live production endpoints. | OPEN |
| LV-ADM-09 | P2 | Admin navigation and audit semantics | Audit visibility requires AUDIT_VIEW independently; no permission granted just for prototype visual parity. Confirm Business Admin, IT Admin, and Platform Admin tabs/routes follow actual capabilities. | OPEN |

**Agreed sequencing:** start with LV-ADM-01 and LV-ADM-02 (business admin least privilege and multi-role coexistence); then LV-ADM-03/04 authorization model; then LV-ADM-05/06 wizard and identifier mapping; then LV-ADM-07/08 intake operations; then LV-ADM-09 regression. Each item needs targeted regression evidence and explicit review before closure. No changes to roles, backend, or UI were made by this backlog-only commit.


## Role model follow-up — Document Viewer and IT-managed access (2026-10-26)

**Approved product direction; backlog only, not implemented.**

| ID | Priority | Requirement | Acceptance criteria | Status |
|---|---|---|---|---|
| LV-ADM-10 | P0 | Read-only Document Viewer role | Document Viewer can search, open and view **authorized** documents, document metadata, and permitted read-only activity in a focused Documents workspace. No Desk operational queues, Intake, Review, Admin or Reports by this role alone; no upload, download, export, redaction, approval, reject, reprocess, delete, or any other document-changing actions. Verify UI, API authorization and tenant/business scope. Log access consistent with audit policy. | OPEN |
| LV-ADM-11 | P0 | IT Administrator role assignment and removal | IT Admin can grant/revoke the predefined Document Viewer role to eligible users, subject to identity, tenant, authorization, non-escalation and audit safeguards. Broader assignment of Document Specialist/Supervisor and privileged admin roles **requires explicit policy design/review**; do not assume IT Admin may grant itself or others all roles. Preserve existing OIDC/bootstrap and role-to-capability semantics until reviewed. | OPEN |
| LV-ADM-12 | P0 | Explicit operations role boundary and navigation | Rename Document Processor to **Document Specialist** in user-facing language (pending compatibility review). Document Specialist: Desk, Intake, Review; not Admin/Reports. Document Supervisor: same primary navigation with additional final-approval capability (and any existing authorized supervisor operations), not Admin/Reports. Business Admin: Admin and Reports, not operational screens. Users with multiple roles get the union of *authorized* permissions. Confirm existing backend capability mapping before changing it. | OPEN |

**IT Admin scope discussion:** Integration/connection setup (push, HTTP pull/polling, watched folder, scanner), master and derived destinations, mapped identifiers as defined by Business Admin, identity/sign-in, scoped user role assignments, health/diagnostics read-only, monitoring and operational troubleshooting, integration-specific audit/configuration history. Platform-only controls remain Platform Admin. This scope is **for user review**, not permission approval or implementation authorization.


## Architecture clarification — redaction processor, not document repository (2026-10-26)

**User-confirmed product intent; design requirement, NOT a claim that current runtime already conforms.**

LexaVault's primary job is to **ingest documents, analyze/redact, run authorized review/approval as needed, and deliver completed output via IT-configured outbound integration**. LexaVault is **not** the system of record, permanent document vault or final repository.

- IT Administrator owns intake source connections (API push/listener, HTTP polling/pull, watched folder, scanner), authentication, schedules, field mapping, outbound/master/derived delivery configurations.
- Filesystem/local document storage for tests is **LOCAL-profile only**; do not position this as a production document-repository feature.
- Remove Storage Management as a normal Platform Operations user-facing section. Platform Admin owns runtime health, workers/queues, diagnostics and controlled infrastructure recovery; IT owns integration endpoints.
- Before implementation, audit runtime staging/transient persistence, encryption, cleanup, retries, durable destination acknowledgements and failure recovery. Necessary temporary processing persistence must not become a permanent repository; do not delete documents based on unverified delivery.
- Review master-delivery terminology and descriptions that imply permanent vault storage.
- **LV-ADM-13 OPEN:** align administration and operational screens / deployment profiles with redaction-and-forward architecture, without breaking in-flight processing or audit guarantees.


## System Administrator source inspection — first-pass findings (2026-10-26)

**Review only; no executable code changed.** Verified against ProductRole.java, ProductAuthorizationPolicy.java, RoleAuthorizationInterceptor.java, OidcRoleMapper.java, OidcKnownGoodRecovery.java, uiAuthorization.ts, AUTHORIZATION.md and ProductAuthorizationPolicyTest.java.

- `ADMIN` is a canonical role and returns `EnumSet.allOf(UserCapability.class)`; it is **not** equivalent to IT Administrator. `RoleAuthorizationInterceptor` separately short-circuits `@RequireRole` for `ADMIN`. Do not map `ADMIN` to `TENANT_IT_ADMIN` or delete its checks without endpoint inventory and permission tests.
- `TENANT_IT_ADMIN` grants integration/authentication administration, technical retries and holds, audit, dashboard and reporting, but **not** document intake or approvals; `PLATFORM_OPERATIONS_ADMIN` grants platform operations/diagnostics, technical retry/hold and currently integration management, but not tenant sign-in management. Realignment is needed for new ownership boundaries.
- `BUSINESS_ADMIN` **currently inherits all processor+supervisor document capabilities**. This contradicts approved Admin+Reports-only behavior.
- `DOCUMENT_PROCESSOR` **currently has `PACKAGE_APPROVE_DELIVERY`**; approved target distinguishes Supervisor approval. Existing tests expressly assert current behavior, so change tests and implementation together.
- `REPORT_VIEWER` is an existing role distinct from requested document-only `DOCUMENT_VIEWER`. Preserve intentionally until a decision on report-viewer need.
- `ADMIN` appears in frontend LOCAL role selector as System Administrator (Internal), in canonical backend enum and documented OIDC-role mapping. Identity role mapping may grant it only from configured external claims; audit actual deployments before removal.
- OIDC emergency known-good recovery is a guarded, disabled-by-default startup operation using explicit IDs/version/replay prevention, not evidence that an interactive System Administrator must remain. Preserve recovery path separately.
- **Next gate:** repo-wide controller `@RequireRole` and `@RequireCapability` inventory, LOCAL/OIDC configuration and bootstrap assignments, user-management feasibility, migration plan for existing role holders, negative tenant tests, and actual runtime validation. This is a first-pass source inspection, not completed global search.

**Proposed sequence:** freeze desired authorization matrix; explicitly scope IT vs Platform responsibilities; inventory every `ADMIN` bypass and role mapping; implement capability-safe navigation/backend changes in small review checkpoints; migrate assignments; remove interactive `ADMIN` exposure only after negative-path regression. No automatic transfer of `ADMIN` rights to IT Admin.


## Phase 1 Checkpoint A — partial backend policy implementation (2026-10-26)

**Source changes only; Maven tests and LOCAL/OIDC browser validation NOT RUN. Do not mark LV-ADM-01, 02, 12 complete.**

- Commit `8ee8c9a`: ProductAuthorizationPolicy now grants BUSINESS_ADMIN policy/identifier configuration plus report read/export, **not** inherited document processor/supervisor capabilities; DOCUMENT_PROCESSOR retains Desk/Intake/Review but no longer holds PACKAGE_APPROVE_DELIVERY; DOCUMENT_SUPERVISOR gains approval and retains processor capabilities plus existing reprocess/remove abilities.
- Commit `1ac5538`: ProductAuthorizationPolicyTest updated for least-privilege separation and additive BUSINESS_ADMIN+DOCUMENT_PROCESSOR/SUPERVISOR combined roles.
- Commit `129612d`: docs/product/AUTHORIZATION.md capability table and semantics updated.
- UI navigation is currently gated by backend-supplied capabilities, so BUSINESS_ADMIN-only should no longer be offered Desk/Intake/Review after a successful backend restart, but **unverified**. Other role-specific controllers may use `@RequireRole`; audit before declaring effective permission boundaries.
- **Known remaining gaps:** no DOCUMENT_VIEWER backend role/capabilities/workspace; no IT role-grant UI or privileged-role safeguards; no System Administrator migration; no frontend label rename; legacy role mappings and production runtime acceptance outstanding.
- **Verification:** run focused `ProductAuthorizationPolicyTest`, other authorization contract tests, frontend auth tests and live LOCAL role matrix, then OIDC multi-role and endpoint-denial tests. Test existing supervisor approval end-to-end. Keep this checkpoint OPEN until verified.


## Phase 1 Checkpoint A — user LOCAL acceptance (2026-10-26)

**User reports PASS for all target browser checks**, including Business Administrator (Admin/Reports only; no Desk, Intake, Review, IT or Platform controls), Document Processor (Desk/Intake/Review, no Admin/Reports), Document Supervisor (Desk/Intake/Review, no Admin/Reports), and BOTH final-delivery approval cases (Processor denied; Supervisor allowed when business approval policy was enabled). User explicitly approved closing this checkpoint and proceeding.

**Status:** Checkpoint A **USER-ACCEPTED / CLOSED** for the tested LOCAL role/navigation/approval scenarios. `LV-ADM-01` and the relevant Processor/Supervisor portions of `LV-ADM-12` pass reported LOCAL acceptance; other scope under those IDs and `LV-ADM-02` combined-role OIDC/API-negative-path testing remain separately open where not evidenced.

**Build distinction:** focused ProductAuthorizationPolicyTest 10/10 PASS; a broader build previously failed on a stale capability interceptor test and was fixed by commit `6277b37`. No subsequent full-build PASS output is present in this conversation; **do not claim full-suite PASS** or extensive OIDC/tenant isolation verification. The user's runtime approval tests do not substitute for those security regressions.

**Next checkpoint B:** implement a distinct strictly read-only Document Viewer role, scoped search/view APIs, viewer-only workspace, backend denial on mutation/download/export, tenant/business isolation, and tests. Preserve REPORT_VIEWER as separate until explicitly retired. No broad DB changes or permanent document storage feature.


## User correction — NO general Document Viewer (2026-10-26)

**Supersedes the proposed Document Viewer direction in LV-ADM-10, LV-ADM-11 and Checkpoint B. These entries are superseded, not implementation-authorized.**

- LexaVault is a **redaction-only processor**, not a document repository or business document viewing product.
- **Do not create a DOCUMENT_VIEWER role, a general read-only Documents workspace, a generic search/open-PDF experience, or IT-managed assignments of such a role.**
- Document processing personnel may see **only the restricted pages/previews necessary for their authorized redaction/review tasks**. Exact visibility rules for unredacted regions and page-level scope require a security review. Do not broaden current visibility or infer that whole originals are readable.
- Document Supervisor inherits only appropriate processing/review previews where necessary; Business, IT and Platform administrators do not gain document viewing by administrative role alone.
- After authorized redaction/review, deliver through configured outbound integration, subject to policy; temporary working copies must follow lifecycle/cleanup controls.
- **Full build status:** User confirmed the build was successful after the interceptor test correction; no log was provided in this turn. Record as **USER-REPORTED FULL BUILD PASS** (not independently inspected).
- **Next recommended Checkpoint B:** audit and harden existing page-scoped review/preview and download API permissions, then proceed with IT Admin redesign; do not implement a generic document viewer. Review whether current frontend actually exposes unrestricted case PDFs or downloads.

**Backlog disposition:** LV-ADM-10 (general Document Viewer) is CANCELLED/SUPERSEDED; document preview restrictions remain a security requirement. LV-ADM-11's Document Viewer assignment portion is CANCELLED/SUPERSEDED; future role assignment for existing operational roles requires separate approval.


## No manual document downloads — inspection and partial fix (2026-10-26)

**User requirement: No LexaVault user may download original or redacted documents. Authorized processor/supervisor preview remains page-scoped for review. System-to-system outbound delivery is separate and must continue to work.**

**Confirmed issue:** `ArtifactDownloadController.GET /api/v1/documents/{documentId}/artifacts/redacted/content` previously allowed any `DASHBOARD_ACCESS` user and returned PDF bytes with Content-Disposition attachment. Case File `CaseDrawer.tsx` also showed a "Redacted" download button calling this API.

**Source fixes on review branch:**
- `1dd6715`, `5257abe`: remove the Case File drawer redacted download button/handler and obsolete import.
- `e73e369`: retain legacy URL only as explicit AccessDeniedException, with no artifact fetching, to fail closed even for direct requests.
- `9bc0080`: targeted unit regression expects denial and unchanged legacy route.
- Review preview endpoint `GET /api/v1/review-items/{findingId}/page-preview` returns PNG of one relevant page, gated by MANUAL_REVIEW and no-store. This is a distinct preview path, not a full-PDF download, but page-content sensitivity and any other preview/full-content APIs still need broader review.

**Status: PARTIAL FIX / UNVERIFIED.** Run build and targeted test; inspect all legacy UI pages, standalone download APIs, browser preview/save affordances, and alternate artifact endpoints. Do not claim repository-wide no-download security acceptance yet. No storage, reporting exports, or automated integration deliveries were intentionally changed by this patch.


## Batched IT Administration UX candidate (2026-10-26)

**Code changes source-committed on review branch, NOT built/runtime-verified.** User requested batching tests instead of pausing after each small correction.

- `295f27e`, `b08c763`: integration editor now explains **business document direction** ("Receive documents" versus "Send processed documents") and identifies which side initiates communication; provider dropdown says **Transfer method**, with business-oriented options ("External system sends (API push)", "LexaVault retrieves (HTTP polling)", watched folder, scanner, outbound HTTP, file share). The ambiguous editable technical Direction dropdown is removed. Provider-selection logic still sets technical INBOUND/OUTBOUND and acknowledgement/polling values in existing backend request shape.
- Saved connection list now labels "Documents in" / "Documents out" rather than naked INBOUND/OUTBOUND.
- This is a **minimal guided-choice foundation**, not the approved multi-step wizard. Full guided wizard, field mappings, role management and platform restructuring remain OPEN.
- Batch acceptance: full frontend TypeScript/build, focused API download-denial test and Maven full build; LOCAL IT Admin verify Push API and HTTP Polling create/edit defaults and schedule, plus Master/Derived delivery, failure handling and no regressions to outbound workflow; processor verify no "Redacted" download button and direct artifact request denied. No real external connection should be enabled merely for testing.


## Batch 1 combined LOCAL acceptance — user report (2026-10-26)

**User-reported 26 PASS, 0 FAIL, 3 N/A.** Build: frontend TypeScript PASS, frontend production build PASS, backend clean install PASS, startup PASS. Document Processor: navigation, no download button, restricted preview and normal intake/processing PASS. Supervisor: navigation, conditional final approval, no download PASS. Business Administrator: Admin/Reports-only, policy/fields, no IT/platform controls and final approval policy PASS. IT Integrations: screen, transfer-method language, API push, polling, master delivery, derived delivery, connections retained and sign-in/health visibility PASS. Security: direct artifact-download denial, unauthorized redirects and authorized review preview PASS.

**Not tested (N/A, not PASS):** watched-folder runtime regression, outbound delivery runtime regression, and browser/server error-log inspection. Retain these as targeted later acceptance tests. No defects were reported in this batch.

**Status:** Batch 1 **USER ACCEPTED for tested scope**; no claim of complete end-to-end regression or exhaustive API/content-path security audit. Next planned batch is guided IT Integration configuration and Platform Operations scope alignment; System Administrator retirement and user role-grant flows remain security-gated.


## Batch 2 results and triage — 2026-10-26

User reports backend clean install, frontend TypeScript/build and LOCAL startup PASS. IT Admin navigation (except operational Desk restriction) PASS. API push, HTTP polling, watched-folder and scanner configuration fields PASS. **Two FAIL:** (1) IT Admin unexpectedly has Desk/operational document-access capability; (2) create wizard does not explicitly distinguish Master Delivery from Derived Delivery after choosing Send. Other checks covering outbound delivery, field validation, roles, monitoring, download security and watched folder are PENDING, not accepted.

**Remediation:** commits `85aa63e`, `96c9db4` remove `DASHBOARD_ACCESS` from `TENANT_IT_ADMIN` and update focused policy regression. **Not yet build/runtime verified.** Confirm IT Admin can still access Integrations, Sign-in, Platform Health and technical incident views; role-specific API calls should deny Desk/package listing.

**Remaining defect:** guided New Connection flow is still a single form scoped to an existing group, rather than Receive/Send followed by Master/Derived selection. This is an **OPEN implementation gap**, not a completed wizard. Preserve old configuration options and backend enum semantics when implementing. Require actual branch code and browser acceptance before closing.

**Next combined test round:** build after current edits, confirm IT Admin Desk/direct package API denied without damaging management access, and validate completed Send wizard once implemented. Preserve all earlier PENDING/N/A regressions.


## Batch 2 guided Integration wizard implementation candidate (2026-10-26)

**Commit `6c73b51` — SOURCE IMPLEMENTED, NOT BUILD/RUNTIME VERIFIED.** New-connection creation in Case File IT Integrations now proceeds:
1. Choose **Receive documents** or **Send documents** (business document direction, not HTTP-request direction).
2. Receive: API Push, HTTP Polling, Watched Folder, Scanner. Send: **Master Delivery** or **Derived Delivery**, distinguished explicitly.
3. Configure source/destination-specific fields and save. Back, Reset, and Cancel are available; existing connection edit remains direct.

The wizard uses existing `draftForPurpose` / `applyProvider` / `toPayload` paths; does not modify backend integration APIs, stored connections or actual delivery processing. Existing connection-group filter does not prevent creating a Send connection from Intake group; saved result moves to its destination group.

**Pending:** frontend TypeScript/build, browser wizard flow and edit/regression, backend IT Desk denial from `85aa63e`, and the already listed PENDING Batch 2 checks. Do not mark Batch 2 PASS before tests.

## Batch 2 targeted retest and R2 recording — accepted (2026-10-26)

**User reported 23/23 PASS**, covering 4 build/startup checks, 6 IT Admin access/redirect checks, 5 Receive wizard checks, 5 Send wizard checks and 3 existing-connection checks; no issues noted. Submitted video `IT_admin_R2.mp4` (~68 sec) was sampled for visual review: IT Admin Integrations list, New Connection create panels, Master/Derived destination groups, Sign-in and Platform Operations screens are visible. This is **user acceptance of the targeted Batch 2 correction**, not confirmation of successful creation/delivery or exhaustive security review.

**Status:** Batch 2 targeted scope USER ACCEPTED. Previously untested backend identifier enforcement, actual end-to-end polling/delivery, watched folder runtime regression, no-download API regression in other routes, System Administrator retirement, full role-assignment redesign and Platform Operations architecture alignment remain separately OPEN. Do not assume a checked wizard option was saved or exercised against a real destination. No main merge approved.


## Batch 2 R2 targeted acceptance + recorded UX observations (2026-10-26)

User reports **23/23 PASS** for targeted R2 checks: backend/frontend TypeScript/production builds and LOCAL startup; IT Admin restricted navigation (no Desk, Intake, Review; direct URL redirects; integration/sign-in/health access); guided Receive selection, four intake methods, conditional push/polling fields and Back/Reset/Cancel; guided Send Master/Derived selection with proper configurations; existing integrations visible/editable/preserved. No user-reported failures.

R2 attached screen recording (~67.6 seconds) independently confirms IT Admin nav has no Desk/Intake/Review, Step 1 Receive/Send, Step 2 Master vs Derived, and existing connection list; it **does not** establish successful saved creation, outbound delivery, API security denial, permission enforcement of Platform Health read-only controls, or all 23 individual checks. Mark targeted Batch 2 R2 **USER ACCEPTED**, keep wider end-to-end and permission tests open.

**UX follow-up:** Step 2 Master/Derived option buttons are densely packed on one horizontal row; consider responsive stacked selection cards with descriptions and clear selected/focus affordances. Top navigation still includes Reports and Support for IT Admin in R2 recording; role policy currently grants report and technical issue access, but the agreed target scope did not explicitly settle those top-level entries. Review before removing permissions; do not silently change. Platform Health transient spinner seen during navigation; no evidence of sustained loading failure.

**Open regression scope carried forward:** watched folder, real HTTP polling Run Now, isolated outbound, logs, field enforcement, cross-role/OIDC and alternate file-response security paths. No claim of completed end-to-end validation.


## Platform Operations R1 — read-only separation (2026-10-26)

**Source changes on review branch; NOT yet build or LOCAL verified.**

- `6154bb8`: IT Admin health overview is refresh/read-only; active health-check and watched-folder scan buttons are hidden from the Platform Health surface unless PLATFORM_OPERATIONS is held. Only Platform Admin sees infrastructure sections. "Processing storage" is clarified as **Temporary processing infrastructure**, not an end-user repository.
- `3521290`: `POST /api/v1/platform-admin/health-check` now requires PLATFORM_OPERATIONS rather than TECHNICAL_ADMIN_VIEW. `GET /overview` remains readable with TECHNICAL_ADMIN_VIEW. API-boundary test added in `0b25f6b`.
- `82a1568`: remove "Open case" navigation from technical-incident entries in Platform Health; IT/Platform administrators do not gain document review access merely through admin roles.
- Watched-folder manual scan API remains TENANT_INTEGRATION_MANAGE, intentionally under IT integration authority, but removed from the read-only Health view for IT. Determine proper IT Integrations scan-control placement in future step before relocating UI.
- **Unresolved structural alignment:** backend and platform admin still have temporary processing-storage configuration whose production-vs-LOCAL lifecycle must be audited; do not delete it without safe delivery/retry cleanup review. Reports/Support IT role visibility and System Administrator retirement remain separately pending.
- **Acceptance next:** backend clean build / platform-health contract test, frontend TypeScript/build, IT Admin health only refresh/no operations/no case link, direct POST health-check forbidden for IT Admin, Platform Admin active health-check and infrastructure sections available, no document access, IT integration setup/polling unaffected.

Do not close Platform Operations checkpoint until these tests pass.


## User-approved IT Intake / Platform Operations redesign requirements — 2026-10-26

**Approved design direction; implementation pending. Preserve multi-node intake guarantees throughout.**

### Multi-node watched-folder intake — MUST PRESERVE
- Watched-folder connection ownership, configuration, operational scan and outcomes belong under **IT Administration → Integrations → Intake Sources**; retain dedicated source status, errors and scan controls.
- **Do not simplify watched-folder intake into a single-node watcher.** Preserve lease ownership, node identity/role, coordinated scheduled/manual scans, fencing token/generation, lease expiry, takeover/failover handling, idempotent triggers and duplicate prevention, per-node health, safety during failover and existing audit behavior. The current multi-node coordination logic/contracts must be inspected before changing controls, routing or scheduling.
- Platform Administrator owns **cross-node operational health and distributed coordination diagnostics** (lease/fencing/takeover/worker readiness) without taking over business source connection configuration.
- Avoid two independently competing "Scan now" actions on separate admin screens. Correctly enforce execution authority server-side; no duplicate or uncoordinated scan path.
- Respect LOCAL vs multi-node production safety, including storage/provider validation and fail-closed behavior.

### Three explicit findings from PA_R1 recording — MUST FIX/TRACK
1. **Platform Admin operational Desk access:** `PLATFORM_OPERATIONS_ADMIN` currently receives `DASHBOARD_ACCESS`; audit and remove unintended Desk and document access at the backend, with frontend navigation and endpoint regression tests. No generic viewer or downloads.
2. **Health status ambiguity:** overview counts dependencies requiring attention while individual lines can say "Configured"/"Healthy." Distinguish configured, reachable, operational readiness and degraded/down states with accurate labels; don't camouflage failures or invent health.
3. **Storage/delivery terminology and model:** remove repository/archive implication; explain LOCAL filesystem delivery as LOCAL/demo-only where relevant and production processing storage as secure *temporary* lifecycle infrastructure; preserve underlying retries, acknowledgements, cleanup, multi-node validation and routing semantics.

### Approved information architecture
- **IT Admin:** Integrations (Intake sources incl. watched folder/scanner/push/polling; master/derived destinations; routing; monitoring/manual actions), Sign-in, user/access design later, technical troubleshooting, read-only health.
- **Platform Admin:** System Overview, Runtime & Recovery (backoff/retries/AI failover), Processing Infrastructure (readiness, validation, node checks, safe provider activation, cleanup visibility), Licensing, Branding, Product Telemetry, Advanced Diagnostics.
- Relocate watched-folder operational summary out of generic Platform Overview while keeping **multi-node coordination diagnostics** under Platform; eliminate generic "Resilience and integrations" overview card by moving runtime policies to Runtime & Recovery, delivery to IT Integrations and identity state to Sign-in.
- Redesign temporary processing-storage UI progressively; preserve all existing provider/tenant override/test/activate/retire guardrails and avoid functional regressions. Audit lifecycle cleanup before introducing controls or removing backend infrastructure.
- Move in reviewed checkpoints with one consolidated user acceptance round. Do not change `main`, previous integration branch, OIDC semantics, or role grants without explicit scoped review.

**Status: APPROVED DESIGN / OPEN IMPLEMENTATION**. Acceptance must explicitly exercise 2+ nodes, lease takeover/fencing, duplicate avoidance and manual scan coordination where a safe test environment is available; mark those tests PENDING otherwise, never PASS by inference.


## Approved admin responsibility matrix audit + System Administrator retirement (2026-10-26)

**Status: audit complete for central role mapping and admin navigation; implementation pending. User explicitly approved retirement of ordinary System Administrator, NOT deletion of guarded recovery mechanisms.**

| Matrix requirement | Current code | Disposition |
| --- | --- | --- |
| Business Admin business policy / identifiers and business reports | `BUSINESS_ADMIN` scoped grants | KEEP, recheck runtime |
| IT integrations, OIDC, technical monitoring | `TENANT_IT_ADMIN` grants integration, identity, diagnostics and audit | KEEP |
| IT user/access management | No approved privilege-safe tenant role-grant architecture established | OPEN SECURITY DESIGN; self-elevation/role-ceiling/two-party approval decisions |
| IT not business reporting by default | IT currently inherits `REPORT_READ_CAPABILITIES` and `REPORT_EXPORT` | OPEN role cleanup, audit callers and report routes |
| Platform not business reporting by default | Platform currently inherits report view/export | OPEN role cleanup |
| Platform not manage tenant integrations by default | Platform currently receives `TENANT_INTEGRATION_MANAGE` | OPEN role cleanup; identify endpoint dependencies before removal |
| Platform controls vs IT monitoring | `PLATFORM_OPERATIONS` platform controls, `TECHNICAL_ADMIN_VIEW` shared read; backend active health-check corrected | PARTIAL PASS, runtime recheck |
| Platform no Desk | standalone Platform role `DASHBOARD_ACCESS` removed at `9de0a9c` | SOURCE FIX, runtime pending |
| Intake/scan multi-node split | IT manual scan relocated, Platform lease/fencing diagnostics retained | SOURCE FIX, 2+ node verification pending |
| Support and technical retry/hold | Both IT and Platform have technical retry/hold; Support navigation remains | OPEN differentiated scope and endpoint review |
| No document downloads | Legacy artifact-download URL denied; role preview constrained | PARTIAL; alternate content endpoints still require audit |
| System Administrator user-facing retirement | `uiAuthorization.ROLE_OPTIONS` includes `ADMIN` | OPEN remove after safety checks |
| System Administrator unrestricted capability grant | `ProductAuthorizationPolicy` ADMIN gets EnumSet.allOf(UserCapability.class) | BLOCKING security cleanup |
| System Administrator `@RequireRole` bypass | `RoleAuthorizationInterceptor` ADMIN bypass | BLOCKING security cleanup |
| OIDC external ADMIN mapping | `ProductRole.ADMIN` is recognized by `OidcRoleMapper` through `SecurityProperties.oidc.roleMappings` | BLOCKING migration; discover actual config/tenant assignments |
| Local test/admin bootstrap and guarded known-good recovery | LOCAL X-Actor-Roles and recovery paths may rely on ADMIN | BLOCKING audit; preserve break-glass, lockout avoidance and operational rollback |
| Additive roles | Policy unions roles | KEEP with tests; no automatic role inference |
| Report Viewer | remains distinct | KEEP unless separately retired |

**Retirement safety sequence**
1. Inventory `ADMIN` occurrences across config, identity mappings, bootstrap/recovery, `@RequireRole` routes, tests/docs/LOCAL launchers; enumerate live OIDC mappings and affected principals before rollout. Never remap ADMIN blindly to IT or Platform.
2. Establish dedicated least-privilege role grants for each previously ADMIN-only operation and negative tests for IT, Platform, Business, processors and unknown roles. Separate emergency identity recovery from ordinary product-role grants.
3. Migrate external role mappings and LOCAL defaults; verify working IT and Platform administration and recovery with a non-ADMIN principal.
4. Remove ADMIN from user-facing role selection and ordinary OIDC role acceptance, delete unrestricted capability grant and interceptor bypass after dependency migration; deny stale ADMIN tokens/roles fail-closed.
5. Run full tests and runtime OIDC/LOCAL regression including role unions, identity activation/rollback, bootstrap/known-good recovery, integrations, watched-folder multi-node and platform maintenance. No production merge or rollout until checkpoint accepted.

**Do not treat removal from dropdown as security retirement.** No implementation changes yet in this audit.


## R1 scoped grants implemented; R2 retirement prerequisites (2026-10-26)

- `b91315a`: remove default business report view/export from standalone `TENANT_IT_ADMIN` and `PLATFORM_OPERATIONS_ADMIN`; remove tenant integration-management capability from standalone Platform Admin. Business Admin and Report Viewer reporting unaffected, and legitimately combined roles remain additive.
- `13b604f`: update central authorization tests with negative checks for reporting and Platform integration administration.
- **Pending local build/test and runtime validation.** Expected IT Admin: Integrations, Sign-in, Audit, Support, read-only Platform Overview; no Reports. Expected Platform Admin: Platform Operations, technical incidents/Audit/Support; no Reports or tenant Integrations. Scan Now remains IT-owned under TENANT_INTEGRATION_MANAGE. Existing Platform watched-folder lease and fencing remains read-only.
- **R2 inventory started:** Ordinary `ADMIN` is still recognized by ProductRole, OIDC Role Mapper and LOCAL role selector; gives all UserCapability enum values and bypasses @RequireRole via RoleAuthorizationInterceptor. Must inspect deployment roleMappings, ADMIN-only @RequireRole actions, bootstrap + OIDC known-good recovery before removing. **Not retired**, do not revoke existing production assignments until a tested migration exists. No silent ADMIN-to-IT or ADMIN-to-Platform remap.
- **Do not prematurely close:** Technical retry/hold API ownership, IT users-and-access privilege ceilings, System Administrator migration, and multi-node cluster tests remain OPEN.


## R3 ordinary System Administrator retirement — code candidate (2026-10-26)

**Review branch source only; UNBUILT/UNVERIFIED. User requested batching tests until meaningful checkpoint.**
- `2bf085e`: eliminated unrestricted `ADMIN` capability grant from `ProductAuthorizationPolicy`.
- `3718f42` / `432538c`: removed automatic @RequireRole ADMIN bypass and fail-closed for a stale `ADMIN` principal even if endpoint annotation accidentally names it.
- `ffe6c85`: retired `ADMIN` enum value from `ProductRole`, so `OidcRoleMapper` no longer maps external ADMIN claims and LOCAL parser rejects X-Actor-Roles ADMIN.
- `495f2ac`: removed user-facing LOCAL System Administrator selector.
- `41342ca`, `038fc74`, `1b5a941`: negative unit/security tests for retired role and LOCAL/OIDC trust-boundary rejection.
- `e597842`: removed obsolete `ADMIN` OIDC property from application.yml.
- **Important compatibility gate:** inspect all compile references to `ProductRole.ADMIN`, all `@RequireRole` annotations naming ADMIN, generated configs and legacy launch scripts; determine migration of deployed admin-only OIDC groups to explicit approved functional roles. Avoid remapping retired ADMIN group implicitly to one or many privileged roles.
- **Guarded break-glass remains:** `OidcKnownGoodRecovery` uses separate disabled-by-default startup recovery with tenant/connection/version/request-id exact inputs and DB known-good recovery, not product ADMIN role. Must verify preserved behavior in full tests.
- **No runtime testing or production migration done**. Do not claim complete retirement until LOCAL/OIDC negative tests, ordinary IT/Platform/Business positive paths, identity recovery, and live deployment migration reviewed. Do not merge to main without explicit approval.
- R4 Users & Access with privilege ceilings and audit remains OPEN.


## P0 security retirement handoff (2026-10-26)

- Source authority changes committed: `ADMIN` no longer receives capabilities; no unrestricted interceptor bypass; deleted from canonical identity enum and UI role selector; OIDC `ADMIN` mapping deleted from shipped application.yml. A legacy `ADMIN` group presented *alone* fails identity mapping; alongside a legitimate mapped group it conveys no additional grants.
- Added `f8b9c50` OIDC regression cases for all three administrator roles and legacy `ADMIN` group combined with legitimate IT admin. Existing tests cover LOCAL rejection, legacy OIDC-only rejection, absent default admin, and additive capability checks.
- Disabled-by-default `OidcKnownGoodRecovery` is an independent one-shot startup hook backed by `recoverKnownGood`, not `ProductRole.ADMIN`; no changes made to it.
- **Verification still required before declaring P0 COMPLETE:** run `git grep -n -E 'ProductRole[.]ADMIN|hasRole\\("ADMIN"\\)|@RequireRole|SECUREDOC_SECURITY_ROLE_ADMIN|System Administrator' -- '*.java' '*.yml' '*.yaml' '*.tsx' '*.ps1'`, inspect each live use. Run full `Build-LexaVault-All.ps1` and targeted security/identity tests. The repository cannot be locally compiled from connected GitHub reads in this session.
- **Deployment-specific rollout gate (cannot be answered from source code):** enumerate existing user/group memberships and deployment overrides for retired ADMIN, assign explicitly authorized business/IT/platform roles in the IdP, ensure OIDC bootstrap/known-good recovery can be invoked independently, and verify at least one nonlegacy privileged operator in each required scope *before* enabling candidate in a real tenant. No automatic conversion of old ADMIN group. Remove obsolete `SECUREDOC_SECURITY_ROLE_ADMIN` deployment secret/env entry.
- **Do not merge/deploy** without verifying retired-role denial and identity known-good rollback against configured OIDC. R4 Users & Access remains separate.


## P1 checkpoint — scoped incident recovery and Users & Access architecture (2026-10-26)
- `5bc00ff`: remove `TECHNICAL_RETRY` and `TECHNICAL_HOLD_MANAGE` from standalone `PLATFORM_OPERATIONS_ADMIN`. IT Admin retains tenant-scoped incident REPROCESS, investigation hold/release/resolution. Platform retains technical exception viewing, diagnostics and infrastructure operations. Multi-role assignments still additive. Back-end mutation endpoints already require specific technical capabilities.
- `cefc4d7`: negative assertions prevent Platform-only technical retry and hold; IT-positive checks already present.
- Existing `SupportIncidentController` permits Platform cross-tenant incident **search/detail** through `PLATFORM_OPERATIONS`; cross-tenant **action** permission now absent from Platform-only capabilities. If Platform combines IT+Platform roles, cross-tenant action remains possible through additive capabilities; needs explicit cross-tenant mutation guard or dedicated policy before treating this fully resolved.
- Users & Access: current external OIDC role map derives authorization from verified IdP groups; LOCAL mode uses development headers. No authoritative tenant user-role assignment persistence, IdP provisioning lifecycle, scope-limited role grant authority, or change/audit contract was established by the reviewed source. **Do not fake role assignment with a local UI state or an untrusted table; no new management endpoints/UI were added.** Design implementation must choose IdP-backed delegated role assignment vs explicit LexaVault role-binding authority with revocation/session freshness and immutable audit. Required security: tenant isolation, role ceiling, no self-elevation, no grant of Platform role by tenant IT, actor/subject separation, idempotent writes, dual approval for privileged grants if policy adopted, rollback and token caching semantics. Preserve bootstrap and known-good recovery.
- Pending code: endpoint-level technical incident permission tests; cross-tenant mutation guard when dual roles; Users & Access trust-boundary design and implementation; user navigation review; P0 full build/identity regression.
- **Status: partial P1 implementation, source unbuilt and unverified; do not merge/deploy.** User prefers one later consolidated test checkpoint.


## P1 Users & Access authorization foundation — unverified candidate (2026-10-26)
- `cb50ed7`: new fail-closed `DelegatedRoleGrantPolicy` for a future user-role assignment command; requires Tenant IT role, separate actor/target identities, same tenant, and permits only DOCUMENT_PROCESSOR and REPORT_VIEWER under this conservative provisional policy. Explicitly denies ADMIN, Platform Admin, IT Admin, Business Admin, and Document Supervisor grants. This policy **is not yet invoked by an endpoint**, does not provision IdP groups, and is not a user-management implementation.
- `b1bf7ee`: unit tests for basic delegation, tenant isolation, self-grants, privileged-role denial, and null parameters.
- **Architecture decision still needed:** tenant role source of truth. Existing product OIDC identity is derived from verified IdP groups; no authoritative user-role CRUD/provisioning implementation was found. Prior to user-facing assignment: agree on delegated IdP API vs versioned application role bindings (with authoritative intersection/override, lifecycle, revocation, tenant identity subject, audit), and specify privileged grant approval. Do not present role management as working until provision and read-back are real.
- Build not run after these commits; batch testing by user preference. No main merge.


## P1 LOCAL-first Users & Access simulation candidate (2026-10-26)

**SOURCE IMPLEMENTED, NOT BUILT OR RUNTIME VERIFIED.**
- `035-local-demo-users.xml`: tenant+actor-keyed persisted LOCAL demo identities, one low-privilege role each; migration included in master; do not confuse with enterprise identity store.
- `LocalDemoUserStore` and `LocalUsersController`: wired only when `securedoc.security.mode=LOCAL`; IT-authenticated LIST and scoped PUT; validates against `DelegatedRoleGrantPolicy` (no self-grants, no privileged grants), records `CAPABILITY_CHANGED` audit, update transaction. API not exposed in OIDC.
- `RequestContextResolver`: LOCAL only, reads persisted role for matching demo actor; if assigned, this takes precedence over `X-Actor-Roles`. OIDC resolution and service integration principals unchanged. Existing LOCAL role-header fallback remains for developer/legacy demo actors not registered in the simulated store. Thus LOCAL is a simulated test environment, not a production-grade security boundary.
- Frontend: `/admin/users` LOCAL-only IT area; create/list low-privilege simulated users, switch actor via session-scoped selected UUID, and exit back to default demo actor via topbar control. Role selector hidden for selected persisted demo actors; API header actor ID switches while backend remains authority for assigned roles.
- Added test in `RequestContextResolverSecurityTest` that persisted LOCAL roles override caller-supplied higher privileges; existing `DelegatedRoleGrantPolicyTest` validates tenant/self/role restrictions.
- **IMPORTANT REMAINING:** run full compilation, Liquibase migration, LOCAL list/assign/switch/exit runtime tests, audit verification, and OIDC negative route check. Verify exact frontend JSX/TS types and backend JdbcTemplate + AuditService transaction wiring. Production Users & Access design and IdP authoritative assignment/revocation remain OPEN. This demo implementation must not be marketed as production role administration.
- Do not merge or deploy until checked. `main` untouched.


## V1 scope decision: Users & Access removed (2026-10-26)
Prior Users & Access entries above are historical, superseded. The new UI, simulated-user backend endpoints, database migration, role-grant policy, and tests were removed. Enterprise IdP remains the authority for users and groups. LOCAL developer role selector is retained. P0 and incident authorization changes remain. Rebuild and verify after pulling; if migration 035 was ever applied locally, inspect migration history before starting an upgraded instance.


## P1 authorization checkpoint after Users & Access removal (2026-10-26)
- Incident action endpoint now uses technical-incident view as an entry gate and explicitly checks the action-specific capability: REPROCESS requires TECHNICAL_RETRY; holds, releases and resolution actions require TECHNICAL_HOLD_MANAGE. This avoids requiring hold permission merely to retry. Existing same-tenant mutation guard remains.
- Added Platform-only denial tests for retry, hold, release and resolve. No schema changes in this checkpoint; OIDC and LOCAL role behavior unchanged.
- Existing HealthArea distinguishes tenant IT read-only operational overview from Platform operations changes; IntegrationsArea remains tenant IT-only through capabilities.
- Source commits `9d96a0c` and `6e78bb6`. **Pending local build and runtime acceptance**; do not merge into main until checked.


## P1/P2 follow-on checkpoint (2026-10-26)
- `01815c5`: contract tests assert IT-only manual watched-folder scan capability, platform-only health check and temporary processing storage actions, and permanent denial by legacy redacted-document download route. These are source contract tests, not distributed-runtime validations.
- `6b33e46`: P2 status wording now makes technical failure retention conditional on incident state and temporary-retention policy rather than claiming the original is retained indefinitely.
- P2 distributed acceptance remains OPEN: multi-node lease fencing and takeover, processor claim recovery, transient technical retry sequencing, processing storage provider activation/fallback, retention cleanup timing, multi-node observability and concurrent operation; requires local infrastructure/runtime tests. Existing code is not evidence those scenarios passed.
- P1 remaining: inventory other legacy access routes (particularly LOCAL-only artifact access request endpoints), test effective action/permission gates and role-specific frontend navigation, and inspect tenant/provider metadata for accidental leakage. No deployment or production merge before local build and runtime checks.


## Follow-up P1/P2 source boundary checkpoint (2026-10-26)
- `894f3cb` adds reflection/permission regression tests for document lifecycle actions: processor Desk-only visibility; supervisor approval/reprocess; IT hold; platform purge. Negative assertions cover standalone Business, IT, Platform, Report Viewer and retired ADMIN roles.
- Legacy `ArtifactAccessRequestController` and `VaultEntryController` are conditional on LOCAL mode, and the legacy redacted download endpoint throws access denied. Production OIDC does not register LOCAL artifact access controllers. No attempt to restore document downloads.
- **Residual LOCAL concern:** `ArtifactAccessRequestController` has local-only request/list/detail methods without capability annotations; this is not a production OIDC attack path because the controller is conditional, but local simulation is intentionally not a production trust boundary. Do not expose LOCAL mode publicly.
- Distributed P2 behaviors (multi-node failover, fencing, retry, cleanup/retention and storage outage) still require real local or staging infrastructure for meaningful acceptance. Source-level policy tests alone cannot close those checks.
- No additional database migrations or application runtime testing this checkpoint. Current candidate remains unverified until user runs build.


## P1 security fix — derived routing ownership (2026-10-26)
- Previous routing rule endpoints incorrectly required `TENANT_POLICY_MANAGE`, permitting Business Admin to administer delivery mappings while Tenant IT could be blocked. Review branch now uses `TENANT_INTEGRATION_MANAGE` across all list/read/create/update/delete and destinations endpoints. Existing route URLs are unchanged for client compatibility.
- Removed the Business Admin policy page's fallback rendering of RoutingRules. Tenant IT Integrations remains the sole navigation location. Updated the authorization contract test and negative Business/Platform coverage. Commits `73f90f8`, `a6279b8`, `1e87355`, `40734ce`, `5a7318b`.
- Build, endpoint authorization, UI and LOCAL acceptance remain pending. No migration or main merge.


## P2 source correction: recovery batch fault isolation (2026-10-26)
- `06cc9c6`: orphaned PROCESSING-without-lease and expired-lease recovery scans catch failures per candidate, instead of aborting the whole recovery batch on a single repository/dispatch/claim exception. Enumeration failure still fails that scan safely; recovery of other categories remains independent. Original fencing, lease claims, attempt budget and tenant identity preserved.
- `2f34408`, `baded95`: regression tests exercise a failed dispatch followed by another viable orphan/expired-lease package; expected second dispatch proceeds. Tests are source-only and not yet run.
- Remaining P2 acceptance: execute unit tests and full build, verify live multi-node fencing/takeover, retention and temporary storage cleanup, provider outages, and repeated startup idempotency before declaring resilience complete. No migration or main merge.


## P2 manual fallback eligibility (2026-10-26)
- Read-only GET `/api/v1/packages/{packageId}/manual-fallback/eligibility` now delegates to the same service-level eligibility validator as POST `/start`; the latter rechecks eligibility before mutations. Requires MANUAL_REVIEW capability. Ineligible replies have `eligible=false` and sanitized validation reason. Unexpected/server errors are not converted to `eligible=true`.
- Case drawer only offers manual continuation upon an explicit eligible response. On ineligibility it shows the reason; on lookup error it fails closed. Existing backend checks still cover AI_ANALYSIS stage, failed-document states, page count greater than zero, available retained originals, and unresolved findings.
- Added read-only and invalid-stage unit tests. Candidate not yet locally compiled or runtime tested after this change; verify especially zero-page PDF, safe AI-only failure, expired original, and OIDC role denial.
- Generic UNKNOWN_TECHNICAL_FAILURE incident classification for malformed/zero-page PDFs is separate P2 diagnostic issue, not changed in this checkpoint. Prefer safe deterministic classification only after tracing stage-specific root cause without leaking PDF content.
