# Report 0039: Follow-ups to the case limits and Support position

Date: 2026-11-22
Branch: `feature/0039-limits-followups`
Outcome: DONE

## What was done

### 1. Page number for text-reading failures (V1 processing, owner-approved)

- `TechnicalDependencyException` can carry a **page number** (an integer from 1; anything else is dropped). It is kept through retries and the other copies (`withAttempts`, `withLvCode`, and so on).
- Where the page is set:
  - `TesseractOcrAdapter`: every failure (timeout, non-zero exit, missing output, interrupted, can't start) carries the page it was reading.
  - `HybridPdfTextExtractionAdapter`: any text-reading failure on a page is marked with that page if it has none yet. This covers "page unreadable" (LV-PRC-002), "too poor to read" (LV-PRC-003) and "could not render" (LV-PRC-006).
- `ProcessingIncidentService` writes it to the incident metadata as `page` (digits only). Support already shows it ("document 2 of 3, page 14", task 0038).
- Only the number. No text, image, file name or error output. The message, code, retryability and attempts are unchanged, so V1 behaviour is the same: same retries, same outcome, same codes.
- A failure not about one page (the whole PDF can't be opened, an unclassified error) records no page.

### 2. Integration source limits capped at the case limits

- `IntegrationConnection`: the most a source can be set to is now the `CaseLimits` values, **50 files per case and 500 pages per file**. These are also the new defaults (before: 100 files and 2,000 pages, with maximums of 1,000 and 10,000).
- **The API refuses a higher value** with **LV-CFG-001** "Setting not valid", which already exists with "what to do" and is what these range checks already returned. The message says why:
  - "Maximum documents per intake must be between 1 and 50: a case holds up to 50 files"
  - "Maximum PDF pages must be between 1 and 500: a case takes files of up to 500 pages"
  - No new code, so no registry change.
- **Stored sources with higher values, without a migration:**
  - The record reads a stored value above the cap as the cap. The case limits already won at intake, so nothing changes in what is taken in.
  - The API and screens show the capped value. Saving the source again stores the capped value.
  - Only saving a new value above the cap is refused; loading, switching on and off, and saving without touching the limits are not.
- **Intake order:** push, scanner and polling now check the case limit before the source's own limit. A stored source read as 50/500 therefore still gets LV-INT-029 / LV-INT-028 for 51 files / 501 pages, as in 0038, and not LV-INT-010 / LV-INT-009. A source set lower (for example 10 files) still gets its own codes.
- **Scanner metadata:** the bound on the page count a scanner reports (LV-SCN-003) stays at 10,000, as before. It used the old source constant. A scan over 500 pages is refused when its pages are counted (LV-INT-028), as in 0038.
- **Local demo:** the demo scanner source that `LocalDemoBootstrap` creates used 10,000 pages and would now be refused on a fresh local start, so it uses the cap.
- **Integrations screen** (Admin › Integrations, and the older `/integrations` page):
  - "Files per case, at most" stops at 50 and says "A case holds up to 50 files."
  - "Pages per file, at most" stops at 500 and says "A case takes PDFs of up to 500 pages."
  - A higher value is marked at once ("Enter a value from 1 to 500.").
  - A stored higher value opens as the cap.
  - The scanner keeps "A scanner sends one PDF per case."
  - "Cases per collection" (polling, cases per run) is not a case limit and is unchanged.

### 3. Two uploads at the same moment can't exceed 50 files

- **Choice: a row lock on the case** (`SELECT … FOR UPDATE` on `document_packages`), not an advisory lock. Why:
  - It belongs to the transaction: released at commit or rollback, with nothing to unlock and no leak.
  - It checks the tenant in the same statement.
  - It needs no number derived from the case id. Advisory locks share one key space with the destination locks from 0036/0038.
  - The upload already writes the case row, so a second upload to the same case had to wait for that row anyway.
- `UploadDocumentService` locks the case **before it reads it**. The file count and the file added after it then see every upload that committed first. The lock is held until the upload's transaction ends.
  - New port method `PackageRepository.lockForUpdate` (default: does nothing, for in-memory copies).
  - JPA implementation in `JpaPackageRepositoryAdapter`, using a native `FOR UPDATE` query on `SpringDataDocumentPackageRepository`.
- **Where it applies:** only inside a read-write transaction.
  - Manual upload and push/scanner intake run in one, so they are covered.
  - The scheduled watched folder and polling run without one. Opening a transaction for them would change what their failures roll back (frozen V1 behaviour), so they are unchanged. Their cases are made by that source, and the file count is checked before the case is made (0038).
- **Proof** (PostgreSQL, real JPA adapters, real upload service, each upload in its own transaction as the endpoint runs it, own database per class):
  - 12 uploads at once to a case at 49: exactly one adds the 50th file, and 11 get LV-INT-029. Repeated 3 times; the case ends with 50 files each time.
  - Uploads at once to different cases don't wait for each other; all succeed.
  - The lock is held until commit and released after.
  - Without a transaction the lock does nothing and doesn't fail.
- **Lock switched off (scratch copy only, never committed):** 4 of 6 tests fail. The extra uploads end in an optimistic-locking error (a technical failure, not LV-INT-029), and the case row is not held. With the lock: 6 of 6 pass.

**Independence rule:** this task adds no other product's name or behaviour to code. Test destinations and sources use neutral names ("Records archive", "Loan origination system").

## Commands run and results

| Command | Result |
|---|---|
| Throwaway database container `lexavault-0039-pg` (postgres:16-alpine, 127.0.0.1:55449; password generated into the session scratchpad, never printed) | used for every PostgreSQL run, then removed |
| `mvn -o clean verify` with `LEXAVAULT_TEST_PG_*` set (PostgreSQL tests on), runs 1 and 2 | FAILED: old fixtures used 100 files / 2,000 pages (now refused) and two 0038 tests built sources above the cap. Fixtures updated (see "Tests changed") |
| Same, run 3 (final code) | **BUILD SUCCESS, 0 failures** |
| `UploadCaseLockPostgresTest` with the lock switched off (scratch copy) | 4 of 6 fail, as it should |
| `test-tools\no-redacted-values\Invoke-NoRedactedValueCheck.ps1 -JarPath <build>` | PASS, **0 matches**; its container removed |
| `test-tools\compliance-report\Invoke-ComplianceReportCheck.ps1 -JarPath <build>` | PASS, 116 checks, 0 failed; its container removed |
| `scripts\Test-LexaVault-ExportE2E.ps1 -JarPath <build> -ProofDir …` | PASS, 8 passed; its container removed |
| `npm run test:standalone` (run suite by suite) | PASS: auth 10, vocabulary 188, ui-framework 17, oidc-signin 165, tablet 95, a11y 156 |
| Playwright with `P0039_PROOF=1` (part of oidc-signin) | proof picture written |

Notes:
- The owner's app was running from this checkout's `target` jars. As in 0038, every backend build ran in a scratch git worktree with the same changes, and the jar for the checks came from there. The running app was not touched.
- `-o` (offline): every dependency was already in the local Maven repository.

## Tests: counts per module, and any failures with their output

Backend, final build (PostgreSQL on): domain 79, application 294, scan bridge 136, infrastructure 70, API 535. 0 failures.

New tests:
- Domain, `IntegrationConnectionTest` (+3):
  - the limits are the case limits and the defaults;
  - saving above them is refused with the reason (create and update);
  - a stored source above the cap still loads, reads as the cap and saves again.
- Application:
  - `ProcessingIncidentServiceTest` (+2): a failing page records only its number with the code unchanged; a non-page failure records none.
  - `IntegrationConnectionServiceTest` (+1): above the cap is LV-CFG-001 on create, update and draft test.
  - `ExternalIntakeServiceTest` (+1): a source set lower keeps LV-INT-010 / LV-INT-009.
- API:
  - `HybridPdfOcrFailurePageTest` (5): a timeout on page 2 of 3 carries page 2 after the same 3 attempts; an unreadable page 3 carries page 3 with LV-PRC-002; a page the provider set is kept; a non-PDF failure has no page; all pages read means no change.
  - `TesseractOcrAdapterCleanupTest` (+1): the failure carries page 7 and the same message.
  - `UploadCaseLockPostgresTest` (6, PostgreSQL).
- Frontend:
  - `integrationRules.test.ts` (+2).
  - `adminIntegrations.spec.ts` (+1): fields stop at 50/500 with the reasons; a stored 1,000/2,000 opens and saves as 50/500; 600 is marked.

Tests changed, and why:
- Fixtures that saved a source with 100 files / 2,000 pages (or 10,000 pages for the scanner) now use 50 / 500. Those values are now refused, and none of these tests is about the limits. Files: `IntegrationConnectionTest`, `IntegrationConnectionServiceTest`, `DraftConnectionTestServiceTest`, `PollingScheduleCalculatorTest`, `LocalDemoBootstrapScannerIntegrationTest`.
- `ExternalIntakeServiceTest` (0038's two "source allows more" tests): such a source can no longer be saved, so the harness builds it as a row stored before the cap. The assertions are unchanged (LV-INT-029, LV-INT-028).
- `integrationRules.test.ts`: the summary with nothing saved now reads "up to 50 files, 50 MB, 500 pages" (was 100 and 2000).


## Files changed

- **Item 1:**
  - `TechnicalDependencyException`
  - `TechnicalFailureClassifier` (`page(...)`)
  - `ProcessingIncidentService`
  - `OcrProcessingException`
  - `TesseractOcrAdapter`
  - `HybridPdfTextExtractionAdapter`
- **Item 2:**
  - backend: `IntegrationConnection`, `ExternalIntakeService`, `LocalDemoBootstrap`
  - frontend: `integrationRules.ts`, `IntegrationsArea.tsx`, `pages/IntegrationsPage.tsx`
- **Item 3:**
  - `PackageRepository` (`lockForUpdate`)
  - `JpaPackageRepositoryAdapter`
  - `SpringDataDocumentPackageRepository`
  - `UploadDocumentService`
- **Tests:** as listed above.
- **Proof:** `docs/work/0039-proof/`
  - `integrations-capped-fields.png`: a source stored at 1,000 / 2,000 opens as 50 / 500, with the reasons under the fields.
  - `export-e2e/`: the export run's own pictures.

No migration. No new dependency. Nothing in identity, sign-in, setup or platform-core.

## Commits

- `29108d2` feat: task 0039, OCR failure page number, source limits capped at the case limits, case lock on upload (with this report and the proof pictures).

Not merged, not pushed.

## Not done, and why

Nothing in the task is left. One limit of item 3, stated above: the case lock is taken only where the upload runs in a transaction (manual upload, push, scanner). The scheduled watched folder and polling run without one and are unchanged.

## Questions for the owner

1. **Watched folder and polling without a transaction:** leave them as they are (recommended), or wrap each of their uploads in its own transaction so they take the case lock too? That would also change what their failures roll back (frozen V1 behaviour).
2. **Defaults lowered:** a new connection now starts at 50 files and 500 pages (was 100 and 2,000). This follows from the cap. Please confirm.

## Suggested next step

The owner reviews `integrations-capped-fields.png` and answers questions 1 and 2. Then merge `feature/0039-limits-followups` into `feature/lexavault-r1b-tenant-identity`.
