# LexaVault → SDV Delivery Contract — V1

**Status:** Requirement 3 implementation candidate (2026-09-03)

LexaVault and SDV are independent applications. This document defines the external HTTP contract LexaVault expects from an SDV V1 ingestion endpoint. No shared source library, database/schema, internal ID, domain model, or deployment dependency is permitted.

## Boundary

LexaVault sends only approved/permanently redacted PDFs plus the minimum required business/integration metadata. Original PDFs, raw sensitive finding values/snippets, AI prompts, and internal processing details are not sent.

One LexaVault export cycle maps to one SDV submission. LexaVault-generated `sourceSubmissionId` and `sourceDocumentId` values are external integration identities and are distinct from LexaVault internal package/document UUIDs.

## Create or resume submission

`POST {configured-sdv-base}/api/v1/submissions`

Headers:

- `Content-Type: application/json`
- `Idempotency-Key: <sourceSubmissionId>`
- configured authentication headers

Body:

```json
{
  "sourceSubmissionId": "sub-...",
  "sourceRevision": "1",
  "businessIdentifiers": {
    "contractNumber": "..."
  },
  "expectedDocumentCount": 2,
  "documents": [
    {
      "sourceDocumentId": "doc-...",
      "fileName": "application.pdf",
      "contentType": "application/pdf",
      "sizeBytes": 12345,
      "sha256": "..."
    }
  ]
}
```

Expected 2xx response includes:

```json
{
  "submissionId": "<sdv-owned-id>",
  "status": "AWAITING_DOCUMENTS"
}
```

Creation is idempotent for the same `sourceSubmissionId`.

## Upload one redacted PDF

`POST {configured-sdv-base}/api/v1/submissions/{submissionId}/documents`

Headers:

- `Content-Type: application/pdf`
- `Idempotency-Key: <sourceSubmissionId>:<sourceDocumentId>`
- `X-Source-Document-Id: <sourceDocumentId>`
- `X-Original-File-Name: <original filename>`
- `X-Content-SHA256: <sha256>`
- configured authentication headers

Body: streaming redacted PDF bytes.

Expected 2xx acknowledgement may include:

```json
{
  "documentId": "<sdv-owned-id>",
  "sourceDocumentId": "doc-...",
  "status": "RECEIVED"
}
```

A retry uses the same idempotency key and immutable content.

## Confirm submission completion

`GET {configured-sdv-base}/api/v1/submissions/{submissionId}`

LexaVault marks the master delivery successful only when SDV returns a 2xx response whose `status` is `COMPLETE`.

Until then LexaVault does **not** consume a successful export cycle and the post-export retention clock does not begin.

## Retry / partial acknowledgement

LexaVault persists the SDV submission reference and per-document acknowledgements. If a later document or final status check fails, a technical retry:

- reuses the same `sourceSubmissionId`;
- reuses each `sourceDocumentId`;
- does not rerun redaction;
- does not re-upload already acknowledged documents;
- retries only unacknowledged documents / final completion confirmation.

## Profiles

- `local`: local filesystem master delivery; no SDV connection is required.
- `local-sdv`: real LexaVault→SDV contract testing.
- non-local/default deployment: SDV delivery, fail-closed when no enabled tenant `SDV_DELIVERY` connection exists.

Customer filesystem/S3/object-storage selection is an SDV concern and is not part of this LexaVault contract.


## Safe connection health check

`GET /api/v1/health` is the provider health/authentication probe used by LexaVault IT administration. It must not create a submission, transfer a document, or return secrets/sensitive document data. Any 2xx response means the configured SDV endpoint accepted the authenticated health check.

