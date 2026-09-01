# Secure Document Platform

Generic codebase for the LexaVault project codename. Market-facing naming is intentionally not hardcoded into reusable code.

## Current V1 Direction

An AI-first automatic secure document-redaction platform:

```text
PDF -> native extraction/OCR -> AI semantic discovery + deterministic evidence
    -> policy/confidence -> permanent redaction -> verification -> delivery
```

AI independently analyzes every normalized document. Deterministic recognizers/validators strengthen structured findings but do not gate AI discovery. High-confidence straight-through processing requires no human approval by default; only specific uncertain findings are routed to focused review.

## Repository Guidance

Before development, read:

- `.kiro/steering/requirements-index.md`
- `.kiro/specs/secure-document-platform-v1/unit-of-work.md`
- `docs/architecture/06-core-automatic-redaction-pipeline.md`

Current construction status: **Units 16–25 are approved. There is no Unit 26 in the frozen V1 plan.** The project is in **Final V1 Security / Integration + Acceptance** with focused pre-acceptance runtime corrections only when real customer-package testing exposes a concrete defect. The current baseline includes the Ollama page-level retry correction, the 2026-08-31 DOB/SSN precision and Manual Review preview-diagnostic correction, the runtime-validated credit-card precision correction, and the tenant-driven prompt/per-tenant AI provider routing delta documented under `docs/continuity/`, plus the runtime-driven Dashboard/Manual Review/package-visibility corrections and the 2026-09-01 final-review-item asynchronous resume correction documented under `docs/continuity/`.

## Current Continuity / Final Acceptance

Before coding or verification, read `docs/continuity/README.md` and `docs/continuity/00_LexaVault_V1_Master_Current.md`.

LOCAL convenience scripts are available at the repository root:

- `Start-LexaVault-Local.ps1` / `start-local.cmd`
- `Stop-LexaVault-Local.ps1` / `stop-local.cmd`

The current customer masking policy is tenant-configured for SSN, DOB, Credit Score and Credit Card Number, with a separate `maskSsnLastFour` option for contextual SSN-last-four exposure.

## AI Provider / Model Configuration

Business redaction policy and AI execution are separate. Business Admin controls enabled sensitive categories/options. Deployment/Platform configuration controls provider, endpoint, model and credentials.

Supported V1 transports are Ollama and OpenAI-compatible/vLLM. Model is configuration, so Ollama can run Gemma or Qwen without code changes. Optional deployment-managed tenant profiles are keyed by trusted tenant UUID. Set `SECUREDOC_AI_REQUIRE_TENANT_PROFILE=true` when every production tenant must have an explicit AI endpoint and fallback to the default provider is forbidden.

See `docs/continuity/10_Tenant_AI_Provider_Configuration_Guide.md`.
