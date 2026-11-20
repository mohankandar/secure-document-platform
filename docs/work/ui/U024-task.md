# Task U024: What LexaVault did for you

Written: 2026-11-20, by the design chat. Read `docs/work/ui/README.md` and `docs/work/ui/SPEC.md` first. Build plan step 8 in `docs/work/ui-enhancements-design.md`.

- **Canvas** (match it exactly): https://claude.ai/artifact/1KWPuxuff66xbVM7uGgZVs, artboards "Reports with What LexaVault did for you" and "Report targets card with minutes per page". The content you need is copied below.
- **Backend:** the `timeSaved` block on `GET /api/v1/operations/reports`, and `minutesPerPage` in the business-admin configuration read and save (task 0020).
- **Report:** `docs/work/ui/U024-report.md`.

## Step 0. Merge 0020, and align "No review needed" (owner's answers to 0020)

1. On `feature/time-saved`, commit the design chat's change to `docs/work/0020-report.md` (the owner's answers). Leave `docs/work/0021-task.md` alone: another chat wrote it, it waits for the owner, and it is not part of this task.
2. Merge `feature/time-saved` into `feature/lexavault-r1b-tenant-identity` (no fast-forward; standard message). Build and test: 617 backend, 320 frontend. Do not push.
3. Create the new branch `feature/ui-time-saved` from it.
4. **"No review needed" leaves out cases finished by hand.** Reports' existing "No review needed" (the headline tile, its target bar, and Service levels) uses the same rule as `casesNoPerson`: no person reviewed it, and it was not finished by hand.
   - The compliance report's "No review needed" row follows; its "Checked by a reviewer" and "Finished by hand" rows are unchanged.
   - One shared definition in the backend, not two copies.
   - Update the tests and the scripts' expected numbers, and list every number that changes.
   - Afterwards "No review needed" must always equal `casesNoPerson`, with a test proving it.

## What they see (from the canvas)

**Reports**, under the page title row and above the existing headline tiles: a white section "What LexaVault did for you" (`h2`, 16 px semibold), with four tiles in a wrapping grid (min 220 px):

1. **Handled without a person** (first, it proves the product goal):
   - a 4 px green bottom border (existing success token);
   - the big number is `percentNoPerson`%, or "—" when null;
   - mono grey line: "<casesNoPerson> of <casesDelivered> cases".
2. **Pages checked:** the big number is `pagesChecked`. Mono grey line: "in <documentsChecked> delivered documents" (owner's answer 3).
3. **Sensitive items redacted:** the big number is `itemsRedacted`. Mono grey line: the `itemTypes` names joined with ", ", or "—" when empty. Shorten them to the canvas style: "SSN, date of birth, card, credit score". Keep one mapping from the vocabulary names to those short names, with a test.
4. **Review time saved (estimate):**
   - a 2 px dark border;
   - the big number is "≈ N h" (hours rounded down), "≈ N min" under an hour, or "0 min". Use the same words as the PDF (`TimeSavedRule`), ported once;
   - under it, the link button **"How is this worked out?"**.

**"How is this worked out?"** opens a small panel (`role="dialog"`, labelled), right-aligned under the tiles, max-width 460 px. It holds:
- the title "How "Review time saved" is worked out";
- a 3-row table, numbers in mono and right-aligned:
  - "Pages in cases that needed no person": `pagesNoPerson`;
  - "× minutes a person would take per page": `minutesPerPage`, shown as 1, 1.5, 2.5…;
  - a top-bordered result row: "= <pagesNoPerson × minutesPerPage, before rounding, with 1 decimal when needed> minutes, rounded down" → the same words as the tile;
- grey text: "Pages a person reviewed count as zero. The <m> minute(s) per page is deliberately low and is set by your Business Administrator in Admin › Redaction & retention › Report targets. This is an estimate, not a measurement." Say "deliberately low" only when the setting is the default 1; otherwise "The <m> minutes per page is set by your Business Administrator in …";
- a **Close** button.

Esc and Close close the panel, and focus returns to the link.

**Who sees it:** the same people as the existing headline (`REPORT_EXECUTIVE_VIEW`: Business Administrator and Report Viewer). It follows the page's chosen period and filters, like the other tiles, and shows the page's existing loading and empty states. With no delivered cases, the tiles show 0 / "—" and the panel still opens.

**Report targets card** (Admin › Redaction & retention), under the existing three targets, after a top border:
- the label "Time saved estimate", with a yellow "New" pill (existing mark token);
- a `<label>`: "A person takes [ 1 ] minutes to check one page by hand". The input is a number field, mono, 80 px wide, 40 px tall, `min 0.5`, `max 30`, `step 0.5`, `inputmode="decimal"`;
- grey text: "Used for "Review time saved" on Reports and in the compliance report. Default 1 minute: deliberately low, so the estimate never overclaims. Set it from your own team's experience; changing it is recorded in the audit trail.";
- **Validation:** a value outside 0.5–30, or not a step of 0.5, shows "Enter a number from 0.5 to 30, in steps of 0.5." under the field, and Save is disabled;
- it saves with the card's existing Save and Reset;
- the "New" pill is a one-release marker. Keep it in a single constant so it is easy to remove later.

**Tablet** (U021): Reports must read well on an iPad, with the tiles wrapping, and the panel works by touch. Admin must not break.

## Checks

- `npm run build` and `npm run test:standalone` pass; give counts. Add tests for:
  - each tile's words, including null percent, no types, and minutes vs hours;
  - the short type names;
  - the panel's numbers and its "deliberately low" wording;
  - focus and Esc;
  - who sees the section (Business Admin and Report Viewer yes; the others no);
  - the minutes field's validation, saving, and Reset;
  - a WebKit tablet test of Reports with the panel open by tap.
- Backend step 4 is tested; full build passes; counts given.
- No raw hex outside `tokens.css`, no new width breakpoint, no new app dependency.
- **Proof** in `compare/U024/` (not committed):
  - the Reports section with the panel open at 1440, light and dark, next to the canvas;
  - the Report targets card next to the canvas, plus its error state;
  - Reports on an iPad, portrait and landscape;
  - one real run against the backend: set 2.5 minutes, save, then Reports showing the new estimate and the audit record with `minutesPerPage=2.5; previous=[…]`. Set it back to 1 afterwards.
- Commit on `feature/ui-time-saved`. Do not push or merge.

## Report must include

Outcome; the merge commit; the step 4 result with the numbers that changed; test counts; the proof list; the commit hash; questions for the owner.
