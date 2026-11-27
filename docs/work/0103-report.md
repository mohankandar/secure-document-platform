# Report 0103: detection that does not depend on labels

Date: 2026-11-27
Branch: `feature/0103-label-free-detection`, from the integration branch at `0a756fc` (0100 merged)
Outcome: **DONE** (6 questions at the end; the recommended answer taken in each)

## The owner's two handwritten dates of birth (addendum), reproduced first
`long-60-pages.pdf`, pages 23 and 32.

| What | Found |
|---|---|
| Were the pages read by the vision model? | **No.** Both went the fast way: OCR confidence 0.88 and 0.86, above the 0.85 line, and the handwriting check did not flag them. Pages 5 and 14 (same layout) went to vision (0.81). |
| What OCR read | The handwritten month as a month that does not exist, on both pages. On page 23 also the SSN with a stray space in its last group. |
| Why nothing found the date | The date rule wanted a real date; the AI's answer is checked to be a real date; the final check reads the same OCR text. Nothing looked at a birth label followed by a damaged date. |
| The vision model on these pages | Asked directly (one at a time, and four at once), it found the SSN **and** the date of birth on both pages every time, with the right boxes. It was never asked. |

Now: the date rule holds a birth label followed by something shaped like a date that is not a real one (OCR damage) for a person; the SSN with a stray space next to its label is covered; the final check also asks the vision model about every page that was a picture in the original; and a vision page with an SSN but no date of birth (or the other way round) goes to a person. In the product run below, long-60's pages 23 and 32 were held with their dates of birth.

## Audit: how each kind was found before this task, and how it could be missed

| Kind | Found by (before) | Missed when (before) |
|---|---|---|
| SSN | 3-2-4 with `-` or space **and** an SSN label within 90 characters, nearer than a business label; the AI (V1 kind); the OCR field guard (label + colon) | no label; old, short or Spanish label (Soc. Sec., SS#, Seguro Social); label further than 90; split over two lines; OCR letters for digits; 9 digits together; the AI's answer was **dropped** without SSN words |
| Date of birth | M/D/YYYY with a birth label within 100 characters; the AI, **only** with a birth label (otherwise dropped); vision | no label; other wording (B'date, Fecha de nacimiento); other formats (textual, dots, ISO, D/M/Y); OCR damage (impossible digits); split; picture pages routed the fast way |
| Card | 13-19 digits, check digit, card words near; grouped with an issuer's digits at 0.75 (0099); OCR l, I, O | ungrouped without words; a business label; split over lines; OCR S, B; failed check digit (misread) |
| CVV | its label on the line, or the one number under a CVV header | no label next to a card; old, short or Spanish labels; label earlier in the sentence; a stray space; a card table with several numbers; **not asked of the AI** |
| PIN | "PIN" in capitals or "pin number" | "Pin #", NIP, sentence, near a card; **not asked of the AI** |
| Credit score | score label within 15 characters; one table header wording; the AI with score words; OCR geometry | Beacon, Spanish; another table header; OCR damage; a number alone |
| Routing | check digit **and** a routing label on its line or the line above; the check's bottom line; vision | no label, far label, old (Bnk Rte) or Spanish label, sentence, split, heavy OCR damage; **not asked of the AI** |
| Account | an account label on its line or the line above; the check's bottom line; vision | anything without that label; Spanish; sentence; split; OCR damage; **not asked of the AI** |
| ITIN, EIN | their label | no or far label, old wording, sentence, split, OCR damage; **not asked of the AI** |
| Driver's licence, passport | their label; vision (one "ID" kind) | no or far label, short or Spanish wording, sentence, split, OCR damage; **not asked of the AI** |
| Names | the AI only, always a person | (unchanged by this task) |

## The hard test set, built before the fix
`LabelFreeHardSet` (test code, made by code, no file, no real value): one page per case, 12 kinds × no label, far label (4 lines above), old or short label, Spanish label, table, running sentence, split over two lines, OCR damage, printed in an image (OCR), "handwritten" (an image of turned, shifted italic letters; OCR); 115 values. Plus 9 decoy pages that must stay visible: order numbers, phone and fax, amounts, statement and due dates, a sign date and an "opened" date, ZIP+4, an invoice number, a rate and a term, a quantity and a model year, a case and a docket number.

`LabelFreeDetectionHardSetTest` runs it through the product's detection service: real text reading with Tesseract, the rules, **every text kind on**, and a stand-in AI that finds nothing (only the rules and OCR count, the hardest case for them; the AI reads on top in the product). A value counts as found only when **all** of it is under findings.

### Before (on `0a756fc`) and after
A covered, P a person decides, H the page is held for a person, M missed, M? OCR did not read it at all.

Before:
```
kind                    no_label  far_label old_label spanish table sentence split ocr_damage image handwritten
SSN                     M         H         M         M       A     A        M     M          A     M?
DATE_OF_BIRTH           M         H         M         M       A     A        M     M          A     A
CREDIT_CARD_NUMBER      P         P         A         P       A     A        M     M          A     A
CREDIT_SCORE            M         H         M         M       M     A        -     M          A     A
BANK_ROUTING_NUMBER     M         M         M         M       A     M        M     M          A     A
BANK_ACCOUNT_NUMBER     M         M         A         M       A     M        M     M          A     A
ITIN                    M         M         M         A       A     M        M     M          A     A
EIN                     M         M         M         M       A     M        M     M          A     A
DRIVERS_LICENSE_NUMBER  M         M         M         M       A     M        M     M          A     M?
PASSPORT_NUMBER         M         M         M         M       A     M        M     M          A     M
CVV                     M         M         M         M       M     M        -     M          M     A
PIN                     M         M         M         M       M     M        -     M          A     A
values: A=36, P=3, H=3, M=73, M?=2   decoys: all visible
```
After:
```
kind                    no_label  far_label old_label spanish table sentence split ocr_damage image handwritten
SSN                     A         A         A         A       A     A        A     A          A     M?
DATE_OF_BIRTH           P         A         A         A       A     A        A     A          A     A
CREDIT_CARD_NUMBER      A         A         A         A       A     A        A     A          A     A
CREDIT_SCORE            M         P         A         A       A     A        -     P          A     A
BANK_ROUTING_NUMBER     P         P         A         A       A     A        P     P          A     A
BANK_ACCOUNT_NUMBER     P         P         A         A       A     P        P     P          A     A
ITIN                    P         P         A         A       A     A        P     P          A     A
EIN                     P         P         A         A       A     A        P     P          A     A
DRIVERS_LICENSE_NUMBER  P         P         A         A       A     P        P     P          A     M?
PASSPORT_NUMBER         P         P         A         A       A     P        P     P          A     M
CVV                     P         P         A         A       P     A        -     A          P     A
PIN                     P         P         A         A       P     A        -     A          A     A
values: A=76, P=37, M=2, M?=2   decoys: all visible
```

Misses per kind on readable pages (text and image): **locked items and SSN 22 → 0**; every other kind 51 → 1, the credit score with no label at all (a 3-digit number alone; see question 4). The handwritten column is OCR only: in the product those pages go to the vision model, and the final check asks it again; the 3 handwritten misses there are pages OCR could not read.

### False alarms per kind (findings on no value)
| | Covered without a person | Held for a person |
|---|---|---|
| Decoy pages (9) | 0 before, **0 after** | 0 before, **0 after** |
| Value pages, before | account 1, licence 1 (and the card printed on each CVV/PIN page, 16, which is a real card) | 0 |
| Value pages, after | the same: account 1, licence 1, the 16 real cards | **CVV 26**: lone 3-4 digit numbers near a card (owner's rule 4), e.g. the page's case number |

Boxes on the hard set: automatic 55 → 98, for a person 3 → 77 (most of the new ones are a second kind at the same place, and the codes near cards).

## What was built
- **The owner's rule in the rules** (`LocalRuleBasedDetectionAdapter` 1.9.0, `LockedItemDetector` 1.1.0, `OptInPatternDetector` 1.3.0). A value is found by its shape and validity; a label raises confidence and never decides.
  - **SSN:** every 3-2-4 in the SSN ranges is covered (0.95 with a label of any wording, 0.90 without). Also with en dashes, dots, a line break after a dash, OCR letters for digits, a stray space next to its label, 9 digits together next to its label. When the document names it as something else ("Reference no.", "Batch", "Form number"): a person (0.70). Outside the ranges only with its label (0.75, as in 0069).
  - **Card:** every 13-19 digit number with an issuer's first digits and a valid check digit is covered (0.95 with card words, 0.90 without), grouped or not, split over two lines (each line its own box), OCR S and B too. Named as something else: a person (0.75). Card words with a failed check digit (a misread): a person (0.60).
  - **CVV and PIN:** more wordings (CSC, CID, Sec. code, Código de seguridad, Pin #, NIP, clave personal), a label earlier in the sentence (covered), a stray space. **Rule 4:** every lone 3-4 digit number within 6 lines of a card number is held for a person (0.70).
  - **Date of birth:** numeric (/, -, .), ISO, written-out months (English and Spanish), D/M/Y when M/D/Y is impossible, a line break before the year. With a birth label of any wording: covered (0.90). A birth label before something date-shaped that is not a real date: a person (0.60). Without a label, a real date at an age of 14 to 120 that no other date word owns (statement, due, opened, since, signed, issued, expires…): a person (0.70).
  - **Credit score:** Beacon, Vantage, credit rating, Spanish; the number under a column header with a score word; a score label up to 250 characters before a number alone on its line: a person.
  - **Routing, ITIN, EIN:** a valid value (check digit and valid first digits; ITIN groups; EIN prefixes) without a label: a person (0.70); with its label anywhere in the window before it: covered (0.80). **Account, licence, passport:** an 8-17 digit number, or a letter and 6-9 digits, that nothing names: a person (0.70); the document naming it as something else (order, invoice, part, batch, business licence, plate…): nothing. More wordings for all (Bnk Rte, Acct., Cuenta, Fed. Employer No., Drv. Lic., Licencia de conducir, PP No., Pasaporte). A label followed by a split or OCR-damaged value: a person (0.60).
- **The AI reads for every kind** (owner's rule 3): the catalog asks it for every kind found in text (the opt-in kinds, CVV and PIN too); its prompt (version 1.4.0) and answer schema have them; its prompt says a label is not needed. An AI finding without its words is no longer **dropped**: it goes to a person (`plausibility=HOLD`). Only a value the document names as another date, or an explained score range, is still dropped. **Where the two readers disagree** (a value only the AI found, of a kind the rules also look for): a person (0.75).
- **Addendum:** a vision page with an SSN but no date of birth, or the other way round (from the model or the text), goes to a person (`GROUP_INCOMPLETE`). The **final check asks the vision model** about each page that was a picture in the original (routed to vision, or read by OCR), on the finished file, with the second check's finished-file prompt: what it sees that is not under a cover holds the page; an unusable answer is asked once more, then holds the page. Only with the entity's vision switch on.
- **Test tool:** `test-tools/owner-files/CheckDelivered.java` also counts, per delivered file, the words and the other numbers of the original's text that are no longer there (both directions; counts only).

## Proof
| Command | Result |
|---|---|
| Red run of the hard set on `0a756fc` (a copy of the base, test files added) | 3 of 6 targets fail: 22 locked/SSN misses, 51 other misses |
| `mvn -o -Pdev-build -pl securedoc-domain,securedoc-application,securedoc-ai install` | domain 87, application 396: 0 failed (the AI module's tests run in the API module) |
| `mvn -o -Pdev-build -pl securedoc-infrastructure install` | 216, 0 failed, 88 skipped (PostgreSQL tests without a database) |
| `mvn -o -Pdev-build -pl securedoc-api install` | **970 run, 0 failed**, 121 skipped (PostgreSQL tests without a database, as before) |
| `LabelFreeDetectionHardSetTest` (6) | green: 0 locked/SSN misses, every other miss sent to a person except the known limit, no decoy covered, no value in a finding or the audit |
| `LockedItemsNeverDeliveredTest` (8, the owner's files from 0099) | green |
| `node test-tools/owner-files/owner-files.mjs --real-ai` (13 files and the 3 long files, real models) | see below |
| Benchmark gate, `Invoke-VisionProductBenchmark.ps1`, one run (fixed jar) | routed 127 of 130, all 271 of 274, V1 1 miss: **misses equal to the 0092/0099 baseline** (not worse). False flags 10 (baseline 4: 6 more, from the label-free holds, all for a person) and pages for a person 6 (0099: 0; 0100: 2, the OCR-unreadable pages; +4 from the group check and the final check's vision reading). Vision pages 6.5 s mean. |

**The owner's files through the product, real AI and vision** (the run confirms every value item as a reviewer would; a page to check is left to a person):

| File | Result | Words / other numbers of the original's text gone |
|---|---|---|
| 01, 02, 11, 12 | delivered, **CLEAN** | 0 / 0 |
| 03, 04 (scanned, check) | delivered, **CLEAN** | (pictures, no text of their own) |
| 07 court filing | delivered, **CLEAN**; one SSN-shaped number went to Review and was confirmed by the run | 0 of 1006 / **2 of 68** (that number, as confirmed by the stand-in reviewer) |
| 13 hidden text | delivered, **CLEAN** | 8 of 28 / 1 of 2: the hidden lines are removed whole (0099) |
| 08 | refused at intake (password) | |
| 05, 06, 09, 10 | held for a person (date of birth, SSN without a place, page to check) | |
| long-20, -40, -60 | held for a person; **long-60 pages 23 and 32: date of birth found** | |

- **ALL CLEAN**: nothing delivered holds a value. No value in the database dump or the logs: PASS.
- The final check read 5, 10 and 15 picture pages of the long files (about 3 s a page). In that run the vision model's first answer on some handwritten notes was unusable, which held those pages; the model's answer was then asked once more (fixed after this run, test added; the benchmark run is on the fixed jar).

Left for milestone M2 (rule 9): the two-server proof, three frontend runs, the export end-to-end. No frontend change, so no frontend run (Review already words the final check's codes).

No other product's name or behaviour in code. The known exception `SdvDeliveryAdapter` is not touched.

### Tests changed, and why (each held the rule this task reverses)
- `LockedCardDetectionTest`, `OptInPatternDetectorTest`, `DeterministicContextPrecisionTest`: a valid card or SSN-shaped number the document names otherwise ("Contract number", "Reference", "Batch", "Form number"), an unlabelled letter-and-digits ID, an account number in a sentence: from "not found" to "held for a person" (never covered). An unlabelled grouped or ungrouped card: from 0.75 to covered (0.90). A card with card words and a failed check digit: from "not found" to a person (0.60).
- `SensitiveCandidatePlausibilityPolicyTest`: an AI finding without its words (SSN with a business label, card with a contract label, a score without score words): from "rejected" to "kept for a person".
- `RedactionCatalogTest`, `OptInKindsPolicySaveTest`, `OptInPatternKindsDetectionTest`, `OllamaAnswerSchemaTest`, `TenantAiPolicyAndRoutingTest`: the AI is asked for every kind found in text; the pinned prompt fingerprints are the new prompt's (version 1.4.0).
- `PageBatchesEqualWholeDocumentTest`: a date whose label is on the page before was "missed without shared pages"; now it is held for a person without them and covered with them.

## Questions (recommended answer taken)
1. **More Review work.** Unlabelled dates at a person's age, lone numbers near a card, unlabelled routing/account/ID-shaped numbers (when those kinds are on), and AI-only findings now go to a person. *Recommended: keep (the owner's rule: never dropped). Taken.* The benchmark's false flags and the owner's next run will show the size.
2. **A valid SSN or card the document calls something else** ("Reference no. 287-65-4321") goes to a person instead of being covered. *Recommended: a person, as the rule allows "clearly something else" to be shown by the document. Taken.*
3. **"Where the two disagree"** is read as: a value only one reader found goes to a person when it is the AI; a value only the rules found keeps the rules' confidence (rule 1 and 2 say covered or a person). *Recommended: keep. Taken.*
4. **A credit score with no score word anywhere** (a 3-digit number alone) is not found by the rules: it can't be told from any other number. The AI is asked for it. *Recommended: accept as the known limit, listed in the test.*
5. **The person's group check** holds every vision page with an SSN and no date of birth (or the reverse), also a page that really has only one of them (a W-2 page read by vision). *Recommended: keep (the addendum). Taken.*
6. **The final check's vision reading** costs about 3 s per picture page (15 pages on long-60: 46 s). *Recommended: keep. Taken.*
