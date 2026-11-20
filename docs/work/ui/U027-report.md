# Report U027: Accessibility follow-ups

Date: 2026-11-20
Branch: `feature/ui-a11y-followups` (from `feature/lexavault-r1b-tenant-identity` after the step 0 merge)
Outcome: DONE. A1–A6 are in place and the tests pass. **The OIDC session-continuity script could not run on this machine**: Keycloak rejects the script's built-in test user (see A4). There are three questions for the owner.

## Step 0

1. Moved the proof folder: `compare/U026/` (49 pictures) → `docs/work/ui/compare/U026/`, which is git-ignored. Removed the empty root `compare/` folder. `git log --all -- compare` is empty, so nothing in it was ever committed. `e2e-a11y/proof.spec.ts` now writes to `docs/work/ui/compare/U026/`.
2. Committed on `feature/ui-accessibility`: `443e845` docs(work): U026 report: owner's answers; proof script writes to docs/work/ui/compare/U026. `docs/work/0021-task.md` left alone (untracked).
3. Merged into `feature/lexavault-r1b-tenant-identity` with no fast-forward: **`252f44b`** "Merge branch 'feature/ui-accessibility' into feature/lexavault-r1b-tenant-identity". Not pushed.
   - `.\Build-LexaVault-All.ps1`: PASS. Backend **620** (domain 51, application 229, scan bridge 47, infrastructure 18, API 275), 0 failures. TypeScript check, UI framework contract and Vite build PASS.
   - `npm run test:standalone`: **462** (auth 10, vocabulary 134, ui-framework 17, oidc-signin 112, tablet 85, a11y 104), 0 failures.
4. Created `feature/ui-a11y-followups` from it.

## A1. Field and control borders (WCAG 1.4.11)

New token `--cf-control-line` in `tokens.css`: light `#8a919b`, dark `#707680`. It is used for:
- text, number, date fields and selects (`.cf-field`, `.cf-ad-duration`, `.cf-ad-audit-pick`, `.cf-rp-range`, `.cf-rp-select`, `.cf-cr-dates`, the token textarea);
- the Cases filter box (`.cf-search`);
- segmented controls (`.cf-segment`, `.cf-method`, `.cf-cr-segment`);
- the off state of the Admin switch track (`.cf-ad-toggle-track`).

Checkboxes and radios are native and the browser draws their edge (`accent-color` ink), so they are unchanged. Cards, dividers, table lines and the command palette's input line are also unchanged.

Contrast of the new edge (WCAG formula):

| Background it touches | Light `#8a919b` | Dark `#707680` | Where |
|---|---|---|---|
| Surface (cards, dialogs, the field's own fill) | **3.18:1** | **3.93:1** | New case, Admin cards, Compliance dialog, every field's inside |
| Raised (inside the Compliance period strip) | 2.99:1 | 3.66:1 | Compliance report "Period" strip fill |
| Page background | 2.86:1 | 4.22:1 | Cases chips and filter, Reports filters (outside edge) |
| Old edge, for comparison | 1.29–1.60:1 | 1.24–1.57:1 | |

Dark meets 3:1 everywhere. In light, every edge is 3:1 or more against the white inside of the control. Against the grey page outside it (Cases and Reports filters) it is 2.86:1, and against the raised fill of the Compliance period strip it is 2.99:1. See Q1.

## A2. Single-key shortcuts (WCAG 2.1.4)

- **Review:** R, V, J, K, Shift R and the arrow keys work only while focus is in the queue, the page viewer or the decision panel. Review opens with focus on the current finding in the queue. After a decision, focus moves to the next finding in the queue, because the decided one leaves the page. J and K move focus along with the selection while focus is in the queue. Esc (clear the mark) still works anywhere outside fields and dialogs.
- **Cases:** J and K work only while focus is in the case list. Focus moves to the row they open.
- Neither fires in a text field, select, dialog (including the Shift R confirmation and "Find a case or action") or the account menu. The scope is defined in one place, `src/studio/shortcuts.ts`.
- Ctrl/Cmd K stays page-wide.
- No visible key hint said "anywhere", so the hints are unchanged.
- **Not changed: "/" on Cases** still moves focus to the filter from anywhere outside a field. It is a single-key shortcut that U026 did not list. See Q2.

## A3. Marking a place by keyboard: documented exception

No code change. The exception note is in the conformance table below (2.1.1). Backlog item **C4** was added to `docs/product/ENHANCEMENT_BACKLOG.md` section C.

## A4. Sign-in and entity chooser (markup only)

In `App.tsx`, a small `AuthScreen` wrapper renders `<main class="auth-shell">` and sets the page title. The four older screens use it:
- Verifying your session: "Sign in · LexaVault"
- Sign in: "Sign in · LexaVault"
- Select organization: "Choose an entity · LexaVault"
- Switching organization: "Choose an entity · LexaVault"

"Secure Document Platform" now reads "LexaVault". No sign-in or session logic, routes, redirects or stored values changed; the diff touches only the JSX wrappers and adds the title effect. The chooser's own words ("Select organization", "Select an organization…") are unchanged. See Q3.

Test harnesses waited for `main h1` after clicking Sign in. That would now match the sign-in screen itself, so they wait for `#cf-main h1` (the Case File main): `e2e-a11y/harness.ts`, `e2e-oidc/complianceReport.spec.ts` and two places in `e2e-tablet/tablet.spec.ts`.

**Checks:**
- `npm run test:oidc-signin`: **112 passed**, 0 failed (part of `test:standalone`).
- `Test-LexaVault-OidcSessionContinuity.ps1`: **FAILED, twice, before reaching LexaVault.** I started Keycloak (`docker start lexa-keycloak-db lexa-keycloak`) and the app (`Start-LexaVault-OIDC.ps1 -IssuerUri http://localhost:8181/realms/document-platform-local -Audience lexavault-api -ScannerMode SIMULATOR -AiModel qwen3.5:9b`). The script clicked LexaVault's new "Sign in" button and reached Keycloak. Keycloak then answered "Invalid username or password." for the script's default test user `platform.ops.admin`, so the browser never came back. I did not look for other credentials.
  - The script also waits for a "Dashboard" heading from the older UI. With `VITE_CASE_FILE_UI_ENABLED=true` in `.env.local`, the Case File screens have no such heading, so it would probably fail there too even with the right password.
  - Afterwards I stopped the app (`Stop-LexaVault-Local.ps1`) and Keycloak (`docker stop`), so the containers are as they were. See Q4.

## A5. Reports chart by keyboard

- The "Received and delivered" plot is one Tab stop: a group named "Received and delivered per day. Use the arrow keys to move between days."
- Left/Right (and Up/Down), Home and End move focus between days. Only one element is ever a Tab stop, so Tab from a day leaves the chart.
- Each day is named for screen readers, e.g. "Oct 20: 10 received, 8 delivered". The focused day shows the same tip as on hover; the tip itself is hidden from screen readers, so it is not read twice.
- Esc removes the tip and returns focus to the chart.
- Nothing changes on screen until the chart is focused. The axes and gridlines are hidden from screen readers, and the hidden data table stays.

## A6. Draft conformance table (WCAG 2.1 AA), updated

Only the changed rows are shown; every other row is as in U026.

| Criterion | Level | Status | Note |
|---|---|---|---|
| 1.3.1 Info and Relationships | A | Supports | Sign-in and chooser now have a main landmark (U027 A4) |
| 1.4.11 Non-text Contrast | AA | Supports | Control edges 3.2:1 light, 3.9:1 dark against the control (U027 A1; see Q1 for the light edge against the page) |
| 2.1.1 Keyboard | A | Partially supports | **Exception:** Marking a region by hand (a finding whose place was not found, and Continue manually) needs a pointer. Such a case can be finished by a colleague using a mouse; everything else in Review works by keyboard. Backlog C4. The Reports chart now works by keyboard. |
| 2.1.4 Character Key Shortcuts | A | Supports | Active only while focus is in their zone (U027 A2). Open: "/" on Cases (Q2) |
| 2.4.2 Page Titled | A | Supports | Every screen, including "Sign in · LexaVault" and "Choose an entity · LexaVault" |

## Tests

- `npm run build`: PASS.
- `npm run test:standalone`: **482 passed**, 0 failed (auth 10, vocabulary 134, ui-framework 17, oidc-signin 112, tablet 85, a11y **124**). `test:a11y` includes axe on every screen in light and dark with **0 violations**.
- New `e2e-a11y/shortcuts.spec.ts` (10 tests, run in light and dark):
  - Review opening focus; keys from the queue, with focus following the finding;
  - keys in the viewer and the decision panel; nothing from the body, top bar, account menu or Find dialog;
  - Shift R only in the zone;
  - Cases J/K in the list with focus moving; nothing outside it or in the filter;
  - the chart's arrows, Home, End, Esc, tip and single Tab stop;
  - sign-in and chooser `main` and titles.
- `e2e-a11y/keyboard.spec.ts`: the Review walkthrough presses V straight after Redact with no refocusing.
- The frozen `uiFrameworkContract.test.ts` is untouched and passes.
- The backend was not changed, so it was not rebuilt after the merge build.
- No raw hex outside `tokens.css`, no new breakpoint, no new dependency.

## Proof (`docs/work/ui/compare/U027/`, not committed)

- A1 before and after, light and dark: `a1-new-case-*`, `a1-admin-card-*`, `a1-reports-filters-*`, `a1-compliance-dialog-*` (custom dates shown), `a1-cases-tools-*`.
- A4 before and after, light and dark: `a4-sign-in-*`, `a4-entity-chooser-*`.
- A5, a day focused, light and dark: `a5-chart-day-focused-*`.
- At 1024 px, light and dark: `w1024-new-case-*`, `w1024-reports-*` (chart day focused). All the others are at 1440 px.
- Script: `e2e-a11y/proofU027.spec.ts`. It runs only with `U027_PROOF=before|after`.

## Commits

- `443e845` docs(work): U026 report: owner's answers; proof script writes to docs/work/ui/compare/U026 (on `feature/ui-accessibility`)
- `252f44b` the merge into `feature/lexavault-r1b-tenant-identity`
- `5f4d908` feat: U027 accessibility follow-ups (on `feature/ui-a11y-followups`)
- the commit that adds this report and the task file: docs(work): U027 report

Not pushed, not merged.

## Questions for the owner

1. **Light control edge against the grey page.** `#8a919b` is 3.18:1 against a field's white inside, but 2.86:1 against the page grey (Cases and Reports filters) and 2.99:1 against the Compliance period strip fill. If 3:1 must hold on both sides, `#828993` gives 3.53 / 3.32 / 3.18:1 (barely darker than now). Keep `#8a919b`, or use `#828993`?
2. **"/" on Cases** (focus the filter) is a single-key shortcut that works anywhere outside a field. It was not in U026's list, so it was left unchanged. Under 2.1.4 it should also be scoped (for example to the case list) or removed. Which?
3. **Chooser words.** Its title is now "Choose an entity · LexaVault", but its heading still says "Select organization" and "Select an organization…" (not changed: markup only). Change the words to match ("Choose an entity")?
4. **OIDC continuity script.** It needs a working password for its Keycloak test user (pass `-Password`, or set `LV_OIDC_TEST_PASSWORD`, without telling me the value), and it still expects the older UI's "Dashboard" heading. Should a task update the script for the Case File screens, and will you run it once with the right credentials?

## Suggested next step

Owner's answers to Q1–Q4, then the short accessibility statement from the conformance table.

## Owner's answers (2026-11-20, recorded by the design chat)

1. Light control edge: use `#828993`, so 3:1 holds against the control, the page and the period strip.
2. "/" on Cases: scope it to the case list, like J and K.
3. Chooser words: "Choose an entity" (heading) and "Choose an entity to continue" (text), text only.
4. OIDC continuity script: update it for the Case File screens in U028; the owner runs it once with the test user's password, typed into the command, never into a chat.
