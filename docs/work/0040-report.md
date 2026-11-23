# Report 0040: Vision benchmark, round 2 (no product code)

Date: 2026-11-22
Branch: `feature/0040-vision-benchmark-round2`
Outcome: **DONE, except step 1** (removing two models), which the session's permission check blocked. See "Not done".

**Independence rule:** this task adds no other product's name or behaviour to code. The harness changes are inside `tools/vision-benchmark/` only. Nothing in `backend/` or `frontend/` changed.

## In short

- **No non-Chinese model can be used for redaction today.** Three were tested: Google Gemma 3 12B, IBM Granite 3.2 Vision 2B and Mistral Ministral 3 8B. Each found at most 5 of the 130 items on the routed pages, against 115 for `qwen3-vl:8b-instruct`.
  - Gemma and Ministral do see the items. Ministral names the right types on the right page for 120 of 130 items.
  - But their boxes are in the wrong place: the median box centre is about 22% of the page away from the item. A blacked-out box in the wrong place is useless, so they fail.
  - Granite does not read the pages.
- **Prompt v2 helps `qwen3-vl:8b-instruct`:**
  - found on routed pages: 122 of 130 (v1: 115);
  - check-line misses: 6 (v1: 12, counting the check on a fax);
  - the ID "ghost" photo is now found.
  - The cost is 6 false flags (v1: 0), all on check and fax pages.
- **Requests at once:** with `OLLAMA_NUM_PARALLEL=2`, two vision requests at once give about 30% more pages per minute (qwen3-vl: 21.4 against 16.4). Each model then holds about 1.1 GB more GPU memory.
- **Text and vision models loaded together:** both fit (14.4 GB of 16.3 GB at the default setting, 15.5 GB with parallel 2). Running them at the same time saves only about 9% against running one after the other, because they share the one GPU. **This changes the P2 assumption from round 1** (see "60-page estimate").
- **Ollama was set back as it was and checked** (see step 4).

## 1. Clean-up

**Not done.** The command `ollama rm llama3.2-vision:11b` (then `qwen3-vl:8b`) was refused by the session's permission check (classed as a destructive action). I did not try another way. Both models are still installed (7.8 GB + 6.2 GB). The owner can run the two commands, or allow them, and they take seconds.

## 2. Non-Chinese vision models

### Model records

Ollama 0.40.17, RTX 5080 Laptop GPU (16 303 MiB). Same samples (part A, 26 documents, 108 pages, 274 items), same settings as round 1 (1400 px, temperature 0, warm-up, 27 routed pages), prompt v1.

| Model | Origin | Digest | Size | Quant | Licence | Commercial use | Runs here |
|---|---|---|---|---|---|---|---|
| `gemma3:12b` | Google LLC, USA | `b92b218582c521ba287f38f1d6057dcd3c6cbff036f13cac97b5d861f5e58ec3` | 8.2 GB | Q4_K_M | Gemma Terms of Use | Allowed, with conditions: the Gemma Prohibited Use Policy applies and must be passed on to users; redistribution needs a notice file; Google may restrict use it believes breaks the terms; "reasonable efforts" to use the latest version | yes |
| `granite3.2-vision:2b` | IBM, USA | `3be41a661804ad72cd08269816c5a145f1df6479ad07e2b3a7e29dba575d2669` | 2.4 GB | Q4_K_M | Apache 2.0 | Allowed | yes |
| `ministral-3:8b` | Mistral AI, France | `1922accd5827ebe6829e536369195db25eaf664528dc66206d646ea3bb386b71` | 6.0 GB | Q4_K_M | Apache 2.0 | Allowed | yes |
| Mistral Small 3.1 (24B) | Mistral AI, France | not downloaded | about 15 GB | Q4_K_M | Apache 2.0 | Allowed | **not tried**: does not fit in 16 GB GPU memory with its context; it would run partly on the CPU |

- Licence text was read from the copy inside each model (`ollama show --license`). The Gemma terms point to a separate prohibited-use policy online, which I did not fetch.
- **Box format.** None of these three models has a documented box format. To be fair, each was run on the whole set twice: in image pixels (the harness default) and in the other likely format (Gemma and Ministral 0–1000, Granite 0–1). The tables use the better of the two runs. Both are listed below.
- Downloaded: 16.6 GB in all. No model was removed.

### Found, missed, false flags: vision on the 27 routed pages (130 items)

| Model (box format) | Found | Misses | False flags | Handwritten found (of 57) | Unreadable answers | s/page mean | median | 90th pct |
|---|---|---|---|---|---|---|---|---|
| `qwen3-vl:8b-instruct` (round 1, for comparison) | **115** | 15 | 0 | 57 | 0 | 2.93 | 2.58 | 4.30 |
| `gemma3:12b` (0–1000) | 5 | 125 | 100 | 1 | 4 | 3.65 | 3.59 | 5.13 |
| `gemma3:12b` (pixels) | 2 | 128 | 108 | 0 | 4 | 3.78 | 3.59 | 5.19 |
| `granite3.2-vision:2b` (0–1) | 4 | 126 | 106 | 2 | 13 | 3.09 | 3.08 | 4.27 |
| `granite3.2-vision:2b` (pixels) | 1 | 129 | 57 | 0 | 16 | 2.99 | 2.94 | 3.25 |
| `ministral-3:8b` (pixels) | 5 | 125 | 118 | 0 | 1 | 3.57 | 3.59 | 5.27 |
| `ministral-3:8b` (0–1000) | 4 | 126 | 117 | 1 | 2 | 3.28 | 3.66 | 4.34 |

Misses by page kind (found of expected), better run of each model:

| Page kind | Items | qwen3-vl (round 1) | gemma3 | granite3.2-vision | ministral-3 |
|---|---|---|---|---|---|
| CHECK | 15 | 5 | 1 | 0 | 0 |
| FAX | 29 | 27 | 0 | 0 | 0 |
| HANDWRITTEN | 37 | 37 | 0 | 0 | 0 |
| ID | 25 | 24 | 3 | 4 | 4 |
| TYPED | 24 | 22 | 1 | 0 | 1 |

### Why they fail: types right, boxes wrong

A box-free check (scratch script, numbers only): for each routed page, how many answer-file items had their type named by the model on that page, ignoring the box.

| Model | Items whose type was named on the right page (of 130) | Predictions | Box centre error after the best straight-line fit |
|---|---|---|---|
| `qwen3-vl:8b-instruct` (round 1) | 115 | 115 | 0.3% of the page |
| `ministral-3:8b` (pixels) | 120 | 123 | 9–15% |
| `gemma3:12b` (0–1000) | 93 | 105 | 11–12% |
| `granite3.2-vision:2b` (0–1) | 45 | 110 | — (lists every type in a grid; does not read the page) |

- **Ministral** sees almost everything: types and counts are right. Its pixel boxes shrink toward the top left by about 0.73, which is about what you get if it sees the page at 1024 px instead of 1400 (1024/1400 = 0.73). Undoing that scale does not help: the median box centre is still 22% of the page away. The box error is not systematic, so a different box format cannot fix it.
- **Gemma 3** names the right types on about 70% of items. Its boxes barely follow the items.
- **Granite 3.2 Vision** often gives the same list of all nine types with evenly spaced boxes, whatever the page shows.
- Either way, none of them can locate an item, and a redaction box must cover the item.

### Text path + vision + signature detector (all 274 items, threshold 0.75)

| With | Found | Misses | False flags |
|---|---|---|---|
| Text path alone | 71 | 203 | 0 |
| + `qwen3-vl:8b-instruct`, prompt v1 (round 1) | 236 | 38 | 0 |
| + `qwen3-vl:8b-instruct`, **prompt v2** | **243** | **31** | 6 |
| + `gemma3:12b` | 149 | 125 | 100 |
| + `granite3.2-vision:2b` | 149 | 125 | 106 |
| + `ministral-3:8b` | 150 | 124 | 118 |

The non-Chinese rows add almost nothing over the text path and the signature detector (which already finds 74 signatures on the fast pages).

### GPU memory and requests at once (default setting, prompt v1, from the benchmark runs)

| Model | Model VRAM MiB | 1 at once | 2 at once | 4 at once |
|---|---|---|---|---|
| `gemma3:12b` | 7 977 | 3.9 s, 15.0 pages/min | 6.3 s, 13.7 | 9.2 s, 15.3 |
| `granite3.2-vision:2b` | 2 289 | 2.1 s, 27.1 | 3.2 s, 27.1 | 6.7 s, 22.5 |
| `ministral-3:8b` | 6 061 | 2.9 s, 20.2 | 4.6 s, 18.6 | 8.7 s, 17.1 |

### 60-page package estimate

The text path on the package became slower during the session: 48.0 s in round 1, 48.0 to 56.3 s across today's runs, with the same code and model. The machine slowed down (most likely heat). To compare models fairly, the table adds each model's measured vision seconds on the package's 5 routed pages to round 1's text path and routing checks (51.0 s).

| With | Vision on the 5 package pages | Estimate (round 1 text + checks) | Measured in that run |
|---|---|---|---|
| `qwen3-vl:8b-instruct`, v1 (round 1) | 14.9 s | 65.8 s | 65.8 s |
| `qwen3-vl:8b-instruct`, **v2** | 16.1 s | 67.1 s | 75.3 s |
| `gemma3:12b` | 18.6 s | 69.6 s | 70.7 s |
| `granite3.2-vision:2b` | 17.2 s | 68.2 s | 69.2 s |
| `ministral-3:8b` | 16.8 s | 67.8 s | 72.1 s |

## 3. Prompt v2

`tools/vision-benchmark/src/main/resources/vision-prompt-v2.txt`, sha256 `dffdfc6fff2ecc967bcc08011aa5ddd7db3ac99fc9fbbc8e25f2bb5a77021337`. v1 is unchanged (sha256 `d4814d881ad9fe7e5a61dc4b465082c357523e64671b44893975287b4f7176c9`), and its results are kept.

- **What changed:**
  - a first line saying that a miss is worse than an extra box;
  - a "look carefully" section: the line of special digits at the bottom of a check holds the routing number, then the account number, each to be boxed on its own; ID cards can carry a second, small or faint face photo; on poor scans and faxes, a number next to "Account" or "Routing" is still that item even if some digits are hard to read.
- **Same output contract:** the same nine types, "type and box only, never a value", the same JSON form. A test checks this.
- Results: `C:\dev\benchmark-samples\results\public-20261122-211458`.

### `qwen3-vl:8b-instruct`, v1 against v2 (27 routed pages, 130 items)

| | v1 (round 1) | v2 |
|---|---|---|
| Found | 115 | **122** |
| Misses | 15 | **8** |
| False flags | 0 | 6 |
| Handwritten found (of 57) | 57 | 57 |
| Unreadable answers | 0 | 0 |
| Seconds per page, mean / 90th pct | 2.93 / 4.30 | 3.14 / 4.34 |

Misses by page kind:

| Page kind | Items | v1 misses | v2 misses | v2 false flags |
|---|---|---|---|---|
| CHECK | 15 | 10 | **6** | 4 |
| FAX | 29 | 2 | 2 | 2 |
| ID | 25 | 1 | **0** | 0 |
| TYPED | 24 | 2 | **0** | 0 |
| HANDWRITTEN | 37 | 0 | 0 | 0 |

- **Check line:** the routing and account numbers in the line of digits, on the 4 checks plus the check on a fax, were missed 12 times with v1 and 8 times with v2 (routing 5, account 3). The 6 false flags are boxes on checks and faxes that do not overlap an item, likely the check line boxed in the wrong place or under the wrong type. It is better, but not solved. The R1 check-line patterns with checksums are still needed.
- **Small ID photo:** found (10 of 10 face photos).
- **Account numbers on poor scans:** both found (TYPED misses 2 → 0).
- **Ministral 3 with v2:** worse, 3 found and 130 false flags. Nothing changes for the non-Chinese models.
- **Combined (all 274 items):** 243 found, 31 misses (round 1: 38). The 31 are 23 bank account, routing and ID numbers on typed fast-path pages (no text patterns until R1), plus the 8 check-line items.

## 4. Requests at once

### How Ollama was changed and restored

- **Before:**
  - the Ollama tray app (`ollama app.exe`) had started `ollama.exe serve`;
  - no `OLLAMA_*` variable was set for the user or the machine;
  - the server log showed `OLLAMA_NUM_PARALLEL:0` (not set: the server decides).
- **Changed:** I stopped the tray app and the server, set `OLLAMA_NUM_PARALLEL=2` in that one PowerShell process only, and started the tray app from it. The server log showed `OLLAMA_NUM_PARALLEL:2` and the CUDA GPU.
  - My first try started `ollama serve` directly, with a hidden window and redirected output. GPU discovery then timed out after 15 minutes, and the server fell back to the CPU (about 50 s per page). I noticed it from `size_vram 0`, stopped that probe, threw its numbers away, and used the tray-app method instead.
- **Restored:** I stopped the app and the server, then started the tray app from a fresh shell without the variable.
- **Checked after the restore:**
  - processes: `ollama app.exe` → `ollama.exe serve`, as before;
  - the server log shows `OLLAMA_NUM_PARALLEL:0` and the CUDA GPU (RTX 5080);
  - no `OLLAMA_NUM_PARALLEL` for the user or the machine;
  - `/api/version` answers 0.40.17;
  - a probe then ran normally on the GPU.

### Vision requests at once (probe, first 8 pages marked VISION, prompt v1)

Both probes ran the same day, one after the other, so the machine state is comparable.

| Model | Setting | Model VRAM MiB | GPU peak MiB | 1 at once | 2 at once | 4 at once |
|---|---|---|---|---|---|---|
| `qwen3-vl:8b-instruct` | default | 6 331 | 7 962 | 4.2 s, 14.0 pages/min | 5.7 s, 16.4 | 8.6 s, 16.1 |
| `qwen3-vl:8b-instruct` | **parallel 2** | 7 483 | 9 114 | 4.3 s, 13.7 | **5.0 s, 21.4** | 7.6 s, 20.6 |
| `ministral-3:8b` | default | 6 061 | 7 342 | 3.6 s, 16.4 | 4.8 s, 19.7 | 8.6 s, 17.5 |
| `ministral-3:8b` | **parallel 2** | 7 149 | 8 430 | 3.7 s, 16.1 | **3.8 s, 27.4** | 6.3 s, 25.5 |

- **Most requests at once that stayed fast: 2, with parallel 2.** Two requests take about as long as one, and pages per minute rise by about 30% (qwen3-vl) to 40% (ministral-3). With 4 at once, the extra requests queue and throughput stays flat.
- **Cost:** about 1.1 GB more GPU memory per loaded model (a second context slot).
- Cells show the mean time per request and pages per minute (two rounds per level, each image slightly marked so Ollama's cache cannot answer it).

### Text model and vision model loaded together (`qwen3.5:9b` + `qwen3-vl:8b-instruct`)

One text request is the product's text path on one typed page with an SSN or date of birth. One vision request is one routed page. Three rounds per setting.

| Setting | GPU memory with both loaded | Text alone | Vision alone | Both at once: text / vision / elapsed | Saving against one after the other |
|---|---|---|---|---|---|
| default | 14 385 MiB (5 346 + 6 331) | 3.53 s | 4.38 s | 6.47 / 6.68 / 7.17 s | 9% (7.17 against 7.91 s) |
| parallel 2 | 15 547 MiB (5 632 + 7 307) | 3.41 s | 4.27 s | 6.28 / 6.47 / 6.97 s | 9% (6.97 against 7.68 s) |

- **Both models stay loaded together** (neither was unloaded). With parallel 2 the GPU is close to full: 15.5 of 16.3 GB.
- **Running them at the same time barely helps.** In 2 of 3 rounds each request took about twice as long as alone, so the total was the same as one after the other. In the third round they overlapped well (5.4 s against 7.7 s). They share one GPU, and it is the limit.
- **For P2:**
  - Round 1 estimated about 52 s for a 60-page package if vision runs during the text path, taking the larger of the two times. That is too optimistic: expect close to the sum of the two (about 66 s), minus about 10%, so roughly 60 s.
  - The 60-second target stays borderline on this laptop GPU.
  - What would help more: fewer pages wrongly routed to vision (3 of 16 in round 1), and a faster text step.

## Side-by-side summary (part A, 27 routed pages, 130 items; combined = all 274 items)

| Model | Origin | Licence | Found (routed) | False flags (routed) | Combined misses | s/page mean | 90th pct | Model VRAM MiB | Best at once | 60-page est. |
|---|---|---|---|---|---|---|---|---|---|---|
| `qwen3-vl:8b-instruct` + **v2** | Alibaba, China | Apache 2.0 | **122** | 6 | **31** | 3.14 | 4.34 | 6 331 | 2 (parallel 2) | 67.1 s |
| `qwen3-vl:8b-instruct` v1 | Alibaba, China | Apache 2.0 | 115 | 0 | 38 | 2.93 | 4.30 | 6 331 | 2 (parallel 2) | 65.8 s |
| `qwen2.5vl:7b` (round 1) | Alibaba, China | Apache 2.0 | 36 | 8 | 103 | 2.00 | 2.73 | 5 479 | 1 | 62.7 s |
| `minicpm-v:8b` (round 1) | OpenBMB, China | MiniCPM licence (registration) | 4 | 38 | 126 | 1.63 | 2.74 | 4 722 | 1 | 59.1 s |
| `ministral-3:8b` | Mistral AI, France | Apache 2.0 | 5 | 118 | 124 | 3.57 | 5.27 | 6 061 | 2 (parallel 2) | 67.8 s |
| `gemma3:12b` | Google, USA | Gemma Terms (conditions) | 5 | 100 | 125 | 3.65 | 5.13 | 7 977 | 1 | 69.6 s |
| `granite3.2-vision:2b` | IBM, USA | Apache 2.0 | 4 | 106 | 125 | 3.09 | 4.27 | 2 289 | 1 | 68.2 s |

## Recommendation

- **Best overall: `qwen3-vl:8b-instruct` with prompt v2** (digest `70e58e37…6eb`, Apache 2.0). It finds 7 more items than v1 at the cost of 6 false flags (0.22 per routed page, above the proposed mark of 0.1 per page), so it is not a clear win on every measure.
  - **Recommended: adopt v2**, because a miss is worse than an extra box.
  - **Then:** a prompt v3 that tells the model to box the check line only once per number, to cut the false flags.
  - **Either way:** keep the R1 check-line patterns with checksums; v2 still misses 8 check-line items.
- **Best non-Chinese: `ministral-3:8b`** (Mistral AI, France, Apache 2.0, no conditions). It sees and classifies best (120 of 130 types right on the page), but **it cannot place a usable box today.** So there is **no usable non-Chinese option** for redaction at this size on this GPU.
  - What it could still do: check whole pages, as a second opinion on whether a page holds sensitive types. It cannot place the redaction.
  - Gemma 3 is weaker and its terms add conditions. Granite 3.2 Vision does not read the pages.
- **For the vendor-risk review:** say plainly that the only model that passes is of Chinese origin (Apache 2.0, runs fully offline, no data leaves the machine). The non-Chinese models tested fail on box placement, not on licence.
- **Ollama setting:** `OLLAMA_NUM_PARALLEL=2` for the vision model in P2 (about 30% more pages per minute, about 1.1 GB more GPU memory). It is not set on this machine now; it was set back as agreed.
- **P2:** plan on the text and vision steps adding up, not overlapping (about 9% saving measured).

**How far to trust these numbers:** same limits as round 1. The samples are synthetic and the handwriting is drawn with fonts. The machine slowed during the session (text path up to 17% slower). Prompt v2 was written after seeing round 1's misses on this same set, so its gain may be smaller on part B.

## Commands run and results

| Command | Result |
|---|---|
| `ollama rm llama3.2-vision:11b` / `ollama rm qwen3-vl:8b` | **refused by the session's permission check; not run** |
| `ollama pull gemma3:12b`, `granite3.2-vision:2b`, `ministral-3:8b` | downloaded |
| `mvn -o clean package` (in `tools/vision-benchmark`) | BUILD SUCCESS, 30 tests, 0 failures (run twice: after the code change, and before the v2 run) |
| `BenchmarkMain run … --skip-text --only public-09,public-13,public-16,public-20 --models gemma3:12b,granite3.2-vision:2b` (with and without `--boxes`) | 2 trial runs on 4 pages; too small to pick a box format, so the full set was run both ways |
| `BenchmarkMain run --root C:\dev\benchmark-samples --set public --models gemma3:12b,granite3.2-vision:2b` | exit 0, `public-20261122-203116` (pixels) |
| same with `--boxes gemma3:12b=PER_MILLE,granite3.2-vision:2b=UNIT` | exit 0, `public-20261122-204251` |
| `… --models ministral-3:8b` and with `--boxes ministral-3:8b=PER_MILLE` | exit 0, `public-20261122-205749`, `public-20261122-210546` |
| `… --models qwen3-vl:8b-instruct,ministral-3:8b --prompt v2` | exit 0, `public-20261122-211458` |
| Ollama restarted with `OLLAMA_NUM_PARALLEL=2` (tray app) | server log shows parallel 2 and the CUDA GPU |
| `BenchmarkMain probe … --with-text qwen3.5:9b --label "OLLAMA_NUM_PARALLEL=2 …"` | exit 0, `probe-20261122-215830` (a first attempt on a CPU-only server was stopped and discarded: `probe-20261122-214739`) |
| Ollama restored (tray app, no variable) | checked as described in step 4 |
| `BenchmarkMain probe … --label "default …"` | exit 0, `probe-20261122-220637` |
| `node typeonly.js`, `node boxfit.js`, `node scaled.js` (scratchpad, not committed) | box-free type counts and box-fit numbers above |

In every text-path run, the poor scan `public-23` stopped with an `IllegalStateException`, as in round 1. Results are in `C:\dev\benchmark-samples\results\` and hold ids, types, boxes, counts and seconds only.

## Tests

Harness only (the product is unchanged).

| Test class | Tests |
|---|---|
| `VisionClientTest` | 9 (3 new: prompt v2 keeps the output contract and has its own hash; unknown prompt versions are refused; 0–1 boxes and per-model box overrides) |
| `OptionsTest` (new) | 1 (`--boxes` parsing, bad values refused) |
| `ScorerTest` | 8 |
| `RoutingTest` | 6 |
| `SamplesAndTruthTest` | 4 |
| `FakeDataTest` | 2 |
| **Total** | **30, 0 failures** |

## Files changed

- `tools/vision-benchmark/src/main/resources/vision-prompt-v2.txt` (new).
- `tools/vision-benchmark/src/main/java/com/securedoc/benchmark/vision/VisionClient.java`: prompt version per client (v1 stays the default), a 0–1 box format, box format per model.
- `tools/vision-benchmark/src/main/java/com/securedoc/benchmark/BenchmarkMain.java`: `--prompt`, `--boxes`, and the `probe` command.
- `tools/vision-benchmark/src/main/java/com/securedoc/benchmark/Probe.java` (new): requests at once, and text + vision loaded together.
- `tools/vision-benchmark/src/main/java/com/securedoc/benchmark/Results.java`, `Summary.java`: record and print the box format per model.
- `tools/vision-benchmark/src/test/java/…/VisionClientTest.java`, `OptionsTest.java` (new).
- `tools/vision-benchmark/README.md`, `Run-VisionBenchmark.ps1` (`-Prompt`, `-Boxes`).
- `docs/work/0040-report.md` (this report).
- Nothing in `backend/` or `frontend/`. No new dependency.

## Commits

- `b554183` feat: task 0040, vision benchmark round 2
- a follow-up commit adding this hash to the report.

Not merged, not pushed.

## Not done, and why

- **Step 1, removing `llama3.2-vision:11b` and `qwen3-vl:8b`:** the session's permission check refused `ollama rm`. Both are still installed.
- **Mistral Small 3.1 (24B):** not downloaded; too large for this GPU to run fully on it.
- **Neighbour-page text as context for vision:** still not tried.

## Questions for the owner

1. **Clean-up:** run `ollama rm llama3.2-vision:11b` and `ollama rm qwen3-vl:8b` yourself, or allow the command for a next session? Also: may I remove the three non-Chinese models (16.6 GB), or keep them for part B?
2. **Prompt v2:** adopt it for `qwen3-vl:8b-instruct`? It finds 7 more items and adds 6 false flags (0.22 per routed page, against the proposed mark of 0.1).
3. **Non-Chinese option:** none passes. Should the vendor-risk material state that plainly, or should the next round try a larger non-Chinese model on bigger hardware (for example Mistral Small 3.1 24B on a 24 GB or larger GPU)?
4. **P2 timing:** the text and vision steps do not overlap well on one GPU (9% saving). Keep the 60 s target as a goal for a stronger GPU, or set the laptop target at about 65 s?
5. **`OLLAMA_NUM_PARALLEL=2`:** set it in the local start script for the vision model when P2 lands (a product change, later task)?

## Suggested next step

The R1 check-line patterns with checksums, then re-run this benchmark with prompt v2 (and a v3 that boxes the check line once) to see whether the check-line misses and false flags both reach zero.
