# Unit 23 Verification Plan — Shared Dashboard + Operational Reporting

Unit 23 is implemented in source and must be verified without starting Unit 24.

## Build / Startup

1. Run `mvn clean verify` from `backend/`.
2. Run `npx tsc --noEmit` and `npm run build` from the frontend.
3. Start local PostgreSQL/Ollama/Tesseract stack and confirm Liquibase 011 applies cleanly.

## Dashboard Runtime

Verify with representative data covering Processing, PENDING_REVIEW, PENDING_FINAL_APPROVAL, COMPLETED, FAILED and REJECTED/CANCELLED states:

- overview counts match persisted package states;
- clicking KPI cards applies the matching stage filter;
- stage chips show accurate counts and filter server-side;
- search finds values only through configured searchable business identifiers;
- non-searchable identifier values are not matched by dashboard search;
- multiple visible identifiers (for example Contract Number + Member Number) appear as separate columns in configured order;
- switching Business Admin Primary identifier changes the Primary marker/ordering without exposing package UUIDs;
- Business Admin Dashboard Columns policy can allow/disallow identifier and operational fields, choose default-visible fields, and set default order; Primary cannot be disabled;
- dashboard API does not return values for identifier columns Business Admin has disallowed;
- Columns menu can show/hide/reorder only approved fields and persists user preference;
- collapse/expand Overview persists;
- pagination and sort are server-side and return stable non-duplicated pages;
- attention links route to Manual Review, approval detail or technical detail appropriately;
- one shared Dashboard works under USER, REVIEWER, APPROVER and ADMIN without separate dashboard layouts.

## Reports Runtime

Verify `/reports` and `/api/v1/operations/reports` against database counts for:

- Manual/API/Watched Folder intake;
- WAITING_FOR_TRIGGER, duplicate and rejected observations;
- processing/review/approval/completed/failed/rejected outcomes;
- exported package count and successful export cycles;
- original/redacted/technical-hold deadlines due in the next 24 hours;
- purged package count.

## Tenant / Safety

- Tenant A must not see Tenant B dashboard rows, identifiers, counts or reports.
- Search must not leak cross-tenant identifiers.
- No raw sensitive document content is returned by dashboard/report APIs.
- Internal package UUID may remain in API transport for routing but is not shown as a normal business column.

## Regression

Run high-level regressions for Units 16–22: digital, OCR, review, technical failure, Business Record reuse, Business Admin policy, export/reprocess/retention.

## Scope

Do not implement Unit 24, IT Admin, production identity, IDP, new workflow states, large analytics infrastructure or broad tests during this verification pass.

## Self-Review Edge Checks

- Search values containing `%`, `_`, or `!` are treated as literal identifier text, not SQL wildcard controls.
- `successfulExportCycles` equals the number of persisted successful package export records.
- Any legacy compatibility package in APPROVED or VAULTED remains visible in the Processing filter rather than only in All.
- Saving Dashboard Columns persists tenant-scoped settings in `dashboard_column_settings`; a second tenant retains independent defaults and permissions.
