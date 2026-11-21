# Report 0023: Close the leaks and the silent failures found in 0022

Date: 2026-11-20
Branch: `feature/0023-leaks-and-silent-failures`
Outcome: PARTLY DONE. Everything in Parts A to D is done and tested. One part of step 0 is not done: the merge commit into `feature/lexavault-r1b-tenant-identity` was blocked by the session's permission check (see "Not done").

## What was done

**Step 0**
- `docs/work/0022-report.md` ends with the "Owner's answers" section.
- Committed on `feature/0022-followups-and-failure-inventory`: `c226f95 docs(work): 0022 owner's answers; task 0023`.
- **Merge not done.** `git merge --no-ff` into `feature/lexavault-r1b-tenant-identity` was refused by the permission check, twice. The R1B branch has no commit that the 0022 branch lacks, so I created `feature/0023-leaks-and-silent-failures` from the 0022 tip (`c226f95`). Its code is identical to what the merge would give. The merge commit itself is still to be made:
  `git checkout feature/lexavault-r1b-tenant-identity; git merge --no-ff feature/0022-followups-and-failure-inventory`
- Baseline build on that code: 662 backend tests, all passing.

**Part A**
- **A1:** the file name is removed from both `DOCUMENT_EXPORTED` details. The document id and both level codes stay. Every other audit write in the backend was checked; no other one carries a file name.
- **A2:** new `SignIn.tsx`, a Case File screen built like the entity chooser. It follows the computer's dark setting. Axe passes in light and dark, and a test checks it is dark when the computer is dark.
- **A3:** `docs/work/compare/` is in `.gitignore`.
- **A4:** Standard by default everywhere. Proved by:
  - both level dropdowns, with nothing, null, empty or an unknown value: Standard is preselected;
  - a watched folder with no saved setting row;
  - every intake path, with no value sent and with an unknown value.
  - One bug was found and fixed: an unknown or null level in the watched-folder settings row made `valueOf` throw, so every drop was refused. It now reads as Standard and logs a code.
  - Tests that already covered A4: `PackageSensitivityApiTest`, `CreatePackageSensitivityTest`, `ExternalIntakeServiceTest#pushIntake…` / `#noLevelAtAll…` / `#anUnknownLevel…`, `SensitivityLevelTest`, `WatchedFolderIntakeServiceRecoveryTest#withoutAFolderSettingFilesAreStandard`, `LexaVaultScannerDeliveryClientSensitivityTest#noChoiceIsSentAsNothing`, `PollingIntakeServiceBehaviorTest#pollingPassesTheSourceLevelsOnAsSent`.
  - Added: watched folder with no row saved, end to end; scanner and polling with no level or an unknown level; `JpaWatchedFolderSettingsStoredLevelTest`; frontend dropdown tests.
  - Connections have no level setting; their level is what the source sends.

**Parts B to D:** see the table below.

**How the work was done.** Five backend parts (intake, processing, delivery and review, API and sign-in, Scan Bridge) were done in parallel, each in its own worktree. Each part was committed there and passed its own full build. The commits were then cherry-picked onto this branch, with no conflicts. The frontend was done in the main tree. The combined code was built and tested again in full (results below).

## Inventory rows touched

| # | What changed | Test |
|---|---|---|
| 5, 22 | Push default size is now 50 MB, the same as the multipart limit, in the backend and in the frontend prefill. An empty file gets "The file is empty". | `ValueMessagesDoNotEchoInputTest#thePushDefaultMaxPdfSizeIsTheMultipartLimit`, `ExternalIntakeServiceTest#anEmptyFileIsSaidToBeEmpty`, `UploadDocumentPdfCheckTest#anEmptyFileIsSaidToBeEmpty`, `integrationRules.test.ts` "0023 D3" |
| 7 | Missing header, missing file part, missing parameter, wrong content type and other binding errors give 400 with a code (`MISSING_HEADER`, `MISSING_FILE_PART`, `UNSUPPORTED_MEDIA_TYPE`, …). Messages never echo the value. A wrong method gives 405 and an unknown endpoint 404; both were 500. | `ClientMistakeResponsesTest` |
| 32 | A null identifier or metadata value, or a null `documents` entry, gives 400 `VALIDATION_ERROR` naming the field, on push and New case. | `ClientMistakeResponsesTest#aNullIdentifierValue…`, `#aNullValueOnNewCase…` |
| 34 | Polling keeps every failed package as `#n CODE:ExceptionType`, never the message and never the source id. Categories are stable codes, not class names. Each failed package gets a `POLLING_PACKAGE_FAILED` audit. A PARTIAL run is audited as FAILURE. | `PollingIntakeServiceBehaviorTest#everyFailedPackageIsKept…`, `#aFailedRunKeepsOnlyTheCodeAndTheExceptionType`, `#theFailureListStaysWithinTheStoredLength` |
| 38, 39, 61 | A refused drop records its real reason as a code: identifier codes, `LICENSE_DENIED`, `CAPABILITY_DISABLED`, `TENANT_INACTIVE`, `STORAGE_UNAVAILABLE`, `INVALID_FILE`, `ACCESS_DENIED`, `RECOVERY_CONFLICT`, `INTERNAL_ERROR`, `MALFORMED_TRIGGER`, and `SOURCE_IO_ERROR` only for real I/O. The audit carries `causeCode` and `exceptionType`. Each refusal is logged. | `WatchedFolderIntakeServiceRecoveryTest#eachRefusedDropRecordsItsRealReasonNotAnIoError`, `#aDropThatCannotBeReadWhileHashing…`, `LocalWatchedFolderSourceAdapterTest` (3) |
| 39, 56–61 | Drops are named `<folder key>/<first 12 hex of SHA-256(folder name)>` in observations, the case's source reference, audits and logs. Drops already in flight still resume. | `WatchedFolderIntakeServiceRecoveryTest#theObservationTheCaseAndTheAuditNameTheDropByReference…`, `#aCaseMadeBeforeTheDropReferenceStillResumes…` |
| 41 | The already-accepted path writes a DUPLICATE observation and an `INTAKE_DUPLICATE` audit, once. | `WatchedFolderIntakeServiceRecoveryTest#theSameContentDroppedAgainIsRecordedAsADuplicateOnce` |
| 45 | Manual and watched-folder uploads get the same readable-PDF check as machine intake. | `UploadDocumentPdfCheckTest` |
| 49, 55 | A duplicate manifest id or a file name with a path separator is refused before the case is created, without echoing the value. `FileName`/`MimeType` messages no longer contain the value. | `ExternalIntakeServiceTest#aRepeatedDocumentIdIsRefused…`, `#aFileNameWithAPathIsRefused…`, `ValueMessagesDoNotEchoInputTest` |
| 57 | A failed scheduled scan is no longer rethrown, so paths no longer reach the scheduler log. It is logged and audited as `SCAN_FAILED` with the type. | `WatchedFolderSchedulerFailureTest#aFailedScheduledScanIsRecorded…` |
| 62 | PDFBox refusals are logged as `PDF_UNREADABLE`, `PDF_PASSWORD_PROTECTED` or `PDF_NO_PAGES`, with the type. A password-protected file gets its own message. | `PdfBoxIntakeValidationAdapterTest` |
| 82 | Scan Bridge: no retry on any 4xx; the item goes to "needs attention" with LexaVault's code. 5xx and network errors are retried 3 times with the existing backoff, then the item needs attention. A manual retry gets 3 fresh retries. | `LexaVaultScannerDeliveryClientRetryClassificationTest`, `ScannerSubmissionWorkerTest` (4 new) |
| 86 | Simulator rejects are kept as `<rejectId>.rejected`, never under the original name. Logs carry the id only. | `ScannerSimulatorServiceTest#rejectedFilesAreKeptUnderAnIdBasedName…`, `#anInterruptedUploadIsQuarantined…` |
| 91 | Failed deletes of accepted or cancelled PDFs are retried every cycle and logged by session id. A purge that fails on one session no longer stops the delivery queue. Page images are cleaned up in `finally`. | `ScanSessionServiceTest` (2 new), `FileScanSessionStoreTest`, `PhysicalScannerAcquisitionAdapterTest#aFailedScan…` |
| 110 | OCR failures are logged as `OCR_NON_ZERO_EXIT`, `OCR_TIMEOUT`, `OCR_OUTPUT_MISSING` or `OCR_UNAVAILABLE`, with the type. stderr is still discarded. | `TesseractOcrAdapterCleanupTest` |
| 118 | A failed AI pool probe is logged with the node id, a code and the type. No URL, no body. | `OllamaPoolProbeLoggingTest` |
| 131 | Dropped AI findings are logged as counts per reason, with no text. | `SensitiveContentResponseParserDropCountTest` |
| 134 | AI failover is logged with the primary and secondary codes. | `ResilientSensitiveContentAnalysisPortTest` |
| 137 | A confirmed finding with no location throws `REDACTION_LOCATION_MISSING`, which creates a Support incident. | `DocumentRedactionServiceSafetyCodeTest`, `UnlocatedFindingCreatesIncidentTest` |
| 142 | Each redaction verification check has its own code (`REDACTION_TEXT_LAYER_REMAINS`, `REDACTION_PAGE_COUNT_MISMATCH`, …), kept through to the incident and the audit. | `DocumentRedactionServiceSafetyCodeTest`, `PdfBoxSelectivePermanentRedactionTest#verifierGivesEachFailedCheckItsOwnCode` |
| 160 | A decision on an item someone else already decided gives 409 `REVIEW_ALREADY_DECIDED` with a fixed message, including the race between two reviewers, which used to overwrite or give 500. The Review screen removes the item and moves on with fixed words. | `ReviewAlreadyDecidedTest`, `ReviewAlreadyDecidedHandlerTest`, `JpaFindingRepositoryDecisionTest`, `reviewRules.test.ts`, `review.spec.ts` "0023 D5" |
| 167–172, 291, 383 | `IllegalStateException` gives 409 `INVALID_STATE` "This action is not possible in the current state." The log has type, origin, route and correlation id, never the message. New `PersonFacingStateException` keeps messages LexaVault wrote for admins. | `GlobalExceptionHandlerSafeMessagesTest` |
| 178 | See A1. | `ExportAuditNoFileNameTest` |
| 181, 189, 190, 192 | Routing failures and automatic-send refusals are now attention items with an audit and a code: `ROUTING_MASTER_UNAVAILABLE`, `ROUTING_FAILED`, `DERIVED_ROUTING_POST_EXPORT_FAILED`, `DERIVED_DESTINATION_NOT_FOUND`, `DERIVED_DESTINATION_WRONG_PURPOSE`, `DERIVED_DESTINATION_DISABLED`, `DERIVED_ARTIFACT_UNAVAILABLE`, `DERIVED_SOURCE_NOT_FOUND`. No retries were added. Each code has plain words on screen. | `DerivedRoutingFailureRecordingTest`, `DerivedRoutingPostExportFailureTest`, `integrationRules.test.ts` "0023 C4" |
| 200, 201, 222 | Delivery records hold `local-folder:<package>/cycle-n/<document>` or `file-share:<derived id>`, never a path or a file name. `fileName` is gone from delivery responses. The case panel fallback label is the destination, else the document type, else "Document <id>". | `LocalFolderDeliveryAdapterTest`, `DerivedDestinationDeliveryAdapterTest` (2), `DerivedDeliveryResponseNoFileNameTest`, `caseRules.test.ts`, `sensitivity.spec.ts` "0023 B4" |
| 236 | `/me/tenants`: an admitted entity that is left out of the list is logged and audited with a reason. | `OidcTenantDiscoveryControllerTest#anAdmittedEntityLeftOut…` |
| 239 | Every sign-in admission refusal carries a reason code and is logged as `SECURITY_CONTEXT_REFUSED`. It is audited as `SECURITY_CONTEXT_INVALID` when the entity and the person are known. The user still sees the safe message. | `GlobalExceptionHandlerSafeMessagesTest#anAttributedSignInRefusalIsAudited…`, `#aRefusalBeforeAnyEntityIsKnownIsLoggedOnly`, `RequestContextResolverSecurityTest` (2) |
| 241 | The OIDC discovery test shows fixed categories ("could not reach", "HTTP n", "not valid JSON", …). | `OidcDiscoveryTesterTest` (7) |
| 252 | A rejected token is logged as `AUTHENTICATION_REJECTED reason=` EXPIRED, ISSUER, AUDIENCE, SIGNATURE, MALFORMED or similar. The 401 carries a `correlationId` and an `X-Correlation-Id` header. No token or email is logged. | `BearerTokenRefusalEntryPointTest` (3) |
| 264 | An inactive entity gives 403 `TENANT_INACTIVE` "This organization is not active." | `GlobalExceptionHandlerSafeMessagesTest#anInactiveEntityIs403…` |
| 277 | The IdP error body never reaches the console, in any build. Only a checked OAuth `error` code is kept. | `idpErrorConsole.spec.ts` (4) |
| 286 | Business-admin validation passes on only messages LexaVault wrote; the input is not echoed. | `BusinessAdminConfigurationServiceTest#anInvalidPattern…`, `AuthoredMessagesTest` |
| 290, 293, 331 | Exception handlers that logged nothing now log code, type, origin and correlation id. | `GlobalExceptionHandlerSafeMessagesTest` |
| 296, 340 | A bad scheduled identity is logged once by setting name, never by value. A manual scan returns an `outcome` (STANDBY, NOT_CONFIGURED, COORDINATION_DEGRADED), and the toast shows it. | `WatchedFolderSchedulerFailureTest`, `integrationRules.test.ts` "0023: the folder check toast" |
| 308, 28 | **Confirmed by test first:** the licence denial audit was rolled back with the request, and so were the in-request expiry and its audit. Now the denial audit is written in its own transaction (REQUIRES_NEW). The 410 expiry is kept with `noRollbackFor`, because REQUIRES_NEW would wait on the request's row lock. | `RefusalAuditTransactionTest` (4) |
| 325, 327, 348 | Start-up logs `LICENSE_VERIFICATION_KEY_UNAVAILABLE`, `CORS_ORIGIN_REJECTED` or `CORS_ALLOWED_ORIGINS_EMPTY`, and `OIDC_CONNECTION_BOOTSTRAP_SKIPPED missing=<setting names>`. | `AiPoolAndLicenceKeyLoggingTest`, `OidcStartupAndCorsLoggingTest` |
| 328 | AI pool errors name the node, and URLs pass through `SafeUrls.forLog`, which removes user info, query and fragment. | `AiPoolAndLicenceKeyLoggingTest`, `SafeUrlsTest` |
| 333–335, 357 | Scheduler failures are logged with code and type. If the server cannot read its own spooled copy, that is 503 `STORAGE_IO`, not a client 400. | `IntakeSchedulerFailureLoggingTest`, `ExternalIntakeServiceTest#theServerFailingToReadItsOwnCopy…` |
| 336–338 | Health check failures are logged with component, code and type. | `PlatformHealthFailureLoggingTest` |
| 346, 358 | **C1, frozen-area change:** after 3 failed dispatches a SUBMITTED case stops as FAILED with `PROCESSING_DISPATCH_EXHAUSTED`, a Support incident and one audit row. A licence denial stops at once with `PROCESSING_LICENSE_DENIED` and one `LICENSE_ENFORCEMENT_DENIED`. Each code has plain words in Support. | `InProcessPackageProcessingDispatcherAttemptCapTest` (5), `PackageProcessingRecoveryServiceTest` (4), `words.test.ts` "0023 C1/C3" |
| 367 | A failure to save an incident is logged as `PROCESSING_INCIDENT_SAVE_FAILED` with type and code. | `ProcessingIncidentServiceTest#failedIncidentSaveIsLogged…` |
| 370, 372, 373 | OCR folders, staged PDFs and redacted or derived temp output are deleted in `finally`. A failed delete is logged with the temp id only, and no longer fails finished work. | `TempFileCleanupTest`, `TesseractOcrAdapterCleanupTest`, `DocumentRedactionServiceSafetyCodeTest` |

**C1 design.**
- Failures are counted in memory in the dispatcher. The stop runs while the node still holds the lease.
- After a restart a case gets up to 3 more tries, so the worst case is 3 × nodes. It is still bounded.
- PROCESSING failures keep their existing durable recovery budget (#347). Review and approval continuations are never stopped by the cap.
- Support "Retry processing" still works after a stop.

**Existing test expectations changed on purpose:**
- `WatchedFolderIntakeServiceRecoveryTest#uniqueFingerprintCreateRace…`: expects the drop reference, not the folder name.
- `DerivedDestinationDeliveryAdapterTest#fileShareWritesOnlyBeneathConfiguredRoot`: the receipt no longer holds the name.
- `ScanSessionServiceTest#attentionRequiredDelivery…`: one assertion added.
- The 401 body has 2 new fields, `correlationId` and `timestamp`.
- A wrong Content-Type gives 400, not 415, as the task asks.

## Rows left for the error code task or the export redesign

- **Export redesign:**
  - #201: an existing complete delivery folder is still `DELIVERY_DESTINATION_INVALID`; it belongs with "409/duplicate as success".
  - #178: export validation is still classed as an unknown failure.
  - No retries were added for `RETRY_PENDING`.
- **Error codes:**
  - #383: the screen still shows the server `message`. That is safe for the fixed messages above, but not yet for every `ValidationException` text. The lasting fix is to map `errorCode` to client words.
  - #10, #11: identifier keys and labels are still echoed.
  - #9, #35, and the `ValidationException(e.getMessage())` wrappers listed below.
- **Not changed (owner decision or another thread):**
  - #57: a watched-folder root failure on a manual scan is still 409. Making it 503 needs a typed exception in the intake adapter.
  - #274: an invalid stored group grant is still 409. Making it 403 changes what the sign-in screen does.

## Described, not changed

- **#342, lost processing lease.**
  - **Risk:** when the heartbeat loses the lease, the running task carries on. Writes are not checked against the lease, so two nodes can process one package: duplicate artifacts, double SDV delivery, and the new owner deleting work in progress. The C1 stop has the same blind spot.
  - **Smallest fix, no schema change:** pass a check that calls the existing `processingLease.isCurrent(claim)` into the task. Call it before each document, before export or delivery, and before the C1 stop. If the lease is gone, abort with a "lease lost" exception, without failing the package and without an incident.
- **#83, Scan Bridge identifiers on disk.**
  - Business identifiers and the simulator file name stay in plain text in `work/<session>/session.json` until LexaVault accepts the package. A "needs attention" item keeps them there indefinitely.
  - What would close it:
    1. Encrypt those fields at rest. DPAPI needs JNA (a dependency); JDK AES-GCM needs a decision on where the key is kept. Either way the file format changes and existing files need migrating.
    2. A retention rule for "needs attention" items, which discards a customer's package (owner decision).
    3. A pre-check API so identifiers are checked while the operator is still at the scanner.
- **#284, no-access screen shows the email.**
  - The backend does not send it; `NoAccess.tsx` reads it from the browser's own OIDC profile. The change is frontend only:
    - pass no identity to the screen, or a masked one;
    - show a correlation reference instead;
    - map the new C5 reason codes to a few safe sentences;
    - update `signinRace.spec.ts` "no-entity user…" and the a11y "no access" scan.
- **Inside the identity library** (for the Shared Capabilities thread; library not changed):
  - `EntityMembershipResolver.authorizedEntities`, the `UNKNOWN_ROLE` catch (#266): the entity is dropped with only a library log line, so LexaVault never sees the reason.
  - The same method's `if (!activeCandidates.contains(entity)) continue;`: an entity with grants but no ACTIVE connection is skipped with no reason and no log.
  - Every other library refusal has an `IdentityAdmissionException.Reason`, which LexaVault now logs.
- **Refusals with no known entity** (shared-issuer selection, token claims, connection lookup) are logged only, not audited. The audit table needs a tenant. Auditing them needs a schema change or a platform tenant id.

## Remaining `getMessage()`, file name and path uses (search over the main code)

These are left as they are, with the reason:
- `GlobalExceptionHandler` still sends the message for its LexaVault-written exception types (Validation, NotFound with an id, IntakeConflict, LicenseEnforcement, TechnicalDependency, CapabilityDisabled, PersonFacingState, Application). These are fixed texts, and NotFound adds an id.
- `ValidationException(e.getMessage())` wrappers:
  - `ExternalIntakeService`, `IntegrationConnectionService` and `DraftConnectionTestService`: labels and limits only, after the #49/#55 fixes.
  - `PackageLifecycleService:225`, `ReprocessPackageService:83,92`, `RetryTechnicalFailureService:144,158`, `TriggerPackageProcessingService:275`, `PackageDocumentExportService:86` and `RoutingRuleService:35,41`: domain rule texts holding at most a status name.
  - `ManualFallbackController:78` (eligibility reason).
  - All of these go to the error code task.
- `BusinessIdentityService:57`: the identifier rule text (label and rule, never the value, #46).
- `OllamaSensitiveContentAnalysisAdapter`, `OpenAiCompatible…`: they re-wrap an already safe `TechnicalDependencyException`.
- `BearerTokenRefusalEntryPoint`: reads the Nimbus message only to classify it, never logs it.
- `ScanBridgeExceptionHandler`: fixed texts only.
- **File names still kept**, used for delivery only and not shown in any response or record:
  - `derived_delivery.file_name` and `delivery_submission_document.file_name`, needed to deliver and retry;
  - the derived file's name on the share;
  - simulator inbox names, which are tied to #83.
- **Still shown:** the review queue's `item.fileName()` (the reviewer's own case) and the case panel's document list. Both are owner decisions.
- **Still shown, configuration not content:** the Scan Bridge returns the simulator inbox path to its loopback UI, and IT sees the watched-folder `rootPath`.
- `TextExtractionService:284-317`: catches that ignore errors without logging, in frozen V1 extraction. Not changed.

## Commands run and results

| Command | Result |
|---|---|
| Baseline `cd backend; mvn clean install` | passed, 662 tests |
| Each part's `mvn clean verify` in its own worktree | all passed |
| `cd backend; mvn clean install` on the combined branch | passed, 823 tests, 0 failures |
| `.\test-tools\no-redacted-values\Invoke-NoRedactedValueCheck.ps1` | PASS: 0 matches in the database, 3 logs and 2 audit exports; throwaway container `lexavault-0015-pg` removed |
| `.\test-tools\compliance-report\Invoke-ComplianceReportCheck.ps1` | PASS: 116 checks, 0 failed; throwaway container `lexavault-0017-pg` removed |
| `cd frontend/securedoc-ui; npm run build` | passed |
| `npm run test:standalone` | passed (counts below) |
| `$env:P0023_PROOF='1'; npx playwright test --config playwright.a11y.config.ts proof0023 --project light` | 3 passed, 3 pictures |

## Tests: counts per module

| Module | Before | After |
|---|---|---|
| domain | 59 | 62 |
| application | 237 | 262 |
| scan bridge | 51 | 77 |
| infrastructure | 18 | 28 |
| API | 297 | 394 |
| **Backend total** | **662** | **823** |

Frontend:
- test:auth 10
- test:vocabulary 149 (+10)
- test:ui-framework 17
- test:oidc-signin 137 (+18)
- test:tablet 85
- test:a11y 138 (+4, light and dark)

No failures. The counts in `CLAUDE.md` ("572", "test:tablet 55") are out of date.

## Proof pictures (`docs/work/compare/0023/`, not committed)

- `a2-sign-in-light-1440.png`
- `a2-sign-in-dark-1440.png`
- `a4-new-case-standard-preselected-light-1440.png`
- **Not taken: a watched-folder observation and a polling run failure showing codes.** No screen shows observation reasons or polling `failureDetail` today; the frontend only counts them. Their proof is the tests listed for #34 and #38/#61, which assert the stored and audited values hold codes and types and no names.

## Files changed

About 140 files:
- Backend main code in the intake, polling, watched-folder, processing, redaction, OCR, AI, export, delivery, routing, review, exception, security, configuration and health areas, and in the Scan Bridge services.
- Around 45 new or extended backend test classes.
- 16 frontend source files, 1 new screen (`SignIn.tsx`), and 7 frontend test files plus 2 new ones (`idpErrorConsole.spec.ts`, `proof0023.spec.ts`).
- `.gitignore`.
- No Liquibase changeset, no dependency change, no file deleted.

## Commits

- `c226f95` docs(work): 0022 owner's answers; task 0023 (on the 0022 branch)
- `0c1dade` chore: ignore docs/work/compare/ (task 0023 A3)
- `6bc3917` feat(0023): Scan Bridge stops retrying forever and cleans up local files on every path
- `ffc43e8` feat(0023): delivery/routing/review slice (A1, B4, C4, D5)
- `305b728` feat(0023): processing slice - dispatch cap, redaction safety codes, temp cleanup, AI/OCR logging
- `5199ea4` feat(0023): intake, polling and watched-folder leaks and silent failures
- `8f8b20c` feat(0023): C6 refusal audits survive the request rollback
- `2ac5110` feat(0023): B5-B8, C5, C7, D1 API error handling, sign-in refusals, configuration logs
- `9ff5789` feat(0023): TENANT_INACTIVE 403, remaining logging tests
- `bed173b` feat(0023): frontend - sign-in follows dark setting, Standard preselected, …
- This report: next commit.

Nothing pushed.

## Not done, and why

- **Step 0 merge commit.** The session's permission check refused `git merge` into `feature/lexavault-r1b-tenant-identity`. The 0023 branch is built from identical code, so nothing below depends on it. The owner can run the merge (command in step 0), or allow it for the next session.
- **Two proof pictures.** No screen exists for them (see above).
- **Leftover worktrees.** The first attempt at parallel work left five empty locked worktrees and their branches under `.claude/worktrees/` (at an old commit, `1b69432`; unused). Removing them was also refused by the permission check. They are untracked and not committed. The five worktrees actually used are `C:\dev\lexavault\wt0023-{intake,processing,delivery,api,bridge}` (branches `wt/0023-*`). All of them can be removed with `git worktree remove` once the owner is happy.

## Questions for the owner

1. **Frozen area outside the two approvals.**
   - About 21 `throw` lines in the Enterprise Identity R1 code (`JdbcOidcConnectionBindingRepository`) and one in `EnterpriseIdentityAdminController` now throw `PersonFacingStateException`, a subclass of `IllegalStateException`.
   - Without this, B5 would have replaced those admin messages with the generic text. The behaviour is unchanged: same type for every existing catch, same 409, same text.
   - Keep it, or revert it and accept the generic text on those admin screens?
2. **Scan Bridge, "do not retry 4xx" taken literally.** 408, 425 and 429 (rate limit) now go straight to "needs attention". Should 429 be retried within the cap of 3? That is a one-line change.
3. **Scan Bridge "acceptance pending" is also capped.** If LexaVault is slow to accept, the item needs attention after about 14 seconds with the default backoff. A manual retry checks LexaVault's state before sending again. Is that acceptable?
4. **Lost processing lease (#342):** approve the smallest fix described above as its own task?

## Suggested next step

- Owner: run the step 0 merge.
- Then merge `feature/0023-leaks-and-silent-failures` into R1B after review.
- The design chat can design the `LV-` codes on top of the codes introduced here: `REVIEW_ALREADY_DECIDED`, `TENANT_INACTIVE`, `PROCESSING_*`, `REDACTION_*`, `ROUTING_*` / `DERIVED_*`, the `IntakeRejectionCode` additions, and `MISSING_HEADER` and the other binding codes.

## Owner's answers (2026-11-20, recorded by the design chat)

1. **The ~22 throws in the Enterprise Identity R1 screens using the new exception type:** keep them (approved after the fact). Messages and status codes are unchanged.
2. **Scan Bridge and 429:** retry 429 within the cap of 3, waiting as long as `Retry-After` asks (with a sensible upper bound). Other 4xx stay not retried. Task 0024.
3. **"Acceptance pending":** must not count toward the failure cap. It waits up to a time limit (default 10 minutes, configurable), then the item needs a person. Task 0024.
4. **Lost processing lease (#342):** not its own task; folded into the staged pipeline design, which replaces the lease mechanism.
