LexaVault — Production AI Inference Tier R1 Build Candidate
Date: 2026-09-11
Status: IMPLEMENTED CANDIDATE / LOCAL VALIDATION REQUIRED

Purpose
-------
Adds an on-prem-first, SaaS-ready homogeneous Ollama inference pool behind the
existing provider-neutral SensitiveContentAnalysisPort.

R1 includes:
- 1/N static inference-node configuration (stable logical node IDs)
- exact configured-model readiness checks through Ollama /api/tags
- least-inflight routing with rotating ties
- bounded per-node document concurrency and bounded queue wait
- failover for AI_UNAVAILABLE / AI_TIMEOUT / AI_RATE_LIMITED only
- bounded node-attempt budget (no infinite cross-node retries)
- unhealthy-node circuit cooldown and automatic healthy rejoin
- aggregate Platform Health for configured/reachable/model-ready nodes
- pool-aware startup model preload (at least one node ready is sufficient)
- privacy-safe inference-node operational logging
- tenant-profile pool configuration boundary for future shared/dedicated SaaS pools
- single-endpoint behavior remains the default when pool-enabled=false

Important safety boundaries
---------------------------
- No silent model substitution. Every usable node must expose the exact configured model.
- Pool failover does not replace content/invalid-response failures with another model/provider.
- Existing explicit secondary-provider behavior remains separate from node-level HA.
- No Kubernetes, GPU autoscaler, dynamic provisioning, or performance/load tuning in R1.
- AI prompts, OCR text, provider responses, and protected document values are not added to logs.

Operational ownership
---------------------
- PLATFORM_OPERATIONS_ADMIN: enables/governs the platform capability and supported guardrails.
- TENANT_IT_ADMIN: operates tenant/deployment inference endpoints within those guardrails.
- BUSINESS_ADMIN: business policy only; no inference infrastructure endpoints.

Build
-----
From repository root:

    .\build-all.cmd

This candidate was statically reviewed in the packaging environment, but Maven is not
available there. The developer's Windows build is the build gate.

Backward-compatible local smoke
-------------------------------
With no pool environment variables, start normally:

    .\start-local.cmd

Expected: the existing single Ollama endpoint on localhost:11434 remains in use.

Two-logical-node local validation
---------------------------------
No second GPU/server is required. Start a second Ollama-compatible endpoint separately
(for example another Ollama process on 11435 or a controlled local test endpoint), then:

    $env:SECUREDOC_AI_POOL_ENABLED="true"
    $env:SECUREDOC_AI_POOL_NODE_URLS="ai-1=http://127.0.0.1:11434,ai-2=http://127.0.0.1:11435"
    $env:SECUREDOC_AI_POOL_MAX_CONCURRENT_PER_NODE="1"
    $env:SECUREDOC_AI_POOL_MAX_NODE_ATTEMPTS="2"
    .\start-local.cmd

The normal local launcher still bootstraps/warmups localhost:11434. Additional pool endpoints
are intentionally externally managed so local testing does not introduce a production-style
orchestration subsystem.

Key documentation
-----------------
- docs/architecture/PRODUCTION_AI_INFERENCE_TIER.md
- docs/testing/PRODUCTION_AI_INFERENCE_TIER_LOCAL_TEST.md
- docs/operations/AI_PROVIDER_CONFIGURATION.md
- docs/LEXAVAULT_V1_MASTER_WORK_ITEM_MATRIX.md
- docs/continuity/CURRENT_STATE.md

Scanner baseline
----------------
Scanner-E2E R5 is now documented as VERIFIED / FROZEN (SOFTWARE), with real physical
scanner hardware certification retained as a deployment gate.
