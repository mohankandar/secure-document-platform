# 0108 report: Cases at volume (IN PROGRESS)

Status on 2026-11-27, 21:50 stop: **part 1 of 7 built (the View menu)**. Not finished; kept on `feature/0108-cases-at-volume` (pushed, no PR, not merged). Plan: `docs/work/0108-plan.md`.

The branch now starts from the merged integration head (0111 merged as PR #55, `ab07b75e`), merged in with `git merge` (no rebase).

## Done tonight: View menu, saved on the server (plan backend 1, frontend 2 in part)

- Backend: table `user_case_view` (migration **089**; 088 is queue A's 0110), one row per person and entity: sort, order, columns, density. `GET/PUT /api/v1/me/case-view` (capability `DASHBOARD_ACCESS`; always the signed-in person in the entity they are in; `no-store`). The answer carries what the entity offers: sorts Received, Waiting longest, Priority and the **primary** case field; columns = displayable case fields in the admin's order, then Received, Uploaded, Source.
- Fail closed on save: anything not offered (a non-primary field sort, an unknown sort, order or density, a column that is not displayable, a repeated or empty column) is refused whole with LV-CFG-001; nothing is stored. A read never fails: what the admin has switched off since is left out, an unknown sort reads as Received.
- Frontend: a **View** button next to the filter (Sort by, order switch with the design's words, Columns, Density). Each change shows at once and is saved; a refused save goes back and says "Your view couldn't be saved. Try again." Esc closes it and returns focus to the button. The chosen sort applies inside every group (Needs you still first in a stage filter); the Needs-you heading says "sorted by …". Compact puts the second line beside the first (nothing is left out); 44/52 px targets on touch. Columns choose which case fields show on the second line, Source (its slot stays, so the columns stay in line) and Received ("received 5 h ago" on the second line; a column of its own widened the row and pushed the status chips out at 1024 px, caught by `exports.spec.ts`).
- If the view can't be loaded, the page uses the default view (Received, oldest first) and still works.

### Changed from today (both from the design, not from me)
- The default order inside Needs you was Priority then oldest; it is now **Received, oldest first** (task item 3: "Received (default, oldest first)"). Priority is one choice in View.
- In progress and Delivered were newest-changed first; they now follow the chosen sort too (task item 4: "Sorting applies inside each group").

### Kept exactly (STOP RULE)
Nothing shown today is removed by this part: the rows still show next-step text, the reason, the destination and the action button; the Stopped group, the summary line, the All / Needs you switch and the 0111 Show more are as before. Those change in the later parts.

## Not done yet (next session, in plan order)
1. Waiting-time colours per entity (amber 4 h / red 8 h) and 0111's empty-case expiry days as a Business Admin setting (migration 090; design chat said yes, 2026-11-27 18:22).
2. `receivedAt` / `uploadedAt` / `source` on the dashboard list (the Uploaded column is offered by the server but not shown until the list carries it; Received uses the case's creation time today).
3. Top of the page (six slim stage boxes, the stage sentence, no summary line, no All / Needs you switch), Filter by.
4. Groups: In progress and Delivered today collapsed; older delivered cases off the dashboard.
5. Rows without heading row, next-step button or next-step text; Urgent / Send failed tags.
6. Sliding case card (CaseDrawer moves only), the new-cases pill.
7. iPad landscape/portrait; the 400+ case test; one case in each state with every panel item; screenshots.

## Open question (STOP RULE, asked 2026-11-27 18:20, still open)
A stopped or cancelled case that this person cannot move forward (today's "Stopped" group) is not in the design's groups. Show it under In progress, as a collapsed "Stopped" line, or not at all? Recommended: a collapsed "Stopped" line. Until answered, the Stopped group stays as today.

Decided (design chat, 21:15): the row **keeps** its reason ("why it stopped") and destination text; only the next-step text and button go.

## Proof run tonight (rule 9: targeted; rule 11: `-Dmaven.repo.local=C:\dev\lexavault\m2-queue-B`, nothing installed)
- `mvn -Pdev-build verify -pl securedoc-api -am` with a throwaway PostgreSQL 16 container (`lv-0108-pg`, port 55408; removed): domain 91, application 416, infrastructure 217 (2 skipped), API 992 (5 skipped); 0 failures. New: `CaseViewServiceTest` (6), `CaseViewStorePostgresTest` (1, migration 089 included), `CaseViewControllerTest` (1).
- Frontend, `LV_E2E_PORT_BASE=15275`: type check clean. `test:standalone` first run: auth, vocabulary, ui-framework passed; oidc-signin 235 passed, **2 failed** (`exports.spec.ts` chips at 1024 px, caused by my Received column; fixed as above). After the fix: `exports.spec.ts` + `caseRows.spec.ts` 34 passed; `cases0108.spec.ts` (new, 3 tests × light/dark) + `cases0111.spec.ts` 12 passed. Re-run: oidc-signin **237 passed**, tablet **110 passed**, a11y **stopped at the 21:50 stop after 250 of 392 passed, 0 failed** (the rest to run next session).
- Left for later parts / the milestone: the 400+ case test, iPad sizes, screenshots, the per-state panel test.

## Product names
No other product's name or behaviour in code.
