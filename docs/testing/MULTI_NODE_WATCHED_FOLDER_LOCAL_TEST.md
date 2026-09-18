# Local Multi-Node Watched-Folder Test

Run three LexaVault JVMs on one workstation. All three must use the same PostgreSQL database and same watched-folder root.

## IntelliJ run configurations

Use the same main class with different environment variables.

### APP-01

```text
SERVER_PORT=10080
SECUREDOC_WATCHED_FOLDER_NODE_ID=APP-01
SECUREDOC_WATCHED_FOLDER_ROOT_PATH=C:\LexaVaultTest\shared-intake
SECUREDOC_WATCHED_FOLDER_LEASE_DURATION_MS=10000
SECUREDOC_WATCHED_FOLDER_STALE_CLAIM_AFTER_MS=10000
SECUREDOC_WATCHED_FOLDER_SCAN_INTERVAL_MS=2000
```

### APP-02

```text
SERVER_PORT=10081
SECUREDOC_WATCHED_FOLDER_NODE_ID=APP-02
SECUREDOC_WATCHED_FOLDER_ROOT_PATH=C:\LexaVaultTest\shared-intake
SECUREDOC_WATCHED_FOLDER_LEASE_DURATION_MS=10000
SECUREDOC_WATCHED_FOLDER_STALE_CLAIM_AFTER_MS=10000
SECUREDOC_WATCHED_FOLDER_SCAN_INTERVAL_MS=2000
```

### APP-03

```text
SERVER_PORT=10082
SECUREDOC_WATCHED_FOLDER_NODE_ID=APP-03
SECUREDOC_WATCHED_FOLDER_ROOT_PATH=C:\LexaVaultTest\shared-intake
SECUREDOC_WATCHED_FOLDER_LEASE_DURATION_MS=10000
SECUREDOC_WATCHED_FOLDER_STALE_CLAIM_AFTER_MS=10000
SECUREDOC_WATCHED_FOLDER_SCAN_INTERVAL_MS=2000
```

All three instances use the existing LOCAL PostgreSQL datasource (`securedoc` on localhost:5432) unless explicitly overridden.

## Acceptance matrix

| Test | Expected result |
|---|---|
| Three JVMs start | All healthy; Liquibase applies/recognizes 55 changeSets |
| Lease election | Exactly one current lease owner; other nodes show STANDBY |
| Normal watched intake | One candidate claimed; exactly one package created |
| Concurrent startup | One lease row and one non-expired owner; no duplicate package |
| Leader stop / kill | Another node becomes ACTIVE after release/expiry and fencing token advances monotonically (for example 2 -> 3), never resets to 1 |
| Crash after source claim | Candidate remains `_processing`; newer fencing generation recovers after stale timeout; final package is complete with the expected document set, not merely one row |
| Restart old leader | It returns as healthy and must reacquire normally; it does not inherit old ownership |
| Duplicate A/B | A accepted, B duplicate, one package, both terminally archived under accepted disposition |
| API on :10080/:10081/:10082 | API intake remains available on all healthy nodes; watched-folder lease does not disable API traffic |
| PostgreSQL unavailable | Coordination becomes degraded and watched-folder scanning fails closed; no split-brain scan |
| Shared folder unavailable | Safe scan failure/degraded behavior; configured shared root is **not recreated locally**; no fabricated intake or data loss |
| Reserved folders | `_processing`, `_archive`, `_problem` never appear as incoming candidates |
| Privacy | Logs contain no raw document/OCR text, SSN/PAN/DOB/score, prompts, model responses, credentials, or full protected content |
| Regression | Existing frozen test suites remain green |

## Database checks

```sql
select lease_name, owner_id, fencing_token, acquired_at, heartbeat_at, lease_expires_at
from watched_folder_leases;
```

Expected: one row for the configured watched-folder lease name. During normal operation, the non-expired owner matches exactly one running JVM process incarnation.

After every ownership transfer to a different process, `fencing_token` must increase monotonically. This must hold for both an abrupt process loss and a graceful IntelliJ/Spring shutdown. The lease row must remain present after graceful release so generation history cannot reset.

### R6 fencing regression check

1. Record the current owner and fencing token, e.g. `APP-01/...`, token `2`.
2. Stop the current leader using IntelliJ Stop (graceful Spring shutdown is intentionally part of this regression).
3. Wait for APP-02 or APP-03 to become ACTIVE.
4. Query `watched_folder_leases`.
5. Expected: the same single lease row remains and the new different owner has token `3`, not `1`.
6. Restart the old APP-01 process. It must remain STANDBY while token `3` is current.

Repeat with an abrupt process termination when practical; the next transfer must continue `3 -> 4`, not reset.

### R7 crash-consistency / storage hardening checks

1. **Create-only crash:** establish a watched package in `CREATED` with the candidate fingerprint and zero documents, leave/recover the source, then scan with a newer generation. Expected: the same package is resumed, all expected documents are uploaded, it reaches `SUBMITTED`/normal processing, source is accepted/archived once, and no `DUPLICATE_INTAKE` is recorded.
2. **Partial-document crash:** persist only the first document of a multi-document candidate in a `CREATED` package. Expected: recovery recognizes the persisted SHA-256 match, uploads only missing documents, and final document count/signatures exactly match the source.
3. **Post-submit/pre-observation crash:** seed the same source/fingerprint as a complete `SUBMITTED` package without an accepted observation. Expected: recovery finalizes the original intake as accepted without re-uploading or re-submitting documents.
4. **Conflict safety:** seed a same-source `CREATED` package with a persisted document whose name/size/hash conflicts with the recovered candidate. Expected: fail closed to problem/technical handling; never archive it as duplicate.
5. **Fingerprint race:** force the create pre-check to lose a unique-fingerprint insert race. Expected: re-read the winner; same source resumes, different source remains a normal duplicate; no `SOURCE_IO_ERROR` solely because of the uniqueness race.
6. **Clock skew:** use an older-generation claim with a future timestamp. Expected: it is recoverable under the DB-authoritative time policy and cannot remain stranded indefinitely. New claim markers must carry PostgreSQL coordination time.
7. **Missing shared root:** remove/rename the configured shared root while the app is running. Expected: scan fails safely and the root path remains absent until the real share returns; LexaVault must not call recursive directory creation that recreates the mount point.

## Folder checks

Expected shared-root shape:

```text
C:\LexaVaultTest\shared-intake\
  _processing\
  _archive\
  _problem\        (created only when needed)
  <incoming candidates>
```

A triggered candidate should move incoming -> `_processing` before document ingestion and then reach `_archive` or `_problem` according to its durable intake outcome/configured disposition.
