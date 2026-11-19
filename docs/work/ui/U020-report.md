# Report U020: last polish from the design chat's review of the U019 pairs

Date: 2026-11-19
Branch: feature/ui-polish
Outcome: DONE

## What was done

| # | Fix | What was done |
|---|---|---|
| U019 Q1 | Cases filter | The box is 360 px (`.cf-search-cases`; Audit and Support keep 320 px). It tries, in order: the full wording, the main field alone, "Filter cases" (`caseSearchPlaceholders`, `firstThatFits`). If it can't measure, it shows "Filter cases" (fail closed). The owner's answer is recorded in `U019-report.md`. The loan setup now shows the full wording at every width. |
| 1 | Prose line length | On Admin and Platform, paragraphs, hints, field hints, card subtitles and the sensitive-type descriptions stop at 75ch. Tables, rows, lists, mode cards and controls keep their widths. |
| 2 | Key–value rows | On full-width Admin cards, a `.cf-facts` list has a 200 px label column, with the value left-aligned after it. Below 640 px (an existing breakpoint), the value goes under the label. The case panel, the Review decision panel and the scanner details keep label left and value right. Sign-in is the only read-only key–value list on a full-width card (the other Admin lists are label-above-value tiles). |
| 3 | Account menu | The username line shows only when it differs from the shown name (ignoring case and spaces; `secondNameLine`). "Dana Reyes" now shows once. An email such as `jordan.park@…` still shows. |
| 4 | Review "Fit" | Review already opened at "Fit", but Fit was capped at 620 px (76% at 1920). Fit now fills the viewer width, up to 150% of the page's actual size (`fitWidth`, `FIT_MAX_PERCENT`). At 1920 it opens at 150%. Zoom buttons are unchanged. Before the page image loads, the old 620 px width applies. |
| 5 | Connection summary | The address (or folder) has its own full-width row in step 3. It wraps only after a "/" or "\" (`slashParts`, with `<wbr>`), never inside "://". It falls back to breaking a piece only if that piece alone is wider than the row. At 1920 it shows on one line. |

## Commands run and results

- `npm run build`: passed.
- `npm run test:standalone`: passed, 0 failures.
  - `test:auth` 10
  - `test:vocabulary` 89 (was 85; +4)
  - `test:ui-framework` 17
  - `test:oidc-signin` 69
- **Proof pairs:** 16 images at 1920 px, light and dark, in `compare/U020/`. Not committed.
  - Screens: Cases, Main (account menu open), Review, AdminRetention, AdminSignIn, PlatformStorage, PlatformBranding, ConnectionEditor (all three steps).
  - Script: the U019 one with its output folder changed to U020. It was copied into `e2e-oidc/` only while it ran, then removed. Kept at `C:\Users\mohan\AppData\Local\Temp\claude\b95672b1ca4b9404\580a3ed5-48ec-4d52-a2a0-ab8d463dbb3a\scratchpad\zz-u020-proof.spec.ts`.
- **Width check** (`WIDTHS=1024,1440,1920`, 12 screens): nothing scrolls sideways at any width. The Cases placeholder is "Filter by loan number or borrower name" at all three widths.
- **Rules:**
  - no raw hex added;
  - no new breakpoint (640 px is already used in `admin.css`);
  - `package.json` unchanged;
  - `uiFrameworkContract.test.ts`, `src/index.css` and the legacy pages are unchanged.

## Tests: counts per module, and any failures with their output

- Counts as above. No failures in the final run.
- **Added:**
  - `words.test.ts`: the placeholder order. The U019 fit test was replaced by a `firstThatFits` test with the same cases, plus the middle step and unknown widths.
  - `userName.test.ts`: `secondNameLine`.
  - `reviewRules.test.ts`: `fitWidth`.
  - `integrationRules.test.ts`: `slashParts`.
- **During the work:** the first test run failed with syntax errors in three test lines and in `Desk.tsx`. My shell edits had stripped escape characters. I fixed those lines; this was not an app or test-logic fault.

## Files changed

- `frontend/securedoc-ui/src/studio/`:
  - `Desk.tsx`, `words.ts`, `words.test.ts`, `studio.css`: U019 Q1;
  - `admin/admin.css`: fixes 1, 2 and 5;
  - `AccountMenu.tsx`, `userName.ts`, `userName.test.ts`: fix 3;
  - `Review.tsx`, `reviewRules.ts`, `reviewRules.test.ts`: fix 4;
  - `admin/IntegrationsArea.tsx`, `admin/integrationRules.ts`, `admin/integrationRules.test.ts`: fix 5.
- `CLAUDE.md`: the `test:vocabulary` count.
- `docs/work/ui/U019-report.md`: the owner's answer.
- This report.

## Commits (hash and message), or "not committed"

- `0761ab8` docs(ui): U020 task from the design chat
- "fix(ui): U020 last polish: 360 px Cases filter with stepped wording, prose at 75ch, side-by-side facts on wide cards, single name line, Review fit to width, address row in the connection summary". The hash is in the chat summary.

Not pushed, not merged.

## Not done, and why

Nothing from the task is left. The items under "Not to change" were left as they are.

## Questions for the owner

None.

## Suggested next step

The design chat reviews `compare/U020/`. Then the owner's click-through and the merge decision for `feature/ui-polish`.
