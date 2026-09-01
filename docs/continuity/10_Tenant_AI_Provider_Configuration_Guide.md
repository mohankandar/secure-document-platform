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
    model: gemma3:12b
    timeout-seconds: 180
    parallelism: 2
    page-max-attempts: 2
```

Equivalent environment variables:

```text
SECUREDOC_AI_PROVIDER=ollama
SECUREDOC_AI_BASE_URL=http://localhost:11434
SECUREDOC_AI_MODEL=gemma3:12b
```

Legacy `SECUREDOC_AI_OLLAMA_BASE_URL` and `SECUREDOC_AI_OLLAMA_MODEL` remain accepted as fallback for the current LOCAL scripts.

## Change Ollama model to Qwen

No code change:

```text
SECUREDOC_AI_PROVIDER=ollama
SECUREDOC_AI_BASE_URL=http://localhost:11434
SECUREDOC_AI_MODEL=qwen3:14b
```

The provider is still Ollama; Qwen is the model.

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
