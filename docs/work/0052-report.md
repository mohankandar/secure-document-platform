# Report 0052: P2 follow-ups (overlap only for multi-page AI requests; export jobs finish after a switch back)

Date: 2026-11-24
Branch: `feature/0052-p2-followups`, from 0054's branch (PR #3, not merged yet)
Outcome: **DONE** (1 question for the owner). Built by a subagent from a written brief; I reviewed the export path and ran the proofs myself. Lines marked (agent) were run by the subagent only.

## What was done
1. **Overlap.** Page batches share pages only when one AI request may cover several pages. Every adapter that is wired says so for the entity that owns the document; with failover, either one is enough; one that does not say keeps the overlap. The policy setting is unchanged. **Measured (agent), 500 pages, one page per request: 554 AI page requests in 28 batches before, 500 in 25 batches now (9.7% fewer), no page sent twice**, with the real Ollama adapter against a local counting server. Findings equal the whole-document run.
2. **Export after `export-mode` goes back to `inline`.**
   - The export workers (and their maintenance) start only when export jobs are still open, and finish them. New cases go inline.
   - A case with an open export job, or with a job for this run, is never sent inline. The check reads the run, then the jobs, then the case again, so a job finishing meanwhile is seen.
   - A staged case always ends in an export job, so nothing is sent inside the gate's transaction. The restriction of 0049 (`STAGE_JOBS_WAITING`) is gone.
   - Split documents follow their run: a run exported by a job gets split jobs.
   - A job's attempt is one try, whatever the mode (it honours a 429's wait, and a resend is one attempt).

Still by mode, on purpose: starting with `pipeline-mode=staged` needs `export-mode=job`.

## Commands run and results
| Command | Result |
|---|---|
| `mvn -B -o -Pdev-build clean install`, PostgreSQL tests on | BUILD SUCCESS, 0 failures: domain 79, application 304, scan bridge 136, infrastructure 115, API 698 (1 skipped, the timing test) |
| `npm run test:standalone` | 10, 193, 17, 169, 95, 156 passed, 0 failed |
| No-redacted-value, compliance report, export end-to-end, inline / staged | all PASS |
| Product-name guard | clean (1693 files) |

## Tests
New: `ExportBackToInlinePostgresTest` (6; a switch mid-run with one job in every state: all 9 exports go out, the new case goes inline, each accepted exactly once), `AiPagesPerRequestResolutionTest` (6), and additions to existing classes. With the "a job has this case" check disabled on purpose, 3 of the 6 fail (agent).

Existing tests changed: `StagedPipelinePostgresTest` (the part that checked the removed restriction is removed), `PageBatchesEqualWholeDocumentTest` (its AI stand-in is a field; checks unchanged), `ExportJobFixture` (helper), `DerivedRoutingFailureRecordingTest` (a check added).

Failures on the way (agent): a compile error in a new test, and one set-up mistake in the new switch test; both fixed.

## Known limits
- Two servers with different `export-mode` at the same moment are kept apart by the case's processing lease. If a lease is lost while an inline send is in flight, only the idempotency key protects, as before.
- A case still in processing whose job for this run is DONE, but whose run number did not advance, is refused inline and needs a person (fail closed).
- The gate test uses stand-ins for the case data and the destination.

## Commits (hash and message), or "not committed"
- `c76c075` feat(pipeline): page overlap only for multi-page AI requests; queued exports finish after a switch back to inline
- the report commit

## Not done, and why
- A real-model timing run: not asked for.

## Questions for the owner
1. **`SdvDeliveryAdapter`, one condition changed** ("is this one attempt of an export job?"). `CLAUDE.md` says not to add to that class. Without it, a job finishing after the switch would retry inside the adapter. Accept it? Recommended: yes; the line goes with the class in switch-over part 3.

## Suggested next step
Task 0056, once PR #2 and PR #3 are merged.

No other product's name or behaviour in code, except the existing class named above (planned removal: switch-over part 3).
