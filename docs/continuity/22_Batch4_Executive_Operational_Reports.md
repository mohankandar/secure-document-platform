# Batch 4 — Executive and Operational Reports

Status: implemented for user acceptance testing on 2026-09-01.

## Scope

- One Reports area with an Executive Overview and a capability-gated Operational Details view.
- Product-defined `REPORT_VIEWER` role, executive/operational/export capabilities, backend enforcement, and tenant-scoped aggregate CSV export with an audit event.
- Shared 7/30/90-day period, intake-source, and current-outcome filters; an equal-length prior-period comparison.
- Executive volume, documents/pages, completion, delivery, backlog/attention, delivery-time percentiles, automation/review share, and daily flow trend.
- Operational stage, watched-folder, export, and retention-risk detail.

## Metric definitions

- Intake batches, documents, and pages use packages created within the selected period and matching the selected source/current-outcome filters.
- Completed packages are selected-period intake packages whose current terminal workflow status is `COMPLETED`.
- Delivered packages are unique packages with a structured package export record in the selected period. This is intentionally distinct from workflow completion.
- Delivery-time percentiles measure package creation to first successful package export for selected-period intake.
- Current backlog and attention are point-in-time counts filtered by source and current outcome; they are independent of the selected flow period.
- Straight-through delivery means a delivered package has no structured `review_tasks` record. No audit-message or application-log parsing is used.
- Retention overdue means a scheduled deletion timestamp is before report generation and the corresponding purge timestamp is absent.
- Due-within-24-hours excludes already-overdue records, so the two operational risk measures do not overlap.

The current-outcome filter is explicitly a package's current workflow outcome, not a claim that it held that status at every point in the selected period.

## Authorization

- `REPORT_VIEWER`: Executive Overview and aggregate CSV export.
- `BUSINESS_ADMIN`: Executive Overview.
- `TENANT_IT_ADMIN` and `PLATFORM_OPERATIONS_ADMIN`: Executive Overview, Operational Details, and aggregate CSV export.
- `ADMIN`: all capabilities.
- `USER`, `REVIEWER`, and `APPROVER`: no tenant-wide Reports access by default.

The API requires reporting capabilities. Operational-only values are suppressed unless the principal has `REPORT_OPERATIONAL_VIEW`; UI tab visibility is not treated as the security boundary.

## Explicit exclusions

- No custom report builder, scheduled report distribution, predictive forecasting, or custom templates.
- No dashboard KPI, polling, filter, action, or layout change.
- No global theme redesign.
- No invented SLA health label. A future SLA KPI requires a real tenant-configured target; V1 still reports median/P95 delivery time and oldest backlog age.
