# Report 0079: P5, Bulk sources, the time window and migration progress

Date: 2026-11-24
Branch: `feature/0079-bulk`, from `c9d17fa` (0073 merged); 0080 is merged into it
Outcome: **DONE** (6 questions, the recommended answer taken in each)

## What was done
1. **IT marks a source as Bulk** (a receiving connection or the watched folder; Tenant IT, audited). Cases it sends from then on are Bulk, whatever priority was sent; cases already here keep theirs. Bulk is last, after Low. A reviewer can make one case Urgent, Normal or Low.
2. **Window per source**, in the entity's time zone: default weeknights 7 PM to 6 AM and all of Saturday and Sunday. Without a time zone a source can't be marked, and if the zone is removed later the window stays shut.
3. **Outside the window** no new Bulk job starts; a running one finishes. Polling and the watched folder leave its files at the source (a person can still start a check). The push API still takes them in; their jobs wait.
4. **Live work first:** Bulk is taken only when no other job of the stage is due. At most **half** of each stage's workers (rounded down), so never the last AI worker. Counted per server, with a place kept while a worker looks.
5. A waiting migration does not count for intake pacing, and outside its window it keeps no workers.
6. **Progress** per source: done, waiting, stopped, pace per open hour (last 7 days), expected finish counting only the window's hours. On the Integrations screen.
7. **Ending a migration** turns its waiting cases into Low, so none waits for a window that is gone; the screen asks first.
8. **Migration 083** (Bulk sources; the source of a Bulk case), with rollback (what is left of Bulk becomes Low).

## Commands run and results
| What | Result |
|---|---|
| `mvn -B -o -fae -Pdev-build clean test` with a throwaway PostgreSQL | 89 + 319 + 137 + 141 + 889, 0 failed, 2 skipped |
| `npm run test:standalone`, once (port base 15375) | 10 + 224 + 19 + 204 + 95 + 174, 0 failed |
| `check-product-names.sh` | clean |

These ran before 0080 was merged in (it changes the AI model list, the benchmark tool and documents). Not run again after it; CI does.

**A simulated night** (`BulkNightPostgresTest`: simulated clock, real queue on PostgreSQL, the real worker numbers; the jobs are 60 ms waits; 3 runs alone and once in the full set, all pass):
- 8 PM: 400 migration jobs; 3 of 6 workers on them, never more.
- "2 AM": a live case of 6 documents finished while the migration went on (the test's limit is 20 job lengths, 1.2 s). Queue length for intake pacing: 0.
- A reviewer made the last of the 400 Urgent: it ran while the others waited.
- "6 AM": Bulk stopped, a Low case still ran, workers went back to 1, files wait at the source.
- Saturday noon: on again.
- 2 AI workers give Bulk 1; 1 gives it none; 5 give 2.

The window's own rules, with the night the clocks change, are in `BulkWindowTest`.

## Not run, and limits
- No load run of the app with real documents at night; no two-server run. **The half is per server.**
- With `pipeline-mode: inline` Bulk cases are processed like any other; only exports as jobs follow it.
- **A stage with one worker never works on Bulk** (half of one is none).
- The queue still sorts all due jobs at each claim (as before); 40,000 waiting at once is for milestone M1.
- The 5 s check of the window: a job may start up to 5 s after it closes. If the database can't be read, nothing of Bulk starts.
- If other sources are still Bulk, a job queued at the very moment its source is unmarked waits for the next open window.

## Tests changed
Six migration roll-back tests (one more change set; the two stand-in tables have the new columns), `CasePriorityTest` and `JdbcCasePriorityPostgresTest` (the source is stored with a case), `WorkersThatGrowTest` (its stub answers the new count), `adminIntegrations.spec.ts`, `words.test.ts`.

## Questions for the owner
1. **One worker:** should Bulk get the only worker of a (non-AI) stage when nothing live waits? Recommended: no, as designed; a migration machine should have more.
2. **A priority sent by a Bulk source is ignored.** Recommended: keep; a reviewer can raise single cases.
3. **The push API outside the window** takes Bulk cases in and lets them wait. Recommended: keep (the API always accepts).
4. **A night of a holiday** counts as a weeknight. Recommended: keep.
5. **Who sees progress:** the Integrations screen (IT). Recommended: the Reports tab with the other stage figures in a later task, as section 9 of the design says.
6. **Compliance appendix:** the details of the new audit action print as "—". Recommended: leave.

No other product's name or behaviour in code.
