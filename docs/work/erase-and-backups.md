# Erase and backups (LexaVault deletion design)

Decided 2026-11-22 by the owner, confirmed by the LexaVault design chat. Same rule as platform-core `docs/design/case-fields.md` ("Erase and backups") and Vero's retention design.

## The rule
- **(b) Everyday erase and retention.** A purge, retention expiry or erase request removes the data from the live database and storage at once. Backups are kept only for the backup retention period (below), then destroyed. If a backup is ever restored, every erasure made after that backup is **re-applied from the erase log before the restored system takes any work**.
- **(c) Customer exit.** Destroying the customer's keys makes all their data unreadable everywhere, backups included. This is what the offboarding certificate rests on.
- **(a) Per-case keys:** not now; only if a customer contract requires it.

## Backup retention (decided by the design chat)
- Default **35 days** (covers a monthly backup cycle plus a margin). A setting per installation, allowed **30 to 90 days**, set by contract.
- Backups older than the period are destroyed by the backup job; LexaVault's install guide says so, and the compliance report states the period in force.

## The erase log
- Kept **outside** the database it protects (its own store, backed up separately), so a restore can't roll it back.
- Holds **no values**: entity, case id, what was erased (document, redacted copy, case fields, export copies), when, by which rule or person, and the audit record id.
- Kept for at least the backup retention period plus 30 days; after that every backup it could apply to is gone.
- Restore procedure: restore → replay the erase log → check (nothing in the log is readable) → only then start taking work. Replay is safe to run twice.

## What LexaVault records today and what changes
- Today the audit trail records purges (`REDACTED_ARTIFACT_PURGED`, case purge, export copy deletion). Those records live in the database, so they are **not** an erase log for restores.
- To build (scheduled with switch-over part 1, alongside platform-core's erase, so one erase log serves both): write each erasure to the erase log, the replay command, and a restore drill in the L8/release-candidate checks.
- Customer exit: key destruction step in the offboarding runbook, with the certificate listing the keys destroyed and the date. Lives with the key management work (platform-core crypto, P018).
