# Report 0047: Follow-ups to the new pattern kinds and searchable pages

Date: 2026-11-23
Branch: `feature/0047-redaction-followups` (worked in the scratch worktree `C:\dev\lexavault\lv-queue-b`, detached at `7bc9280`, because your app runs from the main checkout; the branch was then fast-forwarded to the commit)
Outcome: **DONE**

## What was done
1. **"Searchable redacted pages" moved (migration 048, `048-searchable-redacted-pages.xml`, in the master changelog).** New column `tenant_redaction_policies.searchable_redacted_pages`, boolean, not null, default **true**. Every stored `IMAGE_ONLY_REDACTED_PAGES` row is copied (enabled = true becomes false), then those rows are deleted, so `tenant_redaction_kinds` holds redaction kinds only.
   - Fail closed: if a switch row has no policy row to copy it to, the migration halts (precondition), so a stored "off" can't be lost. The app always writes both in one transaction, so none is expected.
   - Rollback: writes the 0044 rows back for every entity that has it off, then drops the column.
   - The policy adapter reads and writes the column. The kind store no longer knows the switch; a leftover row would be an unknown key and never switches anything on. API, screen and audit text unchanged.
2. **The final check re-reads only pages with findings.** New `TextExtractionPort.extractPages(content, mime, pages)`. The hybrid and native adapters take each asked page through the same steps as the full read (native text, the sparse-page image check, OCR), so the text and offsets are the ones detection used. The redaction step asks only for the pages of confirmed findings. The final check still compares **every** output page's text with the values (unchanged code).
3. **SSN or ITIN on one box: ITIN wins.** In the detection service, after merging, an SSN finding that overlaps an ITIN finding on the same page is folded into the ITIN. The ITIN keeps the union of both spans, so the box never covers less. An ITIN finding needs ITIN on and a 9NN value in the ITIN groups, so with ITIN off the SSN stays as before. The pattern detector also accepts "ITIN or SSN:" (the SSN label is nearer) and "SSN/ITIN" as an ITIN label. Before this, only "SSN or ITIN" worked. An SSN label alone still never makes an ITIN.
4. **A driver's licence needs a driver context.** The label must name the driver: "driver's license/licence" (also with ’ and OCR misreads such as "Drivor"), "DL", "D.L.", or DMV wording (driving / operator's / chauffeur's licence, "DMV number"). A bare "License number / no. / #" counts only if it sits directly before the value, has no qualifying word in front of it, and has driver wording earlier in the label window ("Driver information / License number:"). Not flagged: "business license number", "contractor license", "marriage license". Pattern detector version 1.0.0 → 1.1.0.

## Commands run and results
All in `C:\dev\lexavault\lv-queue-b`. Throwaway PostgreSQL `lexavault-0047-pg` (postgres:16-alpine, 127.0.0.1:55447) for the PostgreSQL tests, removed at the end.

| Command | Result |
|---|---|
| `mvn -B -o clean install` in `backend/`, PostgreSQL tests on | BUILD SUCCESS, 0 failures (see note) |
| `npm run test:standalone` | all pass: `test:auth` 10, `test:vocabulary` 192, `test:ui-framework` 17, `test:oidc-signin` 166, `test:tablet` 95, `test:a11y` 156 |
| `test-tools/no-redacted-values/Invoke-NoRedactedValueCheck.ps1 -JarPath <this branch's jar>` | PASS, 0 matches |
| `test-tools/compliance-report/Invoke-ComplianceReportCheck.ps1 -JarPath <this branch's jar>` | PASS, 116 checks, 0 failed |
| `scripts/Test-LexaVault-ExportE2E.ps1 -JarPath <this branch's jar> -ProofDir docs/work/0047-proof` | PASS, 8 passed |
| `LEXAVAULT_TIMING_PDF=<public-26.pdf> mvn -o test -pl securedoc-api -Dtest=SearchableRedactionTimingTest` | numbers below |
| `tools/vision-benchmark`: `mvn -o clean package`, then `run --set public --text-model qwen3.5:9b --text-kinds all --skip-vision` | done, `results\public-20261123-170243` |
| `bash scripts/ci/check-product-names.sh` | clean |

Note on the runs: my first build and frontend run were started from PowerShell background jobs. The PowerShell host ended early and left them hanging with no output, partway through. I stopped those processes (my own: Maven, its test JVM, the Playwright and Vite processes) and ran everything again from Git Bash. The numbers above are from the complete reruns. Your app and the protected containers were not touched.

## Tests: counts per module, and any failures with their output
Backend, PostgreSQL tests on, 0 failures:

| Module | Tests | Change |
|---|---|---|
| domain | 79 | |
| application | 301 | |
| scan bridge | 136 | |
| infrastructure | 107 | +3 net in `SearchableRedactedPagesSettingPostgresTest` (rewritten, see below; now 4 tests), +3 `OptInPatternDetectorTest$Task0047` |
| API | 608 (1 skipped, the timing test, as designed) | +2 `SearchableRedactedPagesPolicyPostgresTest`, +2 `HybridPdfExtractPagesTest`, +1 `SearchableRedactionServiceTest`, +3 `OptInPatternKindsDetectionTest` |

What the new tests prove (fake values only):
- **048:** upgrading from 047 copies "off", "on" and "no row" correctly, removes the switch rows, keeps the kind rows and the other policy columns. A switch row with no policy halts the migration and changes nothing. Rollback restores the rows, and upgrading again keeps every value. A new policy row is searchable. Through real JPA, with Hibernate validating the schema: the adapter saves and reads the column and writes no switch row.
- **Item 2:** only the asked pages are read and OCR'd, each identical (text, spans, source) to the full read. Through the service, only page 1 (the findings page) is read again. The SSN printed again on page 2 (no finding there) still stops the document with LV-RED-005, nothing is stored, and no value is in any audit or log line.
- **Item 3:** both label orders; both kinds on gives one ITIN finding with the value's box. SSN alone gives one SSN. ITIN alone gives one ITIN. A real SSN next to an ITIN stays an SSN.
- **Item 4:** the three other licences and a bare "License number" are not flagged, nor is "Driver: … Business license number". "Driver's license no.", "DL #", "D.L.", "Operator's license number", "DMV number" and "Driver information / License number:" are flagged.

**Existing tests changed (CLAUDE.md asks me to say so):**
- `SearchableRedactedPagesSettingPostgresTest`: rewritten. It tested the 0044 row storage, which this task removes. It now tests migration 048 (same file name, so nothing was deleted).
- Liquibase rollback counts, +1 each because 048 is one more change set on top: `TenantRedactionKindsMigrationPostgresTest` (1 → 2, twice), `DocumentStoreNamesMigrationPostgresTest` (2 → 3, twice), `JdbcClaimLockAndQueueOrderPostgresTest` (3 → 4). What they check is unchanged.
- `OptInPatternKindsDetectionTest`: the fake page text can now be set per test (it was a constant), for the new cases.
- `SearchableRedactionServiceTest`: helpers take a second page and a recording extraction.
- `SearchableRedactionTimingTest`: also times the page-limited read.
- No existing assertion was changed. The 0043 case "Drivor licence-number" still passes unchanged.

## Timing, item 2 (benchmark `public-26`, 60 pages; 29 boxes on 7 pages)

| Second read of the original | Pages read | Pages OCR'd | Seconds |
|---|---|---|---|
| Before (every page) | 60 | 13 | 12.87 |
| After (pages with findings) | 7 | 5 | **3.04** |

The rest is unchanged from 0044: redact 1.98 s off / 8.59 s on, final check 0.41 / 0.43 s.

## Benchmark, text path (part A, 26 documents, 108 pages, qwen3.5:9b, threshold 0.80)
The new kinds, compared with the 0043 run (`public-20261123-123908`):

| Type | Expected | 0043: found / false flags | Now: found / false flags |
|---|---|---|---|
| Driver's licence or passport (`GOVERNMENT_ID`) | 22 | 13 / 1 | **13 / 1** |
| Routing number | 26 | 14 / 0 | 14 / 0 |
| Bank account | 26 | 15 / 1 | 15 / 1 |
| ITIN / EIN (the samples hold none) | 0 | 0 false flags | 0 false flags |

Item 4 loses no real driver's licence number in the samples. The V1 four: 71 / 97, 0 false flags (0043: 72 before and 71 after; the one-item swing comes from the AI text model). `public-23` stops the text path with an IllegalStateException in this run and in both 0043 runs: this was already the case before this task, and it is not looked into here.

## Files changed
- Application: `TextExtractionPort.java` (`extractPages`), `DocumentRedactionService.java`, `SensitiveDataDetectionService.java` (ITIN wins).
- Infrastructure: new `048-searchable-redacted-pages.xml`; `db.changelog-master.xml`, `TenantRedactionPolicyEntity.java`, `JpaTenantRedactionPolicyRepositoryAdapter.java`, `JdbcTenantRedactionKindStore.java` (switch removed), `PdfBoxTextExtractionAdapter.java`, `HybridPdfTextExtractionAdapter.java`, `OptInPatternDetector.java`; tests as listed above.
- API tests: new `SearchableRedactedPagesPolicyPostgresTest.java`, `HybridPdfExtractPagesTest.java`; changed `SearchableRedactionServiceTest.java`, `OptInPatternKindsDetectionTest.java`, `SearchableRedactionTimingTest.java`.
- Docs: `docs/work/0047-proof/` (the export suite's pictures), this report.
- No frontend, API or dependency change.

## Commits (hash and message), or "not committed"
- `feat(redaction): task 0047, redaction follow-ups; migration 048` (on `feature/0047-redaction-followups`; not pushed, not merged)

## Not done, and why
- Nothing in the task was left out.

## Questions for the owner
1. Item 4 also accepts a bare "License number" when driver wording appears earlier in the label window and nothing qualifies the licence ("Driver information / License number: …"). This keeps form layouts where the heading says "Driver". Keep it, or require the driver wording in the label itself?

## Suggested next step
Review and merge into `feature/lexavault-r1b-tenant-identity`. Migration 048 runs on the next app start (your local database included): it moves any stored switch value and changes nothing you can see on screen.

No other product's name or behaviour in code.

## Owner's answers (2026-11-23, recorded by the design chat; owner said "as recommended")
1. **"Driver information / License number:"** (driver wording in the heading, bare "License number" label, nothing qualifying the licence): **keep it.** Real forms often carry "Driver" in the section heading; a qualifying word ("business", "contractor", "marriage") still blocks it.
- **Backlog:** `public-23` stops the benchmark's text path with an `IllegalStateException` (seen since 0033; the product's extraction stops on a poor scan and the harness's in-memory stand-ins throw on the failure path). To be understood in a later task, not hidden.
- Merged into `feature/lexavault-r1b-tenant-identity`.
