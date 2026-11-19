# Report U014: match the canvas, the foundation (fonts, sizes, spacing, colours)

Date: 2026-11-18
Branch: feature/ui-polish
Outcome: DONE, with 1 question for the owner (the font) and 1 configuration gap (the row's second line).

## What I measured

All 22 files in `designs/` use one shared stylesheet. The only difference between them is `.hl .it { gap: 16px }` in 13 files. So the canvas has one set of values. They are listed here with what the app had before.

| Piece | Canvas | App before |
|---|---|---|
| Font | `'IBM Plex Sans', Arial, sans-serif`; mono `'IBM Plex Mono', monospace`. No font is loaded, and Plex isn't installed here, so the canvas shows **Arial** | Plex, then `ui-sans-serif, system-ui`, which shows Segoe UI |
| Line height | normal (about 1.15) | 1.5 everywhere |
| Top bar | 56 px, solid white, gap 18; brand block 120 px wide, 16 px/600; mark 16 px wide with 3 px bars, third bar full width | see-through with blur, gap 24, brand 15 px, mark 18 px with 4 px bars, third bar 82% |
| Nav links | 15 px, ink colour, padding 7×12, gap 6; current link raised with a hairline ring; separator 1×20 px | 14 px/500, muted, 32 px high; current link white with an inset ring; **no separator style at all** |
| Nav counts | 18 px, 11 px/600, normal font, grey fill `#eceef0` | monospace with an inset ring |
| Find box | 300×36, 14 px, one "Ctrl K" key box | 34 px high, min(320, 32vw), two separate key boxes |
| Account button | white, 1 px line border, radius 8, padding 5/10/5/6; name 14/600, entity 12 | see-through, no border, name 13/500 |
| Account menu | 320 px wide, radius 10, padding 8; labels muted with 0.06em spacing; rows padded 8×10; chosen entity row raised with its tick on the right; theme as one segmented strip | 300 px, radius 6, padding 6, faint labels, tick on the left, theme as separate items |
| Page title | 30/600, no letter spacing; lead 16 px muted, 4 px below | 28 px, −0.03em; lead 15 px, 6 px below |
| Highlight | padding 0 5 px | 0 4 px |
| Buttons | gap 6; small button padding 0 12 | gap 8; small button padding 0 10 |
| Stage tiles | 6 **equal** tiles, gap 8, padding 12/14/10, 4 px coloured bottom edge; label 13 px with an **8 px coloured dot**; number **24/500 in the normal font**; 24 px below | widths followed the counts, gap 4, label 12 px with no dot, number 20 px monospace |
| Chips | white strip, radius 6; chip 14 px, ink, padding 6×12; chosen chip raised with a ring; counts in plain text | raised strip, radius 8, muted text, monospace counts, chosen chip white with a shadow |
| Filter box | 36 px, padding 0 12, 14 px, no key hint | padding 0 10, with a "/" key hint |
| Section heading | 14 px: bold title, plain count, muted 13 px note; 18 px above, 8 px below | 13 px; count 12 px monospace; note 12 px faint |
| List | radius 6 | radius 8 |
| Case row | columns 4 · 220 · 90 · 80 · rest · 150 · 80 · 170, min height 64, text 14 px; ID 15/500 mono; source, docs and time 14 px muted, left-aligned | flexible columns with 16 px gaps, so source, docs and next step drifted far from the ID; 13 px text; time right-aligned |
| Stage chip | 13/500, padding 3×9, radius 12, dot 8 px, ink text | 12 px, padding 2×8, dot 7 px; Stopped chip text red |
| Fields | 14 px, label 13/500, hint 12 px muted | input 15 px, hint faint |
| Side panel (`.panel`) | radius 6, padding 20, no shadow; title 24/600; eyebrows 11 px with 0.08em spacing; key-value rows 14 px, padding 8 | radius 10 with a shadow, title 22/500, headings 12 px, rows 13 px |
| Technical details | 13 px, codes mono 12 muted | values in ink, normal font |
| Cards | radius 8, padding 18, gap 14; title 16/600 | radius 10, padding 20, gap 16; title 15 |
| Tabs | 15 px ink; current tab 500 with a highlighter underline | 14/500 muted; current tab 600 |
| Colours, light | same as `tokens.css` `--cf-*` (`bg`, `surface`, `raised`, `line`, `line2`, `ink`, `muted`, `mark`, `marksoft`, `danger`, `ok`, stages); new: `#eceef0` fill | everything matched except the fill |
| Colours, dark (`.dk`) | same as the app's dark tokens, except: stage colours and `--ok` keep their light values, and nav counts and chips stay light | spec values (see below) |

## What changed

- **`src/tokens.css`:**
  - New token `--cf-fill`: `#eceef0` in light, `#23262b` in dark. The canvas defines no dark value, so I chose one that reads on the dark surface.
  - The font stacks are now exactly the canvas's: `'IBM Plex Sans', Arial, sans-serif` and `'IBM Plex Mono', monospace`.
- **`src/studio/studio.css`:** every row in the table above, for the top bar, account menu, page title, buttons, tiles, chips, filter box, section headings, list, rows, stage chips, fields, side panel, Technical details and `.cf-panel`.
  - The account menu is matched with CSS only; its markup is unchanged.
  - At 1200 px or less the row columns are 200 · 80 · rest · 140 · 76 with a 150 px action column, and the docs column is hidden as before. Only documented breakpoints are used.
- **`src/studio/admin/admin.css`:** the shared tabs and cards (they affect every Admin page).
- **`Desk.tsx`:**
  - The tiles no longer size by count, and each label has its dot.
  - The "/" key hint left the filter box. The `/` shortcut still works.
  - The row's Approve button is solid ink, as in the canvas. It is the same button with the same confirmation.
- **`CaseFileApp.tsx`:** the find box shows one "Ctrl K" key box (⌘ K on a Mac).
- Words, roles and behaviour are unchanged. None of the approved changes listed in the task was touched.

## Cases: the known gaps

| Gap | Result |
|---|---|
| Six equal tiles | Fixed |
| Coloured dot by each tile label | Fixed |
| Tile numbers in the normal font, large and bold | Fixed: 24/500, as in the canvas |
| Source, docs and next step close to the ID | Fixed: the canvas's fixed columns are used |
| Second line "<name> · <state>" | **Configuration, not code** (below) |

**The second line.** It shows every *displayable* case field other than the main one, in display order (`caseTitle()` in `data.ts`). The local demo setup (`LocalDemoBootstrap`) creates Contract Number (main), Member Number and Service Entity, a 3-digit number. That is why rows read "00371260080 · 000". To get "Daniel Kim · TX":
- add Borrower name and State fields marked displayable, in Admin › Business;
- set Member Number and Service Entity to not displayable.

No code change is needed. The proof pairs use those fields.

## Proof pairs (not committed)

`docs/work/ui/compare/U014/`, canvas left, app right:
- `Cases-light.png` and `Cases-dark.png`;
- `Main-light.png` and `Main-dark.png`: the top bar with the account menu open;
- the same four at 1024 px in `1024/`.

They use the mocked harness with canvas-like sample data: 4 cases need you (2 Review, 1 Approval, 1 Stopped), 2 in progress, 2 delivered and 1 rejected.

What still differs in the pairs, and why:
- **Words that come from the app, not the look:**
  - the stopped row shows "Processing stopped" and Retry;
  - the in-progress note reads "LexaVault or a colleague has these";
  - the delivered row reads "Redacted and sent".
  
  These are the app's existing words for this sample case and these permissions. Words don't change in these tasks.
- **Things that are only the test setup:**
  - the avatar shows "D", because the mocked user name has no first and last name;
  - Reports and Support aren't in the nav, because the mocked permissions don't include them;
  - the menu has no "Northwind… · Entity" label and no local test-role picker, because the harness has no customer name and isn't in local mode.
- **Dark mode:** the stage colours and chip backgrounds follow the locked `SPEC.md` "Stage colours" table. The canvas reuses its light stage colours in dark mode, and its nav counts stay light. I kept the spec.
- **1024 px:** the canvas has no 1024 px layout of its own; it wraps the nav and the find box. The app uses its documented rules: the find box becomes an icon and the docs column hides. Nothing scrolls sideways.
- Small differences in line height remain because Arial and the app's text rendering differ slightly. They are within about 2 px.

## Commands run and results

- `npm run build` (frontend): passed.
- `npm run test:standalone`: passed:
  - `test:auth` 8;
  - `test:vocabulary` 75;
  - `test:ui-framework` 17;
  - `test:oidc-signin` 66.
- Proof pairs: a temporary Playwright script, run with `npx playwright test --config playwright.oidc-signin.config.ts zz-u014-proof` at 1440 and 1024 px: 4 of 4 each time. It isn't committed. It is kept at `C:\Users\mohan\AppData\Local\Temp\claude\b95672b1ca4b9404\2f5fcd3b-0233-4310-932c-a9a3469413a6\scratchpad\zz-u014-proof.spec.ts` for U015 to U017.
- Sign-in and entity switching were not touched, so those suites ran once, inside `test:oidc-signin`.
- No backend change, so no backend build.
- Rules:
  - no raw hex outside `tokens.css`;
  - no new breakpoint;
  - no new dependency;
  - `uiFrameworkContract.test.ts`, `src/index.css` and the legacy pages are unchanged.

## Tests: counts per module, and any failures with their output

- No test was added or changed. The existing locators still match.
- No failures.

## Files changed

- `frontend/securedoc-ui/src/tokens.css`
- `frontend/securedoc-ui/src/studio/studio.css`
- `frontend/securedoc-ui/src/studio/admin/admin.css`
- `frontend/securedoc-ui/src/studio/Desk.tsx`
- `frontend/securedoc-ui/src/studio/CaseFileApp.tsx`
- `docs/work/ui/U014-report.md`

## Commits (hash and message), or "not committed"

- "feat(ui): canvas foundation: type, spacing, top bar, stage tiles and case rows (UI task U014)". The hash is in the chat summary.

## Not done, and why

- **The IBM Plex font itself:** see question 1.
- **The second line of case rows:** this needs configuration (above), not code.

## Questions for the owner

1. **Font.** The canvas names IBM Plex Sans and Plex Mono but doesn't load them, so on this machine it shows Arial. The app now uses the same stack, so both look the same here. Do you want IBM Plex bundled with the app? That would show the intended font everywhere. It adds a dependency (`@fontsource/ibm-plex-sans` and `@fontsource/ibm-plex-mono`) or self-hosted font files. If yes, the canvas and the app would both need it, so the pairs stay comparable.

## Suggested next step

U015 (the case screens).
