# LexaVault Documentation

This directory separates **current product/architecture truth** from **historical implementation evidence**.

## Read first

1. `continuity/CURRENT_STATE.md` — concise current project state and baseline.
2. `continuity/DEVELOPMENT_WORKING_AGREEMENT.md` — ChatGPT/Kiro development and verification rules.
3. `product/V1_SCOPE.md` — current V1 product boundary.
4. `product/AUTHORIZATION.md` — canonical roles, capabilities, identity transition rules, and authorization semantics.
5. `product/WORKFLOW_AND_LIFECYCLE.md` — intake, processing, review, delivery, reprocessing, technical retry, and retention semantics.
6. `product/REPORTING.md` — current Executive/Operational report definitions and access boundary.
7. `product/INTEGRATIONS.md` — frozen V1 integration architecture, push/pull/SDV/split requirements, and implementation sequencing.
8. `architecture/06-core-automatic-redaction-pipeline.md` — AI-first document processing architecture.
9. `LEXAVAULT_V1_MASTER_WORK_ITEM_MATRIX.md` — canonical current roadmap/status matrix; update after each accepted verification checkpoint.

## Current technical references

- `architecture/` — architecture and ADRs.
- `security/IDENTITY_AND_TENANCY.md` — production identity, tenant resolution, and fail-closed rules.
- `operations/` — build, LOCAL run, AI/OCR configuration, and safe logging.
- `api/README.md` — API-area map and authorization boundary.
- `api/EXTERNAL_INGESTION_V1.md` — current manifest-first machine push-ingestion contract.
- `api/POLLING_INGESTION_V1.md` — accepted scheduled polling/pull-ingestion contract.
- `api/SDV_DELIVERY_V1.md` — current Requirement 3 LexaVault→SDV delivery candidate contract.
- `testing/V1_ACCEPTANCE.md` — accepted verification evidence and future regression expectations.
- `testing/INTEGRATION_PUSH_API_KIRO_VERIFICATION.md` — historical focused checkpoint for accepted Requirement 1.
- `testing/INTEGRATION_POLLING_KIRO_VERIFICATION.md` — historical focused checkpoint for accepted Requirement 2.
- `testing/INTEGRATION_SDV_KIRO_VERIFICATION.md` — focused local/Kiro checkpoint for Requirement 3.
- `architecture/MULTI_NODE_WATCHED_FOLDER_COORDINATION.md` — active enterprise multi-node watched-folder lease/claim/failover design.
- `scanner-bridge/SCANNER_BRIDGE_V1_SB123.md` — Scanner Bridge SB-1/2/3 foundation and boundary carried into the active end-to-end scanner candidate.
- `scanner-bridge/SCANNER_INTAKE_END_TO_END.md` — frozen Scanner Intake end-to-end contract; software verified with physical hardware certification pending.
- `testing/SCANNER_INTAKE_E2E_LOCAL_TEST.md` — Scanner R5 simulator/physical certification checklist.
- `testing/PRODUCTION_AI_INFERENCE_TIER_LOCAL_TEST.md` — active R2 focused local inference-pool validation checklist.
- `verification/scanner-e2e-r4-fix1-fix2-2026-09-10.md` — historical R4 Start Scan browser-preflight fix and unified local-startup checkpoint.
- `verification/scanner-e2e-r5-physical-wia-candidate-2026-09-11.md` — Scanner R5 software verification and remaining real-hardware certification gate.
- `testing/MULTI_NODE_WATCHED_FOLDER_LOCAL_TEST.md` — three-JVM local HA/failover acceptance procedure.
- `testing/SCANNER_BRIDGE_SB123_LOCAL_TEST.md` — local software validation for Scanner Bridge SB-1/2/3 using the scanner simulator.
- `verification/scanner-bridge-sb123-kiro-2026-09-09.md` — focused Kiro SB-1/2/3 PASS WITH MINOR FINDINGS evidence and required closure items.

## Historical records

`archive/` contains prior unit plans, implementation notes, acceptance prompts, superseded role models, one-time runtime corrections, and verification history. Historical files explain how the implementation evolved, but they **must not override current documents**.

Precedence for active work:

1. current source code and database migrations;
2. current files linked above;
3. active `.kiro/steering` requirements;
4. archived records only for historical context.

## Current production-hardening unit — 2026-09-11

Business / Operational Reporting R3 FIX5 is VERIFIED / FROZEN. The active unit is Production Performance / Throughput / Concurrency Optimization R1 baseline instrumentation. R1 adds measurement only; tuning follows measured evidence.
