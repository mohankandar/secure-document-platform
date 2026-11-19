# Report U016: match the canvas, Reports and Admin

Date: 2026-11-18
Branch: feature/ui-polish
Outcome: DONE, with 4 questions for the owner and the known gaps listed.

## The owner's U015 answers and the design chat's notes, done first

| Item | Done |
|---|---|
| 1. Error-card reference stays hidden | No change, as answered. |
| 2. No access: brand-only top bar | No change, as answered. |
| 3. Review queue shows the borrower name | Done. The queue's case line is now the Cases row line: main ID, then the other displayable fields ("LN204881 · Maria Alvarez"), from the same `caseTitle()` and the same `/packages` answer, so the same permissions apply. Fields not marked displayable stay hidden. Only the queue line changed; the decision panel's "Case" row, the toast and the "Showing" line keep the main ID. New browser test in `review.spec.ts`. |
| 4. "What went wrong" for Processor and Supervisor | No change now, as answered (comes with the approved stop-reason backend item). |
| 5. Documents open by default for 3 or fewer | No change, as answered. |
| Note: Review proof page | The proof mock now draws a sample page like the canvas's (title, grey lines, "Borrower SSN: 412-55-0193", "Card: …", "Borrower: Maria Alvarez") and measures each finding's box from the drawn text. The pair shows the highlight on the number. Retaken: `compare/U015/Review-light.png` and `-dark.png`. |
| Note: New case proof files | The sample files are now real page trees with the canvas's sizes: 234 KB / 8 pages, 284 KB / 12 pages, and a 1.2 MB TIFF. The app reads them as "234 KB · 8 pages", "284 KB · 12 pages", "1.2 MB · —". Retaken: `compare/U015/NewCase-light.png` and `-dark.png`. Test data only. |

## Screens

Pairs at 1440 in `compare/U016/` (light and dark), and at 1024 in `compare/U016/1024/`. Not committed.

| Screen | What differed | Fixed | Left, and why |
|---|---|---|---|
| Reports (`Reports.html`), Business Administrator | Filters 13 px, gap 8, 20 px below (canvas 14 px, gap 10, 16 below). Headline box radius 10, padding 16 (canvas 6, 14 · 18). Labels 12 px (canvas 13). Values 24/600 (canvas 26/500). Second lines faint (canvas muted). Sections radius 10, padding 20, titles 15 px, notes 12 px (canvas 8, 18, 16, 13). Service levels: the numbers were red when missed and the met bar was black; the track was the raised grey; the target line was the yellow highlighter, 2 px longer; the line under each bar sat tight (a `p` reset won) (canvas: numbers muted, red bar missed, green bar met, `#eceef0` track, review-yellow target line, 6 px below). Tables had no box and a plain head (canvas `table.t`: bordered box, raised head 12/600, 10 · 14 cells, 14 px). | All of these. | The trend chart is an approved U006 feature; the canvas has none. Pipeline, Backlog age, Retention, Timing and Intake channels are drawn on the canvas as one locked box ("unchanged from today"); they now use the same section look. No entity filter and no "By entity" table (approved, U006). "target now set by you" on the canvas is new words; not added. The source and outcome filters stay native selects. In dark mode the canvas keeps its light track; the app uses the dark token. |
| Reports, Report Viewer | Same as above. | Same. | The viewer's line "More detail is available to Business Administrators." is plain text, as before; the canvas box around it is an annotation. No Export CSV for this role (existing behaviour). |
| Admin › Business (`AdminBusiness.html`), with the Case fields tab | Tabs were 44 px high with the yellow line inset 12 px (canvas: 6 · 12 · 8 padding, line under the whole tab). 18 px gaps (canvas 16). Section names 13 px (canvas 14/500). Card footers had a line above them (canvas: none). Hints were 12 px faint (canvas: 12 px muted under a field, 13 px muted on their own line). Mode cards 10 · 12 padding, gap 8 (canvas 12 · 14, gap 10). "Fallback if something goes wrong" value was 600 (canvas 500 ink). Technical details was a "+ TECHNICAL DETAILS" eyebrow (canvas "▸ Technical details", 13/500 ink). "Send to review" stretched the full card width. The case-fields table had no box and a plain head. | All of these. The table look is the shared `cf-ad-table`, so the Platform and Audit tables get the same box (U017 screens). | The eyebrow has no customer name ("Northwind Title & Escrow ·"): `/me` does not send it (known gap). The area hint line under the tabs ("What gets redacted…") is existing words; the canvas has an annotation there. The canvas's mono summary beside Technical details is new words; not added. The canvas sketches the Sensitive data card short ("the rest of this card is unchanged"); the app's card keeps its controls. Save is disabled until something changes (behaviour). The Case fields tab keeps its editable checkboxes and move arrows; the canvas draws ticks. |
| Redaction and retention (`AdminRetention.html`) | The card look above. | Same. | The canvas shows each duration as one box ("72 hours") and Report targets as labelled boxes; the app uses a number and unit, and sentences for the targets (existing controls and words; question 1). The supervisor approval is a switch in the app and a checkbox on the canvas (question 2). The canvas button says "Save"; the app says "Save changes" (existing words). |
| Integrations (`AdminIntegrations.html`) | Rows were 12 px apart with a line below (canvas: 14 px, a line above every row, the first too). Names 14 px (canvas 15/500). Tags were grey filled boxes, 11 px (canvas: outlined pills, 12 px, ink). Status word was 600 ink (canvas: regular; "Slow" in a dark yellow, "Switched off" red, "Working" muted). Address 12 px faint (canvas 13 px muted mono). Dot 1 px high. Each row's Technical details was an 11 px eyebrow. The failed-files title was 600 (canvas 500). | All of these. New token `--cf-warn-ink` (light `#9a7a00` as on the canvas; dark `#f4d03f`, because the canvas's dark yellow is unreadable on the dark page). | "Having problems" instead of "Slow" (approved). The app shows one group at a time (the group buttons filter); the canvas draws Receiving and Sending by barcode together. Each connection keeps its own Technical details; the canvas has one line per card. "Switch off" and "Refresh" are existing actions. Receiving shows 3 because the sample has three receiving connections. |
| Connection editor, three steps (`ConnectionEditor.html`) | Sheet 640 wide (canvas 620). Title 17 px, head padding 20 · 16 (canvas 18 px, 20 · 24). Footer raised grey with small buttons (canvas: white, 36 px buttons). Name and "How documents arrive" sat side by side (canvas: full width each). Section names 500 with no line between them (canvas 600, a line above "Where and how to connect"). Empty hint lines took 18 px. Advanced was an eyebrow. | All of these. The sheet head and footer are shared, so the other Admin sheets change the same way. | Step 1's two choice cards and step 3's summary have no canvas drawing beyond the step-1 note; they use the same card look. "Collect" (every few minutes or once a day), Description and "Switch on when saved" are existing fields. The canvas's mono summary beside Advanced is shown inside it in the app (existing). "Cancel" is an existing action. |
| Sign-in (`AdminSignIn.html`), read-only card | The card already uses the shared card look (radius 8, padding 18, 16/600 title, 13 px note). | Nothing beyond the shared changes above. | The canvas draws the editable form; the owner chose the read-only card (U009). The canvas shows values in boxes and a green "Working · tested Nov 7" pill; on a read-only card boxes would look editable, so the app keeps label and value rows (question 3). |

**1024 px.** The canvas has no 1024 layout. Report tiles and service levels stay in one row; the two Admin retention cards stack below 1200 (documented breakpoint); the editor sheet keeps 620 px. Nothing scrolls sideways.

## Questions for the owner

1. **Retention durations and report targets.** The canvas shows "72 hours" as one box, and the targets as labelled boxes ("Deliver within" · "for at least"). The app has a number plus a unit list, and the targets as one sentence each ("Deliver within [4] [hours] for at least [95] % of cases"). Matching the canvas changes the controls and the words. Keep the app's, or switch to the canvas's?
2. **Supervisor approval: switch or checkbox?** The canvas draws a checkbox; every on/off in Admin is a switch. Keep the switch?
3. **Sign-in card: value boxes and a status pill?** The canvas shows each value in a box and "Working · tested Nov 7" as a green pill. On a read-only card the boxes look editable. Keep the plain rows, or add only the status pill?
4. **Customer name in the Admin eyebrow.** The canvas reads "Northwind Title & Escrow · Residential lending". `/me` sends only the entity name. Add the customer name to `/me` (a backend change), or leave it?

## Commands run and results

Frontend (`frontend/securedoc-ui`):
- `npm run build`: passed.
- `npm run test:standalone`: passed:
  - `test:auth` 8;
  - `test:vocabulary` 76;
  - `test:ui-framework` 17;
  - `test:oidc-signin` 67.
- `npx playwright test --config playwright.oidc-signin.config.ts review`: 8 of 8 (after the queue change).
- Entity switching and sign-in were not touched, so those tests ran once (inside `test:oidc-signin`).
- Proof pairs: the temporary script, copied into `e2e-oidc/` only while it ran and then removed.
  - U015 retakes: Review and New case, 4 of 4.
  - U016: 14 of 14 at 1440 and 14 of 14 at 1024 (`PROOF_TASK=U016/1024 PROOF_WIDTH=1024`).
  - Not committed. Kept at `C:\Users\mohan\AppData\Local\Temp\claude\b95672b1ca4b9404\b0a9c4a2-b578-473c-add1-7ca3402cbac5\scratchpad\zz-u016-proof.spec.ts` (the sample data is in `u016-data.ts` beside it), for U017.
- During the work, the first proof run timed out on two screens: the script moved to `/admin/…` before the first screen had settled, and the app's own redirect undid it. The script now waits for the first heading. Not an app fault.
- No backend change, so no backend build.

## Tests: counts per module, and any failures with their output

- `test:oidc-signin` went from 66 to 67.
  - **Added:** `review.spec.ts` "the queue case line is the Cases row line: main ID, then displayable fields only". Case A has a displayable borrower name, case B a hidden one; the queue reads "LN204881 · Maria Alvarez" and "LN204885", and never "Daniel Kim".
  - **Changed:** `review.spec.ts` test data only: `caseFor()` takes extra identifiers.
- No test was changed for the look. `reports.spec.ts` "neutral when empty" still checks that no target number uses the danger colour; it passes (they are muted now).
- No failures at the end.

## Files changed

- `frontend/securedoc-ui/src/studio/Review.tsx` (queue line) and `e2e-oidc/review.spec.ts`
- `frontend/securedoc-ui/src/studio/reports/reports.css`
- `frontend/securedoc-ui/src/studio/admin/admin.css`
- `frontend/securedoc-ui/src/studio/admin/IntegrationsArea.tsx`: Name and "How documents arrive" each take the full row in the editor (`cf-ad-span` wrappers only).
- `frontend/securedoc-ui/src/tokens.css`: `--cf-warn-ink`, light and dark.
- This report.

Rules checked:
- no raw hex outside `tokens.css`;
- no new breakpoint;
- `uiFrameworkContract.test.ts`, `src/index.css` and the legacy pages are unchanged;
- no new dependency;
- words, roles and behaviour unchanged, apart from the approved queue line.

## Commits (hash and message), or "not committed"

- `c1b7d89` docs(ui): U015 report with the owner's answers (applied in U016)
- "feat(ui): Reports and Admin match the canvas; borrower name in the Review queue (UI task U016)". The hash is in the chat summary.

## Not done, and why

- The 4 questions above.
- Known gaps, unchanged: the customer name in `/me`, stop reasons, the rejection reason, and the delivery receipt.
