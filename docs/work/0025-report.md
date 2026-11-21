# Report 0025: Put the LexaVault error codes into the product

Date: 2026-11-21
Branch: `feature/0025-error-codes` (from `feature/0024-scan-bridge-retries` at 404a2ea)
Outcome: DONE (not committed; see "Commits")

## What was done

**Step 0.** `docs/work/0024-report.md` (with the owner's answers), the error code design and this task file were committed on `feature/0024-scan-bridge-retries` as 404a2ea. The merge into `feature/lexavault-r1b-tenant-identity` was first blocked by the session's permission check; I branched from the 0024 tip as the task says. After the owner approved it in the session, it was done in a temporary worktree: **8f68abf** (`--no-ff`, standard message; it adds only the three doc files, because R1B already held the earlier 0024 merge 86b7c78). Merged R1B built and tested: 842 backend tests, 0 failures. Not pushed. `feature/0025-error-codes` starts at 404a2ea, not at 8f68abf; the code underneath is the same.

**1. One list.** `LvErrorCode` (domain, `domain/error/`) holds every code: code, area, title, short description, kind, who acts, HTTP status, reserved. It was generated from the design and `LvErrorCodeRegistryTest` compares every field with `docs/work/lv-error-codes.md`, so they cannot drift. The three reserved codes are marked and given to no failure. **The design's tables hold 129 codes, not 124**: the per-area totals are right and add up to 129; the "124 codes" line is a sum error.

**2. Mapping, in one place.** `LvErrorCodes` (application, `application/error/`) maps every `TechnicalFailureCode`, `IntakeRejectionCode`, `LicenseValidationStatus`, polling category, licence code, sign-in admission reason (LexaVault's and every Enterprise Identity `IdentityAdmissionException.Reason`), API `errorCode`, routing/derived-delivery code and connection-test reason. Anything else is `LV-SYS-001`, logged as `LV_CODE_UNMAPPED context=… type=…` (type or identifier only, never a message). Failures can carry their own code: `ApplicationException`, `ValidationException`, `AccessDeniedException`, `TechnicalDependencyException` (`withLvCode`, for one internal code that covers several causes, e.g. `DELIVERY_IO`), and two new domain exceptions `CodedStateException` / `CodedArgumentException` (subclasses of `IllegalStateException` / `IllegalArgumentException`, so every existing `catch` behaves as before). The internal identifier is kept as the detail everywhere. About 230 throw sites were given their code from the inventory rows. The Scan Bridge has its own small table (`BridgeLvCode`, 22 codes) and mapping (`BridgeLvCodes`), checked against the design by its own test, because the bridge must not get a new dependency (not even on the domain module).

**3. Where the codes show.**
- API error body: `{errorCode, code, message, correlationId, timestamp}`; `message` is the code's title, for `LV-CFG-001` followed by LexaVault's own field-and-range text. HTTP statuses unchanged. Also the 401 body (`LV-AUT-001`) and the polling "already running" 409, which now uses the standard body (`errorCode=POLL_ALREADY_RUNNING`, `code=LV-INT-044`; it used the key `code` for the internal value before, #9). Scan Bridge body: `{errorCode, code, message}`.
- Audit: every refusal, stop and export failure records `code=LV-…, detail=<internal>` first (stops: `"<prefix>: code=LV-…, detail=<TechnicalFailureCode>, dependency=…, retryable=…, attempts=…"`; the case panel parser reads this form and the old one). Covered: processing/redaction/delivery stops, export failures, recovery exhausted, licence denials and install refusals, AUTHORIZATION_FAILED (interceptor and recorder), sign-in refusals, watched-folder rejections/duplicates/waits, polling packages and runs, connection tests, derived delivery and routing attention, intake cleanup failure (`LV-SYS-007`), watched-folder scan failure, login-settings activation/rollback, compliance report refusals. Codes only, no content.
- Incidents store their precise code in `safe_metadata.lvCode` (no schema change); Support list and detail and the case panel data return `lvCode`.
- Screens: Support list and Problem page, case panel problem text, toasts and admin messages show "Title · LV-XXX-NNN" with the internal code under Technical details; the no-access screen shows only "No access to LexaVault · LV-AUT-002" (or the code the server sent). Words come from `frontend/securedoc-ui/src/studio/lvErrorCodes.generated.ts`, generated from the registry and the mapper and checked by `LvErrorCodeFrontendFileTest`. The hand-written `PROBLEM_REASONS` (including `MODEL_TIMEOUT` and `SDV_UNAVAILABLE`) is gone. Integrations, polling runs, watched-folder observations and Scan Bridge items show the LV code (the bridge items get `lastDeliveryLvCode`).

**4. Customer guide.** `docs/api/error-codes.md`, marked "Draft: steps to be reviewed by the design chat": all 129 codes in area order with title, meaning, kind, who acts, HTTP status and 2 to 4 steps. `LvErrorCodeGuideTest` checks it against the registry both ways (and order, titles, fields, steps, draft line).

## Commands run and results

| Command | Result |
|---|---|
| `git commit` on `feature/0024-scan-bridge-retries` | 404a2ea `docs(work): error code design; task 0025` |
| `git worktree add ..\r1b-merge-0025 feature/lexavault-r1b-tenant-identity; git merge --no-ff --no-edit feature/0024-scan-bridge-retries` | 8f68abf (after owner approval in the session; first attempt blocked by the permission check) |
| `cd backend; mvn -o clean verify` on merged R1B | BUILD SUCCESS, 842 tests, 0 failures |
| `cd backend; mvn -o clean install` on `feature/0025-error-codes` | BUILD SUCCESS, 913 tests, 0 failures |
| `npm run test:standalone` (frontend) | PASS, exit 0, 0 failures (run 2026-11-21 in task 0026 step 0, before the commit) |
| `.\test-tools\no-redacted-values\Invoke-NoRedactedValueCheck.ps1` | PASS: 0 matches in the database, 3 logs and 2 audit exports; throwaway container `lexavault-0015-pg` removed |
| `.\test-tools\compliance-report\Invoke-ComplianceReportCheck.ps1` | PASS: 116 checks, 0 failed; throwaway container `lexavault-0017-pg` removed (first run: 3 failed, see below) |

## Tests: counts per module, and any failures with their output

| Module | Before (R1B after merge) | Now |
|---|---|---|
| Domain | 62 | 74 |
| Application | 262 | 274 |
| Scan Bridge | 96 | 134 |
| Infrastructure | 28 | 28 |
| API | 394 | 403 |
| **Backend total** | **842** | **913** |

Frontend (`npm run test:standalone`, 0 failures): `test:auth` 10, `test:vocabulary` 156, `test:ui-framework` 17, `test:oidc-signin` 137, `test:tablet` 85, `test:a11y` 138.

New tests: `LvErrorCodeRegistryTest` (registry = design), `LvErrorCodeGuideTest` (guide = registry), `LvErrorCodesTest` (every enum value mapped, fallback, own code first, precise codes, audit format, a sample per area), `LvErrorCodeFrontendFileTest` (generated file current), `LvErrorCodeApiBodyTest` (API body per area: `code`, `message`, `errorCode`, status; every Enterprise Identity admission reason maps to an AUT code); Scan Bridge `BridgeLvCodeDocumentTest`, `BridgeLvCodesTest` (every code literal in the bridge source has a mapping), `ScanBridgeExceptionHandlerTest`, `ScannerDeliveryLvCodeTest`; frontend `lvCodes.test.ts` and updated vocabulary tests.

**Existing tests changed, and why** (the task changes these messages and details on purpose; no test was changed to hide a fault):
- Application: `ComplianceReportServiceTest`, `PackageProcessingRecoveryServiceTest`, `WatchedFolderIntakeServiceRecoveryTest`: audit detail now starts with `code=LV-…, detail=…`.
- API: `ClientMistakeResponsesTest`, `GlobalExceptionHandlerSafeMessagesTest`, `ReviewAlreadyDecidedHandlerTest`, `BearerTokenRefusalEntryPointTest`: `message` is now the title (for example "Required header or part missing" instead of naming the header). `ComplianceReportApiTest`, `OidcTenantDiscoveryControllerTest`, `WatchedFolderSchedulerFailureTest`, `AccessDeniedAuditRecorderTest`, `CapabilityAuthorizationInterceptorTest`, `PollingIntakeServiceBehaviorTest`, `DocumentRedactionServiceSafetyCodeTest`, `UnlocatedFindingCreatesIncidentTest`, `DerivedRoutingFailureRecordingTest`: audit detail format. `GlobalExceptionHandlerSafeMessagesTest` also: the logged type of a domain state refusal is now `CodedStateException`.
- Frontend: the vocabulary, support, integration and e2e-oidc tests that asserted the old words (see "Files changed").
- Test tool: `test-tools/compliance-report/Invoke-ComplianceReportCheck.ps1`, 3 expected audit strings (refused export, tenant-boundary 403, failed report). First run failed exactly on those 3 with the new `code=…` prefix; after the update 116/116.

## Files changed

147 files modified, 16 new paths (`git diff --shortstat`: 1450 insertions, 759 deletions). Main groups:
- New: `backend/securedoc-domain/.../domain/error/` (`LvErrorCode`, `LvCoded`, `CodedStateException`, `CodedArgumentException`), `backend/securedoc-application/.../application/error/LvErrorCodes.java`, `RedactionEngineException` (infrastructure, `LV-RED-003`), `BridgeLvCode` / `BridgeLvCodes`, `docs/api/error-codes.md`, `frontend/securedoc-ui/src/studio/lvCodes.ts`, `lvErrorCodes.generated.ts`, `e2e-a11y/proof0025.spec.ts` (makes the proof pictures, mocked API; suggest committing it), plus the new tests above.
- Exception plumbing and handler: `ApplicationException`, `ValidationException`, `AccessDeniedException`, `TenantBoundaryDeniedException`, `NotFoundException`, `PersonFacingStateException`, `TechnicalDependencyException`, `OcrProcessingException`, `GlobalExceptionHandler`, `ApiErrorResponse`, `BearerTokenRefusalEntryPoint`, `PollingIntegrationController`.
- Throw sites (code only): intake, scanner, review, manual fallback, finalisation, export, routing, integrations, admin settings, branding, compliance, licensing; domain `DocumentPackage`, `ExternalIntake`, `Finding`, `FindingLocation`, `ReviewTask`; infrastructure SDV, derived and local delivery adapters (precise DLV codes), hybrid OCR (PRC-002/003/006), identity grant and SDV-count rules.
- Audit details: the services listed under step 3, `TechnicalFailureClassifier`, `TechnicalFailureAuditParser`, `IntakeFailure`.
- Incidents: `ProcessingIncidentService`, `SupportIncidentSummary`, `SupportIncidentDetail`, `JdbcSupportIncidentSearchRepository` (reads `safe_metadata ->> 'lvCode'`), `TechnicalExceptionDetail`, `TechnicalExceptionQueryService`.
- Scan Bridge: body, filter, handler, session, delivery client, worker, delivery item response.
- Frontend: `data.ts`, `client.ts`/types, `words.ts`, Support list and rules, `CaseDrawer.tsx`, `NoAccess.tsx`, `App.tsx`, `failure.tsx`, integrations rules and area, health area, scan bridge client and page, `package.json` (new test file in `test:vocabulary`).

Proof pictures (not committed) in `docs/work/compare/0025/`: `support-problem.png`, `case-panel-problem.png`, `toast-with-code.png`, `no-access.png`, `admin-cfg-001.png`. Made with Playwright against the frontend with a mocked API (no backend, no real data).

## Mapping table (internal identifier → LV code)

The full table is `LvErrorCodes` (backend) and `LV_BY_DETAIL` in the generated frontend file; the main lines:

| Internal identifier | LV code |
|---|---|
| AI_UNAVAILABLE / AI_TIMEOUT / AI_RATE_LIMITED / AI_REJECTED / AI_INVALID_RESPONSE | AI-001 / AI-002 / AI-003 / AI-004 / AI-005 |
| OCR_TIMEOUT / OCR_UNAVAILABLE / OCR_PROCESS_FAILED | PRC-004 / PRC-005 / PRC-005 (page causes: no text PRC-002, low confidence PRC-003, render PRC-006) |
| PROCESSING_DISPATCH_EXHAUSTED, PROCESSING_RECOVERY_EXHAUSTED | PRC-008 |
| PROCESSING_LICENSE_DENIED | LIC-001 by default, the licence status's code when known |
| REDACTION_LOCATION_MISSING / REDACTION_* verification (8 values) / engine failure | RED-001 / RED-002 / RED-003 |
| DELIVERY_IO | DLV-001; with the destination's answer: 5xx/429 DLV-002, unreadable or missing receipt DLV-007, not complete DLV-008 |
| DELIVERY_DESTINATION_INVALID | DLV-006; 4xx DLV-004, 401/403 and secret/auth set-up DLV-005, export folder exists DLV-010 |
| DELIVERY_SENSITIVITY_REFUSED | DLV-003 |
| STORAGE_IO / UNKNOWN_TECHNICAL_FAILURE | SYS-002 / SYS-001 |
| IntakeRejectionCode: IDENTIFIER_CONFIGURATION_* / MISSING_REQUIRED_IDENTIFIER / UNKNOWN_IDENTIFIER / INVALID_IDENTIFIER_VALUE / IDENTIFIER_CONFLICT | CFG-002 / INT-013 / INT-014 / INT-015 / INT-016 |
| MALFORMED_TRIGGER / MISSING_FILE / NO_DOCUMENTS / UNSUPPORTED_FILE, INVALID_FILE / DUPLICATE_INTAKE / SOURCE_IO_ERROR | INT-031 / INT-032 / INT-033 / INT-007 / INT-017 / INT-034 |
| LICENSE_DENIED / CAPABILITY_DISABLED / TENANT_INACTIVE / STORAGE_UNAVAILABLE / ACCESS_DENIED / RECOVERY_CONFLICT, INTERNAL_ERROR | LIC-001 / AUT-010 / AUT-005 / SYS-002 / CFG-007 / SYS-001 |
| WAITING_FOR_TRIGGER | INT-030 |
| Polling: POLL_SOURCE_UNAVAILABLE, POLL_INTERRUPTED / POLL_DISCOVERY_FAILED, RETRIEVAL_FAILED / POLL_RESPONSE_INVALID / RETRIEVAL_INVALID_CONTENT_TYPE / RETRIEVAL_STAGING_FAILED / SOURCE_ACKNOWLEDGEMENT_FAILED, ACK_SERIALIZATION_FAILED / POLL_ALREADY_RUNNING / POLL_*CONFIGURATION* | INT-040 / INT-041 / INT-042 / INT-007 / INT-004 / INT-043 / INT-044 / CFG-003 |
| Intake conflicts: SOURCE_REVISION_REUSED / RETRY_SOURCE_NOT_FOUND / SOURCE_DOCUMENT_ID_REUSED / INTAKE_ALREADY_COMPLETE, INVALID_INTAKE_STATE / MANIFEST_CHECKSUM_MISMATCH / CONTENT_CHECKSUM_MISMATCH / IDEMPOTENCY_CONFLICT / INTAKE_EXPIRED / SCANNER_SOURCE_SIZE_MISMATCH | INT-018 / INT-024 / INT-019 / INT-022 / INT-011 / INT-012 / INT-021 / INT-023 / SCN-002 |
| API: INVALID_REQUEST_BODY / MISSING_HEADER, MISSING_FILE_PART, INVALID_MULTIPART_REQUEST, UNSUPPORTED_MEDIA_TYPE / MISSING_PARAMETER, INVALID_REQUEST, bean validation / FILE_TOO_LARGE / INVALID_ARGUMENT / AUTHENTICATION_REQUIRED / ACCESS_DENIED / CAPABILITY_DISABLED / TENANT_INACTIVE / REVIEW_ALREADY_DECIDED / INTERNAL_ERROR / NOT_FOUND (a case) | INT-001 / INT-002 / INT-003 / INT-004 / CFG-001 (INT-003 on intake routes) / AUT-001 / AUT-006 / AUT-010 / AUT-005 / REV-001 / SYS-001 / INT-025 |
| Licence: LICENSE_REQUIRED / EXPIRED, NOT_YET_VALID / TENANT_MISMATCH, INVALID_SIGNATURE, UNSUPPORTED_SCHEMA, MALFORMED, CONFIGURATION_ERROR / NOT_PERMITTED | LIC-001 / LIC-002 / LIC-003 / LIC-004 |
| Sign-in: NO_MAPPED_ROLE and EI NOT_ENABLED, NO_VERIFIED_MEMBERSHIP, MEMBERSHIP_NOT_BACKED, NO_ROLE_FOR_ENTITY, UNKNOWN_ROLE, INVALID_ROLE_GRANT, PLATFORM_ROLE_IN_ENTITY_GRANT, GROUPS_NOT_A_COLLECTION, NO_ADMISSION_POLICY | AUT-002 |
| Sign-in: NOT_AUTHORIZED_FOR_ENTITY, INVALID_SELECTION / AMBIGUOUS_SELECTION / TENANT_NOT_ACTIVE, TENANT_MISSING / MACHINE_BINDING_NOT_UNIQUE | AUT-003 / AUT-004 / AUT-005 / AUT-008 |
| Sign-in set-up reasons (connection lookup, no active connection, claims, issuer/subject, discovery, shared-issuer, local headers, CONTEXT/POLICY_UNAVAILABLE) | AUT-007 |
| Routing: ROUTING_MARKER_AMBIGUOUS / ROUTING_DESTINATION_UNAVAILABLE, DERIVED_DESTINATION_* / ROUTING_DETECTION_FAILED, DERIVED_DOCUMENT_BUILD_FAILED, ROUTING_FAILED, DERIVED_ROUTING_POST_EXPORT_FAILED / ROUTING_MASTER_UNAVAILABLE, DERIVED_ARTIFACT_UNAVAILABLE, DERIVED_SOURCE_NOT_FOUND / DERIVED_DELIVERY_RUNTIME, DELIVERY_RUNTIME_ERROR | DLV-012 / DLV-006 / DLV-013 / DLV-011 / SYS-001 |
| Connection tests: ADDRESS_NOT_ALLOWED, CONNECTION_INVALID / ADDRESS_NOT_RESOLVED, *_TEST_FAILED, SDV_TEST_UNAVAILABLE | CFG-003 / CFG-004 |
| Scan Bridge (in `BridgeLvCodes`): EMPTY_SCAN, INVALID_PDF ... / SCAN_PDF_TOO_LARGE, SCANNER_STORAGE_UNAVAILABLE / SCANNER_BUSY, SCANNER_INBOX_* / BRIDGE_DISABLED, *_NOT_ENABLED / PHYSICAL_SCANNER_* availability / physical scan job failures / session and readiness codes / BRIDGE_ORIGIN_NOT_ALLOWED, BRIDGE_REQUEST_TOKEN_INVALID / BRIDGE_LOCAL_STATE_FAILURE / LEXAVAULT_ENDPOINT_NOT_CONFIGURED / LexaVault unreachable or 5xx / LexaVault refusal / LEXAVAULT_ACCEPTANCE_WAIT_EXPIRED / retries used up / INVALID_BUSINESS_IDENTIFIER_INPUT / scanner settings | SCN-004 / SCN-005 / SCN-006 / SCN-007 / SCN-008 / SCN-009 / SCN-010 / SCN-012 / SCN-013 / SCN-014 / SCN-015 / SCN-016 / SCN-017 / SCN-018 / INT-015 / CFG-009 |

Settings refusals in admin screens (business admin, policy, branding, dashboard columns, document intelligence, storage, audit filters, compliance period, lifecycle hold hours) are `LV-CFG-001`; integration connection settings `LV-CFG-001`/`003`/`005`; split rules `LV-CFG-006`; login-settings refusals `LV-CFG-008`; disabled polling or SDV source `LV-CFG-010`; watched folder not set up `LV-CFG-007`. Review and manual-processing refusals use REV-002 to REV-008 per the inventory rows.

## Failures I could not place (they give LV-SYS-001, logged as a gap)

- Not in the inventory, so no code: the legacy vault services (`VaultService` 13 refusals, `ArtifactAccessService` 3), technical hold extension and Technical Attention restart (`PackageLifecycleService` 3), removing a case from the Dashboard (`PackageDashboardVisibilityService`), a historical/closed support incident (`SupportIncidentActionService`), review page out of range (`PdfBoxReviewPageRenderAdapter` 2).
- `IntakeRejectionCode.RECOVERY_CONFLICT` (a resumed drop no longer matches its case): no code fits.
- `NotFoundException` for anything that is not a case (routing rule, provider, artifact): only "Case not found" exists. Rows #355/#363 (original missing, `LV-PRC-007`) arrive as such a NotFound and show `LV-SYS-001`.
- Unknown endpoint (404) and wrong HTTP method (405).
- Domain state rules not named by the inventory (for example package processing transitions) keep their exception type and give `LV-SYS-001` if they ever reach the API.
- Scan Bridge: `SUBMISSION_ALREADY_QUEUED`, `ATTENTION_REQUIRED_CANNOT_CANCEL` (#92), `RECOVERED_AFTER_RESTART`.
- Best fits I chose where the design lists no row (please confirm): #152 and "package has no documents" at finalise → REV-002; #161 open task exists → REV-002; #183 send while technical attention is required → DLV-014; #193 retry of a case that is not failed → REV-002, original gone → REV-007; #149 region not active → REV-006; #175 derived delivery id → CFG-001; #32 null values in a request → INT-001; #55 bad file name or MIME on manual upload → INT-003; compliance report period → CFG-001; licence token required (#287) → LIC-003; `IntakeRejectionCode.ACCESS_DENIED` → CFG-007.

## Codes no failure uses yet (besides the reserved ones)

- `LV-SCN-001`, `LV-SCN-002`, `LV-SCN-003` are given by LexaVault only (scanner intake refusals); the bridge shows them inside `LV-SCN-016`. `LV-SCN-011` is shown only by the browser (frontend), never sent by a server.
- `LV-PRC-001` (PDF can't be opened during processing, #113) and `LV-PRC-007` (original missing, #355, #363): those failures still arrive as unclassified exceptions and give `LV-SYS-001`.
- `LV-AI-006` (no AI settings for the entity, #135): the adapter throws `AI_UNAVAILABLE`, so it shows `LV-AI-001`. `LV-AI-007` (model preload at start) is a start-up log only.
- `LV-AUT-009` (browser sign-in didn't complete) is frontend only.
- `LV-SYS-003` (database), `LV-SYS-004` (queue full), `LV-SYS-005` (start-up setting), `LV-SYS-006` (health check failed): no failure is given them yet (start-up and health paths log only).
- `LV-INT-005` is unchanged (intake warning, already in use).

## Not done, and why

- Polling runs, watched-folder observations and derived deliveries do not carry a new `lvCode` field in their API responses; the frontend derives the code from their internal code through the generated file (same result, except where a precise code is stored only on the incident).
- The no-access screen no longer shows the signed-in email; the frontend change dropped it. The task asked only for the title and code; tell me if the email should come back.
- The toast picture caught the toast while it was fading in, so it is faint.
- The API `message` is now the title only, so source systems lose the field-level text they had before (for example which header is missing); `errorCode` and the logs still tell IT. That is what the design asks; mentioned because integrators will notice.

## Questions for the owner

1. Commit `feature/0025-error-codes` as it is (one commit, report included), and commit the proof-picture script `e2e-a11y/proof0025.spec.ts`?
2. For the design chat: the "124 codes" total (should be 129), the unplaced failures and best fits above, the bridge mappings that read oddly (`SCANNER_INBOX_UNAVAILABLE` → SCN-006 "Scanner busy", `PHYSICAL_SCANNER_DEVICE_NOT_FOUND` → SCN-009 rather than SCN-008, `LEXAVAULT_ACCEPTANCE_PENDING` → SCN-015 "can't be reached" while only waiting), and the guide steps marked as unsure: LV-INT-016, INT-017, INT-031, SCN-001, SCN-013, SCN-017/018, PRC-003, AI-005, AI-006, RED-002, REV-001, REV-007, DLV-003/014 (name of the support override), DLV-010, DLV-012, LIC-001/002/003, CFG-009, SYS-001, SYS-007.

## Commits

- 404a2ea `docs(work): error code design; task 0025` (on `feature/0024-scan-bridge-retries`)
- 8f68abf merge of `feature/0024-scan-bridge-retries` into `feature/lexavault-r1b-tenant-identity`
- The 0025 work itself: not committed. Nothing pushed.

## Suggested next step

Owner: review the proof pictures and the guide draft, answer question 1; then the design chat reviews the guide steps and the unplaced list.

## Owner's answers (2026-11-21, recorded by the design chat)

1. **Commit 0025:** yes, as one commit with the report and `e2e-a11y/proof0025.spec.ts`, once `npm run test:standalone` has passed and its result and counts replace the placeholders in this report (task 0026, step 0).
2. **The 8 new codes** (LV-INT-035, LV-SCN-019, LV-SCN-020, LV-SCN-021, LV-REV-009, LV-CFG-011, LV-SYS-008, LV-SYS-009): approved. The design now holds 137 codes. The mapping corrections and best fits are as in task 0026.
3. **No-access screen:** bring back "Signed in as <email or user name>". It is shown only to that person, never logged or audited.
4. **API detail for integrators:** add an optional `detail` naming what is wrong (field, header or part, allowed range), never a value sent.
5. **Guide steps:** reviewed by the design chat; the four corrections are in task 0026, section 5.
