# Tenant-Driven Prompt + Per-Tenant AI Provider/Model Routing — Self Review

**Project:** LexaVault V1  
**Date:** 2026-08-31  
**Baseline:** Runtime-validated credit-card precision package  
**Scope:** Only the two explicitly requested production-readiness items: tenant-driven prompt composition and per-tenant AI provider/model routing.

## Runtime baseline preserved

Before this change, the user reran the real 61-page customer PDF and obtained:

- 61 native pages / 0 OCR pages;
- AI completed successfully with `gemma3:12b`;
- false page-31 credit-card candidate rejected as `INVALID_PAN_SHAPE`;
- final findings exactly `CREDIT_SCORE=1` on page 59;
- `DATE_OF_BIRTH=0`, `SSN=0`, `CREDIT_CARD_NUMBER=0`;
- one verified permanent redaction;
- local delivery success;
- package `COMPLETED`.

That precision behavior is treated as regression-protected baseline.

## Implemented item 1 — tenant-driven prompt composition

- `SensitiveContentAnalysisPort` now accepts a provider-neutral `AnalysisRequest` containing trusted tenant ID, normalized page text, and an `AnalysisPolicy`.
- `SensitiveDataDetectionService` derives `AnalysisPolicy` from the live `TenantRedactionPolicy`.
- New `SensitiveContentPromptBuilder` composes instructions only for enabled tenant categories and honors `maskSsnLastFour`.
- `OllamaSensitiveContentAnalysisAdapter` no longer contains customer category prompt rules.
- New shared `SensitiveContentResponseParser` keeps exact-page anti-fabrication checks and the validated credit-card PAN-shape guardrail provider-neutral.
- Development heuristic adapter now also respects enabled tenant categories.

## Implemented item 2 — per-tenant provider/model routing

- New `TenantRoutingSensitiveContentAnalysisPort` selects an explicitly deployment-configured tenant AI profile or the deployment default.
- New `OpenAiCompatibleSensitiveContentAnalysisAdapter` supports vLLM and customer-managed OpenAI-compatible chat-completions endpoints.
- Ollama remains a provider adapter; model is configuration. Gemma-to-Qwen does not require source changes.
- Added generic configuration names `SECUREDOC_AI_BASE_URL`, `SECUREDOC_AI_MODEL`, `SECUREDOC_AI_API_KEY`; existing local Ollama env names remain fallback compatible.
- Optional per-tenant profiles are bound under `securedoc.ai.tenant-profiles.<tenant-uuid>`.
- `SECUREDOC_AI_REQUIRE_TENANT_PROFILE=true` prevents an unconfigured tenant from falling through to the default provider; the request fails before document text is sent.
- Provider API keys are deployment-managed only and used only for Authorization headers. No UI/database secret store was introduced.
- `heuristic` cannot be configured as tenant provider or secondary provider.
- Platform Admin default AI health now understands Ollama and OpenAI-compatible/vLLM endpoints and reports only safe provider/model/profile-count information.

## Supported V1 provider transports

- `ollama`
- `openai-compatible`
- `openai_compatible`
- `vllm` (alias using OpenAI-compatible transport)
- `heuristic` only for explicit local/development default usage

A proprietary non-OpenAI-compatible customer protocol still requires a dedicated adapter, but no business-policy/detection/redaction changes.

## No schema change

No Liquibase migration was added. Expected total remains **35 changesets**.

## Focused self-checks completed in ChatGPT environment

PASS:

- Java 21 compile of updated domain/application analysis contract, resilience routing, prompt builder and heuristic adapter.
- Java 21 compile of Ollama/OpenAI-compatible adapters and shared response parser using dependency API stubs (syntax/signature verification).
- Java 21 compile of new focused test sources plus existing credit-card/page-retry tests using JUnit/dependency API stubs.
- Executable provider-neutral prompt/routing check:
  - CREDIT_SCORE-only prompt contains CREDIT_SCORE;
  - does not contain DOB/card/SSN instructions;
  - configured tenant routes to tenant provider;
  - other tenant routes to default when fallback is permitted.
- YAML parse of `application.yml` and `application-local.yml`.
- Static scan confirms `OllamaSensitiveContentAnalysisAdapter` contains no category-name prompt hardcoding.
- Static credential scan confirms API key is used only in configuration and Authorization-header construction; no log formatting contains key values.

Environment limitation:

- Maven is not installed in the ChatGPT runtime, and external Maven artifact download/DNS is unavailable. Therefore the authoritative full `mvn clean verify` must run on the user's machine/Kiro.
- Frontend source is unchanged by this delta; final frontend typecheck/build is still required on the user's machine as part of the normal full build.

## Required user/Kiro verification

1. `build-all.cmd` passes backend Maven reactor + frontend typecheck/Vite build.
2. Existing local Ollama `gemma3:12b` real 61-page regression remains exactly correct.
3. Focused prompt policy tests pass.
4. Controlled OpenAI-compatible endpoint test passes, including optional bearer header and no secret leakage.
5. Explicit tenant routing works for at least two tenant IDs/configurations.
6. `require-tenant-profile=true` fails closed and does not call default endpoint for an unconfigured tenant.
7. Existing credit-card precision and Ollama page-retry tests remain green.

## Scope deliberately not added

- No dynamic provider hot swap.
- No browser credential vault/editor.
- No Business Admin ownership of provider endpoint/model/credential.
- No PostgreSQL provider-secret storage.
- No custom proprietary provider SDK.
- No post-V1 semantic categories.
- No unrelated architecture refactor.

## Exact source/config/test files changed by this delta

- `README.md`
- `backend/securedoc-application/src/main/java/com/securedoc/platform/application/port/out/SensitiveContentAnalysisPort.java`
- `backend/securedoc-application/src/main/java/com/securedoc/platform/application/detection/service/SensitiveDataDetectionService.java`
- `backend/securedoc-application/src/main/java/com/securedoc/platform/application/resilience/ResilientSensitiveContentAnalysisPort.java`
- `backend/securedoc-application/src/main/java/com/securedoc/platform/application/resilience/TenantRoutingSensitiveContentAnalysisPort.java` (new)
- `backend/securedoc-ai/src/main/java/com/securedoc/platform/ai/analysis/SensitiveContentPromptBuilder.java` (new)
- `backend/securedoc-ai/src/main/java/com/securedoc/platform/ai/analysis/SensitiveContentResponseParser.java` (new)
- `backend/securedoc-ai/src/main/java/com/securedoc/platform/ai/analysis/OllamaSensitiveContentAnalysisAdapter.java`
- `backend/securedoc-ai/src/main/java/com/securedoc/platform/ai/analysis/OpenAiCompatibleSensitiveContentAnalysisAdapter.java` (new)
- `backend/securedoc-ai/src/main/java/com/securedoc/platform/ai/analysis/LocalHeuristicSensitiveContentAnalysisAdapter.java`
- `backend/securedoc-api/src/main/java/com/securedoc/platform/api/config/AiRuntimeProperties.java`
- `backend/securedoc-api/src/main/java/com/securedoc/platform/api/config/ApplicationServiceConfig.java`
- `backend/securedoc-api/src/main/java/com/securedoc/platform/api/platform/PlatformHealthService.java`
- `backend/securedoc-api/src/main/resources/application.yml`
- `backend/securedoc-api/src/test/java/com/securedoc/platform/api/policy/TenantAiPolicyAndRoutingTest.java` (new)
- `backend/securedoc-api/src/test/java/com/securedoc/platform/api/policy/OpenAiCompatibleAnalysisAdapterTest.java` (new)
- `docs/continuity/*` current master/acceptance/prompt/self-review/config-guide updates
