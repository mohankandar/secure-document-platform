# Dashboard Retention Visibility Synchronization FIX1 — Candidate

Status: **IMPLEMENTED CANDIDATE / LOCAL BACKEND + RUNTIME VALIDATION REQUIRED**

## Requirement

A successfully exported/completed package must remain visible on the operational Dashboard until the tenant's Business Administration **Original retention after successful export** period expires. Dashboard cleanup must be driven by the same final processing-retention purge that clears LexaVault-owned processing artifacts. The configured value is tenant policy (`originalRetentionHours`); **24 hours is only the current/default value and is not hard-coded**.

When the final retention purge succeeds for a successfully exported `COMPLETED` package:

- processing/internal artifacts are purged as before;
- `document_packages.dashboard_hidden_at` / `dashboard_hidden_by` are populated through the existing dashboard-visibility port;
- the package disappears from normal Dashboard rows/KPIs because those queries already exclude hidden rows;
- the durable package row, reporting facts, audit history and export history remain in the database;
- operational reports continue to include hidden packages because reporting queries do not apply the Dashboard visibility filter.

There is **no new Dashboard timer, no hard-coded 24-hour check, no package deletion, and no Liquibase migration**. The final purge deadline continues to be scheduled from `TenantLifecyclePolicy.originalRetentionHours`.

Never-exported technical-hold purges are not treated as exported Dashboard history and are not automatically hidden by this fix. Existing Approver `Remove from Dashboard` behavior remains unchanged and idempotent with automatic retention cleanup.

## Implementation

`PackageLifecycleService` now receives the existing `PackageDashboardVisibilityPort`. During `findDueFinalPurge(...)` processing it:

1. purges remaining internal processing artifacts;
2. for a package with at least one successful export and current status `COMPLETED`, hides the package from the Dashboard using the purge actor/time;
3. marks/saves the lifecycle final purge state;
4. records the existing retention purge audit events.

Placing the Dashboard visibility write inside the same successful final-purge path means a visibility write failure does not mark the lifecycle as fully purged; the normal retention cycle can retry.

## Assistant-side checks

- Java 21 domain + application source compilation: **PASS** (temporary SLF4J compatibility stubs used because Maven is not installed in the assistant runtime).
- Focused retention behavior harness: **3/3 PASS**.
  - 48-hour Business Admin policy: still visible at +24h; final purge + Dashboard hide at the configured 48-hour deadline.
  - never-exported technical-hold purge: package is purged but not auto-hidden as exported history.
  - Dashboard visibility persistence failure: lifecycle is not marked fully purged and the next retention cycle can retry safely.
- Full Maven/backend build: **NOT RUN in assistant runtime (`mvn` unavailable)**.
- Schema: unchanged at **71 Liquibase changeSets** through `030-ui-branding.xml`.

## Local validation

1. Overlay this candidate and run the normal backend/full build.
2. Use a `COMPLETED` package with a successful export and verify it is still visible before its configured `originalDeleteAt`.
3. In LOCAL only, make that lifecycle final-purge deadline due (or wait for the configured policy), then invoke/allow the normal retention purge.
4. Refresh Dashboard: the package row and exported KPI count should no longer include that package.
5. Verify `document_packages` still contains the package and `dashboard_hidden_at` is populated.
6. Verify reporting/export history/audit still retain the package.
7. Verify processing artifacts are deleted according to the existing retention lifecycle.

After local build/runtime PASS this candidate can be frozen as a post-R2–R5 defect correction, and the next major workstream remains **On-Prem Installer & First-Run Setup**.
