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

## Left (next session, in this order; design section 8)
1. Wire the watched folder, then the API and polling (a reused revision or document id becomes a batch or version), then scan (operator's pick). Until an intake opens batches, every new case is a case of one batch and behaves as before.
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
