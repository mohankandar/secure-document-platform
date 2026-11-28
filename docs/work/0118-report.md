# Report 0118: data dictionary that never goes stale

Date: 2026-11-27
Branch: `feature/0118-data-dictionary`, from `origin/feature/lexavault-r1b-tenant-identity` at `ab07b75`
Outcome: **PARTIALLY DONE**: all work committed (PR #56), not merged. `ci-passed` is red because the integration branch itself is red since the 0111/0112 merges (see "Blocker").

## What changed
- **Meaning in the schema.** New changelog `090-schema-comments.xml` (included last in the master changelog): one comment for each of the 76 LexaVault tables and their 751 columns (`setTableRemarks` / `setColumnRemarks`), in one changeset with `runOnChange`, so a corrected meaning is edited in place and set again by the next schema step. The 39 platform-core tables (`idn_`, `lic_`, `notif_`, `erl_`, `fld_`, with their Flyway histories) and their 346 columns are in `backend/securedoc-api/src/main/resources/db/comments/platform-tables.sql`. Their table comments start with "(platform-core)". Liquibase's own two tables start with "(Liquibase)".
- Why the platform comments are not in the changelog: on a new database, the schema step runs Liquibase **before** the identity, licensing, notification and erase-log Flyway runs, so those tables don't exist yet when the changelog runs. `SchemaStep apply` now sets these comments after the platform runs, on every apply (one COMMENT per line, one transaction, new output line `comments  385 platform-core comment(s) set`).
- **Generated dictionary.** `DataDictionary` (`com.securedoc.install`, in the api jar) reads the live schema (pg_catalog: comments, types, required, defaults, primary keys, foreign keys with on-delete, indexes, "referenced by") and writes:
  - `docs/data/DATA-DICTIONARY.md`: an index per owner (LexaVault, platform-core, migration history), then every table in detail. It also lists the **retired tables**, found from the changelogs (created there, missing from the live schema), each with what replaced it. The notes are in `db/comments/retired-tables.properties`: `business_identifier_definitions`, `business_records`, `business_record_identifiers`, `enterprise_identity_connections`, `tenant_redaction_kinds`. It ends with **Useful queries**: find a case by contract or member number (the `JdbcCasePrimaryIdentifierLookup` join), time per stage (`pipeline_jobs`), whole-case time (`audit_records`), and findings by kind and placement (the 0105 columns).
  - `docs/data/RELATIONSHIPS.md`: a Mermaid diagram of the main tables (cases, documents, findings, case fields `fld_record`/`fld_value`/`fld_field_definition`, pipeline jobs, audit records, exports, deliveries, vault). Foreign keys are solid lines. The five links the code joins on without a foreign key are dotted lines, listed in a table under the diagram (e.g. `document_packages.business_record_id` = `fld_record.record_id` as text).
- **Script.** `scripts/Update-LexaVault-DataDictionary.ps1` (and `.sh`; `-Check` / `--check` writes nothing). It starts a throwaway `postgres:16-alpine` container with its own name, a free local port and a random password that is never printed. It then runs the schema step, writes `docs/data` and removes the container. It touches no other database.
- **CI keeps it true.** The check fails when a table or column has no comment, a dropped table has no note, a note names a live table, a diagram link names a missing column, or the committed files differ from the generated ones. The error message says "run scripts/Update-LexaVault-DataDictionary.ps1 (or .sh) and commit docs/data". It runs twice in the backend job: as `DataDictionaryPostgresTest` in `mvn verify` and as a separate step "Data dictionary is complete and up to date" (a fresh database on the job's PostgreSQL service).
- **CLAUDE.md:** new "Database (task 0118)" rule with the owner's sentence, plus where comments go and the script to run.
- Only comments change in the database: no table, column, type or data change. No new dependency.

## Tests changed (said plainly)
Eight migration tests roll back a fixed number of changesets from the end. Counting from the end now also counts 090, so each count goes up by one and its comment now names 090: `DocumentStoreNamesMigrationPostgresTest` (25 → 26, twice), `JdbcClaimLockAndQueueOrderPostgresTest` (26 → 27), `SearchableRedactedPagesSettingPostgresTest` (23 → 24, twice), `StageJobsMigrationPostgresTest` (22 → 23), `TenantRedactionKindsMigrationPostgresTest` (24 → 25), `VisionMigrationPostgresTest` (9 → 10). `DestinationProfilesMigrationPostgresTest` now rolls back "from 067 to the end" with the new helper `PostgresTestDatabase.changeSetsFrom`. A fixed number would be wrong there, because its second rollback is refused halfway. What each test checks is unchanged.

**For later tasks:** a task that adds a migration after 090 (0110's 088/089, for example) must give its new tables and columns comments, and regenerate `docs/data`. Otherwise CI fails, which is the point. If its include goes after 090, rollback counts from the end also move again.

## Proof
All on throwaway PostgreSQL 16 containers (`lv-0118-dict-pg` for the tests, and the script's own `lv-data-dictionary-*`, removed by the script). securedoc-postgres was not touched.
- `mvn -B -q -Pdev-build install -DskipTests` (backend): built.
- `bash scripts/Update-LexaVault-DataDictionary.sh`: "Wrote DATA-DICTIONARY.md and RELATIONSHIPS.md (115 tables). Data dictionary: every table and column has a comment." Container removed afterwards.
- `mvn -B -Pdev-build verify -pl securedoc-infrastructure,securedoc-api` with `LEXAVAULT_TEST_PG_*` set to the throwaway server: infrastructure 216 tests. The first run failed one test, `VisionMigrationPostgresTest` (a rollback count from the end I had missed; fixed as above, then that test passed alone). API 994 tests: 993 passed, 5 skipped, 1 failed, `BulkNightPostgresTest.aMigrationNight` ("Bulk stopped at 6 AM", expected 30, was 33). That test runs on a simulated clock with real jobs: 3 jobs already running finished after 6 AM while three sessions loaded the laptop. It touches no comment. Run alone twice afterwards, it passed both times (3/3). CI is the gate for it.
- `.\scripts\Update-LexaVault-DataDictionary.ps1 -Check`: "every table and column has a comment; the files are up to date", exit 0, container removed. Its first run stopped on the database probe's expected "connection refused" (Windows PowerShell treats a native command's stderr as an error under Stop); fixed in the script.
- `DataDictionaryPostgresTest` (3 tests):
  1. A fresh database through the schema step gives every comment, and the committed `docs/data` equals what is generated.
  2. Running the schema step again changes no comment.
  3. **The check fails** when a table (`lv_0118_uncommented`) and a column (`documents.lv_0118_new_column`) are added without a comment. It names both, says the committed file differs, and points to the script.
- Product-name guard: clean.
- No frontend change, so no frontend run. Left for the milestone: the full proof set.

## Meanings marked "(to confirm)" (for the owner)
| Table.column | Drafted meaning |
|---|---|
| `notif_approval_request.request_type` | Kind of request (for example a delivery approval), whose type rules say who decides and what happens on expiry. |
| `audit_records.detail` | Extra detail about the action in text form, holding codes, counts and ids rather than document values. |
| `outbox_events.status` | Publishing state, written as PENDING; no code in LexaVault moves it further yet. |
| `outbox_events.published_at` | When the event was published; no code writes it yet. |
| `outbox_events.retry_count` | Number of publishing attempts, written as 0 and not changed by any code yet. |
| `outbox_events.last_error` | Error from the last publishing attempt; no code writes it yet. |
| `enterprise_identity_connection_history.change_type` | What kind of change this row records on the old connection. |
| `processing_incidents.processing_fingerprint` | Privacy-safe JSON description of the processing setup in use when the failure happened. |

Tables no code uses any more, as their comments say: `enterprise_identity_connection_history` (history of the retired sign-in connections), `tenant_license_records` and `tenant_license_assignments` (the old in-house licensing, unused since 0057), and `tenant_oidc_group_grants` (read only by the one-off identity migration). Removing them would be a schema change, so it waits for the owner's word.

To correct a meaning: edit it in `090-schema-comments.xml` (or `platform-tables.sql`), run the script and commit.

No other product's name or behaviour in code.

## Blocker: the integration branch is red (not caused by 0118)
PR #56's backend job failed on `StagedPipelinePostgresTest.withExportModeInlineAStagedCaseStillEndsInOneExportJobAndNothingIsSentFromItsGate` ("Ready for export, as in job mode: expected PROCESSING but was FAILED", after its 60-second wait). Every other backend test passed, including `DataDictionaryPostgresTest`.

The same class fails on `feature/lexavault-r1b-tenant-identity` itself, in its last two CI runs after the 0111 and 0112 merges:
- run 46094653587: the same test, the same message;
- run 46084786575: `switchedBackToInlineMidRunEveryStagedCaseStillFinishesAndNothingRunsTwice`.

In the PR's log, a case is stopped with `PIPELINE_CASE_NOTHING_PLANNED` / LV-PRC-011 (the 0112 guard) while switching to inline mid-run. The likely reading is that the 0112 guard stops a case that is still valid when the pipeline mode changes mid-run. If so, it is a product defect, not only a test problem: a good case shown as Stopped. It touches the pipeline's cross-server behaviour and is outside 0118, so I did not change it, re-run it or work around it. No merge is possible until it is fixed: `ci-passed` is required.

Once the fix is merged: rebase or merge the integration branch into `feature/0118-data-dictionary`, run `scripts/Update-LexaVault-DataDictionary.ps1` (only needed if the fix adds a migration), wait for green, then `gh pr merge 56 --merge --match-head-commit <green SHA>` and add the TO-TEST entry.
