# Production AI Inference Tier R3 — Saturation Classification Hardening

**Date:** 2026-09-11  
**Status:** IMPLEMENTED CANDIDATE / ONE FOCUSED LOCAL RERUN REQUIRED

## Evidence carried from R2 runtime validation

- distinct real+mock endpoints both became healthy and received routed work: **PASS**;
- dead node + healthy real node continued through the healthy node: **PASS**;
- all configured nodes down emitted `ALL_NODES_UNHEALTHY`, created a structured AI incident and failed closed without model substitution/bypass: **PASS**;
- saturation/backpressure was bounded, but the second request was misclassified as `ALL_NODES_UNHEALTHY` while the only node was actively leased: **classification finding**.

## Root cause

The request-path health refresh could synchronously re-probe an already-healthy node after the health interval elapsed even while that node had active leased inference work. With a serialized provider/test endpoint, the readiness probe could time out behind the active inference request and mark the node `UNHEALTHY`. A competing request then reported `ALL_NODES_UNHEALTHY` instead of capacity exhaustion.

## R3 correction

1. Do not synchronously refresh health for an already-healthy node while `inflight > 0`.
2. Let the active inference completion/failure update liveness/circuit state authoritatively.
3. Keep the existing bounded permit/queue deadline unchanged, so a competing request still terminates with `QUEUE_TIMEOUT` when capacity does not free.
4. Make the local mock server concurrent so health checks and delayed chat requests do not serialize artificially.
5. Add a focused regression test that would have failed under R2 by returning 503 from a second `/api/tags` probe while the first chat request is active.

## Scope discipline

No change to `qwen3.5:4b`, page-level AI semantics, failover-eligible failure codes, downstream redaction/review/export behavior, tenant isolation, scanner functionality, or the later full performance workstream.
