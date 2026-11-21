# Report 0027: Staged pipeline P1 (backend): export as its own job, with retries by code

Date: 2026-11-21
Branch: `feature/0027-export-job` (created by the design chat from `feature/lexavault-r1b-tenant-identity`)
Outcome: DONE (questions for the owner below)

## What was done

**Step 0.** On `feature/0027-export-job`. Baseline `mvn clean install`: BUILD SUCCESS, 939 tests, 0 failures (domain 74, application 281, scan bridge 136, infrastructure 28, API 420).

**0. Small items.**
- `.gitattributes`: `* text=auto eol=lf`; `.ps1`, `.cmd`, `.bat` CRLF; images, PDFs, fonts, jars, class files and zips binary. `git add --renormalize .` changed **31 files** (line endings only; the repo was not all LF: some Java, XML, Markdown, `.cmd` and script files were stored with CRLF). Committed on its own (8a5f9c1).
  - Trap avoided: this machine has `core.autocrlf=true`, which had treated the 9 test PDFs as text on checkout, so their working copies carried extra CR bytes (e.g. 4839 vs 4727 bytes). Marking PDFs binary made renormalize stage those damaged copies. I unstaged them and restored the correct bytes from the repository. Every tracked binary now matches its stored size. `start-local.cmd` and `stop-local.cmd` had doubled CRs (CR CR LF); they are now stored as clean LF.
- **LV-CFG-011 answers 409.** One line in `GlobalExceptionHandler.handleValidation`. The existing test `LvErrorCodeFollowupsTest.supportActionsInTheWrongStateAreCfg011` asserted 400 (task 0026); I **changed it to expect 409**, as the owner decided. No other test or frontend code depended on 400.

**1. Migration 043 (`043-pipeline-jobs.xml`, registered in the master changelog).**
- `pipeline_jobs`: the columns in the task, plus two ids/numbers: `item_id` (the derived delivery of a SPLIT_EXPORT job) and `export_cycle` (the case run, needed for the same-run identity). Check constraints on stage, status, priority, attempts and cycle. A unique index on (tenant, stage, case, cycle, destination, item), so queuing twice adds nothing. A partial pick-up index (`stage, next_attempt_at, created_at` where READY/WAITING_RETRY) and a running-jobs index (destination, claim_until where RUNNING). FKs to `document_packages` (cascade) and `tenants`. There is no FK on `destination_id`: a deleted connection makes the job fail with its code.
- `export_destination_states` (a small table, keyed by connection id): state WORKING/PAUSED/NEEDS_IT, `paused_at`, `consecutive_failures`, `last_probe_at`, `last_lv_code`, `updated_at`. No row means WORKING.
- No content anywhere: codes, ids and times. `last_detail` holds an internal code only (for example `DELIVERY_IO`, or `LV-DLV-004` under LV-DLV-015).
- Proven: applied by Liquibase with the master changelog on an empty PostgreSQL 16.4 (88 change sets), and again by both end-to-end checks.

**2. Export as its own job (`securedoc.processing.export-mode`, default `inline`).**
- `inline`: nothing changes. `TriggerPackageProcessingService` keeps its old constructor. The code after a successful master export (derived routing, COMPLETED, incident, audit) moved **unchanged** into `MasterExportCompletion`, which the inline path and the job both use. The adapters keep their in-adapter retries. Both job tables stay empty.
- `job`: where processing exported (after the final-approval check), it now queues the case's EXPORT job (`ExportJobQueue`) and returns. If queuing fails, the case fails as an export failure does today. Derived routing, run by the EXPORT job after the master export, queues **one SPLIT_EXPORT job per split document** where it used to call `autoSend`.
- **One EXPORT job per case, not per destination.** LexaVault has one master destination per tenant (the `DeliveryPort`: SDV, or the local folder). The split destinations are routing-rule connections and get SPLIT_EXPORT jobs.
- **Workers** (`ExportJobWorkers`, started only in job mode): a fixed pool, 8 in all, 4 per destination. Each worker claims a job, renews its claim every lease/3 while it runs, and saves the result. A maintenance task requeues jobs whose claim ran out and probes paused destinations.
- **Pick-up:** priority, then oldest; `FOR UPDATE OF j SKIP LOCKED`; the destination must be WORKING and below its worker limit. A claim bumps `claim_token`. `finish` saves only while the job is RUNNING under that token **and** its claim has not run out. A worker whose claim already ran out sends nothing. A job whose claim ran out goes back to READY and counts as an attempt; on its last attempt it goes to NEEDS_IT with LV-PRC-008 (design section 4).
- **Same request each time:** the job calls the unchanged `PackageDocumentExportService`, which reuses the stored `DeliverySubmission` for the case run, so every attempt sends the same `Idempotency-Key: <sourceSubmissionId>` (uploads `<sourceSubmissionId>:<sourceDocumentId>`), as `SDV_DELIVERY_V1.md` defines. Split jobs use `sourceDerivedDocumentId` on every attempt (`DERIVED_ROUTING_V1.md`). If the run was already exported (its result was lost), the job does not send again: it only completes the case.
- **In-adapter retries:** the SDV and derived adapters make one attempt per call in job mode (`withRetry` loops once, no sleeps). In inline they work as today; both are tested.

**3. Retries by code (`ExportJobService.classify` / `apply`).** As the task table. LV-DLV-001/002: WAITING_RETRY in 5 min, up to 3 tries. 3 failures in a row across a destination's jobs pause it. While paused its jobs are not taken and use no attempts, and a job never goes to NEEDS_IT because of the pause. A probe (the existing SDV or derived connection test) runs every 5 min; on success the destination is WORKING and its waiting jobs become due at once, in order. LV-DLV-009: DONE. LV-DLV-003/004: WAITING_FOR_YOU. LV-DLV-005: the destination and the job go to NEEDS_IT. LV-DLV-007/008: 3 tries, then NEEDS_IT (an answer counts as the destination answering, so the run of failures resets). Anything else: NEEDS_IT with its code (LV-SYS-001 if none). Every status change, pause, resume and "needs IT" is audited (8 new audit actions, codes and ids only).

**4. Resend and the IT queue (API only).**

| Method and path | Capability | Does |
|---|---|---|
| `GET /api/v1/export-jobs/waiting-for-you` | PACKAGE_APPROVE_DELIVERY | the tenant's WAITING_FOR_YOU jobs (case id, destination name, code, since) |
| `POST /api/v1/export-jobs/resend` `{jobIds:[…]}` (up to 200) | PACKAGE_APPROVE_DELIVERY | one attempt each: DONE, or NEEDS_IT with LV-DLV-015 and the failure's code as detail; a job not waiting is refused per item with LV-CFG-011 |
| `GET /api/v1/packages/{id}/export-jobs` | DASHBOARD_ACCESS | the case's export state and its jobs |
| `GET /api/v1/support/export-jobs/needs-it` | TECHNICAL_EXCEPTION_VIEW | the IT error queue |
| `GET /api/v1/support/export-destinations` | TECHNICAL_EXCEPTION_VIEW | each export destination: Working / Paused since / Needs IT, failures in a row, last probe |
| `POST /api/v1/support/export-jobs/retry` `{jobIds, resumeDestinations}` | TECHNICAL_RETRY | NEEDS_IT → READY, attempts reset; optionally the destination (PAUSED/NEEDS_IT) back to WORKING |
| `POST /api/v1/support/export-destinations/{id}/working` | TECHNICAL_RETRY | the destination back to WORKING; 409 LV-CFG-011 if it was not paused or waiting for IT |

**Notification:** each job reaching NEEDS_IT records the audit action `EXPORT_JOB_NEEDS_IT_NOTIFICATION` (for the notifications module later) and a processing incident with the job's code. That incident is what makes it count in Support's "needs attention" numbers, which are counted from the incident list.

### How "Ready for export" is shown
It is derived; no package status was added. The case keeps its status while its EXPORT job is open: PROCESSING, or PENDING_REVIEW / IN_REVIEW when its last review decision finished it. `GET /packages/{id}/export-jobs` returns `exportState`, taken from its EXPORT jobs: NEEDS_IT > WAITING_FOR_YOU > DESTINATION_PAUSED > WAITING_TO_RETRY > READY_FOR_EXPORT, then EXPORTED or NONE. To keep this safe, **both processing-recovery queries now skip a case with an open EXPORT job**. Otherwise a case waiting for a paused destination would be "recovered" and, after 3 recoveries, stopped as FAILED. In inline mode there are no jobs, so recovery is unchanged.

### What Vero's 409 means, and the mapping
From Vero's `docs/api/README.md` and `docs/api/error-codes.md`:
- A replay with the same key answers **200** with `idempotentReplay: true`.
- An exact duplicate answers **200** with `receiptAcknowledgement: DUPLICATE_ALREADY_STORED` / `duplicate: true`.
- **409 means same key, different content**: SDV-INT-011 (submission differs), SDV-SEN-003 (document retried with another level), SDV-SEN-004 (submission retried with another record level).

So: 409 stays LV-DLV-004, or LV-DLV-003 when the code is SDV-SEN-*. The two 200 answers are read from the upload reply and the job is DONE, audited with LV-DLV-009 (new `alreadyReceived` flag on the batch result). The derived destinations' contract defines only the idempotency key and no "already received" answer, so their 409 stays LV-DLV-004. An adapter that throws LV-DLV-009 also gives DONE.

### Settings and defaults

| Setting | Default |
|---|---|
| `securedoc.processing.export-mode` | `inline` (`job` turns it on; any other value stops start-up) |
| `securedoc.processing.export-jobs.retry-delay-ms` | 300000 (5 min) |
| `…max-attempts` | 3 |
| `…pause-after-failures` | 3 (in a row, across a destination's jobs) |
| `…probe-interval-ms` | 300000 (5 min) |
| `…claim-lease-ms` | 120000 (renewed every lease/3) |
| `…workers-per-destination` | 4 |
| `…workers-total` | 8 |
| `…poll-interval-ms` | 2000 |

Each one also has an environment variable (`SECUREDOC_PROCESSING_EXPORT_MODE`, `SECUREDOC_EXPORT_JOBS_*`).

### What in the design didn't fit the code
1. **"One EXPORT job per enabled destination"**: there is one master destination per tenant, so it is one EXPORT job per case run. Split documents get one SPLIT_EXPORT job each, to their rule's destination.
2. **The defaults meet each other.** With 3 tries and a pause after 3 in a row, a case failing on its own pauses its destination at its 3rd try. It then waits for the probe instead of going to NEEDS_IT. It reaches NEEDS_IT by tries only if other cases succeed in between, or for the local folder (no destination state). Both behaviours are tested. See question 2.
3. **Job status PAUSED** is in the table but not set. As the task says, a paused destination's jobs stay READY/WAITING_RETRY.
4. **A reviewed case** waits for export as PENDING_REVIEW / IN_REVIEW, not PROCESSING. Screens that list by package status will show it as "in review" until its job runs; its export state says "Ready for export". This is for 0028, or a status in P2.
5. **The per-destination limit of 4 is soft** under concurrency: two servers claiming at the same moment can briefly run 5. The total pool still bounds it.
6. **Split documents after the case completes:** as today, the case is COMPLETED when the master export is done (that starts the retention clock). Its SPLIT_EXPORT jobs then run on their own. A split destination paused longer than the retention period could find its split artifact purged; the job would then go to IT with its code.
7. **Only the end-of-processing path became a job.** It is the only caller of the package export (final approval and support retry go through it), so nothing else exports inline in job mode.

## Commands run and results

| Command | Result |
|---|---|
| `cd backend; mvn clean install` (before any change) | BUILD SUCCESS, 939 tests, 0 failures |
| `git add --renormalize .` | 31 files (PDFs restored, see above) |
| `cd backend; mvn clean install` (final code) | BUILD SUCCESS, 983 tests, 0 failures, 9 skipped (the PostgreSQL test, skipped by design without a database) |
| `JdbcPipelineJobRepositoryPostgresTest` against a throwaway `postgres:16.4-alpine` (`lexavault-0027-pg`, 127.0.0.1:55432, generated password not printed; removed afterwards) | 9 run, 0 failures |
| `cd frontend/securedoc-ui; npm run test:standalone` | exit 0: auth 10, vocabulary 158, ui-framework 17, oidc-signin 138, tablet 85, a11y 138 |
| `.\test-tools\no-redacted-values\Invoke-NoRedactedValueCheck.ps1` | PASS: 0 matches (database, 3 logs, 2 audit exports); throwaway `lexavault-0015-pg` removed |
| `.\test-tools\compliance-report\Invoke-ComplianceReportCheck.ps1` | PASS: 116 checks, 0 failed; throwaway `lexavault-0017-pg` removed |

None of `securedoc-postgres`, `sdv-postgres`, `sdv-vault`, `lexa-keycloak` or `lexa-keycloak-db` was touched.

## Tests: counts per module, and any failures with their output

| Module | Before | Now |
|---|---|---|
| Domain | 74 | 74 |
| Application | 281 | 281 |
| Scan Bridge | 136 | 136 |
| Infrastructure | 28 | 38 (9 of them PostgreSQL, skipped in the normal build) |
| API | 420 | 454 |
| **Backend total** | **939** | **983** |

New tests:
- `ExportJobServiceTest` (25), with the real `PackageDocumentExportService` behind a fake destination and an in-memory queue that follows the SQL rules:
  - a case exported by its job, and split documents too;
  - **the P1 proof**: 6 cases, the destination goes down after the first; it pauses after 3 in a row; while paused no job is taken, no attempt is used, and no case or job fails or goes to IT; the probe brings it back; all 6 go out in their original order;
  - each row of the section 3 table;
  - resend of several jobs: one attempt each, a failed one goes to NEEDS_IT with LV-DLV-015 and detail LV-DLV-004;
  - IT retry;
  - two workers can't take the same job; a stale claim can't save or send;
  - a server stopping mid-export: the job is back after its claim runs out and is sent once, with the same key;
  - LV-PRC-008 on the last attempt; a reviewed case; an already-recorded run.
- `TriggerProcessingExportModeTest` (3), `DerivedRoutingFailureRecordingTest` (+1, job mode queues a SPLIT_EXPORT job), `SdvDeliveryAdapterExportJobTest` (5: one attempt in job mode, retries kept in inline, Vero's replay and duplicate answers, 409 → LV-DLV-004), `JdbcPackageProcessingLeaseAdapterExportJobTest` (1).
- `JdbcPipelineJobRepositoryPostgresTest` (9): runs only when `LEXAVAULT_TEST_PG_URL`, `LEXAVAULT_TEST_PG_USER` and `LEXAVAULT_TEST_PG_PASSWORD` point at a throwaway database. 8 workers at once took 30 jobs, each exactly once. Stale tokens are refused; expiry counts an attempt; the per-destination limit holds; pause and resume keep the order; recovery skips a case with an open export job.

Changed test: `LvErrorCodeFollowupsTest.supportActionsInTheWrongStateAreCfg011` now expects 409 (owner's decision).

During development some of my new tests failed on test mistakes (Mockito stubbing, and tests sharing the throwaway database's global queue). I fixed the tests; no production test was weakened. Writing the tests also found one real bug: a reviewed case would have been stopped. I fixed it and added a test.

## Files changed
- Domain: `pipeline/` (PipelineJob, PipelineStage, PipelineJobStatus, PipelinePriority, DestinationState, ExportDestinationStatus); `AuditAction` (+8).
- Application: `pipeline/` (ExportMode, ExportJobSettings, ExportJobScheduler, SplitExportScheduler, ExportJobQueue, ExportJobService, MasterExportCompletion); ports PipelineJobRepository, ExportDestinationStateRepository, ExportDestinationProbePort; `TriggerPackageProcessingService` (job branch, tail moved to MasterExportCompletion); `DerivedRoutingService` (optional split scheduler); `DeliveryPort.BatchDeliveryReference` and `ExportPackageDocumentsResult` (`alreadyReceived`, old constructors kept); `PackageDocumentExportService` (passes it on).
- Infrastructure: migration `043-pipeline-jobs.xml` and the master changelog; `pipeline/JdbcPipelineJobRepository`, `JdbcExportDestinationStateRepository`; `JdbcPackageProcessingLeaseAdapter` (recovery exclusion); `SdvDeliveryAdapter`, `DerivedDestinationDeliveryAdapter` (single attempt in job mode; SDV reads Vero's "already received").
- API: `ApplicationServiceConfig` (beans), `processing/ExportJobWorkers`, `pipeline/ExportJobController`, `GlobalExceptionHandler` (409), `application.yml` (settings).
- `.gitattributes` and 31 renormalized files (separate commit).
- Tests as listed; `docs/work/0027-report.md`.

## Commits (hash and message), or "not committed"
- 8a5f9c1 `chore: .gitattributes for consistent line endings`
- The task 0027 commit follows on the same branch (hash given to the owner in the session). Not merged, not pushed.

## Not done, and why
- The screens (Waiting for you, bulk resend, destination state, IT queue) are task 0028, as the task says.
- The new audit actions have no screen wording yet (`auditRules.ts`): 0028.
- No live run with `export-mode=job` against the real Vero. The proof is the tests above, with a fake destination and real PostgreSQL.

## Questions for the owner
1. **Who may resend.** The task says Processor and Supervisor with "the same capability as today's manual send". That capability (PACKAGE_APPROVE_DELIVERY) is Supervisor only. I kept it (fail closed), so Processors can't resend today. Should Processors get resend (a new capability, or PACKAGE_APPROVE_DELIVERY for them)?
2. **The defaults meet each other** (point 2 above). Keep 3 and 3 (a lone failing case pauses its destination and waits for the probe), or pause after more failures in a row so a single bad case goes to IT?
3. **A reviewed case waiting for export** shows its review status (point 4). Is that acceptable until P2, or should P1 add a status?

## Suggested next step
The design chat reviews and merges `feature/0027-export-job`, then task 0028 (the screens). Before turning `job` on anywhere, one run with `export-mode=job` against Vero R7 with Vero stopped mid-run.

## Owner's answers (2026-11-21, recorded by the design chat)

1. **Who may resend:** the **Processor** (and Supervisor). A new capability for resend; approving delivery stays Supervisor only. Task 0028.
2. **Pausing a destination: decided by evidence, not by counting.** On can't-connect, timeout or 5xx, LexaVault runs the destination's health check at once: check fails → pause, check every 5 minutes, resume on its own in order; check passes → it's this case. 429 → slow down for that destination (Retry-After), no attempt used, no pause. Every destination failing its check at once → LexaVault's own network: one alert, nothing paused or blamed. Backup only: several different cases failing in a short time while the check passes → pause ("answering but failing"). Task 0028.
3. **A single failing case (destination healthy):** retries on its own about 5 min, 15 min, 1 h, 4 h and 12 h apart, is rebuilt once from the stored redacted copies before a later retry, then goes to IT. **IT alerts are grouped** per destination and problem with one "Retry all". Task 0028.
4. **Status name:** **"Exporting"**, with the detail: ready / destination paused / waiting for you / needs IT. Replaces "In review" for a reviewed case waiting to go out. Task 0029 (screens).
5. **Retention per destination:** the main export and each split destination have their own retention period, set by the Business Admin; each clock starts when that destination confirms receipt; nothing is deleted while its export job is open. Task 0028.
6. **Screens:** as on the design canvas "LexaVault · Export screens" (https://claude.ai/artifact/DQQMWiPS4N8JkUTDBLPcTG), approved: Waiting for you grouped by destination with one "Resend all" per group and no per-row buttons; Resend all confirmation; Exporting status in the Cases list and case panel with one "Resend this case"; Support with grouped IT alerts and destination states. Task 0029.
