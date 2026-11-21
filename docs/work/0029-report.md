# Report 0029: Export screens (staged pipeline P1)

Date: 2026-11-21
Branch: `feature/0029-export-screens`
Outcome: DONE (questions for the owner below)

## What was done

Built the four approved boards of the canvas "LexaVault · Export screens" (read from the canvas itself) and the retention settings, in the Case File UI with its own components, tokens and words. No new dependency; the UI framework is unchanged (its contract test passes).

**1. Waiting for you** (`/waiting`, `WaitingForYou.tsx`)
- Nav item "Waiting for you" right after Review, with a count badge. Shown only to people with `EXPORT_RESEND` and only while something waits (in `inline` mode nothing ever waits, so it stays hidden; an answer the screen does not understand also hides it).
- The approved intro line and footnote (with `LV-DLV-015`).
- One group per destination: name, "full case" / "split by barcode", count ("3 cases", "2 documents"), "Most often: Title · code", and one "Resend all N" in the header. No checkboxes and no buttons on the rows.
- Rows: the case's primary identifier (and "doc N" for a split document), "Title · code", waiting since ("Today 9:41 AM"). The whole row is a real link to `/case/<id>`.

**2. Resend all: confirmation** (the app's own modal `Sheet`, as every other confirmation)
- Title "Resend N cases to <destination>?", the approved body, the box with the count per reason ("2 refused for the sensitivity level · LV-DLV-003"), the check line, **Not now** and **Resend N cases**.
- Calls `POST /export-jobs/resend-all`; then the toast "N cases queued for <destination>. They'll show as "Exporting · ready" until sent."

**3. The "Exporting" status**
- Cases list: a case with an open export job shows **Exporting · ready / destination paused / waiting for you / needs IT**; EXPORTED shows **Exported**; NONE shows the status as today. While exporting a row offers no action, and only "waiting for you" counts as "Needs you" (for a person with `EXPORT_RESEND`), so a reviewed case waiting to go out no longer looks like it needs a reviewer.
- Case panel: the stage strip ends with an **Exporting** step (it reads "Delivered" once delivered). While exporting, the next-step box is replaced by the export box. Waiting for you: "EXPORTING · WAITING FOR YOU", "<destination> refused this case", "Title · code", "Refused today at <time>. Once the destination's team has fixed it, send it again. It gets one more attempt.", and one **Resend this case** (`EXPORT_RESEND` only; `POST /export-jobs/resend`). A resend refused again shows "The destination refused it again, so it went to IT · LV-DLV-015". The job's stored answer, attempts and job id are under Technical details.

**4. Support (IT Admin)** (`/support/export`, `support/SupportExport.tsx`; "Problems | Export" tabs on both Support pages)
- Needs IT: one card per group. "<count> cases failing to <destination> since <time>", "Title · code", what was already tried (the retry-schedule line for LV-DLV-001/002/007/008; own lines for LV-DLV-015 and LV-PRC-008), the case ids, **Retry all N** (`retry-group`). A sign-in failure (LV-DLV-005): "<destination> refused LexaVault's sign-in", "Exports to it are on hold: N waiting, nothing lost. Fix the credential in Admin › Integrations, then set it back to working.", **Set back to working** (`POST …/working`, then `retry-group` for that group's jobs).
- Destinations: state (Working · "Paused since <time>" with "Health check failing · next check <time>" · Needs IT with "Title · code"), the number waiting ("3 ready", "27 waiting"), and **Check now** for a paused one.
- The alert "LexaVault can't reach its destinations" at the top when raised, with "The problem is likely LexaVault's own network, not the destinations."
- Support's nav badge counts the IT groups (not the cases). Action buttons need `TECHNICAL_RETRY`; Platform Operations can read the page.

**5. Business Admin: retention per destination** ("Keeping exported copies" card under Admin › Redaction & retention, next to "Keeping and deleting")
- The main export and each split destination with its hours. Empty is the default: the box shows the default hours in grey and the line under it says "Same as redacted copies (24 hours)" / "Same as the main export (N hours)" (as a placeholder the words were cut off in the narrow box). A value out of 1–72 is marked as typed; the API's own message is shown on save ("… · LV-CFG-001").

**6. Words**
- `exportRules.ts` holds every new screen word (pure, tested). Every code is shown as "Title · LV-XXX-NNN" from the generated list.
- `auditRules.ts`: a sentence for each of the 16 audit actions of 0027 and 0028 (and they are in the Action filter).

### Smallest backend changes (no schema change)
| Change | Why |
|---|---|
| `GET /packages` items carry `exportState` (two queries per page, the same as the dashboard does) | The Cases list calls `GET /packages`; 0028 had added the state only to `/operations/dashboard`. |
| `POST /api/v1/support/export-destinations/{id}/check` (`TECHNICAL_RETRY`) | "Check now" had no endpoint. Runs the destination's health check now; a paused destination whose check passes is WORKING again and its waiting jobs go out in order (audited as `EXPORT_DESTINATION_RESUMED`, by=check-now); a failing check is recorded. LV-CFG-001 for a destination that is not the tenant's. |
| `GET /support/export-destinations`: each destination also has `nextCheckAt` (while paused) and `openJobs` | "next check <time>" and the Waiting column. `openJobs` is one grouped count query (`countOpenByDestination`). |

## Commands run and results

| Command | Result |
|---|---|
| `cd backend; mvn clean install` (final code) | BUILD SUCCESS, 1037 tests, 0 failures, 20 skipped (the PostgreSQL tests, skipped by design without a database) |
| Both PostgreSQL test classes against a throwaway `postgres:16.4-alpine` (`lexavault-0029-pg`, 127.0.0.1:55432, generated password not printed; removed afterwards) | 20 run, 0 failures (11 + 9) |
| `cd frontend/securedoc-ui; npm run test:standalone` (final code) | exit 0: auth 10, vocabulary 173, ui-framework 17, oidc-signin 156, tablet 90, a11y 156 |
| `npx playwright test --config playwright.oidc-signin.config.ts exports` | 18 passed (also inside `test:oidc-signin` above) |
| `.\test-tools\no-redacted-values\Invoke-NoRedactedValueCheck.ps1` | PASS: 0 matches (database, 3 logs, 2 audit exports); throwaway `lexavault-0015-pg` removed |
| `.\test-tools\compliance-report\Invoke-ComplianceReportCheck.ps1` | PASS: 116 checks, 0 failed; throwaway `lexavault-0017-pg` removed |
| `$env:P0029_PROOF='1'; npx playwright test --config playwright.a11y.config.ts proof0029` | 6 pictures in `docs/work/compare/0029/` (not committed) |

None of `securedoc-postgres`, `sdv-postgres`, `sdv-vault`, `lexa-keycloak` or `lexa-keycloak-db` was touched.

## Tests: counts per module, and any failures with their output

| Module | Before (0028) | Now |
|---|---|---|
| Domain | 76 | 76 |
| Application | 281 | 281 |
| Scan Bridge | 136 | 136 |
| Infrastructure | 48 (19 PostgreSQL, skipped) | 49 (20 PostgreSQL, skipped) |
| API | 488 | 495 |
| **Backend total** | **1029** | **1037** |

Frontend (`test:standalone`), before (0028) → now: auth 10 → 10, vocabulary 158 → 173, ui-framework 17 → 17, oidc-signin 138 → 156, tablet 85 → 90, a11y 138 → 156.

New tests:
- Backend: `ExportScreensSupportTest` (5: check now resumes a paused destination and its cases go out in order; a failing check leaves it paused and is recorded; a working destination is only checked; an unknown destination is refused and not checked; next check while paused and the open-job count), `PackageListExportStateTest` (2), and one PostgreSQL test for the count query.
- `exportRules.test.ts` (14): every approved wording, the six states (read against the Java enum), the nav rule, codes from the registry, times, Support lines, retention placeholders and range, and "no screen says Vero".
- `auditRules.test.ts` (+1): each of the 16 export actions read from `AuditAction.java` has its own sentence and filter entry. `words.test.ts`: the two new page titles.
- `e2e-oidc/exports.spec.ts` (18): Waiting for you (groups, row opens the case, Resend all with confirmation and toast, "Not now" sends nothing, single resend in the case panel, refused again, hidden without `EXPORT_RESEND`, hidden when nothing waits, none for the IT Admin); the Exporting status in Cases and in the case panel for each state, and Delivered after export; Support (badge counts groups, alert, group retry, set back to working, check now, read-only for Platform Operations); Business Admin retention (save, out of range with LV-CFG-001).
- a11y (`screens.spec.ts`, +9 per theme): Waiting for you, the dialog, Cases with every export state, the case panel in each of the 4 states, Support export with the alert, the retention card out of range. 0 violations, light and dark.
- Tablet (+1 per size): Waiting for you and the dialog by tap, the case panel's resend, every control 44 px, nothing scrolls sideways.

Found and fixed while testing (not test changes):
- Contrast: the destination kind under the legacy `th` style (12 px grey) and the 11 px danger eyebrow on the danger tint (4.41:1) failed axe; fixed in the new CSS only.
- Touch: "Resend all" was 36 px high on a tablet; the export screens now get the same 44 px touch rule as Cases and Review.
- The UI framework contract refused a 760 px breakpoint; changed to the documented 768 px.
- 21 existing e2e tests (IT Admin and Platform Operations) failed because the new Support badge in the top bar logged a console error on every screen when their mocks answered `{}`. The two top-bar hooks (the Waiting for you item and the Support badge) now check the answer quietly and hide on an answer they don't understand; the screens themselves keep the logging check. No existing test was changed.
- From the proof pictures: long "Exporting · …" chips ran into the time column on Cases (they now wrap inside their 150 px column); the case panel's code broke inside "LV-DLV-003" (now in the code style, like the other screens); the dialog's reason box had no padding inside the sheet.
- One full run had 2 failures in `adminSafePolicy.spec.ts` (a 15-minute stall, then Edge failed to start) while the compliance check was running at the same time. The spec passed alone (17/17) and in every later full run.
- My own test mistakes (a curly vs straight apostrophe in a registry title; a locator that took the panel's "Case" eyebrow) were fixed in the tests.

## Files changed
- Backend: `ExportJobService` (check now, `nextCheckAt`, `openJobs`), `PipelineJobRepository` + `JdbcPipelineJobRepository` (`countOpenByDestination`), `ExportJobController` (check endpoint), `PackageController` and `PackageSummaryResponse` (`exportState`); tests `ExportScreensSupportTest`, `PackageListExportStateTest`, `InMemoryPipelineJobs`, `JdbcExportFollowupsPostgresTest`.
- Frontend, new: `studio/exportRules.ts` (+ test), `studio/WaitingForYou.tsx`, `studio/support/SupportExport.tsx`.
- Frontend, changed: `CaseFileApp.tsx` (nav, badge, routes), `Desk.tsx` (status chip), `CaseDrawer.tsx` (stage strip, export box), `atoms.tsx` (`ExportChip`), `data.ts` (hooks, `needsYou`), `api/client.ts`, `types/index.ts`, `admin/PolicyArea.tsx` (retention card), `admin/auditRules.ts`, `support/SupportList.tsx` (tabs), `words.ts` (nav word, titles), `studio.css`, `support/support.css`, `package.json` (the new test in `test:vocabulary`).
- Tests: `e2e-oidc/exports.spec.ts`, `e2e-oidc/exportFixtures.ts`, `e2e-a11y/harness.ts` (export answers; inline answers by default), `e2e-a11y/screens.spec.ts`, `e2e-a11y/proof0029.spec.ts` (on request only), `playwright.a11y.config.ts`, `e2e-tablet/tablet.spec.ts`.
- `docs/work/0029-report.md`. Proof pictures in `docs/work/compare/0029/` (not committed).

## Commits (hash and message), or "not committed"
- The task 0029 commit on `feature/0029-export-screens` (hash given to the owner in the session). Not merged, not pushed.

## Not done, and why
- The destination's **own** code (for example SDV-SEN-006 on the canvas) is not stored by 0027/0028: a job keeps LexaVault's internal reason (for example `DELIVERY_SENSITIVITY_REFUSED`). Technical details show that. Storing the destination's code needs a backend change in the adapters (no schema change: it would go in `last_detail`); not done without the owner's word.
- The dialog is the app's side sheet (the modal every other confirmation uses), not a centred box as drawn; same words and buttons.
- No live run against the real Vero with `export-mode=job`; the proof is the tests above with mocked answers.
- `test:e2e` (needs the LOCAL app running) was not run.
- The Waiting for you nav count and the Support badge refresh on their own timer (10 s / 15 s), and at once after an action on their own page.

## Questions for the owner
1. **LV-CFG-005's title says "Vero"** ("More than one Vero connection", in the generated code list from `docs/work/lv-error-codes.md`). It can show on Integrations. Rename it (for example "More than one Secure Document Vault connection")? The new "no Vero on screen" test pins it as the only exception.
2. **The destination's own code under Technical details**: store it with the job (in `last_detail`) in a small backend task?
3. **Sign-in card**: "Set back to working" also re-queues the jobs of that group (so the case that met the sign-in failure goes out too). Is that what you want, or only the destination?

## Suggested next step
The owner reviews the proof pictures and the screens on `feature/0029-export-screens`; then one run with `export-mode=job` against Vero R7 (Vero stopped mid-run, then a refusal) to see the screens with real answers.
