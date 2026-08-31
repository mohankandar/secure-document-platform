# Ollama Page-Retry Reliability Fix — ChatGPT Self-Review — 2026-08-31

## Trigger

Real LOCAL processing of a 61-page native-text customer PDF produced:

- extraction: 61 native pages / 0 OCR pages / ~2s;
- Ollama `gemma3:12b` document attempt 1: ~246s then failure;
- complete document attempt 2: ~238s then failure;
- package failed after ~490s.

The failure showed that a transient page failure caused the generic AI resilience decorator to replay the full document.

## Production changes

1. `OllamaSensitiveContentAnalysisAdapter`
   - configurable bounded page parallelism;
   - targeted transient retry per failed page;
   - retry exhaustion stops whole-document replay;
   - safe page attempt/recovery/failure reason logs;
   - analysis summary includes page retry count.
2. `AiRuntimeProperties`
   - parallelism;
   - page max attempts;
   - page retry backoff.
3. `ApplicationServiceConfig`
   - wires new settings to Ollama primary/secondary providers.
4. `application.yml`
   - environment-configurable base defaults.
5. `application-local.yml`
   - LOCAL `gemma3:12b` defaults: timeout 180s, parallelism 2, 2 page attempts, 500ms backoff.
6. `SelectiveRetryExecutor`
   - preserves lower-level targeted retry attempt counts.

## Focused test

`OllamaPageRetryTest` proves:

- a successful page is requested once while a transiently failed page is requested twice;
- an exhausted page retry is propagated as non-retryable to the outer document decorator, preventing a second full-document execution.

## Security / privacy review

New logs contain only:

- provider/model;
- page number;
- stable technical reason code;
- attempt/retry decision;
- counts and durations.

They do not log raw page text, prompt bodies, model response bodies, finding values, customer names/emails, SSNs, card values, tokens or secrets.

## Scope review

No change to:

- AI-first requirement;
- supported four sensitive categories;
- customer-specific SSN-last-four policy;
- deterministic detector role;
- redaction engine;
- PDF/OCR normalization;
- identity/tenant/security architecture;
- delivery/lifecycle architecture.

No Kafka, queue redesign, model replacement, microservice conversion or deterministic page prefilter was introduced.

## Local self-checks performed in ChatGPT environment

- changed application resilience classes compiled with Java 21: PASS;
- `application.yml` parsed: PASS;
- `application-local.yml` parsed: PASS;
- configuration wiring/reference scan: PASS;
- safe-log source scan: PASS;
- package/file integrity check: PASS after ZIP creation.

Full Maven/Frontend build cannot be claimed from the ChatGPT container because Maven is not available there. User must run the included `build-all.cmd`; the user's immediately preceding baseline full build was green before this delta.

## Required next verification

Run:

```powershell
.\build-all.cmd
.\start-local.cmd
```

Reprocess the same 61-page PDF and provide the high-level logs from `PACKAGE_PROCESSING_STARTED` through `PACKAGE_PROCESSING_FINISHED`.
