# Report 0044: R2 part 1, searchable redacted pages (option B)

Date: 2026-11-23
Branch: `feature/0044-r2-searchable` (from `feature/0043-r1-patterns`, commit `6d574f5`)
Outcome: **DONE** (one storage choice for the owner to confirm, question 1)

## What was done
1. **Invisible text layer on redacted pages** (`PdfBoxBurnFlatRedactionAdapter`). After a page is burned flat, the redacted 150 DPI image is enlarged 2× (the same pixels interpolated, no new detail) and OCR'd with the product's Tesseract. Each word is written as invisible text (rendering mode 3, "neither") at its place on the image, sized to its OCR box. This is the standard "image plus hidden text" page, so it can be searched and copied. Only pages with redaction targets get the layer. Pages rendered only because of annotations, and untouched pages, are unchanged. The engine refuses (fail closed) when asked for the layer without OCR.
2. **Final check extended** (`PdfBoxRedactionVerificationAdapter`, `RedactionVerificationPort`). Before a document is marked ready, the text of every output page is compared with the values that were redacted: the original text on untouched pages, the new layer on redacted pages. If any value appears, the document stops, nothing is stored or exported, and the code is **`LV-RED-005` "A redacted value is in the output text"** (detail `REDACTION_VALUE_IN_OUTPUT_TEXT`). The Support explanation names no value.
   - The values: findings keep only offsets (task 0015), so the redaction step reads the original again with the product's own text extraction (`TextExtractionPort`, the same one detection uses) and takes each confirmed finding's span. They are held in memory in `RedactedValues`, which has no getter and whose `toString()` is the count only. They are dropped after the check; nothing is saved or logged.
   - How values compare: letters and digits only, lower case. A value matches a run of consecutive words that joins to it ("412 55 7531" = "412-55-7531"), or, for values of 8 or more characters, one word that contains it. Values shorter than 4 characters (a 3-digit credit score) are not compared, because they can't be told apart from other numbers on a page; the existing box-coverage check still proves they are covered.
   - The check runs in **both** modes. With the layer off, the old rule "no text at all on a redacted page" also still applies.
3. **The setting, per entity, on by default:** "Searchable redacted pages" on Admin › Policy › Sensitive data (`SEARCHABLE_PAGES_WORDS` in `words.ts`). It travels with the sensitive-data policy (`TenantRedactionPolicy.searchableRedactedPages`, API field `searchableRedactedPages`; a request without it keeps the stored value). The audit detail gets `searchableRedactedPages=false` only when it is off, so every detail written before is unchanged.
   - **Stored with no migration:** as one row, `IMAGE_ONLY_REDACTED_PAGES`, in `tenant_redaction_kinds` (migration 047). As for every row there, no row means off, so searchable is on by default. The row is never read as a redaction kind. See question 1.
4. Untouched pages are unchanged (imported as before).
5. **DPI stays 150.** OCR quality is helped by enlarging the image before OCR instead; the page image and the file size are unaffected (+0.1%, below).

`LV-RED-005` was added to the registry (`LvErrorCode`, `LvErrorCodes`), the Support taxonomy, `docs/work/lv-error-codes.md`, the customer guide `docs/api/error-codes.md`, and the generated frontend file (regenerated with the backend test, as its header says).

## Commands run and results
All in the scratch worktree `C:\dev\lexavault\lv-queue-b`; your running app and checkout were not touched.

| Command | Result |
|---|---|
| `mvn -B -o clean install` in `backend/`, PostgreSQL tests on (throwaway `lexavault-0043-pg`, 127.0.0.1:55443) | first run: 4 failures in the error-code registry tests (the new code was not yet in the customer guide or the area counts; fixed, see below); second run: BUILD SUCCESS, 0 failures |
| `npm run test:standalone` | all pass: `test:auth` 10, `test:vocabulary` 192, `test:ui-framework` 17 (type check included), `test:oidc-signin` 166, `test:tablet` 95, `test:a11y` 156 |
| `test-tools/no-redacted-values/Invoke-NoRedactedValueCheck.ps1 -JarPath <this branch's jar>` | PASS, 0 matches. This ran the real app with the switch at its default (on). The stored redacted copy from that run: 17 searchable words, 0 matches for any fake value |
| `test-tools/compliance-report/Invoke-ComplianceReportCheck.ps1 -JarPath <this branch's jar>` | PASS, 116 checks, 0 failed |
| `scripts/Test-LexaVault-ExportE2E.ps1 -JarPath <this branch's jar> -ProofDir docs/work/0044-proof` | PASS, 8 passed (2.5 min) |
| `LEXAVAULT_TIMING_PDF=<benchmark public-26.pdf> mvn -o test -pl securedoc-api -Dtest=SearchableRedactionTimingTest` | numbers below |
| `bash scripts/ci/check-product-names.sh` | clean |

## Tests: counts per module, and any failures with their output
Backend, PostgreSQL tests on, final run 0 failures:

| Module | Tests | Change |
|---|---|---|
| domain | 79 | |
| application | 301 | +2 `SearchableRedactedPagesSettingTest` |
| scan bridge | 136 | |
| infrastructure | 101 | +1 `SearchableRedactedPagesSettingPostgresTest` |
| API | 600 (1 skipped) | +8 `SearchableRedactedPagesTest`, +3 `SearchableRedactionServiceTest`, +1 `SearchableRedactionTimingTest` (skipped unless asked for, as designed) |

What the new tests prove (fake values only):
- `SearchableRedactedPagesTest` (real Tesseract): the redacted SSN, card number and date of birth are not in the output text, the copied text, the decompressed page content or the raw PDF bytes (full value, digits only, last 4, with and without spaces). "Loan", "application", "borrower", "income" and "reviewed" are searchable on the redacted page, and the untouched page keeps its text. The layer is rendering mode 3 over the image. Off equals today's output (see below). A planted leak in the layer, written with dashes or with spaces, fails closed with `REDACTION_VALUE_IN_OUTPUT_TEXT`. A value printed on an untouched page fails in both modes. Image-only mode still refuses any text on a redacted page.
- `SearchableRedactionServiceTest` (real engine, final check and extraction): a clean run stores the searchable copy and audits only counts (`redactedPagesText=OCR_LAYER, valuesChecked=3`). With the switch off, the copy is image-only and the values are still compared. A planted leak stops the document with `LV-RED-005`, stores nothing (so nothing can be exported), and no audit record or log line holds any part of a value.

**Off vs today's output:** two runs of today's engine already differ in exactly one place, the trailer's `/ID` (PDFBox makes a fresh random document id on every save). With that id masked, off is byte for byte today's output, through both the new and the old call (test `offIsTodaysOutputByteForByte`).

**Existing tests changed (CLAUDE.md asks me to say so):** only counts, because one code was added: `LvErrorCodeRegistryTest` (RED 4 → 5, total 139 → 140) and `lvCodes.test.ts` (139 → 140, plus one line checking the new mapping). Nothing else was changed.

## Timing (benchmark part A, `public-26`, 60 pages; the answer file's 29 boxes on 7 pages as targets)

| | Redact | Final check | File size |
|---|---|---|---|
| Searchable off | 1.99 s | 0.42 s | 13,070,987 bytes |
| Searchable on | 8.60 s | 0.44 s | 13,087,491 bytes (+0.1%) |

- **Added per redacted page: 0.95 s** (redact plus final check, on minus off, divided by 7).
- **Also new, in both modes:** to get the values for the final check, the redaction step reads the original's text again. For this 60-page package that took **13.0 s**, because it OCRs the scanned pages again. It happens once per document with confirmed findings. If that is too slow, the next step is to extract only the pages with findings (question 2).

## Files changed
- Application: `PdfRedactionPort.java` (options), `RedactionVerificationPort.java` (`RedactedValues`, the extended check), `DocumentRedactionService.java`, `BusinessAdminConfigurationService.java`, `TechnicalFailureCode.java`, `LvErrorCodes.java`, `TechnicalFailureTaxonomy.java`; test `SearchableRedactedPagesSettingTest.java`.
- Domain: `TenantRedactionPolicy.java`, `LvErrorCode.java`; test count in `LvErrorCodeRegistryTest.java`.
- Infrastructure: `PdfBoxBurnFlatRedactionAdapter.java`, `PdfBoxRedactionVerificationAdapter.java`, `JdbcTenantRedactionKindStore.java`, `JpaTenantRedactionPolicyRepositoryAdapter.java`; test `SearchableRedactedPagesSettingPostgresTest.java`.
- API: `ApplicationServiceConfig.java`, `BusinessAdminController.java`, `UpdateSensitiveDataPolicyRequest.java`, `BusinessAdminConfigurationResponse.java`; tests `SearchableRedactedPagesTest.java`, `SearchableRedactionServiceTest.java`, `SearchableRedactionTimingTest.java`.
- Frontend: `PolicyArea.tsx` (the switch), `words.ts`, `types/index.ts`, `lvErrorCodes.generated.ts`, `lvCodes.test.ts`.
- Docs: `docs/api/error-codes.md`, `docs/work/lv-error-codes.md`, `docs/work/0044-proof/` (the export suite's pictures), this report.

## Commits (hash and message), or "not committed"
- `feat(redaction): task 0044, searchable redacted pages (option B); LV-RED-005` (hash in the queue summary)

## Not done, and why
- Nothing in the task was left out.
- Hidden-content clean-up of untouched pages is a later R2 task, as the task says.

## Questions for the owner
1. **Where the switch is stored.** The task allows no migration and there was no per-entity settings table, so the switch is a row (`IMAGE_ONLY_REDACTED_PAGES`) in `tenant_redaction_kinds` (047). It works and is tested, but that table was meant for redaction kinds. Keep it there, or move it to its own column (a small migration 048) when the R1 catalog task touches the table?
2. **The second text extraction** (13 s on the 60-page package, mostly OCR of scanned pages). Fine for now, or should a follow-up re-read only the pages that have findings?
3. **Short values** (under 4 letters or digits, for example a 3-digit credit score) are not compared with the output text, only proved covered by the box check. OK?
4. The final check now also fails a document when a redacted value is printed, unredacted, on another page (detection missed that copy). This is a real leak, so it fails closed, in both modes. Fine?

## For the owner
- Admin › Policy › Sensitive data: "Searchable redacted pages" is on. Process a case with an SSN, then open the redacted PDF: the page looks the same, and the words around the black box can be searched and copied.
- Throwaway containers removed (`lexavault-0015-pg`, `-0017-pg`, `-0035-pg` by their scripts; `lexavault-0043-pg` at the end of the queue). Protected containers not touched.

## Suggested next step
Answer questions 1–4. Next R2 parts: hidden-content clean-up of untouched pages.

No other product's name or behaviour in code.

## Owner's answers (2026-11-23, recorded by the design chat; owner said "as recommended")
1. **Where the switch is stored:** move it out of `tenant_redaction_kinds` into a proper per-entity setting (task 0047), before anything builds on it.
2. **13 s extra per 60 pages:** too much against the 60 s target. The final check re-reads **only pages with findings** (task 0047).
3. **Values under 4 characters not compared with the output text:** OK; the box-coverage check proves they are covered.
4. **A redacted value printed unredacted elsewhere stops the document:** correct (a real leak). Support names the page, never the value.
5. **"SSN or ITIN" on one box:** **ITIN wins** (an SSN never starts with 9): one finding per box (task 0047).
6. **"License number" alone:** not enough; require "driver", "DL" or "driver's license/licence" nearby (task 0047).
- Merged with 0043 into `feature/lexavault-r1b-tenant-identity`.
