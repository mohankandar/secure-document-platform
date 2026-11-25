# Report 0083: a second, independent AI check of the finished file

Date: 2026-11-24
Branch: `feature/0083-second-check`, from `67c7b2e`, with the integration branch (0079, `95bf9ee`) merged in
Outcome: **DONE** (7 questions)

## Measured
The benchmark's 25 synthetic documents (48 pages), redacted by the product, with **one item left visible on purpose** in each of 23 documents (2 have none). Three sets, another item each time. The prompts and filters were tuned on set A only; B and C have the same documents with other items left visible.

| Set | Caught | False alarms per 10 pages | Seconds per page |
|---|---|---|---|
| A (tuned on) | 20 of 23 (87%) | 0.8 | 2.4 |
| B | 18 of 23 (78%) | 0.8 | 2.3 |
| C | 17 of 23 (74%) | 1.0 | 2.4 |
| **All** | **55 of 69 (80%)** | **0.9** | **2.4** |

- *Caught*: the right page with the right kind (account and routing number count as one). A false alarm is a wrong kind on a page.
- By part, all sets: the vision model finds 51 of the 66 items on pages it reads, with **no** false alarm; the text model 26 of 69, with all 13 false alarms.
- **Most missed**: the account and routing numbers of checks (public-16, 17, 18, 21) and face photos on ID copies. Find misses some of the same (public-16 and 18 in the gate run). The second check is not an independent safety net for checks.
- Seconds are the AI's answers only. **The product's own code with the real models** takes 2.8 to 3.8 s a page, with reading the finished file again (OCR) and page images.
- The product's code flags the page with the item in 21, 19 and 18 of 23 documents, and 1, 1 and 0 other pages. This counts pages, so it is a little kinder than the table.

What did not work (set A): telling the model that the file was already redacted, 43% caught; asking for an inventory with boxes, 39%; the final prompts without the code's filters, 83% with 2.7 false alarms per 10 pages; naming the score range in the vision prompt, 70%. So the prompts are neutral, and the code decides what counts.

## What was built
- **After redaction and the final check, before anything is stored**, the finished file is read again if the entity switched it on: the text model on each page's text; the vision model on pages with a redaction, OCR pages and pages with nearly no text. Both have prompts of their own.
- **A hit never redacts.** The document is not stored, not counted for the licence and not completed. Each page becomes a "page to check" (0074), so the case goes to Review, both with staged and inline processing. Review says "Second check found something on page 3: SSN", kind and page only. The reviewer marks boxes or none, ticks "I have reviewed this page", and the case goes on. That page is not asked about again.
- **No hit:** a box on a cover, the last four digits kept on purpose, what a reviewer decided to keep visible, kinds the entity does not redact, a value of the wrong shape.
- **Fail closed:** an answer that can't be used sends the page to a person. A model that can't be asked, or isn't the approved one, stops the document with its AI code.
- **Switch per entity**, off by default (migration 084). Turning on the FRCP 5.2 pack turns it on; it can be turned off again by hand. In the policy's audit record and the compliance report's history.
- **Model governance (BL7):** model, prompt version and digest are on the page to check and in the new audit record `DOCUMENT_SECOND_CHECK`. Both prompts are pinned by hash in a test. Requests count in the learned AI limit.

## A bug found on the way
My answer schema was built with Java's `Map.of`, whose order changes at every start. The model answers in that order, and false alarms on set B went from 4 to 9. Fixed and tested; two separate runs now agree. **Find's answer schema is built the same way** (`buildResponseSchema`, `namesSchema`). Not changed: frozen area, and it needs its own gate run (question 1).

## Commands run and results
| What | Result |
|---|---|
| `mvn -B -o -fae -Pdev-build clean test` with a throwaway PostgreSQL | 89 + 320 + 137 + 141 + 906, 0 failed, 3 skipped |
| `npm run test:standalone`, once (port base 15375) | 10 + 224 + 19 + 205 + 95 + 174, 0 failed |
| Model change gate, one run (switch off, Ollama 0.40.18) | 119 of 130, 262 of 274, 2 V1 misses, 8 false flags, 0 unread: the baseline exactly |
| `node test-tools/second-check/measure.mjs`, 3 sets | table above |
| `SecondCheckSamplesTest` with the real models (A, B twice, C) | figures above |
| No-redacted-value check | the 22 distinct SSN, card, date and account-shaped values readable in the finished samples, searched in 12 logs and result files of the real-model and gate runs: none found; `SecondCheckRedactionTest` searches its audit records and logs |
| `check-product-names.sh` | clean |

Earlier full runs failed on two roll-back counts (one more migration), and on one wrong expectation in my own new test. All fixed.

## Not run, and limits
- **The gate was run once, not three times.** It equals the baseline; with the switch off Find's requests are unchanged.
- **No run of the started app with the switch on**; the pieces are proven by the tests and the real-model run above. No other model server kind was run (the OpenAI-compatible adapter has the prompt, no test).
- All samples are synthetic, and B and C are the same documents as A, so on other documents expect less.
- It looks for: SSN/ITIN, date of birth, card, credit score, bank account, routing number, ID number, face. **Not names, EIN, CVV, PIN or signatures.**
- With the entity's vision switch off, only text is checked.
- A page is read one after the other: 100 pages add about 5 minutes.
- A reviewer sees the original page, not the finished one.
- If a reviewer decides a second-check page in the moment its job is still ending, the case can wait for a new trigger. Milliseconds; not seen.

## Tests changed
Six migration roll-back tests (one more change set); `StagedPipelinePostgresTest`'s redaction stub can now send a page to a person. New: `SecondCheckRedactionTest` (11), and cases in the prompt, adapter, policy, storage, pack and Review tests.

## Questions for the owner
1. **Fix the order in Find's answer schema** (frozen area) as a small task with three gate runs. It may explain the one typed item that changes between runs (0080). Recommended: yes, soon.
2. **Is 80% caught at 0.9 false alarms per 10 pages good enough** to offer it? Recommended: yes, off by default, and never described as a guarantee.
3. **Drop the text model from it?** It adds 4 items of 69 and all the false alarms. Recommended: keep; pages with text only have no other check.
4. **Entities that already have the FRCP 5.2 pack** keep it off until they switch it on. Recommended: keep; a saved policy isn't changed silently.
5. **A model that can't be asked stops the document.** Recommended: keep; sending every page to a person would flood Review.
6. **Checks (account and routing numbers)** need a better reader, for Find as well. Recommended: a task of its own.
7. **Compliance appendix:** the new audit action's details print as "—". Recommended: leave.

No other product's name or behaviour in code.
