# Report 0007: Phase 1 sign-in shows "Sign-in could not be completed"

Date: 2026-11-17
Branch: `test/phase1-shared-issuer`
Outcome: DONE. Cause found and reproduced. Nothing had stopped and nothing was restarted. **The cause is in frontend product code.** I have not changed it.

There is no task file for this; the owner asked directly in the session.

## What was checked

| Component | State at 18:49 |
|---|---|
| PostgreSQL `lexavault-phase1-postgres` | Up 3 hours (healthy), 127.0.0.1:15432 |
| Keycloak `lexavault-phase1-keycloak` | Up 3 hours, 127.0.0.1:18181, discovery 200 |
| Backend (PID 7444) | running, health UP, port 18080 |
| UI (PID 42256) | running, 127.0.0.1:15173, HTTP 200 |
| Phase 1 data | both entities ACTIVE, both login-system records ACTIVE, 6 grants |

- **Clocks:** the containers and Windows agree within 2 seconds. A new token has `iat` and `exp` correct relative to the host.
- **API:** with a token from the check client, `GET /api/v1/me/tenants` and `/api/v1/me` work both directly and through the UI proxy. carol gets [A, B]; alice gets [A] and opens A.
- **Logs:** neither the backend nor Keycloak logged an error.
- **The sleep is a coincidence.** Windows came back from sleep at 18:44:40. The backend's only warning since then is HikariPool's "clock leap detected" at 18:44:37. The owner's two sign-ins reached Keycloak at 18:46 and 18:47, and both got tokens.

## Reproduction

I drove the real browser sign-in (Authorization Code with PKCE, through `http://localhost:15173/`) in headless Microsoft Edge with Playwright, which the frontend already has installed.
- The script lives in my scratchpad, not in the repository.
- It printed only HTTP statuses, screen text and the app's own console messages. No token or password was printed or saved.

| User | Keycloak token exchange | API calls | Screen |
|---|---|---|---|
| `carol.ab` | 200 | `/me/tenants` 200 | "Select organization" with both entities. Choosing Entity A opens the workspace on Entity A. **Works.** |
| `alice.a` | 200 | `/me/tenants` 200, `/me` 200 | **"Sign-in could not be completed. Please try again."** Console: `Tenant context changed while the request was in progress` (twice) |
| `bob.b` | 200 | `/me/tenants` 200, `/me` 200 | **Same failure** as alice |
| `dave.none` | 200 | `/me/tenants` 200 `[]` | Same message, but this one is an expected refusal: no entity. Console: `No authorized ACTIVE organization is available for this account.` |

**Every user who belongs to exactly one entity fails to sign in, every time.** The server answers correctly; the browser discards the answers.

## Cause

The cause is in frontend product code. It involves three pieces together:

1. **The sign-in check runs twice at once.**
   - The session check in `src/App.tsx` (the `useEffect` with `load()`, lines 299-358) runs twice in parallel.
   - The app is wrapped in `React.StrictMode` (`src/main.tsx:9`), and in development React mounts every effect twice on purpose.
   - The browser log shows "Starting production-session verification" twice, and two `/me/tenants` calls.
2. **The first run changes the entity selection while the second run's request is in flight.**
   - With one entity, the first run calls `setSelectedOidcTenant(choice)` (`App.tsx:323`). That raises the tenant-context revision.
   - The API client's race guard (`src/api/client.ts:77-79`, from R1B step B14) then throws away the second run's in-flight response with `Tenant context changed while the request was in progress`.
3. **The discarded run wipes the session.**
   - Its `catch` block calls `clearOidcSession()` (`App.tsx:348`) without checking whether that run is still the active one. Only the message is guarded by `active`.
   - Clearing the session also clears the entity selection, which raises the revision again. Now the first run's `/me` response is discarded too.
   - Both runs fail, and the user sees the generic message.

Two entities (carol) work, because discovery only shows the choice screen and does not set a selection while both runs are in flight.

### Why carol seemed to fail too

After a failed attempt, the browser's Keycloak session for that user stays alive. I tested this: after alice failed, pressing **Sign in** again went straight back in as alice. No login form appeared, and it failed the same way. So in a window where alice or bob had just failed, an attempt "as carol" was really alice or bob again.

That fits the owner's sequence: carol worked about an hour ago, then step 4 (alice) failed, and later attempts in the same browser failed whoever was intended.

## What this means

- **Dev server only, as far as I can tell.** This shows on the Vite dev server, which is what Phase 1 runs. `StrictMode` runs effects twice only in development, so a production build probably signs in single-entity users normally. I have not tested a production build.
- **The race is still real product code.** The unguarded `clearOidcSession()` in a stale run, combined with the revision guard, would fail the same way whenever this effect overlaps itself. It also makes the normal development setup unusable for any single-entity user in shared-issuer mode.
- **It affects both screen designs.** This check runs in `App.tsx` before either design is chosen.
- **The generic message hides the cause.** The real reason appears only in the browser console.

## What was done

- Checked every component and the Phase 1 data. Read the backend, Keycloak, Vite and Windows power logs.
- Reproduced the sign-in for four users in headless Edge, plus the reuse of a failed user's Keycloak session.
- **Not restarted:** nothing had stopped.
- **Not changed:** product code, Phase 1 test files and the environment. Everything is still running.

## For the owner, until this is fixed

- Close **all** private windows before trying again, so the Keycloak session from a failed attempt is dropped. Then use one new private window per user.
- `carol.ab` signs in and can choose an entity: checklist steps 1 to 3 can be done.
- Step 4 (`alice.a`, single entity) will fail on the dev server. That failure is this defect.

## Questions for the owner

1. **The fix.** Should the sign-in race be fixed as its own task? The likely fix:
   - in `App.tsx`, a stale run (`active === false`) must not call `clearOidcSession()` or change the entity selection;
   - plus a frontend test that mounts the app under `StrictMode` with a single-entity user.
2. **A production-build check.** Should Phase 1 add a UI mode that serves a production build of the frontend (test files only)? That would show whether the failure is development-only.
3. **The browser reproduction script.** Should it be added to `test-tools/phase1-shared-issuer/`, so the screen checks can also be rerun by script? It prints statuses only.
