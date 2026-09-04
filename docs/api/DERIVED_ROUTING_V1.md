# LexaVault V1 — Derived Redacted Document Routing

**Status:** Requirement 4 accepted after local/Kiro verification on 2026-09-03.

## Contract

The full permanently redacted master remains intact and is delivered through the accepted master-delivery provider (SDV outside `local`). Derived routing is an independent post-export track and cannot roll back master `EXPORTED` status or its retention clock.

Derived PDFs are built only from permanently redacted source pages and preserve original page order. Routing marker detection is deterministic and limited to `TOP_RIGHT`, `BOTTOM_LEFT`, or `BOTH`; full-page routing-marker scans and AI destination guessing are prohibited.

Supported marker types: `BARCODE`, `QR_CODE`, `TEXT_PATTERN`. Text-pattern matching normalizes decoration/non-alphanumerics so a business marker value such as `77777777` can match a decorated OCR form such as `|||||77777777|||||`. Ambiguous pages become routing attention rather than selecting a destination.

## Ownership

- Business Admin owns marker → named destination mapping.
- Tenant IT Admin / Platform Operations Admin own the technical `DERIVED_DOCUMENT_DELIVERY` Integration Connection, credentials, health and technical retry.
- Document workflow roles may send/resend only an already-approved derived destination. They cannot supply arbitrary URLs or paths.

## Providers

V1 typed providers:

- `HTTP_API` + `DESTINATION_RECEIPT`; requires an actual receipt/document identifier after successful upload.
- `FILE_SHARE` + `DURABLE_FILE_WRITE`; writes beneath the IT-configured root, fsyncs the temporary file, then atomically renames where supported.

HTTP uploads stream the existing derived artifact and use stable `sourceDerivedDocumentId` as the idempotency identity.

## Lifecycle

One derived-delivery record is created per tenant + package + export cycle + source document + routing rule. This is deliberately export-cycle scoped so a reprocess/new master export can create a new derived output without rewriting prior history.

States: `PENDING`, `DELIVERING`, `RETRY_PENDING`, `DELIVERED`, `ATTENTION_REQUIRED`.

Technical failures are independent of the master SDV export. Delivery retries reuse the already-created derived redacted artifact; they do not rerun OCR, AI detection, review, or redaction.
