# LexaVault Processing Storage R1 — Iteration 2 Candidate

Status: **SUPERSEDED BY ITERATION 2 FIX1**

See `PROCESSING_STORAGE_R1_ITERATION2_FIX1_README.md` for the final validated multi-node evidence and **VERIFIED / FROZEN** acceptance status.

## Iteration 1 evidence carried forward

The Iteration 1 FIX1 backend/foundation has now passed the primary single-node LOCAL checks:

- LOCAL bootstrap created the deployment default and reported `ready=true`.
- `LOCAL-APP-01` provider validation reported `HEALTHY`.
- A normal upload completed through ORIGINAL -> EXTRACTED_TEXT -> REDACTED -> export.
- New artifacts persisted the managed provider UUID rather than the legacy `local` marker.
- Processing-cycle pinning persisted the original provider.
- A second provider was created, tested, activated, and received new artifacts.
- The pre-switch package remained readable from the historical provider after the deployment default changed.
- Older artifacts outside the 24-hour retention window may legitimately be absent and are not valid historical-read tests.

## Iteration 2 implementation

### Platform Operations UI

Adds `Platform Operations -> Processing Storage` as a tab available to `PLATFORM_OPERATIONS` users. The tab provides:

- platform readiness state and active-node count;
- deployment-default provider details;
- provider creation for `LOCAL_FILESYSTEM` and `SHARED_FILESYSTEM`;
- explicit filesystem path entry with OS/service-account authentication guidance;
- candidate provider Test Connection;
- per-provider/per-node validation results;
- activation as deployment default only after every active node is healthy;
- retirement of unassigned providers;
- tenant override assignment and clearing;
- provisioned tenant names/slugs in the assignment UX;
- clear separation between Processing Storage and final SDV/local/shared-folder delivery.

### API hardening

The processing-storage overview now includes:

- node validation for every non-retired provider, including unassigned `CANDIDATE` providers;
- active application-node count;
- safe tenant identity summaries for Platform Operations tenant-override selection.

This closes the Iteration 1 usability gap where Test Connection stored a candidate validation in PostgreSQL but the overview response hid it until the provider was assigned.

## Security / HA rules retained

- PostgreSQL remains the cluster-wide source of truth.
- A shared root must already exist; LexaVault never creates the configured root itself.
- Filesystem authentication remains OS/service-account based; no SMB/NFS password field was added.
- `LOCAL_FILESYSTEM` cannot be activated while multiple application nodes are active.
- No node silently falls back to local storage.
- Provider identity remains attached to artifacts and processing cycles.
- Existing providers remain readable for historical artifacts after an active-provider switch.
- Final master/derived delivery configuration is unchanged.

## Validation required

1. Run backend build/tests and frontend production build/auth tests locally.
2. In LOCAL as Platform Operations Admin, open `Platform Operations -> Processing Storage`.
3. Verify existing deployment default, readiness, node validation and tenant list.
4. Create a candidate folder/provider from the UI, Test Connection, activate it, upload a document, and verify the new artifact provider/root.
5. Verify the prior provider still renders an artifact created before the switch.
6. Run the multi-node shared-filesystem scenario: all active nodes must validate before activation; one-node share loss must make that node processing-ineligible without local fallback.

## Build note

The assistant environment has Java 21 and a global TypeScript compiler but no Maven and no cached npm dependency set. Framework-free application source compilation and TypeScript syntax transpilation were used as source-level checks; the full Maven reactor and frontend production build remain local validation gates.
