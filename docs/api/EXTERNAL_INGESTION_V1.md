# External Push Ingestion API — V1

This is the current machine-to-machine manifest-first ingestion contract implemented under `/api/v1/external-intakes`.

## Authentication / source binding

Production requests are authenticated through the configured OIDC/JWT resource-server boundary. LexaVault resolves tenant and authenticated client identity from the verified token, then requires an enabled tenant `PUSH_API` Integration Connection whose `clientPrincipal` matches that identity.

LOCAL mode may use the existing demo tenant headers plus `X-Integration-Client` for local integration testing only. Production OIDC mode does not trust those headers.

Machine clients do not receive fake human product roles. Their permission to ingest is derived from authenticated service identity + enabled source binding + tenant capability/policy validation.

## 1. Create external intake

`POST /api/v1/external-intakes`

Example:

```json
{
  "externalSourcePackageId": "SRC-PKG-1001",
  "sourceRevision": "1",
  "businessIdentifiers": {
    "contractNumber": "C-100234",
    "memberNumber": "M-88291"
  },
  "metadata": {},
  "documents": [
    {
      "externalSourceDocumentId": "DOC-001",
      "fileName": "application.pdf",
      "sha256": "<optional-64-hex>"
    },
    {
      "externalSourceDocumentId": "DOC-002",
      "fileName": "income.pdf",
      "sha256": "<optional-64-hex>"
    }
  ],
  "retry": false
}
```

The normal tenant-configured mandatory business-identifier rules are enforced before the intake is accepted.

Representative response fields:

```json
{
  "packageId": "<lexavault-uuid>",
  "externalSourcePackageId": "SRC-PKG-1001",
  "sourceRevision": "1",
  "attemptNumber": 1,
  "intakeStatus": "AWAITING_DOCUMENTS",
  "packageStatus": "CREATED",
  "expectedDocuments": 2,
  "receivedDocuments": 0,
  "missingExternalSourceDocumentIds": ["DOC-001", "DOC-002"],
  "createdAt": "...",
  "expiresAt": "..."
}
```

Recreating the identical active/accepted source package+revision returns the existing intake semantics rather than creating duplicate processing. Reusing the same source package+revision with a different manifest/business payload returns `409 SOURCE_REVISION_REUSED`; use a new revision for resubmission.

If a prior identical attempt expired, `retry=true` creates the next internal attempt number while preserving the expired attempt. `retry=true` without any prior matching source identity is rejected.

## 2. Upload one expected PDF

`POST /api/v1/external-intakes/{packageId}/documents/{externalSourceDocumentId}`

Content type: `multipart/form-data`

Required headers:

- `Idempotency-Key` — operation identity for safe retry;
- `X-Content-SHA256` — SHA-256 of the exact PDF bytes.

Multipart field:

- `file` — PDF binary.

LexaVault validates:

- authenticated source owns the intake;
- intake has not expired/completed;
- external document ID is in the manifest;
- idempotency key is not reused for another manifest document;
- `application/pdf` only;
- configured source size/page limits;
- valid readable PDF;
- source checksum and optional manifest checksum;
- LexaVault-computed SHA-256.

Successful acknowledgement means LexaVault has durably accepted responsibility for that PDF in secure original storage. It does **not** mean AI/redaction processing is complete.

A duplicate retry of an already acknowledged external document with the same immutable content returns the existing document acknowledgement and does not upload/process a duplicate.

When the final expected PDF is acknowledged, LexaVault automatically submits the normal package and dispatches the existing asynchronous processing pipeline.

## 3. Reconcile status

`GET /api/v1/external-intakes/{packageId}`

Returns source package/revision/attempt, intake/package state, expected/received counts, missing external document IDs, and deadline timestamps.

## Expiration

Default incomplete window: 24 hours from intake creation, configurable per source within safe bounds. The deadline does not slide when individual PDFs arrive.

At expiry:

- external intake -> `INCOMPLETE_EXPIRED`;
- package is cancelled if it was still awaiting intake completion;
- further upload to that attempt is rejected (`410 INTAKE_EXPIRED`);
- partial protected binaries are deleted through the secure storage/lifecycle boundary;
- package/intake/document metadata and audit evidence remain;
- cleanup failures remain retryable by the maintenance scheduler;
- an identical retry may explicitly request `retry=true`, creating a new internal attempt while preserving history.

## Error classes

- `400` — request/PDF/limit/identifier validation failure;
- `403` — authenticated client is not bound to the tenant/source or does not own the intake;
- `404` — intake/package not found inside the authenticated tenant boundary;
- `409` — identity/idempotency/checksum/state conflict;
- `410` — intake attempt expired.

Backend tenant/source checks are authoritative; UI or client-side hiding is never a security boundary.
