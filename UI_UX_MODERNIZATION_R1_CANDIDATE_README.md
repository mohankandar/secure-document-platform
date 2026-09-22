# UI/UX Modernization R1 — IMPLEMENTED CANDIDATE / LOCAL VALIDATION REQUIRED

**Date:** 2026-09-18  
**Scope:** Design System + Responsive App Shell + Branding Foundation + iPad/Tablet Support

## Product intent

This candidate modernizes the customer-facing presentation layer without reopening frozen V1 business behavior. `LexaVault` remains the development codename/default brand; visible product naming, logos and brand colors are now configuration-driven so a future market name or customer/internal white-label identity does not require renaming technical packages, APIs, tenant IDs or license identities.

## What changed

### Modern application shell
- New responsive enterprise shell with grouped Work / Reports / Administration / Support navigation.
- Desktop sidebar supports expanded/collapsed states; preference is browser-local only.
- Tablet portrait uses a touch-friendly slide-over navigation drawer instead of horizontal navigation.
- iPad/tablet controls target approximately 44px minimum touch targets and do not depend on hover.
- Visible shell branding uses the resolved branding configuration rather than a hard-coded product name.
- Internal marker `data-ui-design-system="1"` identifies migrated shell pages during the broader modernization program.

### R1 design-system foundation
- Shared design tokens for surfaces, typography, spacing, borders, radii, status semantics, focus states and brand colors.
- Shared `PageHeader`, icon and button/form treatment foundations.
- Semantic success/warning/error states remain platform controlled and cannot be redefined by customer brand colors.
- Default theme remains light; tokens are structured so supported dark mode/density options can be added later without rewriting migrated pages.

### Branding & theming foundation
Brand resolution order is:

`built-in default -> deployment override -> tenant override`

Configurable fields:
- application display name;
- short name;
- organization name;
- browser/page title;
- primary accent color;
- secondary brand color;
- primary logo;
- compact logo;
- favicon.

Important boundaries:
- branding never changes immutable `tenantId`, licensing identity, APIs, database identity, audit semantics, exported PDFs, delivery payloads, notification templates or legal/vendor identity;
- deployment and tenant branding administration is restricted to `PLATFORM_OPERATIONS` in R1;
- normal authenticated users receive only their effective branding;
- arbitrary customer CSS is not supported;
- primary accent must be a six-digit hex color with WCAG-AA contrast against white button text;
- image assets are database-backed configuration, not processing-storage artifacts;
- R1 accepts only PNG/WebP images, validates file signatures, and enforces 512 KB logo / 128 KB favicon limits.

### Branding administration
`Technical Administration -> Branding` adds:
- deployment branding editor;
- controlled color pickers and contrast feedback;
- primary/compact logo and favicon upload/remove controls;
- responsive shell preview;
- tenant selector with selective override/inherit behavior;
- reset-to-deployment action;
- audit event `BRANDING_CONFIGURATION_CHANGED`.

### Dashboard / core workflow presentation
- Dashboard header and metric hierarchy migrated to the R1 design system.
- Summary emphasis is Processing / Needs Review / Needs Approval / Technical Attention.
- Desktop/tablet-landscape retains the operational table.
- Tablet portrait intentionally switches the package table to touch-friendly package cards rather than squeezing wide columns.
- Upload Documents, Scan Documents and Technical Administration use the shared page-header pattern.
- Scanner Intake uses configured product naming and explicitly explains that physical scanner acquisition requires the workstation Scanner Bridge; tablet/iPad users can still use browser workflows.
- Existing Processing Storage and Licensing behavior is preserved and rendered inside the modern shell.

## Backend / schema

New migration: `030-ui-branding.xml`.

The candidate schema baseline is **71 Liquibase changeSets** through `030-ui-branding.xml` (frozen V1 remains 64 through `027-package-processing-ha.xml`).

New API surface:
- `GET /api/v1/branding/current` — effective branding for the current authenticated tenant;
- `GET /api/v1/platform/branding` — deployment config + tenant branding summaries (`PLATFORM_OPERATIONS`);
- `GET /api/v1/platform/branding/tenants/{tenantId}` — one tenant override (`PLATFORM_OPERATIONS`);
- `PUT /api/v1/platform/branding/deployment`;
- `PUT /api/v1/platform/branding/tenants/{tenantId}`;
- `DELETE /api/v1/platform/branding/tenants/{tenantId}`.

Overview intentionally does not return every tenant's binary logo payload; tenant detail is loaded on demand.

## Scope deliberately not changed
- package/document processing semantics;
- redaction/OCR/AI behavior;
- Manual Review decision semantics;
- Processing Storage semantics;
- Tenant Licensing enforcement/history semantics;
- scanner bridge acquisition protocol/security;
- reporting calculations;
- final master/SDV/derived delivery;
- role/capability model;
- OIDC trust boundary.

## Validation completed in this build environment
- Branding application-layer Java sources: `javac` PASS.
- Branding API/infrastructure Java syntax: `javac` PASS against minimal framework stubs.
- New Liquibase XML/master XML parsing: PASS.
- Full frontend TypeScript/TSX syntax transpilation: PASS (30 files).
- Full Maven build and full Vite production build are **not claimed** in this environment; local product-owner validation is required.

## Required local validation

### 1. Build / migration
Run the normal full build. Expected:
- backend Maven build PASS;
- frontend production build PASS;
- existing frontend authorization tests PASS;
- Liquibase total = **71** with `030-ui-branding.xml` applied once.

### 2. Desktop shell
With Platform Operations role:
- grouped navigation renders correctly;
- collapse/expand works and persists across browser refresh;
- Dashboard, Upload, Scan, Reports, Technical Administration, Processing Storage and Licensing remain navigable according to capabilities;
- no existing route or role boundary is broadened.

### 3. Branding
In `Technical Administration -> Branding`:
1. Save a deployment application name and accessible dark accent color.
2. Confirm the shell name/accent updates without restarting the backend.
3. Upload a small PNG/WebP logo and verify aspect ratio is preserved.
4. Try an obviously light accent (for example `#FFF59D`) and confirm it is rejected.
5. Confirm SVG/JPEG or an oversized image is rejected.
6. Configure a Local Demo Tenant override for only the application name/accent; confirm unspecified fields inherit deployment values.
7. Reset the tenant override; confirm deployment branding returns.
8. Confirm tenant/license identifiers do not change.

### 4. iPad / tablet responsive acceptance
Validate representative browser viewports:
- 1180x820 landscape;
- 1024x768 landscape;
- 820x1180 portrait;
- 768x1024 portrait.

Acceptance:
- no page-level horizontal scrolling on the migrated shell/Dashboard/Upload/Scan/Technical Administration flows;
- portrait navigation uses a drawer;
- important touch controls remain comfortably tappable;
- Dashboard portrait uses package cards, not a compressed wide table;
- forms stack intentionally in portrait;
- Processing Storage / Licensing remain usable;
- rotation between landscape/portrait does not trap or overlap the navigation drawer.

### 5. Scanner tablet boundary
With Scanner Bridge unavailable, Scan Documents must state that physical acquisition runs on a workstation with Scanner Bridge. It must not imply that iPad Safari can access WIA hardware directly.

### 6. Non-regression
Run the normal high-value regression/golden paths:
- manual upload -> processing -> review when required -> export;
- scanner simulator core path;
- licensing ACTIVE/Allowed state remains unchanged;
- Processing Storage readiness/provider workflow unchanged;
- Platform Operations and tenant-role authorization unchanged;
- cross-tenant access remains denied.

## Freeze gate

Do not mark R1 frozen until the full local build plus desktop/iPad focused UI and business/security non-regression checks pass. Findings should be fixed inside this R1 workstream before moving to the deeper R2 workflow modernization.
