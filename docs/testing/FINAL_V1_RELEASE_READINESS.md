# LexaVault V1 Final Release-Readiness Verification

## Purpose

This is the final V1 software release gate after all construction/performance work. It is a verification phase, not a new implementation unit.

The default rule is **reuse accepted evidence unless the final candidate changed the relevant path**. The objective is to catch integration or packaging regressions without spending time rerunning long, already-frozen scenarios.

## Accepted evidence reused by this gate

| Area | Accepted evidence entering final gate |
|---|---|
| Core PDF/OCR/redaction/manual review | Frozen unit verification including precision and artifact-quality fixes |
| Push/pull/watched-folder intake | Frozen runtime verification and golden-path matrix |
| Derived routing | Frozen deterministic routing + delivery evidence; R2A native-text fast path accepted |
| Security / tenancy / authorization | Frozen OIDC trust-boundary, role/capability, tenant-isolation and support-console verification |
| Scanner software | Frozen simulator + physical-WIA software path; real target hardware still pending |
| Production AI tier | Frozen HA/load-balancing/fail-closed/saturation verification |
| Reporting | Frozen R3 FIX5 verification |
| Performance | R2A + R2C accepted; R2B rejected; R3 scale/soak/FIX4 recovery frozen |
| Package-processing HA | FIX4 controlled node-loss run: 49/49, explicit fencing-token 1→2 takeover/replay |

## Final one-pass gate

### A. Clean build and source integrity — required

1. Full backend Maven build/tests through repository build script.
2. Frontend TypeScript + Vite production build.
3. Frontend authorization test suite.
4. Frozen V1 evidence baseline contained exactly 64 changeSets through `027-package-processing-ha.xml`; the current post-V1 frozen Tenant Licensing baseline contains 70 changeSets through `029-tenant-licensing.xml`.
5. Required frozen implementation files are present.
6. No generated build/cache/performance-result directories are present in the release archive.

### B. Representative LOCAL runtime preflight — required

With the final candidate running through `start-local.cmd`:

1. `/actuator/health` returns `UP`.
2. `/api/v1/me` resolves a canonical LOCAL `DOCUMENT_PROCESSOR` identity and capability set.
3. An unprovisioned tenant identifier is rejected (expected HTTP 403).
4. `qwen3.5:4b` is installed and available through the local Ollama endpoint.
5. Scanner Bridge health is `UP` and configuration endpoint responds.
6. No startup migration checksum error, bean ambiguity, or dependency-preload failure appears.

### C. Regression evidence review — required, usually no rerun

Confirm the final source is the same accepted product behavior for:

- R2A native-text derived-routing fast path and unchanged raster/QR/barcode fallback;
- R2C page-batch 4 / parallelism 2 / 14k-character LOCAL reference profile;
- R3 FIX4 DB-time package-processing lease/heartbeat/fencing/recovery;
- Reporting R3 FIX5 semantics;
- production OIDC trust boundary and canonical roles;
- redacted-by-default access and protected original access;
- structured logging privacy restrictions.

If source changes after the frozen evidence touch one of these areas, rerun only that affected scenario plus the full build.

## Blocking failures

Any of the following blocks `V1_RELEASE_CANDIDATE_APPROVED`:

- backend or frontend build/test failure;
- Liquibase checksum/migration failure or unexpected source changeSet count;
- tenant-isolation or authorization regression;
- raw sensitive values, OCR text, prompts/model responses, secrets or credentials exposed in normal logs/audit/admin payloads;
- redaction/export safety regression;
- package-processing HA regression that can orphan accepted work;
- AI fail-open behavior where the frozen design requires fail-closed/manual continuation;
- required integration/runtime smoke cannot start on the reference environment.

## Non-blocking / deployment gates

These do not silently become product PASS items. Track them explicitly outside the software release verdict:

- real target WIA scanner paper/ADF/duplex certification;
- customer PostgreSQL backup/restore/DR and HA implementation;
- customer shared-storage resilience and backup policy;
- production secrets/certificates/service accounts/DNS/firewall configuration;
- customer monitoring/log shipping/alerting setup;
- customer-specific install/upgrade/rollback and air-gap procedure validation where required;
- frozen Reporting minor backlog items.

## Release decision language

Use exactly one software verdict after final evidence review:

- `V1_RELEASE_CANDIDATE_APPROVED`
- `V1_RELEASE_BLOCKED`

If approved, separately state whether customer production deployment gates are complete or still pending. Do not conflate software release-candidate approval with site go-live approval.
