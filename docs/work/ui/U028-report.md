# Report U028: Last accessibility follow-ups, and the sign-in continuity script

Date: 2026-11-20
Branch: `feature/ui-a11y-final` (from `feature/lexavault-r1b-tenant-identity` after the step 0 merge)
Outcome: DONE. A1–A5 are in place and the tests pass. The continuity script is ready for the owner's one run with the password (commands at the end). It stops cleanly when no password is given; I did not run it with a password.

## Step 0

1. Committed on `feature/ui-a11y-followups`: `b2ff767` docs(work): U027 report: owner's answers. `docs/work/0021-task.md` left alone (untracked).
2. Merged into `feature/lexavault-r1b-tenant-identity` with no fast-forward: **`20741f8`** "Merge branch 'feature/ui-a11y-followups' into feature/lexavault-r1b-tenant-identity". Not pushed.
   - `.\Build-LexaVault-All.ps1`: PASS. Backend **620** (domain 51, application 229, scan bridge 47, infrastructure 18, API 275), 0 failures. TypeScript check, UI framework contract and Vite build PASS.
   - `npm run test:standalone`: **482** (auth 10, vocabulary 134, ui-framework 17, oidc-signin 112, tablet 85, a11y 124), 0 failures.
3. Created `feature/ui-a11y-final` from it.

## A1. Light control edge

`--cf-control-line` light is now `#828993` (was `#8a919b`) in `tokens.css`. Dark stays `#707680`.

| Background it touches | Light `#828993` (was `#8a919b`) | Dark `#707680` (unchanged, from U027) |
|---|---|---|
| The control's white inside (surface `#ffffff`) | **3.53:1** (3.18) | 3.93:1 |
| Page grey (`#f2f3f4`): Cases filter and chips, Reports filters | **3.18:1** (2.86) | 4.22:1 |
| Compliance period strip fill (raised `#f7f8f9`) | **3.32:1** (2.99) | 3.66:1 |

All are 3:1 or more, on both sides of the edge, in light and dark (WCAG formula).

## A2. "/" on Cases

In `Desk.tsx`, "/" now works only while focus is in the case list (`CASES_KEY_ZONE` in `src/studio/shortcuts.ts`, the same zone as J and K) and moves focus to the filter. It does nothing from the page body, the All / Needs you buttons or the account menu, and in a field it types a "/" as usual. No visible hint mentioned "/", so no words changed. The scope note in `shortcuts.ts` now names "/".

New tests in `e2e-a11y/shortcuts.spec.ts` (each run in light and dark):
- "/" in the case list moves focus to the filter (filter stays empty, no case opens);
- "/" does nothing from the page body, the All button or the account menu; typing "a/b" in the filter gives "a/b".

## A3. Chooser words

In `App.tsx`, text only:
- heading "Select organization" → **"Choose an entity"**;
- "Select an organization that your identity is authorized to access." → **"Choose an entity to continue."**;
- the switching screen's visible line "Switching organization…" → **"Switching entity…"**.

No logic, routes or stored values changed (the diff is these three lines). Tests that matched the old words now match the new ones:
- `e2e-a11y/screens.spec.ts` (entity chooser axe scan);
- `e2e-oidc/signinRace.spec.ts` (two-entity user still gets the chooser);
- `e2e-a11y/shortcuts.spec.ts` chooser test now also checks the heading, the line, and that "organization" no longer shows;
- `test-tools/phase1-shared-issuer/Invoke-Phase1BrowserSignin.mjs`: its two "Select organization" checks now look for "Choose an entity". Not run: it needs the Phase 1 containers and their test users.

## A4. `Test-LexaVault-OidcSessionContinuity.ps1`

**Changes:**
- **What it waits for:** the Case File main heading (`#cf-main h1`) instead of the older UI's "Dashboard" heading, after sign-in and after each refresh.
- **The API call that refreshes:** a reload of Cases instead of the older "Dashboard refresh" button (the Case File Cases screen has no refresh button). Reloading makes the app's start-up and Cases requests through the API client, which refreshes the token when it is near expiry, so the three checks mean the same: refresh #1, refresh #2 with the stored (possibly rotated) refresh token, and fail-closed sign-out to "Sign in" when the refresh token is rejected.
- **Sign-in order:** it waits for LexaVault's "Sign in" button, clicks it, then fills Keycloak's form.
- **Entity chooser:** if the user lands on "Choose an entity" (more than one entity), the script stops with that message instead of timing out.
- **Credentials:**
  - `-Username` and `-Password` are kept, as are `LV_OIDC_TEST_USERNAME` / `LV_OIDC_TEST_PASSWORD`;
  - **the built-in default password is removed**, in the PowerShell and in the generated test;
  - new `-PromptForPassword`: asks for the password with hidden typing, so it never goes into the command history;
  - with no password it prints a clear message and exits with code 2 before checking services or writing anything;
  - the password is never printed. It is passed to Playwright only through the environment of that process, and the environment variables are restored afterwards. The generated test file holds no credentials.
  - Playwright's trace is turned off for this run (a trace records what is typed into fields), and its output folder (failure screenshots) is a temporary folder that is deleted at the end.
- **Readme:** a header (`<# … #>`) says what the script needs, the two ways to give the password, and how to run it.
- **Second copy:** `scripts/testing/Test-LexaVault-OidcSessionContinuity.ps1` is updated to the same file. The frozen contract test (`uiFrameworkContract.test.ts`, "ships identically in root and testing scripts") requires the two copies to match. My first `test:standalone` run failed on exactly that (ui-framework 16 passed, 1 failed) before I copied it; the test itself is unchanged.

**Run without a password** (this machine, as in U027):
- Started Keycloak: `docker start lexa-keycloak-db lexa-keycloak`.
- Started the app: `.\Start-LexaVault-OIDC.ps1 -IssuerUri http://localhost:8181/realms/document-platform-local -Audience lexavault-api -ScannerMode SIMULATOR -AiModel qwen3.5:9b`. The launcher reported "Frontend did not become ready on localhost:5173" (exit 1), but the frontend answered 200 a moment later; backend and Keycloak discovery also answered 200. It was only slow to start.
- `LV_OIDC_TEST_PASSWORD` was not set in my shell. `.\Test-LexaVault-OidcSessionContinuity.ps1` printed:
  ```
  STOPPED - no password for the Keycloak test user 'platform.ops.admin'.
    Run it again with -PromptForPassword, or set LV_OIDC_TEST_PASSWORD in your own terminal first.
    There is no built-in password. Nothing was run.
  ```
  Exit code 2; no test file left behind.
- Up to the credentials: a separate browser check clicked LexaVault's "Sign in" and reached Keycloak's form on `http://localhost:8181` (username field, password field and login button present). Nothing was typed.
- The generated test compiles: `playwright test --list` lists the one test.
- Stopped the app (`.\Stop-LexaVault-Local.ps1`) and Keycloak (`docker stop lexa-keycloak lexa-keycloak-db`). Both containers are stopped, as they were before.
- I did not search for, guess or read any password.

## A5. Conformance table (WCAG 2.1 AA), complete

| Criterion | Level | Status | Note |
|---|---|---|---|
| 1.1.1 Non-text Content | A | Supports | Icons hidden or named; page image has alt text; the Reports chart has a data table and named days |
| 1.2.1–1.2.5 Time-based media | A/AA | Not applicable | No audio or video |
| 1.3.1 Info and Relationships | A | Supports | Case File screens; sign-in and chooser have a main landmark (U027) |
| 1.3.2 Meaningful Sequence | A | Supports | |
| 1.3.3 Sensory Characteristics | A | Supports | |
| 1.3.4 Orientation | AA | Supports | Portrait and landscape tablets tested (U021) |
| 1.3.5 Identify Input Purpose | AA | Not applicable | No fields collect the user's own personal data |
| 1.4.1 Use of Color | A | Supports | Stages and targets also in words |
| 1.4.2 Audio Control | A | Not applicable | |
| 1.4.3 Contrast (Minimum) | AA | Supports | After the token fixes (U026) |
| 1.4.4 Resize Text | AA | Supports | 200% checked |
| 1.4.5 Images of Text | AA | Supports | Only customer logos |
| 1.4.10 Reflow | AA | Partially supports | Review and Continue manually are desktop and tablet screens (owner's decision, U021) |
| 1.4.11 Non-text Contrast | AA | Supports | Control edges 3:1 or more on both sides: light 3.5:1 against the control, 3.2:1 against the page, 3.3:1 against the period strip; dark 3.9 / 4.2 / 3.7:1 (U028 A1). Focus rings 3:1 or more (U026) |
| 1.4.12 Text Spacing | AA | Supports | |
| 1.4.13 Content on Hover or Focus | AA | Supports | Chart tip on hover or focus, Esc removes it; "?" notes are click/tap, Esc closes |
| 2.1.1 Keyboard | A | Partially supports | **Exception:** marking a region by hand (a finding whose place was not found, and Continue manually) needs a pointer. Such a case can be finished by a colleague using a mouse; everything else in Review works by keyboard. Backlog C4. The Reports chart works by keyboard (U027) |
| 2.1.2 No Keyboard Trap | A | Supports | |
| 2.1.4 Character Key Shortcuts | A | Supports | Every single-key shortcut works only while focus is in its zone: Review's R, V, J, K, Shift R in the queue, page viewer and decision panel; Cases' J, K and "/" in the case list (U027, U028 A2). None fire in fields, dialogs or menus. No open item |
| 2.2.1 Timing Adjustable | A | Supports | Toasts are status messages; what they confirm stays visible on the screen |
| 2.2.2 Pause, Stop, Hide | A | Supports | Only short loading animations; reduced motion honoured |
| 2.3.1 Three Flashes | A | Supports | |
| 2.4.1 Bypass Blocks | A | Supports | Skip to content |
| 2.4.2 Page Titled | A | Supports | Every screen, including "Sign in · LexaVault" and "Choose an entity · LexaVault" |
| 2.4.3 Focus Order | A | Supports | |
| 2.4.4 Link Purpose (In Context) | A | Supports | |
| 2.4.5 Multiple Ways | AA | Supports | Navigation and "Find a case or action" |
| 2.4.6 Headings and Labels | AA | Supports | The chooser's heading matches its title, "Choose an entity" (U028 A3) |
| 2.4.7 Focus Visible | AA | Supports | 2 px, 3:1 or more |
| 2.5.1 Pointer Gestures | A | Supports | No path or multi-finger gestures needed except drawing (2.1.1 note) |
| 2.5.2 Pointer Cancellation | A | Supports | Actions on release (native buttons) |
| 2.5.3 Label in Name | A | Supports | After U026 fix 5 |
| 2.5.4 Motion Actuation | A | Not applicable | |
| 3.1.1 Language of Page | A | Supports | |
| 3.1.2 Language of Parts | AA | Not applicable | English only |
| 3.2.1 On Focus / 3.2.2 On Input | A | Supports | |
| 3.2.3 Consistent Navigation / 3.2.4 Consistent Identification | AA | Supports | |
| 3.3.1 Error Identification | A | Supports | |
| 3.3.2 Labels or Instructions | A | Supports | |
| 3.3.3 Error Suggestion | AA | Supports | Messages say the allowed range |
| 3.3.4 Error Prevention (Legal, Financial, Data) | AA | Supports | Approve and policy changes confirm first |
| 4.1.1 Parsing | A | Supports | |
| 4.1.2 Name, Role, Value | A | Supports | |
| 4.1.3 Status Messages | AA | Supports | Toasts, preview, "?" notes in live regions |

## Tests

- `npm run build`: PASS.
- `npm run test:standalone` (after the script copy fix above): **486 passed**, 0 failed (auth 10, vocabulary 134, ui-framework 17, oidc-signin 112, tablet 85, a11y **128**: 124 + the 4 new "/" tests). `test:a11y` includes axe on every screen, chooser included, in light and dark with **0 violations**.
- The frozen `uiFrameworkContract.test.ts` is untouched and passes.
- The backend was not changed, so it was not rebuilt after the merge build.
- No raw hex outside `tokens.css`, no new breakpoint, no new dependency (checked on the diff).

## Proof (`docs/work/ui/compare/U028/`, not committed)

- A1, light, at 1440 and 1024 px: `a1-cases-filter-light-{1440,1024}.png` (Cases filter and chips on the page grey), `a1-compliance-period-strip-light-{1440,1024}.png` (the period strip with Custom chosen), `a1-compliance-dialog-light-{1440,1024}.png` (the whole dialog).
- A3, light and dark: `a3-entity-chooser-{light,dark}.png` ("Choose an entity", "Choose an entity to continue.").
- Script: `e2e-a11y/proofU028.spec.ts`. It runs only with `U028_PROOF=1` (`npx playwright test --config playwright.a11y.config.ts proofU028 --project light`).

## Commits

- `b2ff767` docs(work): U027 report: owner's answers (on `feature/ui-a11y-followups`)
- `20741f8` the merge into `feature/lexavault-r1b-tenant-identity`
- `d5a3c77` feat: U028 last accessibility follow-ups (on `feature/ui-a11y-final`)
- the commit that adds this report and the task file: docs(work): U028 report

Not pushed, not merged.

## Questions for the owner

1. **The continuity run.** Please run it once with the commands below and add the result here (PASS, or the step it stopped at). It needs the Keycloak password of `platform.ops.admin` (or give `-Username` for another test user with **one** entity). In U027 Keycloak refused the old built-in password, so the right one has to come from you.
2. **Phase 1 browser script.** `Invoke-Phase1BrowserSignin.mjs` now looks for "Choose an entity". It was not run (it needs the Phase 1 containers). Run it at the next Phase 1 regression, or should a task do that?

## Commands for the owner: run the continuity script once

From `C:\dev\lexavault\secure-document-platform`, in your own PowerShell window.

**Start:**
```powershell
docker start lexa-keycloak-db lexa-keycloak
.\Start-LexaVault-OIDC.ps1 -IssuerUri http://localhost:8181/realms/document-platform-local -Audience lexavault-api -ScannerMode SIMULATOR -AiModel qwen3.5:9b
```
If it says "Frontend did not become ready", wait a minute and open http://localhost:5173; it is usually only slow.

**Run** (either way; the password is never shown or saved):
```powershell
# A: the script asks for the password (hidden typing, not kept in the command history)
.\Test-LexaVault-OidcSessionContinuity.ps1 -PromptForPassword

# B: set it in this window's environment yourself, run, then remove it
$env:LV_OIDC_TEST_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host -AsSecureString 'Password')).Password
.\Test-LexaVault-OidcSessionContinuity.ps1
Remove-Item Env:LV_OIDC_TEST_PASSWORD
```
For another test user add `-Username <name>`. A pass ends with "PASS - OIDC session continuity acceptance completed."

**Stop:**
```powershell
.\Stop-LexaVault-Local.ps1
docker stop lexa-keycloak lexa-keycloak-db
```
