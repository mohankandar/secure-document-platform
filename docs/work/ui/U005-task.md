# Task U005: New case (finding 20)

Written: 2026-11-17, by the design chat. Spec: `docs/work/ui/SPEC.md` (locked). Read `docs/work/ui/README.md` first.

Design: `designs/NewCase.html`.

## Do

1. Under the drop area, state the rules before upload: "PDF only · up to <size> MB and <pages> pages each · up to <n> files per case". Take the limits from the existing intake limits; if the screen has no access to them, use the values the server enforces and say where you found them.
2. List chosen files with size and page count; mark each file the server would refuse in red with what to do ("Not a PDF — remove it or convert it first"). The Create button stays disabled while any file is marked.
3. Button text **"Create case"** (was "Open case & submit").
4. Remove "vault" from the step-2 description.
5. Scan tab: show the scanner by a friendly name if the bridge configuration offers one; otherwise "Scanner at <site>". Device ID, site ID and free space go under Technical details.
6. Accept a `?id=<value>` style pre-fill for the primary identifier, used by "Start a new case for this loan" (U003).

## Checks (all tasks)

- `npm run build` and `npm run test:standalone` pass; give counts.
- Every changed screen checked in light and dark, at 1440 px and 1024 px, with the local app (`.\Start-LexaVault-Local.ps1`). Say which local test roles you checked.
- No raw hex outside `tokens.css`; only documented breakpoints; no new dependency without asking.
- Commit on `feature/ui-polish`. Do not push or merge.
