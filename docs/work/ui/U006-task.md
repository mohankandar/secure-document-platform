# Task U006: Reports: entity filter, by-entity table, review target, chart (findings 13, 14, 32)

Written: 2026-11-17, by the design chat. Spec: `docs/work/ui/SPEC.md` (locked). Read `docs/work/ui/README.md` first.

Design: `designs/Reports.html`.

## Do

1. **Entity filter** in the toolbar ("All entities" or one entity), shown only to people with access to more than one entity.
2. **"By entity" table** (cases, delivered, delivery rate, typical time, no review needed, total row) in the headline part, so the Report Viewer sees it. It adds up numbers only; it never lists cases.
3. **Review target:** the third service-level bar uses the new setting from U007 instead of 100%.
4. **Plain words** on the tiles: "Typical time to delivery" (median), "slowest 5%" (p95), "No review needed" (straight-through), "Open now" (backlog). Title "How the work is going".
5. **Chart:** a left axis with values, and the numbers on hover; dates at regular intervals.
6. Report Viewer without operational access sees one line: "More detail is available to Business Administrators."

## Backend needed (ask before any schema change)

- Reports grouped and filtered by entity: today `OperationalReports` has no entity dimension. Propose the API change in the report first.
- Review pickup target %: see U007.

## Roles

Headline, service levels and by entity: Business Administrator and Report Viewer. Detailed sections and Export CSV: Business Administrator only (unchanged).

## Checks (all tasks)

- `npm run build` and `npm run test:standalone` pass; give counts.
- Every changed screen checked in light and dark, at 1440 px and 1024 px, with the local app (`.\Start-LexaVault-Local.ps1`). Say which local test roles you checked.
- No raw hex outside `tokens.css`; only documented breakpoints; no new dependency without asking.
- Commit on `feature/ui-polish`. Do not push or merge.
