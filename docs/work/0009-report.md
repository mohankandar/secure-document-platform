# Report 0009: close Phase 1, repair the old auth test, add the entity switch

Date: 2026-11-17
Outcome: DONE.

| Part | Result |
|---|---|
| A | Phase 1 merged and the feature branch pushed |
| B | Auth test fixed on its own branch (not merged) |
| C | Entity switch built on `feature/entity-switch` and pushed for review (not merged) |

The Phase 1 environment is running with the switch on `http://localhost:15173/`.

## Part A: close Phase 1

- **Merges:** both into `feature/lexavault-r1b-tenant-identity`, both fast-forwards with no merge commits and no conflicts.
  - `fix/single-entity-signin-race`: `49a8a70..e62bc26`.
  - `test/phase1-shared-issuer`: `e62bc26..523052f`.
- **Tests on the feature branch:**

| Suite | Result |
|---|---|
| Backend `mvn clean install` | BUILD SUCCESS, **426 tests, 0 failures**: domain 31, application 116, scan bridge 47, infrastructure 8, API 224 |
| Frontend `test:ui-framework` (type check + contract) | 17/17 |
| Frontend `build` | passed |
| Frontend `test:oidc-signin` | 3/3 |
| Frontend `test:auth` | 7/8, the known old failure (Part B) |

- **Pushed:** `feature/lexavault-r1b-tenant-identity` `96868d0..523052f`. `main` is untouched at `1b69432`.
- **Guardrails:**
  - Migration 037 is not in `db.changelog-master.xml` (0 matches).
  - The shared-issuer setting still defaults to `false`: `SecurityProperties.java:26` and `JdbcOidcConnectionBindingRepository.java:22`. It is in no yml file.

## Part B: the old `test:auth` failure

- **Branch:** `fix/auth-test-roles`, from the feature branch. Commit `9c0e0cb`. Not merged, not pushed.
- **The fix:** `src/security/uiAuthorization.test.ts` now expects the six current product roles. `ADMIN` is not restored, and the product role list in `uiAuthorization.ts` is unchanged. `test:auth`: 8/8.
- **New command:** `npm run test:standalone` runs every frontend suite that needs no running application: `test:auth`, then `test:ui-framework`, then `test:oidc-signin`. Ran: 8 + 17 + 3 passed. It is test-only; the product build (`npm run build`, `Build-LexaVault-All.ps1`) is unchanged.

Frontend test scripts the standard build does not run:

| Script | What it needs | In `test:standalone`? |
|---|---|---|
| `test:auth` | nothing | yes |
| `test:oidc-signin` | nothing (starts its own dev server; uses the installed Edge) | yes |
| `test:e2e` | the app already running in LOCAL mode on 5173, plus Playwright's own Chromium, which is not downloaded on this machine | no |
| `test:e2e:headed` | same as `test:e2e`, with a visible browser | no |

The standard build does run `test:ui-framework`. Running `test:e2e` from one command would need a step that starts the LOCAL app (or a `webServer` block in `playwright.config.ts`) and a Chromium download or `channel: 'msedge'`. That changes how the existing e2e tests run, so I have left it.

## Part C: the entity switch

**Branch:** `feature/entity-switch`, from the feature branch. Commits `8286c0c` (product and tests) and the Phase 1 test-script change with this report. Pushed for review, not merged.

### What was built

- **The switch** (`src/studio/EntitySwitch.tsx`, new): the organization name at the top right of the newest design.
  - It is a button only if the person has two or more organizations (checked once per mount). Otherwise it is plain text, as before.
  - Opening it reads the list fresh from `/me/tenants` and marks the current organization (`aria-current`, "Current").
  - Choosing another one:
    - asks for confirmation first if there is pending work ("Stay" / "Switch anyway");
    - then lands on the new organization's workspace home (`/`), with that organization's roles and capabilities from a fresh `/me`.
  - If the server refuses, the person stays, with the message "Could not switch to X. You are still in Y." (`role="alert"`).
  - **Keyboard:** Enter or Space opens; Tab moves through the options; Escape closes and returns focus to the control.
  - **Accessible name:** "Switch organization. Current organization: X". The panel is a labelled `dialog`, and the confirmation is an `alertdialog`.
- **One implementation, reused** (`src/App.tsx`):
  - The older sidebar's logic became `listSwitchableTenants()` and `enterTenant()`.
  - The sign-in chooser, the older sidebar (unchanged behaviour: back to the chooser) and the new switch all use them. There is no second copy.
  - `enterTenant` first asks the server with an explicit `X-Selected-Tenant-Id` (new `apiClient.getCurrentUserForTenant`, `src/api/client.ts`), so a refusal changes nothing. Then it changes the selection and revalidates `/me`.
- **Pending work** (`src/api/pendingWork.ts`, new):
  - **Writes in flight:** the API client counts every non-GET request and every upload. This is a wrapper around the unchanged `request` code, now named `send`; `uploadDocument` counts too.
  - **Unsaved work:** screens register through `useUnsavedWork` (`src/studio/unsavedWork.ts`) while they hold unsent input or an open editor. Wired into:
    - Intake (typed case fields) and UploadCapture (files not yet sent);
    - Review (an adjusted redaction box);
    - admin `SettingsCard` (unsaved or saving);
    - IdentityArea (edits or busy), IntegrationsArea and RoutingRules (editor open), FieldsArea (editor open).
- **Not changed:**
  - the API client's race guard (the three checks in `send`);
  - the server's revalidation;
  - `StrictMode`;
  - any backend code.

### How the previous organization's data is cleared, and why that is reliable

1. **In-flight requests.** Changing the selection raises the tenant-context revision. The race guard then throws away the response to any request started for the previous organization, before sending, after the response and after reading it.
2. **Who the person is.** The tenant-change event clears the current user, roles and capabilities held in memory.
3. **Everything on screen and in memory.** `CaseFileApp` is now keyed by organization (`App.tsx`), so a switch unmounts the whole workspace and React discards every piece of its state: packages, documents, findings, policy, counts, toasts and open drawers.
4. **Cached responses.** The newest design fetches through SWR. **SWR's default cache is global and its keys carry no organization** (`'packages'`, `'review-queue'`, …). It survives an unmount, so a newly mounted workspace would first show the old organization's cached data.
   - I proved this with a probe: without a fix, entity A's case list appeared under entity B's name before the refetch.
   - `CaseFileApp` now gives SWR a private cache per mount (`SWRConfig` with its own `Map`), so each organization starts with an empty cache.
   - A test now samples the screen every 20 ms during the switch and fails if the previous organization's data ever appears. It fails without the private cache and passes with it.
5. **Nothing else holds organization data.** I checked the whole newest design and the API layer:
   - the module-level variables are the theme, sign-in requests in progress, roles and capabilities, and the scanner bridge's own token;
   - browser storage holds only the theme and the selection itself.

**Why this is reliable:** it does not depend on remembering to empty each cache. Unmounting removes all of the workspace's state at once, and the private cache goes with it. Anything that arrives late is discarded by the race guard, and the server checks the selection on every request regardless.

### Tests

`npm run test:oidc-signin`: **9 passed.**

| Test | Result |
|---|---|
| Two entities: switch A → B shows only B's case and B's navigation (Reports, no Intake); back to A likewise; no stale frame at any moment | PASS |
| A case-list request held open across the switch, then answered with a marked "late" case: never shown | PASS |
| One entity: no switch control; the name is plain text | PASS |
| Server refuses B: alert shown; still on A with A's data; selection still A | PASS |
| Unsent upload on Intake: confirmation; "Stay" keeps the file and A; "Switch anyway" moves to B and the file is gone | PASS |
| Keyboard: Enter opens, Escape closes and returns focus, Enter on an option switches | PASS |
| The three sign-in tests from task 0008 | PASS |

Also passing on this branch:
- `test:ui-framework` 17/17, type check clean;
- `build`.

`test:auth` shows only the old failure; it is fixed on `fix/auth-test-roles`, which is not merged here.

### Phase 1 result

- **Browser script** (`Invoke-Phase1BrowserSignin.mjs`, dev server on 15173), now extended. carol switches A → B → A without signing out, and each step checks that every case number shown belongs to the organization shown (A's end in 1, B's in 2).

| User | Result |
|---|---|
| carol.ab | chooser, then A; switch to B ok; switch back to A ok |
| alice.a | workspace A; no switch control |
| bob.b | workspace B; no switch control |
| dave.none | refused |

  4/4 pass (`browser-dev-20261118-010942.json`).
- **Scripted checks:** 60/60 pass (`checks-20261117-201205.json`).

### Observations, not changed

- **The sign-in chooser makes one extra `/me` call**, because it now uses the shared `enterTenant`, which asks first.
- **Three API calls bypass the race guard:** the two page-preview images and the document upload.
  - After a switch, a late preview is not shown, because its screen has gone.
  - An upload that was in flight is counted as pending work, so the person is asked first.
  - If they switch anyway, the upload still completes for the original organization on the server, which is correct.
- **The older design's sidebar switch was not browser-tested separately.** It now uses the same code path as the chooser, which the tests cover.

## Environment left running

| Component | Address |
|---|---|
| UI with the switch | `http://localhost:15173/` (dev server, from the working tree on `feature/entity-switch`) |
| Backend | localhost:18080 |
| Keycloak | 127.0.0.1:18181 |
| PostgreSQL | 127.0.0.1:15432 |

The production-build preview on 15174 was stopped, because it held a build from before the switch.

## Checklist for the owner

1. Close all private windows. In a new one, open `http://localhost:15173/` and sign in as **`carol.ab`**. The password is under `carol.ab` in `C:\dev\lexavault\phase1-run\secrets\users.json`.
2. Choose **Phase1 Entity A**. At the top right, under the name, "Phase1 Entity A" now has a small arrow. Click it.
   - Expected: a list with Entity A marked **Current**, and Entity B.
3. Choose **Phase1 Entity B**.
   - Expected: the desk home of Entity B, with only cases ending in **2**. The navigation shows what carol may do in B.
4. Switch back to **Entity A** the same way.
   - Expected: only cases ending in **1**.
5. **Optional:** go to **Intake**, add any PDF but do not send it, then switch.
   - Expected: a confirmation with **Stay** and **Switch anyway**.
6. **Optional:** sign in as **`alice.a`** in a new private window.
   - Expected: the name at the top right is plain text, with no arrow and no switch.

## For the owner to decide

1. Merge `feature/entity-switch` (pushed for review) into the feature branch?
2. Merge `fix/auth-test-roles` (local only) into the feature branch, and push it?
3. Make `test:e2e` runnable from one command? It needs a LOCAL app start and a Chromium download, or the Edge channel.
4. `docs/product/ENHANCEMENT_BACKLOG.md` appeared, untracked, during this task. I did not touch it or commit it.
