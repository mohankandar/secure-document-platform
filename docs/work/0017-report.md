# Report 0017: compliance report PDF (backend)

Date: 2026-11-19
Branch: `feature/compliance-report` (from `feature/lexavault-r1b-tenant-identity` @ `66b6c4f`)
Outcome: DONE

## What was done

**Step 0. 0016 merged.**
- On `feature/audit-detail`: committed the owner's answers in `0016-report.md` (`1cdcee4`).
- Merged into `feature/lexavault-r1b-tenant-identity`, no fast-forward, standard message. Merge commit: **`66b6c4f`**.
- Merged branch: backend `mvn -o clean install` passed, **502** tests, 0 failures (domain 51, application 158, scan bridge 47, infrastructure 8, API 238). Frontend `npm run test:standalone`: **242** pass (auth 10, vocabulary 91, ui-framework 17, oidc-signin 69, tablet 55).
- Created `feature/compliance-report` from `66b6c4f`. Nothing pushed.

**A1. Case ID on Document, Finding and Artifact records.**
- `AuditService` has new `recordSuccess`, `recordFailure` and `recordDenied` overloads that take the case (`PackageId`). When the case passed is null, the case ID is worked out from the record as before.
- **31 of 33** call sites now pass it. Each one uses a package the code already held (the command's validated `packageId`, the loaded document's `packageId()`, the loaded package, or the vault entry). No query was added.
  - Detection 2, extraction 3, document processing 2, trigger processing 1, redaction 3, single-document export 2, package export 1, upload 1, external intake 1.
  - Manual fallback 2, manual fallback preview 1, finding review 3, focused review 3, review page preview 1, vault download 5.
  - `FindingReviewService`: its existing check already loaded the review task, so the check now returns the task's package.
- **Still without it (2):** both are in `VaultService.execute(DownloadArtifactCommand)`, refused before the vault entry is loaded: "extracted text access denied" and "no vault entry".

**A2. Machine clients are named.**
- Format: **`Push API: <connection name>`** and **`Scanner: <connection name>`**. The name is the integration connection's configured `name`, never its client principal, key or secret.
- `ExternalIntakeService` binds the name right after it resolves the enabled push or scanner source. It uses the same request-bound holder as 0016, so the request filter clears it at the end of the request.
- The system actor still gets no name. A blank name binds nothing, and control characters are dropped.
- Not covered: polling connections. They run on a scheduler thread, and the task names only push and scanner clients (question 7).

**A3. CSV formula cells.**
- New `api/csv/CsvCells`: a cell that starts with `=`, `+`, `-` or `@`, after any leading spaces, gets a leading `'`.
- Used in the audit CSV export (every cell) and in the Reports CSV export (every value cell). JSON is unchanged.
- Tests: `CsvFormulaCellTest` (2) and `ReportsCsvFormulaCellTest` (1). Result: pass.

**A4. A policy save that changes nothing writes no audit record**, and saves nothing.
- Sensitive-data policy: compared with the stored policy (types as a set, threshold within 1e-9, review mode, last-four switch).
- Lifecycle policy: the 4 values.
- The first save, when no policy is stored, is always recorded (`previous=none`).
- Tests: `PolicySaveWithoutChangeTest` (5). The end-to-end run also passed both checks. Result: pass.

**A5. Extracted text is deleted with the original: confirmed.**
- New test `finalPurgeDeletesExtractedTextAndOcrTextTogetherWithTheOriginal` runs through `PackageLifecycleService`.
- Before the due time: the original, the `EXTRACTED_TEXT` and the `OCR_TEXT` artifacts are all kept.
- At the due time, one purge marks all three `DELETED` and deletes all three stored files, and `ORIGINAL_ARTIFACT_PURGED` is recorded. Result: pass. No stop was needed.

**A6. Capabilities.**
- `COMPLIANCE_REPORT_SUMMARY`: `BUSINESS_ADMIN` only. `COMPLIANCE_REPORT_FULL`: `TENANT_IT_ADMIN` only. Platform Operations gets neither.
- The frontend's e2e copy of the policy (`e2e-oidc/policyCapabilities.ts`) gained the same two entries. Its contract test (`test:auth`) reads the Java policy and failed until they were added. No UI change.

**B1. `POST /api/v1/compliance-reports`** (`ComplianceReportController`).
- The body is `period`, `from`, `to`, `timeZone` and `includeFullAuditTrail`.
  - `includeFullAuditTrail` is required; a missing value gives 400.
  - Any other field, such as a tenant id, is ignored. The end-to-end run sent another entity's id and got its own entity's report.
- The entity is always the request's tenant.
- Each edition needs its own capability; anything else gives 403 and is recorded as `COMPLIANCE_REPORT_CREATED` with outcome DENIED and detail `edition=…, refused=ROLE`.
- The response is `application/pdf` with `Content-Disposition: attachment; filename="compliance-report-<entity-slug>-<from>-<to>.pdf"`, `Cache-Control: no-store` and `X-Report-File-SHA256`. That header is added to the CORS exposed headers.
- Period rules are in `CompliancePeriod`:
  - last complete month, quarter or year in the requested zone;
  - `CUSTOM` must end before today in that zone and be 1 to 366 days long;
  - `from`/`to` are only accepted with `CUSTOM`;
  - the time zone must be an IANA region name; offsets and short ids are refused;
  - `from` and `to` are inclusive (to the end of `to`).
- The endpoint has no outer transaction, so a refusal or failure record is never rolled back with the request.

**B2. Sources and definitions.** All are scoped to the signed-in entity and the period `[start of from, start of the day after to)` in the requested zone.

*Section 1, redaction policy:*
- **At the end of the period:**
  - the newest `SENSITIVE_DATA_POLICY_CHANGED` success before the period end;
  - if there is none, the `previous=` values of the first change after the period (defaults if `previous=none`);
  - if there is no change at all, the current policy (defaults if none is stored).
  - "(unchanged since before this period)" is shown when there is no change in the period.
- **Changes:** one line per changed value, from the new values and `previous=[…]`.
  - Wording: "Preset: Balanced → Light", "Sent to a person: Only uncertain findings → Every case", "Redacted without review at: 80% → 70%", "Credit score turned on/off", "Labeled last-four SSN treated as sensitive: No → Yes".
  - Records without earlier values: "Policy saved for the first time" (when `previous=none`), or "Changed (this record does not hold the earlier values)" (records written before 0016).
- **Type names:** ported once from `words.ts` into `SensitiveTypeNames` ("Social Security Number", "Date of birth", …).

*Section 2, keeping and deleting:*
- **Settings at the end of the period:** from `LIFECYCLE_POLICY_CHANGED` the same way. Before the first recorded save the policy was the default (only a recorded save changes it).
- **Originals due for deletion:** `package_lifecycles` rows whose due time falls in the period. The due time is `original_delete_at` for a delivered case, or `technical_hold_until` for a case never delivered.
- **Deleted on time:** of those, the ones with an `ORIGINAL_ARTIFACT_PURGED` success for the case no later than due + 24 h.
- **Late or failed:** due minus on time. This includes `PACKAGE_PURGED` failures and cases not yet purged.
- **Still within keep time (the sentence under the tiles):** delivered before the period end, with the original due after the end and not deleted before it.

*Section 3, cases and review:*
- **Received, Delivered, No review needed, Checked by a reviewer:** the Reports query itself (`GetOperationalReportsQuery`, source and outcome ALL, same instants). These are Reports' "Cases received", "Delivered" (first delivered in the period), the straight-through count and the reviewed count.
- **Rejected by a supervisor:** received in the period with status `REJECTED` (from `reporting_package_facts`).
- **Still open on <end>:** received in the period, not first delivered before the end, and not rejected or cancelled.
- **Finished by hand after a processing stop:** first delivered in the period, with a `BUSINESS_MANUAL_FALLBACK_SUBMITTED` success.
- **"Delivered after supervisor approval":** used only when approval was required at the start of the period and no change in the period turned it off.
- **Shares:** whole percent of Received (first table) or of Delivered (second table); "—" when dividing by 0.

**Section 4, the audit-action mapping.** Every row counts records in the period in this entity.

| Row | Counts |
|---|---|
| Redaction policy changes | `SENSITIVE_DATA_POLICY_CHANGED`, success |
| Keeping and deleting changes | `LIFECYCLE_POLICY_CHANGED`, success |
| Findings decided by a reviewer | `FINDING_CONFIRMED`, `FINDING_DISMISSED`, `FINDING_OVERRIDDEN`, success, not by the system actor |
| Cases approved for delivery | `PACKAGE_FINAL_APPROVED`, success |
| Cases rejected | `PACKAGE_REJECTED`, `REVIEW_REJECTED`, success (nothing writes either today, question 4) |
| Originals deleted | `ORIGINAL_ARTIFACT_PURGED`, success |
| Report exports (CSV) | `REPORT_CSV_EXPORTED` success + `AUDIT_EXPORTED` success with resource `CSV` |
| Compliance reports created | `COMPLIANCE_REPORT_CREATED`, success |
| Access refused (wrong role) | any action with outcome DENIED, + `AUTHORIZATION_FAILED` with any other outcome |

**B3. The PDF** (`PdfBoxComplianceReportAdapter`, US Letter).
- Layout and wording follow the canvas pages "PDF cover", "PDF policy" and "PDF activity", at 0.75 pt per canvas pixel. I checked rendered pages against the canvas.
- Section 3 always starts a new page.
- Content pages carry the header line (report · entity · period, and the customer on the right when there is one). Every page carries the footer.
- The cover footer is the canvas text. The cover has no header line, as on the canvas (question 1).
- The full edition adds "Appendix · Full audit trail" in the Contents.
  - Columns: Time (`yyyy-MM-dd HH:mm:ss` in the zone), Action (the audit-trail sentence, ported once into `AuditSentences`), Person (display name, "LexaVault (automatic)", or "Person <last 8>" for records written before 0016), Result, Case ID, Detail.
  - Rows wrap and the column header repeats on every page.
- A character the font cannot draw is printed as `?`, and control characters are dropped, rather than failing.

**The appendix detail allow list** (`AppendixDetailAllowList`, 89 actions, test `AppendixDetailAllowListTest`).
- **Printed:** every action whose every call site builds the detail from fixed text, counts, settings, enum codes, ids, page numbers or type names. A subagent surveyed every call site; I checked its key claims myself (`TechnicalFailureClassifier.safeAuditDetail` and `FailureReasonSanitizer` print codes only, never an exception's message).
  - `PACKAGE_CREATED`, `PACKAGE_SUBMITTED`, `PACKAGE_PROCESSING_STARTED`, `PACKAGE_PROCESSING_RECOVERED`, `PACKAGE_PROCESSING_RECOVERY_EXHAUSTED`, `PACKAGE_PROCESSING_COMPLETED`, `PACKAGE_APPROVED`, `PACKAGE_FINAL_APPROVAL_REQUIRED`, `PACKAGE_FINAL_APPROVED`, `PACKAGE_REPROCESS_REQUESTED`, `PACKAGE_TECHNICAL_RETRY_REQUESTED`, `PACKAGE_HIDDEN_FROM_DASHBOARD`, `PACKAGE_EXPORT_RECORDED`, `RETENTION_SCHEDULED`, `RETENTION_HOLD_EXTENDED`, `RETENTION_HOLD_RELEASED`, `TECHNICAL_ATTENTION_AUTO_CLOSED`, `SUPPORT_INCIDENT_ACTION_RECORDED`, `REDACTED_ARTIFACT_PURGED`, `ORIGINAL_ARTIFACT_PURGED`, `PACKAGE_PURGED`, `LIFECYCLE_POLICY_CHANGED`
  - `BUSINESS_RECORD_CREATED`, `BUSINESS_RECORD_LINKED`, `BUSINESS_IDENTIFIER_CONFIGURATION_CHANGED`, `SENSITIVE_DATA_POLICY_CHANGED`, `DOCUMENT_INTELLIGENCE_CONFIGURATION_CHANGED`, `DOCUMENT_INTELLIGENCE_LAST_KNOWN_GOOD_CHANGED`, `DOCUMENT_INTELLIGENCE_ROLLED_BACK`, `DASHBOARD_CONFIGURATION_CHANGED`, `INTAKE_WAITING_FOR_TRIGGER`, `INTAKE_ACCEPTED`, `INTAKE_REJECTED`, `INTAKE_DUPLICATE`, `EXTERNAL_INTAKE_CREATED`, `EXTERNAL_DOCUMENT_RECEIVED`, `EXTERNAL_INTAKE_ACCEPTED`, `EXTERNAL_INTAKE_EXPIRED`, `EXTERNAL_INTAKE_CLEANED`
  - `DOCUMENT_UPLOADED`, `DOCUMENT_EXTRACTION_STARTED`, `DOCUMENT_EXTRACTION_COMPLETED`, `DOCUMENT_DETECTION_COMPLETED`, `DOCUMENT_REDACTION_COMPLETED`, `DOCUMENT_REDACTION_VERIFIED`, `DOCUMENT_EXPORT_FAILED`, `DOCUMENT_PROCESSING_FAILED`, `FINDING_CONFIRMED`, `FINDING_DISMISSED`, `FINDING_BOUNDING_BOX_UPDATED`
  - `REVIEW_TASK_CREATED`, `REVIEW_TASK_ASSIGNED`, `REVIEW_STARTED`, `REVIEW_APPROVED`, `REVIEW_PAGE_PREVIEWED`, `BUSINESS_MANUAL_FALLBACK_STARTED`, `BUSINESS_MANUAL_FALLBACK_REGION_ADDED`, `BUSINESS_MANUAL_FALLBACK_REGION_REMOVED`, `BUSINESS_MANUAL_FALLBACK_SUBMITTED`
  - `VAULT_ENTRY_REGISTERED`, `ACCESS_REQUESTED`, `ACCESS_APPROVED`, `ACCESS_DENIED`, `DOCUMENT_DOWNLOADED`, `REPORT_CSV_EXPORTED`, `REPORTING_CONFIGURATION_CHANGED`, `COMPLIANCE_REPORT_CREATED`
  - `PLATFORM_HEALTH_CHECKED`, `WATCHED_FOLDER_SCAN_TRIGGERED`, `POLLING_RUN_STARTED`, `POLLING_RUN_COMPLETED`, `INTEGRATION_CONNECTION_TESTED`, the six `ENTERPRISE_IDENTITY_*` actions, the five `PROCESSING_STORAGE_*` actions, `LICENSE_VALIDATION_FAILED`, `LICENSE_ENFORCEMENT_DENIED`, `BRANDING_CONFIGURATION_CHANGED`, `ROUTING_RULE_DELETED`, `ROUTING_ATTENTION_REQUIRED`, `DERIVED_DOCUMENT_DELIVERY_FAILED`
- **Printed as "—" (detail can hold such text):**
  - `DOCUMENT_EXPORTED` (file name)
  - `AUDIT_EXPORTED` (unchecked filter text)
  - `POLLING_RUN_FAILED` (exception message)
  - `POLLING_SOURCE_ACKNOWLEDGED` (source revision)
  - `INTEGRATION_CONNECTION_CREATED/UPDATED/ENABLED/DISABLED` (connection name)
  - `ROUTING_RULE_CREATED/UPDATED` and `DERIVED_DOCUMENT_CREATED/DELIVERED` (destination label)
  - `LICENSE_INSTALLED` (unchecked edition text)
  - every action nothing writes today (26), and any action added later.
- An allowed detail is cut to 300 characters, with control characters removed.

**Fonts.**
- IBM Plex Sans Regular, Medium and SemiBold, and IBM Plex Mono Regular and Medium, embedded as subsets.
- Source: the app bundles only woff/woff2 (FontBox reads neither), and IBM's npm packages carry no TTF. So the TTF files come from IBM's official GitHub release assets `@ibm/plex-sans@1.1.0` and `@ibm/plex-mono@1.1.0` (`fonts/complete/ttf`, Nov 2024).
- Added under `backend/securedoc-infrastructure/src/main/resources/fonts/ibm-plex/` with the SIL Open Font License 1.1 as `OFL-LICENSE.txt`. The OFL allows bundling.
- Nothing is loaded from the internet at run time.
- SHA-256: Sans-Regular `975dcda3…`, Sans-Medium `331c8639…`, Sans-SemiBold `a20caf82…`, Mono-Regular `fe11304a…`, Mono-Medium `0bede3de…`.

**B4. Fingerprint.**
- `ComplianceReportContent.fingerprint()` is the SHA-256 of a canonical text: one `key=value` line per printed value, in print order. It includes the edition, entity, period, creator, created-at time, every table row and every appendix row.
- It leaves out page numbers and the fingerprint itself.
- It is printed in full on the cover, in groups of 4, and shortened in every footer ("7f3a 9c21 … e9f3"). The cover text is the task's wording.
- `COMPLIANCE_REPORT_CREATED` detail: `period=<from>..<to>, edition=SUMMARY|FULL, fingerprint=<hex>, fileSha256=<hex>`. The response header `X-Report-File-SHA256` carries the file's SHA-256.
- Failures are recorded as `period=…|invalid, edition=…, failure=VALIDATION|NOT_FOUND|INTERNAL`, never with an exception's text.
- The PDF is built in memory, using a temp-file-only PDFBox stream cache for large appendices, and is never put in storage.

**B5.**
- The report never reads document text, findings' values, file names or business identifiers.
- Tests cover it in four places:
  - `ComplianceReportApiTest`: markers in a file-name detail, a loan-number detail, a connection name and a destination label. Both editions: 0 matches in the PDF text (also with whitespace removed), in the report audit records and in the logs.
  - `ComplianceReportServiceTest`: a file-name marker is not in the canonical text.
  - `NoRedactedValueSavedTest`, new third test: runs detection on the 0015 fake values, then builds both editions from the records it wrote. 0 matches of any full or last-4 value in the PDF text, the report audit record and the logs.
  - C2: real markers end to end.

## Commands run and results

| Command | Result |
|---|---|
| `git merge --no-ff feature/audit-detail` (on the R1B branch) | `66b6c4f` |
| `cd backend; mvn -o clean install` (merged branch) | BUILD SUCCESS, 502 tests, 0 failures |
| `npm run test:standalone` (merged branch) | 242 pass, 0 failures |
| `cd backend; mvn -o clean install` (final) | BUILD SUCCESS, **552** tests, 0 failures |
| `test-tools\compliance-report\Invoke-ComplianceReportCheck.ps1` (final jar) | **PASS, 65 of 65 checks**, exit 0 |
| `npm run test:standalone` (final) | 242 pass, 0 failures |

`.\Build-LexaVault-All.ps1` was started first. It stopped at its own `java -version` line, because I redirected all output streams and PowerShell 5.1 treats that stderr line as an error. That is a script quirk, not a build failure, so I ran the backend and frontend commands directly, as in 0016.

**C2 (`Invoke-ComplianceReportCheck.ps1`, new)**, on throwaway container `lexavault-0017-pg`:
- Seeding: through the API first (policy and lifecycle changes, 6 cases with marker contract numbers, a PDF named with a file marker, both CSV exports). Then everything moved 35 days back by SQL on that database only. Then deliveries, purges, review facts and audit rows were added in the period.
- Roles: BUSINESS_ADMIN summary 200 and TENANT_IT_ADMIN full 200. The other 10 role and edition pairs got 403, and 10 DENIED records were written. A period ending today gave 400, recorded as `failure=VALIDATION`.
- Hashes: both editions' header equals the SHA-256 of the bytes and equals `fileSha256` in the audit record. The cover prints the recorded fingerprint.
- Numbers match the seed and `GET /operations/reports`:
  - Reports gave batches 6, delivered 4, reviewed 2, straight-through 2.
  - The PDF prints Received 6 100%, Delivered 4 67%, Rejected 1 17%, Still open 1 17%, No review needed 2 50%, Checked 2 50%, Finished by hand 1 25%.
  - Due 3, on time 1, late or failed 2, and "1 case … still within its keep time".
  - Every section 4 row, the policy lines and the settings.
- Appendix: 36 rows for 36 records in the period. Details were printed only for allowed actions (no `filename=`).
- No file marker, contract-number marker, loan marker or 0015 fake value appeared in either PDF, the report audit records or the log lines written while the reports were created.
- Only `lexavault-0017-pg` was removed; the containers named in `CLAUDE.md` were never touched.

**Sample PDFs (git-ignored):**
- `test-tools\compliance-report\output\compliance-report-summary-sample.pdf`
- `test-tools\compliance-report\output\compliance-report-full-sample.pdf`

## Tests: counts per module, and any failures with their output

| Module | Before (merged) | Now | Added |
|---|---|---|---|
| Domain | 51 | 51 | |
| Application | 158 | 196 | `CompliancePeriodTest` 7, `ComplianceReportServiceTest` 10, `PolicyHistoryTest` 7, `AppendixDetailAllowListTest` 3, `PolicySaveWithoutChangeTest` 5, `AuditServiceExplicitCaseIdTest` 4, A5 test 1, A2 push-name test 1 |
| Scan bridge | 47 | 47 | |
| Infrastructure | 8 | 11 | `PdfBoxComplianceReportAdapterTest` 3 (cover and sections wording, fonts embedded, US Letter, 5,000-row appendix: 204 pages, every row once and in order, header and footer on every page) |
| API | 238 | 247 | `ComplianceReportApiTest` 5, `CsvFormulaCellTest` 2, `ReportsCsvFormulaCellTest` 1, `NoRedactedValueSavedTest` +1 |
| **Backend total** | 502 | **552** | +50 |

- **Existing tests changed:** only additions. `NoRedactedValueSavedTest`, `ExternalIntakeServiceTest` and `PackageLifecycleDashboardRetentionTest` each gained one test.
- **Frontend:** `e2e-oidc/policyCapabilities.ts` gained the two new capabilities. Its contract test exists to fail when the backend policy changes, and it did fail (`test:auth`: 9 of 10, "the e2e capability lists are exactly ProductAuthorizationPolicy"). It passes after the update. Final `npm run test:standalone`: **242** pass, 0 failures (auth 10, vocabulary 91, ui-framework 17, oidc-signin 69, tablet 55 in WebKit).

## Files changed

- **Application:**
  - `compliance/` (new): `ComplianceEdition`, `CompliancePeriod`, `ComplianceReportContent`, `ComplianceReportService`, `PolicyHistory`, `SensitiveTypeNames`, `AuditSentences`, `AppendixDetailAllowList`
  - `port/out/ComplianceReportReadPort`, `ComplianceReportPdfPort` (new)
  - `service/AuditService` (overloads), `audit/RequestActorDisplayName` (machine names), `intake/external/ExternalIntakeService`, `business/admin/BusinessAdminConfigurationService` (A4)
  - the 15 services of A1
- **Infrastructure:** `compliance/JpaComplianceReportReadAdapter`, `compliance/PdfBoxComplianceReportAdapter` (new); `resources/fonts/ibm-plex/` (5 TTF + `OFL-LICENSE.txt`)
- **API:**
  - `controller/ComplianceReportController`, `csv/CsvCells` (new)
  - `AuditController`, `OperationsController` (CSV)
  - `UserCapability`, `ProductAuthorizationPolicy` (A6), `SecurityConfig` (exposed header), `ApplicationServiceConfig` (wiring)
- **Tests:** listed above. Frontend: `e2e-oidc/policyCapabilities.ts`.
- **Test tools:** `test-tools/compliance-report/` (new: `Invoke-ComplianceReportCheck.ps1`, `ExtractPdfText.java`, `README.md`). `.gitignore` gained `test-tools/compliance-report/output/`.
- **Docs:** `CLAUDE.md` (backend count 552), `docs/work/0017-task.md`, `docs/work/0017-report.md`.

No schema change, no new library, no frozen area, no UI change.

## Commits (hash and message), or "not committed"

- `1cdcee4` on `feature/audit-detail`: `docs(work): 0016 report: owner's answers recorded`
- `66b6c4f` on `feature/lexavault-r1b-tenant-identity`: `Merge branch 'feature/audit-detail' into feature/lexavault-r1b-tenant-identity`
- One commit on `feature/compliance-report`: `feat(compliance): 0017 compliance report PDF ...`. The hash is in the session message.

Not pushed, not merged.

## Not done, and why

- Polling connections do not get a display name: they run on a scheduler thread, and the task names push and scanner clients (question 7).
- The 2 `VaultService` call sites without a case ID (A1).
- Known edge: a 366-day `CUSTOM` period that crosses one daylight-saving change can be 1 hour longer than the Reports query's 366-day limit. The report then fails, and the failure is recorded with `failure=INTERNAL`. Last year and every shorter period are fine.

## Questions for the owner

1. **Cover header.** The task says every page has the header line; the canvas cover has none. I followed the canvas. Should the cover carry it too?
2. **Customer name.** No customer record exists on the server, so the cover and header show the entity name alone. An installed licence holds a `customerName`. Should the report use it when there is one?
3. **Access refused.** The capability check that guards the endpoints (`CapabilityAuthorizationInterceptor`) does not write an audit record, and nothing writes `AUTHORIZATION_FAILED`. So "Access refused (wrong role)" counts only the DENIED records written today: vault downloads and compliance report refusals. Should the interceptor record every 403?
4. **Rejected cases.** Nothing in the product rejects a case today: no code calls `DocumentPackage.reject()` or writes `PACKAGE_REJECTED` or `REVIEW_REJECTED`. "Rejected by a supervisor" and "Cases rejected" are therefore always 0. Keep the rows, or hide them until rejection exists?
5. **Lifecycle history.** `LIFECYCLE_POLICY_CHANGED` records hold only the new values. The report reconstructs earlier settings from the previous record, or from the defaults before the first one. Should its detail get `previous=` like the sensitive-data policy (0016)?
6. **"Delivered".** As asked, it is Reports' "Delivered": first delivered in the period, whenever the case was received. Rejected and Still open are of cases received in the period. All shares are of Received, so the four shares need not add up to 100%, unlike the canvas sample. Is that the intended reading?
7. **Polling clients.** Should polling connections record "Polling: <name>" too?

## Suggested next step

Owner review of the diff and the two sample PDFs, then merge `feature/compliance-report`. Then U022 (the UI dialog), which calls `POST /api/v1/compliance-reports` and can show `X-Report-File-SHA256`.
