# Report 0043: R1 part 1, new pattern types (detection only)

Date: 2026-11-23
Branch: `feature/0043-r1-patterns` (from `feature/0046-tablet-44px`)
Outcome: **STOPPED: a migration is needed to store the new kinds' on/off flag per entity (the task's own stop condition).**

## What was done
- Read the task and checked where an entity's redaction kinds are switched on and off.
- `tenant_redaction_policies` (migrations 009 and 012) holds **one boolean column per kind**: `ssn_enabled`, `date_of_birth_enabled`, `credit_card_number_enabled`, `credit_score_enabled`. `JpaTenantRedactionPolicyRepositoryAdapter` maps exactly those four, and `TenantRedactionPolicy` refuses any other kind (`V1_CONFIGURABLE_TYPES`; the same check is in `SensitiveContentAnalysisPort.AnalysisPolicy`).
- There is no generic per-entity setting table that could hold the flags (checked migrations 001–046). `findings.entity_type` is a plain `VARCHAR(50)` with no check constraint, so the findings themselves need no migration.
- So "off by default, a Business Admin turns it on" cannot be stored without a schema change. The task says: "if one is needed, stop and describe it (stop rule) instead of adding it." No product code was changed.

## The migration that would be needed (for the owner's decision)
Option 1 (smallest, same shape as today): migration `047-redaction-new-kinds.xml` adds to `tenant_redaction_policies`, each `BOOLEAN NOT NULL DEFAULT FALSE`:
`bank_routing_number_enabled`, `bank_account_number_enabled`, `us_tax_id_enabled`, `drivers_licence_enabled`, `passport_enabled`
(or one column per kind as the owner splits them, see question 2). Existing rows get `false`, so nothing changes for existing entities. Rollback: drop the columns.

Option 2 (fits the R1 catalog that comes next): migration `047-tenant-redaction-kinds.xml` creates `tenant_redaction_kinds (tenant_id UUID, kind_key VARCHAR(64), enabled BOOLEAN NOT NULL DEFAULT FALSE, updated_at, PK (tenant_id, kind_key), FK to tenants)`, used only for the new kinds now; a missing row means off. The four existing columns stay as they are (V1 frozen). Later catalog work (packs, locked items, styles) can add columns to this table instead of one column per kind.

Either way: no data is moved, existing kinds are untouched, and the migration is registered in the master changelog (unlike 037).

## Commands run and results
- None beyond reading code (`grep` over `backend/`, the changelog folder and the persistence adapter). No build: no code changed.
- `bash scripts/ci/check-product-names.sh`: clean (1628 files).

## Tests: counts per module, and any failures with their output
- Not run (no code change). Frontend proof for the branch base is in report 0046; the backend is unchanged since `feature/lexavault-r1b-tenant-identity`.

## Files changed
- `docs/work/0043-report.md` (this file).

## Commits (hash and message), or "not committed"
- `docs(work): task 0043 STOPPED, migration needed for new kinds` (hash in the queue summary)

## Not done, and why
- Everything in "Build" and "Prove it": stopped before building, as the task requires when a migration is needed. The benchmark was not re-run (it would compare against kinds that are not built).
- Per queue rule 3, task 0044 was not started.

## Questions for the owner
1. Approve a migration for the new kinds' on/off flag? Option 1 (columns on `tenant_redaction_policies`) or option 2 (`tenant_redaction_kinds` table, recommended because R1's catalog comes next)?
2. How many separate switches: is "US tax IDs" one kind (EIN and ITIN together) or two, and "driver's licence and passport" one kind or two? (Proposed: five kinds: routing, account, tax ID, driver's licence, passport.)
3. The existing enum has `FINANCIAL_ACCOUNT` and `GOVERNMENT_ID`. Reuse them for "bank account" and "driver's licence / passport", or add new specific kinds (proposed: new specific kinds with stable keys, leaving the old two unused)?

## Suggested next step
Answer questions 1–3; then restart the queue at 0043 (with the migration approved), followed by 0044.

No other product's name or behaviour in code (no code changed).
