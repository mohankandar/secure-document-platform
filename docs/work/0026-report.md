# Report 0026: Error code follow-ups from 0025

Date: 2026-11-21
Branch: `feature/0026-error-code-followups` (from `feature/0025-error-codes` at d27b8c9)
Outcome: DONE (0026 work not committed; see "Commits")

## What was done

**Step 0.**
1. `docs/work/0025-report.md` ends with "Owner's answers": yes.
2. `npm run test:standalone` on the 0025 work: PASS, exit 0 (auth 10, vocabulary 156, ui-framework 17, oidc-signin 137, tablet 85, a11y 138). The placeholders in the 0025 report were replaced with these.
3. Committed on `feature/0025-error-codes`: **0883006** `feat: 0025 LexaVault error codes (LV-<AREA>-<NNN>) in API, audit, screens and guide` (170 files, report and `e2e-a11y/proof0025.spec.ts` included), then **d27b8c9** `docs(work): error code additions; task 0026` (this task and the 137-code design).
4. Merged into `feature/lexavault-r1b-tenant-identity` in a temporary worktree (since removed): **230c752**, `--no-ff`, standard message. **I merged 0883006, not the branch tip d27b8c9.** d27b8c9 adds 8 codes to the design that the 0025 code doesn't have, so `LvErrorCodeRegistryTest` and `BridgeLvCodeDocumentTest` would fail on R1B. The design and task file reach R1B with the 0026 merge. Merged R1B: `mvn -o clean verify` BUILD SUCCESS, **913 tests, 0 failures** (domain 74, application 274, scan bridge 134, infrastructure 28, API 403). Not pushed.
5. `feature/0026-error-code-followups` created from d27b8c9.

**1. The 8 new codes.** Added to `LvErrorCode` (137 codes), the generated frontend file and the guide. The registry can now hold more than one HTTP status (`httpStatuses()`, `httpStatusLabel()`); LV-SYS-009 is "404 / 405".
- `IntakeRejectionCode.RECOVERY_CONFLICT` → LV-INT-035.
- Bridge `SUBMISSION_ALREADY_QUEUED`, `ATTENTION_REQUIRED_CANNOT_CANCEL` → LV-SCN-019; `RECOVERED_AFTER_RESTART` → LV-SCN-020; `LEXAVAULT_ACCEPTANCE_PENDING` → LV-SCN-021. After the time limit it stays LV-SCN-017. These two states win over an HTTP status left on the item by an earlier attempt.
- Review page below 1 or past the last page (`PdfBoxReviewPageRenderAdapter`) → LV-REV-009.
- Technical hold extension (2 refusals), Technical Attention restart, a closed support incident, removing a case from the Dashboard → LV-CFG-011.
- `NotFoundException` for anything that is not a case → LV-SYS-008; a case stays LV-INT-025; a thrower can give its own code (used for PRC-007).
- Unknown address (`NoResourceFoundException`, also `NoHandlerFoundException`) → 404, and wrong method → 405, both LV-SYS-009 with the standard body. A request without a valid sign-in still gets 401 LV-AUT-001 first.
- **Legacy vault services: a route still reaches them, but only in LOCAL security mode.** Both controllers carry `@ConditionalOnProperty(securedoc.security.mode=LOCAL)`. That is the `local` profile used by `Start-LexaVault-Local.ps1`; the default (OIDC) has no route. `DownloadArtifactUseCase` (`VaultService.execute(DownloadArtifactCommand)`, 6 refusals) has no route in any mode. Code not deleted. Routes and refusals for the design chat, with the code each gives today:

| Route | Refusal | Code today |
|---|---|---|
| `POST /api/v1/vault/entries` (`VaultService`) | package not found | INT-025 |
| | document or artifact not found | SYS-008 |
| | package not APPROVED/VAULTED; document not in package; document not PROCESSED; artifact not in document; artifact not AVAILABLE; not REDACTED/ORIGINAL; entry already exists (7 `ValidationException`s, no code) | SYS-001 (logged as a gap) |
| | an id that isn't a UUID (`UUID.fromString` in the controller) | CFG-001 |
| `POST /api/v1/artifact-access-requests` (`ArtifactAccessService`) | vault entry not found | SYS-008 |
| | artifact not in the entry; only original artifacts (2, no code) | SYS-001 |
| | justification blank; expiry not in the future (domain `AccessRequest`, `IllegalArgumentException`) | CFG-001 |
| `GET /api/v1/artifact-access-requests/{id}` | request not found | SYS-008 |
| `POST …/{id}/approve`, `POST …/{id}/deny` | request not found | SYS-008 |
| | denial reason required (no code) | SYS-001 |
| | wrong status, expired, requester approves own request (domain `IllegalStateException`) | SYS-001 |
| `GET /api/v1/artifact-access-requests` | none | — |

**2. Mapping corrections.** `SCANNER_INBOX_UNAVAILABLE` → LV-SCN-013; `PHYSICAL_SCANNER_DEVICE_NOT_FOUND` → LV-SCN-008; a missing or empty licence token → LV-LIC-001 (in `LicensingController` and in `TenantLicensingService.install`). Other best fits unchanged.

**3. Codes now wired.**
- **LV-PRC-001:** a PDF that can't be opened during text extraction (native and hybrid adapter) throws `PdfUnreadableException` (an `UncheckedIOException`, as before; fixed message). A failure to write the temporary copy keeps its old exception. Intake (INT-008) is unchanged because `stage()` was not touched.
- **LV-PRC-007:** original artifact missing at extraction (#355) and at redaction (#363).
- **LV-AI-006:** no AI settings for the entity (#135). The internal code stays `AI_UNAVAILABLE`.
- **LV-SYS-003 / LV-SYS-004:** recognised by type name (`SQLException`, `DataAccessException`, `TransactionException`, `PersistenceException`, `LiquibaseException`; `RejectedExecutionException`, `TaskRejectedException`). Logged with `code=` on: the intake maintenance cycle, the processing lease (heartbeat, check, release), the recovery scans and candidates, and the dispatcher's queue-full refusal (`PACKAGE_PROCESSING_DISPATCH_REJECTED`). An unhandled database or queue-full failure that reaches the API gets that code in its body and log; the status stays 500.
- **LV-SYS-005 / LV-SYS-003 / LV-AI-007 at start:** new `StartupFailureLogger` (registered in `main`) logs `LV_STARTUP_FAILED code=… type=… origin=…` on a failed start, with codes and types only, never the message. The AI preload log lines carry `code=LV-AI-007`.
- **LV-SYS-006:** a failed health check logs `lvCode=LV-SYS-006` and its result carries `lvCode`. Admin › Health shows "Down · LV-SYS-006".

**4. Owner's answers.**
- **No-access screen:** "Signed in as <email or user name>" right under the title and code, then the advice (plus "If this isn't the account you meant to use, sign out and sign in with the right one."). It is display only; nothing logs or sends it.
- **API `detail`:** an optional field, left out of the JSON when empty, that names what is wrong and never a value sent. Sources:
  - Missing header, part or parameter ("Header X-Content-SHA256 is missing").
  - Content-Type ("Content-Type must be one of: …", the accepted types only).
  - Multipart body, parameter type, bean-validation field names, the upload size limit, and the allowed methods on a 405.
  - The thrower's own detail.
  - Otherwise, for intake, scanner, settings and licence `ValidationException`s, LexaVault's own refusal text, which names the field and the range.
  - Case-field refusals (`IntakeValidationException`) never use their message, which can quote the key or value sent. They now carry their own detail, for example "Case field Loan number is required", or "… breaks its rule (length, pattern or allowed values)".
  - One settings message echoed a value ("Unsupported support incident action: " + action); it no longer does.
  - `EXTERNAL_INGESTION_V1.md` has a new "Error body" section.

**5. Guide.** "Reviewed by the design chat, 2026-11-21." replaces the draft line.
- DLV-003 and DLV-014 step 3: "After the fix, choose **Retry processing** in Support." That is the button's real name on the Support Problem page (`ProblemDetail.tsx`).
- LIC-001 and LIC-002 have step 4 as asked.
- SYS-007 step 1 is reworded as asked.
- 8 new entries with 2–3 steps each.

## Codes in use, per area (137 codes, 3 reserved)

INT 37 · SCN 21 · PRC 9 · AI 7 · RED 4 · REV 9 · DLV 13 · AUT 10 · LIC 4 · CFG 11 · SYS 9 = **134 in use**. The 3 reserved (INT-027, DLV-009, DLV-015) are given to nothing. SCN-011 and AUT-009 are given by the browser only. SYS-005 and AI-007 appear in start-up logs only.

## Commands run and results

| Command | Result |
|---|---|
| `npm run test:standalone` on the 0025 work (step 0) | PASS, exit 0 |
| `git commit` ×2 on `feature/0025-error-codes` | 0883006, d27b8c9 |
| `git worktree add ..\r1b-merge-0026 feature/lexavault-r1b-tenant-identity; git merge --no-ff --no-edit 0883006` | 230c752; worktree removed afterwards |
| `cd backend; mvn -o clean verify` on merged R1B | BUILD SUCCESS, 913 tests, 0 failures |
| `mvn -pl securedoc-application test -Dtest=LvErrorCodeFrontendFileTest -Dlv.codes.write=true` | generated file rewritten (137 codes) |
| `cd backend; mvn -o clean install "-Dp0026.proof=…\docs\work\compare\0026"` on 0026 | BUILD SUCCESS, **939 tests, 0 failures** (first run: 1 failure in my new test, see below) |
| `npm run test:standalone` suites on 0026 | auth 10, vocabulary 158, ui-framework 17 (includes `tsc`), oidc-signin 138: all pass. The first tablet run stalled (one test 15 min, the next 1.9 h; the session ended, likely machine sleep). Re-ran `npm run test:tablet`: 85 passed. Then `npm run test:a11y`: 138 passed |
| `.\test-tools\no-redacted-values\Invoke-NoRedactedValueCheck.ps1` | PASS: 0 matches (database, 3 logs, 2 audit exports); throwaway `lexavault-0015-pg` removed |
| `.\test-tools\compliance-report\Invoke-ComplianceReportCheck.ps1` | PASS: 116 checks, 0 failed; throwaway `lexavault-0017-pg` removed |
| `$env:P0026_PROOF='1'; npx playwright test --config playwright.a11y.config.ts proof0026 --project light` | 3 passed; pictures below |

## Tests: counts per module, and any failures with their output

| Module | 0025 (R1B after merge) | Now |
|---|---|---|
| Domain | 74 | 74 |
| Application | 274 | 281 |
| Scan Bridge | 134 | 136 |
| Infrastructure | 28 | 28 |
| API | 403 | 420 |
| **Backend total** | **913** | **939** |

Frontend: auth 10, vocabulary 158 (+2), ui-framework 17, oidc-signin 138 (+1), tablet 85, a11y 138.

The one failure on the first backend run was in a new test: `LvErrorCodeFollowupsTest.databaseAndQueueFullFailuresReachingTheApiCarryTheirCodes:147`. The generic handler had put `code` in a structured-log key that `StructuredLogContext` filters out, so the code was never logged. Fixed in the code: it is now in the log text (`… correlation=… code=LV-SYS-004`). The test was not changed.

New tests:
- `LvErrorCodeFollowupsTest`, covering:
  - SYS-008 and INT-025 bodies, CFG-011, and REV-009 with a real PDF.
  - PRC-001 with a cut-off PDF through both extraction adapters.
  - AI-006 through the real routing port.
  - SYS-003 and SYS-004 bodies and logs, with no message text.
  - Start-up codes, logged without the message.
  - The case-field detail names the field and never the key or value sent; settings detail; the thrower's own detail first; no `detail` key when there is none.
- `ClientMistakeResponsesTest` (MockMvc, real dispatch): header/part/Content-Type detail without the value; 404 and 405 LV-SYS-009 with the standard body; malformed JSON without `detail`; a request-value refusal names the field.
- `LvErrorCodesTest`: INT-035, SYS-008/PRC-007, SYS-009, database/queue by type, AI-006, LIC-001.
- `TenantLicensingServiceTest`: empty token gives LIC-001.
- `BridgeLvCodesTest`: SCN-019/020/021 and the two corrections.
- `PlatformHealthFailureLoggingTest`: SYS-006 logged and on the failed item only.
- Frontend: `lvCodes.test.ts` (137 codes, the new ones), `platformRules.test.ts` ("Down · LV-SYS-006"), and `errorStates.spec.ts`. The last checks that the sign-in name is under the title and appears in no console line and no `/api/` request.

Existing tests changed on purpose:
- `LvErrorCodeRegistryTest` and `LvErrorCodeGuideTest`: 137 codes, two HTTP statuses, the reviewed line.
- `LvErrorCodesTest`: a non-case not-found is now SYS-008, not SYS-001.
- `BridgeLvCodesTest`: acceptance pending is SCN-021, not SCN-015; #92 and the restart are no longer on the "intentional SYS-001" list.
- `errorStates.spec.ts` and `signinRace.spec.ts`: the new no-access wording.

## Files changed

47 modified, 4 new (`git diff --shortstat`: 728 insertions, 134 deletions).
- New: `StartupFailureLogger`, `PdfUnreadableException`, `LvErrorCodeFollowupsTest`, `e2e-a11y/proof0026.spec.ts`. The proof spec runs only with `P0026_PROOF` set, so normal counts are unchanged; I suggest committing it.
- Registry, mapper and exceptions: `LvErrorCode`, `LvErrorCodes`, `NotFoundException`, `ValidationException`, `IntakeValidationException`, `ApiErrorResponse`, `GlobalExceptionHandler`.
- Throw sites: `BusinessIdentityService`, `PackageLifecycleService`, `SupportIncidentActionService`, `PackageDashboardVisibilityService`, `PdfBoxReviewPageRenderAdapter`, `TextExtractionService`, `DocumentRedactionService`, `TenantRoutingSensitiveContentAnalysisPort`, `TenantLicensingService`, `LicensingController`, the two extraction adapters.
- Logging: `SecureDocumentPlatformApplication`, `OllamaModelPreloader`, `ExternalIntakeExpiryScheduler`, `DatabasePackageProcessingDispatchLease`, `PackageProcessingRecoveryScheduler`, `InProcessPackageProcessingDispatcher`, `PlatformHealthService`, `PlatformHealthItem`.
- Bridge: `BridgeLvCode`, `BridgeLvCodes`.
- Frontend: `NoAccess.tsx`, `HealthArea.tsx`, `platformRules.ts`, `types/index.ts`, `lvErrorCodes.generated.ts`.
- Docs: `docs/api/error-codes.md`, `docs/api/EXTERNAL_INGESTION_V1.md`; the 0025 report placeholders.

Proof pictures (not committed) in `docs/work/compare/0026/`:
- `no-access-signed-in-as.png` and `health-failed-check.png`: mocked harness, no backend.
- `404-unknown-endpoint.png`: the body that the real `GlobalExceptionHandler` wrote through MockMvc dispatch in `ClientMistakeResponsesTest`. The JSON is saved next to it, with `405-wrong-method.json`. I didn't start the app against the real database for this. In that test setup the timestamp is printed as a number; the running app uses Spring Boot's JSON settings and writes ISO-8601.

## Commits

- 0883006 `feat: 0025 LexaVault error codes (LV-<AREA>-<NNN>) in API, audit, screens and guide`
- d27b8c9 `docs(work): error code additions; task 0026`
- 230c752 merge of 0883006 into `feature/lexavault-r1b-tenant-identity`
- The 0026 work: **not committed**, waiting for the owner's review of the proof pictures. Nothing pushed.

## Not done, and why

- The R1B merge is of 0883006, not d27b8c9 (see step 0.4).
- HTTP statuses are unchanged, as in 0025. LV-CFG-011 is listed as 409 in the design, but its refusals still answer 400, because they are `ValidationException`s and the task allows only codes, words and logging to change.
- The generated frontend file keeps one `httpStatus` per code (404 for LV-SYS-009); the backend registry and the guide hold both statuses.

## Questions for the owner

1. Commit the 0026 work on `feature/0026-error-code-followups` as one commit (with this report and `e2e-a11y/proof0026.spec.ts`), then merge into R1B?
2. For the design chat:
   - The legacy vault routes are reachable in LOCAL mode (table above). Give them codes, or leave them?
   - LV-CFG-011's design status is 409, but it answers 400: change the design, or the status?

## Suggested next step

Owner: look at the three proof pictures and answer question 1. Design chat: the two points in question 2.
