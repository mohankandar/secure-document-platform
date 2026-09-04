# Unit 23 Self-Review — Shared Dashboard + Operational Reporting

**Status: IMPLEMENTED IN SOURCE — AWAITING KIRO BUILD/RUNTIME/UI VERIFICATION**

Unit 23 was reviewed against the approved Unit 22 baseline and the frozen Dashboard UX decisions. Unit 24 has not been started.

## Scope Review

- One shared Dashboard remains the only operational dashboard; roles change available actions, not the dashboard layout.
- Overview/KPI area is collapsible and KPI cards apply the same server-side stage filters as the quick chips.
- Quick filters cover All, Processing, Needs Review, Needs Approval, Exported, Technical Attention and Rejected.
- Search is tenant-scoped and only matches active Business Admin-configured searchable business identifiers.
- Displayable tenant identifiers are eligible for business-facing columns; Business Admin controls allowed/default columns and default order for both identifiers and operational fields. The Primary identifier is forced allowed/default-visible. Internal UUIDs are transport/routing values only and are not rendered as normal columns.
- Users can show/hide/reorder only permitted columns and retain local preferences; disallowed identifier values are also removed from the dashboard API payload.
- Manual Review remains a separate focused workbench.
- Reports cover Intake Summary, Exceptions/Rejections, Processing Outcomes, Export Status, and Aging/Retention using structured operational persistence.
- No IT/Platform Admin, provider configuration, production identity, analytics warehouse, or Unit 24 work was added.

## Self-Review Corrections Applied

1. **Successful export-cycle reporting** — changed the report query to count persisted successful `package_export_records` directly instead of inferring cycle count from each package's maximum cycle number. This makes the metric represent actual confirmed successful export cycles.
2. **Configured-identifier search** — escaped SQL `LIKE` wildcard characters (`%`, `_`, `!`) so user search text is treated literally while remaining parameterized and tenant-scoped.
3. **Legacy lifecycle visibility** — existing compatibility statuses `APPROVED` and `VAULTED` are explicitly included in the Processing bucket so a legacy record cannot disappear from all stage filters while still being counted in All.
4. **Business Admin column policy** — added tenant-scoped `dashboard_column_settings`, a Business Admin UI/API for allowed/default/order settings, Primary-identifier protection, and API-side removal of disallowed identifier values. This closes the partial implementation gap where system columns were hard-coded and Admin could not control their allowed/default state.

## Static Checks Completed Here

- All 22 backend XML files parse successfully as XML, including migration 011.
- All 13 frontend TypeScript/TSX source files parse with zero TypeScript parser diagnostics.
- Source diff remains constrained to Unit 23 dashboard/reporting implementation, Unit 23 documentation, and status/documentation updates reflecting Unit 22 approval.

## Build/Runtime Verification Still Required

This execution environment does not contain Maven. The clean source ZIP also intentionally does not carry frontend `node_modules`; dependency installation could not complete in this environment. Therefore this self-review does **not** claim a successful Maven, TypeScript type-check, Vite build, PostgreSQL migration, API runtime, or browser UI run.

Kiro must perform the verification plan in `22-unit23-verification-plan-2026-08-29.md` using the established real local stack, make only minimal defect fixes if required, and report results. Do not start Unit 24.

## Targeted Java Compile Checks

- Java 21 parser/type compilation for the new operations application models/services and input/output ports, including Dashboard column configuration: **PASS**.
- Java 21 compilation of `JpaOperationsReadAdapter` plus the new dashboard-column persistence entity/repository/adapter against lightweight temporary framework stubs: **PASS**.
- Java 21 compilation of the modified Business Admin controller/DTOs and Dashboard-column update request against lightweight temporary framework stubs: **PASS**.
- Earlier targeted compilation of `OperationsController` and the dashboard/report response DTOs: **PASS**.
- These stubs and class outputs were created outside the project tree and are not included in the package.
