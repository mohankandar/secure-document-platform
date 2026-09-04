# LexaVault V1 — Scheduled Polling / Pull Ingestion Contract

**Status:** Requirement 2 implementation candidate; frozen product contract.

## Responsibility boundary

LexaVault initiates polling against a tenant-configured trusted outbound HTTP ingestion source. The source owns the available-for-pickup queue. LexaVault retrieves each discovered submission/revision, durably receives every expected PDF into LexaVault-controlled secure original storage, and only then acknowledges pickup. Pulled documents never use the watched folder as intermediate storage.

## Standard source contract

The configured base endpoint exposes the LexaVault V1 pickup contract below it:

- `GET /lexavault/v1/pickups?limit={n}` — discover available packages;
- `GET /lexavault/v1/pickups/{sourcePackageId}/revisions/{sourceRevision}/documents/{externalSourceDocumentId}` — retrieve one PDF;
- `POST /lexavault/v1/pickups/{sourcePackageId}/revisions/{sourceRevision}/acknowledgement` — acknowledge complete durable receipt.

Discovery returns the source package identity, source revision, optional retry indicator, tenant-required business identifiers, safe metadata, and expected document manifest.

## Scheduling

Tenant IT Admin configures either:

- interval polling (5–1440 minutes); or
- daily polling at a local time plus IANA time zone.

The UI does not expose raw cron. `Run Now` uses the same polling service as scheduled execution. Only one active run may own a tenant/source connection at a time.

## Receipt and acknowledgement

For each missing manifest PDF, LexaVault retrieves to controlled temporary staging, enforces configured byte limits/content type, computes SHA-256, validates through the existing external-intake/PDF path, then commits through LexaVault secure original storage. Temporary staging is deleted after the receive attempt.

Source acknowledgement occurs only after the intake reaches `ACCEPTED_FOR_PROCESSING` (all expected documents durably received). If acknowledgement is lost, the source may expose the same immutable submission/revision again; LexaVault recognizes the existing accepted intake and reissues acknowledgement without reprocessing.

## Retry / resubmission / incomplete behavior

- retrieval retries repeat retrieval of the same immutable source document and do not rerun downstream processing;
- retry remains same source package + revision + manifest/content;
- materially changed content requires a new source revision (resubmission);
- the same fixed configurable incomplete window from Requirement 1 applies (default 24 hours, no sliding reset);
- an expired attempt is not acknowledged as received; a source may explicitly retry that same immutable revision according to Requirement 1 attempt semantics;
- prior revisions are not replaced/deleted automatically.

## Operations and security

Polling status and `Run Now` require `TENANT_INTEGRATION_MANAGE`. Source credentials are referenced through protected secret references. The connector refuses redirects and arbitrary per-document URLs; all request paths are derived beneath the IT-configured trusted base endpoint. Polling run history records safe counts/status/failure categories for Technical Attention/reporting without credential material.
