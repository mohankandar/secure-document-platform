# Report U015: match the canvas, the case screens

Date: 2026-11-18
Branch: feature/ui-polish
Outcome: DONE, with 5 questions for the owner and the known backend gaps listed.

## The owner's U014 answers, done first

| Answer | Done |
|---|---|
| Bundle IBM Plex | `@fontsource/ibm-plex-sans` 5.3.0 and `@fontsource/ibm-plex-mono` 5.3.0, pinned exactly. The owner installed them. New file `src/fonts.ts`, imported once in `main.tsx`, loads only Sans 400/500/600 and Mono 400/500. Vite copies the font files into `dist/assets` (56 files), and the built CSS has no outside font URL. The legacy pages name their own system fonts, so they don't change. |
| Plex in the proof pairs | `designs/*.html` are unchanged. At screenshot time the proof script adds the same bundled `@font-face` rules to each design page, from `node_modules`. Both sides now show Plex. |
| Retake the U014 pairs | Done: `compare/U014/` (Cases, Main, light and dark) and `compare/U014/1024/`. |
| Role in the mock | The sample user now has exactly the Document Supervisor capabilities from `ProductAuthorizationPolicy`, with no `TECHNICAL_RETRY`. The stopped row shows **Continue manually**, as on the canvas. The other proof screens use the role their design shows: Business Administrator (Sam Lee) for ErrorState, and a person with no role for NoAccess. |
| Field names in sentences are lower case | New `fieldNameInSentence()` in `words.ts`. It lowers plain words and keeps acronyms and mixed-case words, so "SSN", "MERS" and "eNote" stay as configured. It is used in: "Filter by loan number or borrower name"; "Loan number or borrower name, or an action…" in the find box; the case panel's "Opens New case with the loan number filled in."; the Admin › Business "Edit loan number" title; and the field editor's "Move … up/down" and "Make … the main ID" labels. |
| Dark stage colours and the stop-reason gap | Kept as they were. |

**A finding the font brought up:** the canvas never sets a line height, so with Plex it uses Plex's own, about 1.3. U014 had copied Arial's (1.15), which made every block a few pixels too tight once Plex loaded. The Case File root and the U014 type rules now use `line-height: normal`. This affects every Case File screen in the same way as the canvas.

## Screens

Measured element by element (canvas against app, at 1440 px) with a probe in the proof script, then compared by eye in the pairs.

| Screen | What differed | Fixed | Left, and why |
|---|---|---|---|
| Cases (`Cases.html`) | Line heights (see above). The lead line lost its 4 px top margin to a general `p` reset. Each row divider added 1 px. | All three. Every measured box (title, lead, tiles, chips, filter, headings, rows, stage chips, buttons) is now within 0 to 2 px. | None from the look. |
| Top bar and account menu (`Main.html`) | Nothing new beyond U014. | Nothing. | Test setup only: the avatar shows "D", and the harness has no customer name or local role picker. |
| Case panel, three states (`CaseStates.html`) | Case ID was 24/600 sans (canvas: mono 22/500, name 13 px). Gaps were 20 (canvas: 12). Passed stages used their own colours (canvas: ink). The next-step eyebrow held a stage chip (canvas: plain words in the state's colour). Delivered had a grey card (canvas: green). Actions were yellow, inline and with icons (canvas: full width, solid for the main action, outlined otherwise, no icons). Hint text was 12 px (canvas: 13). Sections were "+ DOCUMENTS 4" in capitals (canvas: "▸ Documents · 4", 13/600). | All of these. The section style applies to the case panel only. | **"What went wrong"** shows only to people with `TECHNICAL_EXCEPTION_VIEW`; the canvas shows it to Processor and Supervisor (question 4). The stop reason and the rejection reason are known backend gaps. The delivery receipt ("SDV-88213") isn't sent by the API. Documents opens by itself when a case has 3 or fewer documents; that is existing behaviour (question 5). The delivered date includes the year. These are existing words. In dark mode the canvas keeps its light card colours; the app uses the dark tokens. |
| Review (`Review.html`) | Columns were 240 · page · 300 (canvas: 330). Queue was 13 px with 2 px gaps (canvas: 14 px, 4 px). Viewer radius was 10 with a dotted background and loose zoom buttons (canvas: 6, plain, one bordered strip). Panel radius was 10. "TEXT FOUND" was in capitals, and the value was 18/600 (canvas: "Text found" 12 px, value mono 20/400 with the highlighter underline). The reason was 13 px muted (canvas: 14 px ink). Decision buttons were 14/600 with icons (canvas: 15/500, no icons). The bulk box sat under a line (canvas: dashed box, full-width button). | All of these. Every measured box is within 2 px. | Queue masking ("••••0193") is an approved change (U004). The "0 decided this session" meter and the J/K hint are app features. The canvas queue line shows "LN204881 · Maria Alvarez"; the app shows the loan number only (question 3). The bulk box shows only when the case has another finding of the same type, which the canvas sample doesn't really have. |
| New case (`NewCase.html`) | The page was 1120 wide in 0.85/1.15 columns (canvas: 1052, 380 · rest, gap 16). Steps had black number circles (canvas: "1 · Identify the case"). Labels had "Required" on the right (canvas: "Loan number · required"). Inputs were 40 px, and the main one 48 px at 18 px (canvas: 38 px, 14 px). Every field had an empty hint line. The capture method was a full-width tab bar with icons (canvas: the chip strip). The drop zone was grey with an icon (canvas: dashed, centred, "Drop PDFs here choose files" on one line). Files were a list with icons and "01" numbers (canvas: a table, "1 · name", size and pages on the right, "Remove"). The footer note was on the left under a line (canvas: right, next to the button). | All of these. The number in each heading is hidden from screen readers, so the headings' accessible names don't change. The Remove button now says "Remove" (its label was already "Remove <file>"). | The rule line "PDF only · up to 50 MB each" is the approved U005 wording. The list head "3 documents · 3 KB" is the app's wording; the canvas has "File · Size · Pages" column headings. The move-up and move-down arrows are existing behaviour. "Choose files" is the existing wording. |
| Continue manually (`ContinueManually.html`, dark only) | The breadcrumb was 13 px mono (canvas: an 11 px capitals eyebrow). The banner was 13 px. Columns were 220 · page · 300 (canvas: 250 · 330, gap 16). Thumbnails were one per row in grey frames (canvas: two across, paper, a badge in the corner). File chips were large. | All of these. | The banner words are the stop-reason gap. "Add mark" stays disabled until a box is drawn; that is behaviour. |
| No access (`NoAccess.html`), including the sign-in screen from U013 | There was no top bar, the background was grey, the text column was 440 px and centred vertically, the title was 22 px, the text was muted and the email bold (canvas: white page, top bar, a 520 px column 120 px down, title 26/600, text 15 px ink). | All of these. The top bar shows the brand. | The canvas top bar also has an account chip (name and organisation). At sign-in the app knows only the email (question 2). |
| Error state (`ErrorState.html`) | The card was 720 px wide and white, with a red left edge, muted text and a solid Try again below (canvas: full width, soft red fill and border, ink text, small outlined Try again on the right, Technical details under a hairline). | All of these. This is the shared `LoadErrorCard`, so every "couldn't be loaded" card changes the same way. | The canvas shows the reference and the request beside "Technical details". The app keeps them collapsed, and a test enforces that (question 1). The canvas title is "These settings couldn't be loaded"; the app's is "Policy couldn't be loaded" (existing words). The Admin header, tabs and hint line belong to U016. |
| Empty states | No canvas design for them. | Nothing. | None. |

**1024 px.** The canvas has no 1024 layout; it squeezes. The app follows its documented rules:
- New case now keeps two columns at 1024 (320 · rest) and stacks only at 768, because two columns fit and match the canvas. This is a change: before, it stacked at 1024.
- Review keeps its existing ≤1200 layout, with the queue as a row above the page and the decision panel. Three columns at 1024 would leave the page about 330 px wide.
- Nothing scrolls sideways.

## Questions for the owner

1. **Technical details on error cards.** The canvas shows "reference corr-5f2a · GET /business-admin/configuration · unexpected response shape" next to the collapsed "Technical details". The app keeps codes hidden until it is opened (UI spec findings 8 and 22), and `errorStates.spec.ts` checks that. Should the reference show while it is closed, as on the canvas? I tried it, the test failed, and I put it back.
2. **No access: an account chip in the top bar?** The canvas shows "Jordan Park, Northwind Title & Escrow ▾". At sign-in the app knows only the email: there is no name, no organisation and no menu. Should the bar show a plain chip with the email, or stay as it is (brand only)?
3. **Review queue: the borrower name in the case line?** The canvas reads "LN204881 · Maria Alvarez"; the app shows the loan number only. The name is the same second line the Cases row shows. It is new words on this screen, so I didn't add it.
4. **"What went wrong" in the case panel.** The canvas shows it to Document Processor and Document Supervisor. The app shows it only with `TECHNICAL_EXCEPTION_VIEW` (IT Administrator, Platform Operations), and `/technical-exceptions` is protected by that capability on the server. Showing it to the Processor and Supervisor would need a backend change. Do you want that?
5. **Documents section open by default.** The app opens it when a case has 3 or fewer documents; the canvas draws it closed. Keep the app's behaviour?

## Commands run and results

Frontend (`frontend/securedoc-ui`):
- `npm run build`: passed.
- `npm run test:standalone`: passed:
  - `test:auth` 8;
  - `test:vocabulary` 76;
  - `test:ui-framework` 17;
  - `test:oidc-signin` 66.
- The sign-in no-access screen changed, so `npx playwright test --config playwright.oidc-signin.config.ts signinRace entitySwitch` ran 3 times: 11 of 11 each time.
- Proof pairs: a temporary Playwright script, copied into `e2e-oidc/` only while it ran and then removed.
  - `npx playwright test --config playwright.oidc-signin.config.ts zz-u015-proof`: 16 of 16 at 1440, 16 of 16 at 1024 (`PROOF_WIDTH=1024`).
  - U014 retakes: 4 of 4 at each width.
  - Not committed. Kept at `C:\Users\mohan\AppData\Local\Temp\claude\b95672b1ca4b9404\5ab54eef-91cd-4e55-8d6c-e6fde5e14263\scratchpad\zz-u015-proof.spec.ts`, with its probe list `zz-probes.ts`, for U016 and U017. `PROBE=<name>` prints element boxes for canvas and app.
- During the work, one run of `test:oidc-signin` failed on `errorStates.spec.ts:106`:

  ```
  expect(locator).toBeHidden() failed
  ```

  The reference was showing beside the collapsed Technical details. I removed that change (question 1) and the suite passed: 5 of 5, then 66 of 66.
- No backend change, so no backend build.

## Tests: counts per module, and any failures with their output

- `test:vocabulary` went from 75 to 76:
  - **Changed:** `words.test.ts` "search placeholders…" now expects "Filter by loan number or borrower name" and "Loan number or borrower name, or an action…" (the owner's wording answer), and adds a case with "SSN last 4".
  - **Added:** "field names inside a sentence are lower case; acronyms and mixed-case words keep their spelling".
- No browser test was changed. All locators still match.
- No failures at the end.

## Files changed

- `frontend/securedoc-ui/package.json` and `package-lock.json`: the two font packages. The lock file also lost some `libc` lines; that is how this npm version writes the file, not a package change.
- `frontend/securedoc-ui/src/fonts.ts` (new) and `src/main.tsx` (one import line; the tokens-before-index order the contract checks is unchanged).
- `src/studio/studio.css` and `src/studio/support/support.css`
- `src/studio/CaseDrawer.tsx`, `Review.tsx`, `Intake.tsx`, `UploadCapture.tsx`, `NoAccess.tsx`, `failure.tsx`, `admin/FieldsArea.tsx`
- `src/studio/words.ts` and `words.test.ts`
- `docs/work/ui/U014-report.md` (already committed) and this report.

Rules checked:
- no raw hex outside `tokens.css`;
- only documented breakpoints (1200, 1024, 768);
- `uiFrameworkContract.test.ts`, `src/index.css` and the legacy pages are unchanged;
- no dependency other than the two approved font packages.

## Commits (hash and message), or "not committed"

- `b9afd9e` docs(ui): U014 report with the owner's answers (applied in U015)
- "feat(ui): IBM Plex bundled; case screens match the canvas (UI task U015)". The hash is in the chat summary.

## Not done, and why

- The 5 questions above.
- Backend gaps, unchanged: stop reasons, the rejection reason, and the delivery receipt.

## Owner's answers (2026-11-18)

1. **Error-card reference:** keep it hidden until Technical details is opened (spec findings 8 and 22). The canvas is wrong here; no change.
2. **No access, account chip:** no. Keep the brand-only top bar. The email is already on the page above Sign out.
3. **Review queue, borrower name:** yes. Show the same second line as the Cases row ("LN204881 · Maria Alvarez"), using the same displayable fields and the same permissions.
4. **"What went wrong" for Processor and Supervisor:** no change now. It comes through the backend item already approved after U003 (a plain stop reason, no technical detail). The full technical view stays `TECHNICAL_EXCEPTION_VIEW` only.
5. **Documents open by default for 3 or fewer:** keep the app's behaviour.

**Notes from the design chat's review of the U015 pairs. Fix them at the start of U016:**
- **Review proof:** the app's page is blank in the pair because the mock has no page image. Give the mock a sample page image with the text at the finding's place, so the pair shows the highlight sitting on the text. Retake Review light and dark.
- **New case proof:** give the sample files realistic sizes and page counts, so the pair is comparable. This changes test data only.
