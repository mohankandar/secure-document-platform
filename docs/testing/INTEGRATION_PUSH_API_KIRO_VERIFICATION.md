# Integration Foundation + Push API — Kiro Verification Checkpoint

**Mode:** Verify only. Do not implement, refactor, rename, redesign, or clean up source.

Use the current workspace exactly as provided. This checkpoint covers the common Integration Connection foundation and Requirement 1 manifest-first machine Push API. Requirements 2–4 are frozen but not implemented. Requirement 5 AI optimization is deliberately out of scope.

## Required evidence

Run the smallest real-stack verification that proves this candidate is safe to accept:

1. Java 21 / backend: run the repository-supported full Maven clean verification and report the exact command/result.
2. PostgreSQL/Liquibase: apply the current changelog including `014-integration-connection-foundation.xml` and `015-external-machine-intake.xml`; report applied changesets/checksum status and any schema failure.
3. Frontend: run the authorization test plus TypeScript/Vite production build.
4. Spring Boot: start the normal local profile and confirm existing local/demo behavior does not require SDV configuration.
5. Integration management authorization: verify `TENANT_IT_ADMIN`, `PLATFORM_OPERATIONS_ADMIN`, and explicit `ADMIN` can manage tenant Integration Connections as intended; ordinary business/workflow roles are denied through direct APIs.
6. Configure one enabled local `PUSH_API` source bound to a test integration client identity and test the machine contract under `/api/v1/external-intakes`.
7. Create a manifest with all tenant-required business identifiers and at least two PDFs. Verify creation returns attempt 1, fixed `expiresAt`, expected count, zero received, and missing source document IDs.
8. Upload documents independently with `Idempotency-Key` and `X-Content-SHA256`. Verify acknowledgement only after durable storage and correct expected/received/missing counts.
9. Re-send an already acknowledged external document with identical content. Verify idempotent acknowledgement and no duplicate LexaVault document/artifact.
10. Verify failure cases: missing mandatory business identifier, unknown manifest document ID, reused idempotency key across different manifest documents, checksum mismatch, non-PDF/invalid PDF, configured size/page-limit violation, source/tenant ownership violation.
11. Verify the final expected document automatically submits the ordinary package and dispatches existing processing exactly once. Do not change or tune Ollama/AI behavior.
12. Expiry: use a safe shortened test source window or controlled test method. Verify deadline is fixed/non-sliding, expired partial intake becomes `INCOMPLETE_EXPIRED`, late upload is rejected, ordinary package is cancelled if still pre-processing, partial protected binary is physically removed, and source/document metadata + audit history remain.
13. Retry/resubmission: after expiry, create the same immutable source package/revision/manifest with `retry=true`; verify a new internal package with attempt 2 is created. Then alter manifest/content while reusing the same source revision and verify it is rejected as a source-revision reuse conflict; a changed submission requires a new source revision.
14. Production identity boundary/source review: confirm OIDC mode derives tenant/client identity from the verified token, ignores local/demo caller identity headers, and does not assign fake human roles to machine clients.
15. Focused regression: manual multi-PDF intake, watched-folder intake, current local final delivery, authorization, Dashboard/Reports, retention state transitions, and existing Ollama/AI/OCR/redaction paths should show no regression attributable to this candidate.

## Output format

Return:

- `PASS`, `PASS WITH FINDINGS`, or `FAIL — CORRECTION REQUIRED`;
- exact commands executed and results;
- Liquibase evidence;
- API requests/statuses sufficient to prove the cases above (do not expose secrets or raw sensitive values);
- any real defects with file/class/endpoint and severity;
- environment-only limitations separately;
- final recommendation whether this candidate is safe to accept as the next LexaVault V1 baseline.

Do not modify files.
