# Task U017: Match the canvas: Platform, Audit and Support, then a final sweep

Written: 2026-11-18, by the design chat. Spec: `docs/work/ui/SPEC.md` (locked). Read `docs/work/ui/README.md` first. The owner compared the app with the canvas: structure and words match, the look drifts on every page. These tasks make every page match the canvas.

## Do

Run after U016. Screens and their designs:
- Platform operations: `AdminPlatform.html`;
- Processing storage: `PlatformStorage.html`;
- Branding: `PlatformBranding.html`;
- Licensing: `PlatformLicensing.html`. **Look only.** The Licensing content stays as it is until Phase 5; do not build the design's licensing features;
- Audit trail: `AuditTrail.html`;
- Support: `Support.html`;
- the problem detail page: `ProblemDetail.html`.

For each one: proof pair, fix the look, report row.

**Final sweep:** check every screen from U014–U016 once more for anything this task's changes moved, with dark mode at 1024 px. `WhoSeesWhat.html` is a reference for roles, not a screen: check that each role sees what it lists, and report any mismatch as a question; do not change roles.

## The rule for U014–U017 (owner, 2026-11-18)

- **The canvas is the reference for look and layout, on every page.** The canvas files are `docs/work/ui/designs/*.html`, the same as the canvas. This replaces the earlier README line "not code to copy": match their fonts, sizes, weights, spacing, widths, alignment, radii, borders, shadows, dots, chips, buttons and colours exactly. Values still go through `tokens.css` and the shared Case File components, never as one-off styles.
- **Keep these approved changes. Do not "fix" them back to the canvas:**
  - "Having problems" instead of "Slow" (U008, U010);
  - Licensing stays as it is until Phase 5 (U010);
  - Sign-in is a read-only card (U009);
  - no borrower or file names on Support and the problem page (U012);
  - found-text masking in Review, and the "Redact all" confirmation (U004);
  - the New case rule line "PDF only · up to 50 MB each" (U005);
  - no entity filter or "By entity" table on Reports yet (U006).
- **Words, roles and behaviour do not change** in these tasks. Only the look changes.
- **Missing backend data** (names, stop reasons, destinations): leave the gap and list it.
- **Ask, don't assume:** if the canvas and an earlier owner decision seem to disagree, or a canvas detail can't be built with the frozen areas untouched, stop and put it under "Questions for the owner" as a short numbered question.
- **Proof:** for each screen, save a side-by-side pair (canvas left, app right) at 1440 px, light and dark, as PNG under `docs/work/ui/compare/UNNN/<Screen>-<theme>.png`. **Do not commit them.** The design chat reads them from disk. Use the mocked harness with sample data like the canvas's (needs-you rows, review, approval, stopped, delivered).
- **Report:** a table with one row per screen, giving what differed, what was fixed, and what is left and why.

## Checks (all tasks)

- `npm run build` and `npm run test:standalone` pass; give counts. Existing tests may change only for look (class names, locators), and each change is said in the report.
- Light and dark, 1440 px and 1024 px.
- No raw hex outside `tokens.css`; only documented breakpoints; no new dependency without asking.
- `uiFrameworkContract.test.ts`, `src/index.css` and the legacy pages unchanged.
- Commit on `feature/ui-polish`. Do not push or merge.
