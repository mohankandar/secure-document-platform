# Report 0022: Small follow-ups from 0021 and U028, then the failure inventory for the error code list

Date: 2026-11-20
Branch: `feature/0022-followups-and-failure-inventory` (from `feature/lexavault-r1b-tenant-identity` after the step 0 merge)
Outcome: DONE

## What was done

### Step 0
1. Both `docs/work/0021-report.md` and `docs/work/ui/U028-report.md` end with "Owner's answers (2026-11-20)". Checked before committing.
2. On `feature/sensitivity-levels`: committed both reports and this task, `03e1299 docs(work): 0021 and U028 owner's answers; task 0022`.
3. Merged `feature/sensitivity-levels` into `feature/lexavault-r1b-tenant-identity` with `--no-ff` (standard message): `c486fac`. Full build and `npm run test:standalone` pass (counts below). Not pushed.
4. Created `feature/0022-followups-and-failure-inventory` from it.

### Part A
- **A1, case panel line.** The line now shows the case's own level as sent (`pkg.sensitivityLevel`; nothing sent = Standard), not the highest level in the case. A document whose own level differs is still marked next to its name. `effective(document, case)` and the `effectiveSensitivityLevel` fields are unchanged (export and later Reports use them). Changed: `CaseDrawer.tsx` (one line), `words.ts` (parameter renamed to `caseLevel`, comment states the rule). Tests: the e2e test that pinned "Highly Confidential" for a Confidential case with one Highly Confidential document now expects "Confidential"; a new e2e test checks that a case sent with no level reads Standard even when a document in it is Confidential; the word test's title is updated (its assertions already held).
- **A2, local-folder master delivery.** `PackageDocumentExportService` adds `, sensitivityLevel=<document level>, caseSensitivityLevel=<case level>` to the `DOCUMENT_EXPORTED` audit detail, the same form as `DERIVED_DOCUMENT_DELIVERED`. Codes only. The line is written for every master destination (one place in the code), so SDV deliveries get the two codes too; for SDV this repeats what was already sent. No file format added. One new test: `PackageDocumentExportSensitivityTest.localFolderDeliveryRecordsBothLevelsInTheExportAudit` (destination `local-folder`, case Confidential, document Highly Confidential).
- **A3, entity chooser.** Cause: the chooser was the older `auth-card` screen, which never loads `studio/theme.ts` (so `data-theme` was never set before sign-in) and whose `--ui-*` colours have no dark values. Fix without touching the UI framework (`tokens.css`, `index.css`, `components/ui` unchanged): the chooser is now a Case File screen, `src/studio/EntityChooser.tsx`, built the same way as the existing `NoAccess` screen (it loads `./theme` and `./studio.css` and uses the Case File tokens, which already have dark values). `App.tsx` loads it lazily in place of the old markup; sign-in and session logic are unchanged. Same words, one `main` landmark, same page title. Spacing in `studio.css` uses the existing tokens only: `--space-3` between the buttons and above the list (24 px in total under the line, with the screen's own 12 px gap). No raw hex, no new breakpoint. Tests (both light and dark projects): the axe scan of the chooser passes; new: the chooser is dark when the computer is set to dark and light when set to light (checks `data-theme` and the computed background, the dark `--cf-surface`); new: the line has at least 16 px of room under it and the buttons do not touch.
- **A4, proof pictures** in `docs/work/compare/0022/` (not committed; light, 1440 px):
  - `a4-case-panel-confidential-light-1440.png`: Confidential case with one Highly Confidential document. The line shows Confidential; the document is marked Highly Confidential.
  - `a4-case-panel-standard-light-1440.png`: case with no level sent. The line shows Standard.
  - `a4-new-case-sensitivity-open-light-1440.png`: New case with the Sensitivity choice open. A browser draws a native list outside the page, so a headless screenshot cannot show it open; for this picture only, the list is shown in place (three options). The app is unchanged.
  - `a4-integrations-watched-folder-level-light-1440.png` and `…-card-light-1440.png`: Admin › Integrations, "Sensitivity of files from this folder".
  - `a4-entity-chooser-light-1440.png` and `a4-entity-chooser-dark-1440.png`: the two pictures differ (dark background, light text). The computer's setting is set with `emulateMedia`, not by setting `data-theme` by hand as U028 did.
  - The pictures are taken by tests that exist only when `P0022_PROOF=1` is set, so normal runs and counts are unchanged:
    `$env:P0022_PROOF='1'; npx playwright test --config playwright.oidc-signin.config.ts sensitivity adminIntegrations -g proof` and `npx playwright test --config playwright.a11y.config.ts proof0022 --project light`.

### Part B
`docs/work/0022-failure-inventory.md`: 388 rows, grouped by proposed area then by file, with the shared behaviour explained once at the top. No code was changed for Part B and no final codes are assigned.

| Area | INT | SCN | PRC | AI | RED | REV | DLV | AUT | CFG | SYS | Total |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Rows | 71 | 28 | 14 | 22 | 7 | 32 | 54 | 56 | 37 | 67 | 388 |

How it was made: four read-only surveys run in parallel (intake and scanner; processing, AI and redaction; review and delivery; sign-in, configuration, start-up and frontend), then merged by script and renumbered. The 15 delivery rows the processing survey repeated were dropped in favour of the delivery survey's more detailed rows. I re-checked the main claims by hand: the Review conflict check, the 500s for client mistakes, the 50 MB limit, the watched-folder catch-all and `RETRY_PENDING`. A scan of the file for e-mail addresses, file names, drive paths and secret-looking values found none.

**Leak list** (full list in the inventory, 16 groups). The ones that matter most:
- Polling run history and its audit keep up to 500 characters of raw exception text (#34). That can include a source document id (#49), an identifier key, or a file name (#55).
- Watched-folder observations show the drop folder's name, which is often a person's or a case's name (#39, #58 to #61). Scheduled scan errors are logged with full paths (#56, #57).
- `DOCUMENT_EXPORTED` audit detail holds the original file name (#178). Local-folder and file-share delivery store and return file names (#200, #201, #222).
- Any `IllegalStateException` text goes to the screen word for word (#291). Today's texts are safe, but nothing filters it.
- The OIDC discovery test shows raw remote error text (#241). The identity provider's error body is logged to the browser console (#277). An AI node URL with credentials would be echoed in the start-up log (#328).
- Document content can be left on disk after a failure (temp PDFs, OCR images and text, scanner files), recorded nowhere (#370, #372, #373, #86, #91).

**Silent list** (about 60 places, grouped in the inventory). The ones that matter most:
- The watched folder reports every refusal as `SOURCE_IO_ERROR` (#38). The real reason is neither logged nor audited.
- Every sign-in admission refusal is one fixed 403 with no log and no audit (#239). `SECURITY_CONTEXT_INVALID` exists as an audit action but nothing writes it.
- An exception escaping a processing run, or a licence denial, leaves the case SUBMITTED and re-dispatched every 5 s with no limit (#346, #358).
- A confirmed finding with no location fails the case without a Support incident (#137). Redaction verification throws away its exact reason (#142).
- Split routing after a master export, and automatic split send, fail with no audit (#181, #192). `RETRY_PENDING` is never retried automatically (#182).
- Licence-denial and in-request intake-expiry audit rows are probably rolled back with the request (#308, #28). This needs a test.

**Other findings** that will shape the code list (details in the inventory):
- No four-eyes rule (#145).
- The Review screen's "already decided by someone else" branch can never run (#160).
- Client mistakes such as a missing header come back as 500, and the Scan Bridge retries them forever (#7, #32, #82).
- The 250 MB push limit cannot be reached past the 50 MB multipart limit (#5, #22).
- Manual upload has no server-side PDF check (#45).
- Many different stops share "Processing problem", and the sign-in no-access text covers about ten causes (#284).

**Top 10 a customer is most likely to meet:**
1. Business identifier refusals.
2. Watched-folder "I/O error" whatever the cause.
3. Destination unreachable.
4. Destination refuses (`DELIVERY_DESTINATION_INVALID`, including 409).
5. AI model unavailable or slow.
6. OCR timeout.
7. Corrupt or password-protected PDF.
8. Signed in but refused.
9. Permission refused.
10. File too large.

The reasons are in the inventory.

## Commands run and results

| Command | Result |
|---|---|
| `git commit` on `feature/sensitivity-levels` | `03e1299` |
| `git checkout feature/lexavault-r1b-tenant-identity; git merge --no-ff --no-edit feature/sensitivity-levels` | `c486fac`, no conflicts |
| `.\Build-LexaVault-All.ps1` after the merge (step 0) | PASSED |
| `npm run test:standalone` after the merge (step 0) | PASSED |
| `git checkout -b feature/0022-followups-and-failure-inventory` | done |
| `mvn -pl securedoc-api surefire:test -Dtest=PackageDocumentExportSensitivityTest` | 2 run, 0 failed |
| `npm run test:vocabulary`; `npx playwright test --config playwright.oidc-signin.config.ts sensitivity` | 139/139; 5/5 |
| `npx playwright test --config playwright.a11y.config.ts shortcuts screens -g "entity chooser"` | 12/12 (light and dark). The first run of my two new tests failed for my own reasons: the list margin lost to `.cf ul { margin: 0 }` (fixed with `.cf .cf-chooser-list`), and the theme test signed in twice in one page (split into one test per setting). No existing test was changed to pass. |
| `.\Build-LexaVault-All.ps1` on the work branch | PASSED |
| `npm run test:standalone` on the work branch | PASSED (counts below) |
| `.\test-tools\no-redacted-values\Invoke-NoRedactedValueCheck.ps1` (throwaway container `lexavault-0015-pg` only) | PASS: 0 matches in the database, the three app logs and the audit export (JSON and CSV); 15 audit records; throwaway container removed |
| Proof pictures (`P0022_PROOF=1`, see A4) | 5 + 2 passed, 7 pictures |

## Tests: counts per module, and any failures with their output

| Suite | Step 0 (after merge) | Work branch |
|---|---|---|
| Backend domain | 59 | 59 |
| Backend application | 237 | 237 |
| Backend scan bridge | 51 | 51 |
| Backend infrastructure | 18 | 18 |
| Backend API | 296 | 297 (+1: A2) |
| **Backend total** | **661** | **662** |
| `test:auth` | 10 | 10 |
| `test:vocabulary` | 139 | 139 |
| `test:ui-framework` | 17 | 17 |
| `test:oidc-signin` | 118 | 119 (+1: A1) |
| `test:tablet` (WebKit, iPad) | 85 | 85 |
| `test:a11y` (light and dark) | 128 | 134 (+6: A3, three tests × two projects) |

No failures in the final runs.

## Files changed

- `backend/securedoc-application/.../export/service/PackageDocumentExportService.java` (A2)
- `backend/securedoc-api/src/test/.../export/service/PackageDocumentExportSensitivityTest.java` (A2 test)
- `frontend/securedoc-ui/src/studio/CaseDrawer.tsx`, `src/studio/words.ts`, `src/studio/words.test.ts` (A1)
- `frontend/securedoc-ui/src/studio/EntityChooser.tsx` (new), `src/App.tsx`, `src/studio/studio.css` (A3)
- `frontend/securedoc-ui/e2e-oidc/sensitivity.spec.ts` (A1 tests, A4 proof), `e2e-oidc/adminIntegrations.spec.ts` (A4 proof), `e2e-a11y/shortcuts.spec.ts` (A3 tests), `e2e-a11y/proof0022.spec.ts` (new, A4 proof)
- `docs/work/0022-failure-inventory.md` (new, Part B), `docs/work/0022-report.md` (this report)
- Not committed: `docs/work/compare/0022/*.png`. Note: `docs/work/compare/` is not in `.gitignore` (only `docs/work/ui/compare/` is), so these files show as untracked.

## Commits (hash and message), or "not committed"

- `03e1299` docs(work): 0021 and U028 owner's answers; task 0022 (on `feature/sensitivity-levels`)
- `c486fac` Merge branch 'feature/sensitivity-levels' into feature/lexavault-r1b-tenant-identity
- `4608e51` feat: 0022 follow-ups (case panel level, local-folder audit levels, entity chooser dark mode) and failure inventory (on `feature/0022-followups-and-failure-inventory`), plus a docs commit adding this hash to the report

Nothing pushed. No merge beyond step 0.

## Not done, and why

- No leak or silent failure from Part B was fixed: the task says Part B changes no code.
- The sign-in screen itself (before the chooser) is still the older `auth-card` screen and does not follow the dark setting either. Only the chooser was in scope. The same fix (a Case File screen) would work for it.
- The `DOCUMENT_EXPORTED` detail still holds the original file name (leak list item 6). A2 added the level codes beside it but was not asked to remove it.

## Questions for the owner

1. The `DOCUMENT_EXPORTED` audit detail keeps the original file name, and A2 adds the levels next to it. Should the file name come out (a schema-free change in one line), or does the design chat want it kept for the export record?
2. The chooser now uses the Case File look even when `VITE_CASE_FILE_UI_ENABLED` is off, as the no-access screen already does. Is that acceptable, or should the older UI keep the old chooser?
3. Should the sign-in screen follow the dark setting too (same approach as A3)?
4. Should `docs/work/compare/` be added to `.gitignore`, like `docs/work/ui/compare/`?

## Suggested next step

The design chat designs the `LV-<AREA>-<NNN>` list from the inventory. Before the codes, it is worth deciding three bugs from it, because they change what a code can say:
- the watched-folder catch-all (#38);
- the silent sign-in refusals (#239);
- the processing loops with no limit (#346, #358).
