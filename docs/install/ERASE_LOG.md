# Erase log, backups and restore: install guide for IT (LexaVault)

Every erasure in LexaVault is first written to the **erase log**, outside the database (task 0060). If a backup is restored,
the erasures made after it are done again from the log before LexaVault takes any work. Platform-core's
`docs/install/erase-log.md` has the shared part.

## The log location

`PLATFORM_ERASELOG_LOCATION` (`platform.erase-log.location`): an absolute folder. It must **not** be inside the database's
or the document storage's disk, volume, snapshot or backup; it needs **its own backup**. Every LexaVault server of the
installation uses the same folder (a share). Only the service account may write there. One folder per database, for good:
moving it means moving its files (platform-core's guide).

With the key service (`KEY_SERVICE.md`) the log is keyed: nobody without the key can add an entry the app acts on. Outside a
local development start `platform.erase-log.keyed=false` and `platform.erase-log.enabled=false` are refused. A new, empty log
is sealed by the app itself. A log from before the key service was set up is sealed once with
`--erase-log-seal` (the same script as the replay: `-Seal`).

A local development start without the setting uses a folder per database under `<your home>/.lexavault/erase-log`.

## What is written

One entry per erasure, ids only: entity, case (or case/document/file), kind, when, the rule or the person's id, the audit
record's id. Never a name, a file name or a value.

| Kind | When |
|---|---|
| `case-files` | the case's retention has ended (also a rejected, stopped or expired case): all its files but split copies |
| `redacted-copy` | the redacted copy's retention has ended; a case is processed again |
| `export-copy` | a split copy's retention has ended; a case is processed again |
| `document` | the originals of an intake that was never completed |
| `working-file` | text of a case that is processed again |
| `unrecorded-file` | a stored file no record points to |

Each erasure also gives an audit record `FILES_ERASED`. If the log can't be written, **nothing is erased** (LV-SYS-014); it
is tried again at the next run.

## Backup retention

`platform.erase-log.backup-retention-days`: **35** by default, 30 to 90 by contract; outside that LexaVault does not start.
It is printed in the compliance report. The log keeps its entries 30 days longer (65 by default). **Destroy every backup of
the database, the document storage and the key service's snapshots when it is older than this.**

## Restore runbook

Restore the database **and** the document storage from backups of the same time.

1. Stop every LexaVault server.
2. Restore the database and the document storage.
3. Check that the erase log location is mounted. It is never restored from the database's backup; if it was lost itself,
   restore it from its own backup first.
4. Run the replay once, with the app's normal settings:

   ```
   scripts/Invoke-LexaVault-EraseReplay.ps1        (Windows)
   scripts/Invoke-LexaVault-EraseReplay.sh         (Linux)
   ```

   It erases again everything erased after the backup, checks that nothing in the log can be read, prints a report and exits
   0 (done) or 1 (not complete: the first line names the code; fix the cause and run it again; running it again is safe).
5. Start LexaVault. Support, Status shows `Erase log` working. Split copies, originals and cases whose retention ended
   meanwhile are closed by the next retention run.

Until then every server refuses work: changes through the app and the API answer 503 **LV-SYS-013**, no document is taken in,
no job runs, nothing is exported, no scheduled work runs; reading works. This is also noticed when the database is restored
while LexaVault runs (at the next erasure or within a minute). Messages already queued in the restored database may be sent
again.

- **Only the document storage was restored:** nothing tells LexaVault. Run the replay (step 4) before starting; it removes
  the files that came back. Their protection records are gone, so LexaVault could not open them anyway.
- **The replay says `ERASE_VERIFY_FAILED`:** something erased can still be read, or a storage folder is missing. Mount every
  storage folder an erased file was in, or make it as an empty folder if that storage is gone for good, and run it again.
- **A database older than the log keeps entries for** (`ERASE_LOG_GAP`) can't be used.

## Health and codes

Support, Status: `Erase log`, with the platform's code; a logged erasure that could not be done is shown with its entry
number and code, and blocks later erasures until it is. `/actuator/health` is DOWN or OUT_OF_SERVICE meanwhile.

## Database

The schema step makes `erl_position` (`SCHEMA_STEP.md`).
