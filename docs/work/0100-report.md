# Report 0100: nothing goes out unchecked (poor pages, findings without a place)

Date: 2026-11-26
Branch: `feature/0100-nothing-unchecked`, stacked on `feature/0099-locked-always-caught` (PR #46, not merged yet)
Outcome: **DONE** (2 questions, the recommended answer taken in each)

## What was found (measured first)
| Finding | Cause |
|---|---|
| R4, `10-…` p3 delivered | The page **was** routed to the vision model, with `LOW_OCR_CONFIDENCE` and `NOT_READABLE` (OCR confidence 0.21, not blank). It was not a page to check: the vision model answered "read", and a page the model read counted as read, however poor OCR's reading was. On the owner's run the model missed the faint date of birth, so nothing held the page. |
| R8, "will be skipped" | The words were wrong, not the rule. The backend already refused "Redact" for a finding without a place (`LV-REV-006`), and an undecided finding already held the case (no redaction while a finding is DETECTED; no delivery before). "Redact all N like this" left those items in the queue, but the screen said they "will be skipped". |

## What was built
- **R4**: a routed page that OCR could not read (`NOT_READABLE`) is always a page to check, also when the vision model read it. What the model found on it stays, for the person to see (source `vision-page-check`, reason `TEXT_NOT_READABLE`). A blank page is not routed, so it still passes. Pages with only low OCR confidence (fax, handwriting) stay with the vision model, as the benchmark measures them.
- **R8**: the Review wording now says what happens:
  - under the button: "1 of them has no place on the page. The case is not delivered until someone marks its place or keeps it visible."
  - in the confirmation: "… 1 has no place on the page and is not part of this: the case is not delivered until someone marks its place or keeps it visible."
  - after the run: "… 1 has no place on the page and is still waiting: the case is not delivered until …".
- `test-tools/owner-files` (from 0099): `--real-ai` (the local models instead of the stand-in) and `--only=…`, and it reports a "Redact" that was refused.

## Proof
**Red first:**
- `VisionDetectionTest.aPageOcrCannotReadGoesToAPersonEvenWhenTheModelReadIt` failed before the change ("UNREAD_PAGE on page 4: expected 1, was 0"), then passed.
- The new wording tests in `reviewRules.test.ts` failed before the change, then passed.
- `UnplacedFindingHoldsTheCaseTest` (3) passed on the first run. It proves that the rule already held (see above).

**The owner's files with the real models** (`node test-tools/owner-files/owner-files.mjs --real-ai --only=10-…,06-…`; throwaway database; Ollama 0.40.18):

| File | Result |
|---|---|
| `10-mixed-good-blank-unreadable.pdf` | held in Review. The vision model read page 3 (3 items, among them the date of birth), and page 3 is still a page to check. After the date of birth and the SSN were confirmed, the case stays held for page 3. |
| `06-handwritten-form-scanned.pdf` | held in Review. The SSN and one date of birth were confirmed. The second date of birth has no place, and its "Redact" was refused with `LV-REV-006`, so the case waits for a person to mark it or keep it visible. |

- Nothing was delivered with an undecided item.
- No value in the database or the logs: PASS.

| Command | Result |
|---|---|
| `mvn -o -Pdev-build -pl securedoc-application test` | 380, 0 failed |
| `mvn -o -Pdev-build -pl securedoc-api test` | 962, 0 failed, 120 skipped (PostgreSQL tests; CI runs them) |
| `node --experimental-strip-types --test src/studio/reviewRules.test.ts` | 14, 0 failed |
| `npm run test:standalone` (one frontend run) | auth 10, vocabulary 245, ui-framework 19, oidc-signin 233 (with `review.spec.ts`), tablet 110, a11y 380: 0 failed |

**Tests changed, and why:**
- `VisionDetectionTest.aPolicyChangedMeanwhileAsksTheModelNothingAgain`: page 4 (OCR cannot read it) now also has its page to check.
- `reviewRules.test.ts` (3 sentences) and `e2e-oidc/review.spec.ts` (3 sentences): the new wording.

| Benchmark, `Invoke-VisionProductBenchmark.ps1`, one run (Ollama 0.40.18) | routed 127 of 130, all 271 of 274, 1 V1 miss, 4 false flags: **the same as 0099 and the 0092 baseline**. **Unread pages: 2** (was 0): the two benchmark pages OCR cannot read, now held for a person on purpose (`PAGE_CHECK`: 2). See question 1. |

Not run, as rule 9 allows: the two-server proof. Nothing here changes the pipeline across servers.

No other product's name or behaviour in code.

## Questions (recommended answer taken)
1. **For the design chat: the benchmark gate's "no unread page"** now reads 2: the two benchmark pages OCR cannot read are held for a person on purpose (R4), while every score is unchanged. *Recommended: the gate counts `TEXT_NOT_READABLE` pages apart from unread pages (`score.mjs`, a small follow-up), and the baseline line in `test-strategy.md` section 5 says "2 pages held because OCR cannot read them". Not changed here: the gate rule is the design chat's.*
2. **Which pages can't be trusted:** only `NOT_READABLE` (OCR read nothing usable). Low OCR confidence alone (fax, handwriting) is left to the vision model, or every scan would wait for a person. *Recommended: keep. Taken.*
