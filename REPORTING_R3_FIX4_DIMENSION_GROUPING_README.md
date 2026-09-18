# Reporting R3 FIX4 — Historical Dimension Grouping SQL

## Scope
Focused runtime fix for the Reporting R3 FIX3 historical-dimension normalization query.

## Issue
PostgreSQL rejected the native dimension-breakdown query with SQLState `42803`:

`column "d.dimension_value" must appear in the GROUP BY clause or be used in an aggregate function`

The query repeated a parameterized `CASE` expression in both the `SELECT` and `GROUP BY`. With native JDBC parameter expansion, the two `:legacyValue` occurrences can become distinct bind placeholders, so PostgreSQL does not consider the expressions structurally identical for grouping.

## Fix
Normalize dimension values once in a CTE (`normalized_dimension_rows`) and then aggregate by the resulting `normalized_value` column.

This preserves all intended R3 FIX3 semantics:
- current allowed values remain normal reporting categories;
- obsolete/historical values aggregate into `Legacy / Unmapped`;
- configured allowed values with zero activity are still represented by application-side reconciliation;
- no historical metadata is deleted or rewritten;
- no database migration is required.

## Validation
Run the normal local build and restart LexaVault. Open Business Admin Reporting with Service Entity enabled and verify:
- no SQLGrammarException / SQLState 42803;
- values `000`–`005` render as current configured categories;
- obsolete historical values are grouped into one `Legacy / Unmapped` row when present.
