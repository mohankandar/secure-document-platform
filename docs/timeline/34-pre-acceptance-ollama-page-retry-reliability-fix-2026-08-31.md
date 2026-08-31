# Pre-Acceptance Ollama Page-Retry Reliability Fix — 2026-08-31

## Trigger

A real 61-page customer PDF completed native extraction in ~2 seconds, then Ollama `gemma3:12b` failed two complete document-level analysis attempts (~246s and ~238s). Package processing ended `FAILED` after ~490s.

## Root cause in application behavior

The Ollama adapter submitted page work concurrently but surfaced any transient page failure to the generic `ResilientSensitiveContentAnalysisPort`, which retried the entire document. This replayed successful page work. Hardcoded client parallelism `4` plus a `60s` request timeout also made local-model queueing more likely to manifest as client timeouts.

## Focused implementation

- Configurable `securedoc.ai.parallelism` / `SECUREDOC_AI_PARALLELISM`, default `2`.
- Configurable `securedoc.ai.page-max-attempts`, default `2`.
- Configurable `securedoc.ai.page-retry-backoff-ms`, default `500`.
- LOCAL AI timeout default raised to `180s`; base default remains `60s`.
- Ollama transient failures retry only the failed page.
- Exhausted page retry becomes non-retryable to the outer document decorator, preserving optional secondary-provider failover without replaying the whole primary document.
- Safe page-level reason/attempt/recovery logs added. No raw page/model/sensitive content is logged.
- Focused test added: `OllamaPageRetryTest`.
- Retry attempt accounting preserves lower-level targeted attempts.

## No scope expansion

No Kafka, queue redesign, provider replacement, deterministic prefiltering, or AI model change was introduced. `gemma3:12b` remains the Final V1 LOCAL reference model until acceptance evidence says otherwise.

## Required user verification

Run from project root:

```powershell
.\build-all.cmd
.\start-local.cmd
```

Then reprocess the same 61-page customer PDF and return the high-level `AI_*`, `DOCUMENT_*`, and `PACKAGE_PROCESSING_*` logs.
