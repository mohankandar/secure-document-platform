# LexaVault V1 — Current State

**Authoritative date:** 2026-09-03  
**Project codename:** LexaVault  
**Repository/code naming:** generic `secure-document-platform` / `com.securedoc.platform.*`

## Baseline

The **latest accepted development baseline** includes the Common Integration Connection Foundation and Requirements 1–5 after independent Kiro build/runtime verification on 2026-09-03. The current workstream is the final V1 hardening/regression pass; it introduces no new product scope.

Requirement 2 was accepted with one Kiro-authorized startup wiring correction folded into source: the intended public `HttpPollingSourceAdapter(ObjectMapper)` constructor is explicitly `@Autowired` so Spring selects it when the component has multiple constructors.

Implemented/accepted construction includes:

- Units 16–25;
- AI-first digital PDF processing and scanned/mixed PDF OCR;
- confidence/exception handling and focused Manual Review;
- Business Record + multi-identifier unified intake;
- Business Admin configuration;
- delivery, retention, reprocessing, technical holds, and purge lifecycle;
- shared Dashboard and configurable columns;
- tenant/platform technical administration;
- production OIDC/JWT hardening;
- Batch 2 one-page multi-PDF intake;
- Batch 3 breadcrumb/responsive navigation polish;
- Batch 4 Executive + Operational Reports;
- 2026-09-01 role/capability simplification.

Current integration development status:

- Common tenant-scoped `IntegrationConnection` foundation: accepted and independently verified.
- Requirement 1 manifest-first machine Push API: accepted and independently verified, including fixed expiry, secure partial-binary cleanup, idempotency, checksum/PDF validation, status reconciliation, and retry-attempt semantics.
- Requirement 2 scheduled polling / pull ingestion: accepted and independently verified, including scheduling/Run Now, secure storage-before-ACK, partial retrieval continuation, lost-ACK recovery, no-overlap locking, retry/resubmission, failure classification and operational health.
- Requirement 3 redacted master delivery to SDV: accepted and independently verified, including profile fail-closed behavior, tenant-scoped SDV Integration Connections, one submission per export cycle, streamed per-document upload, durable per-document acknowledgements, submission-completion-gated `EXPORTED`, retention-clock gating, retry without reprocessing, and removal of obsolete direct S3/generic callback master delivery.
- Requirement 4 derived split/routing: accepted after Kiro `PASS WITH MINOR FINDINGS`, including deterministic fixed-region marker detection (`BARCODE`, `QR_CODE`, `TEXT_PATTERN`), derived-from-redacted-only PDFs, cycle-scoped identity/history, Business Admin marker-to-destination rules, IT-managed HTTP/file-share destinations, durable HTTP receipt / guarded durable file-share delivery, independent Technical Attention/retry state, and Option A master-export independence.
- Requirement 5 local AI performance/model optimization: accepted after Kiro `PASS`. `qwen3.5:4b` is the V1 LOCAL/demo reference model on the current ~6 GB VRAM demo laptop; it stayed 100% GPU-resident and completed the controlled 5-page/10-page benchmark with full expected detection quality, correct page attribution, zero retries/timeouts, and ~41 output tokens/sec. `gemma3:12b` remains an optional explicitly configured larger-hardware model and is never an automatic fallback. The real Ollama startup preload is privacy-clean and fail-closed.

See `../product/INTEGRATIONS.md` and `../api/EXTERNAL_INGESTION_V1.md`.

## Current product model

LexaVault V1 is an AI-first secure document-redaction and processing platform. The processing path is:

```text
PDF
 -> native extraction or page-aware OCR
 -> AI semantic discovery on every normalized document
    + deterministic supporting evidence
 -> tenant policy/confidence
 -> permanent redaction
 -> verification
 -> focused review only when required
 -> delivery
```

AI discovery is primary. Deterministic recognizers/validators support structured categories but never gate whether AI can discover sensitive content.

Current baseline categories are SSN, Date of Birth, Credit Card Number, and Credit Score. Tenant policy controls which supported categories/options are enabled.

## Canonical roles

Active product roles are:

- `DOCUMENT_PROCESSOR`
- `DOCUMENT_SUPERVISOR`
- `BUSINESS_ADMIN`
- `REPORT_VIEWER`
- `TENANT_IT_ADMIN`
- `PLATFORM_OPERATIONS_ADMIN`
- explicit internal `ADMIN`

The exact matrix is in `../product/AUTHORIZATION.md`.

Retired public workflow roles `USER`, `REVIEWER`, and `APPROVER` are not active product roles. They may exist only as documented transition aliases at the identity boundary.

## Frozen behavior

The following accepted behavior is intentionally preserved unless a future approved change explicitly modifies it:

- modern white-card / pale blue-gray / navy / blue-action visual system;
- one shared operational Dashboard for roles that have Dashboard access;
- smart Dashboard polling and quick filters;
- one-page multi-PDF intake with separate original files/names;
- package-scoped Manual Review and exact PDF page/highlight rendering;
- asynchronous resume after final review decision;
- visibility-only `Remove from Dashboard` semantics;
- business reprocess distinct from transient technical retry;
- AI-first processing and precision safeguards;
- tenant AI policy/provider isolation;
- retention-owned physical cleanup;
- watched-folder and API intake;
- tenant-scoped reports and audited CSV export where authorized;
- production identity fail-closed behavior.

## Frozen integration direction

- Push ingestion is manifest-first and machine identities bind to enabled tenant integration sources; service clients do not receive fake human roles.
- Scheduled pull/polling retrieves into LexaVault secure original storage, acknowledges only after durable receipt, and does not use watched folder as intermediate storage.
- Production master delivery ends at SDV acknowledgement; SDV owns customer durable storage provider choice.
- Direct LexaVault master delivery to S3/generic callback is superseded and removed from the accepted Requirement 3 baseline. Local filesystem delivery remains for `local`/demo; `local-sdv` and non-local profiles use SDV and fail closed without an enabled tenant SDV connection.
- Derived split/routing creates additional redacted PDFs from deterministic routing markers; the full master remains intact and exports to SDV independently.
- Common Integration Connections separate Business Admin routing decisions from Tenant IT Admin technical connection/authentication management.
- Requirements 1–5 are accepted. `qwen3.5:4b` is the default LOCAL/demo AI reference; model/provider selection remains explicit and production remains fail-closed with no silent fallback.

## Verification status

### Role simplification

- user manual testing: PASS;
- independent Kiro verification: PASS / safe to accept as V1 baseline;
- Kiro adversarial authorization review: PASS;
- no code-supported privilege escalation found;
- no cross-tenant bypass found;
- capability authorization confirmed backend-authoritative and fail-closed;
- legacy-role normalization confirmed narrow and identity-boundary-only;
- `ADMIN` confirmed never implicit;
- platform diagnostics confirmed safe and tenant scoped;
- Kiro modified no files during those verification passes.

### Integration verification

Container-available verification completed before local/Kiro verification:

- Java 21 compile of all domain main sources: PASS;
- Java 21 compile of framework-free application main sources (excluding six pre-existing SLF4J-dependent classes because Maven dependencies are unavailable in this container): PASS;
- domain/application test source compilation against local JUnit signature stubs: PASS;
- dependency-free frontend authorization suite: 8/8 PASS;
- application-level external-intake smoke execution: PASS (manifest creation, individual receipt, duplicate acknowledgement without duplicate upload, automatic acceptance/dispatch, idempotent source recreation).

Local/Kiro Requirement 2 verification completed with `PASS WITH MINOR FINDINGS`: backend `mvn clean verify` passed across all 10 modules; frontend TypeScript/auth tests/Vite build passed; Liquibase `016-polling-ingestion` applied cleanly to PostgreSQL; Spring runtime polling lifecycle, partial retrieval, lost-ACK recovery, no-overlap, safe failure classification, authorization negatives, and existing processing convergence were exercised successfully.

Non-blocking follow-up items retained for later operations/reporting hardening:

- add a dedicated regression test around `PollingIntakeService.processPackage` / adapter ACK ordering;
- improve run-level `documentsRetrieved` accounting for a package that fails after some documents were already retrieved. The authoritative per-document state is correct and this does not affect ACK or processing decisions.

Local/Kiro Requirement 3 verification completed with `PASS WITH MINOR FINDINGS`: backend `mvn clean verify` passed across all 10 modules; frontend TypeScript/auth tests/Vite build passed; Liquibase `017-sdv-delivery` applied cleanly to PostgreSQL; Spring started cleanly on `local,local-sdv`; live SDV submission, streamed multi-document upload, per-document acknowledgements, submission-completion-gated export, retention-clock gating, SDV-unavailable handling, technical delivery retry without AI/OCR/redaction reprocessing, authorization negatives, and old S3/API master-delivery removal were all verified. Kiro modified no product source.

Non-blocking Requirement 3 follow-up items retained for later test-hardening:

- add dedicated `SdvDeliveryAdapter` and `PackageDocumentExportService` behavior tests;
- add a static authorization contract assertion for `SdvIntegrationController.test-connection`;
- optionally add a dedicated runtime regression for mid-submission document failure / lost-response reconciliation beyond the existing source/domain/runtime evidence.

Local/Kiro Requirement 4 verification completed with `PASS WITH MINOR FINDINGS`: backend Maven/frontend/Liquibase 018/Spring startup and deterministic routing configuration/authorization checks passed; marker detection is deterministic and derived PDFs are built only from redacted artifacts; master SDV export remains independent; HTTP requires a durable receipt; FILE_SHARE is root-guarded, fsynced and atomically moved; retries reuse derived artifacts without reprocessing. Kiro modified no product source.

Non-blocking Requirement 4/final-regression items retained:

- add safe classified API mapping for `TechnicalDependencyException`;
- add infrastructure tests for the marker detector, PDF builder and derived destination adapter;
- execute one full end-to-end runtime marker → derived PDF → HTTP/FILE_SHARE delivery scenario because the Requirement 4 Kiro session could not exercise that exact path due to pre-existing pipeline-dispatch behavior.

### Requirement 5 verification

Local/Kiro Requirement 5 R2 verification completed with `PASS`: backend Maven/frontend/Liquibase/Spring startup passed; `qwen3.5:4b` preloaded before document inference, remained 100% GPU-resident on the RTX 1000 Ada 6 GB laptop, and completed three warm 5-page runs at ~32.3s median AI time and three warm 10-page runs at ~63.3s median AI time. Expected SSN/DOB/CREDIT_SCORE/CREDIT_CARD findings were fully detected with correct page attribution and no false positives in the benchmark set; no benchmark run timed out or retried. Kiro modified no product source.

The final hardening checkpoint was executed locally by Kiro and returned `FAIL — CORRECTION REQUIRED` for one V1-freeze blocker: Push API auto-dispatch raced the surrounding upload transaction commit, so the asynchronous worker could observe the package as `CREATED` before `SUBMITTED` became durable. Manual `/process` confirmed the processing pipeline itself was healthy. Kiro modified no source.

The correction candidate makes the in-process `PackageProcessingDispatcher` transaction-aware: when called from an active Spring transaction it registers the background processing task for after-commit execution; rollback cancels the dispatch. The dispatcher now also emits generic `PACKAGE_PROCESSING_DISPATCH_*` lifecycle logs instead of review-specific tokens, including explicit deferred/cancelled/failed events. Focused regression coverage proves dispatch does not start before commit and does not run after rollback. This correction must pass the developer build and final Kiro runtime verification before V1 freeze.

### V1 final freeze verification and manual-intake identity correction

Final local/Kiro freeze verification of the R3 post-commit dispatch candidate returned `PASS / V1_FREEZE_APPROVED`: Push API automatic processing was proven after transaction commit without manual `/process`; rollback cancels dispatch; the full runtime marker → derived PDF → HTTP + FILE_SHARE path passed; Qwen remained the LOCAL/demo default; Kiro modified no product source.

A subsequent manual-upload defect was reproduced from the frozen baseline: a new Contract Number could be rejected as `Business identifier conflicts with the existing record: Contract Number` when non-primary identifiers such as Member Number or Service Entity matched an existing record. Root cause: `BusinessIdentityService` treated every configured identifier as a record resolver, while the database also incorrectly required every identifier value to be tenant-wide unique. This conflicts with the product model where exactly one configured identifier is Primary and secondary identifiers may legitimately repeat across business records (for example one member may own multiple contracts and many contracts may share a Service Entity).

The current correction candidate therefore:

- resolves/reuses a BusinessRecord only by the active tenant-configured Primary identifier;
- validates secondary identifiers for consistency only after the Primary record has been resolved;
- permits repeated secondary identifier values across different BusinessRecords;
- preserves uniqueness of the active Primary identifier at the PostgreSQL boundary;
- adds focused regression coverage for repeated Member Number / Service Entity values across different Contract Numbers, same-primary reuse, and same-primary secondary conflict.

This correction must pass full Maven/Liquibase/runtime verification before replacing the frozen functional baseline.

### Historical runtime/build evidence

Prior unit/batch build, PostgreSQL, Ollama, Tesseract, Liquibase, and runtime acceptance records are preserved under `../archive/`.

Historical verification evidence remains useful, but current behavior must be checked against current source when a later change touches that area.

## Known intentional boundaries

- PDF is the V1 document format; DOCX is later scope.
- No custom tenant role builder or tenant-editable role-to-capability matrix.
- No physical-delete button for business users; retention owns purge.
- No custom report builder/scheduled report distribution/predictive forecasting in V1.
- No browser-managed AI secret vault.
- No Kafka/broker requirement without demonstrated operational need.
- No long-term original-document vault/download product workflow.

## Next-session rule

A new ChatGPT or Kiro session should start with:

1. this file;
2. `DEVELOPMENT_WORKING_AGREEMENT.md`;
3. the current product/architecture document relevant to the requested change;
4. source inspection before modification.

Do not reconstruct current behavior from archived unit notes when a current document or current source answers the question.
