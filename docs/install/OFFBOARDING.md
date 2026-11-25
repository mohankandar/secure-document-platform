# Customer exit (offboarding) runbook

Destroying the customer's keys makes every stored document of the customer unreadable, in every backup too. **It can't be
undone.** It is one step of the exit; its result file is the data for the offboarding certificate.

1. **Agree** the date, and in writing that the data will not be needed again. Deliver or export what the customer still needs.
2. **Stop LexaVault** (every server): take no more work in, let no export run.
3. **Files stored before encryption** (releases before task 0060) are plain and are not covered by the keys. Check:
   `select count(*) from stored_files where protection = 'PLAIN'`. It must be 0 before this step counts as complete: let the
   retention run remove those cases first, and wait for the backup retention.
4. **Get a Vault login** with only the policy `platform-crypto-offboard`, for this run. Set it in the environment, as for
   setup: `PLATFORM_SETUP_VAULT_ADDR`, and one login (`PLATFORM_SETUP_VAULT_TOKEN`, `_TOKEN_FILE`, or `_ROLE_ID` with
   `_SECRET_ID`).
5. **Destroy the keys:**

   ```
   scripts/Invoke-LexaVault-DestroyCustomerKeys.ps1 -TenantId TEN-... -Operator "Your Name" -BackupRetentionDays 35
   scripts/Invoke-LexaVault-DestroyCustomerKeys.sh --tenant-id TEN-... --operator "Your Name" --backup-retention-days 35
   ```

   It runs platform-core's `crypto destroy-customer-keys` from the setup jar and asks for the tenant ID again
   (`-Confirm TEN-...` without a console). `-BackupRetentionDays` is the installation's
   (`platform.erase-log.backup-retention-days`); if Vault's snapshots are kept longer, give that number (up to 90).
   Exit codes: 0 destroyed, 1 failed (run it again with another result file; keep both), 2 refused, nothing destroyed.
6. **Keep the result file** (`customer-keys-destroyed-....json`): the customer, both keys with their versions, who and when, and
   the certificate's two dates: **live data destroyed on** (`liveDataDestroyedOn`) and **unreadable everywhere, including
   backups, from** (`unreadableEverywhereFrom`). It holds no secrets.
7. **Revoke the Vault login.**
8. **Remove the installation:** the document storage and the database (case fields, audit and reports in the database are not
   encrypted with these keys: they are gone when the database and its backups are), and after the second date every backup.
   Keep the erase log until then.
9. **Issue the certificate** from the result file, with the date the database was removed.

Afterwards LexaVault refuses every change with **LV-SYS-014**: without the keys it can't use its erase log (rehearsed, task 0093).
The stored files can't be opened either; the rehearsal did not reach that through the app, because the work is refused first. Do not run setup again for this customer: new keys open nothing.

Destinations' copies, the scanner workstation's folder and anything the customer exported are outside LexaVault.
