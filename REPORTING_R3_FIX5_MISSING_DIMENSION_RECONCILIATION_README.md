# Reporting R3 FIX5 — Missing Dimension Reconciliation

## Scope
Focused reconciliation correction for tenant-approved reporting dimensions. No schema or migration change.

## Problem confirmed during FIX4 validation
Historical normalization correctly separated current Service Entity values (`000`–`005`) from `Legacy / Unmapped`, but the visible dimension rows did not reconcile to the selected-period cohort. Packages that had no retained Service Entity value were omitted entirely.

For the validated sample, the dimension rows represented 111 packages / 98 delivered while the selected-period cohort represented 214 packages / 120 delivered.

## Fix
Dimension aggregation now starts from the complete selected-period retained package-fact cohort for every enabled reporting dimension and LEFT JOINs any retained dimension value.

Each package is classified into exactly one bucket for that dimension:

- current approved value — e.g. `000`–`005`;
- `Legacy / Unmapped` — a retained value exists but is outside the tenant's current approved set;
- `Not provided / Unknown` — no retained value exists for the package/dimension.

`Legacy / Unmapped` and `Not provided / Unknown` intentionally remain separate because they represent different data-quality conditions.

The UI explains both buckets and uses a neutral treatment for `Not provided / Unknown`.

## Expected validation
For Service Entity with selected-period filters unchanged:

1. Current approved values remain normal rows.
2. `003` / `004` can remain visible with zero activity.
3. Historical invalid values appear only under `Legacy / Unmapped`.
4. Packages with no retained Service Entity appear under `Not provided / Unknown`.
5. Sum of all Service Entity package rows equals the selected-period received package count.
6. Sum of all Service Entity delivered rows equals selected-intake delivered for the same period/filter cohort.

## Database
No Liquibase changeset is required.

## Status
Reporting remains ACTIVE / NOT FROZEN pending this focused local reconciliation check, final export/reconciliation behavior, and final targeted independent verification.
