# Unit 23 Implementation — Shared Dashboard + Operational Reporting

**Status: IMPLEMENTED IN SOURCE — AWAITING BUILD/RUNTIME/UI VERIFICATION**

Unit 23 implements the approved shared operational dashboard and compact V1 reporting experience on top of the structured Business Record, Intake Batch, review, export and retention facts completed in Units 20–22.

## Shared Dashboard

- One dashboard for all roles; roles continue to control capabilities/actions rather than separate dashboard designs.
- Collapsible Overview section with clickable KPI cards for Processing, Needs Review, Exported and Technical Attention.
- Quick stage chips: All, Processing, Needs Review, Needs Approval, Exported, Technical Attention and Rejected.
- Server-side stage filtering, configured-identifier search, sorting and pagination via `/api/v1/operations/dashboard`.
- Search is restricted to tenant Business Admin-configured active/searchable identifiers.
- Business-facing rows use configured identifier labels and values; internal package UUIDs are not presented as business columns.
- Multiple displayable business identifiers can appear as separate columns.
- Business Admin now has a Dashboard Columns policy for allowed columns, default-visible columns, and default order across both identifier and operational fields. The Primary identifier is protected as allowed/default-visible.
- User can show/hide/reorder only the Business Admin-permitted columns; preference is stored locally per browser profile and is automatically pruned if Admin later removes permission.
- Overview collapsed state, stage filter, search and sort preference are remembered.
- Default system columns include Received, Stage, Documents and Attention; optional system columns include Intake Source, Export Status, Last Updated and Export Cycles.
- Attention routes review items to Manual Review and final-approval/technical items to package detail.

## Operational Reporting

`/api/v1/operations/reports` exposes compact structured counts for:

- Intake Summary;
- Exceptions & Rejections;
- Processing Outcomes;
- Export Status;
- Aging & Retention.

The report reads persisted structured operational tables. It does not parse free-text logs or audit details.

## Persistence / Read Model

- Added an application read port and query service for dashboard/reporting.
- Added a JPA/native-query infrastructure adapter optimized for tenant-scoped operational reads.
- Added dashboard-support indexes plus tenant dashboard-column policy persistence in Liquibase 011.
- No new business domain state or workflow state was introduced.

## Frontend

- Replaced the transitional package table with the approved modern dashboard direction.
- Added `/reports` page and simplified primary navigation: Dashboard, Upload Documents, Manual Review, Reports; admin-only Business Admin and Audit remain under Administration.
- Full Dashboard remains business-facing; deeper IT/platform management remains Unit 24.

## Explicitly Not Included

- IT/Platform Admin configuration;
- production identity/OIDC/IDP work;
- durable analytics warehouse;
- S3 runtime hardening;
- broad automated tests;
- Unit 24 or Unit 25 work.

## Self-Review Corrections

Before packaging, the Unit 23 read model was tightened to count actual persisted successful export records, treat configured-identifier search wildcards literally, keep legacy APPROVED/VAULTED compatibility states visible under Processing, and close the Business Admin allowed/default-column configuration gap. See `23-unit23-self-review-2026-08-29.md`.
