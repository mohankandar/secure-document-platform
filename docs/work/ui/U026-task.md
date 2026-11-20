# Task U026: Accessibility (WCAG 2.1 AA) and "?" help on Admin settings

Written: 2026-11-20, by the design chat. Read `docs/work/ui/README.md` and `docs/work/ui/SPEC.md` first. Build plan step 11 in `docs/work/ui-enhancements-design.md` (#8 Procurement checklist, approved by the owner 2026-11-19): "A WCAG 2.1 AA check of the main screens, fixes, then a short accessibility statement. '?' help text on Admin settings screens only." The statement itself is a later docs step, not this task.

- **Report:** `docs/work/ui/U026-report.md`.
- **Owner approval already given:** the merge in step 0, and one dev-only test dependency, `@axe-core/playwright` (it is not shipped in the app). No other new dependency. No backend change except where a fix truly needs one; stop and ask first.
- U026 is LexaVault-only. Vero does its own.

## Step 0. Merge U024, and two small fixes (owner's answers to U024)

1. On `feature/ui-time-saved`, commit the design chat's change to `docs/work/ui/U024-report.md` (the owner's answers). Leave `docs/work/0021-task.md` alone.
2. Merge `feature/ui-time-saved` into `feature/lexavault-r1b-tenant-identity` (no fast-forward; standard message). Build and test: 620 backend, 352 frontend. Do not push.
3. Create the new branch `feature/ui-accessibility` from it.
4. **Portrait iPad:** the four "What LexaVault did for you" tiles sit 2 + 2, with one portrait-tablet rule like the headline's from U021. Extend the existing tablet test.
5. **Service levels:** the grey line under "No review needed" becomes "Cases delivered with no person reviewing or finishing them".

## Part A: WCAG 2.1 AA check and fixes

**A1. Screens**, as the role that uses each, in light and dark:
- sign-in and the entity chooser;
- Cases and the case panel;
- New case;
- Review (queue, page, decision panel), and Continue manually;
- Approve;
- Reports (with the "How is this worked out?" panel and the Compliance report dialog);
- Admin (every tab, including the Safe policy confirm step);
- Audit trail;
- Support;
- Platform;
- the account menu;
- the No access and problem pages.

**A2. How to check.** Do all three; automated checks alone are not enough.
1. **Automated:** run axe (WCAG 2.0/2.1 A and AA rules) on each screen and state, in a new Playwright suite `test:a11y` that is part of `test:standalone`. It must end with **0 violations**. Any rule you turn off, you list with the reason.
2. **Keyboard only:** go through each main task without a mouse:
   - open a case;
   - decide findings in Review;
   - approve;
   - create a compliance report;
   - change a policy and confirm.

   Check: the tab order is logical, focus is always visible (2 px or more, with 3:1 contrast against what's around it), nothing traps focus except open dialogs, Esc closes dialogs and popovers, and every action has a key path. Write it down step by step in the report.
3. **By hand, against the WCAG 2.1 AA checklist:**
   - text contrast 4.5:1, and 3:1 for large text and UI parts. Status colours must never be the only signal;
   - names and roles of controls; headings in order; one `main`; `lang`; page titles per screen;
   - form labels, errors announced (`aria-describedby`, `aria-invalid`), and required fields marked;
   - 200% zoom with nothing lost or overlapping;
   - reflow at 320 px width for Cases, Reports, Approve and the dialogs. Review and Continue manually are desktop and tablet screens by owner decision (U021); record them as an exception, don't redesign them;
   - text spacing (WCAG 1.4.12) does not break layouts;
   - motion: respect `prefers-reduced-motion`;
   - live regions for toasts and status messages;
   - the Review page image: the redaction marks and findings have a text equivalent in the queue. Say how a screen-reader user decides a finding.

**A3. Fix** everything you find, within the locked look:
- no visual redesign;
- colour fixes only through `tokens.css`, keeping the yellow and black look. Show before and after contrast ratios;
- if a fix would change the look noticeably, stop and ask, with a picture.

**A4. Findings list** in the report, one row each: screen; WCAG criterion (number and name); what was wrong; fix; status (fixed / exception with reason / needs owner). End with a short draft **conformance table** per WCAG 2.1 AA criterion (Supports / Partially supports / Not applicable, with a note). The later accessibility statement will be written from it.

## Part B: "?" help on Admin settings screens

**B1. Where:** next to each setting's label on the Admin settings screens only:
- Redaction & retention: Sensitive data, the presets, Fine-tune, Document intelligence, Keeping and deleting, Report targets (including minutes per page);
- Case fields;
- Integrations, routing rules and their settings;
- Sign-in (read-only card);
- Branding;
- Storage;
- Telemetry;
- Licensing.

Not on case screens, Reports or Review.

**B2. How it works:** a small "?" button (an accessible toggletip):
- `aria-label` "About <setting name>"; 44 px tap area on a coarse pointer;
- it opens a short note next to it, announced to screen readers;
- Esc, the button again, or a click outside closes it;
- it works by keyboard and touch;
- one shared component, no new dependency.

**B3. The words:**
- 1–3 plain sentences per setting, in the product's voice: what it does, its default, and what changing it affects;
- no internal names, no codes;
- where a setting matters for compliance (keeping and deleting, Strict and Balanced and Light, minutes per page), say so in one clause;
- keep all the texts in one file, so they are easy to review and later translate;
- **list every text in the report**, so the design chat and the owner can review the wording.

## Checks

- `npm run build` and `npm run test:standalone` pass, including the new `test:a11y`; give counts.
- Tests for the toggletip (keyboard, Esc, outside click, touch, screen-reader name).
- No raw hex outside `tokens.css`, no new width breakpoint (the step 0 portrait rule uses the existing tablet pattern), no new app dependency. `@axe-core/playwright` is a dev dependency only.
- **Proof** in `compare/U026/` (not committed):
  - before and after pictures of each visible fix;
  - the "?" note open on three Admin cards, light and dark, with one on an iPad;
  - the step 0 iPad 2 + 2 tiles;
  - a 200% zoom picture of Cases and Reports;
  - a 320 px picture of Cases.
- Commit on `feature/ui-accessibility`. Do not push or merge.

## Report must include

Outcome; the merge commit; step 0 results; the axe results per screen (before and after counts); the keyboard walkthrough; the findings list; the draft conformance table; every help text; test counts; the proof list; the commit hash; questions for the owner.
