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
