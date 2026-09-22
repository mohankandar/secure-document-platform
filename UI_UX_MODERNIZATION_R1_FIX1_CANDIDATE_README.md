# UI/UX Modernization R1 FIX1 — Candidate

Status: **IMPLEMENTED CANDIDATE / LOCAL VALIDATION REQUIRED**

This FIX1 is a focused presentation-layer hardening pass on the accepted R1 candidate. It does not change processing, licensing, storage, tenant isolation, scanner acquisition, delivery, audit semantics, or API contracts.

## Fixes

- Expanded sidebar and tablet drawer no longer repeat/truncate the application name beside a configured primary logo.
- If no primary logo exists, the shell falls back to compact logo/lettermark plus application name and organization.
- Branding live preview now follows the same primary-vs-compact logo rules as the real shell.
- 900–1199 px tablet landscape starts with the compact rail by default while still allowing temporary expansion.
- Dashboard tablet landscape uses a separate responsive column preference with an intentional initial set: primary identifier, one searchable secondary identifier, Stage, Documents, Intake Source, and Last Updated. Users may add approved columns through the existing Columns menu without changing desktop preferences.
- Branding editor/preview stacks earlier on constrained desktop/tablet widths to avoid right-edge clipping.
- Tenant branding has a second Save/Reset action group at the bottom of the long override form; on portrait tablet it remains reachable near the working area.
- Existing 820 px two-column branding fields are retained to avoid excessive vertical scrolling.

## Validation requested

1. Full local build.
2. Desktop expanded shell with primary logo.
3. 1180×820 Dashboard and Branding.
4. 820×1180 Dashboard, Branding, and open navigation drawer.
5. Deployment/tenant branding inheritance and reset smoke test.
6. Frozen Processing Storage and Licensing visual/runtime smoke tests.

Liquibase baseline remains **71 changeSets**; FIX1 adds no database migration.
