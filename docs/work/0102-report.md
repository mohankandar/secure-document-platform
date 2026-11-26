# Report 0102: folder intake is all or nothing

Date: 2026-11-26
Branch: `feature/0102-folder-intake-atomic`, from the integration branch (a then-current head; 0097 merged in before the PR)
Outcome: **DONE** (3 questions)

## What was wrong
A folder drop's case was made first and its files taken one by one after. The password-protected drop (contract 779001126006) passed the folder's own checks (manifest valid, a `.pdf`), so the case was made; the file was then refused by the upload's PDF check. The refusal was recorded and the folder moved to `_problem` correctly, but the case stayed: CREATED, 0 documents, "In progress" on the dashboard, with nothing to close it.

A listed file that is **missing** was already refused before any case (`MISSING_FILE`, LV-INT-032); a test now proves it.

## What changed
1. **Watched folder** (`WatchedFolderIntakeService`):
   - **Every listed file is checked before the case is made**: not empty (LV-INT-006), a PDF the server can open and with no password (LV-INT-008), at most 500 pages (LV-INT-028). Same checks and codes as the upload, so the intake problem reads as before.
   - **Fallback** (a file failing later, e.g. storage, or a crash between making the case and taking its files): on any refusal, a CREATED case of the same drop is hidden from the dashboard, then cancelled, then the refusal is recorded and the folder moved. Every step can repeat: a crash in between leaves the claim, and the retry under the multi-node claim rules finishes it. A case already under way (SUBMITTED or later) is never touched.
2. **Upgrade step: migration 086** (data only, no schema change): each folder case still CREATED whose drop's latest intake record is REJECTED becomes CANCELLED and hidden from the dashboard. Its row, intake record and audit stay for reports. The rollback leaves them closed.
   - It is included in the master changelog **after 013, not at the end**. A new database runs it on empty tables, and an existing one runs it once like any new change set. The roll-back tests that count change sets from the end need no new counts.
3. **File polling had the same gap** (step 4). Its case was made first. A refused file left it CREATED on the dashboard until its incomplete window ran out (24 h on the test source), and the source was asked again on every poll. Now a file refused for good ends the whole case at once: password, wrong type or size at receipt (`ValidationException`), or wrong content type or too big at fetch. The attempt becomes INCOMPLETE_EXPIRED, as at its deadline, so the next poll of the same revision takes nothing. The case is hidden and cancelled. Files already stored are erased by the existing expiry clean-up. Source outages still wait for the window, as before. Only polling: the API keeps its retry (with a password).
   - To tell "too big" from a disk fault, the polling adapter now gives it its own category **`RETRIEVAL_TOO_LARGE`** (still LV-INT-004). This also fixes item #67 of the 0022 failure inventory ("wrong category"). The frontend's generated code file gained that one line.

## Changed tests (said openly)
- `WatchedFolderIntakeServiceRecoveryTest.conflictingPersistedDocumentFailsClosedInsteadOfArchivingAsDuplicate` expected the half-made case to stay **CREATED** after a refusal. Under the owner's rule it is now **CANCELLED**; the assertion was changed to that.
- `LvErrorCodesTest`: the new polling category is in the list that must have a code.

## Proof
Red first: with the two new calls switched off, `WatchedFolderAllOrNothingTest` had 4 of 7 failing ("no case is made: expected 0 but was 1", "expected CANCELLED but was CREATED"). The missing-file, all-good and under-way cases already held.

| Run | Result |
|---|---|
| `mvn -o -pl securedoc-application -am test -Dtest=WatchedFolderAllOrNothingTest` (calls switched off) | 7 run, **4 failed** (the red) |
| Same, with the fix | 7 of 7 |
| `mvn -o -Pdev-build -pl securedoc-domain,securedoc-application,securedoc-infrastructure,securedoc-api clean verify` (throwaway PostgreSQL `qc-0102-pg`, removed after) | domain 87, application 388, infrastructure 198 (2 skipped), API 953 (5 skipped) with **1 error**: `EraseReplayDrillPostgresTest`, app start-up check `MON_CHECK_TIMEOUT` (database not answering in 10 s) while the WebKit suite loaded the laptop |
| `EraseReplayDrillPostgresTest` alone, in the reactor | passes (175 s) |
| Migration tests with PostgreSQL (`*MigrationPostgresTest`, claim lock, stored files) | 30 of 30, the new `RefusedDropCasesMigrationPostgresTest` among them |
| `npm run test:standalone`, `LV_E2E_PORT_BASE=15375` (one frontend run: the generated file changed) | all pass: 10, 243, 19, 233, 110, 380 |
| `node test-tools/intake-paths/intake-paths.mjs` | **ALL PASS**, with the new checks: good + locked file leaves no case (LV-INT-008, `_problem`, dashboard +0); listed file missing leaves no case (LV-INT-032, dashboard +0); refused folder drops dashboard +0; refused polled cases dashboard +0 |
| Targeted intake tests after merging 0097 | 163 of 163 |

- Maven: reactor builds only (`test`, `verify`, `package`), **no `mvn install`** into the shared repository (rule 11). One run without `-am` picked the old application jar from the shared repository (`NoSuchMethodError`); it was re-run in the reactor.
- **Not run (left for the milestone):** the two-server proof. The multi-node claim behaviour is unchanged; the new steps sit inside the claimed drop.
- **Manual drop of `test-pdfs\watched-drops\drop-06-password-refused`:** that needs the owner's running app, so it is listed below. The intake-paths run makes the same drop in its own app and database.

No other product's name or behaviour in code.

## Questions
1. **A closed case stays as a row** (CANCELLED, off the dashboard) where the fallback or the polling path made one first. "No case is created" is fully true only on the normal folder path, where the check comes first. Recommended: keep it this way (the row is the record; reports count it as refused). The other choice is to delete such rows, which touches audit and erasure.
2. **Polling still makes the case before fetching**, then closes it at once on a refusal. Checking every polled file before the case is made would need the fetch and the intake to be reordered. Recommended: leave it as it is, unless the owner wants the cancelled row gone.
3. **Migration 086's placement** (after 013 in the master file) is unusual. Recommended: keep it; the comment in both files says why.

## For the owner
- Manual check on your app after the merge and a rebuild: copy `test-pdfs\watched-drops\drop-06-password-refused` into the watched folder with a new `contract_number` in `manifest.json`. Expect: folder in `_problem`, one line in Reports > intake with LV-INT-008, no case on the dashboard. The old 779001126006 case leaves the dashboard when the app starts (migration 086).
