# Task 0043: owner's answers (2026-11-23, recorded by the design chat; owner said "as recommended")

Answers to the three questions in `docs/work/0043-report.md` (on `feature/0043-r1-patterns`). Task 0043 continues with them.

1. **Migration approved: option 2**, a new table **`tenant_redaction_kinds`** (one row per entity and redaction kind, with its on/off switch), so new kinds never need another schema change and the R1 catalog can build on it. One Liquibase migration (047), with a rollback and a PostgreSQL test; the existing four kinds keep their current columns and behaviour unchanged in this task (moving them into the new table is the catalog task's decision, not this one's).
2. **Six switches, all off by default:** bank routing number, bank account number, **ITIN** (personal, as sensitive as an SSN), **EIN** (business; many customers won't want it redacted), driver's licence number, passport number.
3. **New specific kinds with stable keys.** The two unused generic kinds `FINANCIAL_ACCOUNT` and `GOVERNMENT_ID` stay as they are, unused; the catalog task decides whether to remove them.
