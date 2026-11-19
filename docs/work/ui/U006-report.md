# Report U006: Reports (findings 13, 14, 32)

Date: 2026-11-18
Branch: `feature/ui-polish`
Outcome: PARTLY DONE. The screen-only parts are done: plain words, the title, the chart and the Report Viewer line (items 4, 5 and 6). The entity filter, the "By entity" table and the review target (items 1, 2 and 3) need backend changes, and one of them needs a schema change. They are proposed below and wait for the owner's answers. Nothing in the backend was changed.

## What was done

1. **Title and words** (item 4), in `src/studio/reports/Reports.tsx`:
   - Title "How the work is going"; the line under it says "updated" (it said "generated").
   - Tiles: "Typical time to delivery" with "slowest 5%: …", "No review needed" with "…% needed a person", and "Open now" with "… stopped · oldest …".
   - Service levels: "No review needed" (was "Straight-through processing"), "Cases delivered without a person reviewing", "… of … cases missed the target", and the note "Targets are set in Admin › Redaction & retention › Reporting".
   - The same vocabulary in the Business Administrator sections:
     - Timing: "Typical …" and "slowest 5%" (were "Turnaround", "p95" and so on);
     - Pipeline: "Stopped" (was "Technical attention");
     - breakdown tables: "Delivery rate" and "Typical time" (were "Rate" and "Median");
     - Outcome filter: "Stopped" (was "Needs attention").
   - Durations read "45 min", "2.4 h", "4 h", "3.1 days" (were "45m", "2.4h", "4.0h", "3.1d").
2. **Chart** (item 5):
   - **Left axis** with values: steps of 1, 2 or 5 × 10ⁿ from 0, at most 4 gaps, and the top of the axis is the chart's scale. Dashed grid lines.
   - **Numbers on hover:** a tooltip with the date, received and delivered, and the hovered day is shaded.
   - **Dates at regular intervals:** at most 7, every n-th day from the first (30 days: every 5th). They were the first and last raw timestamps.
   - The note says "Cases per day" (was "Per period bucket").
   - **Colours:** delivered is now the Delivered stage green and received the New grey. Delivered used the yellow `--cf-mark`, which the spec keeps for "needs you".
   - **Screen readers:** the chart is hidden from them, and the same numbers are in a hidden table ("Cases received and delivered per day").
   - **Day labels:** a day is the database's day, sent as an instant. It is shown by rounding to the nearest UTC midnight, so the label is right whatever zone the browser is in, for any database zone within 12 hours of UTC.
3. **Report Viewer** (item 6): instead of the detailed sections, one line, "More detail is available to Business Administrators." (it said "…need operational report access"). Roles are unchanged. Headline, trend and service levels are shown with `REPORT_EXECUTIVE_VIEW`. Detail needs `REPORT_OPERATIONAL_VIEW` and Export CSV needs `REPORT_EXPORT`.
4. **New `src/studio/reports/reportRules.ts`:** the duration and percentage formats, axis steps, date intervals and day labels, kept apart from React so they run under `node --test`.

## Not done: waiting for the owner

### Items 1 and 2: entity filter and "By entity" table

**Why the backend is needed.** Reports cover one entity today. `OperationsQueryService` passes `principal.tenantId()`, and every query in `JpaOperationsReadAdapter.loadReports` has `tenant_id = :tenant`. The person's other entities are not looked at. The design names combined reports as phase 11 (`LOGIN_CUSTOMER_ENTITY_DESIGN.md` sections 8 and 12). Its rule: "covers only entities where the person holds Report Viewer". Showing an entity filter with nothing behind it would state something the server does not do, so it is not built yet.

**Proposed API change** (no schema change: `reporting_package_facts` already has `tenant_id`):

- `GET /api/v1/operations/reports` gets an optional `entity` parameter:
  - **left out:** the active entity, exactly as today (no change for anyone);
  - **`<tenantId>`:** that one entity. Refused with 403 unless the person holds the right role there.
  - **`ALL`:** every entity where the person holds the right role, and only those.
- The entities a person may use are worked out on the server from their memberships, through the existing seam (`OidcTenantMembershipResolver` and the library's entity admission). They are never taken from the request. If memberships cannot be resolved, the request is refused (fail closed). Nothing is quietly narrowed.
- The answer gets an `entities` list, one row per entity covered: `tenantId`, `name`, `cases`, `delivered`, `deliveryRate`, `medianDeliveryHours`, `straightThroughRate`. Numbers only, never cases. The total row is computed on the combined facts (`tenant_id in (:tenants)`); medians are not averaged.
- With `ALL`, the headline, trend and service levels cover the combined entities. Each entity's own targets apply to its own cases.
- The detailed sections (pipeline, backlog, retention, timing, intake, breakdowns) and Export CSV stay single-entity. Combining them is not asked for, and each entity's case fields differ.
- **Audit:** a combined report records which entities it covered.
- **Screen:** the filter shows only when the server lists more than one entity for the person. It shows "All entities" and each name; the "By entity" table shows under the headline with the total row.
- **Tests:**
  - backend: a person with Report Viewer in A only gets nothing of B, both with `entity=B` (403) and with `ALL` (A only);
  - the total equals the combined figures;
  - the Phase 1 environment (two entities) as the runtime proof.

### Item 3: review target

- The third bar still uses 100%. `reporting_tenant_settings` has `review_wait_target_minutes` but no percentage, so the setting comes with U007.
- **Proposed in U007:**
  - a new migration adding `review_wait_target_percent` (integer 0–100, not null, default 80) to `reporting_tenant_settings`;
  - `serviceLevel.reviewWaitTargetPercent` in the reports answer;
  - the field on the admin Report targets form.
- Then U006 changes the bar to read it. That is a two-line change, plus a test.
- **Seen on the local app:** with no reviews in the period, the bar shows a red "0% / target 100%" and "0 of 0 reviews waited longer". It is better to show "No reviews in this period" when nothing was eligible. This is proposed for the same follow-up; it was not changed now.

## Commands run and results

- `npm run build`: passed.
- `npm run test:standalone`: passed:
  - `test:auth` 8;
  - `test:vocabulary` 31 (5 new);
  - `test:ui-framework` 17;
  - `test:oidc-signin` 34 (4 new).
- Final run after the last change; each suite once.
- **Changed tests:**
  - `e2e-oidc/errorStates.spec.ts`: two lines expected the old title "How the desk is performing" and now expect "How the work is going". Nothing else changed.
  - No test failed during the work.
- **Local app:**
  - Started with `.\Start-LexaVault-Local.ps1 -ScannerMode SIMULATOR -AiModel qwen3.5:9b`. As in U004 and U005, the script stopped with "Frontend did not become ready on localhost:5173", but the frontend (200) and the backend health check (200) answered.
  - Reports checked as **Business Administrator** and **Report Viewer** (local test roles), light and dark, 1440 and 1024 px, in Edge (Playwright), with the tooltip open.
  - Each run checked: 30 days of local data, axis 0–3, the tooltip shown, the Report Viewer line only for the Report Viewer, Export CSV only for the Business Administrator, no sideways scroll, and no page errors.
  - **Fixed after looking at the screens:** the hidden table made the page taller than its content, leaving blank space at the bottom in dark mode. A table never shrinks to the 1 px hidden box. It is now hidden through a wrapper, and the Report Viewer page is exactly the window height.
  - Stopped with `.\Stop-LexaVault-Local.ps1`.
  - The screenshots are in the session's scratch folder and are not committed.
- **Backend:** not changed, so not rebuilt (440 tests at U004).
- **Rules:**
  - no raw hex outside `tokens.css` (searched `src/studio/reports/`);
  - no new breakpoint and no new dependency;
  - `uiFrameworkContract.test.ts` and the legacy `ReportsPage` are unchanged.

## Tests: counts per module, and any failures with their output

- **New `src/studio/reports/reportRules.test.ts` (node, 5):**
  - durations: minutes, hours and days, no ".0", and never negative or NaN;
  - percentages sent as 0–1 or 0–100;
  - axis steps: from 0, regular, at most 4 gaps, with the top at or above the largest bar;
  - dates at regular intervals, at most 7;
  - day labels for a database in UTC, UTC−5 and UTC+5:30, and an unreadable value.
- **New `e2e-oidc/reports.spec.ts` (Playwright, mocked API, 4):**
  - the headline and service-level words, with none of median, p95, straight-through, "open backlog", desk or "needs attention" left;
  - the chart: axis 0, 10, 20, 30; dates Oct 19, 24, 29, Nov 3, 8, 13 at days 0, 5, 10 and so on; the tooltip on hover with "Oct 31 · 23 received · 19 delivered", gone when the mouse leaves; the hidden table with 31 rows;
  - Report Viewer: headline, chart and service levels, the one line, no Pipeline, no Backlog age, no Export CSV;
  - Business Administrator: Pipeline (with "Stopped") and Export CSV, and no Report Viewer line.
- Failures: none.

## Also in this commit

- `docs/work/ui/README.md`: the owner's "Keep it lean" section (2026-11-18), as asked.
- `docs/work/ui/U013-task.md` is **not** in this commit. It is a new task file and was not mentioned. Say if it should go in.

## Questions for the owner

1. **Combined reports (items 1 and 2):**
   - Build the `entity` parameter as proposed above, now, as part of this UI work? It is phase 11 of the agreed plan, and the plan says backend work pauses during the UI tasks.
   - Which roles let a person see an entity in a combined report? The design says Report Viewer; is Business Administrator included?
2. **Review target (item 3):** approve the migration adding `review_wait_target_percent` (default 80) to `reporting_tenant_settings`, in U007? U006 then reads it.
3. **Empty service levels:** show "No reviews in this period" instead of a red 0% when nothing was eligible, and the same for deliveries?

## Owner's answers (2026-11-18)

1. **Combined reports (items 1 and 2):** do not build them now. The `entity` parameter is backend work (phase 11) and waits for the backend work after the UI series; the proposal above is the starting point. Keep the screen without the entity filter and the "By entity" table until then.
   - **Roles:** base it on the capability, not on a role name. An entity is covered when the person holds `REPORT_EXECUTIVE_VIEW` there, so both Report Viewer and Business Administrator are included. Fail closed as proposed.
2. **Review target (item 3):** approved. In U007, add the migration for `review_wait_target_percent` (integer 0–100, not null, default 80) on `reporting_tenant_settings`, the answer field and the admin form field. Then make U006's two-line change to read it, with its test, in U007's commit.
3. **Empty service levels:** yes. When nothing was eligible, show "No reviews in this period" (and "No deliveries in this period") in a neutral colour instead of a red 0%. Do it now, with a test.
4. **Commit these with this follow-up:** `docs/work/ui/U013-task.md` (written by the design chat) and `docs/work/install-onboarding-approach.md` (owner-approved install approach, also written by the design chat).
5. **Start script:** "Frontend did not become ready on localhost:5173" has appeared since U004 while the frontend answers. Note it for the backend work after the UI series; do not fix it now.

## What was done with the answers (2026-11-18)

1. **Combined reports:** not built. The screen has no entity filter and no "By entity" table. For the backend work after the UI series, the proposal above stands, with one change: an entity is covered when the person holds `REPORT_EXECUTIVE_VIEW` there (Report Viewer and Business Administrator), not by role name.
2. **Review target:** carried into U007: the migration for `review_wait_target_percent`, the answer field, the admin form field, and then the change that makes the third bar read it, with its test, all in U007's commit. Until then the bar still uses 100%.
3. **Empty service levels: done.**
   - When nothing was eligible, a bar shows "No deliveries in this period" or "No reviews in this period" in the neutral muted colour. It shows the target, no fill and no percentage, and no "0 of 0" line.
   - Which bar uses which count:
     - "Delivered within …" is empty when `deliveryEligible` is 0;
     - "No review needed" is empty when no case was delivered in the period (`executive.delivered` is 0). That bar's percentage is measured over deliveries, so it uses the delivery wording;
     - "Review picked up within …" is empty when `reviewEligible` is 0.
   - A missing or unreadable count counts as nothing eligible.
   - The empty bar is not a meter for screen readers. It reads as "<label>: No reviews in this period. Target <n>%."
4. **Committed:** `docs/work/ui/U013-task.md` and `docs/work/install-onboarding-approach.md`, unchanged.
5. **Start script:** "Frontend did not become ready on localhost:5173" is noted for the backend work after the UI series. It appeared again in this run while the frontend answered (200). It was not fixed.

**Commands run and results:**
- `npm run build`: passed.
- `npm run test:standalone`: passed:
  - `test:auth` 8;
  - `test:vocabulary` 31;
  - `test:ui-framework` 17;
  - `test:oidc-signin` 36.
- **New tests**, 2 added to `e2e-oidc/reports.spec.ts`:
  - a period with nothing to measure: "No deliveries in this period" twice and "No reviews in this period" once, the target still shown, no meter, no "0%" and no "0 of 0", and no bar in the danger colour;
  - a period with deliveries and reviews: the three measured bars and no empty wording.
- **Local app:**
  - Reports checked as Business Administrator and Report Viewer, light and dark, 1440 and 1024 px, in Edge (Playwright).
  - The local data has deliveries but no reviews. So the first two bars showed measured values, and the third showed "No reviews in this period" in the muted colour.
  - No page errors. The app was stopped afterwards.
