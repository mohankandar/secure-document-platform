# Report 0028: Export follow-ups (backend): retries by evidence, Processor resend, retention per destination

Date: 2026-11-21
Branch: `feature/0028-export-followups`
Outcome: DONE (two small points for the owner below)

## What was done

Baseline on this branch before any change: `mvn clean install` BUILD SUCCESS, 983 tests (domain 74, application 281, scan bridge 136, infrastructure 38 with 9 skipped, API 454).

Note on the inputs: the owner's answers are at the end of `0027-report.md`, as the task requires. `staged-pipeline-design.md` section 5 still describes "3 failures in a row", and section 10 has no retention per destination (the copy on disk has uncommitted edits from the design chat, not touching those sections). I followed the task text and the owner's answers. I did not commit the design chat's edit to that file.

**1. Resend by the Processor.**
- New capability `EXPORT_RESEND`, given to the Processor's set. The Supervisor has every Processor capability, so it gets it too. No other role holds `PACKAGE_APPROVE_DELIVERY`. Approving delivery is unchanged (Supervisor only).
- `GET /export-jobs/waiting-for-you`, `POST /export-jobs/resend` and the new `POST /export-jobs/resend-all {destinationId}` need `EXPORT_RESEND`.
- Resend all: every WAITING_FOR_YOU job of that destination goes back to READY in one statement, marked as a resend. The workers take them oldest first, so they keep their original order. Each is one attempt: success is DONE, a failure is NEEDS_IT with LV-DLV-015 (the failure's own code as the detail). The single resend stays (it is synchronous, as before).
- The list now returns `{total, destinations:[{destinationId, destinationName, total, countsByCode, jobs}]}`, oldest first.
- A resend that meets a 429, or a destination whose check fails, is not the case's failure. The job waits for it and is still one resend.

**2. Pausing by evidence (replaces "3 failures in a row").**
- **Can't connect, timeout or 5xx (LV-DLV-001/002):** the destination's health check runs at once. For SDV and the split destinations this is the existing connection test. For the local folder it writes and deletes a test file under `<tenant>/.staging`. The local folder now has a destination state: its id is derived from the tenant id, so every server agrees on it (`ExportDestinations.localFolder`).
  - Check fails: the destination is PAUSED and the job waits. **No attempt is used** (the case is not at fault). The existing 5-minute probe brings the destination back, and its waiting jobs go out in their original order.
  - Check passes: the case's own schedule (section 3). The destination stays WORKING.
- **429:** not a failure. No attempt is used and there is no pause. The job waits for Retry-After (seconds or an HTTP date, capped at 1 h; 60 s if absent). No job of that destination is taken until then. The destination gets one worker less (never below 1), and one back after each 10 minutes without a 429. The SDV and split adapters mark the 429 on the failure; its code and its inline retries are unchanged.
- **Every destination failing its check within 2 minutes (at least 2 destinations):** one alert, "LexaVault can't reach its destinations". It is the audit action `EXPORT_DESTINATIONS_UNREACHABLE_ALERT` plus one Support incident with LV-DLV-001 and detail `ALL_DESTINATIONS_UNREACHABLE`. Nothing is paused and no attempts are used. All destinations are checked again every 5 minutes. When any check passes, the alert clears (audited) and the waiting jobs are due again, in order. The alert is shown at `GET /support/export-destinations/alert`.
- **Backup rule ("answering but failing"):** failures from 3 different cases within 10 minutes pause a destination whose check passes. Both numbers are settings.
- **Old settings:** `pause-after-failures` (and `retry-delay-ms`) are still read and validated but no longer used; the counting logic is gone from the service. `pause-min-distinct-cases` never existed in 0027, so there was nothing to remove.

**3. A single failing case: a long schedule, one rebuild, then IT.**
- LV-DLV-001/002 with a passing check, and LV-DLV-007/008: retries after 5 min, 15 min, 1 h, 4 h and 12 h (setting `retry-schedule`). That is 6 tries, then NEEDS_IT.
- Before the 3rd retry the export is **rebuilt once** from the stored redacted copies (setting `rebuild-before-retry: 3`). How the destination still recognises a repeat:
  - The rebuild stays in the same case run, on the same `DeliverySubmission` row: the same id, the same `sourceSubmissionId` (the `Idempotency-Key`) and each document's `sourceDocumentId` (upload key `<sourceSubmissionId>:<sourceDocumentId>`), as `SDV_DELIVERY_V1.md` requires.
  - What the destination had answered is dropped: its submission reference and every acknowledgement. The next try therefore creates the submission again (idempotent for the same key) and uploads every document again (each a replay under its same key). Vero answers those with 200 and `idempotentReplay`, which 0027 already counts as LV-DLV-009.
  - Each stored copy is read back and checked against its stored SHA-256 first. A missing or changed copy is never sent: the job goes to IT with its code.
  - For a split document the identity (`sourceDerivedDocumentId`) never changes. The rebuild checks its stored copy the same way.
- **Grouped IT alerts:** NEEDS_IT jobs of one destination with one code form one group. The first job opens it with one incident and one `EXPORT_JOB_NEEDS_IT_NOTIFICATION`; later jobs join it without either. `GET /support/export-jobs/needs-it` now returns `{total, groups:[{destinationId, destinationName, lvCode, count, firstFailedAt, caseIds, jobs}]}`. `POST /support/export-jobs/retry-group {destinationId, code}` (TECHNICAL_RETRY) retries the whole group and sets a paused or IT-waiting destination back to WORKING; it answers 409 LV-CFG-011 when there is no such group. The single-job retry stays.
- A claim that runs out (a server stopping mid-job) still counts as an attempt. LV-PRC-008 now counts claim expiries on their own (still 3), so a case far into its schedule does not go to IT after one server crash.

**4. Retention per destination.**
- `GET` and `PUT /api/v1/business-admin/export-retention` (TENANT_POLICY_MANAGE). Body: `{masterHours, splitDestinations:[{destinationId, hours}]}`. Null means the default: the master's default is the current redacted-copy retention, and a split destination's default is the master's period. The limits are 1 to 72 hours, the same as the redacted-copy setting; anything else is LV-CFG-001 and nothing changes. Each change is audited (`EXPORT_RETENTION_PERIOD_CHANGED`). The screen is 0029.
- The master copy's clock starts when the master destination confirms receipt (that is when 0027 records the export). A split copy's clock starts at its own delivery receipt (`derived_deliveries.delivered_at`). The two clocks are independent: the final purge keeps a split copy that is still inside its period, and a separate sweep deletes it when its period ends (`SPLIT_COPY_PURGED`).
- Nothing is deleted while that copy's export job is open. The original follows the case's rule but waits while any export job of the case is open.
- A split copy that was never received, with no open job, follows the master copy, as before.
- "Fully exported" now means every export job of the case (master and split) is done. Reports and audit counts are unchanged; none of their queries were touched.

**5. Export state for the screens.**
- `GET /packages/{id}/export-jobs` and each Cases list item (`GET /operations/dashboard`, new field `exportState`) carry one of NONE, READY, DESTINATION_PAUSED, WAITING_FOR_YOU, NEEDS_IT, EXPORTED. A case retrying on its own schedule is READY. The 0027 values READY_FOR_EXPORT and WAITING_TO_RETRY were renamed or merged to match the task's list; no screen used them.
- How the list stays light: two indexed queries per page, whatever its size. One reads the page's export jobs (`package_id IN (...)`, existing index), the other reads the tenant's destination states (a few rows). In inline mode both are empty, so every row is NONE.
- I added `exportState` to the dashboard list only (that is what the Cases list calls), not to `GET /packages`.

**`inline` mode.** In inline mode the export runs exactly as before. The adapters' in-adapter retries are unchanged (tested with a 429 as well). The only change you can see in inline mode is retention: a delivered split copy now follows its own clock from its receipt. With the defaults its period equals the master copy's, so it goes at most one purge cycle (15 min) after the master copy.

### Migration 044 (`044-export-followups.xml`, registered in the master changelog)

| Change set | What |
|---|---|
| 044-01 | `pipeline_jobs`: `resend`, `rebuilt`, `claim_expiries` (check >= 0), `first_failed_at`, `last_failed_at`; index on (tenant, destination, last_failed_at) for the backup rule |
| 044-02 | `export_destination_states`: `rate_limited_until`, `worker_reduction` (check >= 0), `last_rate_limited_at`, `last_check_failed_at`; **drops the foreign key to `integration_connections`** so the local folder can have a state row |
| 044-03 | `export_reachability_alerts`: one row per tenant (RAISED or CLEARED, times) |
| 044-04 | `export_retention_periods`: tenant, destination (null for the master), hours (check 1 to 72), unique per tenant and destination; foreign keys to tenants and connections (cascade) |

There is no content in any column: only ids, codes, numbers and times. Applied by Liquibase from an empty PostgreSQL 16.4 with the master changelog: 92 change sets (88 before, plus 4).

### Settings and defaults

| Setting (`securedoc.processing.export-jobs.*`) | Default | Note |
|---|---|---|
| `retry-schedule` | `5m,15m,1h,4h,12h` | new; units s, m, h (a bare number is ms); a bad value stops start-up |
| `rebuild-before-retry` | 3 | new |
| `failing-cases-to-pause` | 3 | new (at least 2) |
| `failing-cases-window-ms` | 600000 (10 min) | new |
| `rate-limit-default-wait-ms` | 60000 | new; Retry-After capped at 1 h |
| `rate-limit-recovery-ms` | 600000 (10 min) | new |
| `all-unreachable-window-ms` | 120000 (2 min) | new |
| `probe-interval-ms` | 300000 (5 min) | unchanged; also the alert's check interval |
| `max-attempts` | 3 | now the number of claim expiries before LV-PRC-008 |
| `pause-after-failures`, `retry-delay-ms` | 3, 300000 | still read, no longer used |

Each has an environment variable (`SECUREDOC_EXPORT_JOBS_*`), as in 0027.

## Commands run and results

| Command | Result |
|---|---|
| `cd backend; mvn clean install` (before any change) | BUILD SUCCESS, 983 tests, 0 failures |
| `cd backend; mvn clean install` (final code) | BUILD SUCCESS, 1029 tests, 0 failures, 19 skipped (the PostgreSQL tests, skipped by design without a database) |
| Both PostgreSQL test classes against a throwaway `postgres:16.4-alpine` (`lexavault-0028-pg`, 127.0.0.1:55432, generated password not printed, removed afterwards) | 19 run, 0 failures, three runs in a row |
| `cd frontend/securedoc-ui; npm run test:standalone` | exit 0: auth 10, vocabulary 158, ui-framework 17, oidc-signin 138, tablet 85, a11y 138 |
| `.\test-tools\no-redacted-values\Invoke-NoRedactedValueCheck.ps1` | PASS: 0 matches (database, 3 logs, 2 audit exports); throwaway `lexavault-0015-pg` removed |
| `.\test-tools\compliance-report\Invoke-ComplianceReportCheck.ps1` | PASS: 116 checks, 0 failed; throwaway `lexavault-0017-pg` removed |

None of `securedoc-postgres`, `sdv-postgres`, `sdv-vault`, `lexa-keycloak` or `lexa-keycloak-db` was touched.

## Tests: counts per module, and any failures with their output

| Module | Before | Now |
|---|---|---|
| Domain | 74 | 76 |
| Application | 281 | 281 |
| Scan Bridge | 136 | 136 |
| Infrastructure | 38 (9 PostgreSQL, skipped) | 48 (19 PostgreSQL, skipped) |
| API | 454 | 488 |
| **Backend total** | **983** | **1029** |

New tests:
- `ExportFollowupsTest` (18), with the real export service behind a fake destination, covering each rule:
  - check fails: paused at once with no attempt used; checked every 5 min; resumes on its own and sends in order. The local folder is checked and paused the same way;
  - check passes: the exact schedule (5 min, 15 min, 1 h, 4 h, 12 h), rebuilt once before the 3rd retry, then NEEDS_IT. The same Idempotency-Key is sent on all 6 tries; after the rebuild the export starts afresh at the destination. A changed stored copy is never sent;
  - 3 different cases within 10 minutes pause the destination; failures spread over more than 10 minutes do not;
  - 429: waits for Retry-After, uses no attempt, 3 workers instead of 4, one back after 10 quiet minutes, never below 1, 60 s without Retry-After;
  - all destinations down: one alert, no pause, no attempts, checked every 5 min, cleared when one passes, then the cases go out in order. One destination down while another answers is a pause; a single destination alone is a pause;
  - grouped IT alert: one incident, one notification, retry-group; a different code is another group;
  - resend all keeps order, one attempt each, fails to DLV-015 and leaves other destinations alone; a resend that meets a paused destination waits as a resend;
  - the Cases list states in one call; EXPORTED only when every export is done.
- `ExportRetentionTest` (8): a split copy is kept for its own period from receipt, independently of the master copy and the original. A shorter master period with its own clock. A split copy never received follows the master copy. A split copy is not deleted while its job is open, the original is kept while any export job is open, the master copy is kept while the master job is open, and the defaults are as before. The Business Admin settings: defaults, limits, unknown destination refused.
- `ExportResendAuthorizationTest` (2): the Processor can resend and cannot approve delivery; each endpoint's capability.
- `ExportDestinationAdapterFollowupsTest` (6): a 429 is marked with Retry-After and keeps LV-DLV-002; a 5xx is not marked; inline still retries a 429 three times; Retry-After parsing; the SDV check is the health GET; the local folder check writes and deletes its file and fails (LV-DLV-001) when the folder can't be written.
- `ExportJobSettingsTest` (2), `DeliverySubmissionRebuildTest` (domain, 2).
- `JdbcExportFollowupsPostgresTest` (10, PostgreSQL only): the new columns, failure times, distinct failing cases, the 429 hold and worker reduction down to 1 and back, resend-all order, the IT group count, claim expiries, network waiters, the local folder's state row without a connection, the alert, the retention periods and the due-split-copy query (its period, an open job, a deleted copy).

**Tests I changed, because the task replaced the rule they tested:**
- `ExportJobServiceTest`:
  - removed `unreachableOrBusyWaitsFiveMinutesThenNeedsItAfterTheThirdTry`, `withTheDefaultsALoneFailingJobPausesItsDestinationAtItsThirdTry` and `theLocalFolderHasNoDestinationStateSoAJobGoesToItAfterThreeTries`; the new rules are in `ExportFollowupsTest`, and one test was added for a job with no destination;
  - the P1 proof now expects the pause at the first failure, with no attempts used;
  - LV-DLV-007/008 now expect 6 tries;
  - `READY_FOR_EXPORT` was renamed `READY`; the Waiting-for-you list is read through its groups.
- `ProductAuthorizationPolicyTest`: the Processor's exact set now includes `EXPORT_RESEND` (the owner's decision).
- `JdbcPipelineJobRepositoryPostgresTest`: "resuming makes waiting jobs due" now says that only jobs that waited for the destination are made due; a case on its own schedule keeps it.
- The in-memory fakes and the fixture follow the new SQL. `InMemoryDestinationStates.recordTransientFailure` now fails a test if anything calls it.

Failures during development, all mistakes in my new tests, fixed in the tests (no production test weakened):
- one backup-rule test reached the same case twice;
- two PostgreSQL tests inserted a second enabled SDV connection, which the schema forbids for one tenant;
- one PostgreSQL test relied on the throwaway database's clock not stepping back: Docker's VM clock on Windows stepped back about 2 s during a run.

A frontend check found one real problem: the frontend test that mirrors the Java role mapping (`policyCapabilities.test.ts`) reads the Java `EnumSet.of(...)` up to the first `;`, and my comment inside it had one. I moved the comment.

## Files changed
- Domain: `PipelineJob` (+5 fields; the 0027 constructor kept), `ExportDestinationStatus` (+4 fields, `workerLimit`), new `ExportDestinations` (the local folder's id), `DeliverySubmission.rebuild`, `AuditAction` (+8).
- Application:
  - `ExportJobService` (rewritten rules), `ExportJobSettings` (new settings, schedule parser), `ExportJobQueue` (local folder id, `masterDestination` public);
  - ports `PipelineJobRepository` (Finish record and 5 queries), `ExportDestinationStateRepository` (+3), `DeliveryPort.checkDestination`, new `ExportReachabilityAlertRepository`, `ExportRetentionRepository`, `SplitCopyRetentionPort`, `RebuildPackageExportUseCase`;
  - `PackageDocumentExportService.rebuild`, `DerivedDeliveryService.verifyStoredCopy`, `TechnicalDependencyException` (429 mark);
  - new `ExportCopyRetention` and `ExportRetentionService`, wired into `PackageLifecycleService` (new constructor; the old ones keep the earlier rules).
- Infrastructure: migration 044 and the master changelog; `JdbcPipelineJobRepository`, `JdbcExportDestinationStateRepository`, new `JdbcExportReachabilityAlertRepository`, `JdbcExportRetentionRepository`, `RetryAfter`; `SdvDeliveryAdapter`, `DerivedDestinationDeliveryAdapter` (429 mark), `LocalFolderDeliveryAdapter` (check).
- API:
  - `UserCapability.EXPORT_RESEND`, `ProductAuthorizationPolicy`;
  - `ExportJobController` (resend-all, groups, retry-group, alert), new `ExportRetentionController`;
  - `OperationsController` and `DashboardItemResponse` (`exportState`);
  - `ExportJobWorkers` (alert check, worker restore), `ApplicationServiceConfig`, `application.yml`.
- Frontend (test data only): `e2e-oidc/policyCapabilities.ts`, the e2e copy of the role mapping, now includes `EXPORT_RESEND` for the Processor. Its test fails if the copy differs from the Java mapping.
- Tests as listed; `docs/work/0028-report.md`.

## Commits (hash and message), or "not committed"
- The task 0028 commit on `feature/0028-export-followups` (hash given to the owner in the session). Not merged, not pushed.

## Not done, and why
- The screens (0029): the endpoints and the export state are ready for them. The new audit actions have no screen wording yet (`auditRules.ts`).
- No live run with `export-mode=job` against the real Vero. The proof is the tests above: a fake destination, the real export service and real PostgreSQL.

## Questions for the owner
1. **Retention limits per destination:** I used the same 1 to 72 hours as the redacted-copy setting. Should a split destination be allowed longer?
2. **Inline mode and split copies:** a delivered split copy now follows its own clock in inline mode too. With the defaults it goes at most one purge cycle (15 min) after the master copy. Is that acceptable, or should per-destination retention apply in job mode only?

## Suggested next step
The design chat reviews `feature/0028-export-followups`, updates section 5 and section 10 of `staged-pipeline-design.md` to these rules, and writes task 0029 (the screens). Before turning `job` on anywhere: one run with `export-mode=job` against Vero R7, with Vero stopped mid-run and then a 429 from a test proxy.

## Owner's answers (2026-11-21, recorded by the design chat)

1. **Retention limits per destination:** keep 1 to 72 hours for now. Nothing is deleted while a copy's export job is open, so a long pause can't lose a split copy; a wider limit can come later if a customer needs it.
2. **Inline mode and split copies:** acceptable. Each copy following its own clock is the agreed rule; inline mode is on its way out.
3. The design chat updated `staged-pipeline-design.md` sections 5 and 10 to these rules (committed with these answers).
