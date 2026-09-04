# Requirement 5 — Kiro Verification Contract

Kiro is reviewer/tester. ChatGPT owns implementation and architecture.

Historical Requirement 5 R2 verification contract. Qwen3.5:4b subsequently passed and is now the accepted V1 LOCAL/demo reference model. Preserve this file as benchmark evidence; future model changes require an equivalent controlled speed + quality gate.

Core evidence required:

- full backend/frontend build and Spring startup;
- historical R2 comparison kept `gemma3:12b` installed/configurable while explicitly selecting `qwen3.5:4b`; Qwen subsequently passed and is now the accepted LOCAL/demo reference;
- direct Spring `local` startup preload and launcher warmup both work without sending customer content; `ollama ps` confirms the selected model is resident and shows CPU/GPU placement;
- JSON-schema structured output, `think=false`, configured context/output budgets and batching are present in real Ollama requests;
- moderate 5-page and 10-page representative documents use fewer provider calls than page count with LOCAL batch size 2;
- oversized page batches split safely and no document text is truncated by client batching;
- batch findings are accepted only when exact matched text exists on the declared page;
- existing page/batch retry does not replay already successful batches;
- existing credit-card precision and tenant-policy tests remain green;
- safe metrics expose timing/token/call counts only, never prompts/page text/model output/sensitive values;
- compare at least three warm measured runs per representative document where practical;
- quality/finding behavior must be compared with the accepted baseline before calling a speed improvement successful;
- Requirements 1–4 and frozen OCR/redaction/review/delivery behavior must not regress.

Kiro may fix only obvious compile/test-compile/import/Maven/Spring-wiring blockers. Functional, accuracy, security, tenant-isolation, AI-policy, retry, batching, or fail-closed defects must be reported without independent correction.

Verdict: exactly `PASS`, `PASS WITH MINOR FINDINGS`, or `FAIL — CORRECTION REQUIRED`, with commands/evidence, machine profile, benchmark table, finding-quality comparison, files modified, and recommendation on whether `qwen3.5:4b` meets the V1 demo performance + quality target and should become the LOCAL/demo default, while Gemma remains an available correctness/reference profile.

## R2 controlled Qwen benchmark

The approved comparison model is already installed as `qwen3.5:4b`. Keep `gemma3:12b` installed. Use the same representative PDFs and expected-finding set.

Recommended local launches:

```powershell
.\Start-LexaVault-Local.ps1 -AiModel qwen3.5:4b
```

For direct Spring startup verification of the new in-application preloader, use the equivalent `local,local-qwen` active profiles without the launcher warmup. Confirm `AI_MODEL_PRELOAD_STARTED` and `AI_MODEL_PRELOAD_SUCCEEDED` and verify `ollama ps` shows the Qwen model resident.

Capture environment/model placement explicitly:

```powershell
.\scripts\Capture-LexaVault-AiEnvironment.ps1 -Model qwen3.5:4b
```

The model is the only intended inference-variable change from the Gemma benchmark. Keep pageBatchSize=2, pageBatchMaxChars=14000, num_ctx=8192, num_predict=2048, parallelism=2, timeout=180s, structured output enabled, think=false, and the same page-grounding/precision checks.
