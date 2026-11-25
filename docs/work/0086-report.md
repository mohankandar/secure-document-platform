# Report 0086: milestone M1, the full proof on the integrated app

Date: 2026-11-25
Branch: `feature/0086-m1`
Outcome: **DONE, with one known red** (operational continuity test 2, browser step, old screens only). 6 questions.
**Named commit: `d17b4707`.** Every row of the results table ran on it. It holds everything merged up to 0081. 0082 and 0089, merged later, were **not** part of it; CI runs on the merged code.
Run by a subagent. I read its fixes and checked the logs of the backend, frontend, two-server, export, start-path and load rows; the other rows are as it reported them.

## Summary for your own testing

**Works**
- A new database, and databases of 0057 and 0060, in LOCAL and with sign-in.
- Encryption of stored files, the erase log, restore and replay, the customer exit.
- Two servers on one database: one killed, the other finishes, nothing twice. Also when the kill falls inside a delivery to a folder (fixed here).
- All six ways in; the first-entity command; case fields; licence limits.
- Export with retries, pauses, resend and retention, with the old and the staged processing.
- Sign-in and session renewal for all three test users.
- No redacted value in any table, log or export that was searched. The compliance report.
- 4,000 documents on one server: 400 of 400 cases, no failed request, memory at most 2.5 GB.

**Known not to, or not proven**
- The old screens' technical issue page shows IT "Not allowed" (it also asks for the case, which IT may not read).
- The time-left estimate is rough: 25 to 28% too long in one load run, 7 to 12% in the next.
- A server killed while it writes a folder delivery leaves redacted copies under `.staging` in the export location; nothing removes them (by reading).
- A kill in the middle of a delivery to another kind of destination: not tested.
- Scan bridge session files are not encrypted (0060). Typed case fields are sent to no destination yet (0087).
- Real mail servers, a sealed Vault, the GPU benchmarks, 40,000 documents, your own start scripts as such.
- Sign-in mode needs a development Vault. No rolling upgrade: stop every server, schema step, start.
- **Values of purged cases are erased at the first clean-up, and this can't be undone. Say so before your first start if you want to keep them.**

## Results, all on `d17b4707`
| Proof | Result |
|---|---|
| Backend, `mvn -B -Pdev-build clean install`, PostgreSQL tests on | SUCCESS: domain 83, application 377, scan bridge 144, infrastructure 157, API 955; 0 failures, 4 skipped (TLS mail, second-check samples, timing, linked file) |
| `npm run build`; `npm run test:standalone`, 3 runs | built; 3 of 3 with 0 failures: 10, 232, 19, 219, 100, 180 |
| `test:e2e` (old screens) | 2 passed, 6 skipped (they need the continuity row's tool) |
| Two servers, plain | PASS, 35 checks (20 of 20 cases, 51 s after the kill) |
| Two servers, encrypted | PASS, 35 checks (20 of 20 cases, 61 s after the kill) |
| Kill between export folder and record (in both rows above) | the other server finishes, LV-DLV-009, recorded once, files unchanged |
| Export end to end, inline and staged | 8 of 8 and 8 of 8 |
| No-redacted-value check | PASS, 0 matches |
| Compliance report check | PASS, 127 checks |
| Policy estimate check | PASS |
| Intake paths (6 ways in) | PASS |
| First entity (0075) | PASS, 22 checks |
| Start paths from a 0060 database | PASS, 53 checks |
| Start paths from a 0057 database | PASS, 61 checks |
| Sign-in, development Keycloak, three test users | 3 of 3 PASS, no password in any log |
| Operational continuity 1 to 7 (old screens) | **6 PASS, test 2 red** (IT Admin: technical issue 200, case 403; captured) |
| Load, 4,000 documents | PASS, 8 of 8 checks; 1,948 s |
| `Build-LexaVault-All` `.ps1` and `.sh`, tests skipped | PASS |
| Product-name guard | clean (1918 files) |

Earlier passes on other commits are not counted, but one matters: the first load run failed its estimate check (1,381 s, 25 to 28% too long). The second was slower, on a shared machine, and the estimate fitted; **whether the merges between them cost speed is not known.**

## What broke and was fixed
| Commit | What |
|---|---|
| `bf779b2` | **`Build-LexaVault-All` failed since 0061**: its jar check did not allow `platform-fields`. CI does not run that script. |
| `d17b4707` | **Product fix.** A server killed after it wrote a case's export folder and before it recorded the export left the case to IT (LV-DLV-010); also found in 0064, question 6. A repeat now takes the folder as its own when it holds exactly this delivery, byte for byte; anything else is LV-DLV-010, untouched. Tests first. No schema change, setting or folder layout change. |
| `876e44c`, `d17b4707` | Two-server tool: pinned Text worker (since 0067 it never reached its kill), encrypted run, kill in the exact window (a trigger in the throwaway database). Checks only added. |
| `f274720` | Export suite: refused since 0057 (one entity per scenario, two licence slots). Each scenario now starts on a fresh database. No assertion changed. |
| `29e0308` | Policy estimate tool (new SQL parameter) and the continuity tools. |
| `35a1d59` | Continuity test 4: proves the release again (fails when the release is left out). It changes the entity's window while it runs: throwaway databases only. |

## Existing tests changed
- Continuity test 1: names of screen classes, and it waits for the queue. Test 4: "earlier than the preserved hold" could not hold with two equal windows; the test now sets 72 hours and checks both. Test 7: Report Viewer in place of IT Admin, which has no reports.
- Export suite set-up, policy estimate SQL, two-server timings, as above. `ExportJobFixture`: additions only.

## Behaviour changes
- Folder destination: LV-DLV-009 on a recovered delivery; the documents are acknowledged together, not one by one (one by one, a kill in between would leave a folder that can't be compared). A case left half acknowledged by an older version still stops at LV-DLV-010.
- The publishing move never replaces anything. On Linux an empty folder that appears within microseconds could still be replaced; nothing is lost by it.

## Not run
`test-tools/audit-detail` (can't work since 0037), `second-check` and `vision-product` (GPU model). The linked-file test is skipped on this machine (Windows right) and left to CI. A production jar refused to start without licence keys, so "production refuses LOCAL without a key service" rests on unit tests.

## During the runs
In the second pass a script fault started about 990 shells for some minutes. The permission check refused a kill by pattern; the subagent says it then stopped its three own script shells by number and touched nothing else.

## Questions for the owner (recommendation first; none blocks)
1. Estimate: accept as rough, small task later? Yes.
2. Retire `test-tools/audit-detail`? Yes.
3. Old screens: decide when the Case File screens become the default, and drop the old tests then; test 2 stays red until then? Yes.
4. Run `Build-LexaVault-All.sh --skip-tests` in CI? Yes.
5. Export, two-server and continuity tools at milestones only? Yes.
6. A small task to remove `.staging` left by a killed server? Yes.

## PLATFORM-CORE
A testkit helper for a licence with more entity slots in a development build would help end-to-end suites. Nothing patched.

## For the owner
After the merge: stop every server, run `Build-LexaVault-All.ps1`, start as usual.

## Suggested next step
Task 0087.

No other product's name or behaviour in code (the known exception, `SdvDeliveryAdapter`, is untouched).
