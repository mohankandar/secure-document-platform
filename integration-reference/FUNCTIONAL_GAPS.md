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
