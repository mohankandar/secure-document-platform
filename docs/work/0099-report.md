# Report 0099: locked items always caught, hidden text never delivered

Date: 2026-11-26
Branch: `feature/0099-locked-always-caught`, from the integration branch at `999a97a` (0092 merged)
Outcome: **DONE** (5 questions, the recommended answer taken in each)

## Why the owner's three findings happened (measured first)
| Finding | Cause |
|---|---|
| R1, card on `01-…` p1 | The card rule wanted "credit card", "card number" and the like within 160 characters. "paid by card" was not one of them, so the card was dropped. |
| R1, CVV on `01-…` p1 | The CVV **was** found by the rules. The owner's entity is from before the catalog (0062): migration 070 left `locked_items_on = false` until its policy is saved again, so CVV and PIN were off for it and their findings were dropped. A second, wrong CVV hit (on the credit score line below) came from the "code under its header" rule. |
| R2, card on `03-…` | OCR reads the card as `41111111 1111 1111` (one space lost). It still has 16 digits and passes the check digit, but the only label was a bare "Card", the same gap as R1. |
| R3, `13-…` | The page has under 400 characters, so it goes to OCR. The page's own text was then thrown away, and OCR does not see white or 1-point text. The page had no finding, so it was delivered as it was, hidden text included. The SSN on the hidden line had a second cause: in "SSN 512-34-8876 account 4820193746", the label after the value ("account") beat the label before it. |

The final check (0050) only looks for values that were found. A value detection missed was never looked for.

## What was built
- **Locked items always on** (`TenantRedactionPolicy`): card, CVV and PIN are enabled for every entity, whatever `locked_items_on` says. The admin screen shows them as locked. No migration: the column stays.
- **Card rule:**
  - On the number's own line, "card", an expiry (`exp`) or a CVV/CVC label also count as card context.
  - In OCR text, up to three of `l I |` (for 1) and `O o` (for 0) are read as digits.
  - A number grouped like a card (4-4-4-4, 4-6-5), with a card issuer's first digits and a valid check digit, is found without a label at 0.75. It goes to a person. It is not found when a business label (contract, account, reference…) is on its line.
- **CVV:** a line that holds its own code is not a header for the next line.
- **SSN:** an SSN label just before the value wins over another label after it.
- **Hidden text** (`HiddenNativeText`): on a page read by OCR, every line of the page's own text with a word OCR did not read at that place is added after the OCR text, whole and at its own places. So white, tiny and covered text is searched, found and covered like visible text. A one-letter word counts as read when OCR read anything at its place.
- **The guard** (`FinalPatternCheck`, always on, not a setting):
  - Before a redacted file is kept, it is read as a reader would read it: the text layer, with OCR where a page is a picture, and the hidden text above. Then it is searched with the pattern rules again.
  - A locked kind, or a kind the entity has on, that is still found sends that page to Review as a "page to check" with the kinds. Nothing is stored, so nothing can be delivered.
  - What a person decided to keep visible, digits kept on purpose, and pages a person already checked are no hit.
  - Audit action `DOCUMENT_FINAL_CHECK` (pages, kinds, counts). Review shows "Still readable in the finished file on page N: card number…", found by "Final check".

## Proof
**Red first**: the new owner-file tests run on the base commit (`999a97a`), without the code changes: **R1, R2 and R3 fail**, each with "a locked or enabled value is in the delivered file"; 02, 09 and the clean files pass. With the change: green.

**The owner's files through the product** (`test-tools/owner-files`, new): a throwaway database, the built jar, the watched folder and a stand-in AI that finds nothing, so only the rules and OCR find anything. Every delivered file was read again (text layer and Tesseract OCR) for every SSN, date of birth, card, CVV and score of `README.md` and `long\*-expected.txt`.

| File | Result |
|---|---|
| 01, 02, 03, 07, 11, 12, 13 | delivered, **CLEAN** |
| 08 | refused at intake (password) |
| 04, 05, 09, 10 (p3) | held: page to check. These pages are routed to the vision model, which the stand-in AI doesn't provide (`MODEL_NOT_INSTALLED`). |
| 06 | held: date of birth to confirm, and a page to check |
| long-20, -40, -60 | held: handwritten pages to check (vision), plus SSN and date-of-birth items |

- **ALL CLEAN**: nothing delivered holds a value.
- No value in the database dump or the logs: PASS.
- The guard held none of the owner's files: detection itself found everything. It took 0.5 to 0.9 s per one-page document (one page's OCR).
- The files held for vision pages are proven by the benchmark below (real models) and by 0100, not delivered here.

**Automatic tests added** (the owner's synthetic files 01, 02, 03, 07, 09, 11, 12 and 13 are copied into `securedoc-api/src/test/resources/owner-test-2026-11-25`):
- `LockedItemsNeverDeliveredTest` (8): each file through real text reading (Tesseract), the rules, the default policy, the real redaction, the verification and the guard, then the delivered file read again. Also: a missed card holds the file with its kind only; a checked page or a kept value is not held again; a pre-catalog entity has its locked items.
- `LockedCardDetectionTest` (7), `HiddenNativeTextTest` (5), a CVV header case in `LockedItemDetectorTest`, and a words test for the new Review line.

| Command | Result |
|---|---|
| `mvn -o -Pdev-build -pl securedoc-domain,securedoc-application install` | 87 + 380, 0 failed |
| `mvn -o -Pdev-build -pl securedoc-infrastructure install` | 209, 0 failed, 81 skipped (as before) |
| `mvn -o -Pdev-build -pl securedoc-api install` | 958 run: 5 failed, all tests of the rules changed here (below). Fixed, and the 2 classes rerun: 14, 0 failed |
| `mvn -o -Pdev-build -pl securedoc-api surefire:test -Dtest=LockedItemsNeverDeliveredTest` | 8, 0 failed |
| Red run on `999a97a` | 3 of 5 failed (R1, R2, R3), as expected |
| `node test-tools/owner-files/owner-files.mjs` | ALL CLEAN; no value stored or logged |
| `npm run test:standalone` (one frontend run) | auth 10, vocabulary 244, ui-framework 19, oidc-signin 233, tablet 110, a11y 380: 0 failed |
| Benchmark gate, `Invoke-VisionProductBenchmark.ps1`, one run | Ollama 0.40.18: routed 127 of 130, all 271 of 274, 1 V1 miss, 4 false flags, 0 unread pages: **equal to the 0092 baseline** |

**Tests changed, and why** (each one held the rule this task changes):
- `RedactionCatalogTest`, `RedactionCatalogSaveTest`, 2 tests in `OptInPatternKindsDetectionTest`, and 2 in `SearchableRedactedPagesPolicyPostgresTest` (found by CI: the PostgreSQL tests are skipped without a database; then run locally on a throwaway database, all `*PostgresTest` 79 + 117, 0 failed): an entity from before the catalog no longer "keeps exactly its kinds". The locked items are on for it.
- 2 more in `OptInPatternKindsDetectionTest`: the AI is now always asked for card numbers too. The card is a locked kind that the AI and the patterns both look for.
- `OptInPatternDetectorTest.cardsNeedTheirCheckDigit`: "Batch 4000 0566 5566 5556" (grouped, Visa, valid) is now found at 0.75 for a person to decide. An ungrouped number or one with a contract label is still not found.
- `HybridPdfTextExtractionAdapterTest`: its stand-in OCR reads only the picture, so the page's native title and footer now follow the OCR text.

Left for milestone M2 (rule 9): the two-server proof, three frontend runs, the export end-to-end.

No other product's name or behaviour in code. The known exception `SdvDeliveryAdapter` is not touched.

## Questions (recommended answer taken)
1. **Locked items for entities from before the catalog** now are on without a save. This reverses 0062's "keeps exactly the behaviour it had", because the task says a locked item is never delivered. Their next policy save also stores `locked_items_on = true`. *Recommended: keep. Taken.*
2. **A card without a label** (grouped, issuer digits, valid check digit) goes to a person at 0.75. This may add Review items for numbers that merely look like cards. *Recommended: keep, a locked item fails closed. Taken.*
3. **The guard's cost**: one more reading of the finished file, with OCR of pages that are pictures or sparse. This is most of a redacted page's cost when searchable pages are off, and 0.5 to 0.9 s per page here. Large files with many redacted pages pay it per such page. *Recommended: keep for now; a later task can skip OCR of a page whose searchable layer was just made from the same image. Taken.*
4. **The AI is asked for card numbers** for every entity, also one that chose no V1 kind (it was then not called at all). *Recommended: keep. Taken.*
5. **Hidden-text lines OCR misread** are added once more (for example "I certify…" read as "| certify…" before the one-letter fix). A finding there can appear twice, at the same place. *Recommended: accept, it never hides anything. Taken.*
