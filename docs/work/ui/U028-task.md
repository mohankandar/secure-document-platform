# Task U028: Last accessibility follow-ups, and the sign-in continuity script

Written: 2026-11-20, by the design chat. Read `docs/work/ui/README.md` first. These are the owner's answers in `docs/work/ui/U027-report.md` (answers 1–4). A small task.

- **Report:** `docs/work/ui/U028-report.md`.
- **Owner approvals already given:** the merge in step 0; the text change in the frozen sign-in screens in A3 (words only). No new dependency, no backend change.

## Step 0

1. On `feature/ui-a11y-followups`, commit the design chat's change to `docs/work/ui/U027-report.md` (the owner's answers). Leave `docs/work/0021-task.md` alone.
2. Merge `feature/ui-a11y-followups` into `feature/lexavault-r1b-tenant-identity` (no fast-forward; standard message). Build and test: 620 backend, 482 frontend. Do not push.
3. Create the new branch `feature/ui-a11y-final` from it.

## Steps

**A1. Light control edge.** Change `--cf-control-line` light to `#828993`. Give the ratios against the control's white inside, the page grey and the Compliance period strip fill; all must be at least 3:1. Dark stays `#707680`.

**A2. "/" on Cases.** Scope it like J and K, in `src/studio/shortcuts.ts`: it works only while focus is in the case list, and moves focus to the filter. Add tests that it works in the list and does nothing outside it or in a field.

**A3. Chooser words** (text only, in `App.tsx`):
- the heading "Select organization" becomes **"Choose an entity"**;
- "Select an organization…" becomes **"Choose an entity to continue"**;
- the "Switching organization" screen says "Switching entity…" if it shows a visible line.
- Change no logic, routes or stored values. Update any test that matched the old words.

**A4. Update `Test-LexaVault-OidcSessionContinuity.ps1` for the Case File screens.**
- **What to wait for:** where it waits for the older UI's "Dashboard" heading, wait for the Case File main heading (`#cf-main h1`) instead. Where it clicks "Dashboard refresh", use an action that calls the API in the Case File screens (for example the Cases refresh, or a reload of Cases), so the session-refresh checks still mean the same thing.
- **Credentials:**
  - keep `-Username` and `-Password` (and `LV_OIDC_TEST_USERNAME` / `LV_OIDC_TEST_PASSWORD`);
  - **remove the built-in default password**, so the script stops with a clear message when none is given;
  - never print the password, and never write it to a file or log.
- **Login form:** the script clicks LexaVault's "Sign in" button first, then fills Keycloak's form.
- **Readme:** add a short note to the script's header on how the owner runs it.
- Run everything up to the credentials yourself:
  - start Keycloak and the app as in U027;
  - run the script **without** a password and show that it stops cleanly;
  - stop the app and Keycloak afterwards, as in U027.
- **Do not search for, guess or read any password.**
- At the end of the report, give the owner the exact commands to run it once themselves:
  - start;
  - run, with the password typed by them at the prompt, or set in the environment by them in their own terminal;
  - stop.

**A5. Conformance table.** In this report, update the rows for 1.4.11 (now 3:1 on both sides) and 2.1.4 (with "/" now scoped, no open item). The complete table, all rows, is in this report, so the accessibility statement can be written from this report alone.

## Checks

- `npm run build` and `npm run test:standalone` pass, including `test:a11y` with 0 violations; give counts.
- No raw hex outside `tokens.css`, no new breakpoint, no new dependency.
- **Proof** in `docs/work/ui/compare/U028/` (not committed): the Cases filter and the Compliance period strip with the new edge, light; the chooser with its new words.
- Commit on `feature/ui-a11y-final`. Do not push or merge.

## Report must include

Outcome; the merge commit; the A1 ratios; the A4 changes and the run without a password; the commands for the owner; the complete conformance table; test counts; the proof list; the commit hash; questions for the owner.
