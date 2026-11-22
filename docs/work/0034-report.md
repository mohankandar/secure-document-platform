# Report 0034: Staged pipeline P1b: clean-up of finished jobs

Date: 2026-11-22
Branch: `feature/0034-job-cleanup`
Outcome: DONE

## What was done

1. **Clean-up run inside the retention purge.** `RetentionPurgeScheduler` (the `securedoc.lifecycle.purge-*` scheduler, every 15 minutes by default) now runs `FinishedJobCleanupService` after the purge, with the same time and correlation id. There is no new scheduler. If one of the two fails, the other still runs.
2. **What is removed.** For each tenant, `pipeline_jobs` rows in DONE or STOPPED whose `updated_at` is before *now minus `keep-finished-days`*. A job of any other status is never removed. A finished job is also **kept, whatever its age**, while any of these is true:
   - the case's final retention purge has not run (`package_lifecycles.purged_at` is empty, or the case has no lifecycle row);
   - a redacted or split copy of the case is still stored (a `REDACTED` or `DERIVED_REDACTED` artifact not `DELETED`; a split copy can outlive the final purge on its own clock, task 0028);
   - the case still has an open job of any stage;
   - it is a DONE job and the case is still on the Cases list (`dashboard_hidden_at` is empty). See point 3.
3. **Batches.** Each delete removes at most `cleanup-batch-size` rows (default 1,000), oldest first, `FOR UPDATE … SKIP LOCKED`. Each batch commits on its own (no transaction around the batches). One run takes at most 100 batches per tenant; the rest waits for the next run.
4. **Reachability alerts.** A tenant's `export_reachability_alerts` row is removed when it is CLEARED and `cleared_at` is before the same cut-off. A RAISED alert is never removed. A new alert can still be raised afterwards (the raise inserts the row again; tested).
5. **Settings.** `securedoc.processing.export-jobs.keep-finished-days` (default 90, 7 to 3650) and `securedoc.processing.export-jobs.cleanup-batch-size` (default 1,000, 1 to 10,000), in `application.yml` with the environment variables `SECUREDOC_EXPORT_JOBS_KEEP_FINISHED_DAYS` and `SECUREDOC_EXPORT_JOBS_CLEANUP_BATCH_SIZE`. A value outside its range stops start-up. `StartupFailureLogger` logs it as `LV-SYS-005`. The exception message names the setting and its range, never the value. The log line has the code and type only.
6. **Audit.** `PIPELINE_JOBS_CLEANED` (new `AuditAction`), detail `Finished jobs cleaned: jobs=N, alerts=M, cutoff=YYYY-MM-DD`. The resource is the tenant, so no case ids. Audit records belong to a tenant, so there is **one record per tenant per run** that removed anything (not one for the whole platform). A run that removes nothing writes no record. The Audit page shows it as "Removed finished export jobs after their keep time" and has it in the Action filter (`auditRules.ts`).
7. **Inline mode.** Nothing to remove: the run makes one empty delete per tenant and writes nothing.
8. **No migration.** The delete runs per tenant, so it uses the existing `idx_pipeline_jobs_tenant_status (tenant_id, status, updated_at)` from migration 043. The PostgreSQL test proves this on 20,000 jobs with fresh statistics and no planner settings changed: a bitmap index scan on that index, and no sequential scan of `pipeline_jobs`. No schema change, no new dependency.

### What reads `pipeline_jobs`, and why the answers do not change

| Reader | What it reads | Why the clean-up does not change it |
|---|---|---|
| Reports (`/operations/reports`, CSV) | Not `pipeline_jobs`: packages, export records, audit | Not affected |
| Compliance report (`ComplianceReportService`, read port) | Not `pipeline_jobs` | Not affected (the compliance check passes, 116 of 116) |
| Audit trail | `audit_records` only | Not affected. The clean-up only adds `PIPELINE_JOBS_CLEANED` |
| Cases list and Operations dashboard (`exportState`) | The case's export jobs. "Exported" needs a DONE job | Open states use open jobs only. A DONE job is kept while the case is on the list. Removing a STOPPED job never changes the state |
| Case panel, export jobs (`/packages/{id}/export-jobs`) | The case's jobs | Only removed after the case's final purge, when it has left the Cases list |
| Waiting for you, Needs IT, destinations (open counts) | WAITING_FOR_YOU, NEEDS_IT, open jobs | Open jobs are never removed |
| Retention (`ExportRetentionService`, `DUE_SPLIT_COPIES_SQL`) | Open export jobs only | Open jobs are never removed. Jobs of a case with stored copies are kept anyway |
| Recovery (`JdbcPackageProcessingLeaseAdapter`) | Open EXPORT jobs | Open jobs are never removed |
| Support, reachability alert | The alert row | A removed CLEARED alert reads the same as no alert (not raised). Only the old "cleared at" time is gone |

Side effect to know about: the unique job identity no longer stops a second job with the same case, cycle and destination once the old job is removed. That can only happen after the case's final purge, and then the case cannot be exported again.

## Commands run and results

| Command | Result |
|---|---|
| `cd backend; mvn clean install` | BUILD SUCCESS. 1064 tests, 0 failures, 29 skipped (the PostgreSQL tests, skipped by design without a database) |
| The 3 PostgreSQL test classes (`mvn -pl securedoc-infrastructure test -Dtest=Jdbc*PostgresTest`) against a throwaway `postgres:16.4-alpine` (`lexavault-0034-pg`, 127.0.0.1:55432, generated password not printed; removed afterwards) | 29 run, 0 failures (11 + 9 new + 9) |
| `cd frontend/securedoc-ui; npm run test:standalone` (started 00:38) | auth 10, vocabulary 176, ui-framework 17 passed. oidc-signin: 160 passed, **1 failed** (time of day, see Tests). The chain stopped there, so the remaining suites were run one by one below |
| `npm run test:tablet` (run on its own after the chain stopped) | 95 passed |
| `npm run test:a11y` (run on its own) | 156 passed |
| `npm run test:oidc-signin` again, started 00:55 | 161 passed, 0 failed |
| `.\test-tools\no-redacted-values\Invoke-NoRedactedValueCheck.ps1` | PASS: 0 matches (database, 3 logs, 2 audit exports). Throwaway `lexavault-0015-pg` removed |
| `.\test-tools\compliance-report\Invoke-ComplianceReportCheck.ps1` | PASS: 116 checks, 0 failed. Throwaway `lexavault-0017-pg` removed |

None of `securedoc-postgres`, `sdv-postgres`, `sdv-vault`, `lexa-keycloak` or `lexa-keycloak-db` was touched.

## Tests: counts per module, and any failures with their output

Backend now: domain 76, application 281, scan bridge 136, infrastructure 58 (29 skipped without a database), API 513. Total, before (0030) → now: 1044 → 1064, and skipped 20 → 29. That is +20: 11 new unit tests and 9 new PostgreSQL tests.

Frontend, before (0030) → now: auth 10 → 10, vocabulary 175 → 176, ui-framework 17 → 17, oidc-signin 161 → 161, tablet 95 → 95, a11y 156 → 156. 0 failures in the final runs.

New tests:
- `FinishedJobCleanupServiceTest` (6): batches of 1,000 until a short batch (2,500 → 1000, 1000, 500); one audit record with the counts and cut-off date only; no record when nothing is removed; inline mode does nothing; at most 100 batches per tenant per run, the rest next run; a failing tenant does not stop the others; the bounds of both settings, and a message that never holds the value.
- `FinishedJobCleanupStartupTest` (3): the real bean method reads both settings (defaults 90 and 1,000); `application.yml` has the same defaults; 6, 3651 and 0 stop start-up, logged as `LV-SYS-005 type=IllegalArgumentException` without the message.
- `RetentionPurgeSchedulerCleanupTest` (2): the clean-up runs after the purge with the same time, actor and correlation; a failing purge does not stop the clean-up, and a failing clean-up is contained.
- `JdbcFinishedJobCleanupPostgresTest` (9, throwaway database): old DONE and STOPPED jobs removed, jobs changed 89 days ago and 1 day ago kept; READY, RUNNING, WAITING_RETRY, PAUSED, WAITING_FOR_YOU and NEEDS_IT jobs 3,000 days old kept; a finished job of a case with an open job kept; jobs of a case not purged, with no lifecycle row, with a stored split copy or with a stored redacted copy kept, while deleted copies and other artifacts do not keep a job; a DONE job of a case still on the Cases list kept and its STOPPED job removed; batches oldest first (2, 2, 1, 0) and only this tenant's jobs; the case and its lifecycle stay; old CLEARED alerts removed, recent CLEARED and RAISED alerts kept, and a new alert can be raised afterwards; the index check on 20,000 jobs.
- Frontend `auditRules.test.ts` (+1): the sentence, the Action filter entry, and no other action has the same sentence.

Test changes made while getting tests to pass (my own new tests only):
- The index test at first used 50 rows and `enable_seqscan = off`. PostgreSQL then started from the case table, so that test said nothing about scale. I replaced it with the 20,000-job set and normal planner settings. The first version of that assertion looked for the plain index-scan text; the plan uses a *bitmap* index scan on the same index, so I widened the assertion to accept either.
- My frontend test first checked that no two Audit actions anywhere read the same. That fails on an older duplicate: `FINDING_CONFIRMED` and `FINDING_REDACTED` both read "Redacted a finding". I narrowed the test to the new sentence. The old duplicate is left as it was (see Questions).

Frontend failure (`test:oidc-signin`, 1 of 161), not caused by this change:
```
e2e-oidc\exports.spec.ts:92:3 › Waiting for you (Processor and Supervisor) › the case panel: ...
Expected pattern: /Refused today at .+\. Once the destination’s team has fixed it, send it again\. .../
Received string:  "... Refused yesterday at 11:49 PM. Once the destination’s team has fixed it, ..."
```
The run started just after midnight (00:42). The fixture's refusal time is about 53 minutes before *now* (`minsAgo` in `e2e-a11y/harness.ts`), so it fell on the previous day. The test fails every night between 00:00 and about 00:55. This change touches no export screen. The rerun started at 00:55 passed all 161.

## Files changed

- Backend, new: `application/pipeline/FinishedJobCleanupService.java`, `application/pipeline/FinishedJobCleanupSettings.java`, `application/port/out/FinishedJobCleanupPort.java`, `infrastructure/pipeline/JdbcFinishedJobCleanupRepository.java`.
- Backend, changed: `api/lifecycle/RetentionPurgeScheduler.java` (runs the clean-up), `api/config/ApplicationServiceConfig.java` (2 beans), `application.yml` (2 settings), `domain/audit/AuditAction.java` (`PIPELINE_JOBS_CLEANED`).
- Backend tests, new: `FinishedJobCleanupServiceTest`, `FinishedJobCleanupStartupTest`, `RetentionPurgeSchedulerCleanupTest`, `JdbcFinishedJobCleanupPostgresTest`.
- Frontend: `src/studio/admin/auditRules.ts` (1 sentence), `src/studio/admin/auditRules.test.ts` (+1 test).
- No migration. No dependency.

## Commits (hash and message), or "not committed"

- `aa9a03f` feat(pipeline): task 0034 clean-up of finished jobs (P1b). This is the code, the tests and this report.
- A second commit adds this hash to the report.

Not merged, not pushed.

## Not done, and why

- Task 0033 (vision benchmark) still has no report. It is on its own branch, and the owner asked for 0034 by number in this session. 0033 was not touched.
- `docs/work/test-strategy.md` was already untracked before this task. It is not mine and is not committed.

## Questions for the owner

1. **One audit record per tenant per run** instead of one per run. Audit records belong to a tenant, and this way each entity's audit trail shows its own clean-up. Is that right?
2. **Jobs of cases that never reach a final purge** are kept forever (fail closed). An example is a case stopped in a status the purge does not handle. Should they get a rule of their own later?
3. `exports.spec.ts` "Refused today" fails every night between 00:00 and about 00:55, because its fixture time is relative to now. Should it get a fixed time? That would be a small test-only fix.
4. Older finding: `FINDING_CONFIRMED` and `FINDING_REDACTED` read the same on the Audit page ("Redacted a finding"). Do you want a separate sentence for one of them? That would be a small follow-up.

## Suggested next step

Owner review of the keep rules (point 2 of What was done) and questions 1 and 2. Then the next staged-pipeline task.

## Owner's answers (2026-11-22, recorded by the design chat)

1. **One audit record per entity per run:** right, keep it. Each entity's own trail shows its own clean-up.
2. **Jobs of cases that never reach a final purge:** no deletion rule. A case that never reaches a final state is a problem to surface, not to clean up: backlog item for the Operations tab (P6), "cases stuck outside a final state". Their jobs stay.
3. **`exports.spec.ts` "Refused today" around midnight:** yes, give it a fixed time. Done in task 0035.
4. **`FINDING_CONFIRMED` and `FINDING_REDACTED` wording:** yes, separate sentences ("Confirmed a finding" / "Redacted a finding"). Done in task 0035.
