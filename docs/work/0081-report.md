# Report 0081: Reports, the three tabs

Date: 2026-11-25
Branch: `feature/0081-reports-build` (from 9c13cd9, PR #30; nothing newer when it was opened)
Outcome: **DONE**, not yet seen in the real app (see "Not proven"). Built by a subagent from a written brief. I read the SQL, the access rules, the recorder, the migration, the CSV, the Daily tab and the adjusted tests, made two corrections (below), and ran every command in the table myself.

## What was done
- **Overview / Daily intake and delivery / Operations**, with one period, Source system and Level filter, and Export CSV of the tab shown (audited; every cell neutralised). Month, Quarter and the Outcome and channel filters are gone, as designed. The old addresses still work.
- **Daily**: the whole design, top to bottom, with Urgent counts. **Operations**: the old lower part plus 0078's queue, of the reader's entity only; the filters do not change the queue, and it says so. "Intake channels" moved to Daily.
- Read by Report Viewer and Business Administrator; Operations details as before (Business Administrator). Always the reader's own entity.
- **Migration 072** `intake_refusals` (entity, source key, LV code, kind, time), because refusals of push, scan, manual upload and polling were recorded nowhere. No content, file name or person. Recorded after the answer is made, in its own transaction; it cannot change the answer. Rollback included. No dependency, no new error code.
- **No other product's name or behaviour in code.** Pictures: `docs/work/0081-proof/` (test data; the weekday in the picture is the day it was taken).

## Rules decided where the design leaves room
| | Rule |
|---|---|
| Exported | Cases with an export in the period, each once, re-exports too. Overview keeps "first delivered", so the two can differ |
| As of now, whatever the period | Not exported yet, stopped, export failed, waiting to retry. Refused and rejected: in the period |
| Needs attention | Refused + stopped + export failed. Rejected is in the reasons, not in the tile |
| Level of a case | Its highest. Refusals and duplicates have no case: "—" with a note when a level is chosen |
| Usual for a weekday | Lowest to highest of the same weekday in the 4 weeks before, from the entity's first case on; single days only |
| Quiet source | At least 3 of 4 same weekdays with 4 or more receipts; silent over 2 times the usual longest at this time, and over 30 minutes. Never disabled sources, closed Bulk windows or manual upload |
| Refused | Every refused try counts (no identity is stored), so one case sent 3 times is 3, though the tile says cases. Not counted: sign-in (LV-AUT), 401, 404, 405, 429, 5xx. Polling: once per package and server until it restarts |
| Duplicates | Push and scan of a case already accepted; watched folder. A polled source listing a case again is not counted |
| Links | Only for people who may open the target |

## Commands run and results
Isolated copy of the committed tree, nothing installed (rule 11).

| Command | Result |
|---|---|
| `mvn -B -o -Pdev-build verify -fae`, PostgreSQL on (throwaway, removed) | BUILD SUCCESS: domain 83, application 377, scan bridge 144, infrastructure 157, API 941 (3 skipped), 0 failures |
| no-redacted-value check; product-name guard | PASS, 0 matches; clean (1917 files) |
| Frontend (base 15275): auth, vocabulary, ui-framework, oidc-signin, tablet, a11y | 10, 232, 19, 219, 100, 180 passed |

`JdbcDailyReportPostgresTest` proves the Daily numbers on one seeded day (quiet source, rejected case, paused destination, split destination, refusals, duplicates, waiting states, by hand, levels, Urgent, exported today but received before, another entity, filters).

**Corrected in my review:** the level counts read every document the entity ever had (now only the period's); wrong mock data in the a11y harness. **Failures met while building (fixed, by the subagent):** an initialiser order bug, test seeds, label selectors, a CSV download test that closed the browser, and one sign-in test that met the new request (`adminSignIn.spec.ts`).

## Existing tests changed
- Rollback counts +1 for 072 in 6 migration tests.
- `words.test.ts`: Reports may name levels (the design puts them there).
- `reports.spec.ts`: opens the Operations tab first; the filter is Source system.
- `adminSignIn.spec.ts`: its list of thin mock answers has one more address.
- Mock answers added. No check weakened.

## Not proven
- The screens in the real app: the browser tests use mocked answers. The answers themselves are tested in the API and against PostgreSQL.
- A very large entity: "last received" reads all its cases' sources on each load of Daily.
- `/?code=` filters the cases Cases already lists, so it can show fewer than the count.

## Questions for the owner
1. A Business Administrator or Report Viewer alone can't open cases, so "each count opens the cases" is a plain number for them. Let Business Administrators open cases from Reports?
2. Overview and Operations say "Delivered" (design: unchanged), Daily says "Exported". Change them too? (recommended: yes)
3. How long to keep `intake_refusals`? No limit now. (recommended: 13 months)
4. Show "tries" instead of "cases" for Refused? (recommended: yes)
