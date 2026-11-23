# Report 0033: Vision benchmark (no product code)

Date: 2026-11-22
Branch: `feature/0033-vision-benchmark` (from `feature/lexavault-r1b-tenant-identity`)
Outcome: **PARTLY DONE**. Part A (public, synthetic samples) is built, run and reported. Part B waits for the owner's approved customer samples; the `customer\` folder is empty, so only part A was run. Part B runs with one command and no code change.

No other product's name or behaviour in code.

## What was done

1. **Harness** under `tools/vision-benchmark/` (Java 21, stand-alone Maven project, not a backend module; nothing in `backend/` or `frontend/` changed). It:
   - generates the part A samples and their answer files (`generate`);
   - runs the routing checks, the text path, and 3 local vision models on the routed pages, then scores everything against the answer files (`run`);
   - rewrites a summary from saved results (`summarize`).
   - Entry point: `Run-VisionBenchmark.ps1 -Generate`, `-Set public`, `-Set customer`. How each thing is measured: `tools/vision-benchmark/README.md`.
2. **Text path = the product's code.** The harness runs the product's own `SensitiveDataDetectionService` with `HybridPdfTextExtractionAdapter`, `TesseractOcrAdapter`, `LocalRuleBasedDetectionAdapter` and `OllamaSensitiveContentAnalysisAdapter` at the product's default settings. Only the database and storage are in-memory stand-ins. One difference: OCR minimum page confidence is set to 0 so the benchmark can see what the text path finds on poor pages. The pages where the product would stop today are counted separately.
3. **Dependencies.** No new dependency in the product. The harness uses the product modules from the local Maven repository, JUnit 5.10.3 for its tests (the product's version), and the standard Maven plugins already in the local repository.
4. **Privacy.**
   - Samples and results are in `C:\dev\benchmark-samples` (`public\`, `customer\`, `results\`). The harness refuses a samples folder inside a git repository.
   - Customer samples are named `customer-01`… in every output, and file names never appear.
   - Results hold ids, pages, types, boxes, counts and seconds. The vision model's raw answer is parsed in memory (type and box only) and dropped. Only localhost Ollama is accepted.
   - This report holds numbers only.

## Sample set (part A)

26 documents, 108 pages, 274 sensitive items. All are synthetic and drawn by the harness from a fixed seed; no outside document or image was used. Licence: the owner's own generated test data. Source notes are in `C:\dev\benchmark-samples\public\SOURCES.md`.

| Page kind | Pages | How made |
|---|---|---|
| TYPED | 88 | Digital PDFs and clean scans: loan application, credit report, bank and card statements, disclosures, signature pages. Most pages carry initials. Also 2 poor scans (150 dpi, blurred, noisy), 1 scan whose hidden text layer does not match the image, and 1 page with a property photo |
| HANDWRITTEN | 6 | Forms filled in "by hand" (scanned and digital) |
| ID | 4 | Fake driver licence or ID cards (specimen, fictional issuer), scanned or pasted into a digital PDF |
| CHECK | 4 | Fake checks (specimen, fictional bank) with a routing and account line, scanned or pasted |
| FAX | 6 | Typed, hand-filled and check pages resampled to 204×98 dpi, black and white, with speckle and drop-outs |

- **The 60-page package:** document `public-26` is a 60-page loan package. 56 pages are typed, most of them initialled, and 3 are signature pages. It also has 1 hand-filled form, 1 ID copy, 1 check copy and 1 fax page.
- **Items:** 93 signatures and initials, 32 dates of birth, 27 SSNs, 26 bank accounts, 26 routing numbers, 22 ID numbers, 20 credit scores, 18 card numbers and 10 face photos.
- **Fake values:** names and streets are made up, phone numbers are 555-01xx, card numbers are Luhn-valid under the 4000 00 test prefix, routing numbers have a valid checksum, and SSNs are random but in a valid range.
- **Fonts:** Windows fonts (Arial, Courier New, Ink Free, Segoe Print, Lucida Handwriting, OCR A Extended) were used locally to draw the pages. They are not redistributed.

## Models (validation record)

Prompt: `vision-prompt-v1` (`src/main/resources/vision-prompt-v1.txt`, sha256 `d4814d881ad9fe7e5a61dc4b465082c357523e64671b44893975287b4f7176c9`). Ollama 0.40.17, RTX 5080 Laptop GPU (16 303 MiB).

| Role | Model | Digest (ollama) | Size | Quant | Licence | Run |
|---|---|---|---|---|---|---|
| text (current) | `qwen3.5:9b` | `56671c2ab9385f9cfcb404638e32cd62d88e3501d44822208363c010179a3c90` | 6.6 GB | Q4_K_M | Apache 2.0 | yes |
| vision | `qwen3-vl:8b-instruct` | `70e58e37beb15464e19aa903dedb2cba828b89c0fb186f75ff2738c7347b96eb` | 6.2 GB | Q4_K_M | Apache 2.0 | yes |
| vision | `qwen2.5vl:7b` | `5ced39dfa4bac325dc183dd1e4febaa1c46b3ea28bce48896c8e69c1e79611cc` | 6.0 GB | Q4_K_M | Apache 2.0 | yes |
| vision | `minicpm-v:8b` | `c92bfad0120556eda311984f1ac2f0d0a589b8d68c4053c13486b526276aa205` | 5.5 GB | Q4_0 | MiniCPM Model Community Licence 1.0: commercial use allowed **after a registration questionnaire** | yes |
| vision | `llama3.2-vision:11b` | `6f2f9757ae97` (ID) | 7.8 GB | — | Llama 3.2 Community Licence (multimodal rights excluded for EU-based users) | **could not run**: this Ollama has no runner for its `mllama` architecture |
| vision | `qwen3-vl:8b` | `9cf69da2b16d` (ID) | 6.2 GB | Q4_K_M | Apache 2.0 | dropped: a "thinking" build that kept thinking with thinking switched off and ran out of its answer budget. Replaced by the `-instruct` build |

- **Downloaded:** these 5 vision models, about 31.7 GB in all. No installed model was removed.
- **Settings for every vision call:**
  - pages sent at 1400 px on the long side, temperature 0, the same prompt for every model;
  - each model gets its trained box convention (qwen3-vl: 0–1000 scale; the others: pixels);
  - the first image per model is an untimed warm-up of about 50 s, because Ollama loads the vision encoder on first use;
  - no JSON-schema constraint on the answer, because in a trial it made qwen2.5vl answer "no items".
- **Model origin:** all the models that ran (and the text model) are of Chinese origin (Qwen by Alibaba, MiniCPM by OpenBMB). This matters for the vendor-risk check (requirement section 3, item 5).

## The numbers

Routing threshold for these tables: a page goes to vision when OCR confidence is below 0.75. Full tables, per-page misses and the per-document times are in `C:\dev\benchmark-samples\results\public-20261122-194400\summary.md`.

### Routing

| Expected (answer file) | → FAST | → FAST + signature | → VISION |
|---|---|---|---|
| FAST (16) | 13 | 0 | 3 |
| FAST + signature (68) | 0 | 68 | 0 |
| VISION (16) | 0 | 0 | **16** |
| not set: faxes, poor scans (8) | 0 | 0 | 8 |

- Every page that needs vision was routed to it, and every initialled page went to the fast path with the signature detector (signatures are not handwriting).
- 3 of 16 plain typed scans went to vision by mistake: one low-confidence OCR word was taken as handwriting.
- All faxes go to vision, mostly through the handwriting check (fax speckle).
- **60-page package:** 5 pages go to vision (handwritten form, ID, check, fax, and 1 false: a scanned statement). The requirement expected 2–3.
- **Signature detector:** found 74 of 74 signatures on the fast-path pages, with 0 false flags.
- **Cost of the checks:** 0.063 s per page, plus OCR (0.85 s per OCR page), which the text path already does.
- **Pages that stop the case today:** 2 pages (both poor scans) are below the product's 0.55 OCR limit. On those pages OCR reads no text, so the product stops the case (extraction failure).
- **How each check works:** see `routing/PageRouter.java` (class comment).

### Seconds per page

| Path | Mean | Median | 90th pct | Notes |
|---|---|---|---|---|
| Text path (product, `qwen3.5:9b`) | 1.60 | — | — | 0.30 extraction/OCR, 1.29 text model. In the package: 0.80 s/page |
| Vision `qwen3-vl:8b-instruct` | 2.93 | 2.58 | 4.30 | 27 routed pages, 0 unreadable answers |
| Vision `qwen2.5vl:7b` | 2.00 | 1.82 | 2.73 | 0 unreadable |
| Vision `minicpm-v:8b` | 1.63 | 1.35 | 2.74 | 1 unreadable |

### Found, missed, false flags: vision on the 27 routed pages (all 9 types, 130 items)

| | Found | Misses | False flags | Handwritten items found (of 57) |
|---|---|---|---|---|
| Text path alone, same pages | 24 | 106 | 0 | 11 |
| `qwen3-vl:8b-instruct` | **115** | **15** | **0** | **57** |
| `qwen2.5vl:7b` | 36 | 94 | 8 | 12 |
| `minicpm-v:8b` | 4 | 126 | 38 | 3 |

`qwen3-vl:8b-instruct` misses by page kind: CHECK 10 of 15, FAX 2 of 29, ID 1 of 25, TYPED 2 of 24, HANDWRITTEN 0 of 37. The misses were:
- the routing and account numbers in the line of digits at the bottom of checks (12, including the check on a fax);
- 1 small "ghost" photo on an ID card;
- 2 bank account numbers (one on a poor scan, one on the wrongly routed statement).

### Whole set: text path + vision on routed pages + signature detector (274 items)

| | Found | Misses | False flags | Misses on the text path's four V1 types (97 items) |
|---|---|---|---|---|
| Text path alone (today) | 71 | 203 | 0 | 26 |
| + `qwen3-vl:8b-instruct` | **236** | **38** | **0** | **0** |
| + `qwen2.5vl:7b` | 171 | 103 | 8 | — |
| + `minicpm-v:8b` | 148 | 126 | 38 | — |

- **Today's text path** misses 26 of the 97 SSN, date of birth, card number and credit score items. All 26 are on hard pages: handwritten 6, ID 5, fax 7, mismatched text layer 4, poor scan 4. It has 0 false flags.
- **The 38 misses left with qwen3-vl:**
  - 24 are bank account, routing and ID numbers on typed pages that stay on the fast path. The text path has no patterns for these types until R1 (item 2 of the V1 requirements).
  - 12 are on the line of digits at the bottom of checks.
  - 1 photo and 1 account number on a poor scan.

### Estimated seconds per document, and the 60-second target

Measured on the 60-page package, one request at a time:

| With | Text path | Routing checks | Vision (5 pages) | **Total** |
|---|---|---|---|---|
| `qwen3-vl:8b-instruct` | 48.0 | 3.8 | 14.9 | **65.8 s** |
| `qwen2.5vl:7b` | 48.0 | 3.8 | 11.8 | 62.7 s |
| `minicpm-v:8b` | 48.0 | 3.8 | 8.2 | 59.1 s |

- **60 s is not met** with the recommended model when the steps run one after another (65.8 s). It is about 6 s over.
- **What could close the gap** (estimates, not measured):
  - with 3 vision pages instead of 5 (the requirement's expectation), about 60 s;
  - if vision runs while the text model works, about 52 s (the larger of 48 + 3.8 and 14.9, plus some overhead). Both models fit on the GPU together: 5.3 GB + 6.3 GB of 16 GB.
- **30 s is not met:** the text path alone takes 48 s for 60 pages.
- **Whole-set estimate** (this set is weighted towards hard pages on purpose, 15 of every 60 pages routed): 144 s per 60 pages. It is not representative of a real package.

### GPU memory and requests at once

| Model | Model VRAM | GPU peak during run | 1 at once | 2 at once | 4 at once |
|---|---|---|---|---|---|
| `qwen3-vl:8b-instruct` | 6 331 MiB | 7 962 MiB | 3.4 s, 17.3 pages/min | 5.5 s, 15.5 pages/min | 8.0 s, 17.6 pages/min |
| `qwen2.5vl:7b` | 5 479 MiB | 7 046 MiB | 1.8 s, 32.0 | 2.7 s, 33.2 | 4.7 s, 30.7 |
| `minicpm-v:8b` | 4 722 MiB | 5 958 MiB | 2.0 s, 28.6 | 2.9 s, 30.7 | 4.0 s, 37.2 |
| `qwen3.5:9b` (text) | 5 346 MiB | 6 446 MiB | — | — | — |

- **Most requests at once that stayed fast: 1.** With 2 or 4 requests, the time per request grows about in line with the count, and throughput stays flat (pages per minute).
- `OLLAMA_NUM_PARALLEL` was not set (server default). The cells show mean time per request and pages per minute.

### OCR-confidence threshold

- **The sweep (0.55, 0.65, 0.75, 0.85, 0.90) changed nothing on this set.** Every page with OCR confidence below 0.90 was also sent to vision by another check (handwriting, picture or mismatched layer).
- **OCR confidence by page kind:**
  - clean typed scans: 0.90–0.97;
  - handwritten pages: 0.89–0.92;
  - checks: 0.84–0.88;
  - faxes: 0.64–0.85;
  - poor scans: 0.00.

## Recommendation

- **Model: `qwen3-vl:8b-instruct`** (digest above, Apache 2.0). It is far ahead of the other two: 115 of 130 items found on the routed pages, 0 false flags, every handwritten item found, and 2.9 s per page (4.3 s at the 90th percentile).
  - Its weak spot is the line of digits at the bottom of checks. Cover it with the R1 routing and account patterns run on that line (a checksum check), and/or a prompt v2. Re-run this benchmark after either change.
- **OCR threshold: 0.85 as a provisional default.** It keeps clean typed scans (0.90 and up) on the fast path and sends almost every fax measured here (0.64–0.85) to vision, even without the handwriting check. Confirm it with part B; the synthetic set cannot separate the thresholds.
- **60-second target: not met as measured** (65.8 s for a 60-page package, run one step after another). It looks reachable if P2 runs vision while the text path works, and if routing sends fewer false pages. 30 s is not reachable: the text model alone takes 48 s per 60 pages.
- **Proposed default pass marks**, for the owner to approve as LexaVault's thresholds:

| Measure | Proposed mark | Measured here (qwen3-vl combined) |
|---|---|---|
| Misses, SSN / date of birth / card number / credit score | 0 | 0 |
| Misses, signatures and initials | 0 | 0 |
| Misses, handwritten items (all types) | 0 | 0 |
| Misses, bank account / routing / ID number | ≤ 5% (after R1 patterns) | 50% (37 of 74: no text patterns yet; check line not read) |
| Misses, face photos | ≤ 10% | 10% (1 of 10) |
| False flags | ≤ 1 per 10 routed pages | 0 |
| Vision seconds per page, 90th percentile | ≤ 5 s | 4.3 s |
| Text path seconds per page (package) | ≤ 1 s | 0.80 s |
| Pages that must go to vision but went to the fast path | 0 | 0 |
| Fast pages wrongly sent to vision | ≤ 10% | 19% (3 of 16) |
| Seconds per 60-page document | ≤ 60 s | 65.8 s |

**How far to trust these numbers.** The handwriting is drawn with handwriting fonts, which OCR reads well (0.89–0.92) and which are more regular than real handwriting. The routing checks were tuned on this same set. Results on real documents (part B) will likely be worse. Treat part A as proof that the harness works and as a ranking of the models, not as the customer result.

## Commands run and results

| Command | Result |
|---|---|
| `git checkout -b feature/0033-vision-benchmark` | branch created |
| `cd backend; mvn -q install -DskipTests` | product modules installed in the local Maven repository (no product change; product tests not run) |
| `ollama pull qwen2.5vl:7b`, `qwen3-vl:8b`, `llama3.2-vision:11b`, `minicpm-v:8b`, `qwen3-vl:8b-instruct` | all downloaded |
| `cd tools/vision-benchmark; mvn -o clean package` | BUILD SUCCESS, 26 tests pass |
| `BenchmarkMain generate --root C:\dev\benchmark-samples` | 26 documents, 108 pages |
| routing-only runs (`--skip-text --skip-vision`, 3 times) and 2 small smoke runs (`--only …`) | used to tune the routing checks and to find the issues below |
| `BenchmarkMain run --root C:\dev\benchmark-samples --set public` | full run, about 25 min, exit 0, results `public-20261122-194400` |
| `BenchmarkMain summarize …` | summary rewritten after adding the signature-detector scoring and miss lists |
| `.\Run-VisionBenchmark.ps1 -Generate -Root <scratch folder>` | runner works. The answer files match the first generation (reproducible). The scratch folder was deleted |

**Issues found and fixed along the way:**
- `llama3.2-vision` will not load in this Ollama (`unknown model architecture: 'mllama'`).
- `qwen3-vl:8b` keeps thinking with thinking off, so it was replaced by `qwen3-vl:8b-instruct`.
- A JSON-schema answer format made qwen2.5vl return nothing, so it was removed.
- Ollama answers a repeated identical image from its cache, so the concurrency probe now marks each image and the first image request is an untimed warm-up.
- One of my own tests failed because of a badly chosen file name in the test. The test was fixed, not the harness.

**Text path on the poor scan:** the product's extraction stops (OCR reads no text from a non-blank page). The harness then saw an `IllegalStateException` from the failure path with its in-memory stand-ins. It is recorded as "stopped".

## Tests

Harness only (the product is unchanged):

| Test class | Tests |
|---|---|
| `ScorerTest` | 8 |
| `VisionClientTest` | 6 |
| `RoutingTest` | 6 |
| `SamplesAndTruthTest` | 4 |
| `FakeDataTest` | 2 |
| **Total** | **26, 0 failures** |

## Files changed

- `tools/vision-benchmark/` (new):
  - `pom.xml`, `README.md`, `Run-VisionBenchmark.ps1`;
  - `src/main/java/com/securedoc/benchmark/**`: generator, routing, text path, vision, scoring, summary;
  - `src/main/resources/vision-prompt-v1.txt`, `logback.xml`;
  - `src/test/java/**` (5 test classes).
- `docs/work/0033-report.md` (this report).
- Nothing in `backend/` or `frontend/`.

## Commits

- `c2d20b7` feat: task 0033, vision benchmark harness and part A report
- a follow-up commit adding this hash to the report.

Not merged, not pushed.

## Not done, and why

- **Part B:** no customer samples yet (the `customer\` folder is empty). Run it with `.\Run-VisionBenchmark.ps1 -Set customer` once the owner places the approved samples with their answer files.
- **Llama 3.2 Vision:** cannot run on this Ollama version; replaced by MiniCPM-V.
- **Vision and text path at the same time:** not measured. It needs the P2 pipeline design.
- **Neighbour-page text as context for vision:** not tried; prompt v1 sends the page image only.
- **Ollama parallel setting:** not changed. Changing it means restarting the Ollama server with `OLLAMA_NUM_PARALLEL`, which I did not do without asking.

## Questions for the owner

1. **Model:** approve `qwen3-vl:8b-instruct` as the candidate vision model? It is Apache 2.0 and comes from Alibaba (China). The customer's vendor-risk team needs to accept that origin.
2. **60 s target:** measured at 65.8 s per 60-page package. Should P2 plan to run vision while the text path works (both fit on a 16 GB GPU), or should the target change?
3. **Check line (routing and account numbers):** every vision model missed it. Add it to R1 (patterns with a checksum on the OCR of check pages), try a prompt v2, or both?
4. **OCR threshold:** accept 0.85 as the provisional default until part B?
5. **Unused downloads:** may I remove `llama3.2-vision:11b` (7.8 GB, cannot run here) and `qwen3-vl:8b` (6.2 GB, replaced by the instruct build)?
6. **Ollama parallel setting:** may I restart Ollama with `OLLAMA_NUM_PARALLEL=2` to measure requests at once properly? With the default, extra requests only queue.
7. **Part B:** confirm the customer's approval, and place the samples and answer files (format in the README) in `C:\dev\benchmark-samples\customer\`.

## Suggested next step

Part B on the customer samples, with the same command. Then prompt v2 and the R1 check-line patterns, re-measured with this harness.
