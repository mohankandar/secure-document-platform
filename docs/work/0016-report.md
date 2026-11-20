# Report 0016: audit detail

Date: 2026-11-19
Branch: `feature/audit-detail` (from `feature/lexavault-r1b-tenant-identity` @ `f1d2a74`)
Outcome: DONE

## What was done

**Step 0. 0015 finished and merged.**
- On `feature/no-redacted-values`: committed the owner's answers in `0015-report.md` (`ccac015`).
- Merged into `feature/lexavault-r1b-tenant-identity`, no fast-forward, with the given message. Merge commit: **`f1d2a74`**.
- Merged branch, backend: `mvn -o clean install` passed, **459** tests, 0 failures (domain 31, application 142, scan bridge 47, infrastructure 8, API 231).
- Merged branch, frontend: `npm run test:standalone` gave auth 10, vocabulary 90, ui-framework 17 and oidc-signin 69, all passing. `test:tablet` passed 53 of 55; see "Tests" for the 2 timeouts and the rerun.
- Created `feature/audit-detail` from `f1d2a74`. Nothing pushed.

**A1. Display name and case ID, recorded at the time of the action.** No call site changed.

How the display name reaches `AuditService`:
1. `RequestActorDisplayName` (new, application layer, framework-neutral) holds the name for the current thread only.
2. `RequestContextResolver` fills it at the point where it resolves the request's principal:
   - OIDC: the display-name claim (`SecurityProperties.displayNameClaim`, default `name`, read by the existing identity normalizer). When there is none, the username.
   - LOCAL: the username (`demo-user`). There is no name claim.
   - Machine clients (`resolveIntegrationPrincipal`): the holder is cleared. A machine client is not a person.
3. `RequestActorDisplayNameFilter` (new, API layer) clears the holder before and after every request, so a pooled thread never carries a name into the next request.
4. `AuditService` asks the holder for the name of the record's actor. It gets a name only if the actor is the exact person the request was bound for. The system actor (`000…000`) never gets one, even inside a person's request. Work on other threads (worker, schedulers) sees nothing.
5. The name is trimmed, control characters are dropped, and it is cut to 200 characters.

What "case ID" is, and which records get it:
- It is the package id: the "Case ID" the Cases page shows (`CaseDrawer`, `pkg.packageId`). It is a UUID, not a business identifier, so no stop was needed.
- `AuditCaseIds` works it out from what the record already holds. There is no query.
  - The resource type is `DocumentPackage` (or `Package`, or `IntakeBatch`): the case ID is the resource id. `IntakeBatch` appears once, on `PACKAGE_CREATED`, the case's creation record. I added it after the throwaway-database run showed that record was missing its case ID.
  - Otherwise, when the detail holds `package=<uuid>` or `packageId=<uuid>`, that UUID is used. This covers `REVIEW_TASK_CREATED` and `REVIEW_APPROVED`.
  - Otherwise the case ID is empty. Anything that is not a UUID is refused, not guessed.
- What gets it today: the 31 `DocumentPackage` calls, `PACKAGE_CREATED`, and the two review-task records above.
- What does not: most `Document`, `Finding` and `Artifact` records (about 33 call sites). Their call sites pass only the document, finding or artifact id, so the package cannot be found without a query or a call-site change. See question 1.

Both values are exposed as new fields:
- **API and JSON export:** `actorDisplayName` and `caseId` are added after the existing keys. They are always present, and `null` when empty.
- **CSV export:** two columns are appended after `detail`. The existing columns keep their order.

**A2. Migration `040-audit-actor-name-case-id.xml`**, registered in the master changelog after 039.
- One changeSet adds `actor_display_name VARCHAR(200)` and `case_id VARCHAR(100)`, both nullable.
- It has a `not columnExists` precondition (`MARK_RAN`). Its rollback drops both columns.
- No backfill.
- It ran on throwaway databases only (`lexavault-0016-pg`, `lexavault-0015-pg`), never against `securedoc-postgres`.

**A3. Policy changes record the type names, the preset and the previous values.**
- The first four keys are unchanged in order and format, so `auditRules.ts` still reads `threshold=` (the first match, which is the new value).
- Then `enabledTypes=` (sorted names), `preset=`, and `previous=[same 6 keys]`, or `previous=none` when the tenant had no saved policy.
- The preset is worked out on the server from the saved values.

Before:
```
Sensitive-data policy changed: enabledCount=4, threshold=0.80, reviewMode=REVIEW_ALL, maskSsnLastFour=true
```
After (from the throwaway-database run):
```
Sensitive-data policy changed: enabledCount=4, threshold=0.80, reviewMode=UNCERTAIN_ONLY, maskSsnLastFour=true, enabledTypes=CREDIT_CARD_NUMBER,CREDIT_SCORE,DATE_OF_BIRTH,SSN, preset=BALANCED, previous=[enabledCount=4, threshold=0.80, reviewMode=REVIEW_ALL, maskSsnLastFour=true, enabledTypes=CREDIT_CARD_NUMBER,CREDIT_SCORE,DATE_OF_BIRTH,SSN, preset=STRICT]
```

Preset rule: one shared helper, `PolicyPreset.of(reviewMode, threshold)` in the domain (`domain/business/PolicyPreset.java`), for U023 and the compliance report to reuse.
- `REVIEW_ALL` → `STRICT`, at any threshold.
- `UNCERTAIN_ONLY` at exactly 0.80 → `BALANCED`.
- `UNCERTAIN_ONLY` at exactly 0.70 → `LIGHT`.
- Anything else → `CUSTOM`.
- "Exactly" means within 1e-9, which only absorbs floating-point noise. So 0.79, 0.81, 0.801 and 0.799 are all `CUSTOM`.

What the detail holds: a test checks that it contains only the 7 known keys, numbers, booleans and category or enum names. Nothing else was added. The longest possible detail is well under the 1000-character cut.

**A4. `AuditAction.COMPLIANCE_REPORT_CREATED`** is added, with Javadoc saying it is written in step 3 and that its detail will hold the period, the edition and `fingerprint=<SHA-256 hex>`. Nothing writes it yet, and there is no UI change. The frontend test confirms the default sentence, "Compliance report created".

**A5. Follow-ups from 0015.**
- `test-tools/lexavault-acceptance`: `Invoke-TestApi.ps1` and `Send-TestDocument.ps1` now default to `DOCUMENT_SUPERVISOR,BUSINESS_ADMIN,REPORT_VIEWER,TENANT_IT_ADMIN,PLATFORM_OPERATIONS_ADMIN`. Together these hold every tenant capability, as `ADMIN` used to. The evidence label in `Run-OperationalContinuityTest7.ps1` is updated to match.
- What ran: on the throwaway database, the 0016 script ran `Invoke-TestApi`, `Send-TestDocument`, `Set-TestReviewPolicy` and `Restore-TestReviewPolicy` with their default roles. All succeeded. Report 0015 found that `ADMIN` is refused with 403; I did not rerun that.
- What did not run: the `Run-OperationalContinuityTest1..7` scripts. They are hard-wired to `securedoc-postgres`, port 8080, Ollama and the dev server, and they force node loss.
- `CLAUDE.md` "Known state": the backend count is now 502, with the per-module counts.

**A6. Tests**, listed under "Tests" below.

## Commands run and results

| Command | Result |
|---|---|
| `git merge --no-ff feature/no-redacted-values` (on the R1B branch) | `f1d2a74` |
| `cd backend; mvn -o clean install` (merged branch) | BUILD SUCCESS, 459 tests, 0 failures |
| `npm run test:standalone` (merged branch, while two Maven builds were running) | `test:tablet` 53 of 55, the rest all pass (see below) |
| `cd backend; mvn -o clean install` (final) | BUILD SUCCESS, 502 tests, 0 failures |
| `node --experimental-strip-types --test src/studio/admin/auditRules.test.ts` | 11 of 11 pass |
| `test-tools\audit-detail\Invoke-AuditDetailCheck.ps1 -BaseJarPath <jar built from f1d2a74 in a throwaway worktree>` | PASS, 15 of 15 checks, exit 0 |
| `test-tools\no-redacted-values\Invoke-NoRedactedValueCheck.ps1` (final jar) | PASS, 0 matches in all 6 targets, exit 0 |
| `npm run test:standalone` (final, quiet machine) | all pass: auth 10, vocabulary 91, ui-framework 17, oidc-signin 69, tablet 55 (WebKit). 242 total, 0 failures |

`Invoke-AuditDetailCheck.ps1` (new):
- It starts the base jar (migrations up to 039) on `lexavault-0016-pg` and writes 4 old rows, including one old-format policy change.
- It then starts the new jar. Results:
  - 040 is applied (1 changeSet), and the columns are `varchar(200)` and `varchar(100)`, nullable.
  - All 4 old rows are unchanged (md5 of every original column) and read back with both new fields empty, in the database and in the JSON export.
  - The new `PACKAGE_CREATED` row has `demo-user` and its case ID, in the database, JSON and CSV.
  - The new policy changes show `STRICT` after `BALANCED`, then `BALANCED` after `STRICT`.
  - No system record has a display name.
  - The CSV header is the old columns followed by `actorDisplayName,caseId`.
- Only `lexavault-0016-pg` was removed.

Script problems fixed along the way (in the script, not the product):
- The new jar saw the force-stopped base node as still active. The script now waits until the base node is outside the 30-second active-node window.
- `psql` output carries CR characters, and PowerShell 5.1 strict mode refuses `.Count` on a single result. Both are fixed.
- That run also exposed the missing `IntakeBatch` case ID, which is fixed in the product (see A1).

## Tests: counts per module, and any failures with their output

| Module | Before (merged) | Now | Added |
|---|---|---|---|
| Domain | 31 | 51 | `PolicyPresetTest` 20 (16 preset cases including 0.79, 0.80, 0.81, 0.70, 0.69, 0.71, 0.801, 0.799, plus 4) |
| Application | 142 | 158 | `AuditServiceActorNameCaseIdTest` 11, `SensitiveDataPolicyAuditDetailTest` 5 |
| Scan bridge | 47 | 47 | |
| Infrastructure | 8 | 8 | |
| API | 231 | 238 | `AuditActorNameCaseIdApiTest` 7 |
| **Backend total** | 459 | **502** | +43 |

What these cover:
- the display name for a signed-in OIDC person, from the claim and from a configured claim name;
- the username when there is no name claim, in OIDC and LOCAL;
- empty for the system actor, a machine client, another actor, another thread, and after the request filter;
- the case ID on a case action, on the creation record, and from `package=` in the detail; empty when it cannot be found, and lookalikes refused;
- old rows reading back with empty values, in the API map, JSON and CSV;
- the policy detail: old keys unchanged, new keys appended, `previous=none` and `previous=[…]`, and each preset including the edges.

No existing test was changed. `NoRedactedValueSavedTest` still passes (2 of 2, part of API 238).

Frontend: `auditRules.test.ts` has 1 new test (11 now, was 10), using the new detail. `changeLine` still reads "auto-redact level now 70%" and "…85%". `test:vocabulary` is now 91.

Tablet failures on the merged branch, first run:
- `[ipad-1024x768] Case panel → Approve delivery by tap` and `[ipad-1180x820] Reports (Report viewer): the five headline tiles…` both failed.
- Each timed out after 60 s at the mocked sign-in (`getByRole('button', { name: /sign in/i }).tap()`, and `main h1` after the sign-in redirect).
- The same tests passed on the other iPad sizes. The run overlapped with the full backend build and the base-jar build.
- The final run on a quiet machine: 55 of 55 passed in 5.2 min (the first run took 10.4 min). I read the two timeouts as machine load, not a regression; no frontend file had changed at that point.

## Files changed

- Domain:
  - `audit/AuditAction.java` (`COMPLIANCE_REPORT_CREATED`)
  - `audit/AuditRecord.java` (2 nullable fields; the old `create` kept)
  - `business/PolicyPreset.java` (new)
- Application:
  - `audit/RequestActorDisplayName.java` (new), `audit/AuditCaseIds.java` (new)
  - `service/AuditService.java` (new constructor; the old one kept)
  - `audit/query/AuditRecordSummary.java` (2 fields; the old constructor kept)
  - `business/admin/BusinessAdminConfigurationService.java` (policy detail)
- Infrastructure:
  - `AuditRecordEntity.java`, `AuditRecordMapper.java`, `JpaAuditQueryPortAdapter.java`
  - `040-audit-actor-name-case-id.xml` (new), `db.changelog-master.xml`
- API:
  - `RequestContextResolver.java` (binds the name; the old constructor kept for tests)
  - `observability/RequestActorDisplayNameFilter.java` (new)
  - `ApplicationServiceConfig.java`, `AuditController.java` (2 response fields, 2 CSV columns)
- Tests (new): `PolicyPresetTest`, `AuditServiceActorNameCaseIdTest`, `SensitiveDataPolicyAuditDetailTest`, `AuditActorNameCaseIdApiTest`. Frontend: `auditRules.test.ts` (1 test added).
- Test tools:
  - `test-tools/audit-detail/` (new: `Invoke-AuditDetailCheck.ps1`, `README.md`)
  - `test-tools/lexavault-acceptance/helpers/Invoke-TestApi.ps1`, `Send-TestDocument.ps1`, `Run-OperationalContinuityTest7.ps1`
- Docs: `CLAUDE.md` (backend count), `docs/work/0016-task.md`, `docs/work/0016-report.md`

No dependency added. No UI change. No frozen area touched.

## Commits (hash and message), or "not committed"

- `ccac015` on `feature/no-redacted-values`: `docs(work): 0015 report: owner's answers recorded`
- `f1d2a74` on `feature/lexavault-r1b-tenant-identity`: `Merge branch 'feature/no-redacted-values' into feature/lexavault-r1b-tenant-identity`
- One commit on `feature/audit-detail`: `feat(audit): 0016 audit detail ...`. The hash is in the session message.

Not pushed. `feature/audit-detail` is not merged.

## Not done, and why

- Case IDs on `Document`, `Finding` and `Artifact` records: these need a query or a call-site change, and both were outside the rules of this task (question 1).
- The `Run-OperationalContinuityTest*.ps1` scripts were not run: they need the real `securedoc-postgres` and a node-loss setup.
- `CLAUDE.md` still says `test:vocabulary` 90. It is now 91. The task asked only for the backend count.

## Questions for the owner

1. **Case IDs for things inside a case.** Most `Document`, `Finding` and `Artifact` records still have no case ID, because their call sites pass only their own id. Many of those call sites already load the document, which holds the package id. I have not counted how many. Should a later task pass it explicitly through an `AuditService` overload? That would change those call sites, which this task asked me not to do.
2. **Machine clients get no display name.** Push-API and scanner clients are recorded with an empty display name, like the system. Should they record their client name instead?
3. **The CSV export does not neutralise spreadsheet formulas.** A display name comes from the identity provider and could start with `=`, `+`, `-` or `@`. The CSV quotes every value, but that does not stop a spreadsheet from treating such a cell as a formula. The same was already true of `detail`. Do you want a small fix that prefixes such cells with `'` in the CSV only?
4. **A save with no change still writes a change record.** Saving the policy with no change still writes `SENSITIVE_DATA_POLICY_CHANGED`. It is visible in the run: `REVIEW_ALL` → `REVIEW_ALL`, with `previous` equal to the new values. This was already the case before this task. Should the compliance report skip such records, or should the save skip the audit when nothing changed?

## Suggested next step

Owner review of the diff, then merge `feature/audit-detail`. Then build plan step 3 (compliance report backend), which reuses `PolicyPreset`, the `previous=` history and `COMPLIANCE_REPORT_CREATED`.
