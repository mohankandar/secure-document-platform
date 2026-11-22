# Report 0038: Claim-test failures, test isolation, and the open UI-review items

Date: 2026-11-22
Branch: `feature/0038-ui-backlog-tests`
Outcome: PARTLY DONE. Part A is done. Part B is done except two pieces that need data nobody stores yet: the rejection reason (B1) and the page number of a problem (B2). See "Not done".

## What was done

### Part A: the failing PostgreSQL tests

**Verdict: the product code was wrong in both claim failures.** The tests were right. The isolation item was a test problem.

1. **`anotherDestinationIsNotHeldUpByAFullOne` (line 160): a destination's jobs went out of queue order.**
   - Cause: each worker locked a row (`SKIP LOCKED`) *before* it took the destination's advisory lock. Worker A held job 1 and waited for the lock. Worker B held job 2, won the lock, and took job 2. So with a limit of 1, the second job ran while the first waited.
   - The limit held, but the queue order that task 0036 promised did not.
   - Evidence: it failed 6 of 6 runs here. The claimed set held a later job of the same destination.
2. **`workersClaimingAtTheSameMoment…` (line 129, "expected 1 but was 0"): a claimable job was not claimed.**
   - Cause: the database clock stepped back. A temporary diagnostic (scratch copy only, never committed) printed it in a failing round: the reset wrote `next_attempt_at = 16:43:28.4209`, and the workers' `clock_timestamp()` a moment later read `16:43:28.4076`, 13 ms earlier.
   - A READY job looked "not due yet", so all 8 workers found nothing. It failed 1 run in 6 and 1 in 2.
   - READY is only ever written with the clock of that moment, so it means "due now". Trusting a clock that can step back is the same kind of fault as 0035 finding 2.
3. **`resumingADestination…` (`NoSuchElementException`, line 253): fixed by item 2, not reproduced here.**
   - It did not fail in 3 runs of the four PostgreSQL classes together.
   - Line 253 is the first claim right after `makeDueNow` sets `next_attempt_at = clock_timestamp()`. The 13 ms step back gives exactly this failure there, and the item 2 fix removes it.
   - The isolation below was done anyway, as the task asks.

**Fix** (`JdbcPipelineJobRepository`):
- The claim names the destination without locking a row. It then takes the destination's advisory lock, counts the running jobs again, and locks and takes *that destination's first due job* (`FOR UPDATE`, no `SKIP LOCKED`).
- A claim locks a destination's rows only while it holds that destination's lock, so no deadlock is possible.
- Jobs with no destination keep `SKIP LOCKED` and have no limit.
- `READY` is due whatever `next_attempt_at` says. Only `WAITING_RETRY` waits for its time.
- The in-memory test copy (`InMemoryPipelineJobs`) follows the same rule.

**The limit still holds.** As in 0036's proof, I disabled the lock step in a scratch copy: 5 of 12 tests failed (up to 4 running at once against a limit of 1, and the queue-order test failed). With the lock: 12 of 12 pass.

**Isolation:** each `*PostgresTest` class now creates its own database (`lv_t_<class>_<random>`) through the new `PostgresTestDatabase`, and drops it afterwards (`WITH (FORCE)`). All four classes use it, including the migration 045 test.
- Why a database: the changelog creates objects without a schema name, and one test rolls changes back, so a schema per class would need a search path on every connection.
- Why not just cleaning tables: one class's leftovers could still reach another.
- Cost: one changelog run per class, which each class already did. No `lv_t_` databases were left behind.

**New tests:**
- a destination's jobs go out in queue order while 8 workers claim at once (5 cycles of 6 jobs);
- jobs without a destination have no limit and each is taken once;
- a READY job is taken even when the clock is behind its time;
- a WAITING_RETRY job still waits for its time.

### Part B: the UI-review items

**B1. Cases list.**
- `GET /packages` gains `stopReasonCode` and `destinationName`.
  - `stopReasonCode` is an LV code only. For a stopped case it is the latest incident's code. For a waiting case it is the export job's code (Waiting for IT, Waiting for you, destination paused, retrying).
  - `destinationName` is the main export's destination, or "Local export folder".
- The row shows the code's plain title under the next step ("Text reading timed out", "Destination refused the file") and "To Records archive".
- Anything that is not a known LV code is dropped, on the server and in the screen, so no free text can reach the row.
- **Rejected cases show no reason:** none is stored (see Not done).

**B2. Support.**
- A problem about one document says where it is: "It stopped at text recognition, in document 2 of 3, page 14." It is also shown as "Document position" under Technical details.
- The position is the document's place in upload order (upload time, then id). Documents have no position column; this needed no migration.
- The page is shown only when the problem recorded one, as a whole number. No file name appears anywhere.
- **Nothing records a page today** (see Not done), so in practice it shows "document 2 of 3".

**B3. Upload limits, enforced by the server.**
- New `CaseLimits`: 500 pages per file and 50 files per case.
- Where they are enforced:
  - `UploadDocumentService`, the one point every channel passes through: the 51st file is refused for every case type. Manual and watched-folder files over 500 pages are refused before they are stored.
  - Push, scanner and polling (`ExternalIntakeService`): a manifest of more than 50 files is refused before a case is made, and a PDF over 500 pages is refused where it is counted. Both apply even when the source's own limits are higher.
  - Watched folder: a drop of more than 50 files is refused before a case is made. It is recorded as INVALID_FILE with LV-INT-029.
- New codes **LV-INT-028** "Too many pages in one file" and **LV-INT-029** "Too many files in one case" (400, Refused). They are in the enum, `docs/work/lv-error-codes.md` (totals 137 → 139) and `docs/api/error-codes.md` with "what to do". The generated frontend code list was regenerated.
- New case:
  - The rule line reads "PDF only · up to 50 MB and 500 pages each · up to 50 files per case" before any file is picked.
  - A file over 500 pages is marked "Over 500 pages — split it into smaller PDFs first".
  - More than 50 files holds Create case: "A case holds up to 50 files. Remove 1 file, or put the rest in another case."
  - A server refusal shows on the file, for example "The PDF has more than 500 pages · LV-INT-028".

**B4. Branding accent colour removed** from the setting, the API (request and responses), the Branding screen, the legacy Branding panel, the types and the test fixtures.
- **The `accent_color` column stays, unused, with no migration.** The adapter no longer reads or writes it.
- The older screens keep the one accent colour they always had by default (`#315CF4`, fixed in `BrandingContext`). A tenant that had set its own accent now sees the default there.
- An old client that still sends `accentColor` is not refused: Spring ignores the unknown field.

**B5. Cases columns.**
- Saved choices were already stored and matched by key.
- Two places still followed position or label, in both `DashboardColumnConfigurationService` and `JpaOperationsReadAdapter`:
  - a column with no saved choice took its catalog index as its order, which could put it among the saved ones;
  - ties were broken by label.
- Now unsaved columns go after every saved one, and ties go by key. A rename or an added identifier no longer moves the saved order.

## Commands run and results

| Command | Result |
|---|---|
| Throwaway database `lexavault-0038-pg` (127.0.0.1:55448; password generated, kept only in the session scratchpad, never printed) | used for every PostgreSQL run |
| Claim test, base code, 6 runs | line 160 failed 6/6; line 129 failed 1/6 (round 7) |
| Claim test with diagnostics | caught the 13 ms clock step back (above) |
| All `*PostgresTest` after the fix, 3 runs | 12 + 11 + 9 + 9 tests, 0 failures each run |
| Same, with the lock step disabled (scratch copy) | 5 failures, as it should |
| `mvn -o clean verify` with the PostgreSQL tests on, Part A alone | PASS |
| `mvn -o clean verify` with the PostgreSQL tests on, final, **3 runs in a row** | **PASS 3/3, 0 failures** |
| `npm run test:standalone` | PASS: auth 10, vocabulary 186, ui-framework 17, oidc-signin 164, tablet 95, a11y 156 |
| `test-tools\no-redacted-values\Invoke-NoRedactedValueCheck.ps1 -JarPath <build>` | PASS, 0 matches; its container removed |
| `test-tools\compliance-report\Invoke-ComplianceReportCheck.ps1 -JarPath <build>` | PASS, 116 checks, 0 failed; its container removed |
| `scripts\Test-LexaVault-ExportE2E.ps1 -JarPath <build> -ProofDir docs\work\0038-proof\export-e2e` | PASS, 8 passed; its container removed |
| Playwright with `P0038_PROOF=1` (newCase, caseRows, support) | 17 passed; the 3 proof pictures |

Notes:
- The owner's app was running from this checkout's `target` jars (OIDC mode). To leave it running, every backend build ran in a scratch git worktree of this branch with the same changes copied in. The jar for the checks came from there.
- `-o` (offline) was used because every dependency was already in the local Maven repository.

## Tests: counts per module, and any failures with their output

Backend, final build (PostgreSQL on), each of the 3 runs: domain 76, application 290, scan bridge 136, infrastructure 70 (0 skipped), API 523. 0 failures.

New tests:
- Infrastructure: 4 methods in `JdbcClaimLockAndQueueOrderPostgresTest`, and the `PostgresTestDatabase` helper.
- Domain and application: `DashboardColumnKeysTest` (2), `DocumentPositionTest` (3), `BrandingServiceTest` (2 replace the accent test), `ExternalIntakeServiceTest` (+2), `WatchedFolderIntakeServiceRecoveryTest` (+1).
- API: `UploadDocumentPdfCheckTest` (+2), `ListPackagesStopReasonTest` (1), `SupportDocumentPositionTest` (1), `ExportFollowupsTest` (+1).
- Frontend: `caseRules.test.ts` (+2), `intakeRules.test.ts` (+1), `supportRules.test.ts` (+1), `newCase.spec.ts` (+2), `caseRows.spec.ts` (+1).

Tests changed, and why:
- `LvErrorCodeRegistryTest` and `lvCodes.test.ts`: the pinned counts go from INT 38 / 137 codes to 40 / 139, for the two new codes.
- `PackageListExportStateTest`: the list now reads `caseExportRows`, so the stub moved there. It also checks the two new fields.
- `BrandingServiceTest`: the accent assertions and the contrast test are gone with the field. A test now checks that no branding record has an accent.
- `newCase.spec.ts`: the rule line text changed.
- `support.spec.ts`: the "What happened" sentence now includes the position.
- `adminPlatform.spec.ts`: also checks that there is no accent control and no `accentColor` in the save request.
- Fixtures (`harness.ts`, `adminPlatform.spec.ts`): `accentColor` removed.

## Files changed

- **Part A:**
  - `JdbcPipelineJobRepository`
  - `PipelineJobRepository` (its contract comment)
  - `InMemoryPipelineJobs`
  - the four `*PostgresTest` classes
  - new `PostgresTestDatabase`
- **B1:**
  - backend: `ExportJobService` (`caseExportRows`), `PackageSummary`, `ListPackagesQueryService`, `PackageSummaryResponse`, `PackageController`, `ApplicationServiceConfig`
  - frontend: `caseRules.ts`, `Desk.tsx`, `studio.css` (two small rules for the new lines in the row), `types/index.ts`
- **B2:**
  - backend: new `DocumentPosition`, `SupportIncidentDetail`, `SupportIncidentQueryService`, `ApplicationServiceConfig`
  - frontend: `supportRules.ts`, `types/index.ts`
- **B3:**
  - backend: new `CaseLimits`, `LvErrorCode`, `UploadDocumentService`, `ExternalIntakeService`, `WatchedFolderIntakeService`
  - docs: both code registries
  - frontend: `lvErrorCodes.generated.ts`, `intakeRules.ts`, `UploadCapture.tsx`
- **B4:**
  - backend: `BrandingConfiguration`, `BrandingUpdate`, `EffectiveBranding`, `BrandingService`, `BrandingController`, `JdbcBrandingConfigurationRepositoryAdapter`
  - frontend: `BrandingSection.tsx`, `BrandingPanel.tsx`, `BrandingContext.tsx`, `types/index.ts`
- **B5:** `DashboardColumnConfigurationService`, `JpaOperationsReadAdapter`
- **Proof:** `docs/work/0038-proof/`
  - `cases-stopped-waiting-rejected.png`
  - `support-document-position.png`
  - `new-case-limits-and-refusal.png`
  - `export-e2e/` (the export run's own pictures)
  - The three named pictures come from the screen tests, with the API mocked.

No migration. No new dependency. `case-file-next/` (not mounted) is unchanged.

**Independence rule:** this task adds no other product's name or behaviour to code. The destination in the new tests is "Records archive".

## Commits

- `317416f` fix(export): task 0038 part A, claim order and READY due time; own database per PostgreSQL test class
- `25b9e2a` feat: task 0038 part B, Cases stop reason and destination, Support document position, case limits, accent removed, column keys (with this report and the proof pictures).

Not merged, not pushed.

## Not done, and why

1. **Rejection reason in the Cases list (B1).**
   - No rejection reason is stored anywhere. `DocumentPackage.reject()` takes none and has no callers. A review task's reject comment is not persisted (`review_tasks` has no such column). No code path sets a case to REJECTED today.
   - Showing a reason needs a place to store it, which is a schema change. The task allows a migration only for the limits.
   - The row is ready: it shows the code's title as soon as the list sends one.
2. **The page number in Support (B2).**
   - Nothing records it. The OCR timeout loses the page inside V1 processing (`TesseractOcrAdapter` / `HybridPdfTextExtractionAdapter`, a frozen area).
   - Support shows the page whenever a problem's metadata holds `page` or `pageNumber`.
3. **Parallel uploads at the file limit.**
   - The 50-file check reads the case's documents per upload. Two manual uploads sent at the same moment to a case holding 49 could both pass.
   - Closing that needs a lock on the case in the upload service; I left V1 upload's transactions as they are.

## Questions for the owner

1. **Rejection reason:** store it, either as a column on the case or a persisted review comment? That is a schema change. Who writes it, and where (review reject, approval reject)?
2. **Page number for document problems:** may the OCR failure carry its page number into the incident metadata? That touches V1 processing (frozen).
3. **Source limits above the case limits:** integration sources may still be set up to 2,000 pages and 1,000 documents, but the case limits now win (LV-INT-028/029). Should the Integrations screen cap its fields at 500 and 50?
4. **Parallel uploads at 50 files:** add a case lock in the upload service so two simultaneous uploads can never make 51?
5. **V1 upload change:** the limits change V1 upload behaviour (a frozen area). I took the task's "owner approvals given" as approval for that, as U005 foresaw. Please confirm.

## Suggested next step

The owner reviews the three proof pictures and answers questions 1 to 5. Then merge `feature/0038-ui-backlog-tests` into `feature/lexavault-r1b-tenant-identity`.

## Owner's answers (2026-11-22, recorded by the design chat; owner said "as recommended")

1. **Rejection reason:** no change now (no code path rejects a case today). Backlog: when a reject step is designed, it stores a **reason code** (an `LV-` code), never free text.
2. **Page number for text-reading failures:** yes. The OCR failure carries its page number (an integer only) into the incident metadata. Owner approves this small change in V1 processing (frozen area). Task 0039.
3. **Integration source limits:** yes, the Integrations screen and API cap pages per file at 500 and files per case at 50. Task 0039.
4. **Parallel uploads at 50 files:** yes, a lock on the case while the limit is checked. Task 0039.
5. **V1 upload change for the limits:** confirmed.
