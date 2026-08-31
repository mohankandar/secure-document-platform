# Local AI Setup — Unit 16

Unit 16 is AI-first. Every digital/text PDF is sent through the semantic discovery port before deterministic evidence is merged. The default local provider is Ollama.

## Required runtime configuration

The default configuration is:

```yaml
securedoc:
  ai:
    provider: ollama
    base-url: http://localhost:11434
    model: qwen2.5:7b
    timeout-seconds: 60
```

Equivalent environment overrides:

```bash
export SECUREDOC_AI_PROVIDER=ollama
export SECUREDOC_AI_OLLAMA_BASE_URL=http://localhost:11434
export SECUREDOC_AI_OLLAMA_MODEL=qwen2.5:7b
export SECUREDOC_AI_TIMEOUT_SECONDS=60
```

The configured model must already be available to the local Ollama runtime. The application fails the document safely if the AI provider is unavailable or returns an invalid response; it does not silently downgrade to deterministic-only discovery.

## Development fallback

For wiring/smoke development only, a bounded semantic heuristic adapter is available:

```bash
export SECUREDOC_AI_PROVIDER=heuristic
```

This is **not** an LLM and is **not** acceptable evidence that the V1 AI-first runtime acceptance passed. It exists only to exercise the semantic-discovery path when a local model is temporarily unavailable.

## Responsibility split

```text
Native PDF text + trusted geometry
             ↓
AI semantic discovery (primary)
             +
Deterministic recognizers/validators (supporting evidence)
             ↓
Merged findings + confidence
             ↓
Policy: auto-confirm or focused review
             ↓
Controlled burn-flat redaction
             ↓
Verification
             ↓
Batch-gated delivery
```

AI identifies *what* content means. Native PDF extraction supplies authoritative digital-PDF location. The redaction engine, not AI, physically modifies the PDF.

## Verified Unit 16 local runtime

The formal Unit 16 acceptance run used a real local Ollama instance with:

```bash
SECUREDOC_AI_PROVIDER=ollama
SECUREDOC_AI_OLLAMA_MODEL=gemma3:12b
```

All acceptance scenarios passed with that model. The repository default remains `qwen2.5:7b`; operators should explicitly configure a model that is installed in their local Ollama runtime. The verified `gemma3:12b` run is evidence of the provider-neutral local architecture, not a permanent product-model selection.
