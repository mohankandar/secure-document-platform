# LexaVault Production AI Inference Tier R3

**Candidate:** R3 saturation-classification hardening / one focused local rerun required

R2 runtime validation passed distinct-endpoint distribution, dead+good failover, and all-nodes-down fail-closed behavior. The final saturation test exposed one edge case: while a node was legitimately busy, a competing request could trigger a stale `/api/tags` probe and falsely demote that active node to `UNHEALTHY`, producing `ALL_NODES_UNHEALTHY` instead of bounded `QUEUE_TIMEOUT` / `POOL_SATURATED` classification.

## R3 changes

- an already-healthy node with active leased inference work is not synchronously re-probed by a competing request;
- the active inference result remains the authoritative liveness signal until its lease succeeds or fails;
- a focused regression test reproduces a busy node whose readiness endpoint would fail during active inference and requires `QUEUE_TIMEOUT` classification rather than false unhealthy demotion;
- `Start-Mock-Ollama-Node.ps1` now handles connections concurrently, so `/api/tags` remains responsive while a DELAY `/api/chat` request is in flight;
- no model, routing algorithm, retry budget, redaction semantics, tenant boundary, or downstream workflow behavior changed.

## Validation required

Run the normal repository build once, then rerun **only the saturation/backpressure scenario** from `docs/testing/PRODUCTION_AI_INFERENCE_TIER_LOCAL_TEST.md`.

Expected second-request evidence:

```text
AI_INFERENCE_POOL_UNAVAILABLE ... inflight=1 ... reason=QUEUE_TIMEOUT
AI_INFERENCE_POOL_EXHAUSTED ... inflight=1 ... reason=QUEUE_TIMEOUT
```

`POOL_SATURATED` is acceptable only when the configured queue wait is zero. `ALL_NODES_UNHEALTHY` is not acceptable for this busy-node scenario.

Do not rerun the already-passed R2 distribution, dead+good, or all-down cases unless the build reveals an unrelated regression.
