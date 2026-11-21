# Report 0030: Export screens follow-ups

Date: 2026-11-21
Branch: `feature/0030-export-polish`
Outcome: DONE (one question for the owner below)

## What was done

**1. LV-CFG-005** is now "More than one main export destination" / "Only one enabled main export destination is allowed per entity." The code number is unchanged. Changed in `docs/work/lv-error-codes.md`, the registry (`LvErrorCode.java`), the generated frontend file (regenerated with `-Dlv.codes.write=true`) and the customer guide (`docs/api/error-codes.md`). The guide's first step now reads "decide which connection is this entity's main export destination". The "no Vero on screen" test no longer has the LV-CFG-005 exception and now checks the new title and description. It still pins one line: the pattern in `supportRules.ts` that recognises a leading product name. That line is code, not a word on screen.

**2. The destination's own code**
- `TechnicalDependencyException` carries `destinationCode`. Only a code matching `[A-Za-z0-9_-]{1,40}` is kept; anything else becomes null. The code survives `withLvCode`, `withAttempts` and `asRateLimited`.
- SDV adapter: the sensitivity refusal (`SDV-SEN-nnn`) and every other non-2xx answer pass the answer's `code` field. Split adapter (HTTP): every non-2xx answer passes it. An answer that isn't JSON, or has no code or a non-text code, carries no code.
- The export job stores `<reason>; destination=<code>` in `last_detail`, for example `DELIVERY_SENSITIVITY_REFUSED; destination=SDV-SEN-006`. The job re-checks the code against the same pattern. **`last_detail` is VARCHAR(80)** and a schema change is not allowed, so the code is dropped (the reason is kept) whenever the whole line would pass 80 characters. With the longest reason (28 characters), a code of 39 or 40 characters is dropped. Codes like `SDV-SEN-006` are far shorter.
- Screens: in the case panel, Technical details shows "Reason DELIVERY_SENSITIVITY_REFUSED" and "Destination code SDV-SEN-006". The old label "Destination's answer" was wrong (the field held LexaVault's reason), so it now reads "Reason". In Support, each IT card has Technical details listing "Destination code SDV-SUB-017", each code once. The frontend applies the same safe pattern again.

**3. Status chips on one line.** The chips no longer wrap, and the 0029 wrap rule is removed. The stage column is now 236 px (the longest chip, "Exporting · destination paused", is 215 px), and the next-step column gives way and ends in "…". With the case panel open, the source and docs columns give way. At 1200 px and below with the panel open, a row shows the identifier and the chip, with the next step underneath, as it already did at 1024 px and below. Measured at 1440, 1280, 1100, 1025, 1024, 820 and 768 px, with and without the panel. Before this change, rows at 1025 px with the panel open were already wider than the list.

**4. "Exported"** replaces "Delivered" in the stage name (the stage tile, the Cases group and the stage filter), the status chip for every finished status, the case panel's stage strip and heading ("Exported to <destination>"), and the line under the Cases title ("3 exported"). The status note reads "Processing and export completed." Audit sentences and technical text are unchanged.

## Commands run and results

| Command | Result |
|---|---|
| `mvn -pl securedoc-domain,securedoc-application -am test -Dtest=LvErrorCodeFrontendFileTest -Dlv.codes.write=true` | regenerated `lvErrorCodes.generated.ts` (one line changed) |
| `cd backend; mvn clean install` | BUILD SUCCESS, 1044 tests, 0 failures, 20 skipped (the PostgreSQL tests, skipped by design without a database) |
| `cd frontend/securedoc-ui; npm run test:standalone` | exit 0: auth 10, vocabulary 175, ui-framework 17, oidc-signin 161, tablet 95, a11y 156 |
| `.\test-tools\no-redacted-values\Invoke-NoRedactedValueCheck.ps1` | PASS: 0 matches (database, 3 logs, 2 audit exports); throwaway `lexavault-0015-pg` removed |
| `.\test-tools\compliance-report\Invoke-ComplianceReportCheck.ps1` | PASS: 116 checks, 0 failed; throwaway `lexavault-0017-pg` removed |
| `$env:P0030_PROOF='1'; npx playwright test --config playwright.a11y.config.ts proof0030 --project light` | 7 passed; 7 pictures in `docs/work/compare/0030/` (not committed) |

None of `securedoc-postgres`, `sdv-postgres`, `sdv-vault`, `lexa-keycloak` or `lexa-keycloak-db` was touched. The PostgreSQL test classes were not run against a throwaway database: no SQL or repository code changed.

## Tests: counts per module, and any failures with their output

| Module | Before (0029) | Now |
|---|---|---|
| Domain | 76 | 76 |
| Application | 281 | 281 |
| Scan Bridge | 136 | 136 |
| Infrastructure | 49 (20 PostgreSQL, skipped) | 49 (20 PostgreSQL, skipped) |
| API | 495 | 502 |
| **Backend total** | **1037** | **1044** |

Frontend (`test:standalone`), before (0029) → now: auth 10 → 10, vocabulary 173 → 175, ui-framework 17 → 17, oidc-signin 156 → 161, tablet 90 → 95, a11y 156 → 156. No failures.

New and changed tests:
- Backend `ExportDestinationCodeTest` (5): main export stores and shows `SDV-SEN-006` (job row, case jobs, Waiting for you); main export drops 8 unsafe codes; split export stores a safe code and drops an unsafe one; the 80-character limit; the code survives the other marks on the failure. `SdvDeliveryAdapterSensitivityTest` (+1) and `DerivedDestinationDeliveryAdapterTest` (+1): the adapter passes a safe code and drops unsafe, too long, nested and non-JSON ones. `ExportJobFixture`: the fake destination can carry a code (test fixture only).
- The registry, guide and generated-file tests (`LvErrorCodeRegistryTest`, `LvErrorCodeGuideTest`, `LvErrorCodeFrontendFileTest`) pass with the new title. They read the files, so no test code changed.
- `exportRules.test.ts`: the no-Vero test without the LV-CFG-005 exception (+ the new title and description); +2 tests (reading the destination code, the safe pattern; "Exported" everywhere a finished case is named). `words.test.ts`: Desk.tsx must not spell "Exported" itself either.
- `e2e-oidc/exports.spec.ts`: the case panel shows "Destination code SDV-SEN-006"; Support shows "Destination code SDV-SUB-017". New test: an unsafe code is not shown and the reason still is. The test "reads Delivered" was **changed** to "reads Exported", and it now also checks the heading and the Cases page. That change follows the owner's word decision. +4 tests: chips on one line (bounding box no higher than one line, not cut, inside their column, no sideways scroll) at 1440 and 1024 px, with and without the case panel (plus the panel's eyebrow and stage names on one line).
- `e2e-tablet/tablet.spec.ts` (+1 per size): the same one-line checks, then the case panel opened by tap; no "Delivered" on Cases.
- `e2e-oidc/exportFixtures.ts`: jobs now carry destination codes (one safe code for the case, one unsafe, one for IT); new helpers `chipProblems` and `panelLineProblems`.

## Files changed
- Backend: `LvErrorCode.java`, `TechnicalDependencyException.java`, `ExportJobService.java` (`withDestinationCode`, `classify`), `SdvDeliveryAdapter.java`, `DerivedDestinationDeliveryAdapter.java`; tests `ExportDestinationCodeTest` (new), `SdvDeliveryAdapterSensitivityTest`, `DerivedDestinationDeliveryAdapterTest`, `ExportJobFixture`.
- Frontend: `lvErrorCodes.generated.ts`, `exportRules.ts`, `CaseDrawer.tsx`, `support/SupportExport.tsx`, `support/support.css`, `Desk.tsx`, `data.ts`, `words.ts`, `studio.css`; tests `exportRules.test.ts`, `words.test.ts`, `e2e-oidc/exports.spec.ts`, `e2e-oidc/exportFixtures.ts`, `e2e-tablet/tablet.spec.ts`, `e2e-a11y/proof0030.spec.ts` (new, on request only), `playwright.a11y.config.ts`.
- Docs: `docs/work/lv-error-codes.md`, `docs/api/error-codes.md`, this report.

## Commits (hash and message), or "not committed"
- One commit on `feature/0030-export-polish`: "feat: 0030 export screens follow-ups" (hash given to the owner in the session). Not merged, not pushed.

## Not done, and why
- **Reports (studio) still says "Delivered"** in its metrics: the "Delivered" outcome filter, the Pipeline row, the "Delivered" tile, the chart legend, the daily table, "Delivered within 4 h" and "Delivery rate". The review said "Exported" matches Reports, but the Reports screen uses "Delivered", so changing it would go beyond what the task describes. Left unchanged; see question 1. The old pages under `src/pages/` were not touched either.
- A destination code of 39–40 characters is dropped when the reason is long (VARCHAR(80), no schema change). See point 2 above.
- No live run against Vero R7 with `export-mode=job`; the proof is the tests and mocked pictures.
- `test:e2e` (needs the LOCAL app running) was not run.

## Questions for the owner
1. Should Reports use "Exported" too (the outcome filter, Pipeline row, tile, chart legend and daily table; perhaps "Exported within 4 h" and "Export rate")? It's a small words-only change if you want it.

## Suggested next step
Review the pictures in `docs/work/compare/0030/` and answer the Reports question. Then do the run with `export-mode=job` against Vero R7 suggested in 0029: a real refusal should show "Destination code SDV-SEN-…" under Technical details.
