# Report 0092: a better reader for checks

Date: 2026-11-25
Branch: `feature/0092-checks`, from the integration branch (0091 merged; 0084 and 0085 merged in)
Outcome: **DONE for Find. The second check did not improve** (4 questions)

## Why checks were missed (measured first, five check pages, six lines)
| Page | What went wrong |
|---|---|
| public-16, public-26 p32 | the vision model sees the line but shifts the names: the account number is called routing, the check number account |
| public-21 (fax) | routing and account swapped |
| public-18 | the second check on the page is not seen |
| public-17 | right |

OCR with a digits-only setting finds the places of the line on most pages but **misreads its digits**: of six lines the routing number's checksum was right on two or three, whatever setting. So the line is found, not read.

## What was built
- **The line's shape** (`CheckLineFinder`): characters of one pitch and one height in the proportion of the MICR standard (E-13B: 8 an inch, 0.117 inch high), a few groups, close to standard size. A group of 9 to 11 places (the marks may be in it) followed by another is routing, the next the account. **Places only: no digit is read, so the checksum idea of the task is not used.** No new dependency, no migration, no prompt change.
- **Find:** on pages read by the vision model the line's two numbers stand for what the model said at those places; everything else the model said stays. They are findings like the model's (0.75, always to Review, removed in full), recorded as found by `check-line`. When the line search fails, the page goes to a person.
- **Second check:** a line still to be seen on the finished page sends it to a person.

## Before and after (the product, three runs each, the same numbers in all three; Ollama 0.40.18)
| | Before (0091) | After |
|---|---|---|
| **Check pages** | 9 of 15 (4 false flags) | **15 of 15** (2 false flags) |
| Fax pages | 27 of 29 (2) | **29 of 29** (0) |
| Routed pages | 119 of 130 | **127 of 130** |
| All pages | 263 of 274 | **271 of 274** |
| False flags | 8 | **4** |
| V1 misses | 1 | 1 |
| Unread pages | 0 | 0 |

- The line search adds about 50 ms a page. The vision page times (8.9, 4.7 and 4.7 s on average) vary with the model, not with this.
- `score.mjs` now prints 11 or 12 "vision findings without model and digest": these are the line's own findings, which no model made.

**Second check** (0083's three sets, real models): 21, 19 and 18 of 23 pages, 1, 1 and 0 others: **exactly as in 0083**. Its samples leave one number of a line visible and cover the other, so no line is left to find. It helps only where a whole line was missed.

## How far this can be trusted
- **The benchmark's checks are drawn with the font "OCR A"**, which has nearly the standard's size. No real E-13B print was available; the shape rules follow the standard, not that font. The limits were set on the benchmark's six lines.
- **Held out**: 90 newly drawn pages (two layouts, three fonts, 80 to 105% size, up to 1.2° tilt, noise): **84 of 90 (93%)** with both numbers right. One limit (the gap between the groups) was set after a first such set gave 61 of 90; the 84 is from a second set with another seed and one other font.
- **Lookalikes**: of 30 pages written to look like a check line (monospaced, "Ref 123456789   12345678"), **15 gave a false line**. Only on routed pages, only to Review. On the benchmark's 108 pages: none.
- Not found: a line whose groups touch, a check photographed at an angle or under 75% size, or one with its numbers half covered.

## Commands run and results
| What | Result |
|---|---|
| Model change gate, three runs | table above |
| `SecondCheckSamplesTest` with the real models, three sets | as 0083 |
| `mvn -B -o -fae -Pdev-build clean test`, throwaway PostgreSQL | on the merged code (with another queue's delivery change, which came in after the gate runs and touches no detection): 87 + 380 + 144 + 197 + 952, 0 failed, 7 skipped |
| No-redacted-value check | the finder returns places only; `SecondCheckRedactionTest` searches its audit records and logs; no new log line |
| `check-product-names.sh` | clean |

The first start of the gate did not run: the build of the jar failed (a test's policy) and the runs found no jar. Nothing was measured by it.

## Tests changed
`VisionFindingsTest` and `SecondCheckRedactionTest` got cases. New: `CheckLineFinderTest` (3), `CheckLineItemsTest`.

## Questions for the owner
1. **New baseline: 127 of 130, 271 of 274, at most 4 false flags**? Recommended: yes (proposed in `test-strategy.md`).
2. **Real checks** (or a page printed with a real MICR font) for the samples before this is relied on? Recommended: yes.
3. **The second check for half-covered lines** (a number beside a black box)? Recommended: not now; with Find at 15 of 15 the need is smaller.
4. **Lookalikes cost a click in Review.** Recommended: accept; tighten when a customer's documents show them.

No other product's name or behaviour in code.
