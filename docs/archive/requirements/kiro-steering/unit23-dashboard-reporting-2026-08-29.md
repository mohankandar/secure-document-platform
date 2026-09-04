# Unit 23 — Shared Dashboard + Operational Reporting

## Frozen Scope

- One common Dashboard for all roles; roles control actions, not duplicate dashboard pages.
- Collapsible Overview/KPI section; KPI cards apply the same stage filters as quick chips.
- Quick filters: All, Processing, Needs Review, Needs Approval, Exported, Technical Attention, Rejected.
- Search only Business Admin-configured searchable business identifiers.
- Multiple displayable identifiers may be visible as independent columns; exactly one remains Primary.
- Business Admin controls the Dashboard column policy: which configured identifier/system columns are permitted, which are visible by default, and their default order. Identifier `displayable` remains the prerequisite for an identifier to be eligible. Users may hide/show/reorder only permitted columns locally. The Primary identifier is always permitted and default-visible.
- Internal package/business/workflow UUIDs are not normal business-facing columns.
- Server-side filtering/sort/pagination.
- Compact reports: Intake Summary, Exceptions/Rejections, Processing Outcomes, Export Status, Aging/Retention.
- Reports derive from structured operational persistence, not parsing logs/audit detail.
- Manual Review remains the Unit 19 focused workbench.
- Do not start Unit 24 IT/Platform Admin while verifying Unit 23.
