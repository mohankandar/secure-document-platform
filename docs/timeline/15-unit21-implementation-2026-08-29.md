# Unit 21 Implementation — Business Admin

Date: 2026-08-29
Status: **IMPLEMENTED IN SOURCE; AWAITING BUILD/RUNTIME VERIFICATION**

## Goal

Replace demo-seeded business configuration as the only management path with a minimal tenant Business Admin experience. Unit 21 configures business terminology/identifiers and sensitive-data policy while preserving the approved AI-first processing pipeline.

## Business identifier administration

`/business-admin` now lets an ADMIN manage the active tenant identifier configuration:

- add an identifier by business label (for example Contract Number, Member Number, Loan Number),
- remove/deactivate an identifier without deleting historical stored values,
- reorder identifiers,
- choose exactly one Primary identifier,
- independently configure Required, Searchable, Extractable, and Displayable flags.

Safety rules:

- at least one active identifier is required,
- exactly one active identifier is Primary,
- Primary must also be Required and Displayable,
- labels must be unique within the active configuration,
- stable internal keys are preserved for existing definitions and generated for new definitions,
- changing Primary is applied transactionally so the PostgreSQL one-primary partial unique index is not violated mid-update.

The existing manual/API/watched-folder intake paths immediately read the active configuration, so future intakes use the new labels and Required rules without hardcoded mortgage terminology.

## Sensitive-data policy administration

Unit 21 adds a tenant-owned persisted redaction policy (`tenant_redaction_policies`) with:

- enabled V1 categories: SSN, Date of Birth, Credit Card Number, Credit Score,
- automatic-redaction confidence threshold,
- review mode:
  - `UNCERTAIN_ONLY` — recommended straight-through behavior,
  - `REVIEW_ALL` — temporary trust-building posture.

Runtime detection now loads this tenant policy instead of using a hardcoded threshold/type set:

- AI semantic discovery still runs independently on every normalized text-bearing document,
- deterministic recognition/validation remains supporting evidence,
- disabled policy categories are filtered from the actionable finding pipeline,
- only enabled findings that individually meet the tenant threshold and have trusted geometry auto-confirm in `UNCERTAIN_ONLY`,
- `REVIEW_ALL` leaves every enabled finding pending for focused Manual Review,
- OCR confidence/location safety continues to bound the effective finding confidence.

Focused Review reason labeling now uses the tenant threshold/review posture as well.

## API

ADMIN-gated endpoints:

- `GET /api/v1/business-admin/configuration`
- `PUT /api/v1/business-admin/identifiers`
- `PUT /api/v1/business-admin/sensitive-data-policy`

Configuration changes are tenant-scoped and safely audited without identifier values or document content.

## UI

A minimal Business Admin page was added. It intentionally does not redesign the shared Dashboard.

The page provides:

- ordered identifier rows,
- business labels rather than technical keys,
- Primary/Required/Searchable/Extractable/Displayable controls,
- add/remove/reorder actions,
- V1 sensitive-category toggles,
- confidence-threshold control,
- `Review only uncertain findings` vs `Review every finding`.

The Business Admin navigation item is shown in the local/demo UI only when the selected demo role includes ADMIN; backend role enforcement remains authoritative.

## Deliberately deferred

- Final approval after redaction: deferred until Unit 22 can hold output before final delivery.
- Original/redacted retention and reprocessing settings: Unit 22, after lifecycle implementation exists.
- Full Dashboard/KPI/stage/search/configurable-column UI: Unit 23.
- Separate BUSINESS_ADMIN vs PLATFORM_ADMIN production roles: later identity/platform-admin work; current ADMIN temporarily owns both configuration surfaces.
- Additional semantic confidential-content categories beyond the four V1 categories: later policy expansion using the same AI -> Finding -> Policy -> Redaction architecture.
