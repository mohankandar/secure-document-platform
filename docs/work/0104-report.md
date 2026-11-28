# Report 0104: one case per contract, document versions, never refuse a business resend

Date: 2026-11-27
Branch: `feature/0104-case-versions`, from `origin/feature/lexavault-r1b-tenant-identity` at `7c56856`
Outcome: **IN PROGRESS**. The branch is pushed; there is no PR, because the task is not finished (owner's rule: the merged branch is always testable).

## Done so far
1. **Design:** `docs/design/CASE_VERSIONS.md`.
   - Today's behaviour, measured. A second batch for the same contract becomes a *new* case on the same case-field record. It is refused only for differing case fields (LV-INT-016) or an identical whole batch (LV-INT-017). A case takes documents only while CREATED and has at most 3 export cycles. Every export cycle sends every document.
   - The model, the order of recognition, processing and delivery per batch, and the build order.
   - What DocuSign and eOriginal put in their files (section 7): no public source confirms an envelope or document id inside the PDF's properties. DocuSign's id is visible text on the page. So recognition uses the source's ids, the PDF's own `/ID` and XMP DocumentID, and incremental updates.
2. **Version recognition** (`DocumentVersionRecognition`, domain), in the design's order. It has no text reading and never refuses:
   - same SHA-256 as any file ever received: skipped;
   - the source's id or a manifest's `replaces`: next version (always after the latest version; a `replaces` naming no document of this case is ignored);
   - the PDF's `/ID` or XMP id, or an incremental update: next version;
   - same name only: a new document marked "possible version";
   - otherwise: a new document.
3. **PDF identity reader** (`PdfFileIdentityPort`, adapter `PdfBoxFileIdentityAdapter`): the first part of the trailer `/ID` (kept by a re-save) and the XMP `DocumentID`, both as SHA-256 hashes.
4. **Migration 088** (data plus new tables and columns, within the task's 08x approval):
   - `case_batches`;
   - `case_batch_files`: every received file's fingerprint, kept for the life of the case; a name hash only, never a name;
   - version columns on `documents`;
   - every existing case becomes batch 1, and each document version 1 of its own lineage.
   It is included after 087, not at the end, so the roll-back tests' counts stay.
5. **Batch service and store** (`CaseBatchService`, `CaseBatchStore`, `JdbcCaseBatchStore`):
   - classifies a batch against every version the case ever received, and against the files before it in the same batch;
   - gives each new document its id up front;
   - keeps fingerprints after a purged original.

## Proof so far
| Run | Result |
|---|---|
| `mvn -o -pl securedoc-domain test -Dtest=DocumentVersionRecognitionTest` | 11 of 11 |
| `PdfBoxFileIdentityAdapterTest` | 3 of 3 |
| `CaseBatchServiceTest` | 3 of 3 |
| All `*MigrationPostgresTest` (throwaway PostgreSQL `qc-0104-pg`, port 55404), the new `CaseBatchesMigrationPostgresTest` among them | all pass |
| `JdbcCaseBatchStorePostgresTest` (numbering, versions, fingerprint after the document row is gone) | 1 of 1 |
| `FreshDatabaseStartPostgresTest` (API module, fresh install with 088) | 6 of 6 |

Reactor builds only, no `mvn install` (rule 11).

6. **The case side** (`DocumentPackage`):
   - a delivered case takes its next batch and goes back to SUBMITTED;
   - a case that is processing or in Review makes the batch wait, never refusing it;
   - a rejected or cancelled case takes none;
   - a batch of skipped copies only is not submitted.
   **The open case of a contract** (`CaseBatchStore.openCaseFor`): the newest case on the record that is not rejected, cancelled or erased, never another contract's or entity's. Tests: `DocumentPackageBatchTest` 4 of 4 (domain module 106 of 106), `JdbcCaseBatchStorePostgresTest` 2 of 2.

## Question before the next step (frozen area)
**Step 3 changes V1 document processing**, which is frozen (CLAUDE.md: ask first):
- the staged pipeline and the inline path would process only a batch's new documents;
- the export would send only them;
- the export-cycle limit would no longer apply to batches.
The task asks for exactly this (rules 1, 5 and 7), so I read it as approval. I have **not** changed the pipeline until that is confirmed. Recommended: confirm. Nothing already processed is reprocessed, and the final check (0099) still runs on every new document and version in full.

**Answer (design chat, 2026-11-27 19:00): confirmed as recommended.** The 3-run limit does not apply to batches; it still applies to "Process again" on the same documents.

## Step 3, session 2026-11-27 evening (built)
- **Processing:** no change needed. The staged pipeline, the inline path and `PackageProcessingService` already process only UPLOADED documents. A batch's new documents arrive UPLOADED; the documents of earlier batches stay PROCESSED and are not read, found, redacted or checked again.
- **Export of the batch only:** `PackageDocumentExportService` (direct export, staged export job and the rebuild of a submission) delivers only the current batch's documents when the case has more than one batch. A case of one batch, or one from before batches, delivers every document, as before.
- **Export-cycle limit per batch:** `PackageLifecycle.nextExportCycle(cyclesBeforeBatch)` and `recordSuccessfulExport(..., cyclesBeforeBatch)`. Only the current batch's cycles count against the limit of 3, so a new batch is never refused for the cycles of the batches before it. Re-exports of one batch stay limited. The old methods stay and mean "one batch".
- **Port `CaseBatchScope`** (implemented by `JdbcCaseBatchStore`; `singleBatch()` keeps the old rules): the cycles before the current batch, the current batch's documents, and "current batch delivered" (sets `case_batches.export_cycle` and `state = DELIVERED`). `PackageLifecycleService` takes it (new constructor; the older constructors use `singleBatch()`), wired in `ApplicationServiceConfig`.
- No schema change: the existing columns of migration 088 are used.

**Proof (reactor builds, `mvn -o -pl securedoc-api -am test -Dtest=...`, no `mvn install`; throwaway PostgreSQL `qc-0104-pg` on port 55404, removed afterwards):**

| Run | Result |
|---|---|
| `BatchExportCycleLimitTest` (new: one batch as before; batch 2 after the limit; its own re-exports limited; bad input refused) | 3 of 3 |
| `PackageDocumentExportServiceTest` (new test: a later batch delivers only its own documents) | 3 of 3 |
| `PackageDocumentExportSensitivityTest`, `ExportAuditNoFileNameTest`, `RetentionTimersTest` | 2, 2, 1, all pass |
| `JdbcCaseBatchStorePostgresTest` (new test: the batch scope: no batches, batch 1, batch 2, cycles before, delivered) | 3 of 3 |
| `FreshDatabaseStartPostgresTest` (the Spring context with the new bean, fresh install) | 6 of 6 |
| `ExportJobServiceTest`, `ExportJobSettingsTest`, `PackageLifecycleDashboardRetentionTest`, `ReprocessPackageServiceTest`, `DocumentLifecycleRoleBoundaryContractTest` | all pass (47 tests in that run, 0 failures) |
| `ExportRetentionTest`, `StagedPipelinePostgresTest`, `TriggerProcessingExportModeTest`, `RejectCaseServiceTest`, `CaseBatchServiceTest`, `DocumentPackageBatchTest`, `DocumentVersionRecognitionTest` | 8, 21, 5, 6, 3, 4, 11, all pass |

Left for the milestone: the full suite and the two-server proof. No frontend change.


## Intake building block (same session)
- **Upload into the next batch:** `UploadDocumentCommand.forNextBatch(..., documentId)` stores a file of a delivered case's next batch under the id its batch classification gave it (`Document.createWithId`), and adds it with `DocumentPackage.addBatchDocument`. While the case is processing, in Review or still being filled, nothing is stored (LV-INT-026). An ordinary upload to a delivered case is still refused, as before.
- Proof: `UploadDocumentPdfCheckTest` 10 of 10 (3 new), `IntakeStoppedByLicenceTest` 5 of 5, `UploadCaseLockPostgresTest` 7 of 7, the domain's `Document*` tests all pass.
- **Not wired yet:** no intake calls it. The watched folder, the API and polling, and scan still make a new case for each arrival, so today's behaviour is unchanged on this branch.
- **For the design chat:** the 50-files-per-case limit (task 0038) counts every document of the case, all batches together. A long-lived contract would reach it and its next batch would be refused, against "never refuse a business resend". Recommended: count the limit per batch. I have not changed it.
## Watched folder joins the open case (session 2026-11-28, night)
Merged the newest integration branch first (13 commits, among them 0118's data dictionary: migration 088 now has comments on every new table and column, change set `088-05-comments`).

- **`CaseBatchIntake`** (new, application): the one join every intake calls.
  - It finds the contract's open case (the newest case on its case-field record that is not rejected, cancelled or erased).
  - A case that is delivered (COMPLETED) takes the arrival as its next batch: each file is an exact copy (recorded, not stored), a new document or a new version. The new ones are stored under the ids their classification gave them, their versions set, every file recorded, then the batch is submitted (`SubmitPackageCommand.nextBatch`). Only those documents are processed.
  - A case of exact copies only gets a `NOTHING_NEW` batch: kept in the history, never processed or delivered, and never the "current batch" for export.
  - A case still being filled, processing or in Review is BUSY: nothing changes.
  - **Interrupted join:** the same arrival resumes the open batch (case COMPLETED with stored but unsubmitted documents), and files already recorded in it are not stored again.
- **Watched folder:** with batches on (wired in `ApplicationServiceConfig`), a drop whose contract has an open case joins it instead of making a new case. This also applies to a resend of the same files in a new folder: accepted, recorded, nothing processed, where it used to be LV-INT-017. A busy case makes the drop **wait**: it is given back to the incoming folder untouched and unrecorded (`WatchedFolderSourcePort.release`, fenced like a disposition), and a later scan takes it once the running batch is delivered. The drop's own crash recovery (same drop, same case) is unchanged. A contract with no open case makes a new case as before.
- **Found and fixed on the way:**
  1. A case made after migration 088 by today's intakes had no batch rows. Its next batch would have been compared against nothing, numbered 1 and delivered with every document. `openBatch` now adopts such a case's documents as batch 1 (once, with the cycles that delivered it).
  2. Migration 088 gave existing delivered cases no export cycle, so a next batch would have counted the case's earlier cycles against its own limit. Corrected in `088-04` (not merged anywhere yet).
  3. **Retention during a batch:** a batch joining on day 5 of a 7-day keep period would have been purged on day 7 while in Review. `PackageLifecycle.startNextBatch` stops the delete times when a batch joins; the batch's own delivery sets them again (the owner's "retention runs from the last delivery").
- **50-file limit (my question of 2026-11-27, not answered yet):** taken as recommended. A later batch counts only its own files (documents not yet processed). An ordinary upload counts the whole case, as before.

Proof (reactor builds, `mvn -o -Pdev-build -pl securedoc-api -am test -Dtest=...`, no `mvn install`):

| Run | Result |
|---|---|
| `CaseBatchIntakeTest` (new: the rest of a contract later; same package resent; re-signed = incremental update; e-signing re-save = PDF id; rescan with same name = possible version, not refused; busy in 6 states; interrupted join resumed) | 7 of 7 |
| `WatchedFolderBatchTest` (new: a drop joins a delivered case and only its new file is stored; a drop for a case in Review waits, released, not refused; a resent package accepted, nothing processed) | 3 of 3 |
| `BatchExportCycleLimitTest` (2 new: the keep period stops for a next batch and runs from the last delivery; a purged case takes no batch) | 5 of 5 |
| `WatchedFolderAllOrNothingTest`, `WatchedFolderIntakeServiceRecoveryTest`, `WatchedFolderSchedulerFailureTest`, `CaseBatchServiceTest`, `DocumentPackageBatchTest`, `DocumentVersionRecognitionTest`, `UploadDocumentPdfCheckTest` | 7, 19, 4, 3, 4, 11, 10: all pass |
| On a throwaway PostgreSQL (`qc-0104-pg`, port 55404): `JdbcCaseBatchStorePostgresTest` (new test: a case made without batches adopts its documents as batch 1 once, with its cycles; a batch of copies is never the current batch), every `*MigrationPostgresTest`, `LocalWatchedFolderSourceAdapterTest`, `FreshDatabaseStartPostgresTest`, `IdentityMigrationPostgresTest` | 4, 20, 14, 6, 5: all pass |
| `DataDictionaryPostgresTest` after `scripts\Update-LexaVault-DataDictionary.ps1` (117 tables, 1,124 columns, every one commented) | 3 of 3 (a first run without `-am` read the old infrastructure jar from the shared Maven repository: re-run in the reactor) |

## Left (next session, in this order; design section 8)
1. Watched folder done (above). Left: the manifest's per-file `documentId` / `replaces`; the API and polling (a reused revision or document id becomes a batch or version), then scan (operator's pick). Until an intake opens batches, every new case is a case of one batch and behaves as before.
2. `versions.json` in the cycle folder and the version fields in the destination profile.
3. Split destinations (`DerivedRoutingService`) still route every document of the case; limit them to the batch.
4. "Process again" (`ReprocessPackageService`) on a case of several batches resets every document; limit it to the current batch.
5. Case history, "New version" badge, Reports, user guide, the intake-paths test, the no-redacted-value check, and the owner's four examples as tests.
6. Page-decision reuse.

## Decisions taken (design section 9, as recommended)
1. Mixed key fields go to a person. Until that screen exists they stay refused (LV-INT-016).
2. A batch for a busy case waits for the running batch to be delivered.
3. No file names are kept in the history, only hashes.
4. No export-cycle limit for batches.
5. E-signing files: standard PDF identity only. The owner is asked for one real DocuSign file and one eOriginal file.
6. A reused API revision or document id with other bytes is a new batch or version, not LV-INT-018/019.

No other product's name or behaviour in code (DocuSign and eOriginal are named only in the design document, as sources of files).

## Owner's answers of 2026-11-28 09:45 and 09:55 (session 2026-11-28, day)
Merged the newest integration branch first. **Migration renumbered: 088 became 093** (0110 took 088 for the audit table; 092 is used by 0108 on its branch). Its change sets are now `093-01` to `093-06`; nothing outside throwaway databases ever ran the 088 names.

1. **All files identical: refused, on the existing LV-INT-017 path** (CASE_VERSIONS.md section 3, rule 4 changed).
   - `CaseBatchIntake.join` compares the files first. When every one is an exact copy it answers `ALL_COPIES` and writes nothing: no batch, no file record, also for a busy case (refused at once, not kept waiting).
   - The watched folder sends that to its existing duplicate path: DUPLICATE record with `DUPLICATE_INTAKE` (LV-INT-017) and the case it was received for, the duplicates count, the "already received" place, the `INTAKE_DUPLICATE` audit line. Reports > Daily counts it under "Duplicates" of the watched folder.
   - Some identical and some changed: as designed (copies skipped and recorded, changed ones processed). The earlier "accepted with nothing processed" test is replaced (`WatchedFolderBatchTest`, `CaseBatchIntakeTest`); I changed those two tests on purpose, because the owner changed the rule.
   - No new alert or e-mail.
2. **Decision (a): a case whose files were deleted takes the contract's next batch** (design section 2a).
   - `openCaseFor` no longer leaves out purged cases.
   - `PackageLifecycle.startNextBatch` no longer refuses a purged case: it clears the purge marks (the new batch gets its own keep period from its own delivery) and says whether files had been deleted. The test "a purged case takes no batch" is replaced by its opposite, as decided.
   - The batch is marked `earlier_files_deleted` (new column, commented), and the case shows on the dashboard again (`PackageDashboardVisibilityPort.show`).
   - **Keyed contract fingerprint** (`case_contract_keys`, new table in 093, commented, on delete cascade with the case): `CaseFieldService.contractKeys` makes it from the primary and required fields, each normalised by its rule, with the entity's fingerprint key (platform-core `Fingerprinter`, context per entity, every usable key version). Without a key service: none, never a plain hash. Written by `CreatePackageService` for every new case and by every join.
   - When the arrival's values made a new record (the old ones were erased), the case is found by the fingerprint and pointed at the new record (`DocumentPackage.relinkBusinessRecord`), so it shows and delivers this batch's values.
   - SHA-256 fingerprints already stayed after the files were deleted (`case_batch_files`, `documents.checksum_value`). The month-later tests below prove it.
3. Data dictionary regenerated (`scripts\Update-LexaVault-DataDictionary.ps1 -JarPath ...`, 118 tables, every column commented).

**Proof** (reactor builds, `mvn -o -Pdev-build -pl <module> -am test -Dtest=...`, no `mvn install`; jar for the dictionary from `mvn -Pdev-build -DskipTests package`; throwaway PostgreSQL `qc-0104-pg` on port 55404):

| Run | Result |
|---|---|
| `WatchedFolderBatchTest` (new: resend refused as already received; resend for a case in Review refused at once; **a resend a month after the files and values were deleted is refused and counted as a duplicate**; a changed document a month later joins the same case, takes the new record, keeps its fingerprint, new keep period, back on the dashboard, history says files deleted) | 6 of 6 |
| `CaseBatchIntakeTest` (all copies: nothing written; one copy and one changed file: only the changed one processed) | 8 of 8 |
| `CaseFieldServiceTest` (new: contract key normalised, per entity, holds no value, none without a key service or with a key field missing) | 13 of 13 |
| `BatchExportCycleLimitTest` (replaced: a case whose files were deleted takes its next batch with its own keep period) | 5 of 5 |
| Postgres: `JdbcCaseBatchStorePostgresTest` (new: found by keyed fingerprint, any key version, never another entity's, once per case, gone with the case, earlier-files-deleted flag; changed: a purged case stays open), `JdbcDailyReportPostgresTest` (new: **the month-later resend counted under the watched folder's Duplicates, not refused**), every `*MigrationPostgresTest` | 26 of 26 in that run |
| API module: `FreshDatabaseStartPostgresTest`, `DataDictionaryPostgresTest`, `CaseFieldsAppPostgresTest`, `UploadCaseLockPostgresTest`, `CreatePackage*Test`, `WatchedFolder*Test`, `PackageLifecycle*Test`, `RetentionTimersTest`, `ExportRetentionTest`, `StagedPipelinePostgresTest` | 74 of 74 |

**Not covered yet:** the API and polling refusal of an all-copies batch goes through `JdbcIntakeRefusals` once those intakes are wired (next item); the watched folder is the only intake that joins today.
