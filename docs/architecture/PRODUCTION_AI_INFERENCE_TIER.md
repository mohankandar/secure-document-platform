# Production AI Inference Tier — HA, Load Balancing & Horizontal Scaling

**Status:** R3 SATURATION-CLASSIFICATION HARDENING CANDIDATE / ONE FOCUSED LOCAL RERUN REQUIRED  
**Date:** 2026-09-11

## Goal

Decouple LexaVault application nodes from one fixed AI endpoint while preserving the already-frozen AI/redaction semantics. V1 must support one inference node for small on-prem installs, two or more nodes for HA, and N homogeneous nodes for horizontal capacity growth without introducing Kubernetes or a new mandatory inference microservice.

## Boundary

```text
LexaVault app node(s)
        |
        v
SensitiveContentAnalysisPort
        |
        v
OllamaInferencePoolSensitiveContentAnalysisAdapter
  | health/model readiness
  | least-inflight + rotating ties
  | bounded permits / bounded wait
  | failover / circuit cooldown
  +-------------------------------+
          |            |          |
          v            v          v
       AI node 1    AI node 2  AI node N
       exact model  exact model exact model
```

The pool lives behind the existing provider-neutral application port. Domain/application processing remains unaware of node topology. Single-endpoint configuration remains the default and continues to instantiate the existing Ollama adapter directly.

## R1/R2/R3 decisions

- Provider: pool mode is currently Ollama-only. OpenAI-compatible/vLLM remains supported as the existing single endpoint or explicit secondary provider.
- Model safety: every pool node must expose the exact configured model name through `/api/tags`. A node with another model is excluded before customer text is sent.
- Routing: eligible nodes are ordered by least in-flight work; round-robin rotation breaks ties so healthy nodes share work.
- Concurrency: each node has a bounded document-level permit count. Existing page-batch parallelism remains independently bounded inside the node adapter.
- Backpressure: when every healthy node is at capacity, callers wait only for `pool-queue-wait-ms`; no unbounded internal queue is created.
- Failover: infrastructure failures (`AI_UNAVAILABLE`, `AI_TIMEOUT`, `AI_RATE_LIMITED`) may move the request to another untried healthy node. Content/provider rejection and invalid-response failures do not silently move across nodes.
- Retry budget: lower-level page attempts remain bounded. Once a node has exhausted targeted infrastructure attempts, the pool may try another node. If the pool itself exhausts after dispatching work, it marks the failure non-retryable so the outer document resilience decorator does not replay the entire document again.
- Circuit behavior: a node that fails an eligible infrastructure request is temporarily removed from routing. After cooldown, the next eligible request performs a content-free health/model probe; a recovered node rejoins automatically.
- Health: application Platform health reports aggregate configured/reachable/model-ready node counts. Runtime routing maintains per-node health internally and emits privacy-safe node-ID events.
- Preload: startup model preload warms all configured pool members but fail-closed startup requires at least one ready node, not every node.
- Observability: logs may include provider, model, logical inference node ID, attempts, durations, in-flight counts, healthy-node counts and reason codes. They must not include prompts, OCR text, provider responses or protected values.

## On-prem ownership and SaaS direction

For V1 on-prem deployments:

- `PLATFORM_OPERATIONS_ADMIN` enables/governs the capability and platform-supported provider/model guardrails.
- `TENANT_IT_ADMIN` owns the tenant/deployment node endpoints and day-to-day inference infrastructure operation.
- Business Admin continues to own business redaction policy, not infrastructure endpoints.

R1 configuration remains deployment-managed rather than browser-secret-managed. `AiRuntimeProperties.TenantProfile` can independently enable/configure a pool, keeping the code boundary ready for future SaaS where Platform Operations may run shared regional pools and a tenant may be assigned shared or dedicated inference capacity.

## Failure behavior

| Condition | Required behavior |
|---|---|
| One node down, another ready | Route/fail over to a ready node |
| Node reachable but required model missing | Exclude node; never substitute another model |
| Node fails during inference | Open its circuit; try another node only for eligible infrastructure failures |
| Node recovers | Re-probe after cooldown and return to rotation |
| Every ready node saturated | Wait only for bounded queue interval, then controlled AI dependency failure |
| Every node unavailable/not-ready | Existing technical-failure/manual-continuity path; no silent AI bypass |
| Pool disabled | Preserve existing single-endpoint behavior |

## R2 hardening

R1 local validation proved the new pool path with one real Ollama node. R2 does not redesign routing. It tightens operational correctness and evidence:

- readable local logs now show `aiNode`, attempt/maxAttempts, inflight count, healthy-node count and reason code;
- pool dispatch failure is classified explicitly as `ALL_NODES_UNHEALTHY`, `POOL_SATURATED`, or `QUEUE_TIMEOUT`;
- localhost and loopback aliases on the same scheme/port/path are rejected as duplicate capacity, preventing one physical Ollama process from being counted as two HA nodes;
- `Start-Mock-Ollama-Node.ps1` provides a controlled independent local endpoint for SUCCESS/HTTP503/DELAY/WRONG_MODEL routing tests without requiring a second GPU.

## R3 saturation-classification hardening

R2 local runtime evidence passed distinct-endpoint distribution, dead+good failover and all-nodes-down fail-closed behavior. The remaining saturation run showed that a competing request could synchronously re-probe an already-healthy node while it was actively leased. If that readiness probe timed out behind active inference, the node could be falsely demoted and the second request classified as `ALL_NODES_UNHEALTHY`. R3 treats an already-healthy node with active leased work as capacity-constrained: request-path health refresh is deferred until the active lease completes or fails, while the existing bounded queue deadline remains unchanged. The local mock endpoint is also concurrent so delayed chat traffic does not block content-free readiness probes.

## Deliberately outside R1/R2/R3

- Kubernetes/GPU autoscaling or dynamic machine provisioning;
- throughput/load/soak optimization and tenant-fair scheduling (final Performance workstream);
- model re-selection or benchmark changes;
- automatic model fallback;
- browser editing of infrastructure credentials;
- cloud-provider-specific inference orchestration.

## Acceptance gate

Before this inference-tier unit is frozen, targeted local evidence must prove single-node compatibility, 2-node distribution, one-node failure/failover, wrong-model exclusion, bounded saturation, recovered-node rejoin, pool-aware startup/health and no regression of the provider-neutral tenant routing boundary. Broad OCR/redaction/HA suites are not required unless a targeted failure indicates regression.
