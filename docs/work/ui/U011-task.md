# Task U011: Admin › Audit trail (finding 29)

Written: 2026-11-17, by the design chat. Spec: `docs/work/ui/SPEC.md` (locked). Read `docs/work/ui/README.md` first.

Design: `designs/AuditTrail.html`.

## Do

1. Rows read as sentences: when, what happened ("Approved delivery", "Redacted a Social Security Number", "Changed the auto-redact level" with "80% → 85%"), who, case (primary identifier), result. "LexaVault (automatic)" for system actions.
2. One toolbar: type (Everything, Sign-in, Settings, Cases, Exports, Refused), person, period, loan number search, More filters, Export (CSV or JSON in one menu).
3. More filters holds action, result, item type, item id, person id, reference (correlation id) and exact time range.
4. A row opens to show every recorded detail, unchanged.
5. If the audit records have no display name for the actor or no primary identifier for the case, say so in the report; show the email or id until the backend provides them. Do not change what is recorded.

## Roles

IT Administrator and Platform Operations (unchanged).

## Checks (all tasks)

- `npm run build` and `npm run test:standalone` pass; give counts.
- Every changed screen checked in light and dark, at 1440 px and 1024 px, with the local app (`.\Start-LexaVault-Local.ps1`). Say which local test roles you checked.
- No raw hex outside `tokens.css`; only documented breakpoints; no new dependency without asking.
- Commit on `feature/ui-polish`. Do not push or merge.
