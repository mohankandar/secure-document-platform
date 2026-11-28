# Report 0119: a page a person reviewed does not come back; one review stop per case; the right message for unreadable pages

Date: 2026-11-28
Branch: `feature/0119-reviewed-page-stays`, from `origin/feature/lexavault-r1b-tenant-identity` at `bf6fe07` (0118 merged)
Outcome: **DONE** (PR to follow on green CI)

## Cause of the owner's page 5 (item 1)
The final check did ask the vision model about page 5, and the call worked. On the finished page (both values already covered) the model answered in plain words, "There are none.", instead of JSON. The adapter could not read that (LV-AI-005, ANSWER_NOT_READABLE), so the page counted as UNREAD. The final check asked once more and got the same answer (temperature 0), so it held the page. Reproduced with the delivered `long-20-pages.pdf` from round `20261127-225649`: three answers, all "There are none.". With the answer's JSON shape enforced, the model answers `{"items": []}`.

On the unredacted page, the shape alone made the model miss the date of birth (it found the SSN only), so the shape is **not** used for every asking.

A second cause made the same page come back. The final check counted a page as checked by a person only from a dismissed page check, so the two date-of-birth findings the owner had confirmed on page 5 did not count (`checkedByAPersonBefore=0`).

## What changed
- **Vision model (`OllamaVisionModelAdapter`).** An answer that can't be read is asked once more with the answer's JSON shape enforced (Ollama `format`). A readable answer is never asked again, so detection and the benchmark are unchanged wherever the model answered in JSON. A page the model can't read is now logged with its codes only (`VISION_PAGE_NOT_READ page code reasonCode prompt`).
- **Pages a person finished in this case** count, whatever they were shown for. That covers a finding they confirmed or dismissed, a box they placed or drew, and a page they checked. They are read from the audit trail: `FINDING_CONFIRMED`, `FINDING_DISMISSED`, `FINDING_BOUNDING_BOX_UPDATED` and `REVIEW_PAGE_CHECKED` on the case's findings, never by the system actor. This adds a new method on `AuditQueryPort`; no schema change. Without it, only placed boxes and checked pages count, which means fewer pages and is the safe side.
- **Final check split (owner, 00:28):**
  - (a) The quick search of every page's stored text (and form fields) always runs and can't be switched off. It uses the new `TextExtractionPort.extractTextLayer`, which runs no OCR.
  - (b) OCR and the vision model read again only the picture pages no person finished in this case: pages that were pictures in the original, and pages with almost no stored text in the finished file. This is the new entity setting **"Final check: read picture pages again"**, on by default. The Business Admin can turn it off under Policy; the change is audited (`finalCheckReread=false` in the policy record and its history line) and saved in changelog **091**, which carries the column's comment. The data dictionary was regenerated.
  - (c) Pages a person finished are never read again. The second check also skips their OCR now.
- **A value still found on a page a person finished is our defect.** The document stops (Stopped), it is not sent back to the reviewer, and nothing is delivered. This uses the new failure code `REDACTION_VALUE_LEFT_ON_CHECKED_PAGE` (LV-RED-002, the safety-check family), with `leftOnCheckedPages=[pN]` in the final check's audit line. The kinds a person let stand on a page check of that page are kept, as a dismissed value is, so ticking "checked" on a held page still delivers as before (test 0099).
- **One review stop per case:**
  - Staged pipeline: before a case goes to Review, every document waiting for redaction gets its REDACT run in this cycle. A document with nothing for a person is redacted and checked as before (its final check may hold pages). A document with findings for a person gets the final check, and the second check where it is on, on a **trial file** in which the waiting findings are covered as proposed (`DocumentRedactionService.checkBeforeReview`). What they find joins the same review as a page to check. The case goes to Review when the last run ends.
  - Inline: the same check runs after detection.
  - After the review, the checks run on the finished file, re-reading only where no person looked.
  - A failure of the check before review sends nothing and stops nothing: the checks after the review still decide.
- **One vision reading per picture page** when the second check is on: the final check and the second check share the readings of a file.
- **The intermittent red CI since 0112 (found and fixed here; the PR's first CI run hit it).** Two documents of one case finishing at the same moment on two workers: the gate read the case and its documents (the other document still waiting), the other worker then saved its document and its job together, and the gate read that job as finished. The gate concluded "finished, nothing planned" and stopped a good case with LV-PRC-011. Loading the case also loads its documents, so a later read gave the same stale state. Now the gate reads the case's jobs **first**, before the case and its documents. New test `StagedPipelinePostgresTest.twoDocumentsOfACaseFinishingAtOnceNeverStopTheCase` saves the other worker's step exactly at the gate's read: red with the old code (the case FAILED), green with the fix. The class: 25/25.
- **Message by reason (Review).** UNREAD: "The final check could not read page N" and "The final check could not read this page (handwriting or a poor scan). Please confirm it is clean.…". FOUND keeps "Still readable in the finished file…". The reason code (UNREAD or FOUND) is under **Technical details**.
- Audit detail of the final check: adds `readAgain=N`, and `leftOnCheckedPages=[…]` when there are any. A check before review starts with "Before review:".

## Tests
- New `ReviewedPageStaysTest` (6), through the redaction service with the real PDF, redaction, verification and pattern adapters, and a vision model that answers as the owner's did:
  - the owner's page 5: confirmed by a person, one review only, the page never read again (`checkedByAPersonBefore=1`);
  - an unreadable page nobody reviewed joins the same review (UNREAD), is not added twice, and after the person's decision the case goes on without a second stop;
  - a value left on a page a person finished stops the document (code above, not sent back);
  - a value on a page nobody looked at still goes to a person;
  - with the re-read off, no picture page is read again, but the text search still finds page 3;
  - with the second check on, page 2 is read by the vision model once.
- `OllamaVisionModelAdapterTest` +1: a word answer is asked once more with the shape, and only then.
- Frontend `words.test.ts` +1: UNREAD and FOUND messages and the reason code.
- Changed tests (said here as the rules require):
  - `StagedPipelinePostgresTest.aFindingForAPersonSendsTheCaseToReview…`: it asserted "nothing is redacted before the review", which the owner's one-stop rule replaces. It now asserts the checks run first, then Review, with the other document finished.
  - Seven migration tests that roll back a fixed number of change sets from the end: each count +1 for the new changeset 091 (as 0118 did for 090).
- Runs:
  - `mvn -o -Pdev-build test` for the changed groups;
  - the PostgreSQL tests on a throwaway container (`lv0119-pg`, removed): migration tests, `StagedPipelinePostgresTest` 24/24, `DataDictionaryPostgresTest` 3/3;
  - the whole backend `mvn -o -Pdev-build verify` with PostgreSQL tests on: domain 91, application 410, scan bridge 144, infrastructure 216 (2 skipped), API 1001 (5 skipped): 0 failed, BUILD SUCCESS;
  - `scripts\Update-LexaVault-DataDictionary.ps1` (its own throwaway database);
  - `LvErrorCodeFrontendFileTest -Dlv.codes.write=true` regenerated `lvErrorCodes.generated.ts` (one line);
  - frontend: `npx tsc --noEmit`, `npm run test:standalone`: vocabulary 252/252, ui-framework 19/19, OIDC sign-in 237 passed, tablet 110 passed, keyboard/a11y passed; exit 0.
- Maven: shared repository (queue A).

## Left for the milestone
The full proof set (two-server proof, 3 frontend runs, the export end-to-end).

## Questions
1. **A page check a person ticked "checked" on, with a value still found:** the kinds that page check named are kept (delivered), as before 0119 and as a dismissed finding is. Only a value nobody was shown on that page stops the document. Recommended: keep this; the person decided.
2. **Pages no person looked at are read again after the review** (allowed by rule b), so a picture page with nothing for a person is read twice (before and after the review). Keeping the first reading would need a stored per-page result (a schema change). Recommended: keep as is; the cost is one vision reading per such page.
3. **The text search alone can't see a value under a person-finished page that was turned into a picture** when "searchable redacted pages" is off (no text layer). The whole-file check of the redacted values still runs. Recommended: accept (the person looked at that page).

No other product's name or behaviour in code.
