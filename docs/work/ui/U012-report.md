# Report U012: Support, problem detail and Continue manually (findings 9, 10, 30)

Date: 2026-11-18
Branch: feature/ui-polish
Outcome: WAITING FOR THE OWNER. The task starts with "Ask first". Nothing is built yet.

## Ask first: the UI framework contract

### Where the four pages are used today

- `SupportIncidentsPage`, `SupportIncidentDetailPage`, `TechnicalIssueDetailPage` and `ManualFallbackPage` are routed twice:
  - in the legacy app (`App.tsx`), which is still the default (the Case File screens show only with `VITE_CASE_FILE_UI_ENABLED=true`);
  - in the Case File app (`CaseFileApp.tsx`), which reuses the same legacy pages.
- All four are in the contract test's `routePages` list (`src/security/uiFrameworkContract.test.ts`). The rule for them is "must use `<PageScaffold>` and must not hand-roll `Breadcrumbs`/`PageHeader`".

### What I propose (no contract rule changes)

Build new Case File screens and leave the legacy pages alone. This is how U003 to U011 handled the other screens.

- New files under `src/studio/support/`: the Support list, one Problem detail page, and Continue manually.
- In `CaseFileApp.tsx` only, the four routes point at the new screens. `/support/incidents/:id` and `/technical-exceptions/:packageId` both open the one Problem detail page.
- The legacy pages, `App.tsx`, `PageScaffold` and `index.css` stay unchanged. The legacy app still shows them.
- **Contract test: no rule changes.** All four stay in `routePages` and keep passing, because the files don't change. The new screens follow the rules that apply to all files: no raw hex, documented breakpoints only, `ui-button` on every button variant, no retired classes.
- The backend already has what the screens call: incident search and detail, incident actions with `holdDays` ("Keep the original 7 more days"), technical retry, and manual processing. No backend or schema change is expected. If one turns out to be needed, I will stop and ask.
- Reason-code sentences go in the U001 vocabulary module, with tests.

### The other way (not proposed)

Rewrite the four legacy pages in the Case File look. Then they would stop using `PageScaffold`. The contract would change: the four pages would be removed from `routePages` (and covered by a new Case File rule instead), and the legacy app would change too. That is a bigger change to a frozen area for no gain while the legacy app is the default.

## Questions for the owner

1. May I build U012 as proposed: new Case File screens, routed in `CaseFileApp.tsx` only, with the legacy pages and the contract test unchanged?

## Commands run and results

- None. Only code was read.

## Files changed

- `docs/work/ui/U012-report.md` (this report)

## Commits (hash and message), or "not committed"

- "docs(ui): U012 asks the owner first (UI framework contract)". Its hash is in the chat summary.

## Suggested next step

The owner's answer to question 1, then build U012.
