# 0121 report: staged pipeline only, cases take turns, measured speed

Status: IN PROGRESS (part 1). Branch `feature/0121-staged-only`, from the integration head `b531508` (0119 merged, so every part may proceed).

## Addendum: the intermittent CI failure (inline-export test, LV-PRC-011)
**The race, and it was not inline-only.** The case gate (`StagedProcessingService.decide`) read the case's documents, then, in a separate statement, its jobs (PostgreSQL READ COMMITTED). A REDACT step saves its document (REDACTING → PROCESSED) and its job (DONE) in one transaction, and it does not take the gate lock. When two documents of one case finished on two Redact workers at once, the gate of the first could read the second document still REDACTING, then read that document's REDACT job as DONE with nothing waiting for a person: "nothing planned", and a good case was stopped with LV-PRC-011 and never got its export job (the test's 60 s wait). Export mode played no part, so **staged + job export could hit it too.**
**Fixed already by 0119** (`b9a9c2a`, merged): the gate reads the jobs first. A job read as DONE implies its document's new state is visible to the reads after it. 0119 added a deterministic regression test (the other worker's step is saved exactly at the gate's read). This task adds the repeated staged + job-export run the addendum asks for (50 runs; see Proof).

## Part 1: what is deleted (listed before deleting, owner rule)
Settings: `securedoc.processing.pipeline-mode` and `export-mode` are kept as settings but accept only `staged` / `job` (empty means those); `inline` is refused at start with a message naming the setting and this task. (The smaller change: start scripts and old configuration that name `staged`/`job` keep working.)

Code removed:
- `PipelineMode.INLINE`, `ExportMode.INLINE`, `ExportJobScheduler.INLINE`.
- `PipelineModeRouter` (every trigger goes to the staged pipeline; `StagedProcessingService` is the processing use case).
- In `TriggerPackageProcessingService`: the inline run (`execute`, `executeFullProcessing`, `executeContinueProcessing`, `executeContinueAfterReview`, `processDocument`, `attemptRedaction*`) and the inline send at the end of processing (export inline, the 0052 "left to its job" check). Kept, because the staged pipeline calls them: the document-intelligence snapshot, `hasDetectedFindings`, the case completion that queues the export job, `markDocumentFailedIfNeeded`. `recoverInterruptedDocuments` moves to the staged pipeline (see below).
- Inline-only branches: `StagedProcessingService.handles` (the inline fallback), `StageJobWorkers` / `ExportJobWorkers` "off / finishing" modes, `ExportJobQueue` / `ExportJobSettings.jobMode` refusals, the inline send in `DerivedRoutingService` / `DerivedDestinationDeliveryAdapter` / `DestinationDeliveryConfig`.
- Tests that only test inline: `ExportBackToInlinePostgresTest`, `TriggerProcessingExportModeTest`, the inline cases in `StagedPipelinePostgresTest` (including `withExportModeInlineAStagedCaseStillEndsInOneExportJobAndNothingIsSentFromItsGate`), and inline settings in the other tests named in Proof.
- Inline mentions in `application.yml`, the start scripts, the owner round, `test-tools` (export e2e, two servers), the install and sizing guides.

Kept on purpose: a case that an older version was running inline when it stopped (PROCESSING, no stage jobs) is not lost: its interrupted documents are rewound to the kept original, as inline recovery did, and it is planned as jobs.

## Part 1: done
- `PipelineMode` and `ExportMode` keep only STAGED and JOB. Both settings may be empty (the default now) or `staged` / `job`; `inline` stops the start with "securedoc.processing.pipeline-mode=inline was removed (task 0121): leave it empty or set staged" (and the same for export-mode). The start scripts never set the mode, so they need no change; `application.yml` defaults are empty.
- `PipelineModeRouter` deleted; `StagedProcessingService` is the processing use case (every trigger plans the case as jobs).
- `TriggerPackageProcessingService` keeps only what the staged pipeline calls (591 → about 300 lines); the inline run and the inline export are gone. `ExportJobScheduler.INLINE`, `jobMode`, `jobHasCase`, `splitsGoInline` and the workers' "off / finishing" modes are gone.
- **A case an older version was running inline** (PROCESSING, no stage jobs) is rewound (its half-done documents start again from the kept original, as inline recovery did) and finishes as jobs: new test `aCaseLeftByAnInlineRunIsRewoundAndFinishesAsJobs`.
- Docs: sizing guide, What's new, the platform operations and tenant IT guides, `Measure-PipelineTiming.ps1` (staged only). The frontend's "only while processed in steps" texts stay: they show only with an older server that says `staged: false`.

Tests deleted (inline only): `ExportBackToInlinePostgresTest`, `TriggerProcessingExportModeTest`, and in `StagedPipelinePostgresTest` `onlyNewCasesAndStagedCasesAreStaged`, `switchedBackToInlineMidRun…`, `withExportModeInline…` (the intermittent one), `inInlineModeWithNoStagedJobsNoWorkerStarts`.
Tests moved to the staged path (same checks): `UnlocatedFindingCreatesIncidentTest` → `StagedPipelinePostgresTest.aRedactionStoppedByAFindingWithoutAPlaceIsASupportIncidentWithItsCode`; `DerivedRoutingPostExportFailureTest` now runs `MasterExportCompletion` (what the EXPORT job runs).
Tests changed because the workers are always on now (said here, not hidden): `BulkNightPostgresTest.neverTheLastAiWorker…` (Find's places now include the AI server's: 2 for one unmeasured server, so Bulk gets 1, not 2); `NotificationsAppPostgresTest` (its hand-made "can't reach" alert is marked "just checked", so the always-running export probe does not clear it mid-test); `DerivedRoutingFailureRecordingTest` (its inline part removed).

## Part 2: cases take turns (done)
- Claim order (`JdbcPipelineJobRepository.FAIR_ORDER`): priority, the entity turn (0049), **then the case turn inside the entity** (the case after the one this server served last in that entity, round and round by case id), then queue order within the case. Only for the processing stages (Text, Find, Vision, Redact); export jobs keep queue order between cases (a case is sent in the order it became ready).
- Test `casesTakeTurnsInsideAnEntityAndUrgentAndOtherEntitiesStillComeFirst`: a case with six batches queued first and a small case after it: the small case is taken in the first two claims, not seventh; Urgent first; another entity gets its turn at once. One older assertion changed: two Urgent cases of one entity no longer go strictly by arrival (`aCasesJobsHaveItsPriority…`: now either of the two first).
- EXPLAIN on a 10,000-job queue (200 cases, 3 entities; test `theClaimWithCaseTurnsStaysQuickOnATenThousandJobQueue`): seq scan of the stage's due jobs + top-N heapsort, **6.7 ms** for the candidate query; **41 ms** per whole claim. No index added (the plan was the same shape before; the per-row case look-up is over 3 entities).

## Addendum proof
`StagedPipelinePostgresTest.fiftyRunsOfTwoDocumentsFinishingTogetherNeverStopAGoodCase`: staged + job export, three cases of two documents, two workers per stage, 50 runs: every case one export job, none stopped, LV-PRC-011 never recorded. Green.

## Proof run so far (rule 11: `-Dmaven.repo.local=C:\dev\lexavault\m2-queue-B`, nothing installed; throwaway PostgreSQL `lv-0108-pg`)
- `mvn -Pdev-build verify -pl securedoc-api -am`: domain 91, application 410, infrastructure 218 (2 skipped), API 989 (5 skipped), **1 failure**: `BulkNightPostgresTest.aMigrationNight` ("Bulk stopped at 6 AM", 31 against 30). Alone: 2 passes in 3. It also failed once on the 0108 branch, which has no 0121 change: **an existing timing flake**, not caused here, not changed here. For the design chat: a small fix task (the test counts jobs taken around a clock step).
- Every test named above re-run green on its own (StagedPipelinePostgresTest 24 with the 50-run test, JdbcCasePriorityPostgresTest 9, JdbcClaimLockAndQueueOrderPostgresTest 12, StageJobsMigrationPostgresTest 6, NotificationsAppPostgresTest 16, BulkNightPostgresTest's changed test).
- No frontend change, so no frontend run.
