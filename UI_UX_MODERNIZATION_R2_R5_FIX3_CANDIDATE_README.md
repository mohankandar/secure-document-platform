# UI/UX Modernization R2–R5 FIX3 — Purpose-Locked Integration Creation

Status: **IMPLEMENTED CANDIDATE / FRONTEND BUILD + NARROW INTEGRATIONS VALIDATION REQUIRED**  
Baseline: **R2–R5 FIX2 runtime validation PASS**  
Schema: **71 Liquibase changeSets through `030-ui-branding.xml` — unchanged**

## Why FIX3 exists

Focused FIX2 validation confirmed the new Integrations purpose groups work correctly. One final usability issue remained: when a user chooses **+ New connection** from Intake Sources, Master Delivery or Derived Delivery, the editor still allowed the Purpose field to be changed, which could create a connection in a different category than the one the operator intentionally entered.

## Change

- In **Intake Sources**, **Master Delivery**, and **Derived Delivery**, the Purpose field is read-only/disabled when the draft belongs to the active group.
- The editor explains that Purpose is locked to the current connection group.
- **All Connections** keeps Purpose selectable so an administrator can intentionally choose any supported purpose from the neutral inventory view.
- Provider, direction, acknowledgement, authentication, health-test, polling, enable/disable, retry and secret behavior are unchanged.
- Backend validation remains authoritative.

## Assistant checks

- Modified `IntegrationsPage.tsx` TypeScript/TSX syntax transpile: **PASS**.
- Frontend authorization tests: **8/8 PASS**.
- Full local Vite build is not claimed in the assistant environment because the packaged source does not include `node_modules`.

## Focused validation

1. Intake Sources → **+ New connection**: Purpose = Ingestion Source and cannot be changed.
2. Master Delivery → **+ New connection**: Purpose = Master Delivery / SDV Delivery and cannot be changed.
3. Derived Delivery → **+ New connection**: Purpose = Derived Document Delivery and cannot be changed.
4. All Connections → **+ New connection**: Purpose remains selectable.
5. Existing connection Edit from a purpose-specific group keeps Purpose locked; editing from All Connections keeps Purpose selectable.
6. Confirm no horizontal overflow at 1180×820 and 820×1180.

## Deliberate non-changes

No backend/API/schema/security/tenant-isolation/storage/licensing/scanner/delivery semantics changed.
