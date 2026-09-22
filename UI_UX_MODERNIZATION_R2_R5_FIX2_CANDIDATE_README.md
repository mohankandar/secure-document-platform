# UI/UX Modernization R2–R5 FIX2 — Integrations Information Architecture Candidate

Status: **IMPLEMENTED CANDIDATE / LOCAL BUILD + INTEGRATIONS FOCUSED VALIDATION REQUIRED**  
Baseline: **UI/UX Modernization R1 FIX1 — VERIFIED / FROZEN + R2–R5 FIX1 candidate/runtime validation**  
Schema: **71 Liquibase changeSets through `030-ui-branding.xml` — unchanged**

## Why FIX2 exists

The user runtime walkthrough has now exercised the modernized Dashboard/operator workflows, Upload, Scanner simulator intake, package lifecycle, Manual Review, Business Administration persistence and role boundaries, Reporting, Support Incidents, Audit routine-noise filtering and Package Details count handling. Those surfaces are acceptable.

The remaining UX concern is Integrations: intake sources, master-delivery destinations and derived-delivery destinations were still presented together in one configured-connections list. That makes an IT administrator mentally classify every connection before acting.

FIX2 changes only this information architecture.

## Changes

### Purpose-group navigation

Integrations now exposes four purpose views:

- **Intake Sources** — `INGESTION_SOURCE`
- **Master Delivery** — `SDV_DELIVERY`
- **Derived Delivery** — `DERIVED_DOCUMENT_DELIVERY`
- **All Connections** — complete tenant inventory

Each group shows its own count and short explanation. Existing connection cards stay collapsed/summary-first until intentionally expanded.

### Context-aware creation

`+ New connection` now starts from the active purpose group:

- Intake Sources defaults to the existing Push API / inbound posture.
- Master Delivery defaults to HTTP API / outbound / SDV acknowledgement.
- Derived Delivery defaults to HTTP API / outbound / destination-receipt acknowledgement.
- All Connections uses Intake Source as the neutral creation default.

The operator can still change Purpose/Provider in the editor; backend validation remains authoritative.

### Editor behavior

- Routine review shows the connection list at full width with no placeholder editor panel.
- New/Edit opens the technical editor intentionally.
- Wide desktop may show list + editor side by side.
- <=1199 px stacks the editor beneath the list for tablet usability.
- Reset restores the original connection while editing, or the current purpose-group defaults while creating.

### Derived-delivery attention

Derived-delivery failures are shown only when viewing **Derived Delivery** or **All Connections**. Intake/master-delivery review is no longer interrupted by unrelated routing failures.

## Deliberate non-changes

FIX2 does **not** change:

- integration REST APIs or persistence;
- connection CRUD semantics;
- enable/disable behavior;
- HTTP polling / Run Now;
- SDV or derived health tests;
- derived retry semantics;
- authentication/acknowledgement validation;
- protected secret handling;
- tenant authorization/isolation;
- processing, redaction, review, retention or delivery semantics;
- Processing Storage, Licensing or Branding;
- database schema.

## Assistant-environment checks

- Frontend authorization tests: **8/8 PASS**.
- Modified `IntegrationsPage.tsx`: TypeScript parser reports only expected unresolved-module diagnostics in the dependency-free assistant environment; **no syntax diagnostics**.
- `src/index.css` brace balance: **PASS**.
- Liquibase: **unchanged at 71**.

A full local Maven/Vite build is not claimed until run in the user's environment.

## Focused local validation

Use the IT Administrator role.

1. Open **Integrations** and confirm the four groups appear with counts.
2. Confirm **Intake Sources**, **Master Delivery**, and **Derived Delivery** contain only the expected connection purpose.
3. Confirm **All Connections** shows the complete inventory.
4. Expand one existing connection and verify Provider / Direction / Authentication / Configuration plus existing actions.
5. Choose **Edit**, verify values populate, press **Reset**, and confirm the original values return. Close without saving.
6. In each of the first three purpose groups, choose **+ New connection** and confirm Purpose plus default Provider/Direction/Acknowledgement are appropriate; close without creating test noise.
7. If derived attention exists, confirm it appears in Derived Delivery / All Connections and not in Intake Sources / Master Delivery.
8. At **1180×820** and **820×1180**, confirm the group selector, connection disclosures, New/Edit editor and actions remain reachable without page-level horizontal overflow.

## Freeze rule

If the local build and these focused Integrations checks pass, mark combined **UI/UX Modernization R2–R5 VERIFIED / FROZEN** and make **On-Prem Installer & First-Run Setup** the active post-V1 workstream.
