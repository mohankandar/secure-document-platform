# Report 0036: Export P1 fixes from the end-to-end run

Date: 2026-11-22
Branch: `feature/0036-export-p1-fixes`
Outcome: DONE. All 7 items built and tested; the L5 suite passes with 4 workers and real-order checks. Two questions below.

## What was done

### Backend
1. **Per-destination worker limit holds under concurrency** (`JdbcPipelineJobRepository.claimNext`). The single `CLAIM_NEXT_SQL` statement is replaced by a claim in short transactions. Each try:
   1. picks the next due job (`CANDIDATE_SQL`, still `FOR UPDATE SKIP LOCKED`, same filters as before);
   2. takes `pg_advisory_xact_lock(hashtextextended('lexavault.export-destination:' || tenant || ':' || destination, 0))`;
   3. counts that destination's RUNNING jobs again, in a new statement;
   4. takes the job only if there is room. Otherwise it tries again, up to 3 times per poll.
   Jobs without a destination take no lock.
2. **Queue order never depends on the clock.** Migration **045** (`045-pipeline-job-queue-seq.xml`, registered in the master changelog as the task approved) adds `pipeline_jobs.queue_seq BIGINT`.
   - It is filled from the sequence `pipeline_jobs_queue_seq` by a column default, so the insert code is unchanged.
   - Existing rows are numbered in `created_at, id` order under an exclusive table lock. The column is then set NOT NULL and UNIQUE.
   - The pick-up index `idx_pipeline_jobs_pickup` is rebuilt on `(stage, next_attempt_at, queue_seq)`. A new index `(tenant_id, destination_id) WHERE status='RUNNING'` serves the limit count.
   - The claim, "resend all" and the job listings order by `queue_seq`. `next_attempt_at` still uses the database clock.
3. **One destination down raises the alert** (`ExportJobService`).
   - When the entity's **only** destination fails its check, it is paused as before **and** "LexaVault can't reach its destinations" is raised: one audit row, one incident.
   - The alert clears when any destination resumes, by the probe, Check now, IT, or the alert's own check (which now also resumes a paused destination whose check passes).
   - With two or more destinations nothing changed: when all fail, the alert is raised and nothing is paused.
   - `allDestinationsDown` is now true for a single destination.
4. **For the screens, in the existing responses (no new endpoint):**
   - `GET /packages/{id}/export-jobs` and `GET /support/export-jobs/needs-it` now carry `timings`: probe interval, retry span (the sum of the schedule), number of retries, whether the case is rebuilt once, and the interruptions allowed before IT. These come from `ExportJobSettings`.
   - Each IT group carries `cases: [{packageId, caseNumber}]`, the case's main ID from the existing `CasePrimaryIdentifierLookup`. This is needed because an IT Admin cannot open `GET /packages/{id}`. In 0035 that is why the card showed `c12725cb`.
   - Each destination carries `waiting: {queued, waitingForYou, needsIt}`.

### Screens
5. **Timings come from the settings:**
   - The case panel: "checks it every 5 minutes", or "every 4 seconds" in the test profile.
   - The Destinations note and the failed "Check now" toast.
   - The IT card: "retried over about 17 hours and rebuilt once" with the defaults (5 m + 15 m + 1 h + 4 h + 12 h = 17 h 20 m). "and rebuilt once" only shows when the schedule really rebuilds.
   - "Interrupted 3 times" uses the real number.
   - Rounding: seconds under a minute, minutes under an hour, hours above, with "about" when rounding changed the number.
   - With no timings (an older server) no number is named ("regularly").
6. **Cases page tiles:** New · Redacting · Review · Approval · **Exporting** · Exported · Stopped.
   - A case with an open export job ("Exporting · …" chip) counts under Exporting, whatever its status.
   - The tile uses the Exporting chip's grey colours.
   - The tile filter and the In progress / Exported groups follow the same rule, so a case still exporting is never listed under Exported.
7. **Support › Destinations, Waiting** counts each state on its own: "2 ready · 1 waiting for you · 2 need IT".
   - "need IT" is shown in red. Needs IT is never counted as ready.
   - A paused destination's queued jobs still read "N waiting".
8. **IT card wording:**
   - When the destination's check passes (it is Working), the title reads "2 cases can't be delivered to Local export folder since 9:55 AM". For LV-DLV-001/002 the reason line becomes "Delivering these cases fails · LV-DLV-001" instead of "Destination can't be reached".
   - While the destination is paused, the wording stays as it was.
   - Cases are listed by case number: "Case X." or "Cases X, Y." With more than 5: the first 5 and "and N more". A case with no number reads "Untitled case". The internal id is never shown.

## The lock choice and why

I used an **advisory transaction lock per (entity, destination)**, not a lock on the destination's state row:
- A destination often has **no** `export_destination_states` row (a working destination has none until something happens to it). There would be nothing to lock, and creating one on every claim would be an extra write.
- The state row is also updated by pauses, probes and 429 handling, so locking it would make claims wait on those.

The advisory lock needs no row, is released at commit, and only workers claiming the **same** destination meet it:
- Picking the candidate keeps `SKIP LOCKED`, so jobs of different destinations never wait on each other.
- The second worker for the same destination waits only for the first worker's few-millisecond transaction, then sees its RUNNING job and moves on.
- Each try holds at most one advisory lock, so two workers cannot deadlock on them.
- A hash collision between two pairs would only make them wait for each other; it can never let the limit be exceeded.

Proof that the lock is what holds the limit: with the lock step disabled, 4 of the 8 new PostgreSQL tests fail (for example "expected 1 claim, was 4" with limit 1, and "expected 2, was 5" with limit 2). With it, all pass.

## The migration (045)

- One changeset, `045-01-pipeline-job-queue-seq`, with a rollback.
- Tested on existing rows: a fresh throwaway database was migrated to the end, rolled back to 044, and 4 jobs were inserted with `created_at` out of insert order. 045 was then applied: the jobs were numbered 1–4 in `created_at` order, the column is NOT NULL, the pick-up index uses `queue_seq`, and the next queued job got 5 and was claimed last.

## Before / after pictures

New pictures in `docs/work/0036-proof/` (1440 × 900, light); the 0035 ones stay in `docs/work/0035-proof/` for comparison.

| Picture | Before (0035) | After (0036) |
|---|---|---|
| `01-case-exporting-destination-paused.png` | Tile "Redacting 3"; panel "checks it every 5 minutes" (profile was 4 s) | Tiles New · Redacting · Review · Approval · **Exporting 3** · Exported · Stopped; panel "checks it every 4 seconds" |
| `01b-support-destination-paused.png` | Paused, no alert, note "every 5 minutes" | **"LexaVault can't reach its destinations"** shown; note "every 4 seconds"; Waiting "3 waiting" |
| `05-support-it-queue-grouped-alert.png` | "2 cases failing to …", "Destination can't be reached", "retried over 24 hours", "Cases c12725cb, 898e533f", Waiting "2 ready" | "2 cases can't be delivered to Local export folder since 9:55 AM", "Delivering these cases fails · LV-DLV-001", "retried over 10 seconds and rebuilt once", "Cases 356425098005, 351352771054.", Waiting "**2 need IT**" in red |

The other six pictures (02, 03, 03b, 04, 05b, 06) were retaken in the same run. Their content is unchanged except for the new tile strip, where the Cases list shows.

## Commands run and results

| Command | Result |
|---|---|
| `cd backend; mvn clean install` | BUILD SUCCESS. 1077 tests, 0 failures, 37 skipped (the PostgreSQL tests, skipped by design without a database) |
| PostgreSQL tests `Jdbc*PostgresTest` against throwaway `lexavault-0036-pg` (postgres:16-alpine, 127.0.0.1:55436, generated password not printed) | 37 run, 0 failures: new `JdbcClaimLockAndQueueOrderPostgresTest` 8, `JdbcExportFollowupsPostgresTest` 11, `JdbcFinishedJobCleanupPostgresTest` 9, `JdbcPipelineJobRepositoryPostgresTest` 9. Container removed |
| The same new test class with the lock step disabled (temporary local edit, reverted at once) | 4 failed, as expected (proof above) |
| `cd frontend/securedoc-ui; npm run test:standalone` | auth 10, vocabulary 182, ui-framework 17, oidc-signin 161, tablet 95 passed; **a11y 154 passed, 2 failed** (see Tests) |
| `npm run test:a11y` again on its own | 154 passed, 2 failed (2 different tests; see Tests) |
| `npx playwright test --config playwright.a11y.config.ts e2e-a11y/screens.spec.ts e2e-a11y/helpTip.spec.ts e2e-a11y/keyboard.spec.ts` (every test that had failed) | 118 passed, 0 failed |
| `npx playwright test --config playwright.oidc-signin.config.ts e2e-oidc/exports.spec.ts` | 23 passed |
| `.\test-tools\no-redacted-values\Invoke-NoRedactedValueCheck.ps1` | PASS: 0 matches. `lexavault-0015-pg` removed |
| `.\test-tools\compliance-report\Invoke-ComplianceReportCheck.ps1` | PASS: 116 checks, 0 failed. `lexavault-0017-pg` removed |
| `.\scripts\Test-LexaVault-ExportE2E.ps1` (fresh jar, **4 workers, 1 per destination**) | PASS: 8 of 8, 2.6 min. `lexavault-0035-pg` removed |

None of `securedoc-postgres`, `sdv-postgres`, `sdv-vault`, `lexa-keycloak` or `lexa-keycloak-db` was touched.

## Tests: counts per module, and any failures with their output

**Backend, 0035 → now:**
- domain 76 → 76, application 281 → 281, scan bridge 136 → 136.
- infrastructure 58 → 66 (+8 PostgreSQL tests, skipped in the normal build).
- API 513 → 518.
- Total 1064 → 1077, 0 failures.

**New backend tests:**
- `JdbcClaimLockAndQueueOrderPostgresTest` (8):
  - 8 workers at once with limit 1, 25 rounds: exactly 1 RUNNING each round.
  - With limit 2: exactly 2.
  - Two destinations at once: one job each; a full destination does not hold up the other.
  - 4 workers draining 12 jobs: never 2 RUNNING.
  - Pick-up and the job listing follow `queue_seq` when `created_at` is out of order.
  - Resend all follows `queue_seq` when `created_at` is out of order.
  - The queue number always increases.
  - Migration 045 on existing rows.
- `ExportFollowupsTest` (+2 net):
  - The only destination down: paused and alert raised; the probe resumes it and clears the alert; cases go out in order.
  - The alert's own check resumes it.
  - Check now clears it.
- `ExportScreensSupportTest` (+3): waiting counts per state; timings from the settings (default and custom); IT groups list case numbers.

**Changed backend tests (said plainly):**
- `aSingleDestinationDownIsAPauseEvenAlone` asserted *no* alert. It was replaced by the new expectation (alert raised), which is the owner's answer 1.
- `ExportFollowupsTest.aFailedCheckPausesTheDestination…` and `ExportJobServiceTest.p1ProofDestinationDownMidRun…` asserted *no incident at all* for a single paused destination. They now assert exactly one incident, the alert's (`ALL_DESTINATIONS_UNREACHABLE`), and nothing for any case. Same reason.

**Frontend:**
- vocabulary 177 → 182: 5 new `exportRules` tests (timing words, sentences from settings, Waiting parts, IT card wording and case list, the Exporting tile).
- The `triedLine` assertion changed from "over 24 hours" to "over about 17 hours", as approved.
- `e2e-oidc/exports.spec.ts` (mocked suite) now checks:
  - the 7 tiles in order, the Exporting count and the filter;
  - "every 5 minutes" in the paused panel;
  - "can't be delivered" on a working destination's card;
  - "Case LN-6990." by number;
  - "3 ready · 3 waiting for you · 1 needs IT" with the red part.
  - The fixtures (`exportFixtures.ts`) carry `timings`, `cases` and `waiting`.

**L5 suite (`e2e-export`):**
- Scenario 2 now expects the alert raised once, visible on Support, and cleared once the probe resumes the folder. It also checks the panel and note say "every 4 seconds", the Exporting tile is 3 and Redacting is 0, and Waiting reads "3 waiting".
- Scenario 5a checks case numbers in the API and on the card, the new title and reason, "retried over 10 seconds", and "2 need IT" in red.
- The order checks are strict again: queue order = the real order the cases finished, and the order the files were written in the folder = that order. The DONE audit order matches too. Nothing is recorded instead of failing.
- The profile runs `workers-total: 4`, `workers-per-destination: 1`.

**a11y failures (not product failures, in my reading):**
- In the full `test:standalone` run, `helpTip.spec.ts:78` and `keyboard.spec.ts:57` timed out.
- In the standalone `test:a11y` rerun, two *different* tests, `screens.spec.ts:275` and `:282`, timed out. That run reported "154 passed (6.6h)", so the machine was suspended during it.
- All four fail at the same point, before any page under test is shown:
  ```
  Error: locator.waitFor: Test timeout of 60000ms exceeded.
    - waiting for locator('#cf-main h1').first() to be visible
    - navigated to "http://localhost:15177/?code=a11y-code&state=…"
    at signInAs (e2e-a11y/harness.ts:414)
  ```
- The first run overlapped the two backend checks I ran at the same time.
- Re-running every spec file that contained them gave 118 passed, 0 failed.
- I did not change any test for this.

## Files changed

- **Backend:**
  - `JdbcPipelineJobRepository.java` (claim with lock, `queue_seq` order, per-status counts)
  - `PipelineJobRepository.java` (one new method)
  - `ExportJobService.java` (single-destination alert, alert cleared on resume, timings, waiting counts, case numbers)
  - `ExportJobController.java` (`timings` in two responses)
  - `ApplicationServiceConfig.java` (wiring)
  - new `045-pipeline-job-queue-seq.xml` and `db.changelog-master.xml`
- **Backend tests:** new `JdbcClaimLockAndQueueOrderPostgresTest.java`; `ExportFollowupsTest`, `ExportJobServiceTest`, `ExportScreensSupportTest`, `ExportJobFixture`, `InMemoryPipelineJobs`.
- **Frontend:** `exportRules.ts` (+ test), `support/SupportExport.tsx`, `support/support.css`, `CaseDrawer.tsx`, `Desk.tsx`, `data.ts`, `words.ts`, `studio.css`.
- **Suites:** `e2e-oidc/exportFixtures.ts`, `e2e-oidc/exports.spec.ts`, `e2e-export/export.e2e.spec.ts`, `e2e-export/stack.ts`.
- **Tools:** `test-tools/export-e2e/application-export-e2e.yml` (4 workers), `scripts/Test-LexaVault-ExportE2E.ps1` (new `-ProofDir`, default `docs/work/0036-proof`), `scripts/test-lexavault-export-e2e.sh` (same default; still not run, as in 0035).
- **New:** `docs/work/0036-proof/*.png` (9), this report.

No new dependency. No default setting changed. Migration 037 untouched; shared-issuer still off.

## Commits (hash and message), or "not committed"

See the commit after this report is written (hash added in a follow-up commit). Not merged, not pushed.

## Not done, and why

- The bash twin of the L5 script was not run (Windows machine, as in 0035).
- The in-memory test fake (`InMemoryPipelineJobs`) still orders by its own creation time. It is single-threaded and uses a test clock, so the order is the insert order anyway. The real order is proved by the PostgreSQL tests.

## Questions for the owner

1. **Alert wording with one destination.** The alert says "The problem is likely LexaVault's own network, not the destinations." With a single destination LexaVault cannot tell the two apart, so that line can be wrong (in scenario 2 it *is* the destination). Should the line be different when there is only one destination, for example "LexaVault can't reach Local export folder. Its health check fails."?
2. **A case with status COMPLETED but an open split export** now shows under the Exporting tile and the In progress group, no longer under Exported. I applied the tile rule to the groups too, so the list and the tiles agree. Is that right?

## Suggested next step

Owner review of the pictures in `docs/work/0036-proof/` and the two questions. Then merge `feature/0036-export-p1-fixes` into `feature/lexavault-r1b-tenant-identity` when approved.
