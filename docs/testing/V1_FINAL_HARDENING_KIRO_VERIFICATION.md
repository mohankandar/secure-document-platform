# LexaVault V1 — Final Hardening & Regression Kiro Verification

Use this only after the complete hardening candidate builds successfully on the developer machine.

ChatGPT is the developer/architecture owner. Kiro is the independent local reviewer/tester. Kiro may fix only obvious compile/test-compile/import/Maven/Spring-wiring blockers. Functional, security, authorization, tenant-isolation, lifecycle, AI-quality, routing, persistence-semantic, retention or delivery-contract defects must be reported without modification.

## Hardening changes to verify

1. `HttpMessageNotReadableException` maps malformed JSON/type mismatches in request bodies to safe HTTP 400 `INVALID_REQUEST_BODY` instead of generic 500.
2. `TechnicalDependencyException` maps to a safe classified response: retryable failures -> HTTP 503; non-retryable dependency/config failures -> HTTP 502; response exposes only stable failure code + safe message/correlation id.
3. Polling run-level `documentsRetrieved` retains already successful receipts when a later document in the same source package fails; source ACK remains after final durable `ACCEPTED_FOR_PROCESSING` only.
4. SDV static authorization contract covers Test Connection; focused SDV adapter/export-service behavior tests pass.
5. Requirement 4 marker detector/PDF builder/HTTP receipt/FILE_SHARE durability regression tests pass.
6. Requirement 5 accepted promotion is real: default `local` + default local launcher select `qwen3.5:4b`; `gemma3:12b` remains explicitly selectable and there is no silent fallback.

## Build / stack

Execute and record:

- `Build-LexaVault-All.ps1`
- backend `mvn clean verify`
- frontend TypeScript/auth tests/Vite build
- PostgreSQL + Liquibase startup/checksum status
- Spring Boot startup with `local` and, where SDV is needed, `local,local-sdv`
- Ollama `qwen3.5:4b` preload/residency
- Tesseract availability

No new Liquibase migration is expected from this hardening pass.


## Push API post-commit dispatch correction — CRITICAL

The prior final checkpoint found a real read-before-commit race: the Push API upload transaction set the package to `SUBMITTED` and immediately dispatched background processing before commit, allowing the worker to read `CREATED` and fail.

The correction candidate makes `InProcessPackageProcessingDispatcher` transaction-aware. Verify all of the following:

- when called inside an active Spring transaction, dispatch is deferred until transaction commit;
- the worker never observes the pre-submit `CREATED` state for the accepted Push intake;
- if the transaction rolls back, no processing task starts;
- normal non-transactional dispatch remains asynchronous;
- runtime logs use `PACKAGE_PROCESSING_DISPATCH_*` and clearly show DEFERRED/SCHEDULED/STARTED/COMPLETED or FAILED/CANCELLED as appropriate;
- a real Push intake with the final expected PDF proceeds automatically from durable receipt -> SUBMITTED -> extraction/AI/redaction without any manual `/process` call.

If manual `/process` is required for the normal Push path, final V1 verification is still a FAIL.

## Full Requirement 4 runtime closure — required before V1 freeze

Use the repository fixtures:

```powershell
python .\scripts\testing\Create-DerivedRoutingFixture.py .\derived-routing-final.pdf
python .\scripts\testing\DerivedRoutingMockDestination.py --port 19090 --output .\derived-routing-mock-output
```

Configure two IT-managed derived-delivery connections and two Business Admin routing rules:

- `77777777` / `TOP_RIGHT` / `South Carolina` -> HTTP destination `http://127.0.0.1:19090` with `DESTINATION_RECEIPT`
- `88888888` / `BOTTOM_LEFT` / `Atlanta` -> a dedicated local test `FILE_SHARE` root with `DURABLE_FILE_WRITE`

Both transports are required in this final runtime closure so one package proves HTTP receipt semantics and FILE_SHARE durable-write semantics end to end.

Process `derived-routing-final.pdf` through the real LexaVault pipeline using the accepted `qwen3.5:4b` local model. Prefer the normal intake/automatic dispatch path. If runtime timing makes state observation necessary, use existing supported status/process operations but clearly report whether automatic dispatch occurred.

Required evidence:

- original/source document is processed by the real extraction/AI/redaction path;
- the full permanently redacted master remains intact and completes normal master delivery;
- derived routing reads only the `REDACTED` artifact;
- pages 1 and 2 group to `77777777` / South Carolina in original order;
- page 3 groups to `88888888` / Atlanta;
- resulting derived PDFs contain no unrelated pages;
- HTTP upload is streamed, carries stable idempotency identity, and is `DELIVERED` only after the mock returns a receipt;
- FILE_SHARE (if used) remains beneath configured root and completes durable write/rename semantics;
- mock `events.jsonl`, received PDFs, DB rows and audit entries agree;
- derived failure/retry does not rerun Qwen/OCR/redaction/master SDV delivery;
- master `EXPORTED`/successful export remains independent of derived delivery status;
- no original/unredacted PDF is sent to a derived destination.

## Requirements 1–5 no-regression

Provide focused evidence rather than unnecessarily repeating every historical test:

- Req1 Push API: machine identity, manifest/idempotency, auto-submit/dispatch, tenant isolation.
- Req2 Polling: schedule/Run Now, secure receipt-before-ACK, partial continuation, lost ACK, no overlap and corrected retrieved counter.
- Req3 SDV: one submission/cycle, streaming docs, per-doc ACK, COMPLETE gate, retention gate, retry without reprocessing, local/local-sdv profile behavior.
- Req4: deterministic marker types/fixed regions, redacted-only split, Business Admin vs IT ownership, HTTP receipt and FILE_SHARE safety, Option A master independence.
- Req5: default Qwen preload, structured output, think=false, page batching/grounding, privacy-safe telemetry; benchmark result need not be rerun exhaustively unless AI source/config changed unexpectedly.

## Security / authorization regression

Direct backend negative tests must preserve the canonical roles/capabilities, tenant isolation, no implicit ADMIN, technical/business admin separation, and no arbitrary workflow-user URL/path/credential input.

## Final verdict

Return exactly one:

- `PASS`
- `PASS WITH MINOR FINDINGS`
- `FAIL — CORRECTION REQUIRED`

Then provide:

- critical / important / minor findings;
- hardening API mapping verification;
- polling counter/ACK verification;
- SDV test-hardening verification;
- derived adapter/PDF/marker test verification;
- full runtime derived-routing closure evidence;
- Qwen-default/fail-closed verification;
- Requirements 1–5 no-regression;
- backend/Liquibase/frontend evidence;
- files modified by Kiro;
- explicit recommendation whether the workspace is safe to freeze as **LexaVault V1**.
