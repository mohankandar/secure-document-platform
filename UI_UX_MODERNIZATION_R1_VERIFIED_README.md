# UI/UX Modernization R1 FIX1 — VERIFIED / FROZEN

Status: **VERIFIED / FROZEN**

Freeze date: **2026-09-20**

R1 establishes the modern enterprise presentation foundation while preserving the frozen LexaVault business/security baseline. `LexaVault` is a built-in development codename/default brand rather than a permanent market identity. Effective branding resolves built-in default → deployment override → tenant override without changing immutable `tenantId`, licensing identity, APIs, audit semantics, exported documents or delivery payloads.

## Accepted scope

- modern light-theme design tokens and shared page patterns;
- responsive grouped application shell with desktop collapse/expand, tablet landscape compact rail and portrait drawer;
- touch-friendly controls and tablet-aware responsive behavior;
- PostgreSQL-backed deployment/tenant branding configuration;
- application/short/organization names, page title, controlled primary/secondary colors, primary/compact logos and favicon;
- Platform Operations Branding administration with inheritance/reset and responsive live preview;
- Dashboard modern overview, filters, responsive table, reduced tablet-landscape columns and portrait package cards;
- responsive baseline for existing Upload, Scan, Technical Administration, Processing Storage and Licensing pages;
- semantic success/warning/error colors remain platform-controlled and independent of customer branding.

## Accepted validation evidence

- Full local R1 build: PASS.
- Deployment branding name/color changes: PASS.
- Tenant selective override, property-level inheritance and Reset to deployment: PASS.
- Primary logo, compact logo and favicon upload/persistence: PASS.
- Expanded shell / drawer / preview logo-rule consistency: PASS after FIX1.
- Dashboard 1180×820: compact rail + reduced intentional columns + pagination without horizontal scrollbar: PASS.
- Dashboard 820×1180: drawer shell + 2×2 metrics + wrapped controls + package cards: PASS.
- Branding 1180×820: compact rail, stacked preview, tenant actions: PASS.
- Branding 820×1180: top bar, usable two-column form, stacked preview, touch-friendly assets, bottom Save/Reset, tenant preview: PASS.
- Existing Processing Storage and Licensing presentation remained available on the new shell; frozen backend behavior was not changed.

## Schema / compatibility

The frozen post-V1 schema baseline is **71 Liquibase changeSets** through `030-ui-branding.xml`. R1 FIX1 adds no migration.

R1 is frozen as the UI foundation for the combined R2–R5 application-workflow modernization.
