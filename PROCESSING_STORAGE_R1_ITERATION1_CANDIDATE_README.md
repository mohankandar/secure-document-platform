# LexaVault — Platform-Managed Processing Storage R1 / Iteration 1 Candidate

Status: **FIX1 IMPLEMENTED CANDIDATE / LOCAL VALIDATION REQUIRED**  
Baseline: **Final LexaVault V1 Regression / Release Readiness — COMPLETE / VERIFIED / FROZEN**


## FIX1 — JDBC timestamp binding correction (2026-09-17)

Initial LOCAL validation proved Liquibase 028 and the PostgreSQL SQL/schema were valid, but runtime bootstrap/node heartbeat failed with `BadSqlGrammarException`. Manual PostgreSQL execution succeeded. The failure was isolated to direct `java.time.Instant` positional JDBC parameters against PostgreSQL `TIMESTAMP WITH TIME ZONE`. FIX1 converts those values to `java.sql.Timestamp` before `JdbcTemplate` binding and preserves richer exception detail in bootstrap/node-monitor logs. No schema or delivery behavior changed.

## Scope

This iteration changes **internal processing storage only**. It does not change SDV delivery, master local/shared-folder delivery, or derived-document routing/delivery.

Implemented foundation:

- PostgreSQL-backed processing-storage provider definitions.
- `LOCAL_FILESYSTEM` and `SHARED_FILESYSTEM` provider types.
- Deployment-default storage assignment plus optional tenant-specific override.
- Stable provider UUID persisted in new artifact storage references.
- Package processing-cycle storage pinning so activation changes cannot split a processing cycle.
- First-cycle compatibility inference for pre-existing ORIGINAL artifacts.
- Retired providers remain readable and can continue an already-pinned cycle; they cannot receive new assignments.
- LOCAL profile seeds the historical `securedoc.storage.local.root-path` as the default managed provider.
- Non-LOCAL profiles start with processing storage unconfigured; admin/runtime can start, but intake writes and package processing fail/skip closed until a provider is activated.
- Shared roots are never created by LexaVault. The configured root must already exist and be readable/writable.
- OS/service-account filesystem authentication remains the security model; no SMB/NFS username/password is stored in LexaVault.
- Application-node heartbeat and per-provider read/write probe state in PostgreSQL.
- Provider activation requires healthy validation from every active application process.
- `LOCAL_FILESYSTEM` activation is rejected when more than one application node is active; multi-node deployments must use `SHARED_FILESYSTEM`.
- Package dispatcher skips processing on a node whose effective tenant processing storage is not healthy on that process, allowing another healthy node to recover/claim work.
- Platform Operations REST API foundation for listing, creating, testing, activating, tenant overriding, clearing overrides and retiring providers.

## New API foundation

All endpoints require `PLATFORM_OPERATIONS` capability:

- `GET /api/v1/platform/processing-storage`
- `POST /api/v1/platform/processing-storage/providers`
- `POST /api/v1/platform/processing-storage/providers/{providerId}/test`
- `POST /api/v1/platform/processing-storage/providers/{providerId}/activate-default`
- `POST /api/v1/platform/processing-storage/providers/{providerId}/activate-tenant/{tenantId}`
- `DELETE /api/v1/platform/processing-storage/tenant-overrides/{tenantId}`
- `POST /api/v1/platform/processing-storage/providers/{providerId}/retire`

Example create body:

```json
{
  "name": "Production Processing Storage",
  "providerType": "SHARED_FILESYSTEM",
  "rootPath": "\\\\NAS01\\LexaVault\\processing"
}
```

The share is expected to be reachable using the LexaVault service identity on every node.

## Database

Liquibase `028-processing-storage-management.xml` adds:

- `processing_storage_providers`
- `processing_storage_assignments`
- `processing_storage_cycle_pins`
- `processing_storage_nodes`
- `processing_storage_node_validations`

Current candidate source count: **68 changeSets**.

## Local validation performed in this build workspace

The execution environment does not contain Maven and has no external network access to download it, so a full Maven reactor/test run was not possible here.

Completed source-level validation:

- Entire `securedoc-domain` compiled with Java 21.
- Entire `securedoc-application` compiled with Java 21 using minimal SLF4J compile stubs.
- New `ProcessingStorageConfigurationServiceTest` compiled successfully against the current domain/application sources.
- New storage infrastructure classes compiled with Java 21 using minimal Spring compile stubs.
- New storage API/controller/dispatcher classes compiled with Java 21 using minimal Spring/API compile stubs.
- Liquibase XML parsed successfully; current count confirmed as 68.
- Managed-storage smoke test passed: provider ID persisted, same processing cycle remained pinned after default activation changed, next cycle switched to the newly active provider, and retrieval from the old provider remained successful.

## Required local verification

On the normal LexaVault workstation/CI environment:

1. Run the full backend Maven test reactor.
2. Run frontend build/auth regression (Iteration 1 contains no intended UI behavior change).
3. Start LOCAL and verify an automatic `Local Processing Storage` assignment is created and processing remains green.
4. Start a non-LOCAL profile and verify Platform Operations remains reachable while processing storage reports `NOT_CONFIGURED`.
5. Configure a shared root, wait for all active nodes to report `HEALTHY`, then activate it.
6. Verify upload -> extraction -> redaction -> export continues successfully and exported delivery behavior is unchanged.
7. In a multi-node run, remove share access from one node and verify that node becomes processing-ineligible while another healthy node processes/reclaims work.
8. Activate a second provider while a package is mid-cycle and verify all artifacts for that cycle remain on the pinned provider; verify a later reprocess cycle uses the new provider.

## Next iteration

Iteration 2 will add the **Platform Operations -> Processing Storage** UI, configuration-required UX, provider/tenant-override management, node validation display, and final regression/hardening.
