# Report 0093: back-up and restore guide, with one rehearsed restore

Date: 2026-11-25
Branch: `feature/0093-backup-restore` (from fd4f6811, PR #33; f48e2605, PR #34, merged in: documents only)
Outcome: **DONE**; what was not rehearsed is listed below.

## What was done
- `docs/install/backup-and-restore.md`: what to back up, retention (35 days, 30 to 90), taking a back-up, restoring (database and storage together, replay, verify), lost keys, customer exit.
- `test-tools/restore-rehearsal/restore-rehearsal.mjs`: the rehearsal. It can be run again after each release. It is also the small check of a restore: every file in `stored_files` is there with its checksum.
- No product code, no migration, no dependency. **No other product's name or behaviour in code.**

## The rehearsal
Throwaway PostgreSQL 16 and Vault 1.21.4 (integrated storage, its own unseal key), all removed; LOCAL sign-in, files encrypted, the app with a database user that owns no tables. The app was built from the 0082 tree; no backend, script or install file has changed since (`git diff`).

Back up two cases, then erase one and make a third, then lose the database, the storage and Vault; the erase log survives.

| Step | Seconds |
|---|---|
| Stop LexaVault | 2.3 |
| Back up the database (with roles) | 0.9 |
| Back up the document storage (6 files) | 0.1 |
| Back up the key service | 0.4 |
| Back up the configuration | under 0.1 |
| **Restore:** new Vault, snapshot, unseal with the old key | 17.8 |
| **Restore:** new PostgreSQL, roles, database | 8.8 |
| **Restore:** document storage | 0.1 |
| **Erase replay** (3 entries erased, 3 verified; mostly the app's start) | 29.7 |
| **Wait 30 s and start** | 56.9 |
| First restore command to started, with an early start, a second replay and the checks | 170.8 |
| Customer exit: destroy the keys | 2.4 |

**The data was tiny (476,060 bytes in the whole back-up), so the times say nothing about a real installation's.**

## Commands run and results
| Command | Result |
|---|---|
| `node test-tools/restore-rehearsal/restore-rehearsal.mjs`, once | ALL PASS, 24 checks |
| product-name guard | clean |
| Build, backend, frontend | not run locally: no product code changed (CI runs) |

Checks: restored files match the database; started too early, work is refused (LV-SYS-013) and reading works; the replay removes the erased case again, and a second run is safe; status Working; an old case is processed again from its original; a new case completes; a case made after the back-up is lost; an older erase log, lost keys and destroyed keys refuse work; a wrong confirmation destroys nothing; no password, key or token in a log, the roles file or another text file. (Binary dump and snapshot were not searched.)

## Found
- **With lost or destroyed keys every change is refused at the door with LV-SYS-014** (the keyed erase log can't be used), and not LV-SYS-011 as `OFFBOARDING.md` says ("answers LV-SYS-011 for every stored file"). So opening a stored file without the keys was not reached through the app. Not changed: wording question 1.
- The app runs with a database user that owns no tables, once the schema step has run. `MONITORING.md` says its user owns tables today. Not changed.
- A Vault snapshot taken before the destruction brings the keys back: known, in the guide. The rehearsal restored a snapshot after a loss of the keys, not after their destruction.

## Not rehearsed
A back-up while LexaVault runs; Linux scripts; a lost erase log restored from its own back-up; only the storage restored; several storage folders or servers; OIDC; restore across releases; Vault with other storage; large data; `\password` in the guide (the rehearsal used `alter role`).

## Questions for the owner
1. Correct `OFFBOARDING.md` to LV-SYS-014 for new work? (recommended: yes, with 0051 or the next document task)
2. A back-up while running: rehearse it, or require a stop (some seconds to minutes each night)? (recommended: rehearse with a storage snapshot before the first customer)
3. Ship the file check as a small script for IT? (recommended: yes; today it is a query and a comparison)
4. Run this rehearsal in the release checks (some minutes, needs Docker)? (recommended: yes)
