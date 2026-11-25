# Report 0072: "Person name" finding kind

Date: 2026-11-24
Branch: `feature/0072-person-name`, from `bf6e2ad` (0067 merged)
Outcome: **DONE, with one gate question** (5 questions)

## What was done
1. **Catalog:** Person name, off by default, found by the AI only, removed in full. No migration (the catalog table takes any kind).
2. **Always a person:** a name is never confirmed automatically, whatever the policy and the confidence. The policy estimate counts it so.
3. **A request of its own.** Inside the existing prompt the real model returned no names at all (0 of 24, and 0 with two other wordings), so names are asked for separately, only where they are on: one more request per page batch. **The approved prompt and answer shape are unchanged for every entity** (pinned by a hash test). The names prompt is recorded as `text-name1`, with model and digest (BL7), and pinned too. Findings of a document with names on list both prompt versions.
4. **Every place on the page:** a name the model gives is found wherever it stands as whole words, in capitals or not. A name without a place goes to Review without a box. Digits or over 80 characters: not a name.
5. **Not merged away:** a value of another kind at a name's place stays its own finding (a person may dismiss the name).
6. **FRCP 5.2 pack:** names it as suggested; turning the pack on does not turn it on. Its "not covered" text says so.
7. **Benchmark:** 4 synthetic documents with 24 names and 4 pages of things that are not names, last in the generator; the older answer files compared (3) are identical. `-Names` in the product script.

## Commands run and results
| What | Result |
|---|---|
| `mvn -B -o -fae -Pdev-build clean test` with a throwaway PostgreSQL | 85 + 319 + 136 + 133 + 844, 0 failed, 1 skipped |
| `npm run test:standalone`, once | 10 + 215 + 19 + 189 + 95 + 168, 0 failed |
| Real model, 4 name documents (8 pages), twice | 21 of 24 names, 0 false names, all waiting for a person |

The no-redacted-value check for names is in `OptInPatternKindsDetectionTest` (findings, audit, events, logs).

## Model change gate
The names prompt was tuned on one hand-made page of the same style as the generated documents, so 21 of 24 is not an independent figure. There is no approved pass mark for names.

For the other kinds, on one build (30 documents with names on; then the affected documents off and on):

| | 0064 | Names on, full set |
|---|---|---|
| Routed pages, all paths, of 130 | 122 | **118** |
| All pages, all kinds, of 274 | 264 | 262 |
| V1 four kinds, misses of 97 | 2 | 1 |

- **Not caused by names:** documents 10, 11, 16, 18, 21, 23 gave the same score, misses and false flags with names off and on, twice. Documents 23 and 26 gave the same misses on the base without 0072 and on this build with names off: one card number on a poor scan and one check (2 items), which 0064 did not miss. I did not look for what changed there since 0064.
- **One page went unread in two of the runs with names on** (21 p1 in the full run, the fourth miss; 26 p32 in another), and in none with names off; repeating it gave none. In the second case the log shows why: `LV-AI-002` after 14 s, far under the 180 s of an answer, so it was the 10 s limit on reading the AI server's model list. The page went to a person, as designed. The frontend run was loading the machine during the full run. More AI requests may make it likelier.
- **Time, the 60-page package:** 50 and 55 s off; 74 s on (95 s in the run with the timeout).

**By the gate's own words (pass marks met) the full-set run is below 0064.** The cause is on the base, so names stay off by default and nothing changes for entities that do not turn them on; the decision is question 1.

## Not run, and limits
- Names on pages read by the vision model only are not found (its prompt has no names); nor on pages the OCR cannot read.
- A last name alone is found when the model gives it. Names across pages are 0065.
- The two-server run (claims unchanged); no real filings.

## Found, not fixed
- **`tools/vision-benchmark` no longer builds** since Spring Boot 4: it took Jackson 2 from the product. I compiled its generator alone against jars on this machine. Its tests were not run.

## Tests changed
`RedactionCatalogTest` (15 kinds), `OptInKindsPolicySaveTest` (15; the refused kind is now Address), `RedactionCatalogMigrationPostgresTest` and `OptInPatternKindsDetectionTest` (they took "asked of the AI" as the four old kinds), `SearchableRedactedPagesPolicyPostgresTest` (15 rows), `words.test.ts`, `adminSafePolicy.spec.ts`.

## Questions for the owner
1. **Gate:** is 118 of 130 (base, not names) acceptable until the two changed documents are looked at? Recommended: yes for this task, with a small task to find what changed since 0064.
2. **Pass mark for names.** Recommended: at least 85% found and no more than 1 false name per 10 pages, on documents the prompt was not tuned on.
3. **The 10 s model list limit.** Recommended: ask once more before a page counts as unread (small change in 0064's adapter).
4. **Benchmark harness:** it needs Jackson 2 named in its own pom (a dependency of the tool, not of the product). Recommended: yes.
5. **Time:** about 20 s more on 60 pages with names on. Recommended: accept; the entity chooses.

No other product's name or behaviour in code.
