# Report 0006: release library 0.2.1, then Phase 1 sitting 2 (point 3 and the screen checks)

Date: 2026-11-17
Branch: `test/phase1-shared-issuer` (pushed)
Outcome: PARTLY DONE, as the task intends. Parts A and B are done and point 3 passes. Part C is ready and waiting for the owner's screen checks, with one finding: **the newest screen design has no entity switch.**

## Part A: housekeeping

- **Library:** I added the README 0.2.1 title and entry (`85135a7`), then fast-forwarded `main` from `bc63ff0` to `85135a7`. The fix commit `b126091` is included.
- **Tag:** `v0.2.1` (annotated) on `85135a7`.
- **Pushed:** `main` and `v0.2.1` to `github.com/mohankandar/enterprise-identity-parent`. The remote confirms `refs/heads/main = 85135a7` and `refs/tags/v0.2.1`.
- **LexaVault:** `test/phase1-shared-issuer` was pushed to origin, with tracking set. It is not merged. The commit for this report is pushed too.

## Part B: point 3

The checks were added to `Invoke-Phase1Checks.ps1`, so the whole set reruns together.

- **Rules:** entity A has `[SSN]`; entity B has `[SSN, CREDIT_CARD_NUMBER, DATE_OF_BIRTH]`. Both use `REVIEW_ALL` and threshold 0.8, set through the API by each entity's own admin.
- **Documents:** the same three PDFs went to both entities in one MANUAL_UPLOAD package each: `04_approval_flow.pdf`, `05_reprocess_cycle.pdf` and `07_export_delivery.pdf`.
- **Reading the results:** findings were read with `GET /api/v1/review-items?packageId=`.

Run: 60 passed, 0 failed, 0 notes. Results: `C:\dev\lexavault\phase1-run\results\checks-20261117-171556.json`.

| Id | Check (new, point 3) | Expected | Actual | Result |
|---|---|---|---|---|
| P3-01 | alice sets entity A policy (SSN only, REVIEW_ALL) | 200 | 200 | PASS |
| P3-02 | bob sets entity B policy (SSN, card, date of birth, REVIEW_ALL) | 200 | 200 | PASS |
| P3-03 | entity A policy reads back as set | SSN REVIEW_ALL | SSN REVIEW_ALL | PASS |
| P3-04 | entity B policy reads back as set | CREDIT_CARD_NUMBER,DATE_OF_BIRTH,SSN REVIEW_ALL | same | PASS |
| P3-05 | bob (B admin): read entity A's policy | 403 | 403 | PASS |
| P3-06 | alice (A admin): read entity B's policy | 403 | 403 | PASS |
| P3-07 | bob (B admin): change entity A's policy | 403 | 403 | PASS |
| P3-08 | same three PDFs processed in each entity | both PENDING_REVIEW | A=PENDING_REVIEW, B=PENDING_REVIEW | PASS |
| **P3-09** | **entity A findings: SSN only** | SSN | **SSN (SSN=1)** | **PASS** |
| **P3-10** | **entity B findings: SSN and card number, nothing outside its policy** | SSN and CREDIT_CARD_NUMBER, only B types | **CREDIT_CARD_NUMBER=1, DATE_OF_BIRTH=1, SSN=1** | **PASS** |
| P3-11 | entity B also finds the date of birth | present | present | PASS |
| P3-12 | carol in A: B's review items not visible | 403/404, or 200 with 0 items | 404 | PASS |

Unchanged since report 0005: the 48 earlier checks (points 1 and 2, refusals, R-08, R-15, toggles) all pass again.

The rule-based detector did find the date of birth, so no note was needed. With the same documents, A got 1 finding and B got 3, and each set matches its own entity's rules.

## Part C: the screen checks

**Does the newest design have the chooser and the switch?** I read `frontend/securedoc-ui/src/App.tsx` and `src/studio/`.

- **Entity chooser: yes.** The "Select organization" screen is rendered in `App.tsx` (lines 387-419) before either design is chosen, so it appears with the newest design too.
- **Entity switch: no. This is a finding.** "Switch organization" exists only in the older design's sidebar (`AuthenticatedApplication`, `App.tsx` lines 207-216). The newest design, `CaseFileApp`, is not given the switch function (`App.tsx` line 459 passes no `onSwitchTenant`). Nothing under `src/studio/` touches the entity selection. Its header shows the entity name and a Sign out button only (`CaseFileApp.tsx` lines 140-147). With the newest design, a person in two entities can change entity only by signing out and in again. I did not work around this.

**The UI is running** on `http://localhost:15173/` with the newest design on (`VITE_CASE_FILE_UI_ENABLED=true`), OIDC against the throwaway Keycloak, and `/api` proxied to the Phase 1 backend.
- It was started by the new `Start-Phase1Ui.ps1`, using `vite.phase1.config.mjs`. The config resolved from `test-tools/`, so the fallback into `frontend/` was not needed.
- It is bound to 127.0.0.1.
- Checked:
  - the served code has `VITE_CASE_FILE_UI_ENABLED = "true"` and `VITE_SECURITY_MODE = "OIDC"`;
  - `/api/v1/me` through the proxy gives 401 without a token;
  - the Keycloak token endpoint allows origin `http://localhost:15173`.
- `frontend/securedoc-ui/.env.local` is untouched; process environment variables override it.

### Owner's checklist

- **Address:** open `http://localhost:15173/` in a **private browser window**, and use a new private window for each user.
- **Passwords:** each test user's password is in `C:\dev\lexavault\phase1-run\secrets\users.json`, under the user's name. Use the value as the password on the Keycloak sign-in page.
- **Telling the packages apart:** entity A's packages have contract numbers ending in **1**, and entity B's end in **2**.

1. **Chooser.** Click **Sign in** and sign in as **`carol.ab`**.
   - Expected: a "Select organization" screen listing **Phase1 Entity A** and **Phase1 Entity B**.
2. **Only the chosen entity.** Click **Phase1 Entity A**.
   - Expected: the workspace opens with "Phase1 Entity A" shown next to the user name at the top right.
   - The desk lists only packages whose contract numbers end in 1.
3. **Switch.** Look for a way to change entity without signing out.
   - Expected, from the code: **there is none** in this design. Please confirm what you see.
   - Then click **Sign out**, sign in as `carol.ab` again and choose **Phase1 Entity B**.
   - Expected: only packages ending in 2, and no entity A data on screen.
4. **Single-entity user.** In a new private window, sign in as **`alice.a`**.
   - Expected: **no** choice screen. Entity A opens directly.

Please report what you saw at each step, especially step 3.

## Failures

None in the checks. The one finding is the missing entity switch in the newest design (above). It is product code, so I have not changed it.

Two mistakes in the new test launcher were fixed during setup (test files only):
- Vite bound `localhost` to IPv6 only, so the readiness check on 127.0.0.1 failed. Vite is now bound to 127.0.0.1, and readiness is checked over HTTP.
- A wait step read a stale log; I checked the live state instead.

## Files changed

- `test-tools/phase1-shared-issuer/Invoke-Phase1Checks.ps1`: point 3 checks P3-01 to P3-12, and a NOTE result type
- `test-tools/phase1-shared-issuer/Start-Phase1Ui.ps1`: new; starts the UI on 15173 with the newest design on
- `test-tools/phase1-shared-issuer/vite.phase1.config.mjs`: new; port 15173 (strict), 127.0.0.1, proxy to 18080
- `docs/work/0006-task.md` (unchanged) and this report
- Library: `README.md` (title and 0.2.1 entry)

## Environment left running

| Component | Address |
|---|---|
| Keycloak | 127.0.0.1:18181 |
| PostgreSQL | 127.0.0.1:15432 |
| Backend | localhost:18080 |
| UI | 127.0.0.1:15173, open as `http://localhost:15173/` |

`Remove-Phase1.ps1 -KeepContainers` stops the UI and the backend. `Remove-Phase1.ps1` removes everything Phase 1 created.

## Questions for the owner

1. **Steps 1 to 4 above:** what did you see?
2. **The missing switch:** should the newest design get an entity switch before it ships? That is a product change in the UI framework area, so it needs your decision and a separate task.
