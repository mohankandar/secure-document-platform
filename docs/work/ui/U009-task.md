# Task U009: Admin › Sign-in (finding 15): decide, then build

Written: 2026-11-17, by the design chat. Spec: `docs/work/ui/SPEC.md` (locked). Read `docs/work/ui/README.md` first.

Design: `designs/AdminSignIn.html`.

## First, a decision (write it in the report, then stop for the owner)

With the login hub (`docs/design/LOGIN_CUSTOMER_ENTITY_DESIGN.md`), customers connect their login system at the hub. Say whether this tab is still needed per customer, and recommend one of:

- **A. Keep it** for installs without the hub: build the design (plain labels with the technical names under them, Save → Test → Switch on, "Previous versions" collapsed), IT Administrator only.
- **B. Replace it** with a read-only card: "Your sign-in is managed by your login system. Contact LexaVault support to change it."

Do not build either until the owner picks.

## Checks (all tasks)

- `npm run build` and `npm run test:standalone` pass; give counts.
- Every changed screen checked in light and dark, at 1440 px and 1024 px, with the local app (`.\Start-LexaVault-Local.ps1`). Say which local test roles you checked.
- No raw hex outside `tokens.css`; only documented breakpoints; no new dependency without asking.
- Commit on `feature/ui-polish`. Do not push or merge.
