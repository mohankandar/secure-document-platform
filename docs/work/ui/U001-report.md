# Report U001: create the UI branch, then the shared foundation

Date: 2026-11-17
Branch: `feature/ui-polish` (from `feature/lexavault-r1b-tenant-identity` at `82f4ef5`)
Outcome: DONE

## What was done

**Part A: branch**
- `docs/work/0014-report.md` exists. `feature/lexavault-r1b-tenant-identity` had no uncommitted backend changes.
- **The old worktree:** `feature/ui-polish` already existed, at `82f4ef5` with no commits of its own. It was checked out in a second working folder, `C:\dev\lexavault\secure-document-platform-ui`, left from the earlier "second folder" plan. That folder held only an older, superseded untracked copy of `docs/work/ui`. With the owner's yes, I removed that worktree and used the branch here.
- **First commit:** `docs/work/ui/` (README, SPEC, the task files and `designs/`).
- **Note:** task 0014's work is on `feature/phase2-role-mapping` and is not merged, so this branch does not contain it. That is as the task says, and it does not affect the UI work.

**Part B: shared foundation**
1. **Vocabulary module** `src/studio/words.ts`:
   - It holds the nav names (Cases, New case), the stage names (New … Stopped), the Cases groups (In progress), one list of sensitive types (name and description; `SSN`, `Ssn`, `US_SSN` and so on all give "Social Security Number"), the plain reason texts, "place not found", and the search placeholders built from the configured case fields, exactly as configured.
   - It replaces `humanize(entityType)` in Review and the type list in `PolicyArea`. The palette's "Go to the desk", Review's "Back to the desk", the Branding preview and the Case fields texts now say Cases.
   - The field definitions need DOCUMENT_INTAKE. For people without it, the placeholder uses the labels the cases themselves carry. Routes are unchanged.
2. **Stage colours:** the six colours and their soft backgrounds are in `tokens.css`, light and dark. They are used by the stage chips and dots, the stage tiles on Cases and the case-panel stage bar, so Review and Approval no longer share yellow. `--cf-mark` stays reserved for "needs you". The FAILED chip says "Stopped".
3. **Errors never blank the app** (new `src/studio/failure.tsx`):
   - **Error boundaries** around the routed screens (inside the top bar), each Admin area, each Policy card and each Reports card.
   - **The "couldn't load" card** (design `ErrorState.html`): a title, one plain sentence, Try again, and collapsed Technical details with the reference, the request and the cause.
   - **References:** the server's correlation id when it sent one. For an answer of the wrong shape, a `ui-xxxxxxxx` reference that is also written to the browser console.
   - **Shape checks:** the data hooks now treat an answer of the wrong shape as an error: cases, one case, the review queue, the field definitions, the business-admin configuration and Reports. Cases, Admin and Reports show the card instead of a blank screen or an empty list.
4. **Technical details:** one collapsed disclosure component, `TechnicalDetails`.
5. **No-access page** (design `NoAccess.html`): "You don't have access to anything in LexaVault yet", the signed-in email, the plain instruction, and Sign out and Try again buttons. This replaces `OutOfScope`.

## Commands run and results

- **Frontend:**
  - `npm run build` in `frontend/securedoc-ui`: passed.
  - `npm run test:standalone`: passed (`test:auth` 8, `test:vocabulary` 4 (new), `test:ui-framework` 17, `test:oidc-signin` 14, which is 9 before plus 5 new).
- **Backend:** `cd backend; mvn clean install` on this branch, so the local app ran this branch's jar: passed, 430 tests.
- **Screen checks:**
  - **Local app:** `.\Start-LexaVault-Local.ps1 -ScannerMode SIMULATOR -AiModel qwen3.5:9b`, after a plain `docker start securedoc-postgres`. The container was stopped; nothing was recreated.
  - **Method:** screenshots in Edge (Playwright, headless), light and dark, at 1440 px and 1024 px.
  - **Roles checked:** Document Supervisor (Cases, Review), Business Administrator (Reports, Admin › Redaction & retention), Document Processor (Cases).
  - **Error card and no-access page:** taken with the mocked test harness, in both themes and both widths.
  - **Result:** no page errors, the top bar present on every screen, and everything readable in dark.
- **Rules:** no raw hex outside `tokens.css` (the `test:ui-framework` check passes), no new breakpoints, no new dependency.

## Tests: counts per module, and any failures with their output

- **New:**
  - `src/studio/words.test.ts` (node test, 4): every type in the backend's `TenantRedactionPolicy.V1_CONFIGURABLE_TYPES` has a name and a description; one name per type whatever the spelling; stage and nav names; placeholders never say "contract".
  - `e2e-oidc/errorStates.spec.ts` (Playwright with a mocked API, 5). I used Playwright because the project has no component-test setup, and this runs inside `test:oidc-signin`, so in `test:standalone`. It checks:
    - Reports with a wrong-shape answer shows the card while the top bar keeps working, the details are collapsed with the reference and request, Try again recovers, and Cases still works;
    - a card that fails while drawing shows its own card while the other report cards still show;
    - Admin policy with a wrong-shape answer keeps the top bar and the tabs;
    - Cases with a wrong-shape answer shows the card, not an empty list;
    - the no-access page.
- **Existing test changes (said here as `CLAUDE.md` requires):**
  - **`e2e-oidc/entitySwitch.spec.ts`, renamed link:** the nav link "Intake" is now "New case", as U001 requires. Four locators changed from `'Intake'` to `'New case'`; the behaviour checked is unchanged.
  - **`e2e-oidc/entitySwitch.spec.ts`, flaky check (owner approved the fix in the session):**
    - "Switching from A to B shows only B data" was already flaky on the unchanged code: it failed 5 times in 10, and 9 in 10 with U001.
    - **Cause:** it checked for the previous entity's data from the moment of the click. The app keeps the previous screen on purpose until the server accepts the new entity, with the switch panel still open. Every failing sample showed that panel with A as current; no data leaked.
    - **Fix:** samples taken while the switch panel is open are not judged. The check reads the panel before the screen, so a pre-switch reading is never counted.
    - **After the fix:** 60 of 60 passed (the file, 10 times).
    - **Proof it still guards:** with the per-organization data cache removed on purpose (the bug this test guards), it failed 5 of 5. The cache was put back.

## Files changed

- **New:** `src/studio/words.ts`, `src/studio/words.test.ts`, `src/studio/failure.tsx`, `e2e-oidc/errorStates.spec.ts`.
- **Changed:**
  - `src/tokens.css`, `src/studio/studio.css`;
  - `src/studio/` `CaseFileApp.tsx`, `Desk.tsx`, `CommandPalette.tsx`, `Review.tsx`, `atoms.tsx`, `data.ts`;
  - `src/studio/admin/` `Admin.tsx`, `PolicyArea.tsx`, `shared.tsx`, `BrandingSection.tsx`, `FieldsArea.tsx`;
  - `src/studio/reports/Reports.tsx`;
  - `e2e-oidc/entitySwitch.spec.ts`;
  - `package.json` (script `test:vocabulary`, added to `test:standalone`; no dependency).
- **Not changed:** `src/security/uiFrameworkContract.test.ts`, the legacy `PageScaffold` pages, and `App.tsx` (OIDC sign-in, a frozen area).

## Commits (hash and message), or "not committed"

- `c5a3625` docs(ui): UI polish spec, designs and tasks U001 to U012
- The commit that adds this work and this report.

Not pushed, not merged.

## Not done, and why

- **The App-level refusal screen** for someone with no entity at all (dave.none in Phase 1) is in `App.tsx`, inside the frozen OIDC sign-in area, and the Phase 1 browser script matches its wording ("does not have access"). U001 names `OutOfScope`, so only that screen changed. See question 1.
- **Wording that later tasks own** is left for them: the Reports title "How the desk is performing" (U006), "Reprocess retained original" and the case-panel texts (U003), Integrations names (U008).

## Questions for the owner

1. **Should the App-level "signed in, but no access" screen get the same no-access design?** It is in the frozen OIDC sign-in area, and the Phase 1 browser script's REFUSED check would need its new wording. **Recommendation:** yes, as a small follow-up with the browser script updated in the same change.
2. **`Stop-LexaVault-Local.ps1` does not run on this branch.** Windows PowerShell 5.1 rejects line 13 (`"$Name: no PID file."` needs `"${Name}: …"`), and there is no PowerShell 7 here.
   - I stopped the three app windows the start script opened, by their PID files.
   - `securedoc-postgres` is still running. It was stopped before this task; I started it with `docker start`.
   - **Recommendation:** fix the one line, in its own commit.

## Suggested next step

U002: the account menu in the top bar. It also fixes "Sign out" wrapping at 1024 and 1440 px, which the screenshots show today.
