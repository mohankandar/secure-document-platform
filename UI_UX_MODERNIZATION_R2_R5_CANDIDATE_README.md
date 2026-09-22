# UI/UX Modernization R2–R5 — Combined Candidate

Status: **IMPLEMENTED CANDIDATE / LOCAL VALIDATION REQUIRED**  
Baseline: **UI/UX Modernization R1 FIX1 — VERIFIED / FROZEN**  
Schema: **71 Liquibase changeSets through `030-ui-branding.xml` — unchanged**

## Product goal

Complete the remaining application modernization as one coordinated release while preserving the frozen R1 design system, responsive shell, white-label branding, iPad foundation, authorization model and backend business behavior.

This is intentionally one combined codebase with hard internal acceptance checkpoints. A later UI finding does not reopen frozen R1 or accepted backend work.

## Checkpoint A — Core operator workflows

### Upload Documents
- Adds a reusable three-step progress treatment: Business details -> Documents -> Review & submit.
- Progress is derived from existing identifier/document readiness only.
- Package creation, document upload and submit API behavior is unchanged.

### Scanner Intake
- Adds a three-step progress treatment: Identify package -> Capture document -> Queue & continue.
- Existing Scanner Bridge/WIA/simulator boundaries, session semantics, source selection and queue handoff are unchanged.

### Package Details
- Reframes the page as an intake workspace with a shared PageHeader, business/status summary and contextual next action.
- Adds responsive tabs for Overview, Documents, Delivery & retention, and Derived routing.
- Uses responsive document/routing cards and safe lifecycle InfoGrid presentation.
- Existing upload/submit, final approval, reprocess, manual-continuation and derived-send handlers are preserved.

## Checkpoint B — Human review workflows

### Manual Review
- Uses the shared modern page header and standardized action placement.
- Adds a portrait-friendly decision action bar so Confirm / Not Sensitive remain reachable on tablet portrait.
- Existing review queue, document preview, bounding boxes, confidence, confirmation/dismiss semantics and API behavior are unchanged.

### Manual Processing / Business Continuity
- Keeps the proven manual redaction workspace and page tooling.
- Replaces the legacy header with the shared PageHeader and aligns controls to the R1 responsive/touch system.
- Does not bypass verification, permanent redaction, or delivery safeguards.

## Checkpoint C — Administration

### Business Administration
- Adds a sticky/scrollable section navigator for Business Identifiers, Reporting, Dashboard, Sensitive Data, Document Intelligence, Routing, and Retention.
- Existing policy editors, validators, configuration persistence and tenant scoping remain unchanged.

### Integrations
- Adds responsive operational summary cards and shared page treatment.
- Preserves connection CRUD, enable/disable, provider test, polling/run-now, retry and secret-handling behavior.

### Frozen Platform Operations
- Processing Storage, Licensing and Branding behavior remain functionally frozen. The combined candidate consumes their R1 shell/design tokens but does not reopen their business logic.

## Checkpoint D — Reports, Support & Audit

### Reporting
- Keeps the existing Reporting V3 data semantics and responsive report structures.
- Aligns selectable/brand accents with the configurable R1 branding token while leaving semantic status colors unchanged.

### Support Incidents / Technical Issues
- Uses shared PageHeader treatment and compact operational summaries.
- Incident search results adapt from a desktop table to touch-friendly record cards on tablet/portrait.
- Tenant/privacy boundaries and diagnostic content remain unchanged.

### Audit
- Keeps the existing safe filters and API.
- Replaces the wide audit table presentation with responsive event cards containing action/time, outcome, resource, actor, correlation ID and safe detail.
- No protected document content is introduced.

## Shared UI primitives

- `WorkflowStepper` — reusable workflow progress for Upload/Scan and future multi-step flows.
- `StatusBadge` — standardized text + semantic state badge; color is never the only signal.
- `InfoGrid` — responsive definition/details presentation for package/technical information.

## Responsive targets

Primary acceptance targets remain:

- desktop/laptop
- 1180×820 tablet landscape
- 820×1180 tablet portrait
- current iPadOS Safari behavior represented by equivalent viewport testing

Key requirements:

- no page-level horizontal scrolling for normal workflows;
- >=44px touch targets for primary interactive controls;
- no hover-only critical action;
- intentional tablet transformations rather than squeezed desktop tables;
- drawer/top-bar behavior remains the frozen R1 shell behavior.

## Deliberate non-changes

This candidate does **not** intentionally change:

- API contracts or backend domain/application behavior;
- OIDC/role/capability authorization;
- tenant isolation;
- Processing Storage or HA/fail-closed behavior;
- Tenant Licensing verification/entitlements/history;
- Scanner Bridge protocol or physical scanner semantics;
- sensitive-data detection/redaction/review decision rules;
- retention, final master delivery or derived-document delivery semantics;
- database schema.

## Assistant-environment checks completed

- TypeScript 5.8.3 syntax transpile: **PASS** across 34 non-declaration TS/TSX source files.
- Frontend authorization tests: **8/8 PASS**.
- `src/index.css` brace balance: **PASS**.
- `src/pages/ReportsPage.css` brace balance: **PASS**.
- No Liquibase change: baseline remains **71**.

A full local Maven/Vite build is not claimed until run in the user's development environment.

## Local acceptance sequence

### Gate 0 — Build

Run the normal full LexaVault build. Stop if it fails.

### Checkpoint A

1. Dashboard at desktop, 1180×820 and 820×1180.
2. Upload one small PDF using the redesigned Upload page; confirm identifiers, file queue and Submit still work.
3. Scanner simulator: start/complete one scan flow and verify package/document handoff.
4. Package Details: verify Overview, Documents, Delivery & retention and Derived routing tabs; exercise only actions appropriate to a safe test package.

### Checkpoint B

1. Open a package requiring Manual Review.
2. Verify queue selection, preview, Confirm and Not Sensitive flows on desktop and portrait.
3. Exercise Manual Processing on an existing safe fallback test case; confirm page navigation/redaction controls remain functional.

### Checkpoint C

1. Business Administration: navigate all section anchors and save a harmless reversible setting only if desired.
2. Integrations: view list/editor and run only previously safe connection tests; do not introduce new external endpoints solely for UI validation.
3. Processing Storage/Licensing/Branding: smoke-check that frozen tabs still render and retain their accepted state.

### Checkpoint D

1. Reports: open each major report experience and verify filters/tabs on desktop/tablet.
2. Support Incidents: search and open one incident; verify responsive list/detail behavior.
3. Technical Issue detail: confirm safe summary/diagnostics render.
4. Audit: load records and verify responsive event-card presentation.

### Checkpoint E — regression/freeze gate

- role-based navigation/auth regression;
- upload/process/review/export golden path;
- Scanner simulator golden path;
- frozen Licensing and Processing Storage smoke regression;
- desktop + tablet landscape + tablet portrait;
- accessibility/touch/keyboard checks;
- no cross-tenant/security regression.

Only after Checkpoints A–E pass should the combined R2–R5 candidate be marked VERIFIED / FROZEN and the On-Prem Installer / First-Run Setup become active.
