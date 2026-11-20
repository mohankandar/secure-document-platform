# Report U024: What LexaVault did for you

Date: 2026-11-20
Branch: `feature/ui-time-saved` (from `feature/lexavault-r1b-tenant-identity` after the step 0 merge)
Outcome: DONE. Nothing pushed or merged.

## Step 0

### Merge
- Committed the owner's answers in `docs/work/0020-report.md` on `feature/time-saved`: `2162353`. `docs/work/0021-task.md` left untracked and untouched.
- Merged `feature/time-saved` into `feature/lexavault-r1b-tenant-identity`, no fast-forward: **`77291b4`**.
- At the merge: **617 backend** (domain 51, application 229, scan bridge 47, infrastructure 15, API 275) and **320 frontend** (auth 10, vocabulary 125, ui-framework 17, oidc-signin 98, tablet 70). All pass.
- Created `feature/ui-time-saved` from it.

### Step 4: "No review needed" leaves out cases finished by hand
- **One shared definition:** the new `ReportingCaseRules` (infrastructure) holds two SQL conditions.
  - `FINISHED_BY_HAND`: a successful `BUSINESS_MANUAL_FALLBACK_SUBMITTED` record for the case.
  - `NO_PERSON`: not reviewed, and not finished by hand.
- **Who uses it:**
  - `JpaOperationsReadAdapter`: `casesNoPerson` uses `NO_PERSON`. Reports' "No review needed" is now taken from `casesNoPerson`, no longer worked out as "delivered minus reviewed". That covers the headline tile, its target bar, Service levels, the CSV and `usage.straightThroughPackages`.
  - `JpaComplianceReportReadAdapter`: "Finished by hand" uses `FINISHED_BY_HAND` (it was a second copy before).
- **Compliance report:** its "No review needed" row reads `usage.straightThroughPackages`, so it follows. "Checked by a reviewer" and "Finished by hand" are unchanged.
- **Proof that it always equals `casesNoPerson`:**
  - By construction, Reports takes the number straight from `casesNoPerson`.
  - `ReportingCaseRulesTest` (+3) checks that both queries use the shared rule, and only one copy of it.
  - On real Postgres, the compliance script's new check passes with an unreviewed case finished by hand in the period: `noPerson=1 straight=1 rate=25.0 target=25.0 unreviewedByHand=1`.
- **Found while doing it:** a text block drops the space before its closing quotes. The first version produced the SQL `…andexists (…)`. The new test caught it before any run, and both queries now join with explicit spaces.

**Numbers that change** (compliance script `Invoke-ComplianceReportCheck.ps1`, hand-made period with 4 delivered cases):

| Number | Before | Now |
|---|---|---|
| Reports `usage.straightThroughPackages` ("No review needed") | 2 | **1** |
| Reports `executive.straightThroughRate` | 50.0 | **25.0** |
| Reports `serviceLevel.straightThroughActualPercent` (the target bar) | 50.0 | **25.0** |
| Compliance PDF row "No review needed" | 2, 50% | **1, 25%** |
| "Checked by a reviewer" / "Finished by hand" / `casesNoPerson` | 2 / 2 / 1 | unchanged |

The 0020 check "handled without a person = No review needed minus the unreviewed cases finished by hand" became "No review needed = handled without a person". The script's README says so. On live data, "No review needed" drops by the number of unreviewed cases that were finished by hand in the period.

## What was built

### Reports: "What LexaVault did for you" (`Reports.tsx`, `reports.css`, new `timeSavedRules.ts`)
- **Where:** a white section under the title row, above the headline tiles. `h2` 16 px semibold, four tiles in an `auto-fit, minmax(220px, 1fr)` grid.
- **The tiles:**

  | Tile | Big number | Grey mono line | Border |
  |---|---|---|---|
  | Handled without a person | `percentNoPerson`%, or "—" | "348 of 512 cases" | 4 px bottom border in `--cf-ok` |
  | Pages checked | `pagesChecked` | "in 1,873 delivered documents" | |
  | Sensitive items redacted | `itemsRedacted` | the short type names, or "—" | |
  | Review time saved (estimate) | "≈ N h", "≈ N min" or "0 min" | the link button "How is this worked out?" | 2 px `--cf-ink` |

- **Short type names:** one map, `SHORT_TYPE_NAMES`, keyed by the vocabulary keys: SSN, date of birth, card, credit score. A test proves every vocabulary type has one. An unknown name is shown as sent.
- **Time words:** `TimeSavedRule.timeWords`, ported once (`timeWords`). The tile and the panel use the same words.
- **The panel:**
  - `role="dialog"`, labelled by its title. Right-aligned under the tiles, max 460 px.
  - A 3-row table with mono, right-aligned numbers. The result row has a top border: "= 9,690 minutes, rounded down" next to the tile's words. The pre-rounding figure is worked out in half minutes, so 121 × 2.5 reads "302.5".
  - The grey note says "deliberately low" only when the setting is 1, with "1 minute" in the singular.
  - A Close button.
- **Focus and keys:** opening moves focus to the panel. Esc or Close closes it and returns focus to the link. The link has `aria-expanded` and `aria-controls`. A refresh while the panel is open does not move focus.
- **Who sees it:** people with `REPORT_EXECUTIVE_VIEW` (Business Administrator and Report Viewer). Other roles have no Reports page at all.
- **Data and states:** it uses the page's period and filters, and its loading state. An answer without a readable `timeSaved` block fails only this card, with its own error, and is not guessed. With no delivered cases it shows 0 and "—", and the panel still opens.

### Admin › Redaction & retention › Report targets (`PolicyArea.tsx`, `admin.css`, `shared.tsx`)
- **Where:** under the three targets, after a top border, before "Break reports down by".
- **Heading:** "Time saved estimate" with a yellow "New" pill (`--cf-mark` / `--cf-mark-ink`). The pill is controlled by one constant, `TIME_SAVED_SETTING_IS_NEW` in `timeSavedRules.ts`.
- **The field:** one `<label>` around "A person takes [ 1 ] minutes to check one page by hand". The input is a number field, mono, 80 × 40 px, min 0.5, max 30, step 0.5, `inputmode="decimal"`. The grey note uses the task's words.
- **Validation:** outside 0.5–30, not a step of 0.5, or empty shows "Enter a number from 0.5 to 30, in steps of 0.5." under the field (`aria-invalid`, `aria-describedby`), and Save stays disabled. `SettingsCard` gained an optional `invalid` check for this. The save also refuses the value, as a second guard.
- **Save and Reset:** the card's existing buttons. `minutesPerPage` is sent in the existing PUT.
- **Found by a test:** the label read "A person takesminutes…" with no spaces around the field, which would run together for a screen reader. Fixed.

## Tests
- **`timeSavedRules.test.ts` (8, new, in `test:vocabulary`):**
  - the tile words;
  - null percent and no types;
  - minutes and hours;
  - the short names and their full coverage;
  - the panel numbers, including 302.5 and no binary-rounding noise;
  - "deliberately low" at 1 and not at 0.5 / 1.5 / 2.5 / 30;
  - the minutes validation;
  - refusing an unreadable block.
- **`reports.spec.ts` (+12):**
  - the four tiles' words, their position above the headline, the borders, and 32 px;
  - no delivered cases;
  - under an hour;
  - the panel's numbers, mono and right-aligned, "deliberately low", width and placement, focus, Esc and Close;
  - 2.5 minutes, without "deliberately low";
  - it follows the Source filter;
  - a missing block fails only this card;
  - the Report Viewer sees it;
  - Processor, Supervisor, IT Administrator and Platform Operations do not (no Reports link, `/reports` redirects, no report request).
- **`adminBusiness.spec.ts` (+2):**
  - the heading, the pill, one label, the attributes, 80 × 40, mono, the note, and the field's place in the card; 2.5 is saved in the PUT;
  - 0.4, 30.5, 1.25, 0 and empty are marked and block Save; 30 is fine; Reset brings back 1.
- **`tablet.spec.ts` (+2 × 5 sizes, WebKit):**
  - Reports: the tiles wrap inside the section, no sideways scroll, the link and Close are at least 44 px, the panel opens and closes by tap, and focus returns to the link;
  - Admin: the minutes field works by touch, and Reset works.
- **Fixtures:** the existing Reports mocks (reports, errorStates, complianceReport, tablet) now include `timeSaved`, and the admin mocks include `minutesPerPage: 1`.
- **Backend:** `ReportingCaseRulesTest` (+3).

## Commands run and results

| Command | Result |
|---|---|
| `git commit` (0020 answers), `git merge --no-ff feature/time-saved` | `2162353`, `77291b4` |
| `Build-LexaVault-All.ps1` at the merge | PASSED, 617 backend |
| `npm run test:standalone` at the merge | 320 passed (see the note below) |
| `mvn -o clean install` after step 4 | 620 passed |
| `Invoke-ComplianceReportCheck.ps1` after step 4 | **116 checks, 0 failed**; throwaway container removed |
| `Invoke-PolicyEstimateCheck.ps1 -KeepRunning` (for the real run) | 27 checks, 0 failed |
| Real run (see Proof), then `Stop-PolicyEstimateEnvironment.ps1` | 7/7 steps; removed `lexavault-0019-pg` only |
| `Build-LexaVault-All.ps1` (final) | **PASSED**: 620 backend, TypeScript, UI framework contract, Vite build |
| `npm run test:standalone` (final) | **352 passed** |

**Failures on the way, all fixed:**
- **First baseline frontend run:** "port 15175 already used". I had started two runs at once by mistake. The first run finished cleanly with 320 passed.
- **`ReportingCaseRulesTest`, first run:** 2 failures, the `andexists` bug above. That was the code, not the test.
- **The first U024 admin tests:** they looked the field up by a name without its value, but a field inside its label gets the value in its name ("A person takes 1 minutes…"). The tests now match the name by pattern.
- **One U024 admin test in the first full run:** it found the missing spaces in the label. That was the code; the component was fixed.

## Test counts
- **Backend: 620**, all pass. Domain 51, application 229, scan bridge 47, infrastructure **18 (+3)**, API 275.
- **Frontend: 352**, all pass. auth 10, vocabulary **133 (+8)**, ui-framework 17, oidc-signin **112 (+14)**, tablet **80 (+10)**.

## Rules checked
- No raw hex outside `tokens.css`.
- No new width breakpoint. The one new media query is `(pointer: coarse)`, for the 44 px tap height of the link, the same pattern as `studio.css` and `compliance.css`.
- No dependency change. `package.json` only adds the new test file to `test:vocabulary`.
- The UI framework contract test passes and is unchanged.
- No schema change.
- No shared container was touched. The tests used only the throwaway containers `lexavault-0017-pg` and `lexavault-0019-pg`, both removed.

## Proof (`docs/work/ui/compare/U024/`, not committed)

**Reports, panel open:**
- `Reports-section-1440-vs-canvas.png`: build next to the canvas.
- `Reports-section-panel-1440-{light,dark}.png` and `Reports-page-panel-1440-{light,dark}.png`.
- The same at 1024: `Reports-section-panel-1024-{light,dark}.png` and `Reports-page-panel-1024-{light,dark}.png`.

**Report targets card:**
- `ReportTargets-1440-vs-canvas.png`: build next to the canvas.
- `ReportTargets-{1440,1024}-{light,dark}.png`.
- The error state: `ReportTargets-error-{1440,1024}-{light,dark}.png`.

**iPad, panel opened by tap:**
- portrait: `iPad-Reports-panel-ipad-768x1024-{light,dark}.png`;
- landscape: `iPad-Reports-panel-ipad-1024x768-{light,dark}.png`.

**Canvas renders:** `canvas-Reports-section-1440.png` and `canvas-ReportTargets-1440.png`.

**Real run** against the 0019 throwaway backend (21 real cases, UI on 15180, as Business Administrator):
- Pictures:
  - `RealRun-1-targets-2.5-{before-save,saved}.png`: set 2.5 and saved (PUT 200, body `minutesPerPage: 2.5`);
  - `RealRun-2-reports-2.5-{panel,page}.png`: Reports with the new estimate;
  - `RealRun-3-targets-back-to-1-*.png`: set back to 1 (PUT 200).
- What Reports showed at 2.5:
  - the server sent `minutesPerPage 2.5`, `pagesNoPerson 12`, `minutesSaved 30`;
  - tiles: "100%, 12 of 12 cases", "12, in 12 delivered documents", "0, —", "≈ 30 min";
  - panel: 12 × 2.5, "= 30 minutes, rounded down", "≈ 30 min", without "deliberately low".
- **Audit records** (`RealRun-audit-records.txt`):
  - `… reviewWaitTargetPercent=80; minutesPerPage=2.5; previous=[… reviewWaitTargetPercent=80; minutesPerPage=1.0]`
  - `… minutesPerPage=1.0; previous=[… minutesPerPage=2.5]`
- The proof scripts are kept beside the pictures in `proof-script/`.

**Side-by-side differences, all deliberate:**
- The canvas shows 68%; the build shows 67%. That is the canvas's sample data; rounding down stays (owner's answer 4).
- The panel's result reads "≈ 161 h"; the canvas shows "161 h". The task says the result uses the same words as the tile.
- The card's other parts (title, the three target sentences, Save changes) are the existing card, unchanged.
- On a portrait iPad (768 px) the four tiles wrap 3 + 1; at 820 px and above they sit in one row or 2 + 2.

## Commits (on `feature/ui-time-saved`)
- `b4ffd7d` fix: U024 step 0, "No review needed" leaves out cases finished by hand
- `540d47f` feat: U024 What LexaVault did for you, and minutes per page in Report targets
- the next commit: this report

## Questions for the owner
1. **Portrait iPad wraps the four tiles 3 + 1.** It reads well and nothing scrolls sideways. Should it be 2 + 2 instead, as the headline was made to fill its rows in U021? That would need one portrait-tablet rule like the headline's.
2. **Service levels' grey line under "No review needed"** still reads "Cases delivered without a person reviewing". Since step 4 it also leaves out cases finished by hand. Should it say "…without a person reviewing or finishing by hand", or stay short?
3. **The tile says "Handled without a person" and the headline says "No review needed".** Since step 4 they are always the same number, shown twice on one page (the tile as a percent of delivered cases, the headline as a rate). Keep both, or drop one later?

## Owner's answers (2026-11-20, recorded by the design chat)

1. Portrait iPad: the four tiles sit 2 + 2, with one portrait-tablet rule like the headline's (U026 step 0).
2. Service levels' grey line under "No review needed" becomes "Cases delivered with no person reviewing or finishing them" (U026 step 0).
3. Keep both "Handled without a person" and the headline's "No review needed".
