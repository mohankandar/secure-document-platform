# Operational Continuity, Technical Attention & Privacy Telemetry R1 — Candidate

Status: **FIX1 IMPLEMENTED / LOCAL STARTUP + RUNTIME REVALIDATION REQUIRED**

## Scope

## FIX1 runtime wiring correction

- Full local Maven build passed and Liquibase applied both operational-continuity changesets, but startup exposed ambiguous constructor injection in `SupportIncidentController`.
- FIX1 explicitly marks the 4-argument production constructor (including `SupportIncidentActionService`) as the Spring injection constructor.
- No functional/schema/frontend change; Liquibase remains 73. See `OPERATIONAL_CONTINUITY_R1_FIX1_README.md`.

This post-freeze candidate builds on Dashboard Retention Visibility FIX2 without reopening the frozen UI/UX modernization baseline. It addresses long-lived `PROCESSING` packages, Technical Attention lifecycle cleanup, exceptional Tenant IT actions, and privacy-preserving product telemetry.

## Package-processing recovery budget

- Existing lease/heartbeat/recovery scheduling remains authoritative. Recovery scans themselves do **not** count as attempts.
- One acquired processing-lease generation is one package-processing attempt.
- Default package-processing budget is **3 total attempts**, configurable with `SECUREDOC_PROCESSING_MAX_ATTEMPTS`.
- After the configured budget is exhausted while the package is still `PROCESSING`, LexaVault transitions it to `FAILED` / Technical Attention with structured reason `PROCESSING_RECOVERY_EXHAUSTED`.
- Legacy/pre-HA `PROCESSING` rows with no lease become eligible for recovery after the configurable orphan grace (`SECUREDOC_PROCESSING_ORPHAN_WITHOUT_LEASE_GRACE_MS`, default 60s) instead of remaining indefinitely stuck.
- Lease fencing remains the concurrency authority; scan frequency is not used as an attempt counter.

## Technical Attention lifecycle

- New/default tenant lifecycle policy uses **168 hours / 7 days** for never-exported Technical Attention retention. Existing tenant-configured values are intentionally preserved by the migration.
- At retention expiry, LexaVault purges processing artifacts, removes the failed package from the normal operational Dashboard, and auto-closes still-active processing incidents with `RETENTION_EXPIRED`.
- Durable package, support-incident, audit and reporting history remains. Artifact availability/purge state is surfaced to Support rather than producing an ambiguous missing-file experience.
- Historical failed packages whose artifact lifecycle was already purged can be reconciled without rerunning physical artifact deletion.

## Exceptional Tenant IT actions

Support Incident actions are intentionally exceptional rather than part of the normal business workflow:

- `REPROCESS`
- `PRESERVE_FOR_INVESTIGATION` for 7/14/30 days
- `RELEASE_HOLD`
- `RESOLVE_NO_REPROCESS`
- `SUPERSEDED`
- `DUPLICATE`

These actions preserve immutable package/failure history. Investigation hold extends Technical Attention retention; releasing/resolving restarts normal retention rather than deleting package history.

## Privacy-preserving product telemetry foundation

- Modes: `OFF`, `BASIC`, `ENHANCED`; on-prem default is **OFF**.
- Dedicated product-telemetry JSONL stream with an explicit allow-list.
- Uses pseudonymous installation/tenant identifiers; Enhanced may add a package pseudonym.
- Customer content is forbidden: no document/PDF/image content, OCR/extracted values, business identifiers, customer filenames/paths, prompts/model responses, or actor identity.
- Product telemetry is non-authoritative: telemetry failure must never block processing, retention, support actions or business completion.
- This candidate provides the safe local/store-and-forward foundation only; no outbound vendor collector endpoint is invented without a future fleet-service/authentication contract.

## Database / configuration

- Adds `031-operational-continuity.xml`:
  - `package_processing_leases.attempt_count` with `>= 1` constraint.
  - default for `tenant_lifecycle_policies.never_exported_hold_hours` becomes 168 for new/default policies; existing stored tenant values are not rewritten.
- Liquibase candidate baseline: **73 changeSets** through `031-operational-continuity.xml`.
- Dashboard retention manual maintenance endpoint documentation is corrected to the supported maximum: `POST /api/v1/lifecycle/purge-due?limit=500`.

## Assistant-side verification

- Java 21 domain + application compile: **PASS — 457 sources**.
- Changed processing-lease persistence compile: **PASS**.
- Changed recovery scheduler/API compile: **PASS**.
- Support/telemetry controller compile: **PASS**.
- Liquibase XML parse: **PASS**.
- Focused lifecycle/recovery/incident/telemetry tests: **18/18 PASS**.
- Full Maven + frontend production build and local runtime validation: **REQUIRED** before freeze.

## Local acceptance focus

1. Run normal backend/frontend build and Liquibase migration.
2. Confirm a normal package completes on attempt 1.
3. Force one recoverable ownership loss and confirm processing resumes on attempt 2 without Technical Attention.
4. Exhaust the configured 3-attempt package budget and confirm transition to Technical Attention with `PROCESSING_RECOVERY_EXHAUSTED`.
5. Validate one legacy/stale `PROCESSING` row with no lease is reclaimed rather than remaining indefinitely stuck.
6. Confirm a failed/never-exported package remains on the Dashboard during configured Technical Attention retention, then is hidden when retention purge completes while durable Support/Audit/Reporting evidence remains.
7. Validate investigation hold, release, resolve/no-reprocess, superseded/duplicate and reprocess actions.
8. With telemetry OFF, confirm no product-telemetry events are emitted. With BASIC/ENHANCED, inspect JSONL and verify only allowed pseudonymous/operational fields appear.
9. Reconfirm Dashboard Retention FIX2 and the manual lifecycle endpoint maximum of 500.

After full local acceptance, this candidate can be frozen as the post-freeze operational-continuity baseline.
