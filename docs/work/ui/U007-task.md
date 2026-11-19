# Task U007: Admin for the Business Administrator (findings 11, 12, 13, 17, 18, 23)

Written: 2026-11-17, by the design chat. Spec: `docs/work/ui/SPEC.md` (locked). Read `docs/work/ui/README.md` first.

Designs: `designs/AdminBusiness.html`, `designs/AdminRetention.html`.

## Redaction & retention tab

1. **Sensitive data:** chips use the vocabulary names (Social Security Number, Date of birth, Credit card number, Credit score). Rest unchanged.
2. **Document intelligence:** the three mode options side by side, radio on the left, name and description beside it: Off / Observe / On. Today the radio floats in the middle and the text is pushed right.
3. **AI version:** show the version as a plain name and date ("November 2026 update · released Nov 1, 2026") with its description. "Last known good" becomes "Fallback if something goes wrong" with buttons "Use this version as the fallback" and "Go back to fallback…". Model, prompt, detector, OCR rules, profile, validation and thresholds versions go under Technical details. If bundles have no date or display name, derive "November 2026 update" from the version string and say so.
4. **Keeping and deleting** (was Retention and approval): durations in days where whole days (72 h stays hours), "Keep stopped cases for" (was Hold if never delivered), "Require a supervisor's approval before delivery".
5. **Report targets:** add **"Pick up reviews within <time> for at least <n>% of reviews"** (default 80%).
   - **Backend + schema change:** `ReportingConfiguration` has no review target percentage. Ask the owner before the migration. Reports (U006) reads it.

## Case fields tab

6. Field names in normal case, column headings aligned with their columns, and each rule spelled out ("Letters and numbers · 6–12 characters · saved in capitals · spaces and dashes removed").

## Roles

Business Administrator only (unchanged).

## Checks (all tasks)

- `npm run build` and `npm run test:standalone` pass; give counts.
- Every changed screen checked in light and dark, at 1440 px and 1024 px, with the local app (`.\Start-LexaVault-Local.ps1`). Say which local test roles you checked.
- No raw hex outside `tokens.css`; only documented breakpoints; no new dependency without asking.
- Commit on `feature/ui-polish`. Do not push or merge.
