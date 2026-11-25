# Report 0060: encryption of stored files and the erase log

Date: 2026-11-25
Branch: `feature/0060-crypto-erase-log`, with the integration branch (0073, 0080, 0079) merged in
Outcome: **DONE** (9 questions). Built by a subagent from a written brief. I read the encryption, storage, erase and work-gate code and the migration, did the merges, and ran the full proof set on the merged code. **The start paths were run by the subagent, before the merges.**

## What was done
1. **Encryption.** Every stored file is encrypted with the customer's keys as it is written and decrypted in memory as it is read. Table `stored_files` (065) says how each file is protected; the file itself is never asked. A file without a record, or that differs from it, is refused (LV-SYS-012). Files of before stay readable. Key service down or sealed: LV-SYS-010; refused: LV-SYS-011; both retryable. Destinations still get a plain PDF.
2. **Erase log.** Every erasure is written to the log, outside the database, before anything is erased; when the log can't be written, nothing is erased. Six kinds, each with erase, verify and replay.
3. **After a restore** no work is taken (no change by a person, worker or schedule) until the replay has run and was verified.
4. **Backup retention:** 35 days, allowed 30 to 90, checked at start, stated in the compliance report.
5. Status page checks and two IT notices (key service; erase log). `customerKeys` is `required`.
6. **Documents:** `docs/install/KEY_SERVICE.md`, `ERASE_LOG.md`, `OFFBOARDING.md`; scripts for the replay and for destroying the customer's keys.
7. **LOCAL development start:** works without a key service (plain files, recorded as plain, a loud log line), only with mode LOCAL, profile `local` and a development build. No setting forces it.

**Migration:** 065 (table; a PLAIN record for each existing file), with rollback. The `erl_` tables are platform-core's, a fifth run of the schema step. No registered changeset edited. **Dependencies:** platform-core's crypto and erase-log libraries 1.0.0 (four jars) and their test kits; nothing else.

## Deletions found
| Deletion | Through the erase log |
|---|---|
| Redacted copies at retention; case purge; split copies; expired incomplete intake | yes |
| Reprocess, technical retry, recovery: a saved result withdrawn | yes |
| Sweep of files no record points to (0049) | yes |
| Page batch results after gathering | no: the content lives on in the case's text; the case entry covers it |
| Files of a step that was not saved; a failed write | no: never saved; the sweep covers leftovers |
| Jobs, alerts, findings rows, settings, notification rows, temporary files | no: ids, settings or temporary; no case files |

Plain content outside this: destination folders, watched folders, temporary work files (listed in `KEY_SERVICE.md`), scan bridge session files (question 4). Database values are not covered by these keys.

## Commands run and results
On the merged code (me):

| Command | Result |
|---|---|
| `mvn -B -Pdev-build clean install`, PostgreSQL tests on | SUCCESS: domain 89, application 320, scan bridge 137, infrastructure 153, API 899 (2 skipped: timing test, TLS mail), 0 failures |
| `npm run build`; `npm run test:standalone`, 1 run | built; 0 failures: 10, 224, 19, 204, 95, 174 |
| No-redacted-value check | PASS, 0 matches |
| Compliance report check | PASS, 127 checks |
| Product-name guard | clean (1870 files) |

The same set also passed after the first merge (before 0079).

By the subagent, on `c83188b` (before the last naming fix and the merges): start paths on throwaway PostgreSQL, Keycloak and Vault, **57 checks, all pass**: fresh LOCAL; refusals (retention 29 and 91, OIDC without a key service, crypto or erase log off); a 0059 database with plain files, then with Vault; fresh OIDC with Vault; Vault paused and denied, then back; a file swapped for a plain one; erase, restore, blocked, replay twice, work again; keys destroyed. The PostgreSQL drill is part of the backend build.

Merge conflicts: rollback counts in 5 migration tests; the two job workers (the work gate and 0079's bulk places, both kept, gate first).

**Left for M1:** two servers, export end-to-end, repeats, start paths on merged code.

## Failures met (fixed)
- The gate for scheduled tasks was first built so that it would also have stopped the heartbeats; found by its own test and rebuilt.
- Fresh LOCAL did not start (a licence bean exists only in tests); found by the start path.
- The demo step, a bean name clash, duplicate names in the generated codes file, wrong values in new tests.
- **Found in 0059 and fixed:** the "AI model down" notice compared a wrong check name and was never raised. Now tested.

## Existing tests changed (none removed)
- Counts and lists: 5 migration tests, `SchemaStepPostgresTest`, `LvErrorCodeRegistryTest`, `lvCodes.test.ts`, the a11y harness, the compliance check tool.
- `DemoStepTest`: keys required. `FreshDatabaseStart`, `ExistingDatabaseStart`, `LicenceBuild`, `NotificationsApp`: development roles file and their own erase log.
- The erasure port in place of storage: `PackageLifecycleDashboardRetention`, `ReprocessPackageService`, `PackageProcessingRecoveryService`, `ExternalIntake(Maintenance)Service`, `ExportRetention`, `UnrecordedFileSweepPostgresTest`, `LocalFileStorageAdapterTopologyTest`; `ComplianceReportServiceTest` (case added).

## Behaviour and default changes
- **Outside a LOCAL development build the app does not start without a key service.** `Start-LexaVault-OIDC.ps1` now needs a development Vault.
- **The app does not start before the schema step. Stop every server before that step:** an old server would store files without a record, and they would not open.
- An erase log that can't be used stops all work (platform-core's rule), and `/actuator/health` shows it.
- An erasure stays done even when the caller's own transaction fails.
- One more audit record per erasure (`FILES_ERASED`); one key call and one more read of the file per read.
- A LOCAL database dropped and made again under the same name waits until the replay script has run once.

## Not done or not proven
- Files are not re-encrypted after a key rotation; old key versions must stay.
- A restored database may send queued e-mails again (ids only).
- After a restore of the storage only, nothing tells the app; the runbook says to run the replay (tested).
- A sealed Vault (paused and denied were tried); a real production jar refusing LOCAL (unit test only); the Build scripts and the owner's start scripts as such.

## Questions for the owner (the recommended answer was taken)
1. LOCAL without a key service: plain files as above? Yes (recommended).
2. A development roles file (keys optional) for the local scripts only? Yes.
3. The local OIDC start needs a development Vault? Yes.
4. Encrypt scan bridge session files in a later task? Yes; until then an encrypted disk.
5. Withdrawn results count as erasures, clean-ups of batches and unsaved steps do not? Yes.
6. Old plain files stay until their retention ends? Yes; the offboarding runbook checks that none are left.
7. Re-encrypt after a key rotation? Not now.
8. An export stopped by the key service goes to the IT queue; automatic retry in a later task? Yes.
9. `FILES_ERASED` details off the compliance appendix until reviewed? Yes.

## PLATFORM-CORE (nothing patched)
- A new, empty keyed erase log is blocked at start (`ERASE_LOG_NOT_SEALED`), though the guide says it is sealed at its first entry. Reproduced by the subagent. LexaVault seals an empty log itself.
- Replay does not re-apply entries below the position, so after a storage-only restore verify alone would fail. LexaVault's verify finishes such an erasure.
- The guard can't see a restored storage, and the guide does not say to restore both together.
- `LicenceBuild` does not say whether it is a development build.

## For the owner
After the merge: stop LexaVault, run `Build-LexaVault-All.ps1`, start as usual. 065 and the erase-log tables run once.

## Commits
`fee9599`, `c83188b`, `d892c1e`, the merges and the report commit.

## Suggested next step
Task 0061.

No other product's name or behaviour in code (the known exception, `SdvDeliveryAdapter`, is untouched).
