# Report 0091: one fixed order in every answer schema

Date: 2026-11-25
Branch: `feature/0091-schema-order`, from 0065's branch (same file), opened as a PR after 0065 was merged
Outcome: **DONE** (2 questions)

## What changed
- The answer schemas of Find, the names search, the second check, the summary and known names are built once, in one fixed order on every level; the messages of a request too (`OllamaSensitiveContentAnalysisAdapter`). The order is the one the code always listed: page number, kind, text, context, confidence.
- Only the order changed. The second check's, the summary's and known names' property order is as measured before.
- The vision adapter's request and the OpenAI-compatible adapter are not changed (no schema is sent there).

## Does the run-to-run change disappear?
Three runs of the model change gate, setting by setting the same as before (Ollama 0.40.18, `qwen3.5:9b`, `qwen3-vl:8b-instruct`, names off):

| | Baseline (0080, 0083, 0065) | Run 1 | Run 2 | Run 3 |
|---|---|---|---|---|
| Routed pages | 119 of 130 | 119 | 119 | 119 |
| All pages | 262 of 274 | **263** | **263** | **263** |
| V1 misses | 2 | **1** | **1** | **1** |
| False flags | 8 | 8 | 8 | 8 |
| Unread pages | 0 | 0 | 0 | 0 |

- **The item is found in all three runs**: the typed credit score on public-26. It was the one missed in 0065's run today.
- The 276 findings of the three runs have the same pages, kinds, confidence, status and path.
- **Not the same: boxes.** Runs 2 and 3 agree; run 1 has 48 slightly other boxes, on documents with vision pages. The vision request is unchanged by this task, so this is the vision model's own (or the AI server's). It changed no score.
- Three runs are not proof that nothing can vary again. What is proven is that the request no longer does: the test has the schema's bytes written out and passed in two separate Java starts.

## Commands run and results
| What | Result |
|---|---|
| `OllamaAnswerSchemaTest` (two builds, byte for byte, and against written-out bytes) | pass, in two separate Maven runs |
| Gate, three runs | table above |
| `mvn -B -o -fae -Pdev-build clean test`, throwaway PostgreSQL | 87 + 379 + 144 + 158 + 970, 0 failed, 5 skipped |
| No-redacted-value check | no change of what is logged or stored; the existing tests pass |
| `check-product-names.sh` | clean |

One targeted run failed with a missing class file that this task does not touch (the editor's Java server writes into the build folder); the full set passed.

## Tests changed
New: `OllamaAnswerSchemaTest`. None changed.

## Questions for the owner
1. **New baseline 119 of 130 and 263 of 274, at most 1 V1 miss**? Recommended: yes (written into `test-strategy.md` with this task).
2. **The varying boxes of the vision model**: look into it? Recommended: no, unless a box ever misses a value.

No other product's name or behaviour in code.
