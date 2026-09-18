# Multi-Node Watched-Folder Coordination and Shared Intake Storage

**Status:** VERIFIED / FROZEN — R7 Kiro PASS + verification-evidence completion (2026-09-09)
**Baseline entering this unit:** Watched-Folder Intake + Fresh Golden-Path Workflow Matrix — VERIFIED / FROZEN
**Verified R7 tests:** 246 backend tests (Domain 27 / Application 81 / API 138), 0 failures/errors/skips; frontend build PASS; auth 8/8 PASS; Liquibase 55.
**Runtime verification:** three-node single-owner coordination, monotonic fencing, crash recovery, stale-claim DB-time recovery, shared-root fail-closed/resume, duplicate/idempotency, DB outage fail-closed and API availability all PASS; Document Intelligence remained DISABLED.

## Objective

Allow multiple LexaVault application nodes to point at the same PostgreSQL database and shared watched-intake location without double-consuming a watched-folder candidate or requiring a permanently designated primary server.

## Architecture

```text
                    Load Balancer
                         |
             -------------------------
             |           |           |
          APP-01       APP-02       APP-03
             \           |           /
              \------ PostgreSQL ----/
                         |
                 Shared Intake Root
                   /   |    |    \
          incoming  _processing _archive _problem
```

- All application nodes remain API-capable.
- PostgreSQL owns the renewable scanner lease.
- Exactly one non-expired scanner lease exists for the configured watched-folder lease key.
- Lease ownership is not hard-coded to a particular server.
- Each process uses a unique process incarnation ID even when two processes are accidentally configured with the same logical node name.
- A monotonic fencing token increments whenever ownership transfers to a different process.
- Before each new candidate claim and before create/upload/submit mutations, the application confirms that the exact owner + fencing generation still owns an unexpired lease using PostgreSQL DB time.
- Claim timestamps are sourced from PostgreSQL coordination time; JVM wall clocks are not authoritative for stale-claim age.
- A recovered source with the same intake fingerprint **and the same watched source reference** resumes its existing `CREATED` package instead of being falsely classified as a duplicate.
- Recovery reconciles persisted documents against the source using file name, MIME type, size and SHA-256; only missing documents are uploaded, conflicts fail closed, and submission occurs only after the exact expected document set is present.
- Triggered candidates are atomically renamed into `_processing` before intake reads/uploads their documents.
- Terminal source moves are fenced by owner + fencing token so an old process cannot archive/problem-move a candidate reclaimed by a newer generation.
- Stale `_processing` claims from an older fencing generation may be returned to incoming after the configured stale-claim timeout.
- PostgreSQL/intake-fingerprint uniqueness remains the authoritative duplicate/idempotency boundary.

## Database lease

Table: `watched_folder_leases`

Fields:
- `lease_name` — primary key
- `owner_id` — logical node + unique process incarnation
- `fencing_token` — monotonic ownership generation
- `acquired_at`
- `heartbeat_at`
- `lease_expires_at`

Acquisition/renewal uses PostgreSQL `clock_timestamp()` rather than application-server clocks. A node can renew its own lease. A different owner can acquire only after the persisted lease has expired. Ownership transfer increments the fencing token. Graceful release does **not** delete the lease row; it expires `lease_expires_at` in place while preserving `owner_id` and `fencing_token`, so the next different process observes the previous generation and atomically advances it.

## R5 local finding and R6 correction

Local three-JVM validation proved single-leader election, shared-folder intake, exactly-one package creation, and automatic failover. During leader shutdown/takeover, the next owner acquired fencing token `1` after the prior owner had token `2`. Source inspection showed the graceful `release()` path deleted the single lease row. A subsequent owner therefore executed the insert path and restarted the generation at `1`.

R6 corrects this by changing graceful release to a fenced `UPDATE` that sets `lease_expires_at = clock_timestamp()` without deleting the row. The durable generation history is retained; a different owner can acquire the expired row only through the conflict-update path, which increments `fencing_token` by one. No schema change is required, so Liquibase remains `55`.

Expected revalidation sequence: prior owner token `N` -> graceful/abrupt loss -> new different owner token `N+1`; tokens must never decrease or reset for the same logical lease key.

## R6 independent-verification finding and R7 correction

Independent R6 verification passed fencing, leader exclusivity, API availability, DB fail-closed behavior, authorization, privacy and frozen workflow regression, but failed the mandatory crash-consistency gate. A leader crash after package creation but before upload/submit left a `CREATED` package with the intake fingerprint; the next leader treated the recovered source as `DUPLICATE_INTAKE`, archived it, and stranded the incomplete package. Verification also found future-dated claim markers could delay recovery indefinitely and that a missing shared root could be silently recreated as a node-local directory.

R7 addresses those findings without changing the R6 lease schema or monotonic fencing algorithm:

- same-source fingerprint collisions are classified as **recovery**, not duplicate;
- `CREATED` packages are resumed by reconciling already persisted documents and uploading only missing content;
- submitted-or-later same-source packages are verified complete and finalized as the original accepted intake;
- a create-time unique-fingerprint race re-reads the winning package and routes it through the same recovery classifier;
- stale-generation mutations stop when the DB lease is no longer current;
- claim timestamps and stale-age evaluation use the PostgreSQL coordination clock; legacy impossible future timestamps do not strand an older generation;
- the configured watched root must already exist and be readable; LexaVault may create reserved child directories but never recreates the configured shared root.

No schema change is required; Liquibase remains `55`.

## R7 independent verification and freeze evidence

Kiro independently re-verified R7 on a live three-node cluster and returned **PASS**. A focused evidence-completion run then executed the runtime scenarios that had initially been covered only by source/unit-test evidence. Final accepted evidence includes:

- backend `mvn clean install`: 246 tests (Domain 27 / Application 81 / API 138), 0 failures/errors/skips;
- frontend production build PASS and authorization 8/8 PASS;
- Liquibase 55, detector 1.6.0, OCR guard 1.4.0, Document Intelligence DISABLED;
- exactly one active watched-folder owner with two API-capable standby nodes;
- fencing generations observed monotonically `1 -> 2 -> 3 -> 4 -> 5`;
- same-package recovery from `CREATED` with zero documents;
- same-package recovery from a partial two-document state with exactly one persisted document, finishing with each document present exactly once;
- post-submit / pre-observation recovery using the same package, with ACCEPTED observation and single archive disposition;
- older-generation claim recovery using PostgreSQL coordination time, including an impossible future claim timestamp;
- missing shared root fails closed and is not recreated locally; scanning resumes after the real root is restored;
- duplicate/idempotency, PostgreSQL outage fail-closed behavior, authorization, privacy-safe logging, architecture boundaries and source integrity all PASS.

A true graceful `@PreDestroy` console shutdown signal could not be delivered in the Windows verification environment. Graceful release semantics are nevertheless covered by `gracefulReleaseExpiresLeaseWithoutDeletingFencingHistory`, the `@PreDestroy releaseLease()` -> `leasePort.release()` source path, and the observed non-resetting monotonic fencing history. This is an environment limitation, not an open product finding.

This unit is frozen. Reopen it only for a reproducible correctness/security/privacy/tenant-isolation/data-integrity regression or an explicitly scoped later enhancement.

## Shared folder semantics

Reserved folders:

```text
_processing
_archive
_problem
```

The watcher ignores all reserved folders during normal discovery.

A complete candidate remains visible in incoming until its `manifest.json` exists. When a lease owner processes a triggered candidate, the filesystem adapter atomically claims it by moving it to `_processing/<sourceReference>` and writing a safe `.lexavault-claim.json` marker.

Accepted and duplicate intake follow the configured accepted disposition. Rejected intake follows the configured rejected disposition. `LEAVE` restores the claimed candidate to the incoming root after the intake result is durably recorded.

## Failure rules

- **Database/lease store unavailable:** fail closed; do not scan without coordination when coordination is enabled.
- **Current scanner process crashes before claim:** another node acquires the lease after expiry and can claim the source normally.
- **Current scanner crashes after claim:** the candidate remains in `_processing`; a newer fencing generation may recover it after the stale-claim timeout and resume the same incomplete package when the fingerprint/source reference match.
- **Old process continues after lease loss:** it cannot claim additional candidates and R7 will not begin further package create/upload/submit mutations after the exact DB owner/generation is no longer current. Terminal source moves remain fenced by the per-candidate marker.
- **Duplicate content appears under two source folders:** existing intake fingerprint/idempotency behavior still permits only one accepted package.
- **Shared intake unavailable:** scan fails safely; the configured root is not recreated locally, and the package is not fabricated or silently accepted.

## Configuration

LOCAL/demo defaults permit multi-node simulation but production remains externally configured.

Key settings:

```text
SECUREDOC_WATCHED_FOLDER_ROOT_PATH
SECUREDOC_WATCHED_FOLDER_COORDINATION_ENABLED
SECUREDOC_WATCHED_FOLDER_NODE_ID
SECUREDOC_WATCHED_FOLDER_LEASE_NAME
SECUREDOC_WATCHED_FOLDER_LEASE_DURATION_MS
SECUREDOC_WATCHED_FOLDER_STALE_CLAIM_AFTER_MS
SECUREDOC_WATCHED_FOLDER_SCAN_INTERVAL_MS
```

Production should use a customer-provisioned shared/durable path such as an approved SMB/NFS share or supported shared-storage provider. LexaVault does not provision the customer's file share.

## No-regression boundaries

This unit must not alter:
- R11 detection/redaction accuracy;
- Manual Review safety;
- Document Intelligence behavior (remains DISABLED for the stability path);
- authorization roles/capabilities;
- PDF artifact/redaction behavior;
- reprocess behavior;
- API intake availability on every healthy application node;
- privacy-safe logging rules.
