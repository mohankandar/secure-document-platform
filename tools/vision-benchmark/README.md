# Vision benchmark (tasks 0033 and 0040)

Measures, per page of each sample: which path the routing rule would choose (`docs/work/vision-routing-requirement.md` section 2), what today's text path finds, and what local vision models find on the routed pages, against a hand-made answer file. No product code: nothing here is part of the product build, and the product does not depend on it.

## What it uses

- The product's own modules (`securedoc-application`, `securedoc-infrastructure`, `securedoc-ai`) from the local Maven repository, so the text path is the product's code: `SensitiveDataDetectionService` with `HybridPdfTextExtractionAdapter`, `TesseractOcrAdapter`, `LocalRuleBasedDetectionAdapter` and `OllamaSensitiveContentAnalysisAdapter`, at the product's default settings. Only the database and storage are in-memory stand-ins.
- Its own dependency: JUnit 5.10.3 (tests only, the version the product uses). Build plugins: the standard Maven compiler, resources, jar, surefire and dependency plugins.
- Tesseract on PATH, and a local Ollama. The harness refuses any Ollama address other than localhost.

## Privacy rules built in

- Samples and results live under the samples root (default `C:\dev\benchmark-samples`), which must be outside any git repository; the harness refuses a root inside one.
- Customer samples are named `customer-01`, `customer-02`, ... (sorted file-name order) in every output; file names never appear.
- Results hold ids, page numbers, item types, boxes, counts and seconds. No value, no page text, no picture. The vision model's raw answer is parsed in memory (type and box only) and dropped.
- Console output: ids and numbers only. Product log lines are value-free and shown at WARN level only.

## Folder layout

```
C:\dev\benchmark-samples\
  public\     part A: synthetic samples + answer files (made by "generate")
  customer\   part B: the owner places approved samples + answer files here
  results\    one folder per run: results.json and summary.md
```

## Answer files

Each sample `X.pdf` has `X.truth.json` beside it: per page, the page kind and the sensitive items as a type and a rough box. **Never a value.**

```json
{
  "version": 1,
  "pages": [
    { "page": 1, "kind": "CHECK", "expectedRoute": "VISION",
      "items": [ { "type": "ROUTING_NUMBER", "box": [0.18, 0.31, 0.16, 0.02] },
                 { "type": "SIGNATURE", "box": [0.56, 0.27, 0.28, 0.05], "handwritten": true } ] }
  ]
}
```

- `box`: `[left, top, width, height]`, each 0 to 1 of the page, origin top-left. Rough is fine.
- `kind`: `TYPED`, `HANDWRITTEN`, `ID`, `CHECK` or `FAX`.
- `type`: `SSN`, `DATE_OF_BIRTH`, `CREDIT_CARD_NUMBER`, `CREDIT_SCORE` (the text path's four types today), `BANK_ACCOUNT`, `ROUTING_NUMBER`, `GOVERNMENT_ID`, `SIGNATURE`, `FACE_PHOTO`.
- `expectedRoute` (optional): `FAST`, `FAST_SIGNATURE` or `VISION`, to score the routing.
- `handwritten` (optional): `true` for handwritten items.

## Run

```powershell
cd tools\vision-benchmark
.\Run-VisionBenchmark.ps1 -Generate          # part A samples (26 documents, 108 pages, fixed seed)
.\Run-VisionBenchmark.ps1 -Set public        # part A run, about 25 minutes on an RTX 5080 laptop GPU
.\Run-VisionBenchmark.ps1 -Set customer      # part B, same command, no code change
```

Options: `-TextKinds v1|all` (task 0043; default `v1`, the product's default kinds; `all` also switches on the opt-in pattern kinds: bank routing and account numbers, ITIN, EIN, driver's license and passport numbers, scored as `ROUTING_NUMBER`, `BANK_ACCOUNT` and `GOVERNMENT_ID`), `-SkipVision` (routing and text path only), `-Models`, `-TextModel`, `-Threshold` (OCR confidence below which a page goes to vision; the summary also sweeps 0.55 to 0.90), `-Ollama`, `-Prompt v1|v2` (default `v1`), `-Boxes model=PIXELS|PER_MILLE|UNIT,...` (box convention for a model whose trained convention is not the default). Models must already be pulled (`ollama pull <name>`).

Probe only (task 0040), without the full run: requests at once per model, and the text model and one vision model resident together:

```powershell
java -cp "target/classes;target/lib/*" com.securedoc.benchmark.BenchmarkMain probe --root C:\dev\benchmark-samples `
    --models qwen3-vl:8b-instruct,gemma3:12b --with-text qwen3.5:9b --with-vision qwen3-vl:8b-instruct --label "OLLAMA_NUM_PARALLEL=2"
```

`--label` records how the Ollama server was started; the harness cannot read the server's settings. Output: `results\probe-<time>\probe.json` and `probe.md`.

To rewrite a summary from saved results: `java -cp "target/classes;target/lib/*" com.securedoc.benchmark.BenchmarkMain summarize --root C:\dev\benchmark-samples --results <run folder>\results.json`.

## How things are measured

- **Routing checks**: see the class comment of `routing/PageRouter.java` (text layer, OCR confidence with the product's Tesseract adapter, positional text-layer/OCR agreement, a tile-based picture detector, and an unexplained-ink check that separates signatures from handwriting by a nearby "sign"/"initial" label).
- **Text path**: `textpath/TextPathRunner.java`. One difference from the product: the OCR minimum page confidence is set to 0 so findings on poor pages can be measured; the summary counts the pages where the product would stop the case today.
- **Vision**: `vision/VisionClient.java` with `src/main/resources/vision-prompt-v1.txt` or `vision-prompt-v2.txt` (v2, task 0040: extra guidance for the check line, small ID photos and account numbers on poor scans, same output contract; the prompt version and its sha256 are in every result). Pages are sent at 1400 px on the long side. Each model gets the box convention it was trained on (qwen3-vl: 0 to 1000; others: pixels unless `--boxes` says otherwise; the conventions used are in every result); the rest of the prompt is identical. Temperature 0. The first image request per model is an untimed warm-up. Vision runs on the pages routed at 0.90 (the widest threshold of the sweep), so every threshold can be scored from one run.
- **Scoring**: `score/Scorer.java` (same page, same type, box overlap of at least 30% of the smaller box or the item's centre inside the found box, found box at most 10 times the item's area).
- **Requests at once**: 1, 2 and 4 concurrent requests per model, two rounds each, each image slightly marked so Ollama's cache cannot answer it. The probe uses the first 8 pages the answer files mark as VISION.
- **Text and vision resident together** (probe `--with-text`): both models loaded, then per round one text request alone (the product's text path on one typed page with an SSN or date of birth), one vision request alone, and both at once.
- **GPU memory**: `nvidia-smi` sampled every 0.5 s while each model runs, and Ollama's own VRAM figure.
- **Model records**: digest, size, quantization and the first licence line from Ollama, for the text model and each vision model.
