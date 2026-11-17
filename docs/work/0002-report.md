# Report 0002: adopt the work-folder routine and record Phase 0

Date: 2026-11-17
Branch: `fix/surefire-infrastructure-tests`
Outcome: DONE

Task 0002 was written before the last steps of Phase 0 were finished. Steps 1 and 4 were already done when the task was picked up. They were checked and are reported as they stand; nothing was redone or reverted.

## What was done

1. **Step 1, the two commits.** Both exist, so nothing new was needed:
   - `30e318a` build: pin maven-surefire-plugin 3.5.2 for every backend module (`backend/pom.xml` only)
   - `0e5b258` docs: add Claude Code instructions and login/customer/entity design (`CLAUDE.md`, `docs/design/LOGIN_CUSTOMER_ENTITY_DESIGN.md`)
2. **Step 2.** Added the "Work routine" section to `CLAUDE.md`, directly above "## Environment (Windows)", with the wording from the task.
3. **Step 3.** Wrote `docs/work/0001-report.md` for Phase 0, including the Maven 3.9 / 3.8.7 correction.
4. **Step 4.** Checked and reported below. Nothing was changed in this task.
5. **Step 5.** Committed `docs/work/README.md`, `docs/work/0002-task.md`, `docs/work/0001-report.md` and the `CLAUDE.md` change together as `c33a602`.
6. **Step 6.** This report, committed on its own.

## Answers to step 4

**Git identity**

```
git config user.name   ->  mohankandar
git config user.email  ->  111023382+mohankandar@users.noreply.github.com
```

Both come from `C:/Users/mohan/.gitconfig`. The `mohan@MSI.(none)` value seen earlier was right: no identity was set at the time, and the first commit attempt failed for that reason. The owner then told the coding session to set the global identity, and it was set to the GitHub noreply address that the earlier commits on this branch already use. This happened before task 0002 was picked up. Git configuration was not changed in this task.

**`.gitignore`**

Both folders should be ignored, and both already are. The owner approved the change separately, and it was committed as `531b639` before this task. `.gitignore` was not changed in this task. `git check-ignore` confirms that `.gitignore:66 .run/` and `.gitignore:67 **/securedoc-output/` match.

| Folder | What it holds, by type | Why ignore it |
|---|---|---|
| `.run/` | 3 small `.pid` files (backend, frontend, scanner-bridge shell process IDs) written by the local start scripts | Machine-local runtime state. Several scripts also use `.run\` for a cluster manifest and watched-intake staging. `scripts/Invoke-LexaVault-FinalReleasePreflight.ps1` already requires `.run/` in `.gitignore`. |
| `backend/securedoc-output/` | Local delivery output: a tenant-ID folder containing a `.staging` folder and a package-ID folder with a `cycle-1` folder; 1 PDF in total | Processed documents written by the backend's local delivery adapter (`output-path: ./securedoc-output` in `application.yml`). Generated at runtime and possibly sensitive; matches the existing `securedoc-storage/` rules. |

## Commands run and results

| Command | Result |
|---|---|
| `git config user.name` / `user.email` / `--show-origin` | As above |
| `git check-ignore -v .run/x backend/securedoc-output/x` | Both ignored (lines 66 and 67) |
| `ls` / `find` on the two folders (types and counts only, no contents read) | As in the table above |
| `git commit` (step 5) | `c33a602`, 4 files, +170 lines |

No build was run. This task changed documentation only.

## Tests: counts per module, and any failures with their output

Not run: there were no code changes. The last build gave 426 tests and 0 failures (see report 0001).

## Files changed

- `CLAUDE.md`: +4 lines ("Work routine" section)
- `docs/work/README.md`: new (supplied by the design chat, unchanged)
- `docs/work/0002-task.md`: new (supplied by the design chat, unchanged)
- `docs/work/0001-report.md`: new
- `docs/work/0002-report.md`: new (this file)

## Commits (hash and message), or "not committed"

- `c33a602` docs: adopt the docs/work task and report routine, record Phase 0
- This report is committed separately, right after `c33a602`. Its hash is in `git log`.

`git log --oneline -6` before this report's commit:

```
c33a602 docs: adopt the docs/work task and report routine, record Phase 0
531b639 chore: ignore local runtime state, delivered output and result folders
0e5b258 docs: add Claude Code instructions and login/customer/entity design
30e318a build: pin maven-surefire-plugin 3.5.2 for every backend module
aa3eb30 ops: add size-limited rolling stdout capture for Ubuntu frontend
b56d998 ops: match Ubuntu stop script to external runtime directory
```

`git status --short` before this report was written: clean (no output).

Not pushed, not merged.

## Not done, and why

Nothing from the task is undone. Steps 1 and 4 needed no action because the work was already done, as explained above.

## Questions for the owner

None.

## Suggested next step

1. Confirm the Ubuntu WSL build (Maven 3.8.7) gives 426 tests, with 8 in securedoc-infrastructure.
2. Decide whether to merge `fix/surefire-infrastructure-tests` into `feature/lexavault-r1b-tenant-identity`.
3. Then start Phase 1.
