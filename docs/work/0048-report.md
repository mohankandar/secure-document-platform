# Report 0048: Staged pipeline P2, part 1: Text, Find and Redact as per-document jobs

Date: 2026-11-23
Branch: `feature/0048-p2-staged-jobs` (worked in the scratch worktree `C:\dev\lexavault\lv-queue-b`, detached at `90493ad`, because your app runs from the main checkout; the branch was fast-forwarded to the last commit)
Outcome: **DONE** (2 questions for the owner, below)

## What was done
1. **Stages as jobs.** With `securedoc.processing.pipeline-mode=staged`, a submitted case gets one `TEXT` job per document in `pipeline_jobs`. A TEXT job reads the text and queues the document's `FIND` job. FIND and REDACT end in the case's gate. Workers per stage (`StageJobWorkers`, 2 per stage per server by default) claim jobs exactly as the export workers do: claim lock, `SKIP LOCKED`, queue number order. Each step calls the existing use case with the same failure handling as inline (incident, document FAILED with a safe reason). No step's logic changed.
2. **Gates.** After each job, the case's gate runs, one at a time per case (an advisory lock per case). It reads only the documents' saved states:
   - While any document is still being read or searched, nothing happens.
   - Otherwise, a failed document fails the case, and a finding that needs a person sends it to Review. Both use inline's own gate code (`completePackageProcessingIfReady`).
   - Otherwise every document waiting for redaction gets its `REDACT` job.
   - When all are redacted, the case is Ready for export and its EXPORT jobs are queued (P1, unchanged).
   - After a review, the reviewer's continue queues the REDACT jobs. Review stays a person.
3. **Claim number, #342 fixed.** A step and its result are **one database transaction**. The last write is the job's result, saved only while the claim number is still current (the P1 fenced update). If it is not current, the whole transaction rolls back: the document state, findings, artifact records, audit rows and outbox events of that step all disappear. The files the step stored are deleted too (`StagedStepScope`; the storage adapter records what a step stores). Claims are renewed every third of the lease.
4. **A server stops mid-job.** Its transaction never commits, so nothing is half-saved. The claim runs out, the job goes back to the queue, and that counts as an attempt. After 3 attempts the job goes to NEEDS_IT and the case stops with `LV-PRC-008` (the existing stop, now also for a case in review whose Redact job died).
5. **Audit.** Every job status change is audited (`PIPELINE_JOB_QUEUED`, `PIPELINE_JOB_STATUS_CHANGED`) with ids, stage, claim number and codes only. No content.
6. **Rollout setting.** `pipeline-mode`: `inline` (**default**, today's flow) or `staged`. Staged needs `export-mode=job`; any other combination is refused at start (no silent fallback). Further settings: `stage-jobs.claim-lease-ms` (120000), `max-attempts` (3), `text-/find-/redact-workers` (2 each), `poll-interval-ms` (1000).
7. **Existing cases at upgrade: cases in the middle of an inline run finish inline; switching to staged picks up only new work.** A case is staged when it is submitted with staged on, or when it already has stage jobs. Every other case, at any status, goes on inline, and the inline recovery scans still recover it. The inline recovery scans skip cases that have stage jobs, so a staged case is never rewound or re-run by them. Proved in `onlyNewCasesAndStagedCasesAreStaged`, and the two-server run uses only new cases.
8. **Screen.** The case panel says "Now at **Text** / **Find** / **Review** / **Redact** / **Exporting**" in the existing chip style. It is read from the case's and its documents' statuses, so it is the same in both modes.
9. **Migration 049** (`049-pipeline-stage-jobs.xml`):
   - The job identity now includes the document for TEXT/FIND/REDACT jobs, and a page range. Export jobs keep their identity.
   - New nullable columns `page_from` / `page_to`, unused in part 1. Task 0049's page batches can use them with no further table change.
   - A stage job must name its document.
   - New index for "does this case have stage jobs".
   - Rollback removes stage jobs and restores the old identity.
   - The P1 expired-claim sweep is now limited to export jobs; stage jobs have their own sweep.

**Differences from inline that you will see (staged only):**
- When one document of a case needs review, no document is redacted before the review. Inline redacts the others right away. The end result is the same. This is the task's gate.
- The intermediate document states inside one step (for example EXTRACTING) are never visible, because a step saves once.

## Commands run and results
All in the scratch worktree. Throwaway containers only (`lexavault-0048-pg` for the PostgreSQL tests, removed; the scripts' own `-2s-pg`, `-t-pg`, `-0015-pg`, `-0017-pg`, `-0035-pg`, each removed by its script).

| Command | Result |
|---|---|
| `mvn -B -o clean install` in `backend/`, PostgreSQL tests on | BUILD SUCCESS, 0 failures |
| `npm run test:standalone` | all pass: `test:auth` 10, `test:vocabulary` 193, `test:ui-framework` 17, `test:oidc-signin` 166, `test:tablet` 95, `test:a11y` 156 (see the flaky test below) |
| `test-tools/two-servers/Invoke-TwoServerCheck.ps1` (new) | **PASS, 17 of 17 checks** (details below) |
| `Invoke-NoRedactedValueCheck.ps1`, inline / staged | PASS, 0 matches / PASS, 0 matches |
| `Invoke-ComplianceReportCheck.ps1`, inline / staged | PASS, 116 checks, 0 failed / same |
| `scripts/Test-LexaVault-ExportE2E.ps1`, inline / staged | PASS, 8 passed / PASS, 8 passed |
| `test-tools/two-servers/Measure-PipelineTiming.ps1` (new) | numbers below |
| `bash scripts/ci/check-product-names.sh` | clean (1657 files) |

The staged runs of the three existing scripts were started with `SECUREDOC_PROCESSING_PIPELINE_MODE=staged` (and `EXPORT_MODE=job` where the script does not set it). Their server logs show `STAGE_JOB_WORKERS_STARTED` (the inline runs: `STAGE_JOB_WORKERS_OFF`).

## The two-server proof
- **Servers.** One throwaway PostgreSQL (`lexavault-0048-2s-pg`) and the install steps. Two servers from the same jar, A on 18048 and B on 18049, with node ids `node-a` and `node-b`, `pipeline-mode=staged`, `export-mode=job`, claim lease 15 s.
- **Storage.** LexaVault refuses LOCAL_FILESYSTEM storage with two active servers, so after A's first start the entity's processing storage was switched to SHARED_FILESYSTEM. Both servers use that one folder, as a file share. Exports go to one local export folder.
- **AI.** The task 0015 fake-value mock, answering after 2.5 s.
- **Cases.** 20 cases of 3 documents each (typed 2 pages, scanned 2 pages that need OCR, typed 2 pages), 60 documents. Created half through A and half through B, one after the other, and submitted.
- **The kill.** The script read the database until A held a RUNNING Text, Find and Redact job at the same time. It noted those jobs and their claim numbers, then killed A's process (`Stop-Process -Force`: no shutdown, no clean-up). At that moment A held **6 claims: 2 TEXT, 2 FIND, 2 REDACT** (claim number 1 each).
- **What happened next.** A's claims ran out after 15 s, and B took the jobs (claim number 2) and finished them. **All 20 cases were exported 70 s after the kill.**
- **Late saves.** For each of A's 6 jobs, the script ran the workers' own fenced save with A's old claim number: **0 of 6 changed a row**.
- **Checked in the database and the audit:**
  - every case COMPLETED;
  - per document exactly one TEXT, one FIND and one REDACT job, all DONE (60 each);
  - A's 6 jobs DONE with one attempt and one claim that ran out each;
  - 180 findings, 0 duplicated places;
  - 60 redacted copies and 60 stored text reads for 60 documents;
  - per document exactly one `DOCUMENT_EXTRACTION_COMPLETED`, one `DOCUMENT_DETECTION_COMPLETED` and one `DOCUMENT_REDACTION_VERIFIED` (the 0044 final check). A killed run's audit rows are rolled back with its step;
  - 180 RUNNING-to-DONE job audits for 180 jobs;
  - one EXPORT job per case, DONE, and no case delivered twice;
  - the fake values are in no table (pg_dump: 0 matches).

## Timing (benchmark `public-26`, 60 pages, one case, one server, real local model qwen3.5:9b, export-mode job)
| Run (warm model, A-B-B-A order) | Submit to the Review gate | Text | Find |
|---|---|---|---|
| inline | 47.8 s | 14.1 s | 33.5 s |
| staged | 49.6 s | 14.3 s | 33.8 s |
| staged | 48.0 s | 14.4 s | 32.9 s |
| inline | 47.9 s | 14.4 s | 33.2 s |

- The package stops at Review in both modes: some findings cannot be confirmed automatically, even with the auto-redact threshold at 0. So Redact and Export are not in these numbers. The 0047 timing of this package's redaction (unchanged code) still applies.
- One 60-page document is one job per stage, so staged is not faster here. Page batches (part 2) are what spread a large document over the workers.
- A first pair of runs with a cold model (inline 82.2 s, staged 46.7 s) is not counted: the inline run included loading the model.

## Tests: counts per module, and any failures with their output
Backend, PostgreSQL tests on, 0 failures:

| Module | Tests | Change |
|---|---|---|
| domain | 79 | |
| application | 301 | |
| scan bridge | 136 | |
| infrastructure | 110 | +3 `StageJobsMigrationPostgresTest` |
| API | 615 (1 skipped, the timing test, as designed) | +7 `StagedPipelinePostgresTest` |

What the new tests prove (real PostgreSQL, real job queue, real JPA repositories, real inline gate and LV-PRC-008 stop; only the step use cases are stand-ins making the same document state changes):
- **Gates:** each document runs Text, Find and Redact. REDACT is queued only when the last document is found, and the export only after the last redaction. The final check is audited once per document, and every job change is audited. A second gate call adds no job.
- **Review:** a finding for a person sends the case to Review, nothing is redacted before it, and the reviewer's continue queues every document.
- **Failure:** a failing step fails its document (incident and code on the job) and then the case, as inline.
- **Fencing:** a worker whose claim was taken by another server mid-step saves nothing. The document stays UPLOADED, no next job is queued, the newer claim is untouched, the stored file is deleted, and a stale claim number never saves.
- **Claims that run out:** three of them make 3 attempts, then NEEDS_IT with LV-PRC-008, and the case is FAILED with its audit.
- **Stopped case:** its remaining jobs are STOPPED and do nothing.
- **Upgrade:** a case in the middle of an inline run stays inline; a new case and a staged case are staged.
- **Migration 049:** one job per document and page range, export identity unchanged, each sweep releases only its own stages, rollback works.

**`inline` mode: every existing test passes.** Existing tests I changed (CLAUDE.md asks me to say so):
- Liquibase rollback counts, +1 each because 049 is one more change set: `DocumentStoreNamesMigrationPostgresTest` (3 → 4, twice), `JdbcClaimLockAndQueueOrderPostgresTest` (4 → 5), `TenantRedactionKindsMigrationPostgresTest` (2 → 3, twice), `SearchableRedactedPagesSettingPostgresTest` (1 → 2, three times). What they check is unchanged.
- `Start-FakeValueOllama.ps1` takes an optional `-DelayMs`. The default 0 answers at once, as before.

**One frontend test failed in some full runs.** `e2e-oidc/exports.spec.ts:225` ("a destination code that is not safe is not shown") timed out clicking "Technical details" in the case panel ("element is not visible").
- Full `test:oidc-signin` runs: with my case panel change, it failed 3 times and passed 2 times (one of the failures was with the new chip's render switched off). Without my case panel change, it passed 2 of 2.
- The spec alone passed 23 of 23.
- The final full `test:standalone` run (the numbers above) passed 166 of 166.

It looks like an existing timing-dependent test that my change makes more likely to fail, though I could not show how. I did not change the test.

## Files changed
- Domain: `AuditAction.java` (2 actions).
- Application:
  - new: `StagedProcessingService.java`, `PipelineModeRouter.java`, `PipelineMode.java`, `StagedProcessingSettings.java`, `StagedStepScope.java`, `TransactionPort.java`;
  - changed: `PipelineJobRepository.java` (stage-scoped release, `hasStageJobs`, `lockCaseGate`), `ExportJobService.java` (export sweep limited to export jobs), `PackageProcessingRecoveryService.java` (`exhaustStagedCase`), `TriggerPackageProcessingService.java` (four helpers made package-visible, no logic change).
- Infrastructure: new `049-pipeline-stage-jobs.xml`; `db.changelog-master.xml`, `JdbcPipelineJobRepository.java`, `JdbcPackageProcessingLeaseAdapter.java` (recovery skips staged cases), `LocalFileStorageAdapter.java` (records stored files for a staged step); tests as above.
- API: new `StageJobWorkers.java`; `ApplicationServiceConfig.java` (settings, transaction port, staged service, router as the trigger), `application.yml`; tests as above.
- Frontend: `CaseDrawer.tsx`, `caseRules.ts`, `caseRules.test.ts`, `words.ts`, `admin/auditRules.ts`.
- Test tools: new `test-tools/two-servers/` (`Invoke-TwoServerCheck.ps1`, `MakeCasePdf.java`, `Measure-PipelineTiming.ps1`); `no-redacted-values/Start-FakeValueOllama.ps1`.
- Docs: this report, `docs/work/0048-proof/` (the export suite's pictures, inline run).

## Commits (hash and message), or "not committed"
- `82a19dd` feat(processing): task 0048 step 1, Text/Find/Redact as per-document jobs (staged pipeline P2 part 1); migration 049
- `a1388a4` feat(ui): task 0048 step 2, the case panel says which processing step a case is at; two-server and timing tools
- the report commit (`docs(work): task 0048 report`)

Not pushed, not merged.

## Not done, and why
- Page batches, fairness between entities and the Ollama "2 at once" setting: part 2 (task 0049), as the task says. The job's page range columns are there, unused.
- Redact and Export timing on the 60-page package: the case stops at Review in both modes (see Timing).

## Questions for the owner
1. **Switching back from staged to inline.** A staged case stays staged (it is never re-run inline), but with `inline` the stage workers are off, so its open jobs wait until staged is switched on again. Nothing is lost, but nothing moves either. Options: keep it so and only switch back with an empty stage queue, or have the stage workers also run in inline mode while stage jobs are open.
2. **A file left by a server that dies mid-step.** A refused save deletes the files its step stored. But if a server dies after a step stored its file (the text read, which holds page text, or the redacted copy) and before the step's transaction commits, the file stays in storage with no record pointing to it. Today's inline flow has the same gap, with a shorter window. Add a sweep for files with no record, in part 2?

## For the owner
- Nothing changes until `pipeline-mode` is set to `staged` (with `export-mode=job`). With staged on, the case panel shows "Now at Find" and so on.
- Each staged worker holds a database connection for the length of its step. With the defaults (6 stage workers, 8 export workers) that fits the default pool of 10 connections only because export workers hold connections briefly. Sizing workers to the machine is P3.
- A `testcontainers-ryuk-…` container (Testcontainers' clean-up helper) was created at 19:39 during these runs. I could not tell which step started it, so I left it. It is not a protected container and can be removed.
- Protected containers and the Phase 1 regression containers were not touched.

## Suggested next step
Answer questions 1 and 2. Then task 0049 (P2 part 2: page batches, fairness between entities, the AI's requests at once).

No other product's name or behaviour in code.
