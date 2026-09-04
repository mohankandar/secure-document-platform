# Dashboard Action Menu UX Self-Review — 2026-08-31

## Baseline

Built on the Manual Review API ID serialization fix package. The Manual Review UUID serialization fix, package-scoped review flow, exact-page preview work, smart dashboard polling, and existing dashboard-hide backend semantics are preserved.

## Change made

Dashboard Approver action presentation only:

- Replaced repeated red `Delete Package` links with a compact `⋮` row action trigger.
- `View Details` is available from the action menu.
- `Remove from Dashboard` appears only for `COMPLETED` packages.
- Removal now uses a confirmation modal rather than expanding destructive controls inside the table row.
- On small/touch layouts, the same confirmation is styled as a bottom-sheet style component with 48px touch targets.
- Confirmation copy explicitly states that DB history/audit remains and physical files continue under the existing retention policy.
- Confirmation displays the tenant-configured primary identifier label/value when available; it does not hardcode Contract Number.
- Escape/outside click closes menus; Escape/backdrop closes the confirmation when no removal request is in flight.

## Deliberately unchanged

- Backend `hidePackageFromDashboard` behavior.
- DB schema/migration from the prior workflow correction.
- `PACKAGE_HIDDEN_FROM_DASHBOARD` audit behavior.
- Retention scheduler and physical artifact purge behavior.
- Dashboard smart polling (5 seconds active / 30 seconds idle / visibility-aware).
- Manual Review API ID serialization correction and exact-page rendering flow.
- AI detection, prompt, provider routing, and redaction behavior.

## Static verification

- Modified `DashboardPage.tsx` transpiles successfully with TypeScript `transpileModule` syntax diagnostics.
- No `Delete Package` label remains in frontend source.
- Current continuity/freeze/acceptance wording updated to `⋮` → `Remove from Dashboard`.
- Full frontend type/build requires installed npm dependencies and remains authoritative on the user's workstation through `build-all.cmd`.
