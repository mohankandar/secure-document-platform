# Unit 23 Final Merge — Kiro Verification Plan

**Verification only. Do not reimplement Unit 23. Do not start Unit 24.**

The previous Kiro Unit 23 baseline already passed the comprehensive real-stack verification. This final pass must confirm the merged delta did not regress that baseline and must specifically exercise the stronger Business Admin Dashboard-column policy.

## 1. Build / Startup

- `mvn clean verify` from `backend/`.
- `npx tsc --noEmit` and `npm run build` from `frontend/securedoc-ui/`.
- Start the normal real local stack with PostgreSQL, Ollama and Tesseract.
- Confirm Liquibase uses the retained 4.27 XSD and applies new changeset `011-03-dashboard-column-settings` cleanly.
- If the test database already contains `011-01` and `011-02`, confirm only the new `011-03` is pending/applied and there is no checksum/startup problem.

## 2. Business Admin Dashboard Columns — Required Focus

Using the Business Admin page/API, verify the tenant can configure both identifier and operational fields:

- identifiers such as Contract Number and Member Number;
- Received;
- Stage;
- Documents;
- Attention;
- Intake Source;
- Export Status;
- Last Updated;
- Export Cycles.

Verify:

- each eligible column can be Allowed/disallowed except the Primary identifier;
- Allowed columns can be Default-visible or not;
- default order can be changed;
- Primary remains Allowed + Default at tenant-policy level and cannot be disabled;
- save/reload returns the persisted tenant configuration;
- a second tenant has independent settings;
- disallowed operational columns are absent from the Dashboard column catalog;
- disallowed identifier columns are absent from the Dashboard column catalog **and their identifier values are not returned in dashboard item payloads**;
- users can show/hide/reorder only the permitted catalog;
- a user's existing local column preference is pruned if Business Admin later removes permission;
- removing permission does not expose or fall back to an internal UUID.

Restore the preferred default configuration after testing.

## 3. Existing Identifier Semantics

- Exactly one Primary identifier remains enforced by Unit 20/21 behavior.
- Multiple identifiers may remain Required independently of Primary.
- `displayable` remains the prerequisite for an identifier to be eligible for Dashboard-column policy.
- Primary switching Contract Number -> Member Number still updates the Dashboard correctly without creating/merging Business Records unexpectedly.

## 4. Search Edge Cases

Confirm configured searchable identifiers still work normally and search remains tenant-scoped.

Additionally test identifier values/search terms containing literal `%`, `_`, and `!` characters. They must match literal text and must not broaden matches as SQL wildcards.

## 5. Report / Stage Delta Checks

- `successfulExportCycles` equals the actual count of persisted successful `package_export_records` rows.
- Exported package count remains distinct package count.
- If practical, seed or inspect compatibility statuses `APPROVED` / `VAULTED` and confirm they appear under Processing in overview, stage filter and stage sort. Do not change the normal Unit 22 workflow to create them.

## 6. Baseline Non-Regression

Recheck at minimum:

- shared Dashboard loads for USER/REVIEWER/APPROVER/ADMIN;
- KPI/stage counts and server-side filter/search/sort/pagination;
- Manual Review navigation and Technical Attention separation;
- all five reports;
- tenant isolation and backend action authorization;
- no sensitive document content/secrets in Dashboard/report payloads;
- high-level Units 16–22 regression, using the prior verification evidence where no changed code path requires destructive reruns.

## 7. Cleanup / Final Report

Remove verification-only documents/output/build artifacts before packaging. Do not package `target`, `node_modules`, `dist`, `.git`, `.idea`, storage/output directories, test PDFs, screenshots, or temporary scripts.

Return:

1. backend build result;
2. frontend build result;
3. Liquibase 011-03 result, including upgrade-from-previous-011 behavior;
4. Business Admin allowed/default/order result for identifiers + operational columns;
5. Primary protection result;
6. tenant independence result;
7. API suppression of disallowed identifier values;
8. user preference pruning/permitted-column result;
9. literal `%` / `_` / `!` search result;
10. export-cycle count result;
11. APPROVED/VAULTED compatibility result if practical;
12. shared Dashboard/report/security/tenant non-regression result;
13. Units 16–22 regression summary;
14. exact source files changed by Kiro, if any;
15. explanation of every correction;
16. final backend/frontend build results;
17. remaining known Unit 23 limitations;
18. recommendation: `UNIT 23 APPROVE` or `DO NOT APPROVE`.

Stop there. **Do not start Unit 24.**
