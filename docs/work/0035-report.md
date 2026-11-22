# Report 0035: Export end to end against the folder destination (L5)

Date: 2026-11-22
Branch: `feature/0035-export-e2e-folder`
Outcome: DONE (all 7 scenarios pass on the real stack). Two findings and five questions below; nothing in the product was changed.

## What was done

1. **The L5 suite.** `frontend/securedoc-ui/e2e-export/export.e2e.spec.ts`, 8 Playwright tests (scenario 5 is two tests), every title tagged `@e2e-export`. Own config `playwright.export-e2e.config.ts`, not in `test:standalone` or any normal build. It runs against the **real** backend jar in `export-mode=job`, a throwaway PostgreSQL, the real folder destination, and the real UI (Vite dev server, LOCAL mode, Case File UI).
   - `e2e-export/stack.ts` starts and stops the jar itself (scenario 6 kills it mid-run), talks to the API with the LOCAL identity headers, reads the throwaway database with `docker exec … psql`, and controls the folder.
   - `e2e-export/global-setup.ts`: a mock Ollama on 127.0.0.1 that finds nothing, so a case finishes with no review and goes straight to its export job.
   - **One entity per scenario** (8 tenant ids), so pauses, destination states and the backup rule's count never mix. The entities are created in the throwaway database by copying the LOCAL demo entity's set-up rows (capabilities, identifier rules, sensitive-data policy).
   - **Down / up.** The folder adapter creates missing folders, so renaming the folder away does not make it unreachable. "Down" therefore puts a plain *file* where the entity's export folder was; "up" removes it and puts the folder back.
   - **One case failing while the folder is fine:** a plain file where that case's folder would go (`<out>/<entity>/<case>`), put there before the case is submitted. Its write fails with LV-DLV-001; the health check (which writes under `.staging`) passes. No test hook was added to the product.
2. **Test profile** `test-tools/export-e2e/application-export-e2e.yml` (Spring profile `export-e2e`, loaded from that folder with `--spring.config.additional-location`; not in the jar, no default changed): retry schedule `2s,2s,2s,2s,2s`, rebuild before the 3rd retry (default), backup rule 3 cases in 10 minutes (default), probe every 4 s, poll 300 ms, claim lease 6 s, **one export worker** (`workers-total: 1`, see finding 1), purge every 10 s.
3. **Script** `scripts/Test-LexaVault-ExportE2E.ps1`: starts `lexavault-0035-pg` (127.0.0.1:55435, generated password, never printed), runs the suite and the screenshots, prints each test with its time, stops a backend left running, removes only that container. `-Grep` runs a subset; `-LogDatabaseWrites` keeps the database's write log for diagnosis. A bash twin `scripts/test-lexavault-export-e2e.sh` is written but **not run** (this machine is Windows; Git Bash would hand `/c/...` paths to the Windows `java`).
4. **Fix 1:** `e2e-oidc/exports.spec.ts` "Refused today": the page clock is fixed (`page.clock.setFixedTime`, 20 Nov 2026 3:00 PM) and so is that job's refusal time (2:05 PM), so the test no longer depends on the hour. It now checks the exact line "Refused today at 2:05 PM."
5. **Fix 2:** Audit page wording (`auditRules.ts`): `FINDING_CONFIRMED` reads "Confirmed a finding" (with a type: "Confirmed a Social Security Number"), `FINDING_REDACTED` "Redacted a finding". `auditRules.test.ts` updated and one test added: the two differ, and **no two actions in the Action filter read the same any more**. `e2e-oidc/adminAudit.spec.ts` looked for the old sentence "Redacted a Social Security Number" on a `FINDING_CONFIRMED` row; changed to "Confirmed a Social Security Number" (3 lines). That is the approved wording change, not a test bent to pass.

## Scenarios (final run with the freshly built jar)

Run `20261122-021117`: **8 of 8 passed, 2.6 min** in all (backend start included).

| # | Scenario | Result | Time | What it checks |
|---|---|---|---|---|
| 1 | Happy path | PASS | 9.8 s | 3 cases → 3 jobs DONE; each case has exactly `cycle-1` with its one file in the folder; case state EXPORTED and "Exported" on screen; audit: 3 × EXPORT_JOB_QUEUED, 3 × RUNNING→DONE, 3 × PACKAGE_EXPORT_RECORDED, 3 × PACKAGE_PROCESSING_COMPLETED |
| 2 | Destination down, check fails | PASS | 20.6 s | Folder taken away, 3 cases finish: destination PAUSED (one EXPORT_DESTINATION_PAUSED, reason CHECK_FAILED); all 3 at 0 attempts (one WAITING_RETRY "DESTINATION_PAUSED" LV-DLV-001, two READY); all 3 "Exporting · destination paused"; checked by the probe (last check moves) and still paused 9 s later; Support shows it paused with 3 waiting; **no** "can't reach its destinations" alert (question 1). Folder back: resumed by the probe (one EXPORT_DESTINATION_RESUMED by=probe), all 3 DONE in queue order, 1 attempt each, one `cycle-1` each |
| 3 | One case fails, destination healthy | PASS | 14.5 s | The other 2 cases DONE while the failing one is still retrying (not held up). The failing case: WAITING_RETRY ×3, **rebuilt once**, WAITING_RETRY ×2, then NEEDS_IT at 6 attempts, LV-DLV-001; waits ≥ 1.5 s apart (2 s schedule); one rebuild audit, one IT notification; destination never paused; case "Exporting · needs IT" |
| 4 | Backup rule | PASS | 5.1 s | 3 different cases failing → EXPORT_DESTINATION_PAUSED "reason=FAILING_CASES, cases=3", none for CHECK_FAILED; Support's Check now passes. Once the cases can be written, all 3 DONE |
| 5a | IT queue, Retry all | PASS | 17.4 s | 2 cases in Needs IT form **one** group (Local export folder, LV-DLV-001, count 2) with **one** notification; "Retry all 2" clicked on the Support screen → both DONE; one group-retry audit, 2 job retries |
| 5b | Resend all, who may | PASS | 6.7 s | 2 refused cases (refusal put in place, see Not done) → case panel and Waiting for you; "Resend all 2" → confirmation → "Resend 2 cases" → toast → both DONE, one `cycle-1` each, one RESEND_ALL audit. `EXPORT_RESEND`: Processor 200, Supervisor 200; Business Admin, Report Viewer, IT Admin, Platform Ops 403; "REVIEWER" 200 (question 2) |
| 6 | Restart in the middle | PASS | 42.0 s | Folder down, 3 cases waiting (1 WAITING_RETRY, 2 READY, destination PAUSED); the backend is **killed** and started again: same statuses and attempts, still paused. Folder back: all 3 DONE in queue order; exactly one `cycle-1` and one file per case; 3 DONE audits, 3 export records; `.staging` empty. Includes 36 s wait (see Findings, storage) |
| 7 | Retention | PASS | 6.4 s | A: a refused case past its window: the purge keeps its copy; the resend is DONE and the exported file's SHA-256 equals the stored redacted copy's checksum. B: an exported case past its window: the purge deletes the copy (artifact DELETED, REDACTED_ARTIFACT_PURGED, `redactedPurgedAt` set); a resend is refused `saved=false, LV-CFG-011` (question 3) |

Database clock against Windows during this run, sampled before and after each scenario: between +1.5 s and −0.8 s (finding 2).

## Proof pictures (L6), `docs/work/0035-proof/`, 1440 × 900, light

| Picture | State | Compared with the canvas board |
|---|---|---|
| `01-case-exporting-destination-paused.png` | Case at "Exporting · destination paused" (scenario 2) | CaseExport |
| `01b-support-destination-paused.png` | Support › Export, the folder "Paused since …, Health check failing · next check …", Check now | Support |
| `02-case-exporting-needs-it.png` | Case at "Exporting · needs IT" (scenario 3) | CaseExport |
| `03-case-exporting-waiting-for-you.png` | Case at "Exporting · waiting for you", "Resend this case" (scenario 5b) | CaseExport |
| `03b-waiting-for-you.png` | Waiting for you, one group, "Resend all 2" | Main |
| `06-resend-all-confirm.png` | The Resend all confirmation | ResendAll |
| `04-case-exported.png` | Case "Exported", "Exported to the delivery folder" (scenario 1) | CaseExport |
| `05-support-it-queue-grouped-alert.png` | IT queue: one grouped card, "2 cases failing to Local export folder", LV-DLV-001, "Retry all 2" | Support |
| `05b-support-after-retry-all.png` | After Retry all: the toast, "No export is waiting for IT" | Support |

Differences from the canvas "LexaVault · Export screens" (read from the canvas itself):

1. **Resend all** opens as a panel on the right; the board shows a centred dialog. Words, the per-code summary and both buttons are the same. (As built in 0029.)
2. **Cases list**: the board's columns are Case · Source · Docs · Where it is; the built list has the case, the next step, the status chip and the time, and the count tiles above it. The chips' words and colours match the board.
3. **Count tiles**: a case that is exporting (status PROCESSING) is counted under **Redacting**, while its chip says "Exporting · …" (picture 01: "Redacting 3" with three paused exports). The board has no tiles, so this is not a canvas difference, but it reads oddly. See question 5.
4. **Support › Destinations, "Waiting"**: two jobs in **Needs IT** at a working destination show as "**2 ready**" (picture 05). The board only shows ready jobs at a working destination. See question 5.
5. **Fixed words that assume the default timings.** The case panel says "checks it every 5 minutes", the Destinations note says the same, and the IT card says "Each was retried over 24 hours and rebuilt once". In this run the profile used 4 s and 2 s, so the pictures say more than happened. With the defaults the first is right; the last is not: the default schedule (5 min, 15 min, 1 h, 4 h, 12 h) adds up to **17 h 20 min**, not 24 h. The board has the same sentence. See question 4.
6. Case names are the 12-digit contract number (the LOCAL identifier rules) where the board shows "LN-7004"; the destination is "Local export folder" where the board shows "Document vault".

## Findings (not fixed)

1. **The per-destination worker limit can be exceeded.** With 4 workers and `workers-per-destination: 1`, two jobs of the same destination ran at the same time after a resume (they finished 0.5 ms apart; run 2026-11-22 01:27). `CLAIM_NEXT_SQL` counts the destination's RUNNING jobs inside each worker's own statement; two workers claiming at the same moment both see 0 and both claim (the first one's row is locked, so the second takes the next job). Effect: at most a few extra parallel sends to one destination, and the order they *arrive* in can differ. The fix is in claim locking (for example a lock per destination while claiming) and needs its PostgreSQL tests; not small, so not done. The suite uses one worker in all so its order checks test the designed order.
2. **Queue order follows the database clock.** Jobs are ordered and made due by `created_at` / `next_attempt_at` from PostgreSQL's `clock_timestamp()`. On this machine the database runs in Docker Desktop's VM, whose clock stepped back and forth by up to about 1.5 s against Windows during a run (the suite records the skew before and after each scenario; runs saw from +1.5 s to −1.0 s). Twice a case's job got a `created_at` earlier than the job of the case before it (proved from the app's own host-clock values in the database write log), and so went out first. A job stamped just before a backward step also waits up to the size of the step. On a server with a normal NTP-slewed clock this should be rare; an order that never depends on the clock needs a sequence column (a schema change). The suite now checks that jobs go out in LexaVault's own queue order and records, without failing, when that differs from the real order.

Also seen, not export: after an abrupt stop, uploads of new cases are refused with **LV-SYS-002 "Storage not available" for up to 30 s**. Processing storage counts the dead server as an active node until its last heartbeat is 30 s old (`ProcessingStorageConfigurationService.ACTIVE_NODE_WINDOW`), and local-folder storage refuses new uploads while two nodes look active. Fail-closed and by design as far as I can tell; scenario 6 waits it out.

## Commands run and results

| Command | Result |
|---|---|
| `cd backend; mvn clean install` | BUILD SUCCESS. 1064 tests, 0 failures, 29 skipped (the PostgreSQL tests, skipped by design without a database) |
| `cd frontend/securedoc-ui; npm run test:standalone` | All passed: auth 10, vocabulary 177, ui-framework 17, oidc-signin 161, tablet 95, a11y 156 |
| `npx playwright test --config playwright.oidc-signin.config.ts e2e-oidc/exports.spec.ts e2e-oidc/adminAudit.spec.ts` (the two fixes, run first on their own) | 27 passed |
| `.\test-tools\no-redacted-values\Invoke-NoRedactedValueCheck.ps1` | PASS: 0 matches (database, 3 logs, 2 audit exports). `lexavault-0015-pg` removed |
| `.\test-tools\compliance-report\Invoke-ComplianceReportCheck.ps1` | PASS: 116 checks, 0 failed. `lexavault-0017-pg` removed |
| `.\scripts\Test-LexaVault-ExportE2E.ps1` (final, fresh jar) | PASS: 8 of 8, 2.6 min. `lexavault-0035-pg` removed |

Earlier runs of the new suite while building it: 8 full runs and 4 partial ones; the failures are listed under Tests.

None of `securedoc-postgres`, `sdv-postgres`, `sdv-vault`, `lexa-keycloak` or `lexa-keycloak-db` was touched. The only containers used were the throwaway `lexavault-0035-pg` (each run), `lexavault-0035-probe` (one manual look at the schema), and the checks' own `lexavault-0015-pg` / `lexavault-0017-pg`; all removed. One `alpine` image was pulled to read the Docker VM clock (`docker run --rm`).

## Tests: counts per module, and any failures with their output

Backend (unchanged): domain 76, application 281, scan bridge 136, infrastructure 58 (29 skipped), API 513. Total 1064, as after 0034.

Frontend, before (0034) → now: auth 10 → 10, vocabulary 176 → 177 (+1, the new audit wording test), ui-framework 17 → 17, oidc-signin 161 → 161, tablet 95 → 95, a11y 156 → 156. 0 failures.

New L5 suite: 8 tests, all passed (not part of any normal run).

Test changes while getting the new suite to pass (my own new tests only):
- Scenario 3 first required ≥ 1.9 s between the failing case's status audit rows. Rows are written just after each try, so a 2 s wait read as 1.89 s; relaxed to ≥ 1.5 s.
- Scenarios 2 and 6 first required the jobs to finish in the order the cases were created. That failed three times: once from finding 1 (4 workers), twice from finding 2 (clock). Now: one worker, and the order is checked against LexaVault's queue order (above).
- Scenario 2 first expected the oldest job to be the one that hit the down folder; with finding 2 that can be the second one. It now checks one waiting job (WAITING_RETRY, DESTINATION_PAUSED, LV-DLV-001, no attempt) and two READY.
- Scenario 6 waits until 36 s after the stop before handing over (the storage behaviour above); scenario 7 failed with LV-SYS-002 before that.

## Files changed

- New: `frontend/securedoc-ui/e2e-export/stack.ts`, `e2e-export/global-setup.ts`, `e2e-export/export.e2e.spec.ts`, `playwright.export-e2e.config.ts`, `vite.export-e2e.config.ts` (same app, `/api` sent to the test backend's port), `test-tools/export-e2e/application-export-e2e.yml`, `scripts/Test-LexaVault-ExportE2E.ps1`, `scripts/test-lexavault-export-e2e.sh`, `docs/work/0035-proof/*.png` (9), this report.
- Changed: `src/studio/admin/auditRules.ts` (1 word), `src/studio/admin/auditRules.test.ts`, `e2e-oidc/exports.spec.ts` (fixed clock), `e2e-oidc/adminAudit.spec.ts` (new wording).
- Backend: nothing. No migration, no dependency, no default changed.

## Commits (hash and message), or "not committed"

- See below (filled in after the commit).

## Not done, and why

- **"Waiting for you" from the folder itself.** The folder can't refuse a file (LV-DLV-003/004); its failures are LV-DLV-001 (can't write) or LV-DLV-010 (folder exists, goes to IT). For scenarios 5b and 7 the refusal is put in place in the throwaway database (`markRefused`: the waiting job becomes WAITING_FOR_YOU with LV-DLV-004, exactly the row a refusal leaves). Everything after it (Waiting for you, the panel, Resend all, the resend, the export) ran for real. The mock destination server (L4) will give a real refusal.
- **"Shortened" retention window.** The window can't be shorter than 1 hour (`ExportRetentionService.MIN_HOURS`); the scenario moves the case's `redacted_delete_at` into the past in the throwaway database and calls `POST /lifecycle/purge-due`.
- The bash twin was not run (see What was done, 3).
- Scenario 2's IT alert: see question 1.

## Questions for the owner

1. **"One IT alert 'LexaVault can't reach its destinations' if it is the only destination."** The code raises that alert only when *every* destination fails and there are **at least 2** (`allDestinationsDown`); with only the folder it **pauses** it and raises no alert (scenario 2 checks exactly that). The canvas note says the alert replaces the per-destination view "if every destination fails its check at the same time". Should a single destination that is down also raise the alert (and then pause or not)? Product behaviour, so not changed.
2. **"A Reviewer cannot resend all."** LexaVault has no Reviewer role: `REVIEWER` (and `USER`) are read as **Document Processor** (`ProductRole.fromIdentityName`), so a "Reviewer" can resend. Scenario 5b checks that Processor and Supervisor can, and Business Admin, Report Viewer, IT Admin and Platform Operations get 403. Should a separate reviewer role exist, or is the alias right?
3. **Resend after the retention window.** Nothing is deleted while a case's export job is open, so a case waiting for a resend always keeps its copy (scenario 7 A: past its window, purged, still there, and the resend sent exactly that copy, same SHA-256). An exported case past its window loses its copy, but it can't be resent: the resend API answers `saved=false, LV-CFG-011` ("not waiting for a person") and the screen offers no Resend. So "resend says the copy is gone" can't happen today. Is that the intended rule, or should there be a resend of an exported case (which would then need that message)?
4. **"Each was retried over 24 hours"** (IT card) while the default schedule adds up to 17 h 20 min. Change the words ("over about 17 hours"), or the schedule? And should the "checks it every 5 minutes" sentences follow the setting?
5. **Two small screen points**: exporting cases counted under the "Redacting" tile; Needs IT jobs counted as "ready" in Support › Destinations. Worth a small UI task?

## Suggested next step

Owner review of the findings and questions. Then, if wanted, a small task for finding 1 (claim lock per destination, with its PostgreSQL test) and the two screen points. The suite is ready to move onto the mock destination server when platform-core delivers it (switch-over part 3).
