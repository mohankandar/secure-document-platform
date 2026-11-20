# Report U026: Accessibility (WCAG 2.1 AA) and "?" help on Admin settings

Date: 2026-11-20
Branch: `feature/ui-accessibility` (from `feature/lexavault-r1b-tenant-identity` after the step 0 merge)
Outcome: DONE, with four items that need the owner (listed under Questions)

## Step 0

1. Committed the design chat's owner's answers on `feature/ui-time-saved`: `cb112eb docs(work): U024 report: owner's answers`. `docs/work/0021-task.md` left alone (untracked).
2. Merged into `feature/lexavault-r1b-tenant-identity`, no fast-forward: **`0c1c40f`** "Merge branch 'feature/ui-time-saved' into feature/lexavault-r1b-tenant-identity". Not pushed.
   - `.\Build-LexaVault-All.ps1`: PASS. Backend **620** (domain 51, application 229, scan bridge 47, infrastructure 18, API 275), 0 failures.
   - `npm run test:standalone` on the merge commit: **352** (auth 10, vocabulary 133, ui-framework 17, oidc-signin 112, tablet 80), 0 failures.
3. Created `feature/ui-accessibility` from it.
4. Portrait iPad: "What LexaVault did for you" sits **2 + 2**: one portrait-tablet rule in `reports.css`, the same media query as the headline's U021 rule. The tablet test now checks rows `[2, 2]` at 768×1024 and 820×1180 (passes at all five sizes).
5. Service levels: the grey line under "No review needed" reads "Cases delivered with no person reviewing or finishing them" (`Reports.tsx`, `e2e-oidc/reports.spec.ts`).

## Part A: what was checked and how

**A2.1 Automated.** New suite `npm run test:a11y` (`playwright.a11y.config.ts`, `e2e-a11y/`), added to `test:standalone`. It runs axe (`@axe-core/playwright` 4.14.0, dev dependency only, exact version) with the WCAG 2.0 and 2.1 A and AA rules on 36 screens and states, each in light and dark, as the role that uses it. **No rule is turned off.** (axe's "best-practice" rules are not WCAG criteria and are not run.) `A11Y_SURVEY=<file>` records violations without failing; that gave the "before" counts.

axe violations per screen (nodes), before → after. After is 0 everywhere, light and dark.

| Screen and state | Role | Before light | Before dark | After |
|---|---|---|---|---|
| Sign-in | — | 0 | 0 | 0 |
| Entity chooser | Supervisor, two entities | not recorded* | 0 | 0 |
| No access | no role | not recorded* | 0 | 0 |
| Cases | Supervisor | 3 | 3 | 0 |
| Case panel | Supervisor | 4 | 4 | 0 |
| Approve (confirm dialog) | Supervisor | 0 | 0 | 0 |
| Account menu (open) | Supervisor | 3 | 3 | 0 |
| Find a case or action (open) | Supervisor | 5 | 5 | 0 |
| New case | Processor | 3 | 3 | 0 |
| Review: queue, page, decision panel | Processor | 5 | 5 | 0 |
| Continue manually | Supervisor | 3 | 3 | 0 |
| Reports | Business Administrator | 11 | 13 | 0 |
| Reports: "How is this worked out?" panel | Business Administrator | 11 | 13 | 0 |
| Reports: Compliance report dialog (Custom) | Business Administrator | 11 | 13 | 0 |
| Reports | Report viewer | 11 | 11 | 0 |
| A problem card (Reports cannot be loaded) | Business Administrator | 2 | 2 | 0 |
| Admin › Redaction & retention (Fine-tune open) | Business Administrator | 2 | 2 | 0 |
| Admin › policy preview (Light chosen) | Business Administrator | 4 | 2 | 0 |
| Admin › Safe policy confirm step | Business Administrator | 4 | 2 | 0 |
| Admin › Case fields | Business Administrator | 2 | 2 | 0 |
| Admin › Add case field (sheet) | Business Administrator | 0 | 0 | 0 |
| Admin › Integrations: Receiving / Sending to SDV / Sending by barcode / All | IT Administrator | 3 / 2 / 2 / 3 | 2 / 2 / 2 / 2 | 0 |
| Admin › connection editor (sheet) | IT Administrator | 0 | 0 | 0 |
| Admin › Sign-in | IT Administrator | 2 | 2 | 0 |
| Admin › Audit trail | IT Administrator | 2 | 2 | 0 |
| Admin › Platform operations (health) | IT Administrator | 3 | 2 | 0 |
| Support | IT Administrator | 2 | 2 | 0 |
| Problem page | IT Administrator | 2 | 2 | 0 |
| Platform: overview / storage / licensing / branding / usage data | Platform Operations | 3 / 2 / 2 / 3 / 2 | 2 / 2 / 2 / 3 / 2 | 0 |
| **Total** | | **117** | **115** | **0** |

\* The survey run's light pass of these two hit a setup timeout (the dev server was reloading while I edited files); both had 0 in dark, have no colour-only differences, and pass at 0 in light and dark in the final run.

What axe found (all fixed): faint grey text below 4.5:1 (chart axes, hints, timeline labels, palette group names, logo preview), the "fewer" change and the "Having problems" word at 4.1:1, the keyboard hints inside small buttons at 2.3:1, Reports row names at 3.6:1 in dark, "Find a case or action" and the account button whose names did not contain their visible words, an unlabelled hidden file input on New case, and the Review / Continue manually page viewer that scrolls but could not be reached by keyboard.

**A2.2 Keyboard only.** Automated in `e2e-a11y/keyboard.spec.ts` (light and dark). Every Tab stop on the way is checked for a focus indicator of at least 2 px. All pass.

1. *Skip link.* First Tab: "Skip to content" appears top left; Enter moves to the main area.
2. *Open a case.* Tab (Cases nav, New case, filters, search) to the row link "LN-3002 …"; Enter opens the case panel; Esc closes it.
3. *Approve.* Tab to the row's "Approve LN-3002"; Enter opens "Release redacted documents?" with focus inside. Tab stays in the dialog (it is a native modal dialog: past the last button Tab goes to the browser's own controls, never to the page behind). Esc closes it and focus returns to "Approve LN-3002". Enter again, Tab to "Approve delivery", Enter approves.
4. *Decide findings in Review.* Tab through the queue (each item reads, for example, "Social Security Number, ••••0193 · p.1 · 62%"), on through Zoom out / Zoom in / Fit and the page, to "Redact R"; Enter redacts and the next finding is shown. The keys R (redact), V (keep visible), J / K (next / previous) and Shift R (redact all like this) also work. The page viewer is now a Tab stop; there the arrow keys scroll the page.
5. *Create a compliance report.* Tab to "Compliance report"; Enter opens the dialog with focus on the selected period; Esc closes it and focus returns to the button. Enter, Tab to "Last quarter", Enter, Tab to "Create PDF", Enter: the PDF downloads and the dialog closes.
6. *Change a policy and confirm.* Tab to the "Balanced" preset; ArrowDown chooses Light and the preview appears (announced, it is a status region). Tab to "Save Light", Enter: the confirm step opens with focus on "Keep Balanced". Esc = keep: the card goes back to Balanced, nothing is saved. Shift+Tab back to the presets, ArrowDown, Tab to "Save Light", Enter, Tab to the dialog's "Save Light", Enter saves.
7. *Account menu.* Tab to the account button, Enter opens it, arrow keys move through it, Esc closes it and focus returns to the button.
8. *"?" help.* Tab to it, Enter or Space opens the note, Esc (or Enter again) closes it, focus stays on the "?".

Focus is always visible: the general ring is 2 px ink (light) or 2 px light ink (dark). The yellow rings on the account button, account menu items and "Technical details" were 1.5:1 on white; they now use a new `--cf-focus` token: ink in light, the highlighter yellow in dark (unchanged look in dark). Text fields had a 1 px ink border plus a 12% glow; they now have a 2 px ink ring plus the same glow. Nothing traps focus except open dialogs.

**A2.3 By hand.**
- *Contrast:* text checked by axe on every screen above, plus the new `--cf-faint`, `--cf-warn-ink` values by calculation (table below). Non-text: focus rings fixed; **field and control borders are 1.6:1** (needs owner, Q1). Status is never colour alone: stages are words with a dot, targets have numbers and "met / missed" in their names, failed and stopped cases say so in words.
- *Names, roles, headings, landmarks, language:* axe passes; one `main` on every Case File screen; `lang="en"`. **Page titles** were the product name on every screen; now each screen has its own, for example "Review · LexaVault", "Redaction & retention · Admin · LexaVault" (rule in `words.ts`, unit-tested).
- *Forms:* field hints are now tied to their inputs (`aria-describedby`) in the shared Admin fields; error messages under the percentage, duration and minutes-per-page inputs are tied to the input and the input is marked `aria-invalid`; required text fields carry `aria-required` (optional ones already say "Optional"). The number fields in the connection editor ("Wait for an answer", "Try again up to") had a label not linked to the input; now linked. The Compliance report dates already used `aria-invalid` + `aria-describedby` + `role="alert"`.
- *200% zoom* (1280 px window at 200% = 640 px layout): Cases and Reports lose nothing and nothing overlaps (pictures).
- *Reflow at 320 px:* Cases, Reports, Approve and the Compliance report dialog. Before: the top bar was 503 px wide (sideways scroll on every screen) and Reports' paired sections and period buttons were too wide. Fixed below the existing 640 px breakpoint only: the top bar takes two rows (the workspace links on the second), Reports' pairs take one column and the period buttons wrap. Automated in `e2e-a11y/reflow.spec.ts`. Review and Continue manually: exception by owner decision (U021), not redesigned.
- *Text spacing (1.4.12):* with line height 1.5, letter spacing 0.12 em, word spacing 0.16 em and paragraph spacing 2 em, Reports keeps all text and nothing is cut off (picture).
- *Motion:* `prefers-reduced-motion` already stops every animation and transition in the Case File (studio.css); the new "?" note has no motion.
- *Live regions:* toasts are in an `aria-live="polite"` region; the policy preview is `role="status"`; errors on the Compliance report are `role="alert"`; the zoom value is live; the "?" note is announced through its own polite live region.
- *Review page image, by screen reader:* the page image has alt text ("Page 1 of Closing_Disclosure.pdf"); the marks on it are decoration. The text equivalent is the queue and the decision panel: the queue is a navigation of buttons, one per finding, each naming the type, the last digits found (or "place not found"), the page and the confidence; the decision panel (a complementary region named "Decision") gives the type as a heading, "Text found" with its line of context, why it was asked, the case and document, then "Redact" and "Keep visible". A screen-reader user moves through the queue, reads the panel, and presses Redact or Keep visible; the outcome is announced by the toast. Gap: a finding whose place was not found needs a box drawn with a pointer before it can be redacted (Q3).

**Colour changes (all in `tokens.css`)**

| Token | Before | After | Ratio before → after |
|---|---|---|---|
| `--cf-faint` light | #9aa0a8 | #686e77 | 2.4 → 4.6 on the page (#f2f3f4), 2.6 → 5.1 on white |
| `--cf-faint` dark | #636973 | #8a9099 | 3.2 → 5.6 on the surface, 3.5 → 6.2 on the page |
| `--cf-warn-ink` light | #9a7a00 | #806800 | 4.1 → 5.4 on white |
| `--cf-focus` (new) | (yellow #f4d03f on light) | ink on light, #f4d03f on dark | 1.5 → 18 on white |

Other contrast fixes without a new colour: the keyboard hint in small buttons takes the button's own colour instead of 55% opacity (2.3 → 5.6); Reports' table row names use the Case File ink instead of the older pages' grey (3.6 → 15 in dark).

## A4. Findings list

| # | Screen | WCAG criterion | What was wrong | Fix | Status |
|---|---|---|---|---|---|
| 1 | Cases, case panel, Review, Reports chart, palette, Branding preview | 1.4.3 Contrast (Minimum) | Faint grey text 2.4–3.5:1 | `--cf-faint` light and dark | fixed |
| 2 | Admin policy preview, Integrations, Platform | 1.4.3 | "fewer" and "Having problems" 4.1:1 | `--cf-warn-ink` | fixed |
| 3 | Review | 1.4.3 | Key hints in small buttons 2.3:1 | full colour | fixed |
| 4 | Reports, by entity table (dark) | 1.4.3 | Row names 3.6:1 | Case File ink | fixed |
| 5 | Every screen (top bar) | 2.5.3 Label in Name | "Find a case or action" button name left out "Ctrl K"; account button's name did not start with what it shows | name from the visible words (+ `aria-keyshortcuts`); account button reads "<person> <entity>, account menu" | fixed |
| 6 | New case | 4.1.2 Name, Role, Value; 1.3.1 | Hidden file input had no name | named "Choose PDF files" / "Feed a PDF" | fixed |
| 7 | Review, Continue manually | 2.1.1 Keyboard | Zoomed page could not be scrolled by keyboard | viewer is a Tab stop; arrows scroll there | fixed |
| 8 | Account menu, its items, "Technical details" | 2.4.7 Focus Visible (task: 2 px, 3:1) | Yellow ring 1.5:1 on white | `--cf-focus`: ink on light | fixed |
| 9 | Admin fields, Reports filters, search boxes | 2.4.7 (task: 2 px) | 1 px border as the only solid focus mark | 2 px ink ring | fixed |
| 10 | Audit trail action menu | 2.4.7 | Focus shown only as a faint background | 2 px inset ring | fixed |
| 11 | Every screen | 2.4.2 Page Titled | Same title on every screen | title per screen | fixed |
| 12 | Every screen at 320 px; Reports | 1.4.10 Reflow | Top bar 503 px wide; Reports pairs and period buttons too wide | two-row top bar and one column below 640 px | fixed |
| 13 | Admin fields | 1.3.1 Info and Relationships; 3.3.1, 3.3.2 | Hints and errors not tied to inputs; two number fields not labelled | `aria-describedby`, `aria-invalid`, `aria-required`, `htmlFor` | fixed |
| 14 | Review, Continue manually at 320 px | 1.4.10 | Desktop and tablet screens | — | exception: owner decision (U021) |
| 15 | All fields and controls | 1.4.11 Non-text Contrast | Field and control borders 1.6:1 (light and dark) | proposed darker border; changes the look | needs owner (Q1) |
| 16 | Review, Cases | 2.1.4 Character Key Shortcuts | R, V, J, K and Shift R (Review) and J, K (Cases) work page-wide with no way to turn them off | proposed: active only while focus is in the queue, the page or the decision panel | needs owner (Q2) |
| 17 | Review, Continue manually | 2.1.1 Keyboard | Marking a "place not found" finding, or a region in Continue manually, needs a pointer drag | — | needs owner (Q3) |
| 18 | Reports chart | 1.4.13 / 2.1.1 | Per-day values show on hover only; a screen reader gets them from a hidden table, a sighted keyboard user only from the axis | — | needs owner (Q4, small) |
| 19 | Sign-in, entity chooser | 1.3.1 / 2.4.2 | These two screens (older App.tsx, frozen area) have no `main` and say "Secure Document Platform" | not changed: frozen area | needs owner (Q5) |

## Draft conformance table (WCAG 2.1 AA)

| Criterion | Level | Status | Note |
|---|---|---|---|
| 1.1.1 Non-text Content | A | Supports | Icons hidden or named; page image has alt text; chart has a data table |
| 1.2.1–1.2.5 Time-based media | A/AA | Not applicable | No audio or video |
| 1.3.1 Info and Relationships | A | Partially supports | Case File screens yes; sign-in and chooser lack a main landmark (frozen) |
| 1.3.2 Meaningful Sequence | A | Supports | |
| 1.3.3 Sensory Characteristics | A | Supports | |
| 1.3.4 Orientation | AA | Supports | Portrait and landscape tablets tested (U021) |
| 1.3.5 Identify Input Purpose | AA | Not applicable | No fields collect the user's own personal data |
| 1.4.1 Use of Color | A | Supports | Stages and targets also in words |
| 1.4.2 Audio Control | A | Not applicable | |
| 1.4.3 Contrast (Minimum) | AA | Supports | After the token fixes |
| 1.4.4 Resize Text | AA | Supports | 200% checked |
| 1.4.5 Images of Text | AA | Supports | Only customer logos |
| 1.4.10 Reflow | AA | Partially supports | Review and Continue manually are desktop and tablet screens |
| 1.4.11 Non-text Contrast | AA | Partially supports | Focus rings fixed; field and control borders 1.6:1 (Q1) |
| 1.4.12 Text Spacing | AA | Supports | |
| 1.4.13 Content on Hover or Focus | AA | Supports | Chart tip dismisses on leave; "?" notes are click/tap, Esc closes |
| 2.1.1 Keyboard | A | Partially supports | Drawing a box (place not found, Continue manually) needs a pointer (Q3) |
| 2.1.2 No Keyboard Trap | A | Supports | |
| 2.1.4 Character Key Shortcuts | A | Does not support yet | Q2 |
| 2.2.1 Timing Adjustable | A | Supports | Toasts are status messages; what they confirm stays visible on the screen |
| 2.2.2 Pause, Stop, Hide | A | Supports | Only short loading animations; reduced motion honoured |
| 2.3.1 Three Flashes | A | Supports | |
| 2.4.1 Bypass Blocks | A | Supports | Skip to content |
| 2.4.2 Page Titled | A | Supports | Per screen (sign-in screens keep the product name) |
| 2.4.3 Focus Order | A | Supports | |
| 2.4.4 Link Purpose (In Context) | A | Supports | |
| 2.4.5 Multiple Ways | AA | Supports | Navigation and "Find a case or action" |
| 2.4.6 Headings and Labels | AA | Supports | |
| 2.4.7 Focus Visible | AA | Supports | 2 px, 3:1 or more |
| 2.5.1 Pointer Gestures | A | Supports | No path or multi-finger gestures needed except drawing (2.1.1 note) |
| 2.5.2 Pointer Cancellation | A | Supports | Actions on release (native buttons) |
| 2.5.3 Label in Name | A | Supports | After fix 5 |
| 2.5.4 Motion Actuation | A | Not applicable | |
| 3.1.1 Language of Page | A | Supports | |
| 3.1.2 Language of Parts | AA | Not applicable | English only |
| 3.2.1 On Focus / 3.2.2 On Input | A | Supports | |
| 3.2.3 Consistent Navigation / 3.2.4 Consistent Identification | AA | Supports | |
| 3.3.1 Error Identification | A | Supports | |
| 3.3.2 Labels or Instructions | A | Supports | |
| 3.3.3 Error Suggestion | AA | Supports | Messages say the allowed range |
| 3.3.4 Error Prevention (Legal, Financial, Data) | AA | Supports | Approve and policy changes confirm first |
| 4.1.1 Parsing | A | Supports | |
| 4.1.2 Name, Role, Value | A | Supports | |
| 4.1.3 Status Messages | AA | Supports | Toasts, preview, "?" notes in live regions |

## Part B: "?" help on Admin settings

One shared component, `src/studio/admin/HelpTip.tsx`, no new dependency. A 20 px ring "?" button named "About <setting>", `aria-expanded` and `aria-controls`; the note opens under it inside a polite live region (so it is announced), kept inside the window; Esc, the button again, or a click or tap outside closes it; Esc is taken by the note first, so it never also closes a sheet. On a coarse pointer the tap area is 44 px without moving the layout. All texts are in one file, `src/studio/admin/helpTexts.ts`.

Where: next to the title of every Admin settings card, and next to these individual settings: the presets ("How much should a person check?"), Fine-tune (next to "Send to review"), the three keep-for fields and the approval switch, minutes per page ("Time saved estimate"), "Break reports down by". The fieldset names are unchanged (the legend's text names them, not the "?"). None on case screens, Reports or Review (tested).

Every text, for review:

| "?" next to | Text |
|---|---|
| Sensitive data | Chooses which kinds of personal data LexaVault finds and blacks out, and when a person checks a finding first. All four kinds are on by default. A change applies to new cases only; cases already processed keep the result they had. |
| How much a person checks (presets) | Strict, Balanced and Light set how many findings a person checks before they are redacted. Balanced is the default: findings below 80% confidence go to a person. Your choice is part of your compliance record, and every change is recorded in the audit trail. |
| Fine-tune by hand | Sets the review rule and the confidence level yourself instead of using a preset. Below the level you set, a finding always goes to a person; nothing is skipped. Any value that matches no preset shows as Custom. |
| Document intelligence | Lets AI read the case fields, such as the loan number, from the documents. The default is Off: staff type them. Observe records suggestions to compare without filling anything in; On fills them in for staff to confirm. |
| Keeping and deleting | How long LexaVault keeps each copy of a case before deleting it for good, and whether a supervisor approves delivery. Shorter times keep less personal data; this is part of your retention policy for compliance, and every change is recorded in the audit trail. |
| Keep originals for | How long the documents as received are kept, unredacted. The default is 24 hours. After this time a case can no longer be processed again, because there is nothing left to process. |
| Keep redacted copies for | How long the redacted copy is kept after it is made, so it can be sent again if needed. The default is 24 hours, and the most allowed is shown under the field. |
| Keep stopped cases for | How long a case that stopped before delivery is kept, so someone can fix and finish it. The default is 7 days. After that it is deleted. |
| Require a supervisor's approval | When on, every case waits in Approval until a supervisor signs off before it is delivered. It is off by default. Turning it on adds a step, and a record of who approved, to every case. |
| Report targets | The goals that Reports measures your work against: how fast cases are delivered, how many need no review, and how fast reviews are picked up. The defaults are 4 hours for 95% of cases, 90% needing no review, and 1 hour for 80% of reviews. Changing them changes only how Reports judges the work, not the work itself. |
| Minutes per page | How long a person would take to check one page by hand. Reports and the compliance report use it to estimate the review time LexaVault saved. The default is 1 minute, kept low so the estimate never overclaims; changing it is recorded in the audit trail. |
| Break reports down by | Lets Reports split its numbers by a case field, such as the property state. Only fields with a short list of allowed values can be used. It is off for every field by default. |
| Case fields | The details staff enter or confirm for each case, such as the loan number and the borrower name. The main ID names the case everywhere in LexaVault. The order here is the order staff see. |
| Columns on Cases | Which columns people may show on the Cases screen, and which are shown to new users. The main ID is always shown. Changing it does not change any case. |
| Receiving documents | The systems, folders and scanners that bring documents into LexaVault. Each one has its own sign-in details and status. Switching one off stops new documents coming in from it. |
| Sending to SDV | Where the full redacted case goes when it is delivered, and the sign-in details LexaVault uses there. Test a change before you save it, so delivery is not interrupted. |
| Sending by barcode | Also sends chosen pages, as separate files, to other places, picked by a barcode or text marker on the page. The full redacted case still goes to SDV. Nothing is sent this way until a rule names a place. |
| All connections | Every connection, receiving and sending, in one list. Use the other views to add a connection of one kind. |
| Rules (Sending by barcode) | Each rule names a barcode or text marker, where on the page to look for it, and where to send the pages that carry it. A page that matches no rule is sent only with the full case. A rule can be switched off without removing it. |
| Sign-in | Shows the login system your people use to sign in to LexaVault. It is managed through the login hub and cannot be changed here. To change it, contact LexaVault support. |
| Branding | The product name, organization name, colours and logos people see in LexaVault. By default every customer sees the installation's branding; a customer can be given its own. Status colours for success, warning and error never change. |
| Processing storage | Where LexaVault keeps documents while it works on them. One storage is the default for every customer; a customer can be given its own. A new storage must work from every server before it can be used. |
| Usage data | Whether LexaVault sends counts about how the product is used, so it can be improved. Only the items listed are sent, never document content or who did what. It is set when LexaVault is installed and cannot be changed here. |
| Licensing | Shows each customer's license: what it allows, its limits and when it ends. When enforcement is on, a customer without a valid license cannot process new cases. A new license is installed by pasting the signed license LexaVault sends. |

Defaults quoted were checked in the backend (`TenantLifecyclePolicy.defaults`: 24 h, 24 h, 7 days, approval off; Balanced 0.80; `ReportingConfiguration`: 240 min / 95%, 90%, 60 min / 80%, 1 minute; document intelligence default Off). Note: the Report targets "no review" default in the backend is 90%; the U024 test fixtures use 70%.

## Commands run and results

- `git commit` (U024 answers), `git merge --no-ff feature/ui-time-saved`, `git checkout -b feature/ui-accessibility`.
- `.\Build-LexaVault-All.ps1` on the merge: PASS, backend 620. On this branch: PASS, backend **620** (51, 229, 47, 18, 275), 0 failures; TypeScript and Vite build PASS.
- `npm install --save-dev --save-exact @axe-core/playwright@4.14.0` (owner-approved dev dependency; npm's audit notice about existing packages is unchanged and not acted on).
- `npm run build`: PASS.
- `npm run test:standalone` on this branch: **462**, 0 failures (auth 10, vocabulary 134, ui-framework 17, oidc-signin 112, tablet 85, a11y 104).
- `U026_PROOF=1 npx playwright test --config playwright.a11y.config.ts proof --project light` and `U026_PROOF=1 npx playwright test --config playwright.tablet.config.ts -g "U026|2 \+ 2"`: proof pictures, 13 and 10 passed.

## Tests

- New: `test:a11y` **104 passed** (52 tests, each in light and dark): 36 axe screen states (`screens.spec.ts`), 7 keyboard walkthroughs (`keyboard.spec.ts`), 2 reflow at 320 px (`reflow.spec.ts`), 13 "?" tests (`helpTip.spec.ts`: names, Enter/Space/Esc, focus return, click outside, inside the window, focus ring, every Admin card, none on Cases, Reports or Review).
- `test:tablet`: one new test (the "?" by tap, 44 px tap area, tap outside closes) and the 2 + 2 check added to the U024 tiles test, at all five iPad sizes.
- `test:vocabulary`: one new test (page title per screen).
- **Existing tests changed** (said here as the rules ask): `e2e-oidc/adminSignIn.spec.ts` two tests expected the read-only Sign-in card to have no button; it now has exactly one, its "?" (the test checks that). `e2e-oidc/adminBusiness.spec.ts` `getByLabel('Keep originals for')` now also matched the button "About Keep originals for"; it uses `{ exact: true }` like the two fields next to it already did. `e2e-oidc/reports.spec.ts`: the new Service levels line. No test was weakened.

## Files changed

- Step 0: `reports.css` (portrait 2 + 2), `Reports.tsx`, `e2e-oidc/reports.spec.ts`, `e2e-tablet/tablet.spec.ts`.
- Accessibility: `tokens.css`, `studio.css`, `admin/admin.css`, `reports/reports.css`, `CaseFileApp.tsx`, `words.ts` (+ test), `AccountMenu.tsx`, `UploadCapture.tsx`, `ScanCapture.tsx`, `Review.tsx`, `support/ManualCase.tsx`, `admin/shared.tsx`, `admin/PolicyArea.tsx`.
- Help: new `admin/HelpTip.tsx`, `admin/helpTexts.ts`; `admin/shared.tsx`, `PolicyArea.tsx`, `FieldsArea.tsx`, `IntegrationsArea.tsx`, `RoutingRules.tsx`, `IdentityArea.tsx`, `BrandingSection.tsx`, `StorageSection.tsx`, `TelemetrySection.tsx`, `LicensingSection.tsx`.
- Tests and tooling: new `playwright.a11y.config.ts`, `e2e-a11y/` (harness, screens, keyboard, reflow, helpTip, proof); `package.json`, `package-lock.json`; `e2e-oidc/adminSignIn.spec.ts`, `e2e-oidc/adminBusiness.spec.ts`.
- No backend change, no schema change, no new app dependency, no raw hex outside `tokens.css`, no new breakpoint (640, 768 and 1024 are the documented ones). `uiFrameworkContract.test.ts` untouched and passing. `docs/work/0021-task.md` left alone.

## Proof (`compare/U026/`, not committed)

- Before / after of each visible fix, light and dark: `reports-chart-axes-*`, `case-panel-timeline-*`, `cases-hint-*`, `policy-preview-*`, `field-focus-*`, `review-decision-*` (key hints), `account-button-focus-*`. "Before" pictures put the old token values back with a style tag on the same screen.
- "?" open on three Admin cards, light and dark: `help-sensitive-data-*`, `help-keeping-*`, `help-minutes-per-page-*`; on iPad: `ipad-help-open-<size>.png` (five sizes).
- Step 0 tiles 2 + 2: `ipad-tiles-2x2-768x1024.png`, `ipad-tiles-2x2-820x1180.png`.
- 200% zoom: `zoom200-cases.png`, `zoom200-reports.png`. 320 px: `reflow320-cases.png`, `reflow320-reports.png`, `reflow320-approve-dialog.png`, `reflow320-compliance-dialog.png`. Text spacing: `text-spacing-reports.png`.
- For Q1: `question-field-borders.png`.

## Commits

- `a8466e9` feat: U026 accessibility (WCAG 2.1 AA) fixes, "?" help on Admin settings, and step 0
- the commit that adds this report: docs(work): U026 report

Not pushed, not merged.

## Questions for the owner

1. **Field and control borders (WCAG 1.4.11).** Input, select and segmented-control borders are 1.6:1 in both themes; AA asks 3:1 for the edge that shows where a field is. Proposed: light `--cf-line-strong`-style border #8a919b (3.2:1), dark #707680 (3.3:1), for form controls only (cards and dividers unchanged). It makes fields visibly darker: see `compare/U026/question-field-borders.png`. Yes / no?
2. **Single-key shortcuts (WCAG 2.1.4, level A).** R, V, J, K, Shift R in Review and J, K in Cases work wherever focus is. Proposed fix with no visible change: they work only while focus is in the queue, the page or the decision panel (Review) or the case list (Cases), and Review opens with focus on the current finding. Alternative: an "Keyboard shortcuts on/off" switch in the account menu. Which?
3. **Marking a place by keyboard (WCAG 2.1.1).** A finding whose place was not found, and a region in Continue manually, can only be marked by dragging. Options: (a) accept as a documented exception (the case can still be finished by a person with a mouse, or by a colleague); (b) add a keyboard way to draw a box (arrow keys move and size it). (b) is a new feature. Which?
4. **Reports chart values by keyboard.** Per-day numbers show on hover; screen readers get them from a hidden table. Make each day focusable so the tip also shows on focus (no visible change until focused)? Small change.
5. **Sign-in and entity chooser** are the older App.tsx screens (frozen area: OIDC session continuity). They have no `main` region and say "Secure Document Platform". May I add a `main` and the product name there (markup only, no sign-in logic)?

## Suggested next step

Owner's answers to Q1–Q5, then the short accessibility statement written from the conformance table above.

## Owner's answers (2026-11-20, recorded by the design chat)

1. Field and control borders: yes, the proposed darker borders (light #8a919b, dark #707680), form controls only.
2. Single-key shortcuts: limit them to where focus is (Review: queue, page, decision panel; Cases: the case list), and Review opens with focus on the current finding. No on/off switch.
3. Marking a place by keyboard: (a) documented exception for now; a keyboard way to draw a box goes to the backlog.
4. Reports chart: yes, each day focusable so its value shows on focus.
5. Sign-in and entity chooser: yes, add a `main` region and the product name, markup only, no sign-in logic.
Also: the proof folder landed at the repo root (`compare/`); move it to `docs/work/ui/compare/U026/`, which is not committed.
All in task U027.
