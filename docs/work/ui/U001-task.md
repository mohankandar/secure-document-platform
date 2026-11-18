# Task U001: create the UI branch, then the shared foundation

Written: 2026-11-17, by the design chat. Spec: `docs/work/ui/SPEC.md` (locked). Read `docs/work/ui/README.md` first.

## Part A: branch

Owner's decision (2026-11-17): UI work runs in this same folder, one task at a time, after backend task 0014 has its report. No second working folder.

1. Check that `docs/work/0014-report.md` exists. If it does not, stop and say so.
2. Make sure the backend work is committed on `feature/lexavault-r1b-tenant-identity` (do not commit someone else's unfinished work; if there are uncommitted backend changes, stop and ask).
3. Create branch `feature/ui-polish` from `feature/lexavault-r1b-tenant-identity` and switch to it.
4. Commit `docs/work/ui/` (README, SPEC, task files, `designs/`) as the first commit on `feature/ui-polish`.
5. `npm ci` in `frontend/securedoc-ui` if needed, and confirm the app starts.

## Part B: shared foundation used by every later task

1. **Vocabulary in one place.** Add one module (for example `src/studio/words.ts`) holding the names in the SPEC vocabulary table: stage names, nav names, sensitive-type names (`SSN` → "Social Security Number" and so on, one list used by Review, Policy, case panel and Continue manually), and the plain reason texts. Replace `humanize(entityType)` in Review and the copy in `PolicyArea.SENSITIVE_TYPES` with it. Rename the nav (Cases, New case), the Desk page title (Cases), the group "In motion" → "In progress", the stage "Intake" → "New", the Desk filter and command-palette placeholders (use the configured primary and searchable field labels, never "contract"), and Review's "unplaced" → "place not found". Routes stay as they are.
2. **Stage colours.** Add the six stage colours and soft backgrounds from the SPEC to `tokens.css` (light and dark), and use them for the stage tiles, stage dots and the case-panel stage bar. Review and Approval must no longer share yellow.
3. **Errors never blank the app (findings 8, 22).** Add an error boundary around each routed screen and each Admin/Reports card, plus a small "couldn't load" card: title, one plain sentence, Try again, and a collapsed Technical details with the reference (correlation id) and request. Make the data hooks treat a response of the wrong shape as an error, not as data. Today `/admin/policy` and `/reports` with an unexpected answer blank the whole app including the top bar. Design: `designs/ErrorState.html`.
4. **Technical details component.** One collapsed disclosure ("▸ Technical details") used wherever codes and IDs move out of the main view in later tasks.
5. **No-access page (finding 21).** Replace `OutOfScope` copy: "You don't have access to anything in LexaVault yet", the signed-in email, "Ask your administrator to add you to a LexaVault group, then sign in again", with Sign out and Try again buttons. Design: `designs/NoAccess.html`.

## Tests to add

- A test that every sensitive type the policy can enable has a name in the vocabulary module.
- A test that a screen whose data has the wrong shape shows the error card and keeps the top bar (a component test, or a Playwright test with a mocked response if that fits the existing setup better; say which).

## Checks (all tasks)

- `npm run build` and `npm run test:standalone` pass; give counts.
- Every changed screen checked in light and dark, at 1440 px and 1024 px, with the local app (`.\Start-LexaVault-Local.ps1`). Say which local test roles you checked.
- No raw hex outside `tokens.css`; only documented breakpoints; no new dependency without asking.
- Commit on `feature/ui-polish`. Do not push or merge.
