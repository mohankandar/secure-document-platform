# LexaVault Production AI Inference Tier R2

**Candidate:** R2 hardening / focused local validation

R1 build and one-real-node runtime validation passed. R2 keeps the routing design intact and fixes the evidence/test-topology gaps found during local testing.

## R2 changes

- readable local logs expose `aiNode`, `attempt/maxAttempts`, `inflight`, `healthy`, and `reason`;
- explicit `AI_INFERENCE_POOL_UNAVAILABLE` and `AI_INFERENCE_POOL_EXHAUSTED` events;
- explicit no-dispatch reasons: `ALL_NODES_UNHEALTHY`, `POOL_SATURATED`, `QUEUE_TIMEOUT`;
- duplicate loopback aliases such as `127.0.0.1:11434` + `localhost:11434` are rejected as fake independent capacity;
- focused tests added for loopback alias rejection and explicit pool failure reasons;
- `Start-Mock-Ollama-Node.ps1` added for deterministic SUCCESS/HTTP503/DELAY/WRONG_MODEL local tests without a second GPU;
- documentation synchronized to R2.

## Fast validation

1. Run the normal build once.
2. Re-run the one-real-node pool smoke test only if desired; R1 already passed it.
3. Use a real node + `Start-Mock-Ollama-Node.ps1` on port 11435 for distinct-endpoint routing/failure checks.
4. Use `-LoggingFormat Readable` and capture the `AI_INFERENCE_*` events; node/capacity/reason fields are now visible.

Do not use `localhost` and `127.0.0.1` on the same port as two pool members.
