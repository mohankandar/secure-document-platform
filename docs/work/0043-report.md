# Report 0043: R1 part 1, new pattern types (detection only)

Date: 2026-11-23
Branch: `feature/0043-r1-patterns` (merged `feature/lexavault-r1b-tenant-identity` in first, for the 0043 answers)
Outcome: **DONE**

The first attempt stopped for a decision (migration needed). The owner's answers (`0043-answers.md`) approved migration 047, six switches and new specific kinds. This report replaces the STOPPED one.

## What was done
- **Six new redaction kinds, off by default for every entity**, with stable keys: `BANK_ROUTING_NUMBER`, `BANK_ACCOUNT_NUMBER`, `ITIN`, `EIN`, `DRIVERS_LICENSE_NUMBER`, `PASSPORT_NUMBER` (`EntityType`). `FINANCIAL_ACCOUNT` and `GOVERNMENT_ID` stay as they were, unused.
- **Policy:** `TenantRedactionPolicy` now accepts the V1 four plus the six (`configurableTypes()`, `optInTypes()`). `defaults()` is still exactly the V1 four. The Business Admin save accepts the new kinds; the audit detail lists them as it lists the others.
- **Migration 047** (`047-tenant-redaction-kinds.xml`, in the master changelog): table `tenant_redaction_kinds` (tenant_id, kind_key, enabled default false, updated_at; primary key (tenant_id, kind_key); foreign key to tenants). Rollback drops the table. No data is moved; the four V1 columns on `tenant_redaction_policies` are unchanged. A kind with no row is off; a row with a key the app doesn't own (a V1 key, an unknown key) never switches anything on. Written by `JdbcTenantRedactionKindStore`, in the same transaction as the policy row.
- **Detectors** (`OptInPatternDetector`, called from `LocalRuleBasedDetectionAdapter` after the V1 detectors, with its own detector name `local-pattern-kinds` 1.0.0; the V1 code and its version string are untouched):
  - Routing: 9 digits with the ABA 3-7-1 checksum, bound to a routing / ABA / RTN label, or **on the check line**: a transit-delimited number (printed MICR symbols, or `:` `|` `[` and similar as OCR reads them) on a MICR-like line or on a page with check wording, or the plain "routing account check-number" line.
  - Account: 4–17 digits bound to an account / acct / a/c label, or the field after the routing number on the check line. Excluded: phone, date, amount, ZIP+4, SSN- and EIN-shaped values, grouped card numbers, and values a nearer loan / order / invoice / other label owns.
  - ITIN: 9NN-NN-NNNN with group 70–88, 90–92 or 94–99, with an ITIN label; a nearer SSN label wins, so an SSN stays an SSN.
  - EIN: NN-NNNNNNN with a valid IRS prefix and an EIN / FEIN / tax ID label (plain 9 digits only right after the label).
  - Driver's license and passport: keyword required (driver's license / licence / DL / passport), plausible format.
  - "Bound to a label" means the label is on the same line with only filler between, or the label is on the line above and is the nearest label that could own the value (a form's header row). Common OCR misreads are corrected (O for 0, I/l for 1, S for 5, b for 6, B for 8, joined "DLNO.", "licence-number"); a corrected value loses 0.20 confidence so it goes to review.
- **Detection service:** the AI is asked only for the V1 kinds that are on (the new kinds are pattern-only); if no V1 kind is on, the AI is not called. Confidence and review routing follow the existing rule: auto-confirmed at or above the entity's threshold with a box, otherwise review; "review everything" sends them all to a person.
- **Policy screen** (`words.ts`): the six kinds are listed after the V1 four, each with a one-line description ending "Off by default." The hint under the list no longer says a driver's license is "not a sensitive type". Short names added for the time-saved tile; the same names are in `SensitiveTypeNames` (compliance PDF).
- **"A case field or a redaction item, never both":** still holds. Detection reads page text only; case fields are metadata, and nothing in this task links them. A loan / contract / member number label next to a value makes it *not* an account number.
- **Benchmark harness:** `--text-kinds v1|all` (and `-TextKinds`, `-SkipVision` in the script) switches the new kinds on in the text path. Product kinds are scored as the answer files' `ROUTING_NUMBER`, `BANK_ACCOUNT`, `GOVERNMENT_ID`; ITIN/EIN findings are counted apart (the samples hold none).
- **Regression found and fixed during the build:** the install demo step's own small Spring context did not have the new store, so the demo step failed (6 install tests). `DemoStepContext` now imports `JdbcTenantRedactionKindStore`.

## Commands run and results
All in the scratch worktree `C:\dev\lexavault\lv-queue-b` (your app runs from the main checkout and was not touched).

| Command | Result |
|---|---|
| `git merge feature/lexavault-r1b-tenant-identity` | clean (brings the 0043 answers and queue c) |
| `mvn -B -o clean install` in `backend/`, PostgreSQL tests on (throwaway `lexavault-0043-pg`, postgres:16-alpine, 127.0.0.1:55443) | first run: API 7 failures (6 install tests: the demo-step context, fixed; 1 `NoRedactedValueSavedTest` chance match, see below). After the fix, `mvn -B -o install -rf :securedoc-api`: BUILD SUCCESS, 0 failures |
| `npm run build` | pass |
| `npm run test:standalone` | see Tests |
| `test-tools/no-redacted-values/Invoke-NoRedactedValueCheck.ps1 -JarPath <this branch's jar>` | PASS, 0 matches |
| `test-tools/compliance-report/Invoke-ComplianceReportCheck.ps1 -JarPath <this branch's jar>` | PASS, 116 checks, 0 failed |
| `tools/vision-benchmark`: `mvn -o clean package` | 31 tests pass (+1 `TextKindsTest`) |
| Benchmark part A, before: `run --set public --text-model qwen3.5:9b --text-kinds v1 --skip-vision` | done, `results\public-20261123-123203` |
| Benchmark part A, after: `run --set public --text-model qwen3.5:9b --models qwen3-vl:8b-instruct --prompt v2 --text-kinds all` | done, `results\public-20261123-123908` |
| `bash scripts/ci/check-product-names.sh` | clean (run on the committed tree) |

The `NoRedactedValueSavedTest` failure in the first run: the fake SSN's last four digits (7531) appeared inside a random UUID ("…d75313e") in a compliance-report audit record. It is not a value: the test searches whole dumps, ids included. It passed on the re-run. That existing test can fail this way about once in a few thousand runs. I did not change it; my new test strips ids and timestamps before it searches.

## Tests: counts per module, and any failures with their output
Backend, PostgreSQL tests on, final run 0 failures, 0 skipped:

| Module | Tests | Change |
|---|---|---|
| domain | 79 | |
| application | 299 | +5 `OptInKindsPolicySaveTest` |
| scan bridge | 136 | |
| infrastructure | 100 | +24 `OptInPatternDetectorTest`, +3 `TenantRedactionKindsMigrationPostgresTest` |
| API | 588 | +6 `OptInPatternKindsDetectionTest` |

**Existing tests changed (CLAUDE.md asks me to say so):** only the Liquibase rollback counts in `DocumentStoreNamesMigrationPostgresTest` (1 → 2, twice) and `JdbcClaimLockAndQueueOrderPostgresTest` (2 → 3), because 047 is one more change set on top. What they check is unchanged. No detection test was changed.

Frontend, `npm run test:standalone`, all pass: `test:auth` 10, `test:vocabulary` 192 (+1, the opt-in kinds' names and descriptions), `test:ui-framework` 17, `test:oidc-signin` 166, `test:tablet` 95, `test:a11y` 156.

## Benchmark: part A, text path, before / after (numbers only)
Same samples (26 documents, 108 pages), text model qwen3.5:9b, product default threshold 0.80.

Text path, the types the new kinds cover:

| Type | Expected | Before: found / false flags | After: found / false flags | After: misses |
|---|---|---|---|---|
| Routing number | 26 | 0 / 0 | 14 / 0 | 12 |
| Bank account | 26 | 0 / 0 | 15 / 1 | 11 |
| Driver's license or passport (`GOVERNMENT_ID`) | 22 | 0 / 0 | 13 / 1 | 9 |
| ITIN / EIN (the samples hold none) | 0 | 0 | 0 false flags | |
| By page kind: typed | 33 | 0 / 0 | 25 / 0 | 8 |
| fax | 11 | 0 / 0 | 6 / 0 | 5 |
| handwritten | 15 | 0 / 0 | 7 / 2 | 8 |
| ID card | 5 | 0 / 0 | 1 / 0 | 4 |
| **check (routing + account on the check line)** | 10 | 0 / 0 | **3 / 0** | 7 |

The check-line misses come from OCR: on these synthetic checks Tesseract reads most of the MICR line as letter noise (digits with `e`, `b`, `S` mixed in), so the digits are not there to match. That is what the vision route is for.

The V1 four, text path: before 71 / 97 found, after 72 / 97, 0 false flags both times (the 0040 run had 72). The one-item difference is a credit score the text model found in one run and not the other. In the default case this task changes neither the V1 detectors nor the AI request.

With vision (qwen3-vl:8b-instruct, prompt v2, pages routed at 0.75), after: text path plus vision plus the signature detector finds routing 23 / 26 (4 false flags), account 23 / 26 (3), driver's license or passport 22 / 22 (1). Vision alone on the routed pages: routing 13 / 18, account 15 / 18, ID 15 / 15. Remaining misses: routing and account on two checks and one fax.

## Files changed
- Domain: `EntityType.java`, `TenantRedactionPolicy.java`.
- Application: `SensitiveDataDetectionService.java`, `BusinessAdminConfigurationService.java`, `SensitiveTypeNames.java`; test `OptInKindsPolicySaveTest.java`.
- Infrastructure: new `OptInPatternDetector.java`, `JdbcTenantRedactionKindStore.java`, `047-tenant-redaction-kinds.xml`; changed `LocalRuleBasedDetectionAdapter.java` (one call), `JpaTenantRedactionPolicyRepositoryAdapter.java`, `db.changelog-master.xml`; tests `OptInPatternDetectorTest.java`, `TenantRedactionKindsMigrationPostgresTest.java`, and the rollback counts in two tests.
- API: `DemoStepContext.java`; test `OptInPatternKindsDetectionTest.java`.
- Frontend: `words.ts`, `words.test.ts`, `PolicyArea.tsx` (hint text), `timeSavedRules.ts`.
- Benchmark harness: `TextPathRunner.java`, `BenchmarkMain.java`, `Results.java`, `Summary.java`, `Truth.java`, `Run-VisionBenchmark.ps1`, `README.md`; test `TextKindsTest.java`.
- `docs/work/0043-report.md` (this file).

## Commits (hash and message), or "not committed"
- `feat(detection): task 0043, six opt-in pattern kinds; migration 047` (hash in the queue summary)

## Not done, and why
- Nothing in the task was left out.
- Left out on purpose: the AI prompt is not asked for the new kinds (pattern detection only, as the task title says). The OCR guard for blank sensitive fields (`OcrSensitiveFieldSafetyPolicy`) still covers only the V1 four.

## Questions for the owner
1. When an entity has both SSN and ITIN on and a value is labelled "SSN or ITIN", it is found as both (two findings on the same box). Fine, or should one win?
2. "License number" on its own (for example "Business license number") counts as a driver's license label. Keep it (finds more), or require "driver" / "DL"?

## For the owner
- Look at Admin › Policy › Sensitive data: six new kinds, all off, each with a one-line description. Switching one on and saving works as it does for the four.
- Throwaway containers `lexavault-0015-pg` and `lexavault-0017-pg` (the check scripts' own) removed; `lexavault-0043-pg` is kept for task 0044's PostgreSQL tests and removed at the end of the queue. Protected containers not touched.

## Suggested next step
Task 0044 (searchable redacted pages), on `feature/0044-r2-searchable` from this branch.

No other product's name or behaviour in code.
