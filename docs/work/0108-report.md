# 0108 report: Cases at volume

Status 2026-11-28: **all 7 parts built** on `feature/0108-cases-at-volume` (part 1 on 2026-11-27, parts 2 to 7 tonight), with the owner's Stopped answer (2026-11-27 22:00) and defect 2 (double focus ring). Design: approved board version 1795751690-af63 (`CasesAtVolume.dc.html`, iPad landscape and portrait). Plan: `docs/work/0108-plan.md`. The newest integration branch (`bf6fe07`, with 0118) is merged in (no rebase).

## What changed

**Backend**
- Migration **091** `case_page_settings` (one row per entity; 090 is 0118's): amber and red waiting times (defaults 4 h and 8 h) and the days before a case without documents expires (0111 question 1, "yes" 2026-11-27 18:22). No row: the defaults; the installation's `securedoc.cases.empty-case-expiry-days` stays the default days. The table refuses red before amber and out-of-range values itself.
- `GET/PUT /api/v1/business-admin/case-page-settings` (`TENANT_POLICY_MANAGE`, the signed-in person's entity only, `no-store`). A save out of range is refused whole (LV-CFG-001); a real change writes one audit record (`DASHBOARD_CONFIGURATION_CHANGED`, the old and new numbers); the same values again write nothing.
- The empty-case expiry now uses each entity's days (an entity whose days can't be read is left alone).
- `GET /me/case-view` also answers the entity's amber/red times.
- The case list carries `uploadedAt` (newest document upload), so View > Columns > Uploaded shows.
- Migration 089 got its comments (0118 rule); `docs/data/DATA-DICTIONARY.md` regenerated with `scripts\Update-LexaVault-DataDictionary.ps1` (117 tables, 1,109 columns).

**Cases page (Desk.tsx)**
- No summary line, no All / Needs you switch. Six slim stage boxes (New, Redacting, Review, Approval, Exported, Stopped), numbers with commas, the sentence on hover; the Exported box has an (i) "Exported today". A box lists its stage; the heading row then shows its name, count, sentence and "Show all"; clicking it again shows all.
- Heading row "Needs you N · sorted by Received, oldest first" with Filter by and View at its right end.
- Groups: Needs you open (25, then Show 25 more); In progress and Exported today one line each with the true count until opened. A filter text opens every group, so a match is never hidden.
- Who sees what (owner 2026-11-27 21:57 and 22:00): Needs you holds business work only (review, approval, a refused export for someone who may resend). **Stopped cases are not listed on Cases**: IT works them in Support; the Stopped box still counts them, and for IT it opens Support. Rejected and cancelled cases are final: not on Cases (Reports, Find). Older exported cases: Find or Reports. Find still opens any case; a stopped case's panel says "With IT".
- Rows: no next-step button and no next-step text; the reason, destination and a Low priority stay. Tags after the name: Urgent, Send failed. On Needs-you rows the time is the waiting time, amber after the entity's amber time and red after its red time (with words for a screen reader, not colour alone).
- New cases never jump the list: a dark "N new cases · Show" pill floats over it (it counts only cases that belong in what is open); counts move at once. Show adds them in sort order, scrolls to the first and highlights them for 2 s.
- The case card (CaseDrawer, content unchanged) is a 480 px card that slides over the list from the right (0.2 s, ease-out, none with reduced motion), full height under the top bar, scrolling inside; the case number and ✕ stay at the top. Nothing is dimmed, nothing moves. Another row or J/K switches it; ✕, Esc or a click on empty page space closes it. The Review button is the full-width yellow one, no arrow. No Next case.
- iPad: landscape card 440 px; portrait boxes 3 × 2, Filter by on its own line, Source and Docs columns hidden, card min(520, width − 64); 44 px targets and 52 px rows on touch; no keyboard hint on touch.
- Admin > Redaction & retention: a **Cases page** card (Business Admin): amber after, red after, close a case without documents after N days, each with a one-line hint.
- Defect 2: the filter box draws one ring (the box: dark border, 2 px ring and the soft outer ring the form fields use); the input inside draws none. The same fix covers Support's search box (same style). Find (the command palette) has only one ring already; Review has no search box.

Kept exactly (addendum list): stage box filter and clear; approval tick boxes and BulkApproveBar; "Only cases with ‹code› · Show all cases" (it lists every case with that code, stopped and final ones too, as Find would); the empty, loading, load-error and refresh-error states and words; the ✕ that clears the filter; J/K/Esc and the hint. The case panel's items are unchanged; a test opens one case in each state and checks them.

## Decisions I took (please check)
1. **Exporting cases count in the Exported box** (the design has six boxes and no Exporting box; in its data an exporting case has the delivered colour). The box's hover says "Sent to its destination today, or being sent now." In the lists an exporting case is In progress (or Needs you when it was refused and you may resend).
2. **The words stay "Exported"** (the product's stage name today), not the design's "Delivered": "Exported today", the (i) says "Exported today". Changing the stage name is a vocabulary change across the app.
3. **"Today"** is the browser's calendar day, by the case's last change. Just after midnight the Exported today line is short until new cases are exported.
4. **Reports "With IT: N"** (owner 22:00) is not built here: Reports already shows the stopped count as "Stopped" (Overview) and "Stopped now" (Daily). Recommended: rename those to "With IT" in a small follow-up, if the owner wants the word.
5. The stopped box for people without IT rights is a count only (not a button), with the sentence "Processing stopped; IT works on these in Support."

## Tests changed (because the design changed what they checked)
- `exportRules.test.ts` (0036): it checked the Cases page counts an Exporting tile; now it checks that an exporting case counts in the Exported box.
- `cases0111.spec.ts`: Exported today is opened before its rows are counted; the cancelled case is no longer listed (final) and still says Cancelled when opened.
- `cases0108.spec.ts` (part 1): opens Exported today first; fixture times moved inside today; "sorted by Received, oldest first".
- `needsYou` no longer counts a stopped case for anyone (owner 22:00), so the top bar's Cases count drops by the stopped cases.

## Accessibility fixes found by the a11y suite (product, not tests)
- The Exported box's name now comes from its visible text (with a hidden " today"); an `aria-label` that did not contain the visible text failed WCAG 2.5.3.
- The Urgent tag is white on the danger colour (5.6:1); the soft tint was 4.41:1.
- iPad portrait (820): with Source and Docs hidden the row has its own grid, so the status chip stays inside its column.
- The UI-framework contract: colours come from tokens.css only, and `cf-link-button` was renamed (it matched the retired `link-button` family).

## Also changed
- `?code=` is followed while Cases is showing (a report link opened inside the app).
- Further tests changed because the design removed or collapsed what they checked (each says why in a `Task 0108` comment): `caseRows.spec.ts` (the row-button tests become case-card tests; stopped and rejected cases checked through the report link), `entitySwitch.spec.ts` (the role check is now which group a case lands in), `exports.spec.ts` (six boxes; groups opened), `errorStates.spec.ts`, `caseFields.spec.ts`, `reportTabs.spec.ts`, `support.spec.ts` (Continue manually from the card that Find opens), `tablet.spec.ts` (Approve by tap uses the card), `keyboard.spec.ts`, `reflow.spec.ts`, `screens.spec.ts`, `shortcuts.spec.ts` (the All switch is gone; the View button is the control outside the list).
- New: `e2e-a11y/cases0108b.spec.ts` (430 cases: counts, groups, Show 25 more; stage boxes; Stopped; tags and waiting colours; the pill; the sliding card; defect 2; one case in each state and each export state; iPad 1180 and 820, screenshots in `test-results/0108/`).

## Proof run (rule 9: targeted; rule 11: `-Dmaven.repo.local=C:\dev\lexavault\m2-queue-B`, nothing installed)
- Backend: `mvn -Pdev-build verify -pl securedoc-api -am` with a throwaway PostgreSQL 16 (`lv-0108-pg`, port 55408): application 420, infrastructure 218 (2 skipped), API 996 tests (domain green too), **1 failure**: `DataDictionaryPostgresTest` (the committed dictionary was out of date: 115 tables against 117). Fixed with `scripts\Update-LexaVault-DataDictionary.ps1`; the affected tests re-run green (`DataDictionaryPostgresTest`, `CasePage*`, `CaseView*`, `EmptyCaseExpiry*`, `PackageList*`, `PackageLifecycleDashboardRetentionTest`).
- Frontend (`LV_E2E_PORT_BASE=15275`), first run: auth 10, vocabulary 251 (after the 0036 check change), ui-framework 19 (after the two contract fixes), oidc-signin 214 of 237 (the 23 tests above), tablet 95 of 110 (3 tests × 5 iPad sizes), a11y 372 of 416 (22 tests × 2 themes: the 2 real findings above, the rest tests of removed things). After the fixes: each changed oidc spec file re-run green (caseRows 8, entitySwitch 12, exports + caseFields + errorStates 36, reportTabs + support 15), tablet Cases tests 15/15, a11y changed and related specs 328/328.

## Product names
No other product's name or behaviour in code.
