# LexaVault V1 Reporting

## Report area

V1 has one Reports area with:

- Executive Overview;
- aggregate Operational Reports;
- tenant-scoped aggregate CSV export for roles that have export capability.

Current authorization is defined only in `AUTHORIZATION.md`.

## Filters and comparison

Reports use shared:

- 7 / 30 / 90 day periods;
- intake-source filter;
- current-outcome filter;
- equal-length prior-period comparison.

The current-outcome filter means the package's **current workflow outcome**. It does not claim that the package held that status throughout the selected period.

## Metric definitions

- **Intake batches, documents, pages:** packages created within the selected period and matching selected source/current-outcome filters.
- **Completed packages:** selected-period intake packages whose current terminal workflow status is `COMPLETED`.
- **Delivered packages:** unique packages with a structured package export record in the selected period. Delivery is intentionally distinct from workflow completion.
- **Delivery-time percentiles:** package creation to first successful package export for selected-period intake.
- **Current backlog / attention:** point-in-time counts filtered by source/current outcome and independent of the selected flow period.
- **Straight-through delivery:** delivered package has no structured `review_tasks` record. Reports do not infer this by parsing audit messages or application logs.
- **Retention overdue:** scheduled deletion timestamp is before report generation and the matching purge timestamp is absent.
- **Due within 24 hours:** excludes already-overdue records so the two retention-risk measures do not overlap.

Executive views include volume, documents/pages, completion, delivery, backlog/attention, delivery-time percentiles, automation/review share, and daily-flow trend.

Operational views include stage, watched-folder, export, and retention-risk detail while remaining tenant scoped and safe.

## Authorization boundary

- `BUSINESS_ADMIN`: Executive + Operational + CSV export.
- `REPORT_VIEWER`: Executive + aggregate Operational read-only; no CSV export.
- `TENANT_IT_ADMIN`: Executive + Operational + CSV export.
- `PLATFORM_OPERATIONS_ADMIN`: Executive + Operational + CSV export.
- explicit `ADMIN`: all report capabilities.
- Document Processor/Supervisor: no tenant-wide Reports access by role alone.

Operational visibility is enforced by backend capability checks; hiding a frontend tab is not the security boundary.

## Explicit exclusions

V1 does not include:

- custom report builder;
- scheduled report distribution;
- predictive forecasting;
- custom templates;
- invented SLA-health labels without a configured SLA target.
