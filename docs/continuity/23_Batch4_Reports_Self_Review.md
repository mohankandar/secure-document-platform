# Batch 4 Reports — Self Review

## Verification completed

- Frontend TypeScript and Vite production build passes.
- Reports navigation and route require `REPORT_EXECUTIVE_VIEW`.
- Operational Details is separately gated by `REPORT_OPERATIONAL_VIEW`.
- CSV endpoint requires `REPORT_EXPORT`, is tenant scoped, contains aggregate metrics only, and records `REPORT_CSV_EXPORTED`.
- `REPORT_VIEWER` is recognized at both LOCAL and OIDC trust boundaries and maps only to Executive Overview plus aggregate export.
- Report SQL reads structured package, document, export, lifecycle, intake-observation, and review-task tables; it does not parse application logs or audit-message text.
- Range is bounded to 366 days; source and current-outcome values are allowlisted before the read adapter is called.
- Straight-through/review rates use `review_tasks.package_id`; retention due-soon and overdue counts are non-overlapping.
- Dashboard source and global `index.css` match the accepted Batch 3 SHA-256 values byte-for-byte.

## Environment limitation

The supplied environment contains a Java 17 runtime but not `javac` or Maven, so the backend Maven suite could not be executed here. Focused Java authorization tests were added for independent execution. Kiro remains reserved for verification only after user acceptance.

## Manual acceptance matrix

1. `REPORT_VIEWER`: Reports link visible; Executive Overview and CSV available; Operational Details absent.
2. `BUSINESS_ADMIN`: Executive Overview visible; no CSV or Operational Details.
3. `TENANT_IT_ADMIN` / `PLATFORM_OPERATIONS_ADMIN`: both views and CSV available.
4. `USER`, `REVIEWER`, `APPROVER`: Reports link absent and direct `/reports` navigation denied.
5. Switch 7/30/90 days, intake source, and current outcome; values and equal-period comparison refresh.
6. Confirm Completed and Delivered remain separately labelled and reconcile with workflow status/export records.
7. Confirm overdue retention is visually distinct from items merely due within 24 hours.
8. Confirm the Dashboard appearance and behavior are unchanged.
9. Export CSV as `REPORT_VIEWER`; confirm period/filter context plus aggregate metrics only, and verify `REPORT_CSV_EXPORTED` is audited.
