# Unit 23 Final Merge — Shared Dashboard + Operational Reporting

**Status: FINAL MERGED SOURCE — AWAITING FINAL KIRO VERIFICATION**

This checkpoint reconciles the two ChatGPT Unit 23 implementations with the Kiro-verified Unit 23 baseline. Unit 24 has not been started.

## Authoritative Base

The base is the Kiro-verified Unit 23 workspace that passed the real local stack on 2026-08-30: Java 21, PostgreSQL, Ollama `gemma3:12b`, Tesseract, backend `mvn clean verify`, frontend typecheck/Vite build, Dashboard/report runtime checks, tenant isolation/security checks, and high-level Units 16–22 regressions.

Kiro made one genuine source correction in that baseline: Liquibase migration 011 was changed from the unavailable 4.28 XSD to the bundled/project-consistent 4.27 XSD. That fix is retained unchanged in this merge.

## Final Merge Delta

Only the stronger frozen-requirement deltas from the alternate Unit 23 implementation were added:

1. **Business Admin Dashboard column policy**
   - Tenant-scoped `dashboard_column_settings` persistence.
   - Business Admin API/UI controls Allowed, Default visible, and default order for eligible identifier and operational columns.
   - The Primary business identifier is always Allowed and Default visible at tenant-policy level.
   - Dashboard APIs return only permitted identifier columns/values; users may personalize only the permitted column catalog.

2. **Literal configured-identifier search**
   - SQL `LIKE` metacharacters `%`, `_`, and `!` are escaped so search input is treated as literal business identifier text.

3. **Successful export-cycle metric**
   - Report count uses actual persisted `package_export_records` rows rather than inferring total cycles from each package's maximum cycle number.

4. **Legacy compatibility stage visibility**
   - Existing `APPROVED` and `VAULTED` compatibility states are explicitly included in the Processing overview/filter/sort path.

5. **Cleanup**
   - Verification-only `backend/smoke-test.pdf` is not included in the final clean source package.

## Migration Safety

Liquibase 011 retains Kiro's verified 4.27 schema declaration and unchanged `011-01` / `011-02` changesets. The merge adds only `011-03-dashboard-column-settings`. A database that already ran the Kiro baseline should therefore see one new Unit 23 changeset to apply.

## Self-Review Checks

- All project XML files parse successfully.
- All frontend TS/TSX files parse with zero TypeScript parser diagnostics.
- New application Dashboard-column model/port/service compile under Java 21.
- `JpaOperationsReadAdapter` compiles under Java 21 against lightweight temporary framework stubs.
- New Dashboard-column JPA entity/repository/adapter compile under Java 21 against lightweight temporary framework stubs.
- Modified Business Admin controller/DTO/update request compile under Java 21 against lightweight temporary framework stubs.
- No Unit 24/IT Platform Admin/identity/provider-management functionality was added.

Full Maven/Spring/PostgreSQL/Ollama/Tesseract/browser verification remains required on the merged source before Unit 23 approval.
