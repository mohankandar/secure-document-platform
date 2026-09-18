# Scripts

Build and utility scripts for the SecureDoc Platform.

## Production Performance R1 baseline

- `Capture-LexaVault-PerformanceEnvironment.ps1` captures machine/JVM/Ollama/GPU capability metadata only.
- `Invoke-LexaVault-PerformanceBaseline.ps1` creates LOCAL/demo test packages through the public package API at controlled concurrency levels, waits for automatic terminal/pause states, and records client latency plus CPU/memory/GPU samples.
- `Summarize-LexaVault-PerformanceBaseline.ps1` produces per-concurrency latency/resource summaries and can optionally extract queue/executor/stage structured events from a rolling JSON application log. For R2C it also writes `ai-batch-efficiency-summary.csv` with configured batch size, batch/provider-call counts, effective pages per batch/call, and aggregate Ollama token/timing metrics.

These scripts are for non-production benchmark data only. Never use customer documents or protected production values in performance runs.


## Production Performance R2A rerun

After building R2A, reuse the same R1 baseline harness and the same native-text test document/concurrency matrix. Compare `DERIVED_ROUTING_DETECTION_COMPLETED` median/P95 against the R1 FIX1 reference. Do not combine R2A validation with worker-pool or AI-concurrency changes.

## Production Performance R3 — multi-node/load/soak

- `Start-LexaVault-PerformanceCluster.ps1` — starts the 3-node local backend performance cluster on 10080/10081/10082 with shared PostgreSQL/storage/Ollama and per-node JSON logs.
- `Stop-LexaVault-PerformanceCluster.ps1` — stops all performance-cluster app processes.
- `Stop-LexaVault-PerformanceClusterNode.ps1` / `Start-LexaVault-PerformanceClusterNode.ps1` — controlled one-node removal/restart for R3 recovery evidence.
- `Invoke-LexaVault-PerformanceCluster.ps1` — cross-node scale/overload harness; intentionally rotates create/upload/submit/poll across nodes.
- `Summarize-LexaVault-PerformanceCluster.ps1` — produces cluster throughput, node distribution, resource, queue, stage and AI summaries.
- `Invoke-LexaVault-PerformanceSoak.ps1` — fixed-concurrency soak with node-health and resource sampling; health-aware for new work.
- `Summarize-LexaVault-PerformanceSoak.ps1` — summarizes soak outcomes, throughput, resource envelope, node availability and early-vs-late latency drift.
- `New-LexaVault-ProductionSizingGuide.ps1` — generates measured sizing guidance and an optional capacity ratio when a real peak package/minute target is supplied.

See `PRODUCTION_PERFORMANCE_R3_MULTI_NODE_LOAD_SOAK_README.md` before running R3. Do not run the normal `start-local.cmd` backend at the same time as the R3 performance cluster if you want clean resource evidence.

## Final V1 release-readiness

`Invoke-LexaVault-FinalReleasePreflight.ps1` is the consolidated final-gate runner. It validates frozen source invariants, the Liquibase source count, full repository build, frontend authorization tests, and optionally a small representative LOCAL runtime smoke. It deliberately does not rerun accepted long-duration performance or historical security suites unless source changes invalidate that evidence.
