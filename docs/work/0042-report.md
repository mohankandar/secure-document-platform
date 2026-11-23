# Report 0042: LexaVault CI (GitHub Actions)

Date: 2026-11-23
Branch: `feature/0042-ci` (from the 0041 result, `6a33005`)
Outcome: **STOPPED: the product-name guard, written as the task specifies, fails on today's code (69 files), and making it pass needs product code changes this task does not allow, or an owner decision on what else to exclude.** Everything else in the task is built and proven locally. The queue stops here (queue rule 3): 0043 and 0044 were not started.

**No other product's name or behaviour in code** added by this task. Existing exceptions: `SdvDeliveryAdapter` (known, switch-over part 3) and the 69 files listed below, which the new guard finds.

## What was done
- `.github/workflows/ci.yml`, on push and pull request to `feature/lexavault-r1b-tenant-identity` and `main`:
  - `backend`: Temurin 21, `tesseract-ocr` (CI tooling for the OCR tests, not a product dependency), `settings.xml` written in the job naming `${env.PLATFORM_CORE_READ_TOKEN}` (the secret goes in the environment only, never echoed; a missing secret fails the job with a plain message), `mvn -B verify` with a PostgreSQL 16 service container and the `LEXAVAULT_TEST_PG_*` variables.
  - `frontend`: Node 22, `npm ci`, `npm run build` (typecheck + build), Playwright Chromium and WebKit, `npm run test:standalone`. All six standalone suites can run in CI: `test:auth`, `test:vocabulary`, `test:ui-framework`, `test:oidc-signin`, `test:tablet`, `test:a11y` (own Vite server, API and identity provider mocked). Need the local stack, not in CI: `test:e2e`, the export E2E.
  - `product-names`: `scripts/ci/check-product-names.sh`.
  - `ci-passed`: needs the three, the single required check.
  - Every action pinned to a full SHA with its version: checkout `3d3c42e…` v7.0.1, setup-java `d1374f8…` v6.3.0, setup-node `949feb2…` v7.1.0 (resolved with `git ls-remote` against the public action repositories).
- `scripts/ci/check-product-names.sh`: `Vero|SDV|secure-document-vault` in tracked `.java .kt .ts .tsx .js .mjs .cjs .sql .json .xml .yml .yaml .properties .ps1 .sh .cmd .csv .html .css .md` files. Excluded, and why: `docs/` (history: tasks, reports, designs); `CLAUDE.md` (states the rule and names the exception); `SdvDeliveryAdapter.java` and `SdvDeliveryAdapter*Test.java` (the known exception, removed in switch-over part 3); the script itself (it names what it looks for).
- `docs/install/ci.md`: what runs, how to run the same locally, the secret's name and scope.

## Commands run and results
| Command (as the workflow runs it) | Result |
|---|---|
| `mvn -B verify` in `backend/`, PostgreSQL tests on (throwaway `lexavault-0042-pg`, postgres:16-alpine, 127.0.0.1:55442, generated password not printed, removed) | **PASS**: domain 79, application 294, scan bridge 136, infrastructure 71, API 578; 0 failures, 0 skipped. platform-core 0.2.0 from `~/.m2` (downloaded from GitHub Packages in 0041) |
| `npm ci`, `npm run build` | PASS |
| `npm run test:standalone` | PASS on this same tree in 0041: auth 10, vocabulary 191, ui-framework 17, oidc-signin 166, tablet 95, a11y 156 (0042 changes no frontend file) |
| `bash scripts/ci/check-product-names.sh` | **FAIL**, exit 1: 69 files |

Local runs are on Windows; the CI runner is Linux. Not proven here: Linux-only differences (paths, fonts, Tesseract version). They show on the first CI run.

## Tests: counts per module, and any failures with their output
Backend and frontend as above. The guard's failure (count of matching lines per file):

| File | Lines |
|---|---|
| `backend/securedoc-application/src/test/java/com/securedoc/platform/application/integration/DraftConnectionTestServiceTest.java` | 16 |
| `frontend/securedoc-ui/src/pages/IntegrationsPage.tsx` | 14 |
| `frontend/securedoc-ui/src/studio/exportRules.test.ts` | 12 |
| `integration-reference/case-file/src/studio/admin/IntegrationsArea.tsx` | 11 |
| `frontend/securedoc-ui/case-file-next/src/studio/admin/IntegrationsArea.tsx` | 11 |
| `backend/securedoc-api/src/test/java/com/securedoc/platform/application/pipeline/ExportDestinationCodeTest.java` | 11 |
| `frontend/securedoc-ui/src/studio/admin/IntegrationsArea.tsx` | 8 |
| `backend/securedoc-domain/src/test/java/com/securedoc/platform/domain/integration/IntegrationConnectionTest.java` | 8 |
| `frontend/securedoc-ui/src/studio/admin/integrationRules.ts` | 7 |
| `frontend/securedoc-ui/e2e-oidc/adminIntegrations.spec.ts` | 7 |
| `backend/securedoc-domain/src/main/java/com/securedoc/platform/domain/integration/IntegrationConnection.java` | 7 |
| `frontend/securedoc-ui/src/studio/admin/integrationRules.test.ts` | 6 |
| `backend/securedoc-application/src/main/java/com/securedoc/platform/application/integration/SdvConnectionTestService.java` | 6 |
| `backend/securedoc-application/src/main/java/com/securedoc/platform/application/integration/DraftConnectionTestService.java` | 6 |
| `backend/securedoc-application/src/main/java/com/securedoc/platform/application/pipeline/ExportJobService.java` | 5 |
| `frontend/securedoc-ui/src/studio/words.test.ts` | 4 |
| `frontend/securedoc-ui/e2e-oidc/exportFixtures.ts` | 4 |
| `frontend/securedoc-ui/e2e-a11y/harness.ts` | 4 |
| `backend/securedoc-infrastructure/src/test/java/com/securedoc/platform/infrastructure/pipeline/TestRows.java` | 4 |
| `frontend/securedoc-ui/e2e-oidc/exports.spec.ts` | 3 |
| `backend/securedoc-application/src/main/java/com/securedoc/platform/application/integration/IntegrationConnectionService.java` | 3 |
| `integration-reference/case-file/src/index.css` | 2 |
| `integration-reference/case-file/src/demo/server.ts` | 2 |
| `frontend/securedoc-ui/src/types/index.ts` | 2 |
| `frontend/securedoc-ui/src/studio/words.ts` | 2 |
| `frontend/securedoc-ui/src/studio/lvErrorCodes.generated.ts` | 2 |
| `frontend/securedoc-ui/src/studio/exportRules.ts` | 2 |
| `frontend/securedoc-ui/src/studio/admin/platformRules.ts` | 2 |
| `frontend/securedoc-ui/src/studio/admin/helpTexts.ts` | 2 |
| `frontend/securedoc-ui/src/index.css` | 2 |
| `backend/securedoc-infrastructure/src/main/java/com/securedoc/platform/infrastructure/persistence/adapter/JpaIntegrationConnectionRepositoryAdapter.java` | 2 |
| `backend/securedoc-application/src/main/java/com/securedoc/platform/application/error/LvErrorCodes.java` | 2 |
| `backend/securedoc-api/src/test/java/com/securedoc/platform/infrastructure/delivery/ExportDestinationAdapterFollowupsTest.java` | 2 |
| `backend/securedoc-api/src/main/java/com/securedoc/platform/api/platform/PlatformHealthService.java` | 2 |
| `backend/securedoc-api/src/main/java/com/securedoc/platform/api/config/ApplicationServiceConfig.java` | 2 |
| `integration-reference/case-file/src/studio/admin/shared.tsx` | 1 |
| `integration-reference/case-file/src/studio/admin/HealthArea.tsx` | 1 |
| `integration-reference/case-file/src/demo/state.ts` | 1 |
| `frontend/securedoc-ui/src/studio/support/supportRules.ts` | 1 |
| `frontend/securedoc-ui/src/studio/support/supportRules.test.ts` | 1 |
| `frontend/securedoc-ui/src/studio/admin/shared.tsx` | 1 |
| `frontend/securedoc-ui/src/studio/admin/StorageSection.tsx` | 1 |
| `frontend/securedoc-ui/src/studio/admin/HealthArea.tsx` | 1 |
| `frontend/securedoc-ui/src/studio/admin/Admin.tsx` | 1 |
| `frontend/securedoc-ui/src/pages/PlatformAdminPage.tsx` | 1 |
| `frontend/securedoc-ui/src/components/ui/HorizontalTabs.tsx` | 1 |
| `frontend/securedoc-ui/e2e-oidc/adminPlatform.spec.ts` | 1 |
| `frontend/securedoc-ui/e2e-a11y/screens.spec.ts` | 1 |
| `frontend/securedoc-ui/e2e-a11y/proof0026.spec.ts` | 1 |
| `frontend/securedoc-ui/e2e-a11y/helpTip.spec.ts` | 1 |
| `frontend/securedoc-ui/case-file-next/src/studio/admin/shared.tsx` | 1 |
| `frontend/securedoc-ui/case-file-next/src/studio/admin/HealthArea.tsx` | 1 |
| `backend/securedoc-infrastructure/src/main/resources/db/changelog/017-sdv-delivery.xml` | 1 |
| `backend/securedoc-domain/src/main/java/com/securedoc/platform/domain/routing/DerivedDeliveryStatus.java` | 1 |
| `backend/securedoc-domain/src/main/java/com/securedoc/platform/domain/integration/IntegrationPurpose.java` | 1 |
| `backend/securedoc-domain/src/main/java/com/securedoc/platform/domain/integration/IntegrationAcknowledgementMode.java` | 1 |
| `backend/securedoc-domain/src/main/java/com/securedoc/platform/domain/delivery/DeliverySubmission.java` | 1 |
| `backend/securedoc-application/src/main/java/com/securedoc/platform/application/port/out/SdvConnectionTestPort.java` | 1 |
| `backend/securedoc-application/src/main/java/com/securedoc/platform/application/port/out/ExportDestinationProbePort.java` | 1 |
| `backend/securedoc-application/src/main/java/com/securedoc/platform/application/port/out/DeliveryPort.java` | 1 |
| `backend/securedoc-application/src/main/java/com/securedoc/platform/application/pipeline/MasterExportCompletion.java` | 1 |
| `backend/securedoc-application/src/main/java/com/securedoc/platform/application/pipeline/ExportJobQueue.java` | 1 |
| `backend/securedoc-application/src/main/java/com/securedoc/platform/application/export/service/PackageDocumentExportService.java` | 1 |
| `backend/securedoc-application/src/main/java/com/securedoc/platform/application/exception/TechnicalDependencyException.java` | 1 |
| `backend/securedoc-application/src/main/java/com/securedoc/platform/application/compliance/SensitiveTypeNames.java` | 1 |
| `backend/securedoc-api/src/test/java/com/securedoc/platform/application/pipeline/ExportJobFixture.java` | 1 |
| `backend/securedoc-api/src/test/java/com/securedoc/platform/application/export/service/PackageDocumentExportServiceTest.java` | 1 |
| `backend/securedoc-api/src/test/java/com/securedoc/platform/application/export/service/PackageDocumentExportSensitivityTest.java` | 1 |
| `Stop-LexaVault-Local.sh` | 1 |

What they are: the delivery destination's purpose and acknowledgement values stored in the database (`IntegrationPurpose.SDV_DELIVERY`, `IntegrationAcknowledgementMode.SDV_SUBMISSION_ACK`, Liquibase `017-sdv-delivery.xml`), `SdvConnectionTestService` / `SdvConnectionTestPort` and their error codes (`SDV_TEST_UNAVAILABLE`, `SDV_CONNECTION_TEST_FAILED`), UI words and help texts on the Integrations and Platform screens, test fixtures, the copies in `frontend/securedoc-ui/case-file-next/` and `integration-reference/`, and comments. Also `"SDV"` in `SensitiveTypeNames` (an abbreviation list).

## Files changed
- New: `.github/workflows/ci.yml`, `scripts/ci/check-product-names.sh` (executable), `docs/install/ci.md`, this report, `docs/work/QUEUE-2026-11-23-summary.md`.
- No product code changed.

## Commits
See `docs/work/QUEUE-2026-11-23-summary.md` for the final commit. Not pushed, not merged.

## Not done, and why
- **Push and pull request: not done.** `CLAUDE.md` allows a push only when the owner says so in the session; that was not said. Commands for the owner:
  ```
  git push -u origin feature/0042-ci
  gh pr create -R mohankandar/secure-document-platform --base feature/lexavault-r1b-tenant-identity --head feature/0042-ci --title "CI for LexaVault (task 0042)" --body-file docs/install/ci.md
  ```
  (The PR base needs the 0041 branch merged first, or the PR shows 0041's commits too.) With the guard as it is, the first run's `ci-passed` will be red.
- **0043 and 0044: not started** (queue stop rule 3).

## Questions for the owner
1. **The product-name guard.** Today 69 files outside `docs/` name the other product. Options:
   - a) **Ratchet:** commit today's list as a baseline file; the guard fails only on a new file or a new line, and the list shrinks with switch-over part 3. CI goes green now.
   - b) **Fix first:** rename the stored values and classes (a database migration for `SDV_DELIVERY` / `SDV_SUBMISSION_ACK`, the test service, UI words) as its own task, then the strict guard as written.
   - c) Keep the strict guard and accept a red CI until (b) is done.
   *Recommendation:* (a) now, (b) as switch-over part 3.
2. Should `frontend/securedoc-ui/case-file-next/` and `integration-reference/` (copies) be checked by the guard, or excluded as reference material?

## Suggested next step
Answer question 1; then the guard is a small change (a baseline file or the renames), and the queue continues with 0043.

## For the owner in the morning
- Add the repository secret (reads your existing token without showing it):
  `$env:GITHUB_PACKAGES_TOKEN | gh secret set PLATFORM_CORE_READ_TOKEN -R mohankandar/secure-document-platform`
- Branch protection for `feature/lexavault-r1b-tenant-identity` with required check `ci-passed` (the design chat's `Setup-GitHubSettings.ps1` can do it), after question 1 is settled.
- The push and PR commands above, if you want CI to run now.

## Owner's answers (2026-11-23, recorded by the design chat)
1. **Product-name guard:** option (b), fix first: done in task 0045 (generic "document store" names, migration 046, strict guard, no baseline).
2. **`case-file-next/` and `integration-reference/`:** checked by the guard (renamed in 0045).
- Secret `PLATFORM_CORE_READ_TOKEN` added to `mohankandar/secure-document-platform` by the owner.
