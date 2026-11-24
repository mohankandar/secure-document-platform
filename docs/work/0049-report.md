# Report 0049: Staged pipeline P2, part 2: page batches, fairness, AI requests at once, clean-up

Date: 2026-11-23
Branch: `feature/0049-p2-part2` (worked in the scratch worktree `C:\dev\lexavault\lv-queue-b`, detached at `2dda31a`; the branch was fast-forwarded)
Outcome: **DONE** (4 questions for the owner, below)

## What was done
**0. The unreliable test: the product was wrong, and is fixed.** The case panel drew itself as soon as the case arrived, before the export state did. An exporting case showed "Redacting · LexaVault is redacting" for a moment, then jumped to "Exporting". In that moment the only "Technical details" was the hidden one inside "Case details", and the test's click stayed on it. The panel now keeps its spinner until the export state is known (a failed export answer does not hold it). The failing test is unchanged. A new test holds the export answer back; it fails without the fix.

**1. Page batches.**
- Text: ranges without overlap (a page's text does not depend on its neighbours). Find: ranges sharing the overlap pages. A document up to the batch size is one job, as in 0048.
- Batch size and overlap are sensitive-data policy settings, defaults 20 and 2 (migration **050**, API fields `pageBatchSize` and `pageBatchOverlap`; a request without them keeps the stored value). No screen for them yet.
- Each batch keeps its result as a file in the case's own processing storage, with an artifact record (`EXTRACTED_TEXT_BATCH`, `FINDINGS_BATCH`). A findings batch holds places only, no value or text. Both are deleted when the document is gathered, and with the case. They cannot be downloaded.
- The last batch gathers the document **inside its own fenced transaction**, under the case's gate lock, so exactly one batch gathers. A missing, changed or incomplete result refuses the document (fail closed).
- Merging: overlapping places of one kind become one finding (union of places, higher confidence). A finding with no place cannot be compared by place, so for a shared page the batch with the most of a kind counts.
- If the policy's kinds change between two batches, the whole document is searched once with the current policy.

**2. Fairness.** Within a stage and priority, each server takes the next entity in turn, oldest first within it. Export jobs too. No clock, no table change.

**3. AI requests at once.** `securedoc.ai.requests-at-once` (`SECUREDOC_AI_REQUESTS_AT_ONCE`), default 2, staged mode only. It limits the Find workers, and the requests this LexaVault server sends to one AI server. The PowerShell start scripts give it to Ollama (`OLLAMA_NUM_PARALLEL`) when they start Ollama. If Ollama already runs they leave it alone and say if its setting differs. `Start-LexaVault-Local.sh` never starts Ollama; it says so too.

**4. Back to inline.** With `pipeline-mode=inline`, the stage workers start only while staged jobs are open, and finish them. New cases go inline. This needs `export-mode=job` (a staged case ends in export jobs); without it the open jobs wait and the log says `STAGE_JOBS_WAITING`.

**5. Clean-up sweep**, in the lifecycle maintenance, at most once an hour per server. `securedoc.lifecycle.unrecorded-files-keep-hours`, default 24 (1 to 8760, otherwise LexaVault refuses to start). A file is removed only at LexaVault's own path shape, when its document exists in this database in that case and entity, and no record that is not DELETED has its key or id. Originals are never looked at. One audit record per run and entity with files removed (`UNRECORDED_FILES_CLEANED`), counts only. Files stored before migration 028 are not swept.

**Two faults of 0048 found and fixed on the way:**
- A technical retry of a staged case kept the case run's number, so its finished jobs stopped the new ones from being queued and the case hung. Starting a case now removes that run's old stage jobs.
- A server stopping between a job's save and the case's gate left the case behind for good. A sweep now runs missed gates again (the gate is idempotent).

## Commands run and results
Throwaway containers only, all removed (`lexavault-0049-pg` for the tests; the scripts' own).

| Command | Result |
|---|---|
| `mvn -B -o clean install`, PostgreSQL tests on | BUILD SUCCESS, 0 failures |
| export spec, `--repeat-each 20` | 480 of 480 passed (before the fix: 2 of 5 repeats failed) |
| `npm run test:standalone`, 3 runs | 0 failures in all three: `test:auth` 10, `test:vocabulary` 193, `test:ui-framework` 17, `test:oidc-signin` 167, `test:tablet` 95, `test:a11y` 156 |
| `Invoke-TwoServerCheck.ps1` (extended) | **PASS, 24 checks** |
| `Invoke-NoRedactedValueCheck.ps1`, inline / staged | PASS, 0 matches / PASS, 0 matches |
| `Invoke-ComplianceReportCheck.ps1`, inline / staged | PASS, 116 checks / same |
| `scripts/Test-LexaVault-ExportE2E.ps1`, inline / staged | PASS / PASS |
| `Measure-PipelineTiming.ps1` | below |
| `bash scripts/ci/check-product-names.sh` | clean (1673 files) |
| Start scripts | parse check, and the Ollama helper run with a stand-in for `Start-Process`. Ollama was not started or restarted. |

**Two servers, batches on.** 20 cases, 61 documents, one of 200 pages (10 Text and 11 Find batches). Server A killed holding 2 Text, 2 Find and 2 Redact claims. B finished all 20 cases 246 s later; 0 of 6 late saves counted. The 200-page document has 63 findings for 3 values on 21 pages, including the shared pages, none doubled. Then B was killed while a Text step had stored its file and could not save its record: 1 file left. Restarted, B finished the case, and its sweep removed that file and 0 of 184 recorded files. The 200-page document's batches were not traced to a killed worker separately.

**Timing** (`public-26`, 60 pages, real local model, submit to Review):

| Run (A-B-B-A) | Total |
|---|---|
| inline (cold model, not comparable) | 76.6 s |
| staged | 34.7 s |
| staged | 36.0 s |
| inline | 46.6 s |

- The four runs found 11, 12, 11 and 12 findings (inline 11 and 12, staged 12 and 11). **The real model's answers vary from run to run in both modes**, so "same findings" is proven with a fixed AI stand-in, not with the real model.
- The staged total is one server with 2 Text and 2 Find workers. The audit's Text time reads 0 s in staged mode, because the document's start and end are both written when it is gathered.
- As in 0048, the package stops at Review, so Redact and Export are not timed.

## Tests: counts per module, and any failures with their output
0 failures. Domain 79, application 304 (+3), scan bridge 136, infrastructure 115 (+5), API 631 (+16; 1 skipped, the timing test). Install tests run as part of the build.

- `PageBatchesEqualWholeDocumentTest`: a 500-page PDF read and searched in batches, in random order, gives the same stored text, findings, boxes, confidence and audit as the whole-document run. A finding that needs the page before is kept, and is missed when the overlap is 0.
- `StagedPipelinePostgresTest`: 500 pages over 4 workers, two last batches at the same moment (5 rounds), a lost claim, a failed batch, a technical retry with other batch settings, a switch to inline mid-run, a missed gate.
- `StageJobsMigrationPostgresTest`: migration 050 and its rollback; A queues 200, then B queues 5: all 5 of B's start within the first 10 claims, for Text and for Export; priority before turns.
- `AiServerRequestLimitTest`, `PageBatchSettingsSaveTest`, `UnrecordedFileSweepPostgresTest`.

**Existing tests I changed:** Liquibase rollback counts, +1 each because 050 is one more change set (`DocumentStoreNames…`, `JdbcClaimLock…`, `SearchableRedactedPages…`, `TenantRedactionKinds…`, `StageJobsMigration…`). `StageJobsMigrationPostgresTest` checks the old identity after the rollback with plain SQL, because the job store now writes the page range. `RetentionPurgeSchedulerCleanupTest` passes the new sweep (a stand-in). `StagedPipelinePostgresTest`'s stand-in steps also do batches. What they check is unchanged.

My own first versions of three new tests were wrong and were corrected: one page-break check passed for the wrong reason (the rules found the value alone), one claim-lost test's update was rolled back with the step, and one sweep test used a wrong age.

## Files changed
58 files, +2986 / -130 (before this report). New: `PageBatch*` (application and domain), `050-page-batches.xml`, `AiServerRequestLimit`, `UnrecordedFileSweep*`, `scripts/LexaVault-OllamaParallel.ps1`, tests. Changed most: `StagedProcessingService`, `TextExtractionService`, `SensitiveDataDetectionService` (their parts were separated so that a batch and the whole document run the same code; no rule changed), `JdbcPipelineJobRepository`, `StageJobWorkers`, the three Ollama start scripts, `CaseDrawer.tsx`.

## Commits (hash and message), or "not committed"
- `fadb8c1` step 0, the case panel waits for the export state
- `7e67e1a` step 1, page batches; migration 050
- `671bac2` step 2, turns between entities
- `6e3aa24` step 3, AI requests at once
- `644aac6` step 4, back to inline; missed gates
- `2548ca5` step 5, clean-up sweep
- `b6a1456` two-server check extended
- the report commit

## Not done, and why
- No admin screen for batch size and overlap (the task asks for settings; the API has them).
- The AI limit is per LexaVault server. Two servers sharing one AI server can send 2 × 2 requests. The learned limit is P3.
- A Find batch holds its database connection while the AI answers, as 0048's steps do. Sizing is P3.

## Questions for the owner
1. **Overlap with one page per AI request.** Ollama's default is one page per request, so the model does not see the shared pages together. The overlap then repeats pages (about 10% more AI work) and gains nothing. It helps when several pages go in one request. Keep 2 as the default, or use 0 where the request size is 1?
2. **Batch settings screen:** add it to Admin with P3, or earlier?
3. **`export-mode` back to inline too:** open staged jobs wait until it is `job` again. Keep it so? (The alternative sends inside the gate's transaction.)
4. **Reprocessing a staged case after the switch:** a case that was ever staged stays staged, also when it is reprocessed later in inline mode. Keep it so?

## For the owner
- Your running Ollama has `OLLAMA_NUM_PARALLEL` not set (automatic). To match: set it to 2 and restart Ollama yourself.
- Migration 050 runs at the next start: two columns with defaults on `tenant_redaction_policies`.
- Protected containers and the Phase 1 regression containers were not touched. The script's export proof pictures in `docs/work/0037-proof` were overwritten by the test run and restored.

## Suggested next step
Answer the questions; then P2b (document summary and case instructions), or the continuous queue.

No other product's name or behaviour in code.

## Decisions (2026-11-24, design chat, decided as recommended under the owner's continuous-work rule LV-19; for his later review)
1. **Overlap with one page per AI request:** the overlap applies **only when one AI request covers more than one page**; with one page per request the effective overlap is 0 (no repeated AI work). The policy setting stays (default 2) for multi-page requests. Task 0052.
2. **Batch settings screen:** with P3 (Admin settings for workers), not earlier.
3. **`export-mode` back to inline:** same rule as the pipeline (0048 answer 1): the export workers **finish the export jobs already queued**; only new cases export inline. Task 0052.
4. **A case that was ever staged stays staged when reprocessed:** keep it (one case, one flow).
- **Merge approved** into `feature/lexavault-r1b-tenant-identity` (see the queue's "Approved merges").
