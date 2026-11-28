# One case per contract, documents with versions (task 0104)

Status: design, decided as recommended (open points in section 9). Owner's rules: `docs/work/0104-task.md`.

## 1. Today (measured on `7c56856`)
- A second batch for the same contract is **a new case** on the same case-field record (`document_packages.business_record_id`, one record to many cases). Nothing finds "the open case" for a contract; no such lookup exists.
- It is **refused** in two ways:
  - when its case fields differ from the stored ones (`CaseFieldService` → LV-INT-016);
  - when the whole batch is byte-identical to an earlier one (watched-folder and case fingerprint → LV-INT-017).
- The API refuses a reused source document id with other bytes (LV-INT-019) and a reused source revision (LV-INT-018).
- A case takes documents **only while CREATED** (`DocumentPackage.addDocument`, LV-INT-026). Reprocess runs **all** documents, and a case has at most **3** export cycles (`TenantLifecyclePolicy.MAX_SUCCESSFUL_EXPORT_CYCLES`).
- A document has no version, no lineage and no "replaces". `documents.checksum_value` holds the SHA-256 of the file.
- An export cycle delivers **every** document of the case (`PackageDocumentExportService`).

## 2. The model
- **Case = contract.** One open case per case-field record. "Open" means not REJECTED and not CANCELLED; a case whose files were deleted stays open (section 2a).
- **Batch** (new table `case_batches`): one arrival.
  - Columns: id, tenant, case, number (1, 2, …), source (folder, API, polling, scan, manual), source reference, received at, export cycle, state.
  - A case's first batch is number 1, so existing cases get batch 1 in the migration.
- **Received file** (new table `case_batch_files`): every file of every batch, kept **for the life of the case**, also after the original is purged.
  - Columns: batch, SHA-256 of the file bytes, page count, outcome, document id.
  - Outcome: `NEW_DOCUMENT`, `NEW_VERSION`, `POSSIBLE_VERSION`, `SKIPPED_IDENTICAL`.
  - It holds no file name in clear text, only the name's hash, to compare names (as `WatchedFolderDropReference` does). A name can carry a person's name.
- **Document version**: new columns on `documents`:
  - `lineage_id`: the first version's id; for version 1 it is its own id;
  - `version_number`: 1, 2, …;
  - `replaces_document_id`: the version before;
  - `possible_version_of`: set when rule 3c applies; a person can link it later;
  - `batch_id`;
  - `pdf_identity`: the PDF's first `/ID` and the XMP `DocumentID`, as hashes.
  Old versions are never changed or delivered again.

## 2a. A contract whose files were deleted (owner 2026-11-28, decision (a))
- A case whose files were deleted by retention **stays open for batches**. A document for that contract later (for example a month later, same member number and name) **joins the same case as its next version** (2, 3, 4 and so on). The case history shows each batch and says "files of earlier batches deleted" (`case_batches.earlier_files_deleted`).
- The SHA-256 fingerprints stay for the life of the case record (`case_batch_files`, and `documents.checksum_value`, which the purge keeps), so a month-later resend is still recognised as a copy.
- Once every case of a record is purged, its case-field values are erased (`CaseFieldErasure`). So the contract is also matched by a **keyed fingerprint** of its key case fields (primary and required, each normalised by its own rule), in the new table `case_contract_keys`:
  - made with the entity's fingerprint key, like the other keyed fingerprints; never a plain hash, never a readable value;
  - one row per key version, so a case from before a key rotation is still found;
  - written when a case is made and when a batch joins;
  - deleted with the case (on delete cascade).
- The joining batch's values are stored as a new record, and the case is pointed at it, so it shows and delivers them.
- When the batch joins, the case's purge marks are cleared: the new batch's files get their own keep period from their own delivery. The earlier deletion stays in the audit trail. A case hidden from the dashboard after its purge shows again.
- Without a key service no fingerprint is kept. Such a contract makes a new case once its values are gone: never a wrong case. Cases made before this change have no fingerprint until their next batch joins by record.

## 3. A new arrival
1. Resolve the case fields exactly as today. **All key fields** (primary and required identifying fields) must match one record. When only some match (today's LV-INT-016), the batch goes **to a person** ("Which case?"), never merged and never refused. It waits as an intake to decide, with the same code shown.
2. Find the open case for that record:
   - none, or only rejected or cancelled cases: a **new case**, as today;
   - an open case: the arrival **joins it as the next batch**;
   - the record is new because the earlier values were erased: the open case with the same contract fingerprint (section 2a).
3. For each file, in this order (no reading of the document's text):
   1. **Identical:** its SHA-256 equals any file ever received for the case (all `case_batch_files`). It is `SKIPPED_IDENTICAL`: recorded, not stored, not processed, not counted, not delivered.
   2. **Named by the source:** the API's `externalSourceDocumentId`, a manifest's `documentId` or `replaces`, or the operator's pick at a scan. This gives a `NEW_VERSION` of that lineage, or a `NEW_DOCUMENT` when it names nothing known.
   3. **The file's own identity:** the same PDF `/ID` (first part) or the same XMP `DocumentID` as a lineage's latest version, or an **incremental update** (the new file begins with all the bytes of an earlier version). Each gives a `NEW_VERSION`.
   4. **Still unclear:** a file with the same name as a document but other bytes and no id. It becomes a `NEW_DOCUMENT` marked `possible_version_of`. It never blocks or refuses.
   5. Otherwise: a `NEW_DOCUMENT`.
4. **A batch whose files are all exact copies is refused** back to the sender, as before: LV-INT-017, "already received, no changes" (owner 2026-11-28 09:45; it replaces "recorded, nothing else happens"). It takes the existing duplicate path, so it shows where duplicates already show: the sender's answer ("Already received"), the refusal record (kind DUPLICATE, `JdbcIntakeRefusals`), Reports > Daily "Duplicates" per source system, the folder check's "already received" count, and the audit line. Nothing is recorded in the case and no batch is opened, also when the case is busy. No new alert or e-mail (owner 09:55).
   A batch with some identical and some changed files: the identical ones are skipped and recorded, the changed ones are processed as the next version (rule 3).
5. **Pages:** counted once per version processed. Skipped files count 0.

## 4. Processing a batch
- Only the batch's new documents and versions are processed: text, find, vision, redact and the final check (0099), all in full.
- The case's state machine runs per batch. A batch arriving while the case is processing or waiting in Review **waits as queued** until that batch's review and delivery are done, then starts. It is never refused or merged into the running batch. Usually that is minutes; nothing times out.
- Arriving after delivery (COMPLETED): the case goes back to SUBMITTED for the new batch only. This is like reprocess, but only the batch's documents.
- **Reuse of page decisions** (owner rule 4) is not in the first build (section 8). A page of a new version is reused only when all of these hold:
  - it renders to the same image as the same page of the version before;
  - the entity's redaction settings are the same as at that review;
  - the model versions are the same as at that review.
  Otherwise the version is reviewed in full. The final check always runs on the whole file.

## 5. Delivery
- Each batch is one export cycle. It delivers **only the batch's new documents and versions**, each with its version number and the version it follows.
- Downstream keeps every version (owner): a new version is an addition, never an overwrite.
- The limit of 3 export cycles becomes **no limit on batches**. The limit stays for re-exports of the same batch.
- The local export folder:
  - writes `cycle-N/<documentId>/<file>` as today;
  - adds `versions.json` in the cycle folder, holding per document its lineage, version number and replaced document id. It holds ids only, no names.
  - The destination profile carries the same fields.

## 6. What a person sees
- **The case:** a history of batches. For each batch: the date, the source, and per file processed as vN or skipped as an exact copy.
- **The dashboard and Reports:** a "New version" badge when a version arrives after a delivery, and the counts of batches, versions and skipped files. An optional notification goes through the existing notifications.
- **Review:** "possible new version of …" with a "Link as new version" action.
- **Retention:** an open case's period runs from its **last** delivery.

## 7. E-signing systems: what the files carry (checked 2026-11-27)
- **DocuSign:** by default it stamps the **Envelope ID as visible text** on every page ("Docusign Envelope ID: …"). The sender can switch that off. Documented metadata (envelope custom fields, document fields) lives in DocuSign's API, not in a property of the PDF. I found no source saying the envelope id is stored in the PDF's Info or XMP. Reading the visible stamp would mean reading the text, which rule 3 excludes. So for DocuSign files LexaVault relies on 3.2 (the source's id), then 3.3 (PDF `/ID`, incremental update: a re-signed PDF is usually an incremental update), then 3.4.
  - [DocuSign user guide: Envelope ID placement](https://connect.ncdot.gov/business/Transit/Documents/DocuSign%20User%20Guide.pdf)
  - [DocuSign developers: managing metadata](https://www.docusign.com/blog/developers/dsdev-managing-metadata)
  - [Community: disabling envelope id stamping](https://community.docusign.com/esignature-api-63/how-to-disable-envelope-id-stamping-in-document-21402)
- **eOriginal (Wolters Kluwer):** the vault seals the authoritative copy (tamper seal). Adobe Acrobat Sign hands it a document key through the API and keeps a watermarked copy. I found no public source naming a document-id field inside the PDF.
  - [Adobe: eOriginal vaulting](https://helpx.adobe.com/ee/sign/using/archive-with-eoriginal-vault.html)
  - [Proof: eOriginal vaulting for PDFs](https://dev.proof.com/docs/eoriginal-vaulting-for-pdfs)
  Same order of rules as for DocuSign.
- **Follow-up:** check real sample files from both systems when the owner has them (section 9, question 5).

## 8. Order of build
1. Version recognition as a pure domain rule (`DocumentVersionRecognition`), with tests for every rule in 3 (identical, source id, `/ID`, XMP, incremental update, same name, new).
2. Migration 088: `case_batches`, `case_batch_files`, the new `documents` columns, batch 1 for existing cases.
3. Watched folder joins the open case, then API and polling (a new source revision joins), then scan (operator pick).
4. Batch processing and delivery of only the batch, `versions.json`, no cycle limit for batches.
5. Case history, badge, Reports, user guide, the intake-paths test.
6. Page-decision reuse.

## 9. Open points, decided as recommended
1. **Mixed key fields** go to a person as an intake to decide, not a refusal. Until that screen exists (step 5), they stay refused as today (LV-INT-016) and are listed in the report. *Recommended*, because that screen is UI work.
2. **A batch for a busy case waits** until the running batch is delivered. It is not processed in parallel, because one case has one state machine. *Recommended.*
3. **File names are not stored in the history**, only their hash. The case screen shows the document's existing name. *Recommended* (privacy).
4. **Export-cycle limit:** none for batches; the 3-cycle limit stays for re-exports of one batch. *Recommended.*
5. **E-signing sample files:** none public with confirmed metadata. Recognition uses only the PDF's standard identity and incremental updates. *Recommended:* the owner sends one real DocuSign and one eOriginal file to confirm.
6. **The API's `sourceRevision` reused with other bytes** (today LV-INT-018) becomes a new batch of the same case. A reused `externalSourceDocumentId` with other bytes (today LV-INT-019) becomes a new version of that document. *Recommended*, as rule "never refuse a business resend".
