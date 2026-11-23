# Report 0045: generic "document store" names (product-name clean-up)

Date: 2026-11-23
Branch: `feature/0045-document-store-names` (from `feature/0042-ci`)
Outcome: DONE

## What was done
- **Names.** The other product's name is gone from code, tests, scripts, styles and fixtures outside `docs/`. This
  covers the app, `case-file-next` and `integration-reference`. The generic name is "document store"; in code it is
  `DocumentStore…`.

  | Before | After |
  |---|---|
  | `IntegrationPurpose.SDV_DELIVERY` | `DOCUMENT_STORE_DELIVERY` |
  | `IntegrationAcknowledgementMode.SDV_SUBMISSION_ACK` | `SUBMISSION_ACK` |
  | `SdvConnectionTestService`, `…Port`, `SdvIntegrationController` | `DocumentStoreConnectionTestService`, `…Port`, `DocumentStoreIntegrationController` |
  | `POST /api/v1/integrations/{id}/sdv/test-connection` | `POST /api/v1/integrations/{id}/document-store/test-connection` |
  | JSON `integrations.sdvConfigured` | `documentStoreConfigured` |
  | Failure reasons `SDV_TEST_UNAVAILABLE`, `SDV_CONNECTION_TEST_FAILED` | `DOCUMENT_STORE_TEST_UNAVAILABLE`, `DOCUMENT_STORE_CONNECTION_TEST_FAILED` (still LV-CFG-004) |
  | Setting `securedoc.delivery.type=sdv`, profile `local-sdv` | `document-store`, profile `local-document-store` |
  | Destination type `sdv`; health provider `sdv` | `document-store` |
  | Screen words such as "Sending to SDV" and "Secure Document Vault" | "Sending to the document store", "Document store" |
  | Audit sentences "Delivered to / Registered with / Sealed in Secure Document Vault" | "… the document store" (the stored action names `PACKAGE_VAULTED` and so on are unchanged) |
  | Test data such as `SDV A`, `sdv-sub-1`, `SDV-SEN-006` | `Store A`, `store-sub-1`, `DS-SEN-006` |

  I used `DocumentStore…` for the connection-test names and failure reasons, not the `Destination…` I suggested
  earlier, because `Destination…` names already exist for other things.
- **Migration 046** (`046-document-store-names.xml`, in the master changelog). It renames the stored values in
  `integration_connections` (purpose, acknowledgement mode, failure category), `package_export_records` and
  `delivery_submissions` (destination type). It also renames the index `uk_integration_connection_enabled_sdv` to
  `…_document_store`. It has a rollback. Audit records are history and are not rewritten. No columns or tables change.
- **Start check.** The new `DeliveryTypeCheck` runs before any bean is made. An unknown `securedoc.delivery.type`
  (including the old value) stops the start with one sentence, instead of failing later on a missing delivery bean.
- **The guard is strict, with no baseline.**
  - `scripts/ci/product-names.txt` holds the names, now matched case-aware. The frontend test 0029 reads the same file.
  - The guard now fails when git lists no files. Run from WSL bash, it used to report "clean (0 files)".
- **Left as is, and why** (each listed in the script and in `docs/install/ci.md`):
  - `SdvDeliveryAdapter` and its tests: the known exception, removed in switch-over part 3. I changed only the renamed
    types and setting it uses. It still reads the destination's own codes (`SDV-SEN-…`), so that behaviour is unchanged.
  - Changelog 017: Liquibase keeps its file name and checksum.
  - Migration 046 and its test: they must spell the old values.
  - The container names `sdv-postgres` and `sdv-vault` in the scripts' safety lists, which stop scripts from touching
    your containers.
  - `docs/`: history.
- Task file `0045-task.md` written from your decisions in the session.

## Commands run and results
| Command | Result |
|---|---|
| `bash scripts/ci/check-product-names.sh` (Git Bash) | clean, 1626 files checked |
| `mvn -B clean install` in `backend/` with the PostgreSQL tests on (throwaway `lexavault-0045-pg`, postgres:16-alpine, 127.0.0.1:55445) | BUILD SUCCESS |
| `npm ci`, `npm run build` | pass |
| `npm run test:standalone` | pass (counts below) |
| `test-tools/no-redacted-values/Invoke-NoRedactedValueCheck.ps1 -JarPath <this branch's jar>` | PASS, 0 matches |
| `test-tools/compliance-report/Invoke-ComplianceReportCheck.ps1 -JarPath <this branch's jar>` | PASS, 116 checks, 0 failed |

All of it ran in a scratch worktree, so your running app and your checkout were not touched. The throwaway containers
are removed and the password file is deleted.

## Tests: counts per module, and any failures with their output
- Backend, with the PostgreSQL tests on, 0 failures and 0 skipped:

  | Module | Tests | Change |
  |---|---|---|
  | domain | 79 | |
  | application | 294 | |
  | scan bridge | 136 | |
  | infrastructure | 73 | +2: `DocumentStoreNamesMigrationPostgresTest` |
  | API | 582 | +4: `DeliveryTypeCheckTest` |
- Frontend:

  | Suite | Tests |
  |---|---|
  | `test:auth` | 10 |
  | `test:vocabulary` | 191 |
  | `test:ui-framework` | 17 |
  | `test:oidc-signin` | 166 |
  | `test:tablet` | 95 |
  | `test:a11y` | 156 |
- **Existing tests I changed**, beyond the renames:
  - `JdbcClaimLockAndQueueOrderPostgresTest` (migration 045): it now rolls back 2 change sets, 046 and 045, instead
    of 1, because 046 is now the newest.
  - `exportRules.test.ts` test 0029: it now checks every name in the list, not just one, and expects no file to
    contain any of them. The one allowed line in `supportRules.ts` is gone, because that pattern no longer names
    another product.
  - `words.test.ts` and `supportRules.test.ts`: the removed old reason code is no longer in their "these codes are
    gone" lists. The guard now keeps any name of that kind out.
  - `words.test.ts`: the acronym example "Loan Number SDV" is now "Loan Number ABA", because "SDV" is no longer in the
    acronym lists (Java and TypeScript).
- **Failures along the way:** none in the builds. Before building, I caught and fixed three slips from my own bulk
  rename:
  - It changed the adapter's real destination code prefix (`SDV-SEN-` became `DS-SEN-`), which would have changed
    behaviour. Reverted, together with the adapter tests' data.
  - It turned "SDV" into "Store" in the Java acronym list. Removed.
  - It saved six `.ps1` files with LF line endings that needed no change. Restored.

## Files changed
- 94 files changed. The main groups:
  - the integration, delivery, export and health classes in all backend modules and their tests;
  - the Admin › Integrations, Health and Platform screens, words, help texts, audit sentences and their tests;
  - the e2e-oidc and e2e-a11y fixtures;
  - the `case-file-next` and `integration-reference` copies;
  - `application.yml` and the renamed profile file;
  - the guard and `docs/install/ci.md`.
- New files:
  - `DeliveryTypeCheck.java` and its test;
  - `046-document-store-names.xml` and `DocumentStoreNamesMigrationPostgresTest.java`;
  - `scripts/ci/product-names.txt`;
  - `docs/work/0045-task.md` and this report.

## Commits (hash and message), or "not committed"
In the commit on `feature/0045-document-store-names`; the hash is in the session reply.

## Not done, and why
- **Real data not touched.** Migration 046 runs on your database the next time the schema step runs, which is the next
  start of this branch's jar. Nothing was run against `securedoc-postgres`.
- **Export E2E** (`scripts/Test-LexaVault-ExportE2E.ps1`) was not run. It uses the local-folder delivery, which this
  task didn't change.
- **`docs/` not renamed.** It is history; for example, `docs/api/SDV_DELIVERY_V1.md` is the other product's API.
- **`SdvDeliveryAdapter` not renamed.** That is switch-over part 3.

## Questions for the owner
None.

## Suggested next step
- Push `feature/0042-ci` and this branch when you say so; CI's `product-names` job now passes.
- Then tasks 0043 and 0044, branched from this one.

No other product's name or behaviour in code, except `SdvDeliveryAdapter` and its tests (switch-over part 3). The
other listed exceptions are history and safety lists, not product code.

## For the owner in the morning
1. Read the rename table above. Screens now say "document store", and the connection's own name still shows where it
   did before.
2. The next start of this branch's jar runs migration 046 on your database. Before then, `securedoc.delivery.type`
   must be `document-store` or `local`; yours is `local`, through the local profile.
3. Say whether to push `feature/0042-ci` and `feature/0045-document-store-names`.
