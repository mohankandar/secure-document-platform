# Task U019: final polish from the design chat's live review

Written: 2026-11-19, by the design chat. It reviewed the running app at 1440 px as Document Processor, Business Administrator and IT Administrator, compared it with the canvas, and the owner approved every fix. Read `docs/work/ui/README.md` first. The rule from U014–U017 still applies (the canvas for look; approved changes kept; words, roles and behaviour unchanged except where this task says so).

## Also include (owner approved, left over from the U018 follow-up)

- Delete the now-unused `.cf-strip*` rules in `studio.css`.
- Update the frontend test counts in `CLAUDE.md`.

## Fixes

1. **Page width:** `.cf-main` has `max-width: 1440px`; the canvas `.main` has none, with only `padding: 32px 24px`.
   - Remove the limit on list, dashboard and Admin pages: Cases, Review, Reports, Support, the Audit trail and every Admin and Platform tab.
   - **Keep the canvas's centred columns** on New case (1100 px), the problem page (1100 px) and No access (520 px).
   - Check at 1440, 1920 and 2560 px.
2. **New case drop zone:** "They're processed in the order listed below." sits on the same line as "Drop PDFs here Choose files" and looks cramped. Match `designs/NewCase.html`: one centred line "Drop PDFs here or choose files", with the rule line ("PDF only · up to 50 MB each") centred under it. Put the order note under the file list, or leave it out if the list already shows the order.
3. **Cases filter placeholder is cut off** ("Filter by contract number or member nur"). Make it fit at every width from 1024 px: shorter wording built from the configured field names with the lower-case rule ("Filter by contract or member number"), falling back to "Filter cases" when it can't fit.
4. **Admin form controls on full-width cards** *(owner approved, slightly beyond the canvas):* inputs, dropdowns and the confidence slider must not stretch with the card. Give them a sensible maximum width (about 480 px; short number fields stay short). Mode cards, tables and long text may still use the full width. Check Redaction & retention, Case fields, the connection editor, Platform and Branding at 1920 px.
5. **Review, empty queue:** hide the "0 decided this session" meter and its bar when there is nothing to review.
6. **Case panel:** the "Keeping and deleting · Redacted copy expires in 18 hours" highlight wraps between "18" and "hours". Keep a short phrase like "in 18 hours" together (no breaking inside it), and let the summary wrap only between phrases.
7. **Integrations row actions:** the Local Scanner Bridge row shows Switch off and Edit, while the Watched folder row shows only Check now.
   - Make them consistent where the backend allows it.
   - If the watched folder is set by configuration and can't be edited or switched off here, say so in a short muted line ("Set up by the install configuration").
   - Don't add backend changes. If the answer needs one, stop and ask.

## Proof: every screen at 1920 px

Retake **every** proof pair from U014–U017 at **1920 px**, light and dark, into `docs/work/ui/compare/U019/` (not committed). Use the same persona rules: each role's exact capabilities from `ProductAuthorizationPolicy`. The design chat reviews every pair itself.

## Report

Add a table with each fix, what was done, and anything left. Include the usual checks.

## Checks

- `npm run build` and `npm run test:standalone` pass; give counts.
- Light and dark; 1024, 1440, 1920 px; nothing scrolls sideways.
- No raw hex outside `tokens.css`; only documented breakpoints; no new dependency.
- `uiFrameworkContract.test.ts`, `src/index.css` and the legacy pages unchanged.
- Commit on `feature/ui-polish`. Do not push or merge.
