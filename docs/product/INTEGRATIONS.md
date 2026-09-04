# LexaVault V1 Integration Model

**Status:** Current frozen product/architecture requirements. Implementation is phased; see `../continuity/CURRENT_STATE.md` for what is already implemented/verified.

## Architectural boundary

LexaVault is the redaction/processing product. It accepts documents from configured sources, processes/redacts them, and delivers approved redacted artifacts. Long-term vault storage belongs to the independent SDV application in production.

All integration channels converge into the same LexaVault intake and processing pipeline after secure receipt. Integration-specific transport logic must not create separate AI/OCR/redaction pipelines.

## Common Integration Connection model

Tenant technical integrations use a common tenant-scoped `IntegrationConnection` model with typed provider validation rather than unrestricted generic configuration.

Common concerns include:

- logical connection identity/name;
- purpose and direction;
- provider/transport type;
- authentication type;
- protected secret reference (never plaintext credential return/display);
- enabled/disabled state;
- timeout/retry policy;
- acknowledgement policy;
- health/last-success/last-failure metadata;
- configuration version for historical audit;
- intake limits where applicable.

`TENANT_IT_ADMIN` / `PLATFORM_OPERATIONS_ADMIN` own technical connection configuration. Business administrators may reference named destinations from business routing rules but do not manage credentials or transport details.

Environment-specific endpoint/credential bindings must not silently fall back across profiles. Disabling a connection must preserve queued/pending work. Connectivity tests must not send real customer documents.

## Requirement 1 — Push API / machine-to-machine ingestion

### Contract

A configured authenticated source creates a manifest-first external intake containing:

- external source package identity;
- source revision;
- all tenant-required business identifiers;
- optional safe metadata;
- expected PDF manifest;
- unique `externalSourceDocumentId` for every expected PDF;
- optional declared SHA-256 per manifest document.

Each PDF is uploaded independently and acknowledged only after LexaVault has validated it and durably stored the original in LexaVault-controlled secure storage.

The source identity is bound to an enabled tenant `PUSH_API` Integration Connection. In production, tenant/client identity comes from the verified authentication boundary, not from arbitrary payload tenant fields or caller-supplied identity headers.

### Document identity

- LexaVault `documentId` is always generated internally and is the database/domain identity.
- `externalSourceDocumentId` is an integration/reconciliation identifier and never replaces LexaVault IDs in internal relationships.
- `externalSourceDocumentId` must be unique within the intake manifest.
- Filename is informational and must never be manufactured into an external source ID.

### Idempotency and integrity

- Every document upload requires an idempotency key.
- LexaVault calculates its own SHA-256 and compares it with the source-supplied checksum.
- Repeating an already acknowledged external document with the same immutable content returns the existing acknowledgement and does not create another document.
- Reusing a source package/revision with materially different manifest/business payload is rejected; changed content is a resubmission and requires a new source revision.

### Retry versus resubmission

- **Retry:** same source package ID, same revision, same manifest/content. A terminal expired attempt may be retried explicitly with `retry=true`; LexaVault creates a new internal intake attempt while preserving the prior expired attempt/audit history.
- **Resubmission:** same source business/package identity with a new source revision. LexaVault processes each revision independently and does not replace/hide/delete prior revisions automatically.

### Completion and expiry

- Intake status remains `AWAITING_DOCUMENTS` until every expected manifest PDF is received.
- The completion window defaults to 24 hours and is tenant/source configurable within safe bounds.
- The deadline is fixed from intake creation; document receipts do not slide/reset it.
- When all expected PDFs are received, LexaVault automatically accepts/submits the package for the existing asynchronous processing pipeline.
- If the deadline expires first, the intake becomes `INCOMPLETE_EXPIRED`; further uploads to that attempt are rejected.
- Partial stored originals are physically cleaned through the lifecycle/secure-storage abstraction while database metadata and audit evidence remain.

### Status/reconciliation

The source can query intake status and receive expected/received counts, missing external document IDs, source revision/attempt, and fixed expiration time.

## Requirement 2 — Scheduled polling / pull ingestion

**Implemented, independently verified, and accepted on 2026-09-03.**

LexaVault initiates configurable scheduled polling against trusted sources. The source owns an "available for pickup" queue. LexaVault retrieves the manifest/metadata and PDFs, validates and durably stores them in LexaVault secure original storage, then acknowledges the source. Pulled files do not pass through the watched folder as an intermediate store.

After successful acknowledgement, the source must stop exposing that submission/revision. Lost acknowledgement is safe because LexaVault retains idempotent source identity and may re-acknowledge without reprocessing. Retry/resubmission semantics are the same as above. The same configurable incomplete window applies to retrieval attempts.

## Requirement 3 — Redacted master delivery to SDV

**Implemented, independently verified, and accepted on 2026-09-03.**

Production responsibility boundary:

- LexaVault owns redaction and temporary delivery responsibility.
- SDV is an independent product/application and owns durable vault storage/provider selection.
- LexaVault and SDV must not share source libraries, databases, schemas, internal IDs, domain models, or deployment units. Compatibility is through the versioned external SDV contract only.

One LexaVault package/revision maps to one SDV submission. LexaVault sends only approved redacted PDFs plus the minimum required business/integration metadata. Originals, raw sensitive findings/snippets, AI prompts, and unnecessary processing internals are not delivered to SDV.

`EXPORTED` means SDV has acknowledged responsibility for every expected redacted document in the submission. Partial send or HTTP request success alone is not export completion. Until complete acknowledgement, LexaVault retains the redacted artifacts, retries delivery idempotently, and the post-export retention clock does not begin.

Profiles:

- `local`: local-filesystem delivery only; no SDV configuration required;
- explicit local-SDV/integration profile: real LexaVault-to-SDV contract testing;
- upper/production environments: SDV delivery provider as explicitly configured, with no silent filesystem fallback.

The old direct master-delivery responsibility for customer S3/object storage or generic callback delivery is superseded and has been removed from active source. Customer durable storage choice belongs inside SDV. LexaVault internal secure artifact storage remains required.

The accepted implementation persists a delivery submission, stable external source submission/document IDs, per-document acknowledgements, and attempt history with the non-secret Integration Connection/configuration version. Technical retries resume only missing SDV acknowledgements. See `../api/SDV_DELIVERY_V1.md`.

## Requirement 4 — Derived-document split and external routing

**Frozen design; candidate implementation present, acceptance pending local/Kiro verification.**

A source PDF remains intact as the full redacted master. LexaVault may additionally create derived redacted PDFs containing pages that match deterministic tenant routing markers.

Supported V1 marker types:

- `BARCODE`;
- `QR_CODE`;
- deterministic `TEXT_PATTERN`.

Marker scanning is restricted to configured fixed regions only:

- `TOP_RIGHT`;
- `BOTTOM_LEFT`;
- `BOTH`.

LexaVault must not scan the entire page for routing markers and must not use AI guessing to select an external destination. Marker detection can occur before derived construction, but derived documents are built only from permanently redacted page content and preserve original page order.

A source document may produce multiple derived PDFs for different marker groups/destinations. The full master SDV export is independent (Option A): master may be `EXPORTED` after SDV acknowledgement while a derived delivery is still pending/retrying/attention-required.

Business Admin maps marker values to named destinations. Tenant IT Admin configures the destination's technical connection/authentication/health. Initial derived-delivery providers are typed `HTTP_API` and secure `FILE_SHARE`; the provider architecture remains extensible.

Normal document-workflow users may perform approved normal send/resend actions. Authentication/connection/protocol failures are Technical Attention items handled by technical administrators. Delivery retries resend the already-created redacted derived artifact and never rerun redaction.

The candidate implementation is export-cycle scoped so a later business reprocess creates a fresh derived artifact/delivery record for the new redacted master while retaining prior-cycle history. HTTP delivery requires a durable destination receipt; file-share delivery fsyncs the file before atomic rename. Delivery-attempt history snapshots the Integration Connection/configuration version used.

## Requirement 5 — High-performance local AI

**Accepted 2026-09-03 after controlled Kiro speed + quality benchmark.**

The accepted provider path includes:

- privacy-clean fail-closed startup preload + configurable Ollama keep-alive;
- compact policy-preserving prompt for local extraction;
- JSON-schema structured output;
- unnecessary thinking disabled;
- configurable context/output token budgets;
- bounded two-page batching to reduce provider calls while retaining exact page attribution;
- controlled concurrency and localized batch retry;
- provider timing/token/call-count metrics for repeatable benchmarking.

The V1 LOCAL/demo reference model is `qwen3.5:4b`. On the current RTX 1000 Ada 6 GB demo laptop it remained 100% GPU-resident and passed the controlled 5-page/10-page benchmark with full expected finding coverage, correct page attribution, no false positives in the benchmark set, and zero retries/timeouts. LOCAL defaults use batch size 2, 14,000 max batch characters, 8,192 context tokens, 2,048 max output tokens, parallelism 2 and 30-minute keep-alive.

`gemma3:12b` remains an explicitly configurable larger-hardware model; it is never an automatic fallback. Provider/model/inference settings remain profile/deployment configurable and production remains explicit/fail-closed. Any future model change still requires benchmark + accuracy evidence.
