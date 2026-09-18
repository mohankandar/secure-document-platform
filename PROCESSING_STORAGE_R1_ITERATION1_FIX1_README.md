# LexaVault Processing Storage R1 Iteration 1 — FIX1

Status: **IMPLEMENTED CANDIDATE / LOCAL VALIDATION REQUIRED**

## Observed failure

LOCAL started with the correct `local` profile and Liquibase successfully applied all four `028-processing-storage-management.xml` changeSets (68 total), but runtime showed:

- `PROCESSING_STORAGE_NODE_MONITOR_FAILED ... BadSqlGrammarException`
- `PROCESSING_STORAGE_LOCAL_BOOTSTRAP_FAILED ... BadSqlGrammarException`

Equivalent table inspection and heartbeat INSERT/DELETE commands succeeded directly in PostgreSQL.

## Correction

`JdbcProcessingStorageConfigurationRepository` now converts all `Instant` values used as positional PostgreSQL timestamp parameters to `java.sql.Timestamp` before calling `JdbcTemplate`. This applies to:

- provider created/updated timestamps;
- assignment activation time;
- processing-cycle pin creation time;
- application-node heartbeat;
- node-validation checked time;
- active-node/validation cutoff queries.

Bootstrap and node-monitor failure logs now include the underlying exception stack for future diagnostics.

## What did not change

- Liquibase schema remains unchanged at 68 changeSets.
- Provider/versioning/tenant-override/cycle-pinning semantics are unchanged.
- SDV/master delivery and derived-document delivery are unchanged.
- Frontend remains Iteration 2 scope.

## Validate

1. Build the backend.
2. Restart LOCAL.
3. Confirm `PROCESSING_STORAGE_LOCAL_BOOTSTRAP_READY providerType=LOCAL_FILESYSTEM`.
4. Confirm `GET /api/v1/platform/processing-storage` reports `ready=true`, a deployment default, one provider, and a healthy node validation.
5. Continue the remaining R1 validation checklist.
