# LexaVault V1 — Tenant AI Provider / Model Configuration Guide

## Separate the two configurations

### Business redaction policy
Business Admin controls **what the tenant wants detected/redacted**, for example:

- SSN enabled
- DOB enabled
- Credit Score enabled
- Credit Card Number enabled
- `maskSsnLastFour=true`

This policy is stored as tenant business policy and is passed into the provider-neutral prompt builder at runtime.

### AI execution profile
IT/Platform/deployment configuration controls **where/how AI runs**:

- provider transport
- endpoint/base URL
- model name
- timeout
- concurrency
- page retry
- optional deployment-managed bearer credential

These settings do not change the tenant's masking policy.

## Default Ollama profile

```yaml
securedoc:
  ai:
    provider: ollama
    base-url: http://localhost:11434
    model: qwen3.5:4b
    timeout-seconds: 180
    parallelism: 2
    page-max-attempts: 2
    ollama-keep-alive: 30m
    ollama-structured-output: true
    ollama-thinking-enabled: false
    ollama-context-window-tokens: 8192
    ollama-max-output-tokens: 2048
    ollama-page-batch-size: 2
    ollama-page-batch-max-characters: 14000
```

Equivalent environment variables:

```text
SECUREDOC_AI_PROVIDER=ollama
SECUREDOC_AI_BASE_URL=http://localhost:11434
SECUREDOC_AI_MODEL=qwen3.5:4b
SECUREDOC_AI_OLLAMA_KEEP_ALIVE=30m
SECUREDOC_AI_OLLAMA_STRUCTURED_OUTPUT=true
SECUREDOC_AI_OLLAMA_THINKING_ENABLED=false
SECUREDOC_AI_OLLAMA_CONTEXT_WINDOW_TOKENS=8192
SECUREDOC_AI_OLLAMA_MAX_OUTPUT_TOKENS=2048
SECUREDOC_AI_OLLAMA_PAGE_BATCH_SIZE=2
SECUREDOC_AI_OLLAMA_PAGE_BATCH_MAX_CHARACTERS=14000
```

Legacy `SECUREDOC_AI_OLLAMA_BASE_URL` and `SECUREDOC_AI_OLLAMA_MODEL` remain accepted as fallback for the current LOCAL scripts.

## Production Ollama inference pool (R1 candidate)

Pool mode is opt-in. A one-node deployment can continue using `base-url` exactly as before. For HA/scale, configure homogeneous Ollama nodes that expose the **same exact approved model name**:

```yaml
securedoc:
  ai:
    provider: ollama
    model: qwen3.5:4b
    pool-enabled: true
    pool-node-urls: ai-1=http://ai-node-1:11434,ai-2=http://ai-node-2:11434
    pool-max-concurrent-per-node: 1
    pool-max-node-attempts: 2
    pool-health-probe-timeout-ms: 1500
    pool-health-check-interval-ms: 5000
    pool-circuit-open-ms: 10000
    pool-queue-wait-ms: 5000
```

Equivalent environment variables:

```text
SECUREDOC_AI_POOL_ENABLED=true
SECUREDOC_AI_POOL_NODE_URLS=ai-1=http://ai-node-1:11434,ai-2=http://ai-node-2:11434
SECUREDOC_AI_POOL_MAX_CONCURRENT_PER_NODE=1
SECUREDOC_AI_POOL_MAX_NODE_ATTEMPTS=2
SECUREDOC_AI_POOL_HEALTH_PROBE_TIMEOUT_MS=1500
SECUREDOC_AI_POOL_HEALTH_CHECK_INTERVAL_MS=5000
SECUREDOC_AI_POOL_CIRCUIT_OPEN_MS=10000
SECUREDOC_AI_POOL_QUEUE_WAIT_MS=5000
```

Pool behavior:

- `/api/tags` is used as a content-free readiness/model check; nodes missing the exact configured model are excluded before customer text is sent;
- routing prefers the least-inflight eligible node with round-robin tie rotation;
- each node has a bounded document-level concurrency permit;
- if all healthy nodes are busy, the request waits only for the bounded pool queue window;
- infrastructure failures (`AI_UNAVAILABLE`, `AI_TIMEOUT`, `AI_RATE_LIMITED`) can fail over to another eligible node;
- a failed node opens a bounded circuit and is health-probed before rejoining;
- after targeted node/page attempts plus pool failover are exhausted, the error is marked non-retryable so the outer document resilience layer does not replay the whole document again;
- startup model preload is pool-aware: fail-closed startup requires at least one ready node, not every configured node;
- Platform health reports the aggregate number of configured/reachable/model-ready pool nodes without returning prompts, model responses or endpoint credentials.

There is **no automatic model substitution**. A reachable node with another model is treated as not ready. The existing explicit secondary-provider mechanism remains separate and must be deliberately configured.

### Operational ownership

For on-prem deployments, Platform Operations enables/governs the capability and supported provider/model guardrails; `TENANT_IT_ADMIN` owns the tenant/deployment endpoint configuration and day-to-day node operation. Business Admin does not manage infrastructure endpoints. In a future SaaS deployment, this same boundary allows Platform Operations to run shared platform pools while tenant profiles can select approved shared or dedicated capacity.

## Requirement 5 Ollama performance controls

The Ollama adapter supports deployment/profile-level inference tuning without changing business policy:

- `ollama-keep-alive`: keeps the selected model resident between requests;
- `ollama-structured-output`: sends a JSON schema rather than unconstrained JSON mode;
- `ollama-thinking-enabled`: disabled for extraction by default so thinking-capable models do not spend tokens on unnecessary reasoning;
- `ollama-context-window-tokens`: `num_ctx`; zero means the provider/model default;
- `ollama-max-output-tokens`: `num_predict`; zero means the provider/model default;
- `ollama-page-batch-size`: number of moderate pages analyzed in one provider call;
- `ollama-page-batch-max-characters`: safety cap that splits a batch before page text becomes too large.

Each tenant profile may override these values when that tenant uses Ollama. OpenAI-compatible/vLLM profiles continue to use their provider-specific adapter behavior.

LOCAL currently uses a conservative two-page batch. Oversized pages are isolated automatically rather than truncated. The parser requires every batched finding to declare its page number and still rejects any matched text that is not present on that exact page.

Do not raise batch size/context/parallelism blindly. Benchmark on the deployment hardware and representative customer documents using `../testing/AI_PERFORMANCE_BENCHMARK.md`.

## Select a different approved Ollama model

No code change is required. For example, on larger hardware the previously validated Gemma model can be selected explicitly:

```text
SECUREDOC_AI_PROVIDER=ollama
SECUREDOC_AI_BASE_URL=http://localhost:11434
SECUREDOC_AI_MODEL=gemma3:12b
```

The provider remains Ollama; model selection is explicit configuration and never a silent fallback.

## vLLM / customer-managed OpenAI-compatible endpoint

```yaml
securedoc:
  ai:
    provider: openai-compatible
    base-url: ${SECUREDOC_AI_BASE_URL}
    model: ${SECUREDOC_AI_MODEL:Qwen/Qwen3-14B}
    api-key: ${SECUREDOC_AI_API_KEY:}
```

`vllm` is accepted as an alias for the same OpenAI-compatible transport.

The adapter resolves these forms:

- `http://host:8000` -> `/v1/chat/completions`
- `http://host:8000/v1` -> `/v1/chat/completions`
- a full URL ending `/chat/completions` -> used directly

API key is optional for local vLLM and can be provided as a deployment secret when the customer endpoint requires bearer authentication.

## Different provider/model per tenant

External deployment YAML/config can define profiles by trusted tenant UUID:

```yaml
securedoc:
  ai:
    tenant-profiles:
      "00000000-0000-0000-0000-000000000001":
        provider: ollama
        base-url: ${TENANT_A_AI_BASE_URL:http://localhost:11434}
        model: ${TENANT_A_AI_MODEL:qwen3:14b}
        timeout-seconds: 180
        parallelism: 2

      "11111111-1111-1111-1111-111111111111":
        provider: openai-compatible
        base-url: ${TENANT_B_AI_BASE_URL}
        model: ${TENANT_B_AI_MODEL:Qwen/Qwen3-14B}
        api-key: ${TENANT_B_AI_API_KEY:}
        timeout-seconds: 180
        parallelism: 4
```

The profile key comes from trusted tenant identity in the application request, not a production caller-controlled header.

An Ollama tenant profile may also override the pool controls. This supports an on-prem dedicated tenant pool now and a future SaaS shared/dedicated routing model without changing the application port:

```yaml
securedoc:
  ai:
    tenant-profiles:
      "00000000-0000-0000-0000-000000000001":
        provider: ollama
        model: qwen3.5:4b
        pool-enabled: true
        pool-node-urls: tenant-a-1=http://tenant-a-ai-1:11434,tenant-a-2=http://tenant-a-ai-2:11434
        pool-max-concurrent-per-node: 1
```

## Fail closed when every tenant must have its own AI

For a multi-tenant deployment where fallback to the default provider is forbidden:

```text
SECUREDOC_AI_REQUIRE_TENANT_PROFILE=true
```

or:

```yaml
securedoc:
  ai:
    require-tenant-profile: true
```

If a tenant does not have an explicit profile, AI processing fails as a technical dependency failure **before its document text is sent to the default endpoint**.

## Credentials

Do:

- inject API keys through environment/secrets management;
- keep credentials deployment-managed;
- restart/redeploy after V1 profile changes.

Do not:

- store AI credentials in Business Admin policy;
- put secrets in browser local/session storage;
- log API keys;
- expose them through Platform Admin payloads;
- add a browser credential editor for V1.

## Customer AI not OpenAI-compatible

If a customer provides an endpoint with a proprietary request/response protocol, implement one dedicated `SensitiveContentAnalysisPort` adapter for that protocol. Tenant redaction policy, prompt policy contract, finding merge, review, redaction and delivery do not need to change.
