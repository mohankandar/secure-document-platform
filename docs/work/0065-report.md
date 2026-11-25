# Report 0065: P2b, document summary and case instructions

Date: 2026-11-25
Branch: `feature/0065-summary-instructions-2`, from `1cb33b5`, with the integration branch (`466906b`, then 0094 `05d7eb1`) merged in. The first run on 2026-11-24 stopped before any change; its four questions were decided.
Outcome: **DONE** (7 questions)

## It is built differently from the design note, because the note's way does not work
The design gives the summary to the AI with every batch. Measured (synthetic 300-page filing, `qwen3.5:9b`, Ollama 0.40.18): **64 of 138 names caught with it, 58 without, 8 false names instead of 0.** On pages with capitalised ordinary words, five ways of wording or placing it gave 92 to 177 false names on 80 pages.

What works, and was built:
1. One **first pass** reads the first 12,000 characters of a document (with the case's instructions) and lists its people and every short form used for them. A name that does not stand in that text is dropped by code.
2. On every page, **code finds** where one of those names stands. For each place the AI is asked what the word is **in that sentence**: person, ordinary word, place, season, other name. No usable answer counts as a person.
3. The names prompt and the prompts of all other kinds are **unchanged** (hash tests). Instructions never reach the model as commands to the search, so they can't switch off a finding.

## Measured
Prompts tuned on sets A and N, then frozen; B is held out (other people, sentences and traps). Synthetic, invented names. "Other" is every other name marked.

| 300 pages, the product's own code | Off | On |
|---|---|---|
| **Natural filing** (N): later-page names caught | 48 of 149 | **141 of 149** |
| other names on the same pages | 18 | 18 |
| time for the whole search (names only) | 85 s | 166 s (first pass 6.5 s) |
| **Hard filing** (B, held out; most pages hold a party's name as an ordinary word): caught | 87 of 163 | **159 of 163** |
| other names | 21 | **115** (3.8 per 10 pages) |
| time | 205 s | 513 s (first pass 5.8 s) |

- The pass mark for names (at least 85%, at most 1 false per 10 pages): natural 95% and 0.6; **hard 98% and 3.8, which misses the mark for false names** (by intent of the set). All go to Review.
- **Three public court opinions** (US federal courts, public domain; `C:\dev\benchmark-samples\court-public`, sources in its `SOURCES.md`, not in the repository). Nobody listed their names, so only counts: 237 pages, 467 → 645 page-and-name pairs; 20 pages, 39 → 41; 3 pages, 3 → 4. First pass 39 s, 14 s and 3 s. The 237-page times (1,472 s off, 1,202 s on) are not comparable: other work ran beside the first.
- These are opinions, not party filings; they say nothing about correctness.

## What was built
- **Setting per entity**, off by default (migration 085), "Find the people of a document on every page". It acts only with Person name on. FRCP 5.2 *suggests* it and never turns it on. In the policy audit record and the compliance history.
- **Summary**: staged documents share one, made once before the first batch, as an encrypted file of the document; it is deleted when the batches are gathered. A document searched as a whole stores nothing. Batches of different settings or instructions are never mixed. If the first pass fails, the document stops with its AI code.
- **Case instructions** (up to 2,000 characters): New case (upload), the case panel (reviewer, used at the next search), and `caseInstructions` in the push API. One encrypted file in the case's folder. Refused while the setting is off. A source sending its case again with other instructions before it is complete gets a conflict.
- **Deleted with the case**: `StoredFileErasure` erases the file, its wrapped key and the row; proven in the restore-and-replay drill, also when only the storage is restored.
- Names found go to Review as always (0072); both prompts and the model are recorded with them (BL7).

## Commands run and results
| What | Result |
|---|---|
| `mvn -B -o -fae -Pdev-build clean test`, throwaway PostgreSQL | before the merge: 87 + 378 + 144 + 158 + 954, 0 failed, 4 skipped; after it: 87 + 379 + 144 + 158 + 968, 0 failed, 5 skipped; after merging 0094 (platform-core 1.1.0): 87 + 379 + 144 + 158 + 969, 0 failed, 5 skipped (a first try failed on a taken database port) |
| `npm run test:standalone` (port base 15375) | before the merge: one failure, an accessibility test (Platform overview, light) that timed out waiting for the sign-in page while the GPU run was on; it passed when run again by itself (light and dark). After the merge: 10 + 238 + 19 + 232 + 105 passed, accessibility 374 passed and **4 failed**: 0089's keyboard walks of the whole Error code guide run over 60 s here. The same two in light (dark not tried) fail with the integration branch's own frontend, so it is not this task; they passed in CI on PR #33. They passed in CI on this task's PR #39 too (head `73ff8c3`) |
| Model change gate, one run (setting off) | 119 of 130, 262 of 274, 2 V1 misses, 8 false flags, 0 unread (Ollama 0.40.18): the baseline exactly |
| `DocumentContextTest#measured` with the real model | tables above |
| No-redacted-value check | `DocumentContextTest` searches logs, audit records and events for the names and instructions: none, with the stand-in and in the real-model runs; batch files hold places only; audit has the number of characters only |
| `check-product-names.sh` | clean |

## Not done, and limits
- **No run of the started app with the setting on.** The pieces are proven by the tests, the drill and the real-model run of the search code.
- **The gate was run once, not three times**; 0091 runs it three times next.
- The summary's *kind of document* and *matter* are asked for (the measured prompt) but not kept: nothing uses them.
- **"What to protect" acts on people's names only.** Other wishes in the instructions have no effect.
- A person first named after the first 12,000 characters is not known.
- Instructions were tested with a stand-in AI, not measured with the real model.
- Scan, watched folder and polling don't take instructions.
- The OpenAI-compatible adapter has both prompts; not run against such a server.
- Vision pages read no names (as before).

## Tests changed
Six roll-back counts (one more migration); the erase drill, `OllamaPageRetryTest`, `OllamaVisionModelAdapterTest`, `OptInKindsPolicySaveTest` and `ExternalIntakeServiceTest` got cases. New: `DocumentContextTest` (10, one only with the real model), one browser test.

## Found on the way
- 0089's two walks of the Error code guide need more than 60 s on this machine. Not changed (another queue's).
- `test-tools/vision-product` uses database port 55465; a throwaway container for task 0065 by the usual rule takes the same port. The first gate attempts failed on that and on a missing jar; neither reached the benchmark.

## Questions for the owner
1. **Accept this way instead of the design's** (known names asked place by place)? Recommended: yes.
2. **False names on hard documents** (3.8 per 10 pages) for 98% caught? Recommended: yes; a missed name leaks, a false one is a click in Review. Off by default.
3. **Read more than the first 12,000 characters** (for example the last pages, with signatures)? Recommended: later, measured.
4. **The document stops when the first pass fails.** Recommended: keep (as 0083, question 5).
5. **The summary is deleted when the search ends**, earlier than "with the case". Recommended: keep.
6. **Instructions from scan, folder and polling sources**: recommended later, when a customer asks.
7. **Compliance appendix:** the new audit action prints as "—". Recommended: leave, as 0083.

No other product's name or behaviour in code.
