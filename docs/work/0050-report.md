# Report 0050: R2 part 2, hidden content in the output and a whole-file final check

Date: 2026-11-23
Branch: `feature/0050-r2-hidden-content`, from 0049's branch, with `feature/lexavault-r1b-tenant-identity` (`fc66a51`) merged in
Outcome: **DONE** (1 leak found and handed on as task 0053; 1 question below)

## What was done
1. **Clean output, always on** (`PdfOutputCleaner`).
   - A copied page keeps only its size, rotation, content and the resources its content draws. A shared resource list can hold pictures of other, redacted, pages; those are no longer copied.
   - Annotations and form fields are drawn into the page as they look, then removed. **No annotation stays**; links have no look and are dropped. The page keeps its text (before 0050 such a page became an image without text).
   - Visible layers become ordinary content. A page that can show a hidden layer, or whose layer rules are not certain, is drawn as an image (with the OCR text layer when searchable is on), so hidden content cannot survive.
   - Removed at every level: metadata, XMP, attachments, actions, scripts, private application data, tagged content's alternative and actual texts, thumbnails, bookmarks, names. The file is new (one revision), with producer `LexaVault` and the dates only.
2. **Hidden text** stays and is read: an SSN in invisible or white text is found and redacted (test).
3. **Whole-file final check** (`PdfWholeFileCheck`): every object of the file, also one no page uses: names, strings, drawing text (also when split by spacing), and every other stream except pictures, font programs, character maps, functions and colour profiles. A redacted value anywhere gives **`LV-RED-005`**. Active or hidden content left in the output gives **`LV-RED-002`** with the new reason `REDACTION_HIDDEN_CONTENT_REMAINS`. Compared in memory; nothing kept.
4. **Signed PDFs:** audit `DOCUMENT_SIGNATURE_REMOVED` on the case (document id only, no signer), and a plain line on the case panel, read from that audit. No migration.

No migration, no new dependency, no setting.

## Commands run and results
| Command | Result |
|---|---|
| `mvn -B -o clean install`, PostgreSQL tests on | BUILD SUCCESS, 0 failures |
| `npm run test:standalone` | 10, 193, 17, 168, 95, 156 passed, 0 failed |
| `Invoke-NoRedactedValueCheck.ps1`, inline / staged | PASS, 0 matches / same |
| `Invoke-ComplianceReportCheck.ps1`, inline / staged | PASS, 116 checks / same |
| `scripts/Test-LexaVault-ExportE2E.ps1`, inline / staged | PASS / PASS |
| Product-name guard | clean (1676 files) |
| 26 public benchmark PDFs, no redactions, old output against new | all verified safe, 0 pages look different, same size |

**60-page package (`public-26`), before and after:** output 7752 KB and 7752 KB; building it 61 ms and 80 ms; the final check 123 ms. These are single runs without redactions, so without page images or OCR. "Look different" compares each page at 50 DPI with the input.

## Tests: counts per module, and any failures with their output
Domain 79, application 304, scan bridge 136, infrastructure 115, API 639 (1 skipped, the timing test). New: `HiddenContentInOutputTest` (7: sixteen kinds of hidden content, each first shown to be in the input; values planted in seven places of an output; a form field; invisible text; a signed input), one service test for the audit, one case panel test.

**Failures on the way, and what I changed:**
- `AppendixDetailAllowListTest` (a reviewed list): I had added the new audit action to it. I took that out again; the list is unchanged.
- `StageJobsMigrationPostgresTest` failed once, and once again in five repeats, for a reason that has nothing to do with 0050. Liquibase undoes the last change sets **by time**, and the Docker clock stepped back during the migration, so it undid 026-04 instead of 050. The rollback tests now set the times to follow the recorded execution order first (`PostgresTestDatabase.rollback`). 3 repeats of those tests passed, 27 of 27. This flake was in 0049 and before.
- `supportRules.test.ts` and `e2e-oidc/support.spec.ts`: LV-RED-002 has 9 reason codes instead of 8 (the new one). Two full frontend runs failed on these lists before the one in the table passed. In that run, task 0053's generated code list (one more code) was already in the working folder.

**Existing test changed as the task asks:** `PdfBoxSelectivePermanentRedactionTest`, a page with an annotation now keeps its text (it was required to be an image).

## Files changed
New: `PdfOutputCleaner`, `PdfWholeFileCheck`, `HiddenContentInOutputTest`. Changed: the redaction adapter and its check, `PdfRedactionPort`, `DocumentRedactionService`, `PackageController` and its response, `AuditAction`, `TechnicalFailureCode`, the case panel and its words, the rollback test helper.

## Commits (hash and message), or "not committed"
- `7e2deb9` feat(redaction): nothing hidden in the redacted output and a whole-file final check
- `727da8a` test: rollback tests follow the execution order, and the two list changes above
- `2742fc2` merge of the integration branch; then the report commit

## Not done, and why
- **Leak found:** text in a visible form field is not read by detection, so a value only there stays visible. Fixing it changes detection. Task 0053.

## Questions for the owner
1. **Other annotations:** text in free-text notes and stamps is drawn into the page too, and detection does not read it either (before 0050 it was visible as part of an image). 0053 names form fields only. Extend it to them?

## Suggested next step
Task 0053.

No other product's name or behaviour in code.
