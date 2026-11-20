# Report 0019: policy estimate, audit record count, and names in the audit trail

Date: 2026-11-20
Branch: `feature/policy-estimate` (from `feature/lexavault-r1b-tenant-identity` after the step 0 merge)
Outcome: DONE. One point needs the owner's decision: what Strict means for cases with no findings (question 1).

## What was done

**Step 0.**
- Committed the design chat's U022 answers on `feature/ui-compliance-report` as `04ef377`.
- Merged that branch into `feature/lexavault-r1b-tenant-identity`, no fast-forward: **`291eef0`**.
- Build and tests at the merge: 581 backend, 273 frontend, all pass.
- Created `feature/policy-estimate` from it. Nothing pushed.

### B1. The real rule (read from the code, which was not changed)

From `SensitiveDataDetectionService.execute` (the per-finding loop) and `TriggerPackageProcessingService.completePackageProcessingIfReady`:

- **A finding goes to a person** unless all three hold:
  - the review mode is `UNCERTAIN_ONLY`;
  - its confidence is at or above the threshold (`>=`, so exactly 0.80 at 0.80 is automatic);
  - it has a bounding box.

  If all three hold it is confirmed automatically. Under `REVIEW_ALL` every finding waits.
- **A case goes to a person** when at least one of its findings is still waiting (`DETECTED`). The package then moves to `PENDING_REVIEW`.
- **`REVIEW_ALL` with no findings: the case goes straight through, not to a person.** The document goes to redaction with zero targets and the case is delivered. In the check run, 12 cases with no findings were delivered as `COMPLETED`, and the estimate counts them as not going to a person.

  The canvas says Strict means "Every case goes to a person". **The code does not do that, and the estimate follows the code.** Whether to change the wording or the behaviour is the owner's decision (question 1).
- **Unlocated findings** (AI answers whose text could not be placed on the page) and **OCR-guard review signals** are stored without a bounding box. They always wait for a person, under either mode, at any confidence. OCR-guard credit-score values that were located are normal located findings, and the threshold applies to them.
- **Manual regions** (`MANUAL_FALLBACK`) are drawn by a person during manual fallback, after the case is already in review. They never go through the policy, so the estimate leaves them out.
- Supervisor final approval is a separate setting, not part of the review mode, and is not counted.

### B2. Endpoint

`GET /api/v1/business-admin/sensitive-data-policy/estimate?reviewMode=…&threshold=…`
- **Who:** needs `TENANT_POLICY_MANAGE` (Business Administrator only).
- **Entity:** always the signed-in one.
- **Validation:** same as a save. Mode is required ("Review mode is required" / "Unsupported review mode"). For `UNCERTAIN_ONLY` the threshold must be 0.50–0.99. For `REVIEW_ALL` the threshold is ignored and returned as `null`.
- **Window:** cases (packages) of the entity with `created_at` in the last 30 days, up to now.
- **Same rule on both sides.** The SQL returns only facts that don't depend on the policy:
  - the case count;
  - findings grouped by "effective confidence" (the confidence if located, −1 if unlocated);
  - each case's lowest effective confidence.

  `PolicyEstimateRule` then applies the B1 rule to the saved policy (or the default policy, as detection uses it) and to the proposed one. Actual history (finding status) is never read. Manual regions are excluded.
- **Under 20 cases:** `enoughData: false`, the case count, and the two count keys left out.
- **What it reads:** only confidence, bounding-box columns, detector name and counts. Never text, offsets, files, file names or identifiers. It writes no audit record.

Example, for the 21 real cases in the check run (saved policy `UNCERTAIN_ONLY` 0.99, proposed `REVIEW_ALL`):

```json
{"windowDays":30,"cases":21,"enoughData":true,
 "current":{"reviewMode":"UNCERTAIN_ONLY","threshold":0.99,"preset":"CUSTOM","casesToPerson":9,"findingsToPerson":27},
 "proposed":{"reviewMode":"REVIEW_ALL","threshold":null,"preset":"STRICT","casesToPerson":9,"findingsToPerson":27}}
```

### B3. Speed (throwaway database `lexavault-0019-pg`)

- **Data:** the second entity held 2,000 cases (4,000 documents) and 50,000 findings in the window, plus 200 older cases. The database held 2,221 cases and 50,427 findings in all.
- **Endpoint, 5 timed calls:** 36.5, 37.1, 37.4, 35.2, 37.1 ms.
- **`EXPLAIN ANALYZE`:** 31.1 ms.
- **Index:** no new index needed, and no schema change.
- **The plan** is the adapter's own SQL, read from its source file. Full plan in the run folder.

```
Append (actual time=0.564..30.442 rows=73)
  CTE win  -> Seq Scan on document_packages p  (rows=2000; tenant_id and created_at filter)
  CTE f    -> Hash Join (fi.document_id = d.id)  (rows=49001)
                -> Seq Scan on findings fi  (tenant_id, detector_name <> 'MANUAL_FALLBACK'; rows=49401)
                -> Hash Join (win.id = d.package_id) -> Seq Scan on documents d (rows=4400)
  -> Aggregate (count of win)
  -> HashAggregate group by f.eff  (62 rows)
  -> GroupAggregate group by lowest  <- HashAggregate group by package_id (2000 rows)
Planning Time: 2.334 ms   Execution Time: 31.134 ms
```

The planner chose sequential scans because this entity holds almost all rows in the test database. Indexes already exist on `document_packages(tenant_id, created_at)`, `documents(package_id)` and `findings(tenant_id, document_id)` for the case where an entity is a small part of the data.

### A1. Audit record count

- `GET /api/v1/audit-records/count?period=&from=&to=&timeZone=` needs `COMPLIANCE_REPORT_FULL` and returns `{ "count": N }`.
- The period is read by `CompliancePeriod` exactly as for `POST /compliance-reports`. The count uses the same unfiltered query as the appendix's `forEachRecord`. It is not audited.
- **Dialog (full edition only):** "Every record in the period, added as an appendix · **1,204** records. Cases appear by case ID only.", with the number in mono and "1 record" for one.
  - It updates when the period changes.
  - While loading, on failure, or when the Custom range is invalid, the card shows without the count, never an old one.
  - The summary edition never asks for it.
- **Compliance check script:** count 38 = appendix rows 38. A Business Administrator gets 403, recorded once as `AUTHORIZATION_FAILED`.

### A2. Names in the Who column

- Admin › Audit trail shows the recorded `actorDisplayName` when there is one. Otherwise:
  - "LexaVault (automatic)" for the system;
  - "Person <last 8>" for older records.
- An opened record shows a "Person" row with the name (only when recorded), above "Person ID".
- The Person filter still sends the ID. Each person's option shows their latest recorded name. If two IDs share a name, the short ID is added so the options stay apart.

## Commands run and results

| Command | Result |
|---|---|
| `.\Build-LexaVault-All.ps1` at merge `291eef0` | PASS, backend 581 |
| `npm run test:standalone` at the merge | PASS, 273 (10/103/17/78/65) |
| `cd backend; mvn -o clean install` (final) | PASS, 597 |
| `npm run test:standalone` (final) | PASS, 281 (10/108/17/81/65) |
| `.\Build-LexaVault-All.ps1` (final) | PASS (backend 597, tsc, UI framework contract, Vite build) |
| `.\test-tools\policy-estimate\Invoke-PolicyEstimateCheck.ps1` (new) | PASS, 27/27 checks |
| `.\test-tools\no-redacted-values\Invoke-NoRedactedValueCheck.ps1` | PASS, 0 matches |
| `.\test-tools\compliance-report\Invoke-ComplianceReportCheck.ps1` | PASS, 85/85 (3 new 0019 checks) |

**The estimate check** (21 real cases through intake and processing with the 0015 mock Ollama, policy `UNCERTAIN_ONLY` 0.99):
- **Real outcome:** 9 fake-value cases went to `PENDING_REVIEW` with 27 findings at 0.92–0.97. 12 plain or blank cases with no findings were `COMPLETED`.
- **Estimate for the saved policy:** 9 cases and 27 findings, equal to the real outcome.
- **Independent SQL:** `REVIEW_ALL` and 0.80 match it.
- **Validation:** 7 refused with 400.
- **Other roles:** 5 refused with 403.
- **Audit:** none written.
- **Response:** no found value, file name or identifier.
- **Entity boundary:** the first entity's estimate is unchanged after seeding the second.

**Corrected during the work (my own new tests only):** three expected values in tests written this session were wrong and were fixed. No product code was changed for them, and no existing test was changed.
- `PolicyEstimateServiceTest`: at threshold 0.79 the 0.79 finding is automatic, so 1 case waits, not 2.
- `AuditRecordCountApiTest`: last month in UTC holds 2 of the seeded records, not 0.
- The check script's first two runs:
  - one had a test identifier length the server refused;
  - one had an ambiguous column in a diagnostic query, and a saved policy of 0.80, under which every real case went straight through and nothing was compared. The script now saves 0.99.

## Tests: counts per module

- **Backend 597** (+16): domain 51, application 214 (+9), scan bridge 47, infrastructure 14, API 271 (+7). No failures.
- **Frontend 281** (+8): `test:auth` 10, `test:vocabulary` 108 (+5), `test:ui-framework` 17, `test:oidc-signin` 81 (+3), `test:tablet` 65. No failures.

New tests:
- **`PolicyEstimateServiceTest` (9):** just above, at and just below the threshold; unlocated findings; no findings; `REVIEW_ALL`; manual regions left out; both sides equal the rule applied finding by finding on 60 random cases; presets; default policy; under 20 cases; other entities and the window edges; validation.
- **`PolicyEstimateApiTest` (4):** response shape (canvas numbers 148 → 512 and 340 → 2,410); no counts under 20 cases; refused values read nothing; `TENANT_POLICY_MANAGE` held only by Business Administrator.
- **`AuditRecordCountApiTest` (3):** count equals the appendix records with New York and UTC period edges; other entity; validation; `COMPLIANCE_REPORT_FULL` held only by IT Administrator.
- **Frontend rules:** `countQuery`, `recordCountWords`, `personName` with a name, record detail "Person", `personOptions`.
- **Browser:**
  - the record count shows, updates, is left out while loading or on failure, and is never asked for in the summary edition;
  - the Who column, the record detail and the Person filter by name.

## Files changed

- **Backend (new):**
  - `PolicyEstimateReadPort`
  - `PolicyEstimateRule`
  - `PolicyEstimateService`
  - `JpaPolicyEstimateReadAdapter` (infrastructure, `business/`)
  - `PolicyEstimateController`
  - `AuditRecordCountController`
- **Backend (changed):**
  - `ComplianceReportService.appendixRecordCount`
  - `ApplicationServiceConfig` (one bean)
  - `BusinessAdminConfigurationService`: the threshold limits are now package-visible, so the estimate uses the same 0.50 and 0.99
- **Backend tests:** the three new test classes above.
- **Frontend:**
  - `api/client.ts` (`auditRecordCount`)
  - `types/index.ts` (`actorDisplayName`, `caseId` on audit records)
  - `compliance/ComplianceReport.tsx`, `complianceRules.ts` and its test
  - `admin/AuditArea.tsx`, `auditRules.ts` and its test
  - `e2e-oidc/complianceReport.spec.ts`, `e2e-oidc/adminAudit.spec.ts`
- **Test tools:**
  - new `test-tools/policy-estimate/` (`Invoke-PolicyEstimateCheck.ps1`, `MakeEstimatePdf.java`, `README.md`)
  - `test-tools/compliance-report/Invoke-ComplianceReportCheck.ps1` and its README (A1 count check)
- `docs/work/0019-task.md`, this report.

No schema change, no new library, no change to detection or any other frozen area. Shared-issuer stays off and migration 037 stays out of the master changelog.

## Commits

- `04ef377` docs(work): U022 report: owner's answers (on `feature/ui-compliance-report`)
- `291eef0` Merge branch 'feature/ui-compliance-report' into feature/lexavault-r1b-tenant-identity
- **`c7813ed`** feat(policy): 0019 policy estimate endpoint, audit record count, names in the audit trail (code, tests, task, this report), on `feature/policy-estimate`
- A follow-up commit records this hash. Nothing pushed or merged.

## Not done, and why

- The U023 screen itself (by the task: this task builds only what it reads).
- The estimate counts cases by `document_packages.created_at` ("received"). A reviewer who later draws a box on an unlocated finding (`FINDING_BOUNDING_BOX_UPDATED`) makes it look located in the stored record. Such findings are then estimated as located. This is rare, and noted here rather than guessed around.

## Questions for the owner

1. **Strict and cases with no findings.** Under `REVIEW_ALL` a case with no findings goes straight through today, and the estimate counts it that way. The canvas says "Every case goes to a person". Which is wanted?
   - (a) Change the wording to something like "Every finding goes to a person; cases with nothing found are delivered".
   - (b) Change the behaviour so every case stops for a person under Strict. That would be a change to frozen V1 processing, so it needs its own task.
2. **Window by created time.** The 30 days count from when the case was created (received). OK, or should it be by processing time?

## Suggested next step

U023 (the policy screen) using this endpoint, after question 1 is answered.
