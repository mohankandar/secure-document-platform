# Dashboard Retention Visibility Synchronization FIX2 — Candidate

Status: **IMPLEMENTED CANDIDATE / LOCAL BACKEND + RUNTIME VALIDATION REQUIRED**

## Why FIX2 is required

FIX1 correctly attached Dashboard hiding to the normal final processing-retention purge for all future packages. Local runtime validation then exposed an upgrade/backfill gap: packages whose processing artifacts had already been purged before FIX1 had `purged_at` populated, so the existing `findDueFinalPurge(...)` query would never select them again. Those already-purged completed exports could therefore remain visible on the operational Dashboard indefinitely.

FIX2 keeps the FIX1 future-package behavior and adds only a bounded backend reconciliation for this existing-data state.

## Behavior

During the existing retention-maintenance execution, LexaVault now also finds packages that are:

- current package status `COMPLETED`;
- successfully exported (`successful_export_cycles > 0`);
- already final-purged (`purged_at IS NOT NULL`);
- still operationally visible (`dashboard_hidden_at IS NULL`).

Those packages are hidden through the existing `PackageDashboardVisibilityPort`. Reconciliation uses the already-recorded final `purged_at` timestamp as `dashboard_hidden_at`; it does **not** calculate a new 24-hour deadline and does **not** rerun artifact deletion.

The reconciliation is bounded by the existing purge command limit, is idempotent through `dashboard_hidden_at IS NULL`, and is safe to retry if the visibility write fails. The normal 15-minute scheduler, Business Admin retention policy, final purge flow, manual Approver `Remove from Dashboard`, and durable package/reporting/audit/export history are unchanged.

There is **no new frontend timer, no hard-coded 24-hour value, no package deletion, no processing-storage repurge, no new scheduler, and no Liquibase migration**. Schema remains 71 changeSets through `030-ui-branding.xml`.

## Implementation

- `PackageLifecycleRepository` adds a bounded query for already-purged completed exports that still need Dashboard hiding.
- The JPA query joins `package_lifecycles` state with `document_packages` visibility/status and orders oldest purges first.
- `PackageLifecycleService.execute(PurgeDueRetentionCommand)` runs the reconciliation after normal redacted/final purge processing.
- Existing final-purge behavior from FIX1 remains unchanged for new packages.
- Reconciliation success/failure is auditable under `PACKAGE_HIDDEN_FROM_DASHBOARD`.
- A failed reconciliation leaves the already-completed retention history untouched and remains retryable because `dashboard_hidden_at` is still null.

## Assistant-side checks

- Java 21 domain + application source compilation: **PASS — 453 sources**.
- Changed infrastructure persistence files syntax/type compilation with compatibility stubs: **PASS**.
- Focused Dashboard-retention harness: **5/5 PASS**.
  - 48-hour Business Admin policy remains visible at +24h and hides only with the configured final purge.
  - never-exported technical-hold purge is not treated as exported Dashboard history.
  - future final-purge visibility failure remains retryable.
  - already-purged completed export is reconciled without rerunning artifact deletion and preserves the original purge timestamp.
  - reconciliation visibility failure retries without rewriting retention history or deleting artifacts.
- Test sources affected by the repository-port addition compile with the focused compatibility harness: **PASS**.
- Full Maven/backend build: **NOT RUN in assistant runtime (`mvn` unavailable)**.

## Local validation

1. Overlay FIX2 and run the normal full backend build/startup.
2. Confirm the previously observed old exported/completed rows are still present before reconciliation.
3. Allow the normal retention scheduler to run, or as Platform Operations invoke `POST /api/v1/lifecycle/purge-due?limit=500` in LOCAL to reconcile a larger existing backlog in one validation cycle.
4. Refresh Dashboard. Previously final-purged completed/exported packages should disappear; packages still inside configured processing retention should remain.
5. Verify the reconciled rows still exist in `document_packages`, with `dashboard_hidden_at` populated and package/lifecycle rows intact.
6. Verify reporting, audit, export history and support/history remain available.
7. Verify reconciliation did not create a second retention purge or delete durable database history.
8. Export a fresh package and confirm the normal FIX1 future path still keeps it visible until configured retention expires, then hides it when final processing purge succeeds.

After full local build/runtime PASS, this targeted post-freeze defect can be marked **VERIFIED / FROZEN** without reopening UI/UX Modernization R2–R5.
