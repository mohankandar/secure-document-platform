# Report U004: Review: the words found, zoom, and "Redact all like this" (findings 6, 7)

Date: 2026-11-18
Branch: `feature/ui-polish`
Outcome: DONE, with one gap. The owner's answers are applied (see the two follow-up sections). Review was checked live on a fake case in review. The text being shown is covered by a mocked browser test, not seen on a real document: the start script cannot turn the AI off, so the live run was skipped as instructed.

## What was done

**Owner's approval (2026-11-18), and how each condition is met.** The text field was approved on four conditions:

| Condition | How it is met |
|---|---|
| Returned only to MANUAL_REVIEW | Only on `GET /api/v1/review-items`, which already requires MANUAL_REVIEW. No other endpoint returns it. |
| Never in logs, audit detail or browser storage | Nothing new is logged or audited. The records holding the text print as "redacted" (`toString`), so an accidental log line cannot leak it. Skip log lines carry only the document id and a reason code. The response is `Cache-Control: no-store`. The UI keeps the queue in memory only (SWR); a browser test checks that local and session storage hold no part of it. |
| Full text only in the decision panel; queue items masked except the last 4 | The panel shows "Text found" and the same line around it. Queue items show `••••0193`. The mask has a fixed width, so it does not show the length. **Owner's rule (follow-up):** the last 4 show only when the value has 8 or more characters; shorter values (a credit score "712", a short date) are masked in full. |
| No schema change | None. Nothing new is stored. |

**Backend: where the text comes from.** The stored finding has only a masked preview (by design, `snippet` is "never raw sensitive value"). So the text is read when the queue is asked for:
- from the document's stored `EXTRACTED_TEXT` artifact, using the finding's page and character positions (new `ReviewFoundTextReader`);
- one read per document, and only for the items returned;
- **it fails closed:** no text is returned when there is not exactly one available text artifact, the page count does not match the document, the positions fall outside the page or are 0..0 (unlocated), the span is blank or over 200 characters, or the read fails. The UI then hides the row;
- the context is up to 40 characters each side, on the same line. A span that crosses a line (an address) is shown on one line.
- The page-break marker used when the text artifact is written is now a shared constant (`TextExtractionService.PAGE_SEPARATOR`), with the same value. Extraction behaviour is unchanged.

**Backend: one more field, not a security matter.** The queue response now also carries `autoRedactThreshold`, the policy level from which findings are redacted automatically. The UI needs it for the "why" sentence, and Processors and Supervisors cannot read the policy otherwise. The service already loaded the policy to work out the reason.

**Review screen**
1. **Text found:** shown in the decision panel ("Text found", the value, and “Borrower SSN: **412-55-0193**”); masked in queue items. The row is hidden when the field is absent.
2. **Zoom:** −, the percentage, + and Fit above the page. 100% is the page at actual size (the preview is rendered at 144 DPI). Steps: 50 to 300%. The page scrolls inside the viewer so the finding (or the person's mark) stays in view. Zoom stays the same when moving to the next finding.
3. **Why a person is asked**, in one sentence: "LexaVault is 62% sure this is sensitive. Your policy redacts automatically from 85%, so a person decides." There are other sentences for "place not found" and "policy sends this type to a person". The panel follows the design: the confidence bar is replaced by this sentence, and Document and page are on one line.
4. **Redact all like this:**
   - shows "<n> more <type> waiting in this case" and "Redact all <n+1> like this" (Shift+R);
   - **asks first (follow-up):** the button and Shift+R open "Redact <n> <type> findings?" (for example "Redact 2 Social Security Number findings?"), showing the case and what is skipped, with Cancel and Redact. Cancel sends nothing. Single Redact stays without confirmation;
   - counts only the same type (aliases such as US_SSN count as SSN) in the same case;
   - each finding is confirmed through the existing per-finding call, one after another, so each is audited as its own decision;
   - findings whose place is not found are skipped and stay in the queue; the panel says how many will be skipped before, and the result says how many were. A finding someone else already decided is dropped quietly; any other failure is counted and stays in the queue;
   - there is no "Keep visible all".
5. **Type names** come from the vocabulary module, which now also has plurals ("Dates of birth", not "Date of births").

## Commands run and results

- **Full build:** `.\Build-LexaVault-All.ps1` passed (backend Maven clean install with tests, and the frontend build). A first run failed before building, because I had redirected all of the script's output; run normally, it passed.
- **Frontend:**
  - `npm run build`: passed.
  - `npm run test:standalone`: passed (`test:auth` 8, `test:vocabulary` 19 (9 new), `test:ui-framework` 17, `test:oidc-signin` 26 (7 new)). Final run after the follow-up.
  - **One failed run:** an earlier run stopped because the sign-in suite's dev server did not start within 60 seconds, right after a build. The rerun passed, and so did the final run.
- **Mocked screen checks:** Review as Document Processor, in light and dark, at 1440 and 1024 px, at Fit and zoomed in. They used the mocked browser harness: text found, the masked queue, the why sentence, the bulk block and zoom. No page errors.
- **Local app:**
  - started with `.\Start-LexaVault-Local.ps1 -ScannerMode SIMULATOR -AiModel qwen3.5:9b`, with the jar from this branch. The script stopped with "Frontend did not become ready on localhost:5173", but the frontend answered shortly after and every check ran.
  - **First check:** Review as Document Processor and Document Supervisor, light and dark, 1440 and 1024 px, in Edge (Playwright). The queue was empty, so the screens showed "Nothing left to decide". The live API answered with `autoRedactThreshold` 0.8 and `Cache-Control: no-store`. No page errors.
  - **Live check with fake data (follow-up):** two cases opened through New case as Document Processor (the normal app path), with fake numbers starting 9999. Both use tracked synthetic fixtures, so no new document was added or committed:
    - contract `999900000401`, `test-data/golden-path-watched-intake-template/03-manual-review/03_manual_review_ambiguous_score.pdf`: processed and **delivered without review** (LexaVault was sure). That fixture's notes say it expects Document Intelligence off, and the local app runs with the AI model;
    - contract `999900000402`, the OCR variant `03b-manual-review-ocr/03_manual_review_ocr_ambiguous_score.pdf`: **in review**, one Credit score finding, confidence 50%, place not found.
      - The live queue returned it **without** found text, which is right: it is an unlocated finding (no character positions), so the reader refuses it.
      - Review checked as Document Processor and Document Supervisor, light and dark, 1440 and 1024 px. It showed "place not found" in the queue, no "Text found" row, and "LexaVault found this text but not its exact place on the page, so a person decides." No page errors.
      - The case is left in review in the local data.
  - Stopped with `.\Stop-LexaVault-Local.ps1` after each session.
- **Rules:** no raw hex outside `tokens.css`, only documented breakpoints, no new dependency. The bundled `node:zlib` is used in the test only.

## Tests: counts per module, and any failures with their output

- **Backend:** 440 tests, none failing (domain 31, application 125 (+9), scan bridge 47, infrastructure 8, API 229 (+1)).
- **New `ReviewFoundTextReaderTest` (application, 9):**
  - reads the text and the line around it;
  - context is cut at 40 characters and at line ends;
  - a span across a line is shown on one line;
  - findings with positions but no box still get their text;
  - fails closed for unlocated, out-of-range, wrong-page, over-long and blank spans;
  - a page-count mismatch hides all text;
  - zero, two or an unavailable text artifact hides all text;
  - a read error hides the text instead of failing the queue;
  - the queue adds text only to the items it returns, reads each document once, and returns the policy level.
- **`FocusedReviewControllerSerializationTest` (API, +1):** the text is in the JSON, and never in any `toString`. The existing test now also checks `foundText: null`.
- **New `src/studio/reviewRules.test.ts` (node, 9):**
  - bulk counts only the same type in the same case;
  - bulk is not offered when no other finding of the type waits;
  - bulk skips findings with no place (and a drawn mark counts as a place);
  - singular wording;
  - the confirmation title and body;
  - the result sentence;
  - the masking rule (8 characters show the last 4; 7 or fewer are masked in full);
  - the why sentence;
  - zoom steps.
- **New `e2e-oidc/review.spec.ts` (Playwright, mocked API, 7):**
  - the full text is only in the panel, the queue is masked, a 3-character score shows no digit, and nothing is in browser storage;
  - the why sentence with 85%;
  - no "Text found" row without text;
  - Redact all: Shift+R opens the confirmation, Cancel sends nothing, the button opens it again, Redact confirms f1 and f2 once each (not the unplaced one or the other case), and the unplaced one stays;
  - single Redact sends one call with no confirmation;
  - no "Keep visible all";
  - zoom −, %, + and Fit.
- No existing test was weakened.

## Files changed

- **Backend, new:**
  - `securedoc-application/.../review/result/ReviewFoundText.java`;
  - `securedoc-application/.../review/service/ReviewFoundTextReader.java`;
  - `securedoc-application/src/test/.../review/service/ReviewFoundTextReaderTest.java`.
- **Backend, changed:**
  - `ReviewItemSummary.java` (`foundText`, redacted `toString`);
  - `ReviewQueueResult.java` (`autoRedactThreshold`);
  - `FocusedReviewService.java` (reads text for returned items; new constructor argument);
  - `TextExtractionService.java` (shared `PAGE_SEPARATOR` constant, same value);
  - `FocusedReviewController.java` (`foundText`, `autoRedactThreshold`, `no-store`, redacted `toString`);
  - `ApplicationServiceConfig.java` (wiring);
  - `FocusedReviewControllerSerializationTest.java`.
- **Frontend, new:** `src/studio/reviewRules.ts`, `src/studio/reviewRules.test.ts`, `e2e-oidc/review.spec.ts`.
- **Frontend, changed:** `src/studio/Review.tsx`, `src/studio/words.ts` (plurals, type key, `whyAsked`), `src/studio/studio.css`, `src/types/index.ts`, `package.json` (`test:vocabulary` also runs `reviewRules.test.ts`).

## Commits (hash and message), or "not committed"

- `0ee0271` feat(ui): Review shows the text found, zoom, and Redact all like this (UI task U004)
- `617b870` feat(ui): apply the owner's U004 answers (masking from 8 characters, confirm Redact all)
- The commit that adds the second follow-up to this report (report only, no code).

Not pushed, not merged.

## Follow-up: owner's answers (2026-11-18)

1. **Masking:** show the last 4 only when the value has 8 or more characters; mask shorter values in full. Done (`maskFoundText`, tests).
2. **Live check:** allowed with fake data only, through the normal app path, with no document committed unless it is a clearly fake fixture. Done with two tracked synthetic fixtures through New case (see "Commands run"). Nothing new was committed.
3. **Redact all:** confirm first with "Redact <n> <type> findings?", Cancel and Redact; single Redact stays without confirmation. Done (the shared `ConfirmDialog`, as on Cases). While the confirmation is open, the Review shortcuts are off.

## Not done, and why

1. **The text being shown has not been seen on a real document.** Showing it needs a real finding that has character positions but low confidence, and neither tracked fixture gives one:
   - the plain fixture was redacted automatically;
   - the OCR fixture's finding has no place, so its text is withheld, which is the designed behaviour.
   
   The read itself is proven by the unit tests on the real reader and service code. A live check would need a new fake fixture: for example an image-only PDF with a fake SSN starting with 9, scanned poorly enough that text recognition is unsure of it. There is no tracked tool for that (`scripts/testing` has a Python generator, and Python is not installed here), and getting the confidence below 0.8 may take a few tries, each one adding a fake case to the local data.

## Second follow-up: owner's answer (2026-11-18)

**Asked:** no new PDF. Restart the local app with the AI model off, if the start script supports it, and open fixture 401 again under a new fake 9999 number. Check that the text shows in the panel and is masked in the queue, and that the fake value appears nowhere in the backend logs or audit records. If the AI cannot be turned off, skip the live run, make sure a mocked Playwright test covers the panel showing the text, and note it here.

**Result: live run skipped. The start script cannot turn the AI off.**
- `Start-LexaVault-Local.ps1` always starts the backend with `SECUREDOC_AI_PROVIDER = 'ollama'`.
- Its only AI switches are `-AiModel`, `-SkipAiWarmup` and `-PullModelIfMissing`; none of them turns the AI off.
- No case was opened, and no settings or scripts were changed.

**Mocked coverage instead:** `e2e-oidc/review.spec.ts`, test "the full text is only in the decision panel; queue items show only the last 4, and short values not at all". It checks that:
- the panel shows "Text found", the value `412-55-0193`, and “Borrower SSN: 412-55-0193”;
- the queue shows `••••0193` and `••••7741`, and no full value;
- a 3-character score shows only `••••`;
- browser storage holds no part of the value.

It passed in the last `npm run test:standalone` run (`test:oidc-signin` 26 of 26).

**Not checked live, therefore:**
- the text showing on a real document;
- a search of the backend logs and audit records for a live fake value.

What covers those today, in code and tests:
- the reader and every record holding the text never print it (`toString` is redacted, and tested in `FocusedReviewControllerSerializationTest`);
- skip log lines carry only a document id and a reason code;
- the audit entries for a decision carry only the finding type.

## Questions for the owner

1. **How to see it live later:** a live run needs the AI off. Two ways:
   - a switch added to the start script (a script change);
   - turning the AI mode off for the local tenant in Admin (a change to the local settings).
   
   Neither was done. Should one of them be part of a later task?

## Suggested next step

U005.
