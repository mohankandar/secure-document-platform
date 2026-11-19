# Task U002: top bar and account menu (findings 1, 31)

Written: 2026-11-17, by the design chat. Spec: `docs/work/ui/SPEC.md` (locked). Read `docs/work/ui/README.md` first.

Design: `designs/Main.html` (menu open), and the top bar on every other design.

## Do

1. Replace the right side of the top bar (organization text, theme toggle, local role picker, "Sign out" button) with one **account button**: avatar, person's name, current entity name. It opens a menu with:
   - person: name, email, role name(s);
   - organization name as a label, then the entity list with the current one ticked and the others selectable. Use the existing `EntitySwitch` logic from task 0009; only its placement changes. With one entity, show just its name, no switch;
   - Theme: Light / Dark / System;
   - Test role: the local role picker, **only in local security mode**;
   - Sign out.
2. Below the 1100 px area (use the nearest documented breakpoint), the "Find a case or action" box becomes an icon button that opens the same command palette. At 1024 px nothing may wrap or be cut off.
3. Keyboard: the menu opens with Enter/Space, closes with Esc, focus returns to the button; it is a real menu (arrow keys move between items).

## Roles

Everyone. The nav items still follow capabilities as today.

## Tests

- Local role picker not rendered when not in local mode.
- Entity switch not rendered with one entity.

## Checks (all tasks)

- `npm run build` and `npm run test:standalone` pass; give counts.
- Every changed screen checked in light and dark, at 1440 px and 1024 px, with the local app (`.\Start-LexaVault-Local.ps1`). Say which local test roles you checked.
- No raw hex outside `tokens.css`; only documented breakpoints; no new dependency without asking.
- Commit on `feature/ui-polish`. Do not push or merge.
