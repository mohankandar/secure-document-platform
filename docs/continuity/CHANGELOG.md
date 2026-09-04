
# LexaVault V1 — Milestone Changelog

This is a concise milestone history. Detailed implementation and verification records are preserved under `../archive/`.

## 2026-09-03 — Requirement 5 local AI performance + final hardening candidate

- Requirement 5 R2: added in-application Ollama model preload with fail-closed LOCAL behavior, an explicit `local-qwen` benchmark overlay, and `Start-LexaVault-Local.ps1 -AiModel` support after Kiro showed Gemma3:12b exceeded the benchmark laptop VRAM/performance target.
- Kiro R2 benchmark accepted `qwen3.5:4b` as the V1 LOCAL/demo reference model on the current 6 GB VRAM laptop; `gemma3:12b` remains an explicit larger-hardware option with no silent fallback.
- Added Ollama startup preload/30-minute keep-alive for LOCAL startup.
- Added compact tenant-policy prompt + JSON-schema structured output and `think=false`.
- Added configurable `num_ctx`, `num_predict`, page-batch size and max batch-character safety cap at deployment and tenant profile levels.
- LOCAL defaults batch two moderate pages per provider call while retaining exact page-number validation; oversized pages are isolated rather than truncated.
- Added safe Ollama performance telemetry: batches/provider calls, load duration, prompt/output tokens/evaluation durations and output tokens/sec.
- Added benchmark summary script and benchmark/quality-gate protocol.
- Added focused performance-profile test while preserving existing retry and credit-card precision contracts.

- Final hardening candidate promotes Qwen to the actual `local`/launcher default, adds safe malformed-body and technical-dependency API mappings, preserves polling partial-retrieval counts, and adds focused polling/SDV/derived-routing regression tests.
- Added dependency-free final-runtime fixtures for the carried-forward marker → derived PDF → HTTP/FILE_SHARE regression closure.

| Date | Milestone | Status |
| --- | --- | --- |
| 2026-08-29 | Unit 16 — AI-first digital PDF redaction | Approved |
| 2026-08-29 | Unit 17 — scanned/mixed PDF OCR | Approved |
| 2026-08-29 | Unit 18 — confidence + exception handling | Approved |
| 2026-08-29 | Unit 19 — focused Manual Review | Approved |
| 2026-08-29 | Unit 20 — Business Record + unified intake | Approved |
| 2026-08-29 | Unit 21 — Business Admin configuration | Approved |
| 2026-08-29 | Unit 22 — delivery + lifecycle/retention/reprocess | Approved |
| 2026-08-30 | Unit 23 — shared Dashboard/reporting foundation | Approved |
| 2026-08-30 | Unit 24 — tenant/platform technical administration | Approved |
| 2026-08-30 | Unit 25 — production identity/platform hardening | Approved |
| 2026-08-31 | Pre-acceptance reliability/precision/runtime corrections | Accepted into baseline |
| 2026-09-01 | Batch 2 — one-page multi-PDF intake | Accepted |
| 2026-09-01 | Batch 3 — breadcrumb/responsive navigation polish | Accepted |
| 2026-09-01 | Batch 4 — Executive + Operational Reports | Accepted |
| 2026-09-01 | Role/capability simplification | Accepted; manual + independent + adversarial verification passed |
| 2026-09-01 | Documentation consolidation | Current documentation separated from historical archive |
| 2026-09-03 | Integration Unit 1 foundation + manifest-first Push API | Accepted; full Kiro Maven/Liquibase/runtime/API verification PASS |
| 2026-09-03 | Requirement 2 scheduled polling / pull ingestion | Accepted; Kiro runtime verification PASS WITH MINOR FINDINGS, one-line Spring constructor wiring fix folded into baseline |
| 2026-09-03 | Requirement 3 redacted master delivery to SDV | Accepted; Kiro PASS WITH MINOR FINDINGS, no source changes |
| 2026-09-03 | Requirement 4 derived split/routing | Accepted; Kiro PASS WITH MINOR FINDINGS, no source changes |
| 2026-09-03 | Requirement 5 local AI performance | Accepted; Qwen3.5:4b benchmark PASS, no source changes |
| 2026-09-03 | Final V1 hardening/regression | Candidate; no new product scope |

The archive is historical evidence, not an alternative current specification.
