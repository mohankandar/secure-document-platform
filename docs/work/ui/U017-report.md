# Report U017: match the canvas, Platform, Audit and Support, then a final sweep

Date: 2026-11-18
Branch: feature/ui-polish
Outcome: DONE. There are 2 questions for the owner, and the known gaps are listed.

## The owner's U016 answers and the design chat's notes, done first

| Item | Done |
|---|---|
| 1. Retention durations and report targets | No change, as answered (the app's number plus unit, and its sentences). |
| 2. Supervisor approval | No change, as answered (the switch stays). |
| 3. Sign-in card | No change, as answered (plain rows, no boxes, no pill). |
| 4. Customer name in the Admin eyebrow | Not done now, as answered. It belongs to the backend work after the UI series (add the customer name to `/me`). The eyebrow shows the entity name only. |
| Note: "Cases" in the IT Administrator's nav | **The fault was in the mock, not the app.** `CaseFileApp` shows Cases only with `DASHBOARD_ACCESS` (`canDesk`), so the app needed no fix. The proof persona had `DASHBOARD_ACCESS` and lacked `TECHNICAL_HOLD_MANAGE`. It now has exactly what `ProductAuthorizationPolicy` grants `TENANT_IT_ADMIN`. I checked every proof user against the policy: Supervisor, Business Administrator, Report Viewer and No role were already correct. A new Platform Operations persona was added with exactly the `PLATFORM_OPERATIONS_ADMIN` capabilities. In the retaken pairs the IT Administrator's nav reads Admin · Support, as on the canvas. |
| Note: avatar initials | Fixed. `/me` sends only `username` (no given or family name), so the initials come from it. It is split on spaces as well as `. _ -`, taking the first and last word: "Arun Pillai" gives AP, "Mary Ann Smith" gives MS, and one word gives one letter. The function moved from `AccountMenu.tsx` to `src/studio/userName.ts`, so a node test can run it. There are 4 new tests in `userName.test.ts`, run by `test:vocabulary`. |

## Screens

The pairs at 1440 px are in `compare/U017/` (light and dark). The final sweep at 1024 px is in `compare/U017/1024/` and covers every U014 to U017 screen, 23 screens in both themes. None of the pairs are committed.

| Screen | What differed | Fixed | Left, and why |
|---|---|---|---|
| Platform operations, Overview (`AdminPlatform.html`), Platform Operations | The sub-tabs were a boxed segment; the canvas uses plain 14 px words 16 px apart, with the current one at 500 and a yellow line under it. Each service took two lines (name 15/500, then the product); the canvas uses one line: name at 14, the product in 12 px muted beside it, and the status at 13 regular on the right ("Having problems" in the warning ink). The watched folder had a row with a dot inside the card; the canvas puts the path in the card's subtitle, shows a soft status pill at the top right, and puts the counts in the line below. | All of these. The sub-tabs keep their buttons and group name, so they work the same. | "Refresh" and the Technical incidents card are existing; the canvas has neither. The context line under the tabs stays (as in U016). The canvas's products ("Tesseract 5.4", "llama3.1-8b · answers in 1.8 s", "PostgreSQL 16", "Okta") and its Sign-in row are not in the overview answer; the app shows what the server sends. |
| Overview, IT Administrator | The same as above, without the sub-tabs, Run health checks and Technical details (existing behaviour). | The same. | None. |
| Processing storage (`PlatformStorage.html`) | "Ready · 1 server" was a small grey tag; the canvas uses a green pill. Status words were muted; the canvas uses ink, red when it can't be used. Test was a ghost button and Stop using a red filled button; the canvas uses bordered small buttons for both. The Customer and Storage pickers were narrow; the canvas makes them full width, with a 36 px Apply. Technical details sat outside the cards as an eyebrow; the canvas puts it at the foot of the second card as "▸ Technical details". | All of these. Stop using still asks first, in a red confirmation. | The canvas reads "Not reachable from server node-a" and "Can't write here"; the app says "Not working · the test failed" and "· can't write here" (existing words from the server's codes). Make default stays disabled until a storage works from every server (behaviour). |
| Branding (`PlatformBranding.html`) | "Apply to" sat under the title; the canvas puts it at the top right, 320 px wide. The preview column was 360 px; the canvas uses 520. Accent colour and Logo each took a full row; the canvas pairs them. | All of these. Below 900 px "Apply to" drops under the title. | Usage data is its own sub-tab in the app (U010); the canvas draws its card under Branding. The preview shows the account chip, not a "Primary" button (existing). "More options" and the logo's image box are existing controls. |
| Licensing (`PlatformLicensing.html`) | The content differs throughout, which was expected. | Look only, as the task says: the cards, table, sub-tabs and "▸" disclosures already use the shared look and now match. | The content stays as it is until Phase 5 (U010). That covers the banner, the "Customer · entity" table with pages and entities, and the install card. |
| Audit trail (`AuditTrail.html`), IT Administrator | Everything sat in a card; the canvas puts the toolbar and table straight on the page. The pickers and search stretched; the canvas uses 220, 200 and 260 px, with Export at the right. The time was 12 px sans; the canvas uses 13 px mono. "What happened" was 400; the canvas uses 500. The result was a filled chip; the canvas uses a plain green or red word. "More filters" was an eyebrow. | All of these. | "More filters" opens below the toolbar instead of being a button in it (existing behaviour). Names show as "Person 00000001" and cases as a short ID, because the audit records carry no names or loan numbers (known gap). |
| Support (`Support.html`), IT Administrator | Row columns: the canvas uses 200 · 1fr · 170 · 150 · 90 · 100 with no gap and 64 px rows; the app used its own widths with 16 px gaps. "More filters" was an eyebrow. | Both. Below 1200 px the columns keep a 12 px gap, because the canvas has no narrow layout. | No borrower names (U012): the line under the case says where it stopped. The canvas's "More filters" button is a disclosure in the app (behaviour). |
| Problem page (`ProblemDetail.html`) | The page was full width; the canvas uses one 1100 px column, centred. The crumbs were 13 px in sentence case; the canvas uses an uppercase eyebrow. Cards were radius 10 with 14 px titles; the canvas uses radius 8 and 16/600. Hints were 12 px; the canvas uses 13. The timeline was a tight list with a 140 px time; the canvas uses rows 12 px apart with hairlines and a 150 px, 13 px mono time. "More actions" and Technical details were eyebrows. | All of these. | No borrower name in the crumbs (U012). "Problem: Open" beside the status is existing. "IT Administrator only." is not shown: the card is only reached by people who may act. The canvas's mono summary beside Technical details is new words, so it was not added. |

**Final sweep (1024 px, dark and light).** Nothing on the U014 to U016 screens moved: the only shared rule added (a hairline above Technical details at the foot of a card) matches what the base disclosure already drew. The avatar now reads "AP" for the IT Administrator and "OC" for Ops on call on every screen. At 1024 px nothing scrolls sideways. The Support list fix is the 12 px gap above.

**Who sees what.** I checked each row of `WhoSeesWhat.html` against the app's gates (`CaseFileApp`, `Admin.tsx`, `HealthArea`). Each role sees what it lists, apart from question 1.

## Questions for the owner

1. **Retry processing "in Cases" for the IT Administrator.** `WhoSeesWhat.html` lists Retry processing under "Cases, Support" for the IT Administrator, but that role has no `DASHBOARD_ACCESS`, so it never sees Cases. It can retry only from Support (the problem page). Is Support alone right, or should the IT Administrator also see Cases? Roles were not changed.
2. **The e2e tests give the IT Administrator and Platform Operations `DASHBOARD_ACCESS`.** `adminAudit`, `adminIntegrations`, `adminPlatform` and `adminSignIn` use capability lists that are not the policy's (they add `DASHBOARD_ACCESS`; some lack `TENANT_AUTH_MANAGE` or `TECHNICAL_HOLD_MANAGE`). The tests pass, and the rule for this series lets tests change only for look, so I left them. Should they be corrected to the policy's lists in a small follow-up?

## Commands run and results

Frontend (`frontend/securedoc-ui`):
- `npm run build`: passed.
- `npm run test:standalone`: passed:
  - `test:auth` 8;
  - `test:vocabulary` 80 (was 76; plus 4 initials tests);
  - `test:ui-framework` 17;
  - `test:oidc-signin` 67.
- Entity switching and sign-in were not touched, so each suite ran once.
- Proof pairs came from the temporary script, which was copied into `e2e-oidc/` only while it ran and then removed:
  - U017 before: 16 of 16;
  - U017 after: 16 of 16;
  - the 1024 sweep of every screen: 46 of 46.
- The pairs are not committed. The script is kept at `C:\Users\mohan\AppData\Local\Temp\claude\b95672b1ca4b9404\75ad0542-b379-492f-b00c-6768530665e9\scratchpad\zz-u017-proof.spec.ts`. The first Audit run waited on a hidden `<option>` with the same words as a row; the script now waits for the table cell. This was not an app fault.
- No backend change, so no backend build.

## Tests: counts per module, and any failures with their output

- **Added:** `src/studio/userName.test.ts` with 4 tests (display name to AP and MS; one word to one letter; logins and emails unchanged; empty to U), added to `test:vocabulary` in `package.json`.
- **Changed, for look only:** `e2e-oidc/adminPlatform.spec.ts`, "IT Administrator: services and the watched folder…". The folder's status line was one `.cf-ad-conn-status` element; it is now the pill (`.cf-ad-pill`, "Working · checked 1 min ago") plus the line below ("2 accepted, 1 waiting for its READY file. Scan now…"). The words are the same, split in two.
- No failures at the end.

## Files changed

- `frontend/securedoc-ui/src/studio/userName.ts` (new) and `userName.test.ts` (new); `AccountMenu.tsx` imports `initials` from it; `package.json` runs the test.
- `frontend/securedoc-ui/src/studio/admin/HealthArea.tsx`: sub-tab class, one-line service rows, and the watched folder's head, pill and line.
- `frontend/securedoc-ui/src/studio/admin/StorageSection.tsx`: the pill, button variants, full-width pickers, and Technical details moved into the card.
- `frontend/securedoc-ui/src/studio/admin/BrandingSection.tsx`: "Apply to" in the card head, and Accent colour and Logo paired.
- `frontend/securedoc-ui/src/studio/admin/AuditArea.tsx`: no card, and the result as a word.
- `frontend/securedoc-ui/src/studio/admin/admin.css` and `src/studio/support/support.css`.
- `frontend/securedoc-ui/e2e-oidc/adminPlatform.spec.ts` (one locator).
- This report.

Rules checked:
- no raw hex outside `tokens.css`;
- no new breakpoint (1200 and 900 are already used in `admin.css`);
- `uiFrameworkContract.test.ts`, `src/index.css` and the legacy pages are unchanged;
- no new dependency;
- words, roles and behaviour unchanged, apart from the initials fix.

## Commits (hash and message), or "not committed"

- `c51f570` docs(ui): U016 report with the owner's answers (applied in U017)
- "feat(ui): Platform, Audit and Support match the canvas; avatar initials from display names (UI task U017)". The hash is in the chat summary.

## Not done, and why

- The 2 questions above.
- Known gaps, unchanged:
  - the customer name in `/me` (owner: backend work after the UI series);
  - stop reasons;
  - the rejection reason;
  - the delivery receipt;
  - names and loan numbers in audit records.
