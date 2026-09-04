# Unit 20 — Business Record + Identifiers + Unified Intake

Unit 20 implements the frozen V1 business identity and intake semantics. It must not redesign Units 16–19.

## Non-negotiable rules

- Business Record is long-lived and may receive many Intake Batches.
- Existing DocumentPackage is retained internally but newly created instances represent Intake Batches.
- A tenant may define multiple business identifiers.
- Exactly one active identifier is Primary.
- Multiple identifiers may independently be Required, searchable, extractable, displayable, and ordered.
- Required does not imply Primary.
- Manual/API intake must reject missing required identifiers before accepting the batch.
- Same valid identifiers reuse the same Business Record; conflicting identifiers must fail closed.
- Watched-folder intake requires a trigger/manifest appearing last; no-trigger folders are not ingested.
- Watched-folder fingerprint is an internal deterministic idempotency key, not a business identifier.
- Corrected content/metadata may produce a new fingerprint/new batch.
- Source handling is environment configurable; default leave-in-place is valid for customer-owned shares.
- Missing trigger/malformed trigger/missing required identifiers/missing files/unsupported files/duplicates are structured and reportable.
- PostgreSQL stores metadata only, not PDF binaries.
- Do not expose raw business identifier values in audit details.

## Scope boundaries

Unit 21 owns Business Admin configuration CRUD/UI.
Unit 22 owns final delivery adapters and retention/reprocessing lifecycle.
Unit 23 owns the full approved Dashboard/reporting UI.
Unit 24 owns watched-folder connection/scheduler/technical administration.
Unit 25 owns production identity/IDP hardening.
