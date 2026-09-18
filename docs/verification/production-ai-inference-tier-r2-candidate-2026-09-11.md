# Production AI Inference Tier R2 — Hardening Candidate

**Date:** 2026-09-11  
**Status:** IMPLEMENTED CANDIDATE / LOCAL VALIDATION REQUIRED

## Evidence carried from R1

- repository build passed on the developer workstation;
- one-real-node pool mode processed a document through `AI_INFERENCE_NODE_SELECTED` -> `AI_INFERENCE_NODE_SUCCEEDED` -> normal LexaVault processing;
- a same-process alias topology (`127.0.0.1:11434` and `localhost:11434`) was determined not to be valid HA evidence;
- dead+good-node testing showed bounded-capacity behavior but readable logs did not expose enough node/capacity fields to classify every failure unambiguously.

## R2 changes

1. Readable local logging now includes AI node ID, attempt/max attempts, inflight count, healthy-node count and reason code.
2. Pool dispatch failures emit explicit `AI_INFERENCE_POOL_UNAVAILABLE` and terminal `AI_INFERENCE_POOL_EXHAUSTED` events.
3. No-dispatch reasons are classified as `ALL_NODES_UNHEALTHY`, `POOL_SATURATED`, or `QUEUE_TIMEOUT`.
4. Pool configuration rejects loopback aliases on the same endpoint as fake independent capacity.
5. Focused tests cover the explicit unhealthy and queue-timeout reason paths plus duplicate loopback aliases.
6. `Start-Mock-Ollama-Node.ps1` supports deterministic SUCCESS/HTTP503/DELAY/WRONG_MODEL local endpoint behavior without a second GPU.

## Scope discipline

R2 does not change the frozen model, redaction semantics, tenant boundary, OCR behavior, Document Intelligence policy, or final performance/throughput workstream.
