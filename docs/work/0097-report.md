# Report 0097: small fixes from M1 (.staging leftovers, CI build script, audit-detail retired)

Date: 2026-11-26
Branch: `feature/0097-m1-fixes` (from f68476bf; brought up to 999a97a1, PR #44, before the PR)
Outcome: **DONE.** No migration. **No other product's name or behaviour in code.**

## What was done
1. **`.staging` leftovers** (0086 question 6). A new clean-up, `DeliveryLeftoverService`, runs with the lifecycle maintenance
   (`RetentionPurgeScheduler`, fixed delay, so also right after the start, then every 15 minutes). With deliveries to a folder
   (`securedoc.delivery.type=local`), `DeliveryLeftoverAdapter` removes a staging folder `<entity>/.staging/<case>-cycle-<n>-<delivery>` only when:
   its case is in this database and entity; no export job of the case is running and its processing lease has run out (database clock);
   it holds only document folders with files; nothing in it was written for the claim time + 10 minutes (12 minutes by default).
   It is first renamed to `….erasing`, then erased through the erase log (0060), new kind `delivery-leftover`, with a `FILES_ERASED`
   audit per erasure and one `DELIVERY_LEFTOVERS_CLEANED` audit per run and entity (counts only). Links and junctions are never followed;
   anything else, or a folder with an open file, is kept. The folder delivery now creates its staging document folder with
   `createDirectory` (not `createDirectories`), so a delivery whose folder was taken fails and is tried again, never published in part.
   The erase replay also erases a restored leftover. `docs/install/ERASE_LOG.md` explains it.
2. **CI runs `bash Build-LexaVault-All.sh --skip-tests`** in a new job `build-script`; `ci-passed` now needs it (`docs/install/ci.md`).
3. **`test-tools/audit-detail` retired** (2 files deleted, as the task says; nothing else referred to it).
4. **Tools at milestones only:** noted in `docs/work/test-strategy.md`.

## Commands run and results
| Command | Result |
|---|---|
| `mvn -B -q -Dmaven.repo.local=C:\dev\lexavault\m2-queue-B verify` in `backend/`, PostgreSQL tests on against a throwaway `postgres:16` container (`qb-0097-pg`, removed after) | **passed**: domain 93, application 384, scan bridge 144, infrastructure 206 (2 skipped), API 978 (5 skipped); 0 failures |
| of which `DeliveryLeftoverDrillPostgresTest` (the kill test: the real app in its own JVM, stopped with `Runtime.halt` while writing the second document of a folder delivery, started again) | 1 passed: the case is delivered, the leftovers are erased by the start's clean-up and are in the erase log; a restore that brings them back is undone by the replay |
| `DeliveryLeftoverPostgresTest` | 6 passed (only old leftovers of own idle cases; no link followed; log can't be written → nothing erased; half-done erasure finished by the replay; missing export location; a taken folder is tried again, never published in part) |
| `RetentionPurgeSchedulerCleanupTest` | 2 passed |
| `npm run test:vocabulary`; `tsc --noEmit` | 243 passed; 0 errors |
| product-name guard | clean |

Rule 11: built with `mvn verify` and a separate local Maven repository; nothing installed into the shared one.
Left for CI: the new `build-script` job (its first run is on this PR). Left for the milestone: two-server proof, export end-to-end, frontend runs.

## Questions for the owner
None. (0086 question 3 still waits for you, as the task says.)
