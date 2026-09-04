# Local AI Setup

LexaVault V1 is AI-first. Every normalized document is sent through semantic discovery before deterministic supporting evidence is merged.

## Shipped base defaults

Base configuration defaults to the Ollama transport and remains deployment configurable:

```yaml
securedoc:
  ai:
    provider: ollama
    base-url: http://localhost:11434
    model: qwen2.5:7b
    parallelism: 2
    page-max-attempts: 2
    ollama-keep-alive: 5m
    ollama-page-batch-size: 1
```

Preferred environment overrides:

```text
SECUREDOC_AI_PROVIDER=ollama
SECUREDOC_AI_BASE_URL=http://localhost:11434
SECUREDOC_AI_MODEL=<installed-model>
```

Legacy `SECUREDOC_AI_OLLAMA_BASE_URL` and `SECUREDOC_AI_OLLAMA_MODEL` remain accepted as compatibility fallbacks in the current configuration/start scripts.

## LOCAL reference runtime

The repository `Start-LexaVault-Local.ps1` currently selects:

```text
Ollama / qwen3.5:4b
```

`qwen3.5:4b` is the accepted V1 LOCAL/demo reference model after the Requirement 5 controlled speed + quality benchmark. Provider/model selection remains configuration owned; `gemma3:12b` remains available for explicitly configured larger hardware and is never selected as a silent fallback.

Requirement 5 accepted LOCAL defaults are: Current LOCAL defaults are:

```text
keep_alive=30m
structured JSON schema=true
thinking=false
num_ctx=8192
num_predict=2048
page batch size=2
page batch max characters=14000
parallelism=2
```

`Start-LexaVault-Local.ps1` preloads the configured Ollama model and keeps it resident before starting the backend. Use `-AiModel gemma3:12b` only when deliberately testing the optional larger-hardware model and `-SkipAiWarmup` only when deliberately testing cold-start behavior. Direct Spring startup with the `local` profile preloads the accepted Qwen model in-process and fails closed when the configured model cannot be loaded; `local,local-qwen` remains an explicit benchmark/compatibility overlay.

The Ollama completion log now includes provider call count, prompt/output token counts and evaluation durations so performance can be measured safely without logging document text or sensitive values. See `../testing/AI_PERFORMANCE_BENCHMARK.md`.

## Failure behavior

If the configured AI provider is unavailable or returns an invalid response, document processing fails safely as a technical dependency failure. The application does not silently downgrade to deterministic-only sensitive-content discovery.

## Responsibility split

```text
Native PDF text/geometry or OCR text/geometry
               ↓
AI semantic discovery (primary)
               +
Deterministic recognizers/validators (supporting evidence)
               ↓
Merged findings + confidence
               ↓
Tenant policy: straight-through or focused review
               ↓
Permanent redaction
               ↓
Verification
               ↓
Delivery
```

AI identifies meaning. Native PDF extraction/OCR supplies trusted location. The deterministic redaction engine physically modifies the PDF.

For Ollama, OpenAI-compatible/vLLM, per-tenant profiles, fail-closed routing, and credential rules, see `AI_PROVIDER_CONFIGURATION.md`.
