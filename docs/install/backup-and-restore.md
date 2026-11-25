# Back-up and restore: install guide for IT (LexaVault)

The commands in sections 3, 4 and 6 are the ones of a rehearsed restore (PostgreSQL 16, Vault 1.21 with integrated storage, Windows). Anything that was not rehearsed says so. Rehearse it once on your own systems before you go live, and then every year.

More detail: `ERASE_LOG.md`, `KEY_SERVICE.md`, `OFFBOARDING.md`.

## 1. What to back up

| What | Where | How long to keep | If it is lost without a back-up |
|---|---|---|---|
| **Database** | PostgreSQL, the whole database and its roles | Back-up retention | Cases, settings and audit are gone |
| **Document storage** | Every folder under Admin › Platform operations › Processing storage (Platform Operations sees it; or `securedoc.storage.local.root-path`) | Back-up retention, from the **same time** as the database's back-up | Stored documents are gone |
| **Erase log** | The folder `PLATFORM_ERASELOG_LOCATION` | Its own back-up, on **separate storage**, at least retention + 30 days (65 by default) | LexaVault takes no work until it is back; a restore can't be completed safely |
| **Key service (Vault)** | A Vault snapshot, **and the unseal or recovery keys, kept apart from it** | **At most** the back-up retention | **Every stored document and sensitive case field value can never be read again** |
| **Configuration** | Environment settings, `platform-tenant.properties`, the sign-in file, the group-to-role mapping, certificates | While in use | Set up again |
| **Licence file** | The file you were sent | While in use | Ask for it again |
| **Passwords and logins** | Your own password store: database users, Vault logins, destination secrets | While in use | Make new ones |

Not part of it: destinations (they hold their own copies), watched folders, the scanner workstation's folder, the temporary folder and the AI models.

**The keys are yours.** LexaVault never sees your wrapping key and nobody else has a copy. If Vault and its back-up are both lost, the documents can't be recovered by anyone. Keep the snapshots and the unseal keys in two different places.

## 2. Retention

- **35 days** by default, **30 to 90 by contract**: `platform.erase-log.backup-retention-days`. LexaVault does not start with another value, and prints it in the compliance report.
- **Destroy every back-up** of the database, the document storage and Vault when it is older than that. LexaVault does not do this for you.
- A database older than the erase log keeps entries for can't be used (`ERASE_LOG_GAP`).

## 3. Taking a back-up

Stop LexaVault (every server) first; that is what was rehearsed. A back-up while it runs was **not** rehearsed. If you must, use a snapshot of the database and the storage taken at the same moment.

| Step | Command |
|---|---|
| 1. Database | `pg_dump -U securedoc -d securedoc --format=custom --file=lexavault.dump` |
| 2. Database roles (no passwords in it) | `pg_dumpall -U securedoc --roles-only --no-role-passwords --file=lexavault-roles.sql` |
| 3. Document storage | `robocopy <storage> <backup>\storage /MIR /COPY:DAT /DCOPY:DAT /R:2 /W:5` (below 8 is success) |
| 4. Key service | `vault operator raft snapshot save vault.snap` |
| 5. Configuration and licence | copy the files |
| 6. Start LexaVault | |

`securedoc` is the owner of the tables; add `-h` and `-p` when the database is on another machine. Step 4 needs `VAULT_ADDR` and a Vault login that may take snapshots (the rehearsal used the root login of a test Vault). `/MIR` makes the target the same as the source, deleting what is extra there: give every back-up **a folder of its own**.

**The erase log has its own schedule.** Copy its whole folder to separate storage often (each hour, for example): it must never be older than the database it belongs to. Never put it in the same back-up as the database, and never restore an older copy while the current one exists.

On Linux use your own copy tool for step 3, keeping paths and file names. Not rehearsed.

## 4. Restoring

**Restore the database and the document storage together, from back-ups of the same time.** What was made after the back-up is lost; ask the source systems to send it again.

1. **Stop every LexaVault server** and keep sources from sending.
2. **Key service**, only if it was lost. Set up a new Vault, then:

   ```
   vault operator raft snapshot restore -force vault.snap
   vault operator unseal
   ```

   Unseal with the keys of the **old** Vault, the one the snapshot is from. Its logins and policies come back with it.
   If Vault is still there, leave it alone.
3. **Database**, on an empty database of the same name:

   ```
   psql -U securedoc -d postgres -f lexavault-roles.sql
   psql -U securedoc -d postgres -c "\password lexavault_app"
   pg_restore -U securedoc -d securedoc --exit-on-error --single-transaction lexavault.dump
   ```

   "Role already exists" for the owner is expected. Roles come before the restore, or the app user's rights are lost.
   `lexavault_app` stands for your app's database user. (The rehearsal set its password with `alter role`.)
4. **Document storage**: `robocopy <backup>\storage <storage> /MIR /COPY:DAT /DCOPY:DAT /R:2 /W:5`, into an empty folder at the same path. Give the service account its rights.
5. **Erase log**: check that its folder is there and current. It is **never** restored from the database's back-up. If it was lost itself, restore its newest own back-up (not rehearsed).
6. **Check files against the database** (section 5).
7. **Replay the erase log, before any work.** With the app's normal settings in the environment:

   ```
   scripts/Invoke-LexaVault-EraseReplay.ps1        (Windows, rehearsed)
   scripts/Invoke-LexaVault-EraseReplay.sh         (Linux, not rehearsed)
   ```

   It erases again what was erased after the back-up and checks that none of it can be read. It must print `Erase replay done and verified` and exit 0. If not, fix the cause it names and run it again; running it again is safe.
8. **Wait 30 seconds, then start LexaVault** (the reason is in `ERASE_LOG.md`).
9. **Verify** (section 5), then let the sources send.

If somebody starts LexaVault before step 7, nothing is broken: new work is refused with **LV-SYS-013**, reading works. Stop it and go on with step 7.

## 5. Verifying

Before the replay, list what the database says is stored, and compare it with the folder:

```
psql -U securedoc -d securedoc -tAc "select storage_key || '|' || coalesce(ciphertext_sha256,'') from stored_files"
```

Each `storage_key` is a path below the storage folder, and the second value is the SHA-256 of the file as it is stored. (`Get-FileHash` gives the same in capitals.) No file may be missing or different. With several storage folders, do it for each (`storage_provider`); one folder was rehearsed.

After the start:

| Check | Expected |
|---|---|
| Support, Status | `Erase log` and `Key service`: Working |
| The newest case from before the back-up | Opens, with its history |
| Process one such case again (a Supervisor) | Its original opens and it completes: the restored keys fit the restored files |
| One new test case | Completes and is exported |
| A case erased after the back-up | Its files are not in the storage folder |

Keep a record: the back-up's date, what was restored, what the replay printed, who did it, the times.

## 6. When something else is wrong

| What | What LexaVault does | What to do |
|---|---|---|
| Only the document storage was restored | Notices nothing | Run the replay before starting. Not rehearsed |
| The erase log is older than the database | Refuses work, **LV-SYS-014** | Put the current log back, or its newest back-up |
| The key service is down, sealed, or has no keys | Refuses work: **LV-SYS-014**, **LV-SYS-010** or **LV-SYS-011** | Bring Vault back, or restore its snapshot (section 4, step 2) |
| The keys are lost and no snapshot is left | The same, for good | Stored documents and sensitive case field values can't be recovered. Start again with a new installation |
| A release was installed after the back-up | Not rehearsed | Restore with the release the back-up was made with, replay, and then upgrade |

## 7. Customer exit

`OFFBOARDING.md` has the whole runbook. For back-ups it means:

- Destroying the customer's two keys makes every stored document unreadable, in every back-up too. **It can't be undone**, and it is asked to be confirmed.
- The result file gives the two dates for the certificate: the day the live data was destroyed, and the day it is **unreadable everywhere, back-ups included** (that day plus the retention: in the rehearsal, 35 days).
- The second date is only true if Vault snapshots are kept no longer than that. A snapshot from before the destruction still holds the keys; **restoring it brings them back**. Destroy the snapshots on time.
- What is in the database and is not encrypted (audit, reports, case fields that are not sensitive) goes with the database and its back-ups.
