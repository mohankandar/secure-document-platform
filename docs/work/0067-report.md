# Report 0067: P3, workers grow with the queue, learned AI limit, intake pacing, safety stop

Date: 2026-11-24
Branch: `feature/0067-p3-workers`; the integration branch (with 0058, 0069 and 0071) is merged into it; after 0071 the targeted backend tests (13 + 55 + 3 + 275) and `test:vocabulary` (215) were run again, 0 failed
Outcome: **DONE** (40,000 documents wait for milestone M1; 6 questions, the recommended answer taken in each)

## What was done
1. **Workers** (`ProcessingCapacity`, `ElasticWorkers`, `WorkerLimits`): every 5 s each stage gets a number from its queue, lowest 1 up to its highest; up at once, down by half of what is too many. A worker over the number finishes its job first. **Highest measured at each start**: Text cores − 1, Redact half the cores, both together within free memory / 512 MB, Find and Vision by the AI servers, Export 4 per destination. IT's number wins: Admin (all servers), else this server's settings.
2. **A bound the design does not name:** a running step holds a database connection, so all stages together stay 10 below the connection pool. The pool is now 40 (was Hikari's 10; `SECUREDOC_DB_CONNECTIONS`).
3. **AI limit learned per AI server** (`LearnedServerLimit`): starts at 1 (or where it was), one more while requests wait, one less on 429/503 (Retry-After honoured) or when the server only queues; tried again after an hour or a restart. Stored in `ai_server_limits`. `SECUREDOC_AI_REQUESTS_AT_ONCE` is now IT's maximum.
4. **Intake pacing:** from 200 waiting jobs the watched folder and interval polling check 2, then 4, then 8 times less often and take that much less. Daily polling keeps its time and takes less. A check a person asks for is not paced.
5. **Safety stop:** below 10% free on any document storage disk (or of the database size, if IT sets it) creating a case and sending a document answer 429 `LV-INT-027` with Retry-After (300 s) until everything is above 15%. Watched folder and polling leave files at the source. Submitting and status still work. In `docs/api/EXTERNAL_INGESTION_V1.md`; `LV-INT-027` is no longer reserved.
6. **Admin, Platform operations, Workers:** now / lowest / highest / waiting per step, "AI server 1: 5 at once (learned)", maximums, audited (`PROCESSING_MAXIMUMS_CHANGED`). The numbers are those of the server that answers.
7. **Migration 081** (two tables, numbers only, with rollback).

## Commands run and results
| What | Result |
|---|---|
| `mvn -B -o -fae -Pdev-build test -Dtest=...` (targeted, with a throwaway PostgreSQL; migration 081 applied) | 13 + 55 + 3 + 275, 0 failed, before the last change to how connections are shared |
| `mvn ... package -Dtest=WorkersThatGrowTest` after that change | 7, 0 failed |
| `npm run test:standalone`, once | 10 + 212 + 19 + 187 + 95 + 168, 0 failed |
| `check-product-names.sh` | clean |
| `test-tools/load-run/load-run.mjs 400` | 7 of 7 checks, before that change |
| `test-tools/load-run/load-run.mjs 4000` | 7 of 7 checks, on the final backend code |

The mock AI server that refuses above a number, and the one that only queues (1, 2, 3, 4, 6 or 8 at once), are simulated in simulated time: the limit settles exactly there, and drops after the mock is lowered.

**Load runs** (one server, 32 cores, other sessions running, mock AI that works on 5 at once and queues the rest, `-Xmx4g`):

| | 400 | 4,000 |
|---|---|---|
| Cases exported | 40 of 40 | 400 of 400 |
| Time | 240 s | 1,887 s |
| Text workers, most of highest | 6 of 6 | 11 of 11 |
| Find / Redact, most of highest | 6 of 6 / 6 of 6 | 6 of 6 / 5 of 5 |
| All stages at the end | 1 | 1 |
| Memory, app and OCR (limit 8,192 MB) | 2,471 MB | 2,799 MB |
| Learned AI limit | 5 | **6** |

- **The 6 is one above what the mock takes.** It is the number tried last, and it stayed: Find was bounded at 6 workers, so all 6 places were rarely taken and the number was not judged in that run. The check in the script allows one above, so its PASS does not show settling; the simulated tests do.
- **Redact was the slowest** in the 4,000 run (5 workers by the connection bound; half the cores would be 16). The timings are not a performance figure.
- An OCR process took up to 112 MB (average at one moment); the app's own part is in its heap. 512 MB per worker is cautious.
- Vision was not under load. Two 4,000 runs were started and stopped by me before the final one (a worse sharing of connections, then a jar that was not the new one).

**Not run:** the full proof set, the two-server run (claims unchanged), real AI servers.

## Tests changed
- `LvErrorCodeRegistryTest`: two reserved codes left, not three (`LV-INT-027` is in use).
- Six migration tests (045 to 049, 080): they count change sets back from the end, so each count is one higher (081). The first CI run was red for this (8 failures at `8e365d1`); my targeted set had left them out. All 133 infrastructure tests pass now.
- `platformRules.test.ts`, `helpTip.spec.ts`, `screens.spec.ts`, `adminPlatform.spec.ts`: the new section.
- `WatchedFolderSchedulerFailureTest`: its mock answers the new gate.

## Questions for the owner
1. **Database connections:** 40 per server (two servers stay below PostgreSQL's default 100). Recommended: keep, and set it higher with `max_connections` on big machines, or a later task so that a step does not hold a connection while it works.
2. **Database space:** PostgreSQL does not tell its free disk. Recommended: IT sets `SECUREDOC_INTAKE_SPACE_DATABASE_SIZE_GB`; **unset (the default) means database space is not checked**.
3. **Who sets maximums:** they hold for every entity. Recommended: Platform Operations only (Tenant IT can read them by API).
4. **Learned limit and Find's bound:** when Find has no more workers than the limit, one step too many can stay. Recommended: leave it; at most one request queues at the AI server.
5. **What cannot be measured** (a storage folder that does not answer) neither starts nor ends the stop. Recommended: as is; the storage check already reports the folder.
6. **Compliance appendix:** the detail of `PROCESSING_MAXIMUMS_CHANGED` prints as "—", because the list of allowed details is a reviewed set. Recommended: leave it.

No other product's name or behaviour in code.
