# Task U027: Accessibility follow-ups

Written: 2026-11-20, by the design chat. Read `docs/work/ui/README.md` first. These are the owner's answers in `docs/work/ui/U026-report.md` (answers 1–5). A small task.

- **Report:** `docs/work/ui/U027-report.md`.
- **Owner approvals already given:** the merge in step 0; the visible border change in A1; the markup-only change in the frozen sign-in screens in A4. No new dependency, no backend change.

## Step 0

1. **Move the proof folder.** U026's proof pictures are in `compare/U026/` at the repo root (untracked). Move them to `docs/work/ui/compare/U026/`, which `.gitignore` already excludes. Remove the empty root `compare/` folder, and check that nothing in it was ever committed. Fix the U026 proof script so it writes there.
2. On `feature/ui-accessibility`, commit the design chat's change to `docs/work/ui/U026-report.md` (the owner's answers). Leave `docs/work/0021-task.md` alone.
3. Merge `feature/ui-accessibility` into `feature/lexavault-r1b-tenant-identity` (no fast-forward; standard message). Build and test: 620 backend, 462 frontend. Do not push.
4. Create the new branch `feature/ui-a11y-followups` from it.

## Steps

**A1. Field and control borders (WCAG 1.4.11).**
- Add a form-control border token in `tokens.css`: light `#8a919b`, dark `#707680`.
- Use it for inputs, selects, number and date fields, segmented controls, checkboxes and radios that draw their own edge. Cards, dividers and table lines stay as they are.
- Check each one's contrast against the background it sits on, in both themes, and give the ratios.
- Before and after pictures of New case, an Admin card, the Reports filters and the Compliance report dialog.

**A2. Single-key shortcuts (WCAG 2.1.4).**
- **Review:** R, V, J, K and Shift R work only while focus is in the queue, the page viewer or the decision panel. Review opens with focus on the current finding in the queue, so the shortcuts work straight away for keyboard users.
- **Cases:** J and K work only while focus is in the case list.
- Nowhere do they fire while focus is in a text field, select, dialog or the account menu.
- Ctrl or Cmd K ("Find a case or action") is not a single-key shortcut and stays page-wide.
- Update the visible key hints only if they say "anywhere". Update the keyboard walkthrough tests.

**A3. Marking a place by keyboard: a documented exception.**
- No code change. Add the exception to the conformance notes, for use in the accessibility statement: "Marking a region by hand (a finding whose place was not found, and Continue manually) needs a pointer. Such a case can be finished by a colleague using a mouse; everything else in Review works by keyboard."
- Add backlog item **C4** to `docs/product/ENHANCEMENT_BACKLOG.md` section C: "Draw a redaction box by keyboard (arrow keys move and size it), for WCAG 2.1.1 in Review and Continue manually."

**A4. Sign-in and entity chooser (markup only).** In the older `App.tsx` screens, wrap the content in one `main` landmark. Show the product name, "LexaVault", where "Secure Document Platform" appears, and give the page titles "Sign in · LexaVault" and "Choose an entity · LexaVault".
- Change no sign-in or session logic, no routes, no redirects, and no stored values.
- Run the OIDC session-continuity checks (`Test-LexaVault-OidcSessionContinuity.ps1`, and the `oidc-signin` suite) and give the results.
- If any change would touch logic, stop and ask.

**A5. Reports chart by keyboard.** Each day in the "Received and delivered" chart is focusable in a sensible way: one Tab stop for the chart, then the arrow keys move between days. The focused day shows the same tip as on hover, and the screen reader reads it. There is no visible change until it is focused. Esc leaves the chart's day focus.

**A6. Update the draft conformance table** in this report:
- 1.4.11 → Supports;
- 2.1.4 → Supports;
- 2.1.1 → Partially supports, with the exception note;
- 1.3.1 and 2.4.2 → Supports, if A4 is done.

## Checks

- `npm run build` and `npm run test:standalone` pass, including `test:a11y` with 0 violations; give counts. Add tests for:
  - the shortcut scopes (they fire in their zone, not outside it, and not in fields);
  - Review's opening focus;
  - the chart's arrow keys and tip;
  - the sign-in `main` and titles.
- No raw hex outside `tokens.css`, no new breakpoint, no new dependency.
- **Proof** in `docs/work/ui/compare/U027/` (not committed): A1 before and after, light and dark; A4 sign-in; A5 the chart with a day focused.
- Commit on `feature/ui-a11y-followups`. Do not push or merge.

## Report must include

Outcome; the merge commit; the A1 ratios; the A2 behaviour; the A4 check results; the updated conformance table; test counts; the proof list; the commit hash; questions for the owner.
