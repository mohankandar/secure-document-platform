# Report 0021: Sensitivity levels from intake through to export

Date: 2026-11-20
Branch: `feature/sensitivity-levels` (from `feature/lexavault-r1b-tenant-identity` after the step 0 merge)
Outcome: DONE (answers to four questions asked in the session are below; three more questions for you at the end)

## What was done

### Step 0
1. U028 has its report (`docs/work/ui/U028-report.md`, commit `4615e1d`). It has no "Owner's answers" section and nothing was left uncommitted, so there was nothing to commit on `feature/ui-a11y-final`.
2. Merged `feature/ui-a11y-final` into `feature/lexavault-r1b-tenant-identity`, no fast-forward, standard message: merge commit **`dfef6c7`**. Not pushed.
3. The full build after the merge passed with 620 backend tests (domain 51, application 229, scan bridge 47, infrastructure 18, API 275).
4. Created `feature/sensitivity-levels` from it.

### Answers you gave in the session (2026-11-20)
- **Watched folder level storage:** a new small table (`watched_folder_settings`), edited in Admin › Integrations.
- **File-share split destinations:** no level is sent and no file format is invented. LexaVault's own audit records the level.
- **Scan paper:** the New case "Sensitivity" choice goes through the Scan Bridge.
- **Vero field names:** follow Vero's real API. The task text says `sensitivityLevel` is a delivery field, but Vero (its task 0024) takes the document level as the `X-SDV-Sensitivity-Level` header on each upload, and refuses a `sensitivityLevel` field in the submission JSON (`SDV-SEN-006`). The record level is `recordSensitivityLevel` in the submission JSON, as the task says.

### The rule (one place)
`SensitivityLevel` (domain), with values `STANDARD < CONFIDENTIAL < HIGHLY_CONFIDENTIAL`:
- `fromSource(sent)`: nothing sent gives `STANDARD`, and only an exact name counts. Anything else (wrong case, padding, blank, an unknown word, a number) gives `STANDARD` with `unknown = true`. Nothing is ever refused.
- `effective(document, case)`: the higher of the two.
- `effectiveForCase(case, documents)`: the highest of the case level and all of its document levels. It is used for the case panel line (see question 1).

### Intake paths found, and how each sets the level

| Path | Case level | Document level |
|---|---|---|
| External push API `POST /api/v1/external-intakes` | optional `caseSensitivityLevel` | optional `sensitivityLevel` on each `documents[]` entry; kept on the manifest until the PDF arrives |
| Scanner intake `POST /api/v1/scanner-intakes` (Scan Bridge) | `caseSensitivityLevel` from the New case choice, carried by the bridge (`StartScanSessionRequest` → `ScanSession` → delivery client). Before this task the bridge sent no level. | none; the bridge sends one PDF and no document level, so `STANDARD` |
| HTTP polling (pull) | optional `caseSensitivityLevel` in the source's discovery JSON | optional `sensitivityLevel` per document |
| Watched folder | the folder's level, set by Tenant IT (default `STANDARD`) | each file gets the folder's level |
| Manual upload (New case) | the "Sensitivity" choice (Standard, Confidential, Highly Confidential; default Standard) | none, so `STANDARD` |

- **Unknown values** (every path except the watched folder, whose level is chosen from a list): the case or document is taken in as `STANDARD` and the request still succeeds. The audit record `INTAKE_SENSITIVITY_LEVEL_UNKNOWN` has the detail `code=LV-INT-005,scope=CASE|DOCUMENT`, never the value sent. The push and scanner responses list the warning in a new `warnings` field; a replay of the same request returns the same warnings and records nothing new.
- **Raw JSON:** the request fields are read as raw JSON, so even a number or an object never fails the request.
- **Watched folder, ready for a trigger-file mapping later:** the folder's level is a default passed into the intake as a function. When a drop is resumed, its remaining files get the level already stored on the case, so one drop never ends up with mixed levels.
- **Request fingerprint:** a level counts toward it only when one was sent. Requests without levels keep their old fingerprint, so an older intake can still be retried. A different level on the same source revision returns `409 SOURCE_REVISION_REUSED`.

### Storage: migration 042 (`042-sensitivity-levels.xml`, registered in the master changelog after 041)
- `document_packages.sensitivity_level`, `documents.sensitivity_level` and `external_intake_documents.sensitivity_level`: each `VARCHAR(32) NOT NULL DEFAULT 'STANDARD'` with a check constraint allowing the three values. Existing rows become `STANDARD`.
- New table `watched_folder_settings(tenant_id, folder_key, sensitivity_level, updated_by, updated_at)`, primary key `(tenant_id, folder_key)`, foreign key to `tenants`. The installed folder's key is `installed`.
- The JPA columns are `updatable = false`, so a level cannot change after intake.
- **037 is still not registered.** No redacted values are touched.
- Migration 042 ran on real Postgres in both check runs (all four changesets applied).

### Audit
- `PACKAGE_CREATED` and `DOCUMENT_UPLOADED` details now end with `sensitivityLevel=<code>`.
- New actions:
  - `INTAKE_SENSITIVITY_LEVEL_UNKNOWN` (`LV-INT-005`);
  - `WATCHED_FOLDER_SETTINGS_CHANGED` (detail `sensitivityLevel=STANDARD->CONFIDENTIAL`).
- Both new actions are on the compliance appendix allow list, because their details hold codes only.

### Export
- **Full case (`SdvDeliveryAdapter`):**
  - the submission has `recordSensitivityLevel`, the case level;
  - each upload has the header `X-SDV-Sensitivity-Level`, that document's own level;
  - both are always sent (`STANDARD` when the source sent nothing);
  - the levels are read from the stored case on every attempt, so retries carry the same levels.
- **Refused level:** a 4xx from Vero whose `code` starts with `SDV-SEN-` gives `DELIVERY_SENSITIVITY_REFUSED`. It is not retried. Its public code is `LV-DLV-003`, and Support shows "Destination refused the sensitivity level". Any other 4xx keeps `DELIVERY_DESTINATION_INVALID`.
- **Split documents (by barcode):** each piece carries its **source document's** level plus the case level.
  - `HTTP_API`: headers `X-LexaVault-Sensitivity-Level` and `X-LexaVault-Case-Sensitivity-Level`, alongside the existing `X-LexaVault-*` headers.
  - `FILE_SHARE` (no level sent, your choice): the file carries no metadata. The `DERIVED_DOCUMENT_DELIVERED` audit record holds both levels.
  - An HTTP destination's 400 stays `DELIVERY_DESTINATION_INVALID`, because there is no contract saying which 400 means "level refused".
- **Local-folder master delivery** (`securedoc.delivery.type=local`): this destination also cannot receive a level, and nothing was changed there (see question 2).

### Screens
- **Case panel:** one read-only line, "Sensitivity: <effective level>, as sent by the source system", just above Documents. A document whose own level differs from the case's shows its level next to its size.
- **New case:** a "Sensitivity" choice with the same look as the other fields, Standard by default, used for both Upload and Scan. A resumed scan session shows the level it was started with.
- **Admin › Integrations › watched folder:** "Sensitivity of files from this folder", with Save. It appears on the installed folder's own row and on its connection when the folder is shown on one. The new endpoints `GET/PUT /api/v1/platform-admin/watched-folder/settings` accept only exact level names, because this is an admin setting, not a source value.
- **No badge anywhere else.** A vocabulary test checks the source code: only the case panel, New case and the folder setting use the level words, and two browser tests check the Cases list and the case panel.
- The Support words have `DELIVERY_SENSITIVITY_REFUSED`, and the audit screen has names for the two new actions.

### API spec
`docs/api/EXTERNAL_INGESTION_V1.md` (new section "Sensitivity levels"), `POLLING_INGESTION_V1.md`, `SDV_DELIVERY_V1.md` and `DERIVED_ROUTING_V1.md`.

## Commands run and results

| Command | Result |
|---|---|
| `git merge --no-ff feature/ui-a11y-final` on `feature/lexavault-r1b-tenant-identity` | merge commit `dfef6c7` |
| `.\Build-LexaVault-All.ps1` after the merge | PASS, 620 backend tests |
| `cd backend; mvn -o test -fae` (first run with the changes) | 1 failure: `AppendixDetailAllowListTest` expected 90 actions and found 92. The test pins the reviewed list, so I updated it deliberately to 92 and it now asserts that both new actions are on the list. |
| `mvn -o test -fae` (second run) | 1 error in my own new scanner test: a scanner connection must allow exactly one document per intake. I fixed the test setup only. |
| `mvn -o test -fae` | PASS |
| `.\Build-LexaVault-All.ps1` (final) | PASS |
| `cd frontend/securedoc-ui; npm run test:standalone` | PASS (all six suites) |
| `.\test-tools\no-redacted-values\Invoke-NoRedactedValueCheck.ps1` | PASS: 0 matches; 042 applied; throwaway container removed |
| `.\test-tools\compliance-report\Invoke-ComplianceReportCheck.ps1` | PASS: 116 of 116 checks; throwaway container removed |

The first build attempt after the merge stopped before the build itself ran: my PowerShell `*>` redirect turned `java -version`'s stderr into an error under the script's `ErrorActionPreference=Stop`. I reran it as a child `powershell.exe -File` and it passed. No code was involved.

## Tests: counts per module

| Module | Before | After |
|---|---|---|
| Domain | 51 | 59 |
| Application | 229 | 237 |
| Scan bridge | 47 | 51 |
| Infrastructure | 18 | 18 |
| API | 275 | 296 |
| **Backend total** | **620** | **661** |

Frontend:

| Suite | Count |
|---|---|
| `test:auth` | 10 |
| `test:vocabulary` | 139 |
| `test:ui-framework` | 17 |
| `test:oidc-signin` | 118 |
| `test:tablet` | 85 |
| `test:a11y` | 128 |

No failures.

New and changed tests:
- **`SensitivityLevelTest`:** nothing sent; each exact name; unknown, lower case, padded and blank values; the effective level for every pair; the case's effective level; the level kept through receipt and reload.
- **`ExternalIntakeServiceTest`, five new tests:** push levels kept and nothing-sent; no levels at all; unknown values taken as `STANDARD` with the warnings and the audit holding the code only; a replay records nothing new; a changed level is a changed request; scanner case level.
- **`CreatePackageSensitivityTest`:** each value stored and audited; nothing sent; unknown gives `LV-INT-005` with scope `CASE`.
- **`PackageSensitivityApiTest`:** the New case choice; numbers, objects and arrays are not refused; the case panel fields.
- **`ExternalIntakeSensitivityRequestTest`:** raw JSON reading; `warnings` in the response.
- **`WatchedFolderIntakeServiceRecoveryTest`, three new tests:** the folder level applied to the case and to every file, for each level; Standard without a setting; Tenant IT's change and its audit.
- **`PollingIntakeServiceBehaviorTest`, one new test:** polling passes the levels on.
- **`ScanSessionServiceTest`, two new tests; `LexaVaultScannerDeliveryClientSensitivityTest`:** the bridge carries the choice and sends it.
- **`SdvDeliveryAdapterSensitivityTest`:** both levels in the payload; Standard when nothing was sent; a retried upload carries the same level; a refused case level and a refused document level each give `LV-DLV-003` with no retry; any other 400 keeps its code.
- **`PackageDocumentExportSensitivityTest`:** two attempts carry the same stored levels.
- **`DerivedDeliverySensitivityTest`, plus one new test in `DerivedDestinationDeliveryAdapterTest`:** a split document carries its source document's level and the case level, as headers and in the audit.
- **`AppendixDetailAllowListTest`:** count 90 → 92 (changed on purpose, see above).
- **Frontend:** `words.test.ts` gets five tests, including the source check that there is no badge elsewhere. The new `e2e-oidc/sensitivity.spec.ts` has 4 tests: the case panel line and the document level; no badge in Cases; New case Upload sends the choice; Scan paper sends it. `adminIntegrations.spec.ts` gets two tests for the folder setting.

## Files changed

- **Backend main code (domain, application, infrastructure, API, scan bridge):** 49 modified, 9 new, plus migration `042-sensitivity-levels.xml` and the master changelog.
- **Backend tests:** 6 modified, 8 new.
- **Frontend:**
  - `types/index.ts`, `api/client.ts`, `api/scanBridgeClient.ts`;
  - `studio/` files: `words.ts`, `Intake.tsx`, `UploadCapture.tsx`, `ScanCapture.tsx`, `CaseDrawer.tsx`, `admin/IntegrationsArea.tsx`, `admin/auditRules.ts`, `admin/integrationRules.ts`;
  - tests: `words.test.ts`, `e2e-oidc/adminIntegrations.spec.ts`, and the new `e2e-oidc/sensitivity.spec.ts`.
- **Docs:** 4 API specs, `docs/work/0021-task.md` and this report.
- **No new dependency.**
- **Frozen areas:** the V1 processing steps and the UI framework are unchanged. The export payload and the intake requests gained optional fields only.

## Commits

- `dfef6c7` Merge branch 'feature/ui-a11y-final' into feature/lexavault-r1b-tenant-identity
- One commit on `feature/sensitivity-levels`: "feat: sensitivity levels from intake through to export (task 0021)". The hash is in `git log`.

Nothing is pushed or merged.

## Not done, and why

- **Reading levels from a trigger file:** left out, as the task says. The folder's level is a default that a later mapping can override.
- **The error code list:** it is not in this repository. Please add `LV-INT-005` and `LV-DLV-003` to it.
- **Showing the level in Reports:** not part of this task. The shared effective-level rule is ready for it.

## Questions for the owner

1. **The case panel's "effective level":** I took it to be the highest of the case level and every document level. A Confidential case with one Highly Confidential document therefore reads "Highly Confidential", and that document is also marked next to its name. If you meant the case's own level, it is a one-word change.
2. **The local-folder master delivery** (`securedoc.delivery.type=local`) cannot receive a level either, the same as `FILE_SHARE`. Should it get the same treatment (audit only)? Today its `DOCUMENT_EXPORTED` record does not include the level.
3. **Refusals from HTTP split destinations:** should a destination contract say which 400 means "level refused" (for example a `code` field), so that it can also give `LV-DLV-003`? Today it stays `DELIVERY_DESTINATION_INVALID`.

## Suggested next step

Your screen check of New case, the case panel and the watched-folder setting. Then the Reports task that counts levels and `LV-INT-005` warnings.
