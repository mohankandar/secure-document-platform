# LexaVault V1 Workflow and Lifecycle

## 1. Intake

Normal manual intake is one page containing tenant-configured business identifiers and a PDF queue.

- Desktop drag/drop and `Browse Files` feed the same manual-upload queue.
- Multiple PDFs can be queued in one Intake Batch.
- Every PDF remains a separate document and keeps its original filename.
- PDFs are never merged as part of intake.
- Non-PDF files are rejected before package creation.
- Duplicate local selections are skipped.
- Processing starts only after explicit `Submit Documents`.
- Required identifiers and at least one valid PDF are required before submit.

Submission uses one tenant-scoped Intake Batch, uploads queued documents while the package is `CREATED`, and submits only after every required upload succeeds.

Scanner-assisted intake is a separate **Scan Documents** entry point using the local Scanner Bridge. It reuses the same tenant-configured business-identifier definitions but does not expose a second `Scan Documents` file-picker action inside manual Upload Documents. One Scanner Intake is one business context plus one physical scan job producing one consolidated multi-page PDF. The operator explicitly submits the READY scan; the bridge durably queues it and may let the operator start the next package immediately while authenticated/idempotent delivery proceeds asynchronously. Accepted scanner submissions use `SCANNER_INTAKE` source classification and then converge on the existing Business Record, validation, processing, Manual Review, approval, delivery/export and Dashboard lifecycle. See `../scanner-bridge/SCANNER_INTAKE_END_TO_END.md`.

API, watched-folder, polling and scanner intake use or will converge on the same downstream document-processing lifecycle while preserving their own trigger/identifier/idempotency contracts.

### Machine Push API intake

Machine-to-machine Push API intake is manifest first and is bound to an enabled tenant `PUSH_API` Integration Connection resolved from the authenticated service identity. Service clients do not receive synthetic human product roles.

- Creation supplies the external source package ID, source revision, all tenant-required business identifiers, and the complete expected-PDF manifest.
- Every manifest document has a unique `externalSourceDocumentId`; filename remains informational.
- PDFs are uploaded and acknowledged independently after type/size/page/checksum validation and durable secure storage.
- The intake stays `AWAITING_DOCUMENTS` until all expected PDFs arrive, then it is automatically submitted into the existing asynchronous processing pipeline.
- The completion deadline is fixed from intake creation (24 hours by default, source/tenant configurable within safe bounds); individual receipts do not extend it.
- An expired incomplete attempt becomes `INCOMPLETE_EXPIRED`, the ordinary package is cancelled if still pre-processing, and partial protected binaries are cleaned through lifecycle/storage cleanup while metadata and audit history remain.
- A technical retry repeats the same immutable source package/revision/content and may create a new internal attempt after an expired attempt. A materially changed submission uses a new source revision; prior revisions are not automatically replaced or deleted.

The exact current external contract is documented in `../api/EXTERNAL_INGESTION_V1.md`.

## 2. Processing

For every document:

1. use native PDF text/geometry where usable;
2. OCR only pages that need it;
3. run AI semantic sensitive-content analysis on normalized content;
4. merge deterministic supporting evidence;
5. apply tenant policy/confidence;
6. locate findings using trusted native/OCR geometry;
7. permanently redact confirmed findings;
8. verify the output;
9. deliver when workflow policy allows.

A technical dependency failure does not become a fake Manual Review item.

## 3. Manual Review

Manual Review is focused on actionable findings rather than whole-document browsing.

- Package/contract entry is package scoped.
- Global Manual Review remains tenant wide.
- Selected findings render the exact one-based source PDF page.
- Stored highlight/bounding-box geometry is displayed and may be adjusted where authorized.
- Confirm/dismiss, redaction adjustment, review submission, and final delivery approval remain separate capabilities/audit meanings.
- Review counts include only actionable unresolved findings.
- After the last review decision, processing resumes asynchronously; the user action does not wait for the entire redaction/export pipeline.

## 4. Delivery approval

Tenant lifecycle policy may require final delivery approval. When required, delivery waits for an authorized approval action. Processor, Supervisor, Business Admin, and explicit `ADMIN` currently have that capability.

The capability remains distinct from Manual Review so future separation-of-duties policy can be added without changing the architecture.

## 5. Reprocessing

`Reprocess from Retained Original` is a business lifecycle action for a valid completed/exported package while the retained original still exists.

- Document Supervisor and Business Admin may perform it; Processor may not.
- The UI requires explicit confirmation before the request is sent.
- Once accepted, the package immediately leaves `COMPLETED` and is shown as Processing/Reprocessing while the asynchronous pipeline runs.
- `canReprocess` is false unless the package is currently `COMPLETED`; refreshing or reopening the page cannot expose a second reprocess action while a cycle is active.
- The backend independently rejects reprocess requests for any non-`COMPLETED` package, so UI state is not the authorization/state boundary.
- Reprocessing is bounded by the existing lifecycle/export-cycle and retained-original rules; successful export of the new cycle resets the existing retention deadlines through the normal lifecycle policy.
- It is not the same operation as technical retry.

## 6. Technical retry

`Retry Transient Technical Failure` is for classified retryable technical failures only.

- It is owned by Tenant IT / Platform Operations / explicit `ADMIN`.
- It does not grant review, approval, reprocess, or Dashboard-removal authority.
- Permanent/business/content exceptions are not indiscriminately retried.

## 7. Dashboard removal

`Remove from Dashboard` means hide from the normal operational Dashboard.

- It is allowed only in valid lifecycle state.
- It does not delete the package/database record.
- It does not remove audit/report history.
- It does not immediately delete artifacts.
- It does not change retention deadlines.
- Physical cleanup remains the retention lifecycle's responsibility.

## 8. Retention and physical cleanup

The original is retained while needed for processing/review/reprocess according to tenant lifecycle policy. Redacted output is the primary durable delivered artifact.

No business/product role receives a general manual physical-delete capability. Retention/purge processing owns physical cleanup and records the lifecycle outcome.
