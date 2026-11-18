# Report U007: Admin for the Business Administrator (findings 11, 12, 13, 17, 18, 23)

Date: 2026-11-18
Branch: `feature/ui-polish`
Outcome: DONE. It includes the owner-approved migration 038 (review pickup target %) and U006's change that makes Reports read it.

## What was done

### Redaction & retention tab (`src/studio/admin/PolicyArea.tsx`)

1. **Sensitive data:** the chips already used the vocabulary names (since U001: Social Security Number, Date of birth, Credit card number, Credit score). Nothing else in that card changed.
2. **Document intelligence, mode:**
   - Off / Observe / On side by side, each with the radio on the left and its name and description beside it. The words are from the design.
   - **Why the radio floated mid-card:** the legacy stylesheet (`src/index.css`, frozen) gives every `input` `width: 100%`, so the radio took the whole row. It is undone inside the Case File admin only (`admin.css`); `index.css` is not changed.
3. **AI version:**
   - The select is "AI version in use" and reads "November 2026 update · released Nov 7, 2026". Under it are the version's description and "Only tested and released versions are offered."
   - "Fallback if something goes wrong: <name>", with **Use this version as the fallback** (was "Mark active as last known good") and **Go back to fallback…** (was "Roll back…"). The confirm dialog and messages use the same words.
   - Model, prompt, detector, OCR rules, profile, validation and threshold versions, the raw version strings and the configuration number are under **Technical details**, closed by default.
   - The baseline notice says "Run in Observe first, so AI suggestions can be compared before you turn it on." (it said "…required for this tenant").
   - **Name and date, derived as the task allows.** Versions have no display name. The name is worked out in this order:
     1. from a year and month in the version string (`2026.11.1` gives "November 2026 update");
     2. otherwise from the release date;
     3. otherwise the version string is shown as it is.
   - **Release date:** it is the version's `promoted_at`, which was stored but not sent. It is now in the admin answer as `promotedAt` (no schema change).
   - The local version `r11-baseline-v1` has no date in its string, so its name comes from its release date. Technical details says where each name came from.
   - Two versions that would read the same also show their version string.
4. **Keeping and deleting** (was "Retention and approval"):
   - "Keep originals for" stays in hours (72 h stays hours).
   - "Keep redacted copies for" and "Keep stopped cases for" (was "Hold if never delivered") open in days when the stored hours are whole days, with a unit menu (hours or days).
   - Hints: "After this, a case can't be processed again.", "Up to 30 days." (from the platform maximum) and "Time to fix and finish a stopped case before it is deleted."
   - "Require a supervisor's approval before delivery".
   - "A case can be processed again at most 3 times (set by LexaVault)."
   - What is typed must be a whole number of hours. Part of an hour, or more than the maximum, is marked and refused, never rounded.
5. **Report targets** (was "Reporting"), as sentences:
   - "Deliver within [4] [hours] for at least [95]% of cases";
   - "Cases needing no review: at least [70]%";
   - "Pick up reviews within [1] [hour] for at least **[80]% of reviews**" (new);
   - "Break reports down by".
   - Times are stored in minutes and shown in the largest whole unit.
   - Out-of-range values are marked next to the sentence and refused on save.
6. **Tab hint:** "What gets redacted, how long copies are kept, and the targets Reports measures against." (was "…how long artifacts live…").

### Case fields tab (`src/studio/admin/FieldsArea.tsx`)

7. **Names in normal case:** names show as configured ("Contract Number", "Loan number"), not in capitals, and in the normal text colour.
   - **Cause:** the same legacy stylesheet sets every `table th` in capitals and muted. It is undone in `admin.css` only.
8. **Headings over their columns:** "Field" is left-aligned over the names. The headings are Main ID, Required, Searchable, AI fills in and Shown (were Primary and Auto-extract); "Order" is kept for screen readers only. A test checks that each heading lines up with its column.
9. **Rules spelled out:** for example "Letters and numbers · 6–12 characters · saved in capitals · spaces and dashes removed", "Text · up to 120 characters", "One of 4 allowed values · saved in capitals". They were "ALPHANUMERIC · 6–12 chars · Uppercased".
10. **The same words in the field editor:**
    - Main ID; Required when a case is created; Shown; AI fills in;
    - "What it can contain" (Numbers only, Letters and numbers, …); Capitals; Remove spaces and dashes;
    - the card title "Case fields" and "Add case field";
    - in Columns on Cases, "Main ID", and "Case field" or "Set by LexaVault" (were "Business identifier" and "Operational field").

### Backend (migration approved by the owner on 2026-11-18)

11. **Migration `038-reporting-review-target-percent.xml`**, registered in the master changelog after 036:
    - `review_wait_target_percent` INT NOT NULL DEFAULT 80 on `reporting_tenant_settings`;
    - check constraint `ck_reporting_review_wait_target_percent` (0–100);
    - a rollback that drops the column.
    - Migration 037 is still not in the master.
12. **`ReportingConfiguration`:** gains `reviewWaitTargetPercent`, which must be 0–100 (default 80).
13. **Repository and saving:**
    - the repository reads and writes it;
    - the audit line includes it;
    - `PUT /business-admin/reporting-configuration` accepts `reviewWaitTargetPercent`. If it is left out (the legacy `BusinessAdminPage`, which is frozen), the stored value is kept, never reset.
14. **Answers that now carry it:**
    - `GET /business-admin/configuration` returns it;
    - the reports answer has `serviceLevel.reviewWaitTargetPercent`;
    - the CSV export has a "Review wait target percent" line.
15. **U006's change:** the third service-level bar uses the setting instead of 100%. If a report answer has no readable value, only the Service levels card shows its error; the value is not guessed.

### Also fixed

- **Sideways scroll at 1024 px on both Admin tabs.** The page gutter drops to 16 px at 1024 px, but the tab strip still bled 24 px, so the page scrolled by 8 px. This was already there before this task. It is fixed with the same 1024 px breakpoint the page uses; no new breakpoint.

## Commands run and results

- **Backend:** `cd backend; mvn clean install` passed with 443 tests:
  - domain 31;
  - application 128 (3 new);
  - scan bridge 47;
  - infrastructure 8;
  - API 229.
- **Migration on a throwaway database first:**
  - **Setup:** a new `postgres:16-alpine` container, `lexavault-u007-migration-check`, on port 15499, with throwaway credentials. The API jar ran against it with its own storage folders in the scratch folder, never the project's.
  - **Stage 1:** the master without 038 (77 changesets). One settings row was added.
  - **Stage 2:** the real master. 038 EXECUTED (78 changesets).
  - **Results:** the column is `integer`, NOT NULL, default 80. The existing row got 80. 101 and −1 are refused by the check constraint, and 100 is accepted.
  - The container was removed afterwards. No other container was touched.
- **Dev database:** the local app was then started with the new jar, and 038 applied to `securedoc-postgres` (EXECUTED). It has no stored settings rows yet, so the defaults apply (80%).
- **Phase 1:** its test changelog includes the master, so 038 will apply there on the next `Start-Phase1.ps1`. Phase 1 was not run in this task: no sign-in or entity code changed.
- **Frontend:**
  - `npm run build`: passed.
  - `npm run test:standalone`: passed:
    - `test:auth` 8;
    - `test:vocabulary` 38 (7 new);
    - `test:ui-framework` 17;
    - `test:oidc-signin` 44 (8 new).
  - Final run after the last change.
- **Changed tests:**
  - `BusinessAdminConfigurationServiceTest`: the two existing `updateReportingConfiguration` calls gained the new argument (80). Nothing else in them changed.
  - Report fixtures in `e2e-oidc/reports.spec.ts` and `e2e-oidc/errorStates.spec.ts` gained `reviewWaitTargetPercent`.
- **Failures during the work (all in my own checks; the screen was right):**
  - **Unit test:** my check for codes found "UUID" in "System ID (UUID)". It now reads "System ID".
  - **First run of `adminBusiness.spec.ts`,** two checks:
    - "hours" matched three elements: the hidden `<option>` texts of the unit menus;
    - "no 'minutes' on the card" matched the same hidden options.
    - Both checks were narrowed.
- **Local app:**
  - Started with `.\Start-LexaVault-Local.ps1 -ScannerMode SIMULATOR -AiModel qwen3.5:9b`. "Frontend did not become ready" appeared again (noted for the backend work), and the frontend answered (200).
  - Admin was checked as **Business Administrator**: both tabs, light and dark, 1440 and 1024 px, in Edge (Playwright). No page errors, and no sideways scroll after the fix above.
  - Reports was checked as Report Viewer: the review bar shows "target 80%", read from the new setting through the live API.
  - **Fixed after looking at the screens:**
    - the field names were in the muted colour (legacy `th` style);
    - the 1024 px sideways scroll.
  - **Nothing was saved to the dev database from the screens:** no admin settings were changed there.
  - Stopped with `.\Stop-LexaVault-Local.ps1`.
- **Rules:**
  - no raw hex outside `tokens.css`;
  - no new breakpoint and no new dependency;
  - `uiFrameworkContract.test.ts`, `src/index.css` and the legacy pages are unchanged.

## Tests: counts per module, and any failures with their output

- **Backend `BusinessAdminConfigurationServiceTest` (3 new):**
  - the default is 80 and a value is saved; 0 and 100 are allowed;
  - −1 and 101 are refused and nothing is saved;
  - leaving the percentage out keeps the stored value.
- **New `src/studio/admin/adminRules.test.ts` (node, 7):**
  - unit choice;
  - whole-unit input (never rounded);
  - durations in words;
  - version names from the version string, then the release date, then the version itself;
  - duplicate names;
  - case field rules for every type, with no codes.
- **New `e2e-oidc/adminBusiness.spec.ts` (Playwright, mocked API, 6):**
  - **Document intelligence:**
    - Off / Observe / On, side by side, each radio at the left and beside its text;
    - the version name and date;
    - fallback wording;
    - codes only under Technical details, which is closed;
    - no "last known good", "bundle" or raw version outside it.
  - **Keeping and deleting:**
    - hours for originals; days for the others; the hints;
    - saving 10 days sends 240 hours.
  - **Keeping and deleting, refused:** 31 days and 1.5 hours are marked and not sent.
  - **Report targets:**
    - the sentences with 4 hours, 95%, 70%, 1 hour and 80%;
    - saving sends `reviewWaitTargetMinutes` 120 and `reviewWaitTargetPercent` 85.
  - **Report targets, refused:** 101% is marked and not sent.
  - **Case fields:**
    - headings;
    - names not in capitals;
    - the rules spelled out;
    - each heading lined up with its column, and "Field" left-aligned.
- **`e2e-oidc/reports.spec.ts` (2 new):**
  - the review bar measures against 80%, not 100%;
  - an answer without the target fails only the Service levels card.
- Failures: none in the final runs.

## Questions for the owner

1. **Columns on Cases** lists LexaVault's own columns with the server's names in title case ("Intake Source", "Export Status", "Last Updated"). Rename them to the vocabulary ("Receiving", "Sending to SDV" and so on, in sentence case)? This would be a small backend wording change, so it was not done here.
2. **"Detected entities (4)"** in Sensitive data uses "entities", which now also means a legal entity (the entity switch). Change it to "Sensitive types (4)"?

## Owner's answers (2026-11-18)

1. **Cases column names:** yes, show them as vocabulary words in sentence case ("Intake source", "Export status"). If the server sends a stable key for each column, map the key to `words.ts` in the UI and change nothing in the backend. Only if it sends display labels alone, keep them as they are now and add "stable column keys" to the backend work after the UI series.
2. **"Detected entities":** yes, rename it to "Sensitive types (4)". In this product "entity" means only a legal entity. Put it in `words.ts`, and change any other screen that uses "entity" for a sensitive-data type to the same word.

Apply both, note what was done here, commit, and then do the next UI task.

## Owner's answers applied (2026-11-18)

1. **Cases column names.** The server sends a stable key for each column (`intakeSource`, `exportStatus`, `lastUpdated`, `exportCycles`, …, from `DashboardColumnConfigurationService`), so this is UI only; the backend is unchanged.
   - `words.ts` gains `SYSTEM_COLUMN_NAMES` and `dashboardColumnName()`. Columns on Cases now reads "Intake source", "Export status", "Last updated", "Export cycles" (and Received, Stage, Documents, Attention as before).
   - A case field keeps its configured label exactly. An unknown LexaVault column falls back to its server label in sentence case.
   - The words are the owner's examples. "Intake source" is not changed to "Receiving" (the vocabulary word for the Integrations tab); say if it should be.
   - The Case File Cases screen does not show these columns, so nothing else changed.
2. **"Detected entities" is now "Sensitive types (4)"** (`SENSITIVE_TYPES_HEADING` in `words.ts`).
   - The new test also found the Sensitive data hint saying "A driver's license … is not an entity type"; it now says "is not a sensitive type".
   - The helper in `PolicyArea.tsx` is renamed from `entityLabel` to `typeName`.
   - No other Case File screen shows "entity" for a sensitive type. The legacy Review and Continue manually pages (frozen) use the word only in code names, not in what people see; they are unchanged.

**Commands and results**
- `npm run build`: passed.
- `npm run test:standalone`: passed: `test:auth` 8, `test:vocabulary` 40 (2 new), `test:ui-framework` 17, `test:oidc-signin` 45 (1 new, 1 extended).
- **New and changed tests:**
  - `words.test.ts`: the heading has no "entity"; column names by key, the fallback, and case fields as configured.
  - `adminBusiness.spec.ts`: the fixture gained five columns; the Case fields test checks the Columns on Cases names; a new test checks "Sensitive types (4)" and no "entity" on the Sensitive data card.
- **Failures during the work (my own checks):**
  - the new Sensitive data test first failed on the hint's "entity type" (a real wording miss, fixed above);
  - then on two wrong locators of mine (an exact text match on "Sensitive types" and the card's first legend, which is "Send to review"). Both were narrowed to the "Sensitive types (4)" group.
- Not checked on the live app: these are word changes on screens already checked in this task; the mocked browser tests cover them.
