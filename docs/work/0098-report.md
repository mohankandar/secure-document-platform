# Report 0098: time-left estimate; scan bridge session files (NOT FINISHED, HELD)

Date: 2026-11-26
Branch: `feature/0098-estimate-session-files` (from 999a97a1). Pushed, **no PR**. 0098 is HELD by the owner (starts 2026-11-27).
I started it by mistake before reading the 12:10 hold in QUEUE-B.md, and stopped at a clean point once I read it.
**No other product's name or behaviour in code.**

## Item 1, estimate: cause found, fix written, proof runs NOT done
**Cause.** Two load runs of 1,000 documents (unchanged code, `load-run.mjs 1000 4g 8192 120`):

| Run | At 0% | At ¼ | At ½ | At ¾ |
|---|---|---|---|---|
| baseline 1 (drain 382 s) | +18% | **+32%** | **+34%** | +4% |
| baseline 2 (drain 413 s) | +48% | **+26%** | **+64%** | −4% |

Redact is the slowest stage. While Text and Find run, it does 80 to 115 jobs a minute. Once they finish it does 145 to 155
with the same 5 workers. The estimate takes the present pace for the whole drain, so it is too long until the end.
The machine's CPU was about 45% busy (32 logical cores), so the cause of the slowdown itself is not known (perhaps physical
cores with hyper-threading, or something shared). Not looked into further.

**Fix (written, unit and database tests pass).** `QueueView`: once the stages before a stage are due to be done, the
stage goes on at its best one-minute pace of the last hour, when that is faster and from at least 20 jobs. `JdbcQueueWork`
reads that best minute from finished jobs (all servers together: a finished job no longer says which server ran it).
Offline check against both baselines, with the best pace from each run's own record: within ±11% at every point
(¼: −1%, −1%; ½: −2%, +10%).

**Limits.** A server with no history in the last hour (a fresh start) estimates as before (too long). A best pace measured
when more servers ran makes the estimate too short.

**Not done:** the two proof runs. `load-run.mjs` now takes `LOAD_WARMUP_DOCUMENTS=n` (n documents processed first, as on a
server that has worked before). Planned: `LOAD_WARMUP_DOCUMENTS=500 node test-tools/load-run/load-run.mjs 1000 4g 8192 120`, twice.
The tool also records each stage's pace, the machine's CPU use and when each stage's jobs ended.

## Item 2, scan bridge session files: needs a decision (not started)
The bridge runs on scanner workstations, with no key-service login, no database and no erase log. The scanned PDF beside
`session.json` is plain too, and it is the more sensitive file. Options are in QUEUE-B.md (Session notes, 2026-11-26).
Recommended: give each bridge its own key-service login and a file-based erase log, encrypting both files, as its own task.

## Commands run
| Command | Result |
|---|---|
| `mvn -pl securedoc-application,securedoc-infrastructure,securedoc-api -am verify -Dtest=QueueViewTest,JdbcQueueWorkPostgresTest,PipelineQueueControllerTest` (throwaway PostgreSQL, removed) | 5, 4, 3 passed |
| the two baseline load runs | as above (both FAIL the 20% check, as expected before the fix) |
| the new tests red before the fix | not run separately |

Local Maven repository `C:\dev\lexavault\m2-queue-B` (rule 11).
