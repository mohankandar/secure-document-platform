# LexaVault V1 — Requirement 3 SDV Delivery Verification

**Status:** Candidate verification contract. Verify only; do not redesign/refactor. Kiro may fix only obvious build/startup wiring blockers and must report any functional/security/architecture issue before modifying it.

## High-level scope

Verify that the current Requirement 3 candidate:

- keeps normal `local` profile on local-filesystem master delivery;
- uses explicit `local-sdv` / non-local SDV delivery with no silent local fallback;
- allows only one enabled tenant SDV destination;
- sends one SDV submission per LexaVault export cycle;
- sends only redacted PDFs plus minimum business/integration metadata;
- uploads documents independently with stable source IDs, idempotency and SHA-256;
- persists partial SDV acknowledgements and resumes only missing documents after a retry;
- marks LexaVault `EXPORTED` only after SDV reports the submission `COMPLETE`;
- does not start post-export retention while SDV delivery is pending/failed;
- retries delivery without rerunning AI/OCR/redaction;
- exposes safe IT-admin SDV connection health/test behavior without sending a customer PDF;
- has removed the old direct master S3/generic callback delivery path;
- preserves Requirement 1 Push API, Requirement 2 polling, current roles, Dashboard/Reports, AI/OCR/redaction and retention semantics.

## Required local evidence

1. `Build-LexaVault-All.ps1` / backend `mvn clean verify` and frontend TypeScript/auth/Vite build.
2. Liquibase `017-sdv-delivery` applies cleanly to PostgreSQL with no checksum errors; inspect new delivery tables/indexes/constraints.
3. Start normal `local` profile and confirm local filesystem delivery still works without SDV configuration.
4. Start explicit `local-sdv` profile and confirm startup uses SDV delivery. With no enabled tenant SDV connection, delivery must fail closed rather than write to local output.
5. Configure one enabled tenant `SDV_DELIVERY` HTTP connection with `SDV_SUBMISSION_ACK`; verify a second enabled SDV connection for the same tenant is rejected (application and DB backstop).
6. Use a local/mock SDV API implementing the documented `/api/v1/submissions` contract. Export a package with at least two redacted PDFs and verify:
   - one submission created;
   - each PDF uploaded independently;
   - stable `sourceSubmissionId` / `sourceDocumentId` used rather than LexaVault internal UUIDs as SDV identities;
   - SHA-256/idempotency headers present;
   - only redacted artifacts are read/sent;
   - `EXPORTED`/successful export cycle occurs only after SDV `COMPLETE`.
7. Partial/lost-response test: acknowledge document A, fail document B or final status. Verify delivery submission remains retryable, retention clock is not started, and next retry does not re-upload already acknowledged A or rerun redaction.
8. Final-status test: all document uploads 2xx but SDV status not `COMPLETE` must not mark export successful.
9. Test Connection must call only the safe SDV health endpoint and never create a submission or transfer a customer PDF. Verify authorization is `TENANT_INTEGRATION_MANAGE` and secrets are not returned/logged.
10. Verify old active S3/API-callback master-delivery classes/config/dependencies are absent; historical docs may retain them only with supersession notices.

## Verdict

Return `PASS`, `PASS WITH MINOR FINDINGS`, or `FAIL — CORRECTION REQUIRED`, with commands, runtime/API/DB evidence, files modified (if any), and whether this candidate is safe to accept and proceed to Requirement 4.
