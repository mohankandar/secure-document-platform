# Report U021: tablet-friendly for looking, deciding and approving (iPad)

Date: 2026-11-19
Branch: feature/ui-ipad
Outcome: DONE (one desktop difference to approve, see Questions)

## What was done

| # | Item | What was done |
|---|---|---|
| 1 | WebKit tests | New `playwright.tablet.config.ts`: Playwright's WebKit with the `iPad (gen 7)` device settings (touch, iPad user agent, coarse pointer), one project per size: 768×1024, 820×1180, 1024×768, 1180×820, 1366×1024. WebKit 2215 was already installed, so nothing was downloaded. |
| 2 | Touch targets | Under `@media (pointer: coarse)` only: buttons, chips, the account menu, the find button, nav links, zoom, the Cases filter, selects, the case panel sections and the confirm buttons are at least 44 × 44 px. This applies to the top bar, Cases, the case panel, Review, New case and Reports. "choose files" and the small crosses (filter pill, filter clear) keep their look and get a 44 px hit area. Admin, Support, Platform and Audit are unchanged. |
| 3 | No hover or keys alone | The Reports trend tooltip also opens on tap. Every key already had a visible button (R/V: Redact / Keep visible; Shift R: Redact all; J/K: tap a queue item; Esc: Clear mark / Close). In Review, a finger now scrolls the page; it draws a box only when the place was not found (`touch-action`, plus `pointercancel` handling). |
| 4 | Layout 768–834 | Cases: the next step moves under the identifier at ≤768, and at ≤1024 when the case panel is open, so identifier, next step, stage and action stay visible. With the panel open at ≤1024 the stage tiles go to two rows of three, so names are no longer cut ("Ne", "Rec"). At ≤768 the list now uses the full width (it used about 75% before). Review: the queue was already a swipe strip below 1200. In portrait up to 1024 the decision goes under the page, the viewer is 40vh, and Redact / Keep visible come before the Case / Document lines, so they are on the first screen at 820×1180. |
| 5 | Safari | Checked in WebKit by tests: dropdowns (New case allow-list select), the "choose files" file picker (opens, multiple, file listed), IBM Plex loaded, the account menu (opens and closes by tap, items 44 px). |
| — | Continue manually | Note at the top on touch devices only: "Marking values by hand works best on a computer." (`MANUAL_WORDS.touchNote`). Nothing else changed. |
| — | Review bug found | The viewer scrolled about 330 px too far, so on a tablet the finding sat above the visible area. The cause: the scroll used `offsetTop` (measured from the document) instead of the page's position inside the viewer. Fixed in `Review.tsx`. |

## Commands run and results

- `npm run build`: passed.
- `npm run test:standalone`: passed, 0 failures. Counts:
  - `test:auth` 10;
  - `test:vocabulary` 90 (+1);
  - `test:ui-framework` 17;
  - `test:oidc-signin` 69;
  - `test:tablet` 45 (new: 9 tests × 5 sizes).
- **How the tablet test runs:** run `npm run test:tablet`, which is now part of `test:standalone`. It starts its own Vite server on port 15176 and mocks the identity provider and the API, so no app or backend is needed. The first run on a machine needs `npx playwright install webkit`.
- **Probe** (temporary script, all 16 screens × 5 sizes, WebKit): nothing scrolls sideways in any of the 80 combinations. The coarse pointer is detected and Plex is loaded. On the tablet-friendly screens nothing tappable is under 44 px. The only items listed are the hidden file input and the filter field, whose 44 px label is the tap area.
- **Desktop check** (Edge, 1440 and 1920, light and dark, reduced motion so nothing animates; before = U020 code, after = this branch; pixel diff):
  - Cases: identical pixels at both widths. One dark run at 1440 had a 134-pixel speck; the other three runs were identical.
  - Review: everything outside the page viewer is identical. Inside the viewer, the page's scroll position differs because of the bug fix above. See Questions.
- **Proof:** 106 WebKit screenshots in `compare/U021/` (not committed):
  - Cases, CasePanel, Review, NewCase, Reports (Business Administrator), ReportsViewer and ContinueManually, at all 5 sizes, light and dark. Review shots show the first screen; the others are full page.
  - Admin, Fields, Integrations, Sign-in, Platform, Storage, Audit trail, Support and Problem detail at 768×1024 and 1024×768, light and dark.
  - Desktop before/after pairs: `compare/U021/desktop-before-*` and `desktop-after-*`.
  - The proof script is kept at `C:\Users\mohan\AppData\Local\Temp\claude\b95672b1ca4b9404\1e042dbc-a665-4a77-86c4-c7585db45580\scratchpad\zz-u021.spec.ts`.
- **Rules:**
  - no raw hex added;
  - no new width breakpoint (only 1024 and 768, plus the media features `pointer: coarse` and `orientation: portrait`);
  - no new dependency, only a `test:tablet` script;
  - `uiFrameworkContract.test.ts`, `src/index.css` and the legacy pages are unchanged.

## Tests: counts per module, and any failures with their output

- Counts as above. No failures in the final run.
- **Added:**
  - `e2e-tablet/tablet.spec.ts`, which covers:
    - touch device facts;
    - Cases rows and 44 px targets;
    - Cases → Approve by tap (row button and dialog);
    - Case panel → Approve delivery → Confirm by tap;
    - Review → Redact by tap, then Redact all by tap and confirm, with the layout rule (decision under the page in portrait ≤1024, beside it otherwise);
    - queue item, Keep visible and zoom by tap;
    - the Continue manually note;
    - the account menu;
    - New case select and file picker.
  - `words.test.ts`: the touch note wording.
- **During the work:** WebKit refuses mocked 302 redirects, so the tablet test's fake sign-in page redirects with a script. Three test mistakes of mine were fixed: the panel asks with "Confirm" in place, not in a dialog; a route was shadowed by the catch-all mock; and the filter is tapped by its label. None of these was an app fault.

## Files changed

- `frontend/securedoc-ui/`:
  - `src/studio/studio.css`: tablet section;
  - `src/studio/Review.tsx`: scroll fix, finger scroll versus draw;
  - `src/studio/reports/Reports.tsx`: tap opens the trend tooltip;
  - `src/studio/support/ManualCase.tsx`: touch note;
  - `src/studio/words.ts` and `src/studio/words.test.ts`;
  - `playwright.tablet.config.ts` (new);
  - `e2e-tablet/tablet.spec.ts` (new);
  - `package.json`: scripts only.
- `CLAUDE.md`: test counts.
- This report.

## Commits (hash and message), or "not committed"

"feat(ui): U021 tablet-friendly Cases, case panel, Approve and Review in WebKit (iPad): 44 px touch targets on coarse pointers, next step kept in narrow rows, Review decision under the page in portrait, Continue manually touch note, WebKit tablet tests". The hash is in the chat summary. Not pushed, not merged.

## Not done, and why

- The Review queue stays a horizontal swipe strip, not a picker. The task says it "can" collapse; the strip already works by touch, has 44 px items and never squeezes the page.
- Keyboard hint chips (R, V, J K) still show on tablets. The task keeps the shortcuts, and an iPad with a keyboard uses them.
- No real iPad was used, only Playwright WebKit with iPad settings.

## Questions for the owner

1. **Desktop Review viewer:** the task asked for desktop to look exactly as after U020. The viewer bug fix changes one thing on desktop: where the page sits inside the Review viewer when it opens. Before, it scrolled too far, so the finding sat near the top edge, or above it at shorter heights. Now it is scrolled as the code intended, with the finding nearer the middle (at 1920 the page now opens at its top, with the finding in view). Everything else on Cases and Review is pixel-identical. Keep the fix (recommended), or keep the U020 position on desktop?
2. **Portrait rule:** Review uses `(max-width: 1024px) and (orientation: portrait)`, so 1024×768 landscape keeps the page and decision side by side, while 768 and 820 portrait put the decision under the page. Orientation is a media feature, not a new width. Is that acceptable, or should every width ≤1024 be one column?

## Suggested next step

The design chat reviews `compare/U021/` and answers the two questions. Then the owner checks on a real iPad.
