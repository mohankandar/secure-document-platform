# UI/UX Modernization R2–R5 FIX1 — Validation Cleanup Candidate

Status: **IMPLEMENTED CANDIDATE / LOCAL BUILD + FOCUSED VALIDATION REQUIRED**  
Baseline: **UI/UX Modernization R1 FIX1 — VERIFIED / FROZEN + R2–R5 combined candidate**  
Schema: **71 Liquibase changeSets through `030-ui-branding.xml` — unchanged**

## Why FIX1 exists

The combined R2–R5 candidate built successfully in the user environment and received a broad desktop/tablet runtime walkthrough. Core Upload, Scanner simulator, Package Details, Manual Review, role authorization, Reporting and responsive shell behavior were exercised. The remaining findings were primarily information-density and presentation problems in administration/support surfaces plus one visible Package Details count defect.

FIX1 addresses those findings without reopening frozen backend processing, security, licensing, storage, scanner or delivery behavior.

## Changes

### 1. Business Administration — summary-first sections

- The existing sticky section navigator now behaves as a one-at-a-time section selector instead of leaving all seven configuration areas expanded in one long page.
- Existing Business Identifiers render as compact collapsible rows showing label, validation type and important flags.
- `+ Add Identifier` still creates a new identifier, and a newly added row opens directly into its editor.
- Identifier validation, primary/required/displayable rules, ordering and save APIs are unchanged.
- Derived Document Routing now keeps the rule editor closed until `+ Add Routing Rule` or `Edit rule` is selected.
- Existing routing rules render as compact disclosure rows with marker, destination, region and enabled state.
- Reporting, Dashboard, Sensitive Data, Document Intelligence and Retention are isolated into their own section views; switching sections does not discard in-memory edits.

### 2. Integrations — review first, edit on demand

- Configured connections render as collapsible summary rows instead of permanently expanded cards.
- The large New/Edit Connection form is closed by default.
- `New connection` explicitly opens a clean editor.
- Expanding a configured connection exposes existing operational details/actions; `Edit` opens its editor.
- A successful create/update closes the editor and returns to the summary view.
- CRUD, enable/disable, connection tests, polling/run-now, derived retry and protected-secret behavior are unchanged.

### 3. Support Incidents — primary vs advanced triage

The default search surface now exposes only the fields most commonly needed to locate a case:

- Primary business identifier
- Incident ID
- Status

The following move under `Advanced filters`:

- Tenant override (when authorized)
- Correlation ID
- Package ID
- Document ID
- Stage
- Reason code
- Business outcome
- Business continuity used
- Retryable
- From / To

When advanced filters are collapsed, the button shows the count of active advanced filters so an operator is not surprised by hidden search criteria.

No incident record, tenant boundary or search API behavior changes.

### 4. Audit — reduce scheduled no-op noise without deleting evidence

The Audit Timeline still retrieves and retains all audit records. By default the UI now hides only events matching all of these conditions:

- action = `WATCHED_FOLDER_SCAN_TRIGGERED`
- detail contains `mode=SCHEDULED`
- detail contains `discovered=0`

These are routine scheduler heartbeat/no-op scans rather than meaningful business/security changes. The page reports how many were hidden and provides `Show routine activity` to reveal them.

This is presentation filtering only. No audit event is deleted, suppressed at persistence time or excluded from explicit filtered retrieval.

### 5. Package Details — `Documents (undefined)` defect

The package detail API historically returns `documents` but may omit the summary-only `documentCount`. The frontend client now normalizes the response:

- use `documentCount` when supplied;
- otherwise derive the count from `documents.length`;
- otherwise use `0`.

This fixes the visible `Documents (undefined)` tab/summary without changing the backend API or database schema.

## Deliberate non-changes

FIX1 does **not** intentionally change:

- package processing/redaction/review semantics;
- business identifier persistence or canonicalization;
- routing execution/delivery behavior;
- integration provider execution or secrets;
- audit persistence;
- support incident persistence;
- OIDC/capability authorization or tenant isolation;
- Processing Storage, Licensing or Branding behavior;
- Scanner Bridge/WIA/simulator behavior;
- Reporting calculations;
- database schema.

## Assistant-environment checks

Completed for this candidate:

- TS/TSX syntax parse across all **34** source files: **PASS**
- frontend authorization tests: **8/8 PASS**
- `src/index.css` brace balance: **PASS**
- `src/pages/ReportsPage.css` brace balance: **PASS**
- Liquibase: no change; remains **71**

A full local Maven/Vite build is not claimed until run in the user's development environment.

## Focused local validation after build

### Gate 0 — full build

Run the normal LexaVault full build. Stop on any compile/test failure.

### 1. Package Details

1. Open any package with one document.
2. Confirm the tab reads **Documents (1)**, never `Documents (undefined)`.
3. Open the Documents tab and verify the returned PDF/document card still renders normally.

### 2. Business Administration

Use the Business Administrator role.

1. Confirm only the selected section body is shown at a time.
2. Switch through Identifiers, Reporting, Dashboard, Sensitive data, Document intelligence, Routing and Retention.
3. Confirm values persist while switching sections.
4. Expand an existing identifier; confirm all previous controls are present.
5. Add a temporary identifier only if desired; confirm its row opens for editing. Cancel/remove it if it is only a test.
6. Open Routing; confirm existing rules are compact rows and the rule editor is not shown until Add/Edit.
7. Make only a harmless reversible save if persistence verification is needed.

### 3. Integrations

Use the IT Administrator role.

1. Confirm the page opens with configured connections in compact rows and no permanent New Connection form.
2. Expand one existing connection; verify provider/direction/auth/config and existing actions.
3. Choose Edit and confirm the correct values populate the editor.
4. Close without saving, then verify the summary view returns.
5. Use New Connection and confirm a blank editor appears; do not create an unnecessary external connection.
6. Existing previously-safe Test Connection behavior may be smoke-tested if desired.

### 4. Support Incidents

1. Confirm the default search area contains Business Identifier, Incident ID and Status plus Search/Advanced/Clear.
2. Open Advanced Filters and confirm all previous secondary fields remain available.
3. Enter one advanced value, collapse Advanced Filters and confirm the button shows `(1)`.
4. Clear filters and confirm the count disappears.
5. Search/open one existing incident and confirm case navigation is unchanged.

### 5. Audit

1. Load audit records with routine activity hidden.
2. Confirm repeated scheduled watched-folder scans with `discovered=0` no longer dominate the default list.
3. Confirm the page reports the number of routine scans hidden when present.
4. Enable **Show routine activity** and confirm those records reappear.
5. Confirm meaningful package/security/configuration audit records remain visible normally.

### 6. Tablet smoke

At minimum recheck Business Administration, Integrations and Support Incidents at:

- 1180×820
- 820×1180

Confirm no page-level horizontal overflow and that disclosure/editor actions remain reachable by touch.

## Freeze rule

If the local full build plus the focused checks above pass without a product/security regression, the combined **UI/UX Modernization R2–R5 FIX1** can be marked **VERIFIED / FROZEN**. The next active workstream becomes **On-Prem Installer & First-Run Setup**.
