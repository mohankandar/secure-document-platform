# Report 0084: confidence-based review, measurement first

Date: 2026-11-25
Branch: `feature/0084-confidence-review`, from 0091's branch
Outcome: **Step 1 DONE. Step 2 NOT BUILT**, on purpose: the product already does it, and no threshold saves a review (4 questions)

## Measured
26 synthetic benchmark documents, 274 items, 276 findings, through the product (0091's code, Ollama 0.40.18, `qwen3.5:9b`, `qwen3-vl:8b-instruct`, every kind but names on). Three runs, the same numbers each time. Wrong: no item of that kind at that place.

| Kind | All / wrong | ≥ 0.75 | ≥ 0.80 | ≥ 0.85 | ≥ 0.90 | ≥ 0.95 | ≥ 0.97 | ≥ 0.98 | ≥ 0.99 |
|---|---|---|---|---|---|---|---|---|---|
| SSN | 30 / 0 | 27 | 19 | 16 | 15 | 12 | 10 | 10 | 10 |
| Date of birth | 34 / 0 | 32 | 20 | 18 | 15 | 14 | 12 | 11 | 11 |
| Credit score | 20 / 0 | 20 | 17 | 15 | 15 | 14 | 7 | 7 | 7 |
| Card number (locked) | 17 / 0 | 17 | 11 | 11 | 10 | 10 | 7 | 6 | 5 |
| Bank account | 25 / 4 | 25 (**4 wrong**) | 14 | 12 | 4 | 2 | 0 | 0 | 0 |
| Routing number | 24 / 3 | 23 (**3 wrong**) | 13 | 12 | 11 | 3 | 1 | 1 | 1 |
| Driver's license | 23 / 1 | 23 (**1 wrong**) | 13 | 9 | 2 | 0 | 0 | 0 | 0 |
| Signature | 93 / 0 | 93 | 74 | 74 | 74 | 74 | 0 | 0 | 0 |
| Face photo | 10 / 0 | 10 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

**From 0.80 up, every kind that has findings is 100% right** (no finding of ITIN, EIN, passport, CVV or PIN in the samples).

| Threshold | Cases that skip Review (of 26) | without any locked item | skipped cases with a missed item |
|---|---|---|---|
| 0.75 | 21 | 10 | **4** |
| **0.80 (the product's default today)** | **10** | 3 | 0 |
| 0.85 | 8 | 3 | 0 |
| 0.90 | 4 | 3 | 0 |
| 0.95 | 3 | 2 | 0 |
| 0.97 to 0.99 | 1 | 1 | 0 |

## Why step 2 was not built
1. **It exists.** With review mode "uncertain only" (the default), a located finding at or above the entity's automatic redaction threshold (0.50 to 0.99, 0.80 by default) is confirmed without a person, and a case in which every finding is skips Review. Person name never is. The second check already sends its pages to a person. The measured 10 cases at 0.80 are exactly the 10 the product did not send to Review.
2. **A higher threshold only sends more to Review**, and the task's exclusions send 7 of the 10 back. Nothing would be cut.
3. **What fills Review is one fixed number.** 16 cases go to Review, all because of findings only the vision model made: the model states no confidence, so each gets 0.75 (0064), below the default on purpose. Of 86 such findings 6 are wrong, all bank account and routing numbers.
4. Excluding locked items from the existing rule would change today's behaviour (frozen) and needs a decision (question 2).

## What could cut reviews (not built)
Vision-only findings by kind: SSN, date of birth, credit score, card number, signature and face photo have no wrong one here; account and routing numbers do. But face photos apart, every case that goes to Review has its own reasons, and **lowering the bar to 0.75 lets 4 cases through that miss an item**. A person in Review sees the page and can add the missed item; without Review nobody does. That is the real price, and the finding's confidence says nothing about it.

## Limits
- **Synthetic samples, small numbers**: 0 wrong of 19 SSNs does not show that fewer than about 1 in 7 is wrong (95%). No customer documents.
- Confidence was not compared between models; another model's numbers mean something else.

## Commands run and results
| What | Result |
|---|---|
| `test-tools/vision-product`, three runs (0091's gate) | 119 of 130, 263 of 274 each |
| `node test-tools/confidence-review/measure.mjs` on each | the tables above, the same three times |
| `check-product-names.sh` | clean |

No product code changed, so no tests, migration or gate of its own.

## Questions for the owner
1. **Close step 2 as "exists"?** Recommended: yes.
2. **Locked items (card, CVV, PIN) are confirmed without a person today** when sure enough; the task's rule says never. Change it? Recommended: no. They are always removed in full, so a wrong one over-redacts and never leaks, and a review would add 7 of 26 cases here.
3. **A pass for vision-only findings of some kinds** (SSN, date of birth, signature...), off by default? Recommended: not now; first a measure of missed items on such pages with customer documents (0033 part B).
4. **Should the audit name the threshold for a case that skipped Review?** It already does, per document (`threshold=` in "Detection completed"). Recommended: enough.

No other product's name or behaviour in code.
