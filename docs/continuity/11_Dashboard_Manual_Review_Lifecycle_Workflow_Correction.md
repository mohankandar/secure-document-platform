# Dashboard, Manual Review, and Package-Visibility Workflow Correction

**Date:** 2026-08-31  
**Status:** ChatGPT implementation complete; local full build and focused runtime verification still required before Final V1 acceptance.

## Frozen user decisions implemented

This correction is intentionally limited to operational UX/workflow behavior. It does **not** change AI detection, tenant redaction policy, provider/model routing, permanent-redaction rules, or retention timing.

1. Dashboard refreshes automatically without reloading the browser page.
2. Manual Review entered from a package/contract must show only that package's actionable findings.
3. Global Manual Review remains tenant-wide.
4. A selected finding must render the exact one-based PDF page for that finding and use the stored bounding box/highlight.
5. The active review count must exclude findings that are no longer `DETECTED` and packages that are no longer in `PENDING_REVIEW`/`IN_REVIEW`.
6. Approver gets a row `⋮` action menu with `View Details` and, for `COMPLETED` packages, `Remove from Dashboard`. Removal opens a confirmation modal/sheet and is **Dashboard removal only**:
   - package/database record is retained;
   - audit/lifecycle history is retained;
   - physical artifacts are not deleted by this action;
   - the existing retention lifecycle remains the only physical purge mechanism.

## Implementation

### Dashboard smart refresh

`DashboardPage` now:
- fetches immediately on normal load;
- silently refreshes every 5 seconds while the Dashboard overview reports active processing;
- silently refreshes every 30 seconds while idle;
- does not poll while the browser tab is hidden;
- refreshes immediately when the tab becomes visible or the browser window regains focus;
- keeps the existing manual Refresh action;
- displays the active refresh cadence and last successful refresh time.

### Package-scoped Manual Review

`GET /api/v1/review-items` now accepts optional `packageId`.

- No `packageId`: tenant-wide actionable Manual Review queue.
- With `packageId`: backend fetches that package using both package ID and authenticated tenant ID, then returns only that package's actionable findings.

This is backend scope enforcement, not a frontend-only array filter.

Dashboard review links and Package Details → `Open Manual Review` now navigate with the package scope. The scoped workbench shows the configured primary/displayable business identifier and provides `View all reviews` to return to the global queue.

### Review grouping and exact-page preview

The review queue groups findings by document + page. The preview endpoint still renders only one source page, never the whole original binary.

The response includes `X-Review-Page-Number`; the frontend verifies that header equals the selected finding's page before displaying the image. The review page-number/image-dimension response headers are explicitly added to the CORS exposed-header allowlist so the separate Vite/browser origin can read them in LOCAL and the same contract works in production deployments. A focused regression test constructs a two-page PDF with different page dimensions and proves that page numbers are one-based and page 2 renders page 2.

Stale/non-renderable review state no longer presents as a generic client `HTTP 400` for these state conditions:
- finding is no longer pending;
- package is no longer in review;
- original is unavailable;
- stored finding page is outside the source PDF.

Those are surfaced as safe state conflicts (`409 INVALID_STATE`) with `REVIEW_PREVIEW_REJECTED reason=...` diagnostics. No raw document content or sensitive value is logged.

### Actionable review count

The queue/count includes only findings where:
- package is `PENDING_REVIEW` or `IN_REVIEW`;
- finding is still `DETECTED`;
- finding has a valid one-based page number.

Confirmed, dismissed, redacted, completed-package, and other historical findings do not contribute to the active count.

The global V1 review query still scans the most recent 250 tenant packages before building the actionable queue. This is an accepted current query-model limit; a dedicated indexed review projection is a later scale optimization, not part of this correction.

### Approver Remove from Dashboard = hide from Dashboard

New endpoint:

`POST /api/v1/packages/{packageId}/dashboard-hide`

Security/lifecycle rules:
- requires `APPROVER` (existing `ADMIN` override remains governed by the established authorization layer);
- tenant-scoped lookup is authoritative;
- only `COMPLETED` packages can be hidden;
- no domain processing status is changed;
- no artifact is deleted;
- no retention deadline is changed;
- audit action: `PACKAGE_HIDDEN_FROM_DASHBOARD`.

Liquibase change `013-dashboard-package-visibility.xml` adds nullable operational metadata:
- `document_packages.dashboard_hidden_at`
- `document_packages.dashboard_hidden_by`
- tenant/visibility index.

Normal Dashboard queries and Dashboard KPI counts exclude rows with `dashboard_hidden_at != null`. Operational reports/history continue to include them. The existing retention lifecycle is unchanged and remains responsible for physical original/temporary-artifact purge.

## Focused validation procedure

1. Run the normal full build (`build-all.cmd`) and start LOCAL.
2. Dashboard: process an intake and verify status changes appear automatically; no browser page refresh required.
3. Package-scoped review: temporarily set Business Admin review mode to `REVIEW_ALL`, process a fresh known-good sample, and open Package Details → Manual Review.
   - verify only that package appears;
   - verify the selected finding renders the exact page shown in the queue;
   - verify the bounding box/highlight is on that page;
   - verify backend logs `REVIEW_PREVIEW_RENDERED ... page=<same page>`.
4. Global review: use `View all reviews` / left navigation and verify tenant-wide actionable items are shown.
5. Restore Business Admin review mode to the desired normal setting after the controlled test.
6. Approver: on a `COMPLETED` row choose `⋮` → `Remove from Dashboard` and confirm in the modal/sheet.
   - row disappears from normal Dashboard and Dashboard counts;
   - package remains retrievable in DB/history/reporting;
   - audit contains `PACKAGE_HIDDEN_FROM_DASHBOARD`;
   - files are not immediately deleted;
   - existing retention process remains responsible for later physical purge.

## Self-review / build status in ChatGPT runtime

- Static source review completed against the previously validated tenant-AI/provider-routing baseline.
- AI/detection/provider source files were not modified by this correction; the existing lifecycle/retention service source is also unchanged.
- Java 21 domain + application sources compile with `javac` in the ChatGPT runtime using temporary SLF4J API stubs.
- Changed frontend TypeScript/TSX files pass TypeScript 5.8 `transpileModule` syntax checks.
- Liquibase master and migration 013 parse as valid XML; current changeset count is **36**.
- A focused exact-page PDF render JUnit test was added.
- Full Maven execution could not be run in the ChatGPT container because `mvn` is not installed.
- Frontend dependency installation could not be completed in the container; therefore the user's local `build-all.cmd` remains the authoritative full TypeScript/Vite and Maven build check.


## Runtime follow-up — review preview ID defect

Package-scoped navigation was runtime validated, but Chrome DevTools showed the preview request as `/api/v1/review-items/[object%20Object]/page-preview`. The original artifact was present. The root cause is API serialization of domain identity value objects in the review queue. The focused correction is documented in `12_Manual_Review_API_ID_Serialization_Fix.md`; exact-page runtime validation remains pending after rebuild/restart.
