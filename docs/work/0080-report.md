# Report 0080: why the vision benchmark fell after 0064; the model list asked once more

Date: 2026-11-24
Branch: `feature/0080-benchmark-drop`, from `c9d17fa` (0073 merged)
Outcome: **DONE** (3 questions)

## The answer
**No change in LexaVault caused it.** The exact 0064 commit, with the same models (same digests) and samples, now scores the same as the newest build. What changed is the AI server: **Ollama updated itself from 0.40.17 to 0.40.18** when the laptop was restarted on 2026-11-24 (its logs: 0.40.17 listening at 16:21, the installer started, 0.40.18 at 16:22). 0064's benchmark ran at 11:20 that day, on 0.40.17; every lower run is after 16:22. I did not run 0.40.17 again, so the update is the most likely cause, not a proven one.

| Run (names off, quiet queue, alternating) | Routed pages, of 130 | All, of 274 | V1 misses, of 97 | False flags | Unread |
|---|---|---|---|---|---|
| 0064 (2026-11-24, 11:20, Ollama 0.40.17) | **122** | 264 | 2 | 7 | 0 |
| `a28ba5e`, 1 | **119** | 263 | 1 | 8 | 0 |
| `a28ba5e`, 2 | **119** | 263 | 1 | 8 | 0 |
| `a28ba5e`, 3 | **119** | 263 | 1 | 8 | 0 |
| `cc31779` (newest then), 1 | **119** | 262 | 2 | 8 | 0 |
| `cc31779`, 2 | **119** | 263 | 1 | 8 | 0 |
| `cc31779`, 3 | **119** | 263 | 1 | 8 | 0 |

- All six runs miss the **same 11 items on routed pages**: the 8 that 0064 missed and three more, a card number on a poor scan (public-23) and one check's account and routing number (public-26, page 32).
- **118** in 0072 was this 119 less one page that went unread (a signature).
- Variation between runs: none on routed pages; one item on typed pages (text model).
- The newest build was the faster one on the 60-page package in each pair (46 to 51 s against 55 to 64 s). Other work ran on the laptop during part of this, so this is not a performance figure.
- The three items are not in the code's hands: the same model answers differently. Nothing was changed for them.

## What was done
1. **Gate rule** (`test-strategy.md`, section 5): compare against the lowest of three baseline runs; **record the AI server's version, and a change of it is a model change**. Baseline now: 119 of 130 and 262 of 274 (Ollama 0.40.18). Names' pass mark written down.
2. **The model list is asked once more** when it is late (`OllamaModelDigests`); late twice still means a page not read (`LV-AI-002`). The limit is 5 s; my 0072 report said 10 s, which was wrong.
3. **The benchmark tool builds again:** Jackson 2 in its own pom (approved; tool only). Its tests then showed a mistake of 0072: Person name had gone into the list of kinds a *vision* answer may hold. Fixed; its 31 tests pass unchanged. The product was not affected.

## Commands run and results
| What | Result |
|---|---|
| `Invoke-VisionProductBenchmark.ps1`, 3 runs on each commit | table above |
| `mvn -B -o -Pdev-build clean test -Dtest='OllamaVision*Test,Ollama*Test,TenantAi*Test'` | 39, 0 failed (new: late once, late twice) |
| `cd tools/vision-benchmark; mvn -B -o clean package` | 31, 0 failed |
| No-redacted-value check: the samples' 151 fake values searched in the six runs' logs and results | 54 files, 0 with a value |

The first start of the six runs failed at once (I had copied only one of two jars); nothing was measured by it.

## Not run
The frontend (unchanged), the full backend set (CI runs it), names on.

## Questions for the owner
1. **Accept 119 as the new baseline?** Recommended: yes, with Ollama 0.40.18 named beside it.
2. **Keep Ollama from updating itself** on machines that run the approved model, and have LexaVault record the AI server's version beside model and digest (BL7), so that a silent update is seen. Recommended: yes, as a small task; for this laptop, switch off the automatic update in Ollama's settings.
3. **Prove the cause** by running 0.40.17 once from a separate folder. Recommended: no; the gate now covers it.

No other product's name or behaviour in code.
