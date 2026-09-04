# Requirement 5 — Local AI Performance Benchmark Protocol

Use this protocol before changing the V1 acceptance model. Speed is not accepted at the cost of detection quality.

## Reference and alternative benchmark models

Requirement 5 is now accepted with Ollama `qwen3.5:4b` as the V1 LOCAL/demo reference on the current ~6 GB NVIDIA VRAM laptop. The protocol below preserves the controlled comparison history: Gemma3:12b was the prior correctness reference but was too large for this hardware; Qwen passed the same speed + quality gate. Gemma remains explicitly configurable for larger hardware and is never a silent fallback.

## Representative corpus

Use at minimum:

1. one approximately 5-page mortgage/real-estate PDF;
2. one approximately 10-page mortgage/real-estate PDF;
3. the existing larger regression package when practical;
4. at least one scanned/mixed PDF if OCR performance is being compared end-to-end.

Keep the exact same documents and tenant redaction policy across comparisons.

## Capture the machine profile

Before comparing runs, record the local hardware/Ollama/model profile:

```powershell
.\scripts\Capture-LexaVault-AiEnvironment.ps1
```

This makes results interpretable when the same candidate is tested on another customer/server profile.

## Warm benchmark procedure

1. Build the accepted/candidate workspace.
2. Start with `Start-LexaVault-Local.ps1`; the launcher preloads the selected Ollama model unless `-SkipAiWarmup` is supplied. The default LOCAL launcher now selects `qwen3.5:4b`; use `-AiModel` only to make a deliberate model override. Direct Spring startup with the `local` profile also has an in-application fail-closed preloader; `local,local-qwen` remains an explicit Qwen overlay/compatibility profile; the base `local` profile now already defaults to the accepted Qwen model.
3. Confirm the configured model/profile in startup and `AI_ANALYSIS_STARTED` logs.
4. Process each representative package once as warm-up if storage/workflow state requires it.
5. Run at least three measured iterations for each representative document/profile.
6. Capture backend logs containing `PACKAGE_PROCESSING_FINISHED` and `AI_ANALYSIS_COMPLETED`.
7. Run:

```powershell
.\scripts\Summarize-LexaVault-AiBenchmark.ps1 -LogPath .\path\to\backend.log
```

## Metrics to compare

For AI:

- pages;
- batches;
- Ollama provider calls;
- calls/page;
- AI duration;
- model load time;
- prompt tokens and prompt-evaluation time;
- output tokens and output-evaluation time;
- output tokens/second;
- retry count.

For the full package:

- extraction/OCR duration;
- AI duration;
- permanent redaction/verification duration;
- total package-processing duration;
- final status / whether focused review was required.

## Quality gate

Do not accept a faster profile/model unless it preserves the required LexaVault behavior on the same corpus:

- expected SSN/DOB/credit-card/credit-score findings are not missed;
- known false-positive precision guardrails remain effective;
- exact matched text still maps to trusted PDF/OCR text;
- no fabricated values are accepted;
- malformed/truncated model output fails safely;
- tenant category policy remains enforced.

Record any finding delta explicitly. A material accuracy regression is a FAIL even if throughput improves.

## Tuning order

Change one dimension at a time where practical:

1. warm/keep-alive;
2. compact structured output / thinking disabled;
3. page batching and parallelism;
4. context/output token budgets;
5. only then evaluate another approved local model.

The current LOCAL candidate defaults are intentionally conservative: batch size 2, max 14,000 characters per batch, 8,192 context tokens, 2,048 max output tokens, parallelism 2, and 30-minute keep-alive.


## Requirement 5 R2 model-fit decision

Gemma3:12b remains installed/configurable but was not performant on the benchmark laptop because the quantized model exceeded GPU VRAM and spilled heavily to CPU. The R2 comparison changes only the configured model to `qwen3.5:4b`; batching, structured output, page grounding, context/output budgets, retry, timeout, and parallelism remain unchanged. Acceptance still requires both throughput and finding-quality parity.
