# LexaVault Business / Operational Reporting R3 — Business Dimensions + SLA Targets

Status: **BUILD CANDIDATE — LOCAL BUILD + CONFIGURATION/REPORT VALIDATION REQUIRED**

R3 keeps the locally approved Reporting R2 UX and durable retained-fact foundation. It adds the two remaining business-intelligence capabilities that were intentionally deferred from R2: tenant-approved business dimensions and tenant-configurable targets/SLA measures.

## 1. Tenant-approved business reporting dimensions

Business Admin now owns which business identifiers may appear as reporting dimensions.

V1 deliberately restricts reporting dimensions to **low-cardinality `ALLOW_LIST` business identifiers with 1–100 approved values**. This prevents high-cardinality identifiers such as Contract Number or Member Number from accidentally becoming a reporting dimension while allowing safe business groupings such as Service Entity.

New retained structures:

- `reporting_dimension_definitions` — tenant-approved reporting dimensions;
- `reporting_package_dimensions` — package-level dimension snapshots retained independently from source package/document rows.

The package dimension table has no foreign key to `document_packages`, so historical business breakdowns remain available after source package/document deletion. Values are captured only for dimensions explicitly enabled by Business Admin.

When a dimension is enabled, R3 backfills retained facts from existing long-lived business-record metadata. New reporting facts capture approved dimensions automatically.

Business Admin reporting shows, for each approved dimension value:

- package count;
- delivered count;
- selected-period delivery conversion;
- retained median turnaround support in the API model.

`REPORT_VIEWER` does not receive dimension breakdowns. Dimension values are exposed only to Business Admin-capable report consumers.

## 2. Tenant-configurable reporting targets

Business Admin → **Reporting & Business Targets** adds tenant-owned targets for:

- delivery time target (default 240 minutes);
- delivery SLA target percentage (default 95%);
- straight-through delivery target (default 90%);
- review-wait target (default 60 minutes).

The targets are persisted in `reporting_tenant_settings` and are not infrastructure/provider configuration.

## 3. SLA / target measurements

The report API now returns a `serviceLevel` section with:

- delivery eligible sample count;
- percentage delivered within the configured target;
- delivery breach count;
- configured delivery SLA percentage;
- actual versus target straight-through rate;
- review-start sample count;
- percentage whose review started within target;
- review wait breach count.

Delivery SLA uses the selected-period intake cohort and only packages delivered by the period end. Review wait uses retained review-created → review-started evidence.

Executive Reporting adds the current delivery-within-target rate to the turnaround KPI. Business Admin Reporting adds a **Targets vs actual** panel.

## 4. Privacy / tenant guardrails

- No OCR text, document text, findings, prompts, model responses, or document contents are copied to reporting dimensions.
- V1 blocks arbitrary/high-cardinality identifier definitions from reporting dimensions.
- Every reporting configuration and package-dimension row is tenant-scoped.
- Tenant Business Admin can configure only identifier definitions belonging to its own tenant.
- Reporting configuration changes are audited as `REPORTING_CONFIGURATION_CHANGED`.
- REPORT_VIEWER remains aggregate/executive-only and cannot configure targets or receive Business Admin dimension breakdowns.

## 5. Database migration

Liquibase `026-reporting-dimensions-sla.xml` adds four changesets:

1. tenant reporting settings;
2. tenant reporting dimension definitions;
3. retained package dimension snapshots;
4. PostgreSQL capture/backfill functions and trigger.

Expected total Liquibase changesets after R3: **59**.

## Still not frozen

R3 completes durable historical facts, business dimensions and configurable SLA/targets, but Reporting remains ACTIVE until:

1. final export formats/reconciliation are accepted (CSV plus planned executive PDF/Excel decision);
2. retained-fact/dimension totals are reconciled against representative live operational data;
3. role/tenant-isolation and migration behavior are independently verified;
4. the final targeted Reporting verification checkpoint passes.

Do not spend Kiro credits before local build/migration/configuration validation is clean.

## Local validation

```powershell
.\build-all.cmd
.\start-local.cmd -LoggingFormat Readable
```

On first R3 startup, confirm Liquibase reaches **59** changesets.

Then validate:

1. Business Admin → Reporting & Business Targets loads.
2. Service Entity appears as an eligible reporting dimension in the local demo because it is an allow-list identifier; Contract Number and Member Number must not be eligible.
3. Enable Service Entity and save.
4. Change targets if desired and save; refresh and confirm they persist.
5. Open Reporting → Business Admin and confirm the Targets vs actual panel.
6. Confirm a Service Entity dimension panel appears with package/delivery counts after the dimension is enabled/backfilled.
7. Executive turnaround shows the within-target percentage.
8. Existing R2 totals, current operational risk, and Usage & Adoption values remain consistent.
