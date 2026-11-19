# Report U018: final fixes, unused-file list and the pre-merge checks

Date: 2026-11-18 to 2026-11-19
Branch: feature/ui-polish
Outcome: PARTLY DONE. Two items stopped, as the task says, and need the owner:
- 2 e2e specs fail with the policy's lists (the tests are wrong, not the app);
- the live U004 check could not show the panel, because fixture 401 gives no finding with the AI off.

Everything else is done and passed.

## What was done

**0. Owner's answers.** Recorded in `U017-report.md` under "Owner's answers" and committed first (`cdf4941`). The task file was committed before that (`595dddb`).

**1. Spec.** In `SPEC.md`'s who-sees-what, the Retry processing row now says it is from Support (the problem page) only, and that the IT Administrator never sees Cases. `designs/*.html` is unchanged.

**2. Tests: one shared helper with the policy's lists.**
- New: `e2e-oidc/policyCapabilities.ts`, with `capabilitiesFor(...roles)`. It copies `ProductAuthorizationPolicy` exactly, and gives the union of the lists for several roles.
- New: `src/security/policyCapabilities.test.ts` (2 tests, run by `test:auth`). It reads `ProductAuthorizationPolicy.java` and fails if the copy ever drifts from it.
- These specs now take their lists from the helper and pass: `adminAudit`, `adminBusiness`, `adminIntegrations`, `adminPlatform`, `adminSignIn`, `caseRows`, `newCase`, `reports`, `review`, `signinRace` and `support`.
- What changed in practice:
  - the IT Administrator and Platform Operations lost `DASHBOARD_ACCESS`;
  - the IT Administrator gained `TENANT_AUTH_MANAGE` and `TECHNICAL_HOLD_MANAGE`;
  - Platform Operations gained `PLATFORM_DIAGNOSTICS`;
  - the Business Administrator gained `REPORT_OPERATIONAL_VIEW` and `REPORT_EXPORT`;
  - the processors and supervisors gained the full review set (`FINDING_DECISION`, `REDACTION_ADJUST`, `REVIEW_SUBMIT`, `PACKAGE_REMOVE_FROM_DASHBOARD`);
  - `newCase` lost `DOCUMENT_UPLOAD`, which is not a policy capability.
  
  Every Admin, Audit, Integrations, Platform, Sign-in and Support test passed with the corrected lists, so **no permission bug was found** there.
- **Stopped: `entitySwitch` and `errorStates`** (5 tests). With the policy's lists they fail. Both files were put back exactly as committed, so they still use their old lists. See question 1.

**3. Start script (`Start-LexaVault-Local.ps1`). Script only, no backend change.**
- **`-AiOff`:**
  - starts the backend with the existing setting `SECUREDOC_AI_PROVIDER=heuristic`, the backend's own rules-only reader;
  - skips every Ollama step;
  - stops with a message if a backend is already running on 8080, because that backend would keep its own AI setting (fail closed).
  
  Checked live: the platform overview reported the AI as `provider: heuristic`.
- **The false "Frontend did not become ready" stop:**
  - Cause: the port check used Windows PowerShell's `TcpClient()`, which is IPv4 only, but Vite on Node 22 listens on `[::1]:5173` only. `netstat` confirmed this: 5173 was on `[::1]` alone.
  - Fix: the check now tries `127.0.0.1` and `::1` for `localhost`.
  - Checked live: "Frontend is ready on localhost:5173", with no stop.

**4. Unused, proposed for deletion.** This is the list only. Nothing was deleted. Method:
- a script listed every `cf-` class in the studio CSS, every export in `src/studio`, every static `words.ts` entry and every top-level e2e helper;
- each candidate was then checked by hand for dynamic class names (there are none: no `cf-${…}` anywhere);
- each was also checked at the branch base `82f4ef57`.

There are no image files, icon files or unused design tokens: all `--cf-*` tokens are used, and every bundled font weight (Sans 400/500/600, Mono 400/500) is used.

| Item | Where | Proof it is unused | Since |
|---|---|---|---|
| `.cf-ad-inline` | `admin.css` 69–70, 112–114, 225 | No `.ts`/`.tsx` names it | Its last use was removed in U010 (`bc7b7fa`) |
| `.cf-ad-steps` | `admin.css` 134–139 | No `.ts`/`.tsx` names it | U009 (`5a86b9c`, Sign-in became a read-only card) |
| `.cf-ad-search` | `admin.css` 142–143 | No `.ts`/`.tsx` names it | Already unused at the branch base |
| `.cf-ad-nodes` | `admin.css` 270–271 | No `.ts`/`.tsx` names it | U010 (`bc7b7fa`) |
| `.cf-ad-narrow` | `admin.css` 289 | No `.ts`/`.tsx` names it | U017 (`5ec8b56`) |
| `.cf-drawer-section` | `studio.css` 324–325 | No `.ts`/`.tsx` names it | Already unused at the branch base |
| `.cf-step` | `studio.css` 442 | No `.ts`/`.tsx` names it | U015 (`6a8b3f2`) |
| `.cf-confidence`, `.cf-confidence-row` | `studio.css` 607–609 | No `.ts`/`.tsx` names them | U004 (`0ee0271`) |
| `FindingStrip` (component) | `atoms.tsx` | Exported, never imported or used, not in tests | U003 (`818f01f`, rows use next-step words) |
| `useTheme` | `theme.ts` | Exported, never used anywhere (`useThemePreference` is used) | U002 (`447f7c9`) |
| `REASON_COPY` | `data.ts` | Exported, never used (`words.ts` `REASON_TEXT` replaced it) | U001 (`00676a6`) |
| `useMe` | `data.ts` | Exported, never used | Already unused at the branch base |
| `redactedArtifactAvailable` | `data.ts` | Exported, never used | Already unused at the branch base |
| `canContinueManually` | `support/supportRules.ts` | Used only by `supportRules.test.ts` (4 asserts); no screen calls it | U012 (`9cead8f`). Delete it with its test, or use it on the screen. |
| `CASE_GROUPS.needsYou`, `.stopped`, `.delivered` | `words.ts` | No code reads them. `Desk.tsx` lines 53, 58, 60, 61 and 122 spell "Needs you", "Stopped" and "Delivered" itself. | Better fix: have `Desk.tsx` use them (the vocabulary rule), not delete them. |
| `ACTION_WORDS.processAgain` | `words.ts` | No code reads it. `data.ts` line 119 spells "Process again" itself. | Same: better used than deleted. |
| `ADMIN_CAPS` (test helper) | `e2e-oidc/caseRows.spec.ts` line 14 | Declared, never used in that file | Since that spec was added |

**5. Pre-merge checks.** The results are below. The local app was stopped afterwards with `Stop-LexaVault-Local.ps1`. The Phase 1 apps were stopped with `Remove-Phase1.ps1 -KeepContainers`, so the Phase 1 containers are kept as the regression environment.

## Commands run and results

- **Backend, `cd backend; mvn clean install`: passed, 453 tests, 0 failures.**
  - domain 31;
  - application 138;
  - scan bridge 47;
  - infrastructure 8;
  - API 229.
  
  `CLAUDE.md` still says 430 (application 116, API 228). The count went up with the backend changes in the UI tasks (U004, U006, U016). It was not touched in this task.
- **Frontend: `npm run build` passed. `npm run test:standalone` passed:**
  - `test:auth` 10 (was 8, plus 2 policy tests);
  - `test:vocabulary` 80;
  - `test:ui-framework` 17;
  - `test:oidc-signin` 67.
  
  This was the final run, after `entitySwitch` and `errorStates` were put back.
- **Earlier failing run (corrected lists in every spec): 62 passed, 5 failed.** All 5 are in the two stopped specs:
  ```
  entitySwitch.spec.ts:149  two entities: switching from A to B shows only B data and B roles, and back again
      Error: B-CASE-2002 did not appear after the switch
  entitySwitch.spec.ts:181  a request still in flight during the switch is discarded, not shown   (element not found)
  entitySwitch.spec.ts:263  the switch works by keyboard                                          (element not found)
  errorStates.spec.ts:107   Reports with an answer of the wrong shape ... other screens keep working (element not found)
  errorStates.spec.ts:161   Cases with an answer of the wrong shape shows the error card ...       (element not found)
  ```
  The error context showed the nav as Reports · Admin for the Business Administrator, with no Cases link. That is what the policy grants.
- **Phase 1:**
  - `Start-Phase1.ps1 -JarBuiltFromCommit cdf4941`: ready, and the backend was connected to port 15432 only.
    - The first try found an old Phase 1 backend still running, so I stopped it with `Remove-Phase1.ps1 -KeepContainers`.
    - The second try stalled after "Backend is UP" when the machine slept overnight. I stopped it and ran it again, and it completed.
  - `Invoke-Phase1Checks.ps1`: **60 passed, 0 failed**.
  - `Invoke-Phase1BrowserSignin.mjs --mode dev`: **4 passed, 0 failed** (carol.ab with the chooser and switch A → B → A, alice.a, bob.b, and dave.none refused).
  - `Invoke-Phase1BrowserSignin.mjs --mode prod`: **4 passed, 0 failed**.
- **Live U004 check, with the AI off. STOPPED, see question 2.**
  - Started with `.\Start-LexaVault-Local.ps1 -ScannerMode SIMULATOR -AiOff -EnableRollingFileLog` (the rolling file, so the logs could be searched).
  - Fixture 401 (`03-manual-review/03_manual_review_ambiguous_score.pdf`) was opened through New case as Document Processor, under the fake contract number `999911190859`.
  - The case went straight to **Delivered** (`COMPLETED`) with **0 findings**. The heuristic reader does not recognise this fixture's score, so nothing reached Review, and the panel and the queue could not be checked.
  - Document intelligence is already DISABLED for the local tenant, as the fixture README asks.
  - **Search for the fixture's fake value** (read from the fixture README by the script, never printed):
    - backend log: **0 matches**;
    - `audit_records`: **0 matches**, of 457 rows.
  - **Positive control:** the case's internal ID was found in 15 log lines and in 6 audit rows (created, submitted, processing started and completed, export, retention), so the search does find what is there.
  - The case number was in neither, because audit records carry no loan numbers (the known gap). With no finding at all, this proves less than the task wanted.
  - The case is left in the local data as Delivered. Each case opened this way adds one to the local data.
  - Stopped with `Stop-LexaVault-Local.ps1`.
- **Rules:**
  - no raw hex outside `tokens.css` (no CSS was changed);
  - no new breakpoint;
  - no new dependency;
  - `uiFrameworkContract.test.ts` and the legacy pages are unchanged.

## Tests: counts per module, and any failures with their output

- Backend: as above, 453 tests, 0 failures.
- Frontend: `test:auth` 10, `test:vocabulary` 80, `test:ui-framework` 17 and `test:oidc-signin` 67, with 0 failures.
- Added: `src/security/policyCapabilities.test.ts` (2 tests).
- Changed: 11 e2e specs, which now take their capabilities from the helper. No assertion was changed.
- The failures from the run with all specs corrected are under "Commands run" above. Their specs were put back and not loosened.

## Files changed

- `docs/work/ui/SPEC.md`: the Retry row.
- `docs/work/ui/U017-report.md`: the owner's answers (separate commit).
- `Start-LexaVault-Local.ps1`: `-AiOff`, and the IPv4 and IPv6 port check.
- `frontend/securedoc-ui/e2e-oidc/policyCapabilities.ts` (new).
- `frontend/securedoc-ui/src/security/policyCapabilities.test.ts` (new).
- `frontend/securedoc-ui/package.json`: `test:auth` runs the new test.
- 11 specs in `frontend/securedoc-ui/e2e-oidc/`: `adminAudit`, `adminBusiness`, `adminIntegrations`, `adminPlatform`, `adminSignIn`, `caseRows`, `newCase`, `reports`, `review`, `signinRace` and `support`.
- This report.

## Commits (hash and message), or "not committed"

- `595dddb` docs(ui): U018 task (written by the design chat)
- `cdf4941` docs(ui): U017 report with the owner's answers (applied in U018)
- "fix(ui): e2e capability lists from the policy; start script AI-off switch and IPv6 port check (UI task U018)". The hash is in the chat summary.

Not pushed, not merged.

## Not done, and why

- `entitySwitch` and `errorStates` still use their old capability lists (question 1).
- The live panel and queue check (question 2).
- No deletions, as the task says.
- **Owner's answer 2, for the backend work after the UI series:** a backend automated test that creates a review finding with a known fake value and proves the value appears in no log output and no audit record. Until then, the mocked `review.spec.ts` plus the 2026-11-19 log and audit search stand for the live text-found check.

## Questions for the owner

1. **Two specs sign in with a role that cannot see Cases, but expect to see Cases.**
   - `entitySwitch`: Entity B is a Report Viewer, yet the test expects B's case list.
   - `errorStates`: a Business Administrator is expected to see Cases.
   
   The policy gives neither role `DASHBOARD_ACCESS`, so the app is right to hide Cases. The fault is in the test personas, not a permission bug. My proposal keeps every check as strong as it is:
   - make Entity B a **Document Processor**. It still differs from A's Document Supervisor: no Approve and no Process again. The test also checks that Reports appears in B and goes away again; with B as a Processor that check would need to use Approve instead;
   - give the `errorStates` person **Business Administrator + Document Supervisor**, a real combination, because that spec covers Cases, Reports and Admin.
   
   Should I change the personas this way? `entitySwitch` touches entity switching, so I would run it several times.
2. **The live text-found check needs a different fixture or the AI on.**
   - With the AI on (U004), fixture 401 was delivered without review.
   - With the AI off (now), it gives no finding at all.
   - The OCR variant 402 reached Review, but without a place on the page, so it has no text by design.
   
   Options:
   - **(a)** try 402 with the AI off (a tracked fixture; it adds one local case);
   - **(b)** accept the mocked `review.spec.ts` test plus today's search;
   - **(c)** add a fixture with a located, uncertain finding (a new PDF, which you said no to in U004).
   
   Which one?
3. **The unused list:** may I delete the items in the table?
   - For the `CASE_GROUPS` and `ACTION_WORDS` entries, I suggest the opposite: make `Desk.tsx` and `data.ts` use them, as the vocabulary rule asks.
   - For `canContinueManually`: delete it with its test, or use it on the problem page?
4. **A small finding, not fixed:** the brand link's accessible name is `${branding.applicationName} home` (`CaseFileApp.tsx` line 79). When branding sends no name, it reads "undefined home". I saw it in the mocked tests; real branding has a name. Should I add a fallback ("LexaVault home")?
5. **`src/security/uiAuthorization.test.ts`** (a unit test of the legacy demo-role gates, not an e2e spec) has its own role lists that are not the policy's. For example, it gives the Business Administrator, the IT Administrator and Platform Operations `DASHBOARD_ACCESS`. It was outside this task's "e2e" scope, so it was left. Should it use the same helper?

## The owner's click-through list

Start with `.\Start-LexaVault-Local.ps1 -ScannerMode SIMULATOR -AiModel qwen3.5:9b`, then switch roles in the account menu (Test role · local only).

1. **Cases as Document Supervisor:** each row has one action button (Review, Approve, Continue manually), and the stages have their own colours.
2. **A case panel as Document Supervisor** (open a delivered case): "Process again" shows the runs left, and the codes sit under a collapsed Technical details.
3. **New case as Document Processor:** "PDF only · up to 50 MB each" is shown up front, a non-PDF file is marked red, and Create case stays disabled until it is removed.
4. **Review as Document Processor:** the queue shows only the masked value; the decision panel shows the text found, its line of context, and zoom (−, %, +, Fit).
5. **Reports as Report Viewer:** only the headline, trend, service levels and By entity are shown, with no Export CSV.
6. **Admin › Redaction & retention as Business Administrator:** durations are in days, and "Keep stopped cases for" is there.
7. **Admin › Integrations as IT Administrator:** the tabs read Sending to SDV, Sending by barcode and Receiving, and the watched-folder status is shown once.
8. **Support as IT Administrator:** open a problem; the page is one centred column, and Retry is offered here only. Cases is not in this role's nav.
9. **Admin › Platform operations as Platform Operations:** the sub-tabs are plain words with a yellow line under the current one, and Processing storage has its green "Ready" pill.
10. **Admin › Audit trail as IT Administrator:** the rows are sentences with a 13 px mono time, and the result is a plain green or red word.

Check each in light and dark (account menu › theme).

## Suggested next step

The owner answers questions 1 to 3. Then a short follow-up applies them, and the owner does the click-through before approving the merge of `feature/ui-polish`.

## Owner's answers (2026-11-19)

Apply these as a short follow-up on `feature/ui-polish`, then stop. Do not push or merge.

1. **Personas:** yes, as proposed.
   - In `entitySwitch`, Entity B becomes a Document Processor. Check the difference with Approve instead of Reports.
   - In `errorStates`, the person is Business Administrator + Document Supervisor.
   - Both use the shared helper. Run `entitySwitch` 3 times.
2. **Live text-found check:** (b). Accept the mocked `review.spec.ts` plus today's log and audit search for merging. Add a **backend automated test** to the backend work after the UI series: it creates a review finding with a known fake value and proves the value appears in no log output and no audit record. Note it in the report's "Not done".
3. **Unused list:**
   - **Delete:** `.cf-ad-inline`, `.cf-ad-steps`, `.cf-ad-search`, `.cf-ad-nodes`, `.cf-ad-narrow`, `.cf-drawer-section`, `.cf-step`, `.cf-confidence`, `.cf-confidence-row`, `FindingStrip`, `useTheme`, `REASON_COPY`, `useMe`, `redactedArtifactAvailable` and `ADMIN_CAPS`. Do it in one clean-up commit.
   - **Use, don't delete:** `CASE_GROUPS.needsYou`, `.stopped` and `.delivered` in `Desk.tsx`, and `ACTION_WORDS.processAgain` in `data.ts`.
   - **`canContinueManually`:** use it where the screens decide whether to show Continue manually (the case panel, Cases row and problem page, wherever they compute it today), so the rule lives and is tested in one place. Keep its test.
4. **Brand link name:** yes, fall back to "LexaVault home" when branding sends no name, with a test.
5. **`uiAuthorization.test.ts`:** yes, use the shared helper's lists. If that exposes a real bug, stop and report it.
6. **`CLAUDE.md` test count:** update it to the current backend counts.

Then run `mvn clean install`, `npm run build` and `npm run test:standalone`, and report the counts here under "Follow-up results".

## Follow-up results (2026-11-19)

Outcome: DONE. Every answer applied; no real bug found. Not pushed, not merged.

**1. Personas** (`entitySwitch.spec.ts`, `errorStates.spec.ts`), both now on `capabilitiesFor(...)`:
- `entitySwitch`: Entity A is a Document Supervisor, Entity B a Document Processor. The cases are now waiting for approval, so the difference is checked with the row's Approve button: "Approve A-CASE-1001" in A, no Approve button in B, and back again in A. The Reports and New case checks are gone (both roles have New case, neither has Reports).
- `errorStates`: the person is Business Administrator + Document Supervisor.
- No other assertion was changed. `entitySwitch` run 3 times on its own: 7 passed each time.

**2. Live text-found check:** (b) accepted. The backend test is in "Not done" above.

**3. Unused list.**
- Deleted in one clean-up commit: the 15 items in the answer. `.cf-ad-inline-actions` (the `admin.css` lines 69, 70 and 225 the table listed with `.cf-ad-inline`) is in use and was kept; only the `.cf-ad-inline` rules themselves were removed. Two imports that only `useMe` and `REASON_COPY` used went with them.
- Now used: `CASE_GROUPS.needsYou`, `.stopped`, `.delivered` in `Desk.tsx` (group titles and the Needs you button), `ACTION_WORDS.processAgain` and `.continueManually` in `data.ts`. New test in `words.test.ts`: fails if `Desk.tsx` or `data.ts` spell these words themselves (checked: it fails on the old code).
- `canContinueManually` now decides Continue manually on the case panel (`caseActions` in `data.ts`) and on the Cases row (`rowAction` in `caseRules.ts`). The problem page computes no Continue manually button: it only shows a note for IT ("A Document Processor or Supervisor can also finish it"), which depends on the problem, not on the viewer's role, so it was left. New test in `caseRules.test.ts`: the row offers Continue manually exactly when `canContinueManually` says so. Its own test is kept.
- Found while deleting, not deleted (not on the list): with `FindingStrip` gone, the `.cf-strip`, `.cf-strip-mark`, `.cf-strip-more`, `.cf-strip-settled` and `.cf-strip-none` rules in `studio.css` (lines 204–208) are unused.

**4. Brand link:** new `brandHomeName()` in `words.ts`; `CaseFileApp.tsx` uses it. No name, an empty name or spaces read "LexaVault home". Tested in `words.test.ts`.

**5. `uiAuthorization.test.ts`:** now takes every role's list from the shared helper and checks every surface for each role (open or closed). No real bug: `canAccessSurface` is a plain capability-to-surface map, and no screen calls it (only `ROLE_OPTIONS` from that file is used by the app). The expectations changed to what the policy grants: the Business Administrator has no Cases, New case or Review; the IT Administrator has no Cases and no Reports; Platform Operations has no Cases, Reports or Integrations. The internal-admin case now uses the union of all roles. Also checks that the demo role picker lists exactly the policy's roles.

**6. `CLAUDE.md`:** the backend count is now 453 (domain 31, application 138, scan bridge 47, infrastructure 8, API 229). Its frontend counts (`test:auth` 8, `test:oidc-signin` 9) are also out of date and were not touched; today they are 10 and 67, plus `test:vocabulary` 83.

### Commands run and results

- `cd backend; mvn clean install`: **BUILD SUCCESS, 453 tests, 0 failures** (domain 31, application 138, scan bridge 47, infrastructure 8, API 229).
- `cd frontend/securedoc-ui; npm run build`: passed.
- `npm run test:standalone`: **passed**: `test:auth` 10, `test:vocabulary` 83 (was 80, plus 3 new), `test:ui-framework` 17, `test:oidc-signin` 67. 0 failures.
- Also run after the clean-up commit alone: build passed, standalone 10 / 80 / 17 / 67.
- `npx playwright test --config playwright.oidc-signin.config.ts entitySwitch errorStates`: 12 passed; then `entitySwitch` 3 times: 7, 7, 7 passed.

### Files changed

- Clean-up commit: `admin.css`, `studio.css`, `atoms.tsx`, `theme.ts`, `data.ts`, `e2e-oidc/caseRows.spec.ts`.
- Follow-up commit: `Desk.tsx`, `data.ts`, `caseRules.ts`, `caseRules.test.ts`, `words.ts`, `words.test.ts`, `CaseFileApp.tsx`, `src/security/uiAuthorization.test.ts`, `e2e-oidc/entitySwitch.spec.ts`, `e2e-oidc/errorStates.spec.ts`, `CLAUDE.md`, this report.

### Commits

- `1eebbc7` chore(ui): remove unused classes, exports and a test helper (U018 follow-up, owner's answer 3)
- "fix(ui): U018 follow-up: policy personas, shared Continue manually rule, words from words.ts, brand link fallback". The hash is in the chat summary.

### Next

The owner's click-through (list above), then the decision on merging `feature/ui-polish`.
