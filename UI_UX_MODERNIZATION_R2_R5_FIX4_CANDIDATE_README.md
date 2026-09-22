# UI/UX Modernization R2–R5 FIX4 — Integration Group Draft Isolation

Status: **IMPLEMENTED CANDIDATE / FRONTEND BUILD + NARROW INTEGRATIONS VALIDATION REQUIRED**  
Baseline: **R2–R5 FIX3 focused validation exposed one tab-switch state leak**  
Schema: **71 Liquibase changeSets through `030-ui-branding.xml` — unchanged**

## Why FIX4 exists

FIX3 correctly locked Purpose when a new connection was opened inside Intake Sources, Master Delivery or Derived Delivery. Focused runtime validation found one remaining UI-state defect: an editor opened in one purpose group could remain open while the administrator switched to another group. The stale draft then no longer matched the active group and the Purpose selector became available again.

## Change

- Switching Integration groups now closes and resets any open create/edit editor so drafts cannot leak across groups.
- Purpose is treated as locked for every purpose-specific group, independent of transient draft state.
- Provider choices are constrained by the active purpose group:
  - Intake Sources: Push API, HTTP API, Watched Folder, Scanner.
  - Master Delivery: HTTP API only.
  - Derived Delivery: HTTP API or File Share.
  - All Connections: all provider choices remain available.
- When Purpose is intentionally changed from All Connections, incompatible provider state is normalized to a valid default.
- Backend/API/database/security/delivery semantics are unchanged.

## Assistant checks

- `IntegrationsPage.tsx` TypeScript/TSX syntax transpile: **PASS**.
- Frontend authorization tests: **8/8 PASS**.
- Full local Vite build remains a user-side validation gate because packaged source does not include `node_modules`.

## Focused validation

1. Master Delivery → **+ New connection** → switch to Intake Sources. The old editor must close.
2. Intake Sources → **+ New connection**. Purpose must be Ingestion Source and locked.
3. Master Delivery → **+ New connection**. Purpose must be SDV Delivery and locked; Provider must only offer HTTP API.
4. Derived Delivery → **+ New connection**. Purpose must be Derived Document Delivery and locked; Provider must offer HTTP API/File Share only.
5. All Connections → **+ New connection**. Purpose remains selectable.
6. Repeat the tab-switch test with an existing connection opened for Edit; the editor must close when the group changes.

## Deliberate non-changes

No backend/API/schema/security/tenant-isolation/storage/licensing/scanner/retention/delivery semantics changed.
