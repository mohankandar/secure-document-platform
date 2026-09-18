# Production AI Inference Tier R3 — Focused Local Test

**Status:** R3 saturation-classification hardening candidate  
**Purpose:** prove inference-pool behavior without rerunning the full frozen LexaVault regression suite.

## Build gate

Run the normal repository build once. The candidate adds focused tests for:

- healthy two-node distribution;
- failover after node-local infrastructure retries are exhausted;
- exact-model mismatch exclusion before customer text is sent;
- bounded saturation/queue behavior;
- failed-node cooldown and rejoin;
- pool configuration parsing;
- pool startup preload where one configured node is unavailable and another is ready.

## Single-node compatibility

Leave pool mode disabled and run the existing LOCAL profile. Expected behavior is unchanged: `qwen3.5:4b` at the normal single endpoint remains the LOCAL reference.

## Two logical local endpoints

A second GPU is not required for routing/failover verification. Do **not** configure `localhost:11434` and `127.0.0.1:11434` as two nodes; R2/R3 rejects that because it is one physical Ollama process, not independent capacity. If two real Ollama processes are practical, expose them on different ports. Otherwise use the provided controlled mock endpoint for the second logical node. Example topology:

```text
LexaVault -> ai-1=http://127.0.0.1:11434
          -> ai-2=http://127.0.0.1:11435
```

Before `start-local.cmd`, configure:

```powershell
$env:SECUREDOC_AI_POOL_ENABLED="true"
$env:SECUREDOC_AI_POOL_NODE_URLS="ai-1=http://127.0.0.1:11434,ai-2=http://127.0.0.1:11435"
$env:SECUREDOC_AI_POOL_MAX_CONCURRENT_PER_NODE="1"
$env:SECUREDOC_AI_POOL_MAX_NODE_ATTEMPTS="2"
```

For the controlled endpoint, open a second PowerShell window and run, for example:

```powershell
.\Start-Mock-Ollama-Node.ps1 -Port 11435 -Model qwen3.5:4b -Mode SUCCESS
```

Other deterministic modes are `HTTP503`, `DELAY`, and `WRONG_MODEL`. This helper is only for routing/failure evidence; it is not evidence for model quality, which is already frozen by Requirement 5.

With `-LoggingFormat Readable`, R3 should expose `aiNode`, `attempt`, `inflight`, `healthy`, and `reason` directly on inference-pool log lines.


## R3 one-test validation rule

R2 already passed distinct-endpoint distribution, dead+good failover, and all-nodes-down fail-closed behavior. R3 changes only busy-node health/saturation classification plus the concurrency of the local mock. After the build, rerun only the saturation scenario unless another regression is observed.

Start the concurrent delayed mock:

```powershell
.\Start-Mock-Ollama-Node.ps1 -Port 11435 -Model qwen3.5:4b -Mode DELAY -DelaySeconds 20
```

Start LexaVault with one permit and a 5-second queue wait, then submit two small PDFs close together. The first request should hold `ai-slow`; the second should remain bounded and end with `reason=QUEUE_TIMEOUT` rather than `ALL_NODES_UNHEALTHY`. A busy healthy node is capacity-constrained, not automatically unhealthy.

## High-value runtime checks

1. Both nodes expose the exact configured model; several AI requests are distributed across node IDs in privacy-safe logs.
2. Stop/disable one node; new inference continues through the remaining ready node.
3. Restore the failed node, wait beyond circuit cooldown/health interval, and confirm it rejoins rotation.
4. Make one node expose a different model name; confirm it is excluded and receives no document text.
5. With node concurrency constrained, occupy all capacity and confirm a second request does not create unbounded work; it waits only for the configured queue interval and then fails safely if capacity never frees.
6. Stop every node; confirm LexaVault follows the existing technical AI failure/manual-continuity behavior and never silently switches models or bypasses AI.
7. Platform technical health with probing enabled should show ready/reachable node counts rather than treating one failed pool member as total AI outage when another model-ready node exists.

## Evidence discipline

Reuse the successful normal build and previously frozen OCR/redaction/tenant-isolation evidence. Kiro/local-machine verification should be used only for runtime/process/GPU/network evidence that cannot be established from source and focused tests. Do not rerun broad HA, scanner, OCR precision, Document Intelligence or full performance suites for this checkpoint.
