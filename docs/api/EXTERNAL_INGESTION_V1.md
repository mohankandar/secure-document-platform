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

### Sensitivity levels (task 0021)

Optional. The source system sets them; LexaVault keeps them, passes them on unchanged when it exports, and never changes them. They limit nothing inside LexaVault.

- `caseSensitivityLevel` on the request: the level of the whole case (contract).
- `sensitivityLevel` on each entry of `documents`: that document's own level.
- Values: `STANDARD`, `CONFIDENTIAL`, `HIGHLY_CONFIDENTIAL`, exact capitals. Nothing sent (field absent or `null`) means `STANDARD`.
- **An unknown or wrongly cased value is never refused.** That case or document is taken in as `STANDARD`, the request still succeeds, and the warning `LV-INT-005` "Unknown sensitivity level, taken as Standard" is recorded in the audit trail (the code only, never the value sent). The response lists it:

```json
{
  "warnings": [
    { "code": "LV-INT-005", "message": "Unknown sensitivity level, taken as Standard", "scope": "CASE", "externalSourceDocumentId": null },
    { "code": "LV-INT-005", "message": "Unknown sensitivity level, taken as Standard", "scope": "DOCUMENT", "externalSourceDocumentId": "DOC-002" }
  ]
}
```

`warnings` is empty when there is nothing to say, and on status reads. A replay of the same request answers with the same warnings and records nothing new.

A level is part of the request's identity only when one is sent: requests without levels keep the identity they had before, so an older intake can still be retried. Sending a different level for the same source package and revision is a changed request (`409 SOURCE_REVISION_REUSED`).

The effective level of a document is the higher of its own level and its case's level (`SensitivityLevel.effective`).

The scanner intake (`POST /api/v1/scanner-intakes`) takes the same `caseSensitivityLevel`; the Scan Bridge sends the New case "Sensitivity" choice there. The polling contract (`POLLING_INGESTION_V1.md`) takes the same two fields.

## 2. Upload one expected PDF

`POST /api/v1/external-intakes/{packageId}/documents/{externalSourceDocumentId}`

Content type: `multipart/form-data`

Required headers:

- `Idempotency-Key` — operation identity for safe retry;
- `X-Content-SHA256` — SHA-256 of the exact PDF bytes.

Multipart field:

- `file` — PDF binary;
- `password` — optional, only for a password-protected PDF (task 0063): the user or the owner password, up to 127 bytes of UTF-8.

### Password-protected PDFs (task 0063)

This upload is the only way to send a password-protected PDF. The watched folder, file polling, the scanner and the New case screen (which has no password field) refuse one with `LV-INT-008` "Password-protected file. Send it through the API."

- Send the password in the body part `password`, over HTTPS. In the address (`?password=`) or over plain HTTP it is refused with `LV-INT-037`. Behind a proxy that ends HTTPS, the proxy must send `X-Forwarded-Proto: https`. A call from the same machine may use HTTP.
- The password is held in memory for the decryption only. It is never stored, logged, audited or repeated in an answer. The audit trail says only "decrypted with a supplied password".
- A password that doesn't open the PDF is refused with `LV-INT-036`, with no hint. Nothing is taken in; the same document can be sent again with the right one.
- The decrypted PDF is checked, stored and processed as any other PDF. The encrypted file is not kept. `X-Content-SHA256` and the manifest checksum are those of the file as sent, so a retry of the same encrypted file is still recognised.
- A password sent with a PDF that has none is ignored.
- Never put the password in the metadata, the file name or the identifiers: those are kept.

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

## Error body

Every refusal has the same JSON body:

```json
{
  "errorCode": "MISSING_HEADER",
  "code": "LV-INT-002",
  "message": "Required header or part missing",
  "detail": "Header X-Content-SHA256 is missing",
  "correlationId": "c0ffee00-0000-4000-8000-000000000001",
  "timestamp": "2026-11-21T10:15:30Z"
}
```

- `code` is the LexaVault error code; `message` is its title. The customer error code guide (`docs/api/error-codes.md`) explains each code and what to do.
- `errorCode` is the older internal identifier, kept for API version 1.
- `detail` (task 0026) is optional and left out when there is none. For intake and settings refusals it names **what** is wrong: the field, header or part, and the allowed range (for example `"Case field Loan number is required"`, `"Content-Type must be one of: multipart/form-data"`, `"The upload is larger than the limit of 52428800 bytes"`). It never repeats a value you sent; for a case field whose value breaks its rule it names only the field.
- `correlationId` is the reference number to give IT.

An address LexaVault doesn't offer answers `404` and a wrong method `405`, both with `code` `LV-SYS-009` and the same body (a request without valid sign-in gets `401` `LV-AUT-001` first).

## Error classes

- `400` — request/PDF/limit/identifier validation failure;
- `403` — authenticated client is not bound to the tenant/source or does not own the intake;
- `404` — intake/package not found inside the authenticated tenant boundary;
- `409` — identity/idempotency/checksum/state conflict;
- `410` — intake attempt expired;
- `429` — LexaVault is full for now (`code` `LV-INT-027`, `errorCode` `INTAKE_BUSY`, header `Retry-After` in seconds).

## Busy: the safety stop

LexaVault always takes documents in; a flood only makes processing take longer. The one exception is too little free space: below 10% free disk (or database space, when IT set its size) every request that creates an intake or sends a document is answered `429` with `LV-INT-027` and `Retry-After`. Nothing is lost and nothing of the refused request is kept: send the same request again after that time, with the same `Idempotency-Key`. Intake starts again by itself above 15% free. IT can set both thresholds and the time. Status requests are always answered. The scanner intake (`/api/v1/scanner-intakes`) and the app's own upload answer the same way; the watched folder and polling leave files at their source.

While the queue is long, the watched folder and polling also check less often and take fewer files each time; the files wait at the source.

Backend tenant/source checks are authoritative; UI or client-side hiding is never a security boundary.
