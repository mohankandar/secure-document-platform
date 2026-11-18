# UI work folder: tasks and reports

The approved and locked UI polish for the Case File design. Written by the design chat on 2026-11-17.

This folder has its own numbering (`U001`, `U002`, …) so it never collides with the backend tasks in `docs/work/` (`NNNN`). The routine in `docs/work/README.md` applies unchanged, with two differences:

1. **Where:** UI tasks run in the main folder (`C:\dev\lexavault\secure-document-platform`), on branch `feature/ui-polish` (U001 creates it from `feature/lexavault-r1b-tenant-identity`). Backend tasks pause until the UI tasks are done (owner's decision, 2026-11-17).
2. **Which task:** when the owner says "do the next UI task", take the **lowest**-numbered `UNNN-task.md` here that has no matching `UNNN-report.md`. Do them in order; do not start a task while an earlier one has no report.

Reports use the same layout as `docs/work/README.md`, saved as `UNNN-report.md` in this folder.

## The spec

- The complete, locked spec is `docs/work/ui/SPEC.md` (findings 1–32, roles, vocabulary, colours).
- Each task names its design reference in `docs/work/ui/designs/`. Those are static HTML mockups: open them in a browser. They show layout, words and roles. They are not code to copy: build with the existing Case File components and `tokens.css`.
- Blue dashed "Seen by" tags in the mockups are annotations, not product UI.

## Rules that matter most for this work

- `CLAUDE.md` applies in full. The UI framework is a frozen area: the Case File screens may change as these tasks say, but anything that changes `src/security/uiFrameworkContract.test.ts`, the legacy `PageScaffold` pages' contract, or adds a dependency needs the owner's yes first.
- No raw hex colours outside `src/tokens.css`; only the documented breakpoints.
- Roles: show an action only to people whose capability allows it. The backend still enforces every rule; the UI only hides what would be refused.
- Some tasks need small backend changes (named in the task). Those follow the backend rules: ask before any schema change.
- Run `npm run build` and `npm run test:standalone` for every task; add tests where the task says so. Check every changed screen in light and dark, at 1440 px and 1024 px.
