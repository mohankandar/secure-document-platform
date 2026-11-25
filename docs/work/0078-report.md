# Report 0078: the queue of each step and its ETA (P6)

Date: 2026-11-25
Branch: `feature/0078-operations-eta`, integration branch merged in (up to 0083, PR #26)
Outcome: **DONE, with one known weakness**: on long loads the ETA is within 7% of the real time; **on short, fast loads (about 2 minutes) it said up to 54% too long.** See "ETA against the real time".

## What was done
- **Admin › Platform operations › Overview, new "Queue" card**: per step (Read the text, Find, Look at pages, Redact, Export) waiting, running, still to come, on hold, workers of the highest, oldest waiting job, time left; the queue's time left on top; "How is this worked out?". Read again every 10 seconds.
- `GET /api/v1/platform-admin/queue` (`TECHNICAL_ADMIN_VIEW`). An IT Admin gets their own entity's numbers only; Platform Operations every entity's added up. Numbers only.
- **Pace**: jobs a step finished in the last 10 minutes (in the last minute, if 20 or more), divided by the time the step had work. **Time**: everything waiting, running and still to come, divided by the pace, never less than the step before. Fewer than 5 finished jobs: no time is said.
- **Found at the merge with 0079 and fixed**: a Bulk job waiting for its night window is READY, so it would have been counted as waiting and as busy time. It is now "On hold" by the claim's own rule; its case is not "still to come". New test.
- `test-tools/load-run` reads the ETA and compares it with the real time. Optional 4th value: the mock AI's time per request.
- No migration, no new dependency. **No other product's name or behaviour in code.**
- Picture: `docs/work/0078-proof/queue.png`.

## ETA against the real time (one server; the time from the last document sent until the last job ended)

| Load | Code | At ¼ | At ½ | At ¾ | Within 20% at ¼ and ½ |
|---|---|---|---|---|---|
| 400 documents, AI 120 ms | first: 10-minute pace | +19% | +53% | +43% | FAIL |
| 400, AI 120 ms | 1-minute pace from 60 jobs | +18% | +12% | +28% | PASS |
| 400, AI 4000 ms | same | no ETA | −3% | −15% | FAIL (no ETA at ¼) |
| 400, AI 120 ms | 1-minute pace from 20 jobs | +18% | +35% | +5% | FAIL |
| 800, AI 4000 ms | same | +6% | −2% | −7% | PASS |
| 400, AI 120 ms | **final, merged** | **+54% (said 151 s, was 98 s)** | **+50% (100 s, 67 s)** | +17% | **FAIL** |
| 800, AI 4000 ms | **final, merged** | **+4% (said 570 s, was 550 s)** | **−4% (352 s, 365 s)** | −5% | **PASS** |

The task asks for a run within 20%: the 800-document run is it. The pace lags while the server speeds up and the AI limit is learned; on a load of about 2 minutes that is most of the run. It erred on the long side in every short run with a fast AI. No ETA is said at the start of the drain (seen at 0% and 10%).

## Commands run and results
Isolated copy of the exact merged tree, nothing installed (rule 11).

| Command | Result |
|---|---|
| `mvn -B -o -Pdev-build verify -fae`, PostgreSQL on (throwaway, removed) | BUILD SUCCESS: domain 89, application 324, scan bridge 137, infrastructure 156, API 918 (3 skipped), 0 failures |
| `load-run.mjs 400 4g 8192 120` | 7 PASS, **1 FAIL (the ETA)** |
| `load-run.mjs 800 4g 8192 4000` | 8 PASS |
| no-redacted-value check (its own script) | PASS, 0 matches |
| product-name guard | clean |
| Frontend (base 15275): auth, vocabulary, ui-framework, oidc-signin, tablet, a11y | 10, 225, 19, 205, 95, 174 passed |

Failures on the way:
- One proof run was thrown away: the merge had conflicts (`client.ts`, `load-run.mjs`) and my script went on with the markers in the files. Nothing broken was committed. The proofs now run on the exact tree that is committed.
- `oidc-signin` could not start once: a dev server of my own stopped run held the port. I checked it served this worktree and stopped only that.
- An earlier run: `SearchableRedactedPagesTest` (the text-layer test) failed once by chance. 5 of 3000 repeats fail, each on "1962" or "7531" inside the PDF's random document `/ID`, never in content. **Not a leak.** The test is not changed.

No existing test's checks were changed. `adminPlatform.spec.ts` got the new card's checks and, for the picture only, waits until a message is gone.

## Limits
- Workers are this server's; pace and counts are the whole installation's (or the entity's).
- A case that will still stop for a person is counted as going on. Jobs on hold are not in the time.
- In the first minutes after a Bulk window opens the pace may be too low: the jobs' wait counts as work, up to the 10-minute window. Bulk jobs run only when there is room, which the time does not know.
- Shown only while documents are processed in steps.

## Questions for the owner
1. **Place**: Admin › Platform operations › Overview, because the new design has no Operations tab and Reports is queue A's. Keep it there?
2. **Short loads**: accept a time that is too long for a queue of a few minutes, or say no time (or "a few minutes") below, for example, 5 minutes? I suggest "a few minutes".
3. `SearchableRedactedPagesTest`: may a task make it ignore the PDF's random document id? (About 1 build in 600 fails by chance.)
