# Task U003: Cases (Desk) rows and the case panel (findings 2, 3, 5, 19)

Written: 2026-11-17, by the design chat. Spec: `docs/work/ui/SPEC.md` (locked). Read `docs/work/ui/README.md` first.

Designs: `designs/Cases.html`, `designs/CaseStates.html`.

## Cases rows

1. Replace the small bars in each row with the **next step in words**: "3 findings to review", "Redaction done, ready to approve", the stop reason in plain words ("Text recognition timed out on one page"), "Finding sensitive data…", "Queued", "Sent to Secure Document Vault".
2. Add **one action button** at the row's end, only when the person can act (same rules as `caseActions` and `needsYou`):
   - Review (yellow, `MANUAL_REVIEW`) for review cases; opens Review filtered to the case;
   - Approve (`PACKAGE_APPROVE_DELIVERY`) for approval cases; uses the same confirmation as the case panel;
   - for a stopped case: Retry (`TECHNICAL_RETRY`, IT Administrator) or Continue manually (`MANUAL_REVIEW`, Processor and Supervisor).
   Clicking elsewhere on the row still opens the case panel; the button must not also open it.
3. Stage chip per row using the stage colours from U001.

## Case panel

1. Stage bar labels in full ("Stopped", never "Technical …").
2. **Stopped:** "Processing stopped", the plain reason, and "The original is kept until <date>, so the case can still be finished." Primary button by role as above.
3. **Delivered:** "Delivered to <destination>" with date and time; "Redacted copy kept here until <date>". "Reprocess retained original" becomes **"Process again"** with "<n> of <max> runs left" (from the lifecycle summary). Document Supervisor only, as today.
4. **Rejected:** say why, in plain words, and offer **"Start a new case for this loan"**, which opens New case with the primary identifier filled in. If the API does not return a rejection reason today, say so in the report and show "The source was not accepted" until it does; do not invent a reason.
5. Section names: "Keeping and deleting" (was Retention), "Case details" (was Identity and source).

## Tests

- Row action shown only with the right capability (one test per action).

## Checks (all tasks)

- `npm run build` and `npm run test:standalone` pass; give counts.
- Every changed screen checked in light and dark, at 1440 px and 1024 px, with the local app (`.\Start-LexaVault-Local.ps1`). Say which local test roles you checked.
- No raw hex outside `tokens.css`; only documented breakpoints; no new dependency without asking.
- Commit on `feature/ui-polish`. Do not push or merge.
