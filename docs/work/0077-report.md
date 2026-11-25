# Report 0077: Every intake path, tested end to end

Date: 2026-11-24
Branch: `feature/0077-intake-paths`, integration branch merged in (0076 and 0072)
Outcome: **DONE**. 51 of 51 checks pass. The suite found 2 product bugs; both are fixed.

## What was done
- **`test-tools/intake-paths/`**: one command (`Run-IntakePaths.cmd`), about 5 minutes, plain results, README for the owner. It starts its own throwaway database, app, Scan Bridge (simulator), source system and mock AI, and removes them. No new dependency, no migration.

| Check | Manual | Folder, trigger | Folder, no trigger | Polling | Scanner (simulated) | API |
|---|---|---|---|---|---|---|
| Accepted, exported, right entity | yes | yes | waits, then yes | yes, receipt confirmed | yes | yes |
| Wrong type | INT-007, 008 | INT-007, 008 | INT-007 | INT-007, 008 | SCN-004 | INT-007, 008 |
| Too big (bytes, pages) | INT-004, 028 | INT-028 | INT-028 | INT-004, 028 | SCN-005, INT-028 | INT-004, 028 |
| Password-protected | INT-008 | INT-008 | INT-008 | INT-008 | INT-008 | none INT-008, wrong INT-036, in the address INT-037, right accepted and exported |
| Unknown entity | AUT-003 | LIC-003, waits | LIC-003, waits | AUT-003 | AUT-003 | AUT-003 |
| Server full (0067) | INT-027 | INT-027, waits | INT-027, waits | INT-027 | INT-027 | INT-027 |

(All codes start with `LV-`.) Also: no case is made while full; the waiting folder is taken once there is space; no redacted value or password anywhere (the search first proves it finds planted values).

## Bugs found and fixed
1. **Scan Bridge: cancelling a scan left the scanner busy** (`LV-SCN-006`). The id from the request was compared as an object, not by value. Same for submit, until something asked for the current scan. Fixed in `ScanSessionService`, new test.
2. **A watched folder stopped by the licence was logged without an LV code.** The line now has it (`LV-LIC-003`). New test.

Both made the suite fail on the code before the fix.

## Commands run and results
Isolated copy, nothing installed (rule 11), on the merged head 8bd770e.

| Command | Result |
|---|---|
| `mvn -B -o -Pdev-build verify`, PostgreSQL on (throwaway, removed) | BUILD SUCCESS: domain 85, application 319, scan bridge 137, infrastructure 134, API 855 (1 skipped), 0 failures |
| `node test-tools/intake-paths/intake-paths.mjs` | 51 PASS, 0 FAIL |
| no-redacted-value check (its own script) | PASS, 0 matches |
| product-name guard | clean |

No frontend change, so no frontend run. One build before that failed on a wrong import in my new test (fixed). No existing test changed.

## What the suite does not show
- It runs in LOCAL mode (header sign-in). An unknown entity with real sign-in is covered by the Phase 1 checks, not here.
- "Names the entity": the case answer of the API has no entity field. Checked: stored under the entity, its audit records too, and another entity can't open it.
- The scanner is the simulator; polling is started by "Run now", not by its schedule. The AI is a mock.
- "Server full" is forced by setting the stop at 98% free disk. On a disk with more than 98% free those checks would fail.

## Questions for the owner (nothing changed)
1. **The watched folder has no size limit in bytes** (only 500 pages per file, 50 files), while a manual upload stops at 50 MB. Add one? I recommend yes.
2. **Simulator:** a text file named `.pdf` is moved to the problem folder with `LV-SCN-004` in the log, but the waiting scan is not told, so the operator must cancel it. Simulator only; leave it?
3. The polling log line for a licence stop has no LV code either (same gap as bug 2, not reached by the suite). Add it next time that file is touched?

## Suggested next step
The owner runs `Run-IntakePaths.cmd`; then 0078.

No other product's name or behaviour in code.
