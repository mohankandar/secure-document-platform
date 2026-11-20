# Report 0020: Safe policy follow-ups, and the time saved numbers (backend)

Date: 2026-11-20
Branch: `feature/time-saved` (from `feature/lexavault-r1b-tenant-identity` after the step 0 merge)
Outcome: DONE. One definition differs from the task's words on purpose: "Sensitive items redacted" counts `CONFIRMED` **or `REDACTED`** findings (question 1).

## What was done

### Step 0
- Committed the owner's answers in `docs/work/ui/U023-report.md` on `feature/ui-safe-policy`: `078728d`.
- Merged `feature/ui-safe-policy` into `feature/lexavault-r1b-tenant-identity`, no fast-forward: **`996e357`**.
- Build and tests at the merge: **597 backend** (domain 51, application 214, scan bridge 47, infrastructure 14, API 271) and **310 frontend** (auth 10, vocabulary 121, ui-framework 17, oidc-signin 92, tablet 70). All pass.
- Created `feature/time-saved` from it. Nothing pushed.

### A1. Save waits for an estimate that is on its way
- The card now has one estimate runner (`estimateRunner` in `policyRules.ts`); the preview only shows its answers.
- If the mode or threshold changed and no answer is in yet, Save does the following:
  - starts the request at once if it is still in its 300 ms delay, or waits for the one already running;
  - meanwhile shows **"Working out…"** (`aria-disabled`, `aria-busy`), locks the card (one disabled `fieldset` around all controls, plus Reset), and ignores a second press.
- When the answer comes, the normal rule applies: the confirm step if fewer findings would go to a person, otherwise save.
- A failed answer, or "not enough data", saves without the confirm step, as before. A failed answer is not asked again (no retry loop).
- Answers are kept per key: the saved values plus the edited values. If the values change or the card closes while Save waits, the result is "dropped" and **nothing is saved**. An answer for other values can never answer a Save.

### A2. Confirm wording for Custom
When the old or the new policy is Custom:
- the buttons read **"Keep your current settings"** and **"Save these settings"**;
- the sentence reads "With **these settings**, about …".

The audit line keeps the names ("… as Custom → Light", "Balanced → Custom", "Custom → Custom").

### A3. "Every finding": every place that names this mode
| Place | Before | Now |
|---|---|---|
| Fine-tune mode button (`PolicyArea.tsx`, via `MODE_WORDS` in `policyRules.ts`) | Every case | **Every finding** |
| Hint under the slider, that mode | "Findings below this go to a reviewer. Every case is reviewed regardless." | "Every finding goes to a person. Cases with nothing found are delivered without a check." (Strict's words) |
| Hint under the slider, other mode | "Findings below this go to a reviewer." | unchanged |
| Compliance report section 1, "Sent to a person", and the section 1 change lines ("Sent to a person: … → …") (`PolicyHistory.reviewModeWords`) | Every case | **Every finding** |

Checked and not changed:
- **Audit trail sentences** (`auditRules.ts`, `AuditSentences.java`): they never name the mode. The policy line shows only the auto-redact level.
- **`SensitiveTypeNames`**: holds type names only.
- **Reports (U009) and the compliance dialog (`complianceRules.ts`)**: they never name the mode.
- **The older admin page** (`BusinessAdminPage.tsx`): it already says "Review every finding".
- **"Every case field needs a name"** (`FieldsArea.tsx`): a different meaning.

### A4. The confirm step against real data
- **Environment:** the 0019 throwaway environment (`Invoke-PolicyEstimateCheck.ps1`, new switch `-KeepRunning`). 27/27 checks passed. That left 21 real cases with the saved policy `UNCERTAIN_ONLY` 0.99 (Custom).
- **UI:** run on 15180 in LOCAL mode against that backend. The new browser script `Invoke-ConfirmStepRealData.mjs` acts as the Business Administrator and chose **Light**. All 8 steps passed:
  - preview: "If you save Light, based on the last 30 days in Local Demo Tenant (21 cases)". Cases 9 → 0 (▼ 9 fewer), findings 27 → 0 (▼ 27 fewer). These are the server's numbers.
  - confirm step: "With **these settings**, about **27** fewer findings and **9** fewer cases in the last 30 days would have had a person check them."
  - buttons "Keep your current settings" / "Save these settings"; audit line "… as Custom → Light."
  - **Keep:** no PUT sent, and the card went back to the saved policy.
  - **Save:** one PUT (200), and Light is now "In use".
- **The audit record of the save:** `SUCCESS`, actor `…0099`, detail:
  `Sensitive-data policy changed: enabledCount=4, threshold=0.70, reviewMode=UNCERTAIN_ONLY, maskSsnLastFour=true, enabledTypes=CREDIT_CARD_NUMBER,CREDIT_SCORE,DATE_OF_BIRTH,SSN, preset=LIGHT, previous=[enabledCount=4, threshold=0.99, reviewMode=UNCERTAIN_ONLY, maskSsnLastFour=true, enabledTypes=CREDIT_CARD_NUMBER,CREDIT_SCORE,DATE_OF_BIRTH,SSN, preset=CUSTOM]`
- **Pictures** (not committed): `docs/work/ui/compare/U023/realdata-1-saved-custom.png` … `realdata-5-saved-light.png`. These are full-page captures, so the fixed top bar and dialog scrim are drawn partway down the picture; the screen itself is normal.
- **First try:** the save was refused with 403 "Invalid CORS request", because the test port's origin is not in the backend's allowed list. The test-only Vite config now drops the Origin header on the proxy. The product is unchanged.
- **Cleanup:** `Stop-PolicyEstimateEnvironment.ps1` stopped the backend and mock Ollama and removed `lexavault-0019-pg` only. No shared container or volume was touched.

### A5. Tests
- `policyRules.test.ts` (+4 net), the runner on hand-driven timers and answers:
  - one request after the delay, and only the latest answer is heard;
  - Save inside the delay asks at once;
  - Save while a slow request runs waits for it, without asking twice, and a failed answer is not retried;
  - an answer for other values never answers a Save, and closing the card drops what is waiting;
  - also the Custom words on both sides, and `MODE_WORDS`. The unused `latestOnly` was replaced by the runner, so its test went too.
- `adminSafePolicy.spec.ts` (+6):
  - Save inside the 300 ms delay;
  - a slow estimate: "Working out…", every control disabled, a second press does nothing, then the confirm step;
  - a failed estimate, and a slow "not enough data" one, both save with no confirm step;
  - Custom → Light and Custom → Custom wording;
  - Balanced → Custom wording;
  - the "Every finding" button and hint.

### B1. Definitions, and how they match Reports
All are per entity, for Reports' own period and source/outcome filters. They come from `reporting_package_facts`, the same table and the same `first_delivered_at` set as Reports' "Delivered". The SQL is in `JpaOperationsReadAdapter.TIME_SAVED_CASES_SQL` / `TIME_SAVED_ITEMS_SQL`.

| Field | Definition |
|---|---|
| `casesDelivered` | facts with `first_delivered_at` in [from, to): **identical to Reports' `executive.delivered`** |
| `casesNoPerson` | of those, `reviewed_flag = false` **and** no `BUSINESS_MANUAL_FALLBACK_SUBMITTED` SUCCESS record for the case (the compliance report's "Finished by hand" rule) |
| `percentNoPerson` | `casesNoPerson × 100 / casesDelivered`, rounded down; `null` with no delivered cases |
| `pagesChecked`, `documentsChecked` | sums of `page_count` and `document_count` over the delivered cases |
| `itemsRedacted` | findings in the delivered cases with status `CONFIRMED` **or `REDACTED`**, automatic or by a person (question 1) |
| `itemTypes` | the types of those findings, in vocabulary names and vocabulary order ("Social Security Number", …) |
| `pagesNoPerson` | sum of `page_count` over the `casesNoPerson` cases; reviewed cases count as zero |
| `minutesPerPage` | the entity's setting now |
| `minutesSaved` | `pagesNoPerson × minutesPerPage`, rounded down (`TimeSavedRule.minutesSaved`, BigDecimal FLOOR) |

**Agreement with Reports:**
- `casesDelivered` always equals "Delivered".
- `casesNoPerson` equals Reports' "No review needed" **minus the unreviewed cases finished by hand**. That is the task's definition. Reports' "No review needed" counts such a case, and the new tile does not.
- So the two agree except for those cases. They are exactly the cases the task asks to leave out, so I did not treat this as a disagreement. The compliance check proves the relation on data that has one such case (C6). If they should be equal instead, see question 2.
- `pagesChecked` is over **delivered** cases, as the task says. Reports' existing "Pages" tile is over cases **received** in the period, so the two can differ (question 3).

**Combined (all entities) view:** Reports has none. `GET /operations/reports` always answers for the signed-in entity, so that part of B3 does not apply.

### B2. Setting: minutes per page
- **Migration `041-reporting-minutes-per-page.xml`:** `reporting_tenant_settings.minutes_per_page NUMERIC(4,1) NOT NULL DEFAULT 1.0`, with a check constraint `0.5–30`. It is registered in the master changelog. Migration 037 is still not registered.
- **Validation** (`TimeSavedRule.allowedMinutesPerPage`): 0.5 to 30 in steps of 0.5. Anything else (0.4, 30.5, 1.25, 0, NaN) gives 400.
- **Saved with the report targets.** A caller that leaves it out (`null`) keeps the stored value. The current admin page doesn't send it yet; U024 adds the field.
- **Audit record.** `REPORTING_CONFIGURATION_CHANGED` keeps its six keys and their order, then adds `minutesPerPage` and `previous=[…]` with the same keys:
  `Reporting configuration changed: dimensions=0; deliveryTargetMinutes=240; deliveryTargetPercent=95.0; straightThroughTargetPercent=90.0; reviewWaitTargetMinutes=60; reviewWaitTargetPercent=80; minutesPerPage=2.5; previous=[dimensions=0; …; minutesPerPage=0.5]`.
  It holds numbers only (checked in a unit test and in the script), and the action is already on the appendix allow list.
- **No record when nothing changed** (task 0017's rule). This now applies to every report-targets save, not only to minutes per page. Before, an unchanged save wrote a record.

### B3. API
- `GET /api/v1/operations/reports` has a new `timeSaved` block, under the same capability (`REPORT_EXECUTIVE_VIEW`: Business Administrator and Report Viewer).
- **Example** (the compliance check's hand-made data, Report Viewer):

  ```json
  "timeSaved": { "casesDelivered": 4, "casesNoPerson": 1, "percentNoPerson": 25, "pagesChecked": 201, "documentsChecked": 7,
    "itemsRedacted": 4, "itemTypes": ["Social Security Number", "Credit card number"], "pagesNoPerson": 121,
    "minutesPerPage": 1.5, "minutesSaved": 181 }
  ```
- With no delivered cases: every count is 0, `percentNoPerson` is `null`, `itemTypes` is `[]`.
- `minutesPerPage` is in the business-admin configuration read (`reportingConfiguration.minutesPerPage`) and save (`PUT /business-admin/reporting-configuration`, optional).
- The frontend types have the fields, optional, and no screen uses them yet.

### B4. In the compliance report
- **Placement:** a table at the end of section 3, after "Of the delivered cases", in the same table style. Header: "What LexaVault did for you · Number · Share". The grey line goes under it.
- **Rows** (sample):

  | Row | Number | Share |
  |---|---|---|
  | Handled without a person | 348 | 67% |
  | Pages checked (in 1,873 documents) | 14,220 | — |
  | Sensitive items redacted | 3,912 | — |
  | Review time saved (estimate) | ≈ 161 h | — |

- **Time saved:** "≈ N h" rounded down, or "≈ N min" under an hour, or "0 min".
- **Grey line:** "Estimate: pages in cases that needed no person × <m> minute(s) per page, rounded down. Pages a person reviewed count as zero." It says "1 minute", not "1 minutes".
- **Numbers:** the period's Reports numbers, which section 3 already reads. Minutes per page is the setting **at the period end** from the audit history:
  - the latest change before the end that records it;
  - otherwise the value the first later change replaced (`previous=`);
  - otherwise no change since, so the stored setting.

  Records from before this task, which lack the key, are skipped.
- **Fingerprint:** every row and the grey line are in the content fingerprint (`cases.didForYou`, `cases.didForYouNote`), in print order.
- **Glyphs:** "≈" and "×" draw in IBM Plex. The PDF test checks that no "?" replacement appears.

### B5. Tests
- **Java:**
  - `TimeSavedRuleTest` (5): steps, round down (181.5 → 181, 67.97% → 67), hours and minutes words.
  - `MinutesPerPageSettingTest` (5): 0.4 / 0.5 / 30 / 30.5 / 1.25 and more, the audit detail and that it is numbers only, no record when unchanged, an older caller keeps the value, entity separation.
  - `ComplianceReportServiceTest` (+5): the table and grey line, the fingerprint, minutes per page at the period end (none / later change only / in period / older records skipped), and no delivered cases.
  - `PdfBoxComplianceReportAdapterTest` (+1): the table in section 3, after the delivered table, with the signs drawn.
  - `TimeSavedResponseTest` (4): the capability, the roles, every input in the JSON, and null percent.
  - `PolicyHistoryTest`: wording "Every finding".
- **Script `Invoke-ComplianceReportCheck.ps1`: 116 checks, 0 failed.** The new checks cover:
  - the default 1.0;
  - 400 for 0.4, 30.5, 1.25 and 0, with no record;
  - 30, 0.5 and 2.5 saved with one record each, and no record for an unchanged save;
  - `previous=` and numbers only;
  - each B1 number on hand-made facts: a reviewed case, a reviewed case finished by hand, an unreviewed case finished by hand, a straight-through case, a rejected one, an open one, findings in every status, and 181.5 → 181;
  - agreement with "Delivered" and "No review needed";
  - a second entity's 9,999-page case not counted;
  - Report Viewer and Business Administrator see the block, a processor gets 403;
  - no delivered cases;
  - the PDF rows with 2.5 at the period end ("≈ 5 h": 121 × 2.5 = 302.5) while Reports uses today's 1.5;
  - the appendix prints the new detail.

  **Existing expectation changed:** "Finished by hand" in that script is now 2 (50%), because the new hand-made case C6 is finished by hand.
- **Reruns:**
  - `Invoke-NoRedactedValueCheck.ps1`: PASS, 0 matches (the delivered findings were saved as `REDACTED`).
  - `Invoke-PolicyEstimateCheck.ps1`: 27 checks, 0 failed.

## Commands run and results
| Command | Result |
|---|---|
| `git commit` (U023 answers), `git merge --no-ff feature/ui-safe-policy` | `078728d`, `996e357` |
| `Build-LexaVault-All.ps1` at the merge | PASSED, 597 backend tests |
| `npm run test:standalone` at the merge | 310 passed |
| `Invoke-PolicyEstimateCheck.ps1 -KeepRunning` + `Invoke-ConfirmStepRealData.mjs` + `Stop-PolicyEstimateEnvironment.ps1` | 27/27; 8/8 steps; container removed |
| `npx playwright test --config playwright.oidc-signin.config.ts adminSafePolicy` | 17 passed |
| `Invoke-ComplianceReportCheck.ps1` | 116 checks, 0 failed (first run: 1 failed, see below) |
| `Invoke-NoRedactedValueCheck.ps1` | PASS |
| `Invoke-PolicyEstimateCheck.ps1` | 27 checks, 0 failed |
| `Build-LexaVault-All.ps1` (final) | PASSED, 617 backend tests, TypeScript check, UI framework contract, Vite build |
| `npm run test:standalone` (final) | 320 passed |

**Failures on the way, all fixed:**
- **A4, first try:** 403 "Invalid CORS request" on the save. Fixed in the test-only proxy, as above.
- **My new `timeSavedChangesTheFingerprint` test:** it expected a change from 121 to 122 pages to change the fingerprint. Both print "≈ 3 h", and the fingerprint covers printed values only. The test was wrong, not the code, so the test now uses 160 pages ("≈ 4 h").
- **Compliance script, first run:** "0018 A2: … no AUTHORIZATION_FAILED" failed. My new processor-403 check ran before it and wrote one more `AUTHORIZATION_FAILED` record. I moved my check to the end of the script; the 0018 check is unchanged.

## Tests: counts per module
- **Backend: 617, all pass.** Domain 51, application 229 (+15), scan bridge 47, infrastructure 15 (+1), API 275 (+4).
- **Frontend: 320, all pass.** auth 10, vocabulary 125 (+4), ui-framework 17, oidc-signin 98 (+6), tablet 70.

## Files changed
- **Frontend:** `src/studio/admin/PolicyArea.tsx`, `policyRules.ts`, `policyRules.test.ts`, `admin.css`, `src/api/client.ts`, `src/types/index.ts`, `e2e-oidc/adminSafePolicy.spec.ts`.
- **Backend, application:** `TimeSavedRule` (new), `ReportingConfiguration`, `BusinessAdminConfigurationService`, `OperationalReportSnapshot`, `ComplianceReportContent`, `ComplianceReportService`, `PolicyHistory`.
- **Backend, infrastructure:** `JpaOperationsReadAdapter`, `JpaReportingConfigurationRepositoryAdapter`, `PdfBoxComplianceReportAdapter`, `041-reporting-minutes-per-page.xml` (new), `db.changelog-master.xml`.
- **Backend, API:** `OperationsController`, `BusinessAdminController`, `OperationalReportsResponse`, `BusinessAdminConfigurationResponse`, `UpdateReportingConfigurationRequest`.
- **Backend, tests:** `TimeSavedRuleTest`, `MinutesPerPageSettingTest`, `TimeSavedResponseTest` (new); `ComplianceReportServiceTest`, `ComplianceFakes`, `BusinessAdminConfigurationServiceTest` (new `null` argument only), `PolicyHistoryTest`, `PdfBoxComplianceReportAdapterTest`, `InMemoryComplianceReads`.
- **Scripts:** `test-tools/compliance-report/Invoke-ComplianceReportCheck.ps1` and `README.md`; `test-tools/policy-estimate/Invoke-PolicyEstimateCheck.ps1` (`-KeepRunning`) and `README.md`; new `Invoke-ConfirmStepRealData.mjs`, `vite.realdata.config.mjs` and `Stop-PolicyEstimateEnvironment.ps1`.
- **Docs:** this task and this report.

## Commits
- `90d1fcb` feat: 0020 safe policy follow-ups and the time saved numbers (code, tests, scripts, task and report)
- the next commit: this hash recorded in the report

Not pushed, not merged. `docs/work/0021-task.md` appeared during this task and is left untracked; it is not part of this commit.

## Not done, and why
- The U024 screens (tiles, "How is this worked out?", the Report targets field) are out of scope, as the task says.
- The combined (all entities) view does not exist in Reports, so there was nothing to add up.

## Questions for the owner
1. **"Sensitive items redacted" counts `CONFIRMED` and `REDACTED`.** The task says `CONFIRMED`. But once a redaction is applied, the code moves the finding from `CONFIRMED` to `REDACTED` (`DocumentRedactionService`). In the no-redacted-value run, all delivered findings were `REDACTED`. Counting `CONFIRMED` alone would show close to 0. Keep both?
2. **"Handled without a person" vs Reports' "No review needed".** They differ by the unreviewed cases finished by hand, which the task leaves out of the new tile. Keep the difference, or should Reports' "No review needed" also leave those out? Changing it would also change its target bar and the compliance report's "No review needed" row.
3. **"Pages checked" vs Reports' "Pages".** "Pages checked" counts pages of cases **delivered** in the period, as the task says. Reports' "Pages" counts pages of cases **received** in the period. Both on one page may confuse. Keep both, or relabel one in U024?
4. **Rounding on the canvas.** The canvas shows "68% · 348 of 512". Rounded down, as the design requires, that is **67%**, which is what the backend returns. Should the canvas be corrected?
5. **"1 minute per page".** The grey line says "minute" when the setting is exactly 1 and "minutes" otherwise. The task wrote "<m> minutes". Is that fine?

## Suggested next step
U024: the four tiles, "How is this worked out?", and the "minutes per page" field in Report targets. The API and the setting are ready.

## Owner's answers (2026-11-20, recorded by the design chat)

1. "Sensitive items redacted" counts CONFIRMED and REDACTED: keep.
2. Align Reports' "No review needed" with "Handled without a person": it also leaves out cases finished by hand (its target bar and the compliance report's row follow). Done in U024 step 0.
3. Keep both page counts; the new tile's grey line reads "in N delivered documents".
4. The canvas's 68% is sample data; the build keeps rounding down. No canvas change.
5. "1 minute" when the setting is exactly 1: fine.
Note: task numbers from the UI enhancements chat continue at 0022, because another chat wrote 0021 (sensitivity levels to Vero), which waits for the owner.
