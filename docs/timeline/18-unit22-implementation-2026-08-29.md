# Unit 22 Implementation — Delivery + Storage Lifecycle + Retention/Reprocessing

**Status:** IMPLEMENTED IN SOURCE — AWAITING BUILD/RUNTIME VERIFICATION

## Goal

Complete the V1 delivery and internal-artifact lifecycle around the already-approved AI/OCR/redaction engine. LexaVault remains a processing platform, not a long-term original-document vault.

## Implemented behavior

### Delivery abstraction
- `DeliveryPort` now publishes a complete package by explicit successful export cycle.
- Local delivery preserves original filenames under tenant/package/cycle/document isolation and stages the full package before publication.
- API/callback delivery sends one package-level multipart request and treats only a 2xx response as successful delivery.
- S3-compatible delivery stages objects privately, copies the complete cycle to its final prefix, and writes `_COMPLETE` last as the publication boundary. Consumers must treat the marker as completion.
- Provider selection is deployment configuration (`securedoc.delivery.type`); provider connection details remain an IT/Platform Admin concern.

### Successful export records
- Successful package export creates an immutable package export record plus per-document records containing filename, size, checksum, and destination location.
- The business export cycle increments only after the complete destination acknowledges/publishes the batch.
- Technical delivery retries before success do not consume an export cycle.
- Customer filenames and document boundaries remain unchanged.

### Retention
Tenant Business Admin lifecycle policy now controls:
- original retention after successful export (default 24h),
- internal redacted-copy cache (default 24h, hard max 72h),
- never-successfully-exported technical hold (default 72h, max 14 days),
- optional final delivery approval.

The normal original-retention clock starts only after successful delivery. A later successful reprocess/re-export resets both original and internal-redacted deadlines from the newest success.

### Reprocessing/export-cycle limit
- A successfully exported package may be reprocessed from its retained original while the original is still available.
- Prior working outputs/findings are cleared; the original is retained and reused.
- Reprocess automatically re-enters the existing AI/OCR/redaction pipeline.
- Maximum V1 successful export cycles for one intake lineage: **3**.
- After the third successful cycle, no further reprocess is allowed.
- Failed delivery attempts do not consume a cycle.

### Never-exported technical failures
- A package that fails technically before any successful export receives a bounded technical hold.
- Default: 72h. Business/authorized admin may extend within the configured V1 safety window up to 14 days.
- Hold expiry makes remaining internal artifacts eligible for purge.

### Purge
- Internal REDACTED cache is removed when `redacted_delete_at` is due.
- ORIGINAL and remaining internal working artifacts are removed when `original_delete_at` or a never-exported technical hold expires.
- Metadata, export records, checksums, destination facts, and audit history remain.
- Purge is auditable and safe to retry at the item level.
- Minimal V1 in-process scheduled purge runs periodically; an ADMIN endpoint also exists for explicit verification/operations. Durable scheduling can replace this later.

### Final delivery approval
If tenant policy enables final approval:
- redaction and verification complete first,
- package enters `PENDING_FINAL_APPROVAL`,
- nothing is delivered yet,
- an `APPROVER` releases final delivery,
- processing resumes automatically and delivery occurs,
- retention begins only after successful delivery.

### Active V1 original access
- The active frontend Vault route was removed.
- The original-artifact download endpoint was removed.
- Manual Review still uses a rendered single-page preview, not an original-PDF download.
- Legacy vault/access backend structures may remain for compatibility but are no longer the active V1 product flow.

## Database additions
Liquibase `010-delivery-lifecycle.xml` adds:
- `tenant_lifecycle_policies`
- `package_lifecycles`
- `package_export_records`
- `document_export_records`
- due-retention indexes and tenant/package constraints.

PDF binaries remain outside PostgreSQL.

## Known bounded limitations
- API callback delivery currently buffers the package multipart body in memory; production streaming is a later hardening optimization.
- S3 package publication uses a `_COMPLETE` marker as the atomic visibility contract because object stores do not provide an atomic multi-object rename.
- External destination success plus local metadata persistence cannot be a single distributed transaction; final hardening may add stronger idempotent delivery reconciliation.
- Retention scheduler is in-process V1 scheduling, not durable clustered scheduling.
- Technical exception reporting projection remains a later reporting/platform concern.

Unit 23 Dashboard/reporting was not started.
