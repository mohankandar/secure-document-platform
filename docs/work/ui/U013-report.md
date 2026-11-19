# Report U013: the sign-in "no access" screen in the new design (finding 21, part 2)

Date: 2026-11-18
Branch: feature/ui-polish
Outcome: DONE. The sign-in refusal now shows the Case File no-access screen. One routing change was needed for dave.none (no entity), and the owner approved it in the session.

## Owner's answers (2026-11-18, in the session)

1. **dave.none takes the refusal path, the same as a 403.**
   - What I found: today dave.none (no entity) never reaches the "no access" screen. The server answers `/me/tenants` with `[]`. The app then took the generic branch: it showed "Sign-in could not be completed. Please try again." and cleared the local session. Only a 403 from `/me` showed "does not have access", and that branch keeps the session.
   - The owner chose "Treat as 403": an empty organization list goes down the existing 403 branch. It is the same refusal, the session is kept as for a 403, and the new screen is shown. Sign out ends the identity-provider session.
   - Real failures (network, 401, unexpected answers) keep the generic message and still clear the session.

## What was done

- **One no-access screen for both places:**
  - The Case File no-access page from U001 moved into its own file, `src/studio/NoAccess.tsx`.
  - The Case File app uses it as before.
  - The sign-in screen in `App.tsx` lazy-loads it (with the Case File styles and theme) when someone is refused.
- **Words, as the design and U001:**
  - "You don't have access to anything in LexaVault yet";
  - "You're signed in as <email>, but your account has no LexaVault role. Ask your administrator to add you to a LexaVault group, then sign in again.";
  - Sign out and Try again.
  - If the ID token has no email or user name: "You're signed in, but your account has no LexaVault role. …".
- **The signed-in email:**
  - At sign-in there is no `/me` answer, so the email comes from the ID token: its `email` claim, or `preferred_username`.
  - New `signedInIdentity()` in `src/api/auth.ts` does this for display only. Nothing is decided from it, and nothing is logged or sent.
- **Buttons:**
  - Sign out calls the existing `endOidcSession()`: it clears the local session and goes to the identity provider's sign-out with the ID token.
  - Try again reloads, so the session is checked again.
  - This replaces the old "Sign in with a different account" button on the refusal screen.
- **What changed in `App.tsx`, and only this:**
  1. An empty organization list throws a `NoOrganizationError` instead of a plain `Error`.
  2. The discovery fallback rethrows it, so it can't fall through to `/me`.
  3. The catch treats it like a 403: `authDenied`, and the session is kept.
  4. When `authDenied` is set, the no-access screen is shown instead of the sign-in card. The old refusal sentence is gone.
- Nothing else in sign-in changed: the callback, the token checks, the tenant chooser, single-entity selection, session expiry and organization switching are as before.

## Phase 1 browser script: exactly which lines changed

`test-tools/phase1-shared-issuer/Invoke-Phase1BrowserSignin.mjs`, in `classify()`:

- Line 48 was:
  `  if (text.includes('Sign-in could not be completed') || text.includes('does not have access')) return 'REFUSED';`
- It is now lines 48 and 49:
  `  // UI task U013: the refusal is the Case File no-access screen (it replaces "does not have access" and, for no entity, "Sign-in could not be completed").`
  `  if (text.includes("You don't have access to anything in LexaVault yet")) return 'REFUSED';`
- The check is now stricter: a generic sign-in failure no longer counts as an expected refusal.
- The WORKSPACE check is unchanged. The new screen has a Sign out button but no entity name, so it can't be read as a workspace.

## Commands run and results

- `npm run build` (frontend): passed.
- `npm run test:standalone`: passed:
  - `test:auth` 8;
  - `test:vocabulary` 75;
  - `test:ui-framework` 17;
  - `test:oidc-signin` 66 (1 new, 1 changed).
- **Sign-in and entity switching:** `signinRace.spec.ts` and `entitySwitch.spec.ts` were run 3 more times: 11 passed each time.
- **Backend:** `cd backend; mvn clean install` passed: domain 31, application 138, scan bridge 47, infrastructure 8, API 229.
  - Why I rebuilt it: the jar on disk was older than the last backend commit (`c1a65cf`), so I couldn't name the commit it was built from. There is no backend change in this task.
- **Phase 1:**
  - An earlier Phase 1 backend and UI were still running on the old jar. I stopped them with `Remove-Phase1.ps1 -KeepContainers`, which keeps containers and data.
  - Started again with `Start-Phase1.ps1 -JarBuiltFromCommit 4474595`. The first try failed only because I had added `2>&1`, which makes PowerShell 5.1 treat docker's normal progress messages as errors. It ran without it.
  - `Invoke-Phase1Checks.ps1`: **60 passed, 0 failed** (R-04 to R-06 still show dave's `[]` and 403s).
  - Why 60 and not 68 (task 0014): the 8 role-mapping checks M-01 to M-08 were added on `feature/phase2-role-mapping` (commit `05c358e`), which is not merged into `feature/ui-polish`, so this branch's script has only the 60 earlier checks.
  - `Start-Phase1Ui.ps1`, then `Invoke-Phase1BrowserSignin.mjs --mode dev`: **4 passed, 0 failed**:
    - carol.ab, alice.a and bob.b: workspace as before;
    - dave.none: REFUSED by the new wording.
  - The console line for dave is the existing "verification failed" log, now naming `NoOrganizationError`.
  - The normal containers were untouched; `lexa-keycloak` and `lexa-keycloak-db` were already stopped (exited 26 hours ago).
  - The Phase 1 backend (port 18080) and dev UI (15173) are left running, as they were.
- **Screen checks:**
  - Light and dark, at 1440 and 1024 px, in Edge (Playwright), using the sign-in test harness (mocked identity provider and API, an account with no entity).
  - It matches the design, with no sideways scroll.
  - On the real Phase 1 screen, only the browser script's REFUSED result was checked. I didn't sign in as dave by hand, so as not to handle the Phase 1 test passwords.
- **Rules:**
  - No raw hex, no new breakpoint, no new dependency.
  - `uiFrameworkContract.test.ts`, `src/index.css` and the legacy pages are unchanged.

## Tests: counts per module, and any failures with their output

- **Changed: `e2e-oidc/signinRace.spec.ts` › "no-entity user still gets the refusal".** This is a change to an existing test, made because the owner changed the behaviour (answer 1).
  - Before, it expected "Sign-in could not be completed. Please try again." and a cleared access token.
  - Now it expects:
    - the no-access heading and "You're signed in as race.user@example.test, but your account has no LexaVault role.";
    - no generic message, no workspace, and no `/me` call;
    - the access token kept;
    - Sign out going to the identity provider's sign-out with an `id_token_hint`.
  - The test's fake token now carries an `email` claim, used only by this screen. The other tests in the file are unchanged and pass.
- **New: `signinRace.spec.ts` › "a refused /me shows the same no-access screen"** (one entity listed, `/me` answers 403).
- **Unchanged and passing:** `errorStates.spec.ts` › the Case File no-access page (U001), which now uses the moved component.
- **Failures during the work:** none in the tests. The only failure was the Phase 1 start with `2>&1`, described above.

## Files changed

- `frontend/securedoc-ui/src/studio/NoAccess.tsx` (new; moved from `CaseFileApp.tsx`)
- `frontend/securedoc-ui/src/studio/CaseFileApp.tsx` (uses it)
- `frontend/securedoc-ui/src/App.tsx` (the routing change and the screen, above)
- `frontend/securedoc-ui/src/api/auth.ts` (`signedInIdentity()`, display only)
- `frontend/securedoc-ui/e2e-oidc/signinRace.spec.ts` (one test changed, one added)
- `test-tools/phase1-shared-issuer/Invoke-Phase1BrowserSignin.mjs` (line 48, above)
- `docs/work/ui/U013-report.md`

## Commits (hash and message), or "not committed"

- The work commit includes this report and the Phase 1 script change: "feat(ui): sign-in no-access screen in the Case File design (UI task U013)". Its hash is in the chat summary.

## Not done, and why

- Nothing in the task.

## Questions for the owner

- None.

## Suggested next step

U014. Its task file and those for U015 to U017 appeared in `docs/work/ui/` during this run; they are not committed by me.
