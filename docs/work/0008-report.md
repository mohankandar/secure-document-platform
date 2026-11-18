# Report 0008: fix the single-entity sign-in race (report 0007)

Date: 2026-11-17
Branches:
- `fix/single-entity-signin-race`, from `feature/lexavault-r1b-tenant-identity` at `49a8a70`. Fix commit `e62bc26`. Not pushed, not merged.
- `test/phase1-shared-issuer`: Part A commit `4a4d662`, merge of the fix `6c73deb`, and this report.

Outcome: DONE. The defect is development-only. After the fix, all four users sign in correctly on the dev server and the production build. The 60 scripted checks pass.

## Before and after

Browser sign-in through the Phase 1 UI (real Authorization Code + PKCE, headless Edge), with `Invoke-Phase1BrowserSignin.mjs`:
- **dev:** the Vite dev server on 15173;
- **prod:** a production build (`vite build`) served on 15174.

| User | Expected | Dev, before | Prod, before | Dev, after | Prod, after |
|---|---|---|---|---|---|
| `carol.ab` (A and B) | chooser, then the workspace on A | PASS | PASS | PASS | PASS |
| `alice.a` (A) | workspace on A | **FAIL**: "Sign-in could not be completed", `Tenant context changed while the request was in progress` | PASS | PASS | PASS |
| `bob.b` (B) | workspace on B | **FAIL**: same | PASS | PASS | PASS |
| `dave.none` (none) | refusal | PASS (refused) | PASS (refused) | PASS (refused) | PASS (refused) |

**Does the defect occur in a production build? No.** Before the fix, the production build signed in alice and bob correctly. A production build runs the sign-in check once. Only the development build mounts it twice, through `React.StrictMode`, and the second run is what made the race possible.

Results files are in `C:\dev\lexavault\phase1-run\results\`:
- before: `browser-dev-20261118-000018.json`, `browser-prod-20261118-000053.json`;
- after: `browser-dev-20261118-000542.json`, `browser-prod-20261118-000617.json`.

## What changed in product code

`frontend/securedoc-ui/src/App.tsx`, inside the OIDC session-check effect. There are two guards, 4 lines of code plus comments:

- **Line 318:** `if (!active) return;` straight after `getAvailableOidcTenants()`. A run that is no longer the active one stops there, before it can change the entity selection (`setSelectedOidcTenant` at lines 323-326).
- **Line 348:** `if (!active) return;` at the top of the `catch`. A stale run can no longer call `clearOidcSession()` or set an error.

Not changed:
- the API client's race guard (`src/api/client.ts:71-79`, R1B step B14);
- `React.StrictMode` (`src/main.tsx`);
- any backend code, migration or library.

Also on the fix branch (tests only):
- `frontend/securedoc-ui/e2e-oidc/signinRace.spec.ts` and `frontend/securedoc-ui/playwright.oidc-signin.config.ts`.
  - They start the real app on a Vite dev server (port 15175, OIDC mode) under `StrictMode`.
  - The identity provider and the API are Playwright request mocks.
  - No new dependency: they use the existing `@playwright/test` and the installed Microsoft Edge.
  - The project has no component-test library (no jsdom), so a real browser was the only way to mount the app without adding a dependency.
- `package.json`: new script `test:oidc-signin`.

## Tests

| Suite | Before the fix | After the fix |
|---|---|---|
| `test:oidc-signin` (new) | 1 failed, 2 passed. Single entity failed: the page showed "Sign-in could not be completed", and the trace shows the `Tenant context changed` errors. The chooser and refusal tests passed. | **3 passed** |
| `test:ui-framework` (type check + 17 contract tests) | — | **17 passed**, type check clean |
| `build` (type check + `vite build`) | — | **passed**, 1662 modules |
| `test:auth` | **7 passed, 1 failed** | **7 passed, 1 failed** (same failure) |

**The `test:auth` failure already existed** and is unrelated:
- It fails the same way with the fix stashed.
- `demo selector exposes canonical product roles only` still expects an `'ADMIN'` option that the demo role selector no longer offers.
- The test file was last changed on 2026-09-18.
- `Build-LexaVault-All.ps1` does not run `test:auth`, which is probably why it was not noticed.
- I did not change it.

**Phase 1 scripted checks after the merge:** 60 passed, 0 failed, 0 notes (`checks-20261117-190835.json`).

## Test-file changes (Part A)

- `Invoke-Phase1BrowserSignin.mjs`: the browser script from report 0007, made reusable.
  - Four users, one fresh browser context each, so no Keycloak session is shared.
  - `--mode dev` or `--mode prod`; PASS/FAIL per user.
  - Prints and saves statuses, screen text and the app's console messages only.
- `Start-Phase1UiProd.ps1`: production build into `C:\dev\lexavault\phase1-run\ui-dist` (outside the repository), served with `vite preview` on 127.0.0.1:15174.
- `vite.phase1.config.mjs`: build and preview settings.
- `keycloak/lexavault-phase1-realm.json`: the web client also accepts `http://localhost:15174/`.
- `Phase1-Common.ps1`: `Sync-Phase1KeycloakClients`. It now also updates the redirect URIs of existing clients, and `Start-Phase1.ps1` uses it.
- `Remove-Phase1.ps1`: also stops the production-build UI.
- Small known nit: the sync rewrites the web client on every run, because its comparison does not match Keycloak's stored form exactly. It writes the same values each time, so it is harmless.

## Answers to the two questions (no code change)

**1. "Sign in as a different user" after a failure.**
- **Most of it exists already.** `beginOidcLogin(forceInteraction)` in `src/api/auth.ts:266-284` adds `prompt=login&max_age=0` when asked. That makes Keycloak, or any standards-compliant OIDC provider, show the login form even when its own session is still alive.
- **But it is only used after a 403.** That is the "Sign in with a different account" button, driven by `authDenied` in `App.tsx`. Every other failure, including "no entity" and the generic message, sets `authDenied = false`, so the next "Sign in" silently reuses the provider's session.
- **What it would take:**
  - (a) Offer "Sign in as a different user" (with `forceInteraction = true`) after every failed or refused sign-in, not only after a 403.
  - (b) Better still, end the provider session as well. The app can already do this: `endOidcSession()` sends the browser to the provider's `end_session_endpoint` with `id_token_hint`. But today the failure path calls `clearOidcSession()` first, which throws away the ID token that the logout needs. The failure path would have to keep the ID token until the logout is sent.
  - Without (b), a shared computer still holds the refused user's provider session until the browser closes. With (a) alone, the next person can at least type their own name.

**2. Which failure reasons could safely be shown.** Safe, because they describe only the person's own situation or a general fault:

| Reason (where it arises) | Safe wording |
|---|---|
| No entity for this account (`/me/tenants` empty) | "Your account is not assigned to any organization in LexaVault. Contact your administrator." |
| Session expired (401) | Already shown separately: "Your organization session has expired…" |
| No access (403) | Already shown separately |
| Identity provider unreachable or discovery failed | "The sign-in service is not available right now. Try again later." |
| Code exchange failed, or the callback state or verifier did not match | "Sign-in was interrupted or took too long. Please sign in again." Do not say which check failed. |
| Network failure reaching LexaVault | "LexaVault cannot be reached right now." |

Not safe to show:
- raw server or provider error text;
- issuer, audience or client details;
- tenant or entity IDs or names other than the person's own;
- whether a requested entity exists versus is not allowed (a forged choice must stay a plain refusal);
- stack traces.

A correlation ID next to the message would let support find the details in the logs without showing them.

## Environment left running

| Component | Address | Notes |
|---|---|---|
| Keycloak | 127.0.0.1:18181 | |
| PostgreSQL | 127.0.0.1:15432 | |
| Backend | localhost:18080 | |
| UI, dev server | `http://localhost:15173/` | with the fix, for the owner's step 4 |
| UI, production build | `http://localhost:15174/` | also running; not needed for step 4 |

`Remove-Phase1.ps1 -KeepContainers` stops all three processes.

## For the owner

1. **Step 4 can be done now.** Close all private windows. In a new one, open `http://localhost:15173/` and sign in as `alice.a`; the password is under `alice.a` in `C:\dev\lexavault\phase1-run\secrets\users.json`. Entity A should open directly, with no choice screen.
2. **Decide what to do with `fix/single-entity-signin-race`** (local only, not pushed): push it for review, or merge it into `feature/lexavault-r1b-tenant-identity`.
3. **Decide on the pre-existing `test:auth` failure:** update the test to match the current role list, or restore the `'ADMIN'` option. Is either intended?
4. **Push:** this report and the merge are committed on `test/phase1-shared-issuer` but not pushed. Earlier sittings pushed it; say if you want it pushed now.
5. Questions 1 and 2 above could each become a small UI task, if wanted.
