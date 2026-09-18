# LexaVault Business / Operational Reporting R2 — Durable Reporting Facts

Status: **BUILD CANDIDATE — LOCAL BUILD + DATA/UX VALIDATION REQUIRED**

R2 keeps the approved Reporting R1 UX direction and adds the first durable historical reporting foundation. It also fixes the selected-intake `NaN` presentation defect found during local review.

## What R2 changes

### 1. Durable package-independent reporting facts

Liquibase `025-reporting-package-facts.xml` adds two tenant-scoped reporting projections:

- `reporting_package_facts` — one retained aggregate fact row per package;
- `reporting_package_events` — retained key package activity events used for historical reporting such as export and reprocess activity.

The fact table deliberately has **no foreign key to `document_packages`, `documents`, or business-record rows**. A later package/document deletion therefore cannot cascade-delete historical reporting facts. Tenant ownership remains enforced through `tenant_id`.

The projection retains aggregate/non-document-content metadata such as:

- intake source and received timestamp;
- last known workflow outcome;
- document/page counts;
- first/last successful delivery and export-cycle count;
- whether review occurred and first review lifecycle timestamps;
- accumulated active-processing time;
- reprocess count/activity;
- purge/source-deletion markers.

No OCR text, document text, findings, prompts, model responses, or protected document values are copied into the reporting projection.

### 2. Backfill for the current environment

Liquibase backfills the retained reporting facts from existing package, document, export, review, audit, and lifecycle metadata when R2 is first started. Existing local/demo history should therefore immediately contribute where enough lifecycle evidence exists.

### 3. Ongoing projection maintenance

PostgreSQL triggers keep the retained reporting projection current when:

- package state changes;
- documents/pages are added or updated;
- successful exports occur;
- review tasks are created/started/completed;
- package processing cycles complete;
- reprocess is requested;
- lifecycle purge is recorded;
- a future physical package-row deletion occurs.

This is intentionally a reporting read-model/projection concern; it does not alter the authoritative package workflow.

### 4. Historical report queries use retained facts

Selected-period reporting now reads retained facts for:

- packages/documents/pages received;
- selected-intake delivery conversion;
- delivery activity/trends;
- channel mix;
- straight-through/reviewed delivery;
- end-to-end turnaround;
- active processing time;
- review wait/handling time;
- reprocess activity;
- delivered-package/export-cycle aggregates.

Point-in-time operational risk (current backlog, current review/technical queues, retention due-soon) intentionally remains based on live operational state.

### 5. `NaN` hardening

The UI now defensively normalizes missing/non-finite selected-intake fields so `NaN` can never render. After the R2 backend migration/build, the backend should provide the actual cohort values; the frontend fallback is only a safety guard.

### 6. Clarifying copy

Business Admin now explains:

- why successful export cycles can exceed delivered-package counts after reprocessing;
- why older packages may still show `No data` when historical lifecycle telemetry is incomplete.

## Still not frozen

R2 establishes durable historical facts but Reporting still needs before freeze:

1. tenant-approved configurable business reporting dimensions;
2. tenant-configurable SLA/target definitions and breach measures;
3. final Excel/CSV/executive-PDF export experience and reconciliation;
4. final role/tenant-isolation/performance verification;
5. final reconciliation of retained facts against live operational totals.

Do not spend Kiro credits on R2 yet. First validate the migration and the four Reporting views locally.

## Local validation

```powershell
.\build-all.cmd
.\start-local.cmd -LoggingFormat Readable
```

Expected on first startup:

- Liquibase applies the new Reporting R2 changesets;
- Reporting opens without SQL errors;
- Selected intake delivered/conversion show numeric values, never `NaN`;
- package/document/page/source totals remain consistent with prior R1 values;
- timing cards populate where retained/backfilled lifecycle evidence exists;
- current operational risk remains unchanged in meaning.
