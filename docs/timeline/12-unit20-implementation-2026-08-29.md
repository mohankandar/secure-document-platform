# Unit 20 Implementation — Business Record + Identifiers + Unified Intake

**Status:** IMPLEMENTED IN SOURCE; awaiting full build/runtime verification

## Goal

Introduce the long-lived business identity and intake-batch concepts required by the frozen V1 product model without redesigning the already-approved AI/OCR/redaction pipeline.

The core relationship is now:

`Business Record -> many Intake Batches (existing DocumentPackage aggregate) -> Documents`

The existing `DocumentPackage` class is intentionally retained for compatibility, but a newly created package now carries intake-batch semantics and may link to a long-lived `BusinessRecord`.

## Business identifier model

Added tenant-owned identifier definitions with:

- configurable key and business label
- required/optional
- exactly one active Primary identifier per tenant
- searchable
- extractable
- displayable
- display order
- active/inactive state

Multiple identifiers may be required. `Required` and `Primary` are independent concepts.

A PostgreSQL partial unique index enforces at most one active Primary definition per tenant, while application validation requires exactly one active Primary before intake is accepted.

## Business record resolution

`BusinessIdentityService` validates configured identifiers before creating an intake batch:

- unknown identifier -> structured rejection
- missing required identifier -> structured rejection
- identifiers resolving to different existing records -> conflict rejection
- same identifiers on a later intake -> reuse the existing Business Record
- new valid identifier combination -> create a Business Record
- a compatible new optional identifier may be added to an existing Business Record

Identifier values are normalized for matching, but customer display values are preserved. Sensitive/business identifier values are not written into audit details.

## Manual / scan / API intake semantics

The existing create/upload/submit flow now requires tenant-configured business identifiers before a new intake batch is created.

- Manual UI sends `MANUAL_UPLOAD` with configured identifier values.
- API clients may use the same create contract with `API_SUBMISSION` and the required identifier map.
- Direct `FOLDER_INTAKE` creation through the normal API is rejected; watched-folder batches must originate from the trigger/manifest intake path.
- Existing document upload and submit processing remain reused rather than duplicated.

Local demo bootstrap seeds two definitions to prove the multi-identifier model:

1. `Contract Number` — Required + Primary
2. `Member Number` — Required, non-Primary

Unit 21 will replace this demo-only configuration behavior with Business Admin configuration screens/APIs.

## Watched-folder intake semantics

Added a provider-neutral watched-folder source port plus a local filesystem adapter and one explicit scan-cycle use case.

Current V1 behavior:

- child folder without trigger/manifest is not ingested
- a no-trigger folder becomes a persisted `WAITING_FOR_TRIGGER` observation and is surfaced only after the configured observation threshold
- manifest supplies `businessIdentifiers`, optional metadata, and optionally an explicit file list
- manifest is considered the batch-complete signal
- missing required identifiers, malformed trigger, missing file, unsupported file, no documents, source I/O failure, and duplicate intake have structured reason codes
- deterministic SHA-256 fingerprint includes tenant, identifiers, metadata, filenames/sizes, and file contents
- accepted fingerprint is stored on the intake batch for idempotency
- identical already-consumed intake is not processed again
- corrected metadata/content generates a different fingerprint and can produce a new intake batch
- source post-ingest disposition supports leave, move/copy to Archive, and move/copy to Problem according to configuration
- default behavior is `LEAVE`, appropriate for customer-owned/read-only-style source shares
- disposition failures do not rewrite an already durable intake outcome or abort the rest of the scan cycle; Unit 24 owns operational health/configuration for these filesystem actions
- observation history preserves separate intake episodes for a reused source folder

Automatic watched-folder scheduling and connection administration are deliberately deferred to Unit 24. Unit 20 provides the intake semantics and explicit ADMIN scan endpoint only.

## Persistence

Liquibase migration `008-business-record-intake.xml` adds:

- `business_identifier_definitions`
- `business_records`
- `business_record_identifiers`
- nullable Business Record link + fingerprint + source reference on legacy `document_packages`
- `intake_observations`
- tenant/value uniqueness and reporting-oriented indexes

Legacy packages remain readable because the new Business Record link is nullable.

PDF/document binaries remain outside PostgreSQL.

## API additions

Added/extended APIs for:

- tenant identifier-definition read needed by intake UI
- business identifiers on package create, list, and detail responses
- ADMIN explicit watched-folder scan cycle
- ADMIN watched-folder intake observations

Normal business APIs continue to be tenant-scoped through the existing request context/security boundary.

## Transitional frontend changes

Unit 20 makes only the UI changes necessary to exercise the new model:

- `Upload Documents` renders tenant-configured identifier fields dynamically
- all configured required identifiers must be entered before proceeding
- the Primary identifier is labeled but additional identifiers may also be required
- Intake Details displays business identifiers by their configured labels and hides the package UUID from normal presentation
- the transitional package list uses the configured Primary business identifier as its leading column instead of a package UUID

The approved full Dashboard mockup — collapsible KPI overview, stage chips, configurable/reorderable columns, all visible business identifiers, search, and reporting — remains Unit 23 scope.

## Source-level verification completed during implementation

- all domain + application Java sources compiled successfully with Java 21
- changed infrastructure persistence/watched-folder sources were syntax/integration compiled using local dependency stubs
- Business Record identity smoke verified required identifier rejection, record reuse, new-record creation, and cross-record identifier conflict
- watched-folder application smoke verified waiting-without-trigger, accepted triggered batch, same-fingerprint no re-ingest, and corrected-content new intake
- changed frontend sources passed TypeScript syntax transpilation; a full frontend type/build could not be claimed because package installation was incomplete in this environment

## Deliberate scope limits

Unit 20 does **not** implement:

- Business Admin CRUD/configuration UI (Unit 21)
- sensitive-data/confidence/retention policy admin (Unit 21)
- S3/API delivery and lifecycle/retention (Unit 22)
- full approved Dashboard/reporting UI (Unit 23)
- automatic watched-folder scheduling, connection management, or filesystem health UI (Unit 24)
- production identity/IDP work (Unit 25)
- a large schema/refactor of the approved Unit 16–19 processing pipeline
