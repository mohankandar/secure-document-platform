# Report 0120: Update-And-Start refuses to build while VS Code's Java runs

Date: 2026-11-28
Branch: `feature/0120-vscode-java-check`, from `origin/feature/lexavault-r1b-tenant-identity` (`bf6fe07`)
Outcome: **DONE** (built and proven; merge waits for a green integration branch, see below)

## What changed
1. **`scripts/lib/LexaVault-JavaLanguageServer.ps1`** (new, in the repo): `Find-LexaVaultJavaLanguageServer` finds a `java`/`javaw` process whose command line contains `org.eclipse.jdt.ls`, `jdt.ls` or `redhat.java`. `Get-LexaVaultJavaLanguageServerRefusal` gives the task's message plus the process ids, or nothing. It only reports and never stops a process. A process list can be passed in for tests.
2. **`C:\dev\lexavault\Update-And-Start.ps1`** (outside the repo, like in 0106): after the branch and local-changes checks and **before** stopping the app or pulling, it loads the check from the checkout and stops with exit code 1 if a server runs. It checks **again after the pull, just before the build**. The owner's checkout gets the check only with the pull that brings this task, so the first run after the merge is still checked before the build. If the branch has no check yet (before this merge), it says "close VS Code yourself" and builds as before, so the owner's script keeps working meanwhile.
3. **`Run-OwnerRound.cmd`** builds nothing (it runs the jar already built), so it needs no check. This is noted in its README.
4. **Owner guide:** a new section "Update-And-Start and VS Code" in `test-tools/owner-run/README.md`. That README already names Update-And-Start; no other guide section exists.

## Proof
| Run | Result |
|---|---|
| `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\testing\Test-LexaVault-JavaLanguageServer.ps1` (fake process lists: found / not found, the message, the ids, backend and Maven not counted, unreadable command line, empty list, the live list readable) | 11 of 11 pass |
| Detection on this laptop, VS Code open | finds the 3 running redhat.java servers |
| `Update-And-Start.ps1 -Checkout <this worktree> -Branch feature/0120-vscode-java-check -NoBrowser`, VS Code open | `STOPPED: VS Code's Java extension is running and locks build files. ...`, exit 1, before the stop script, the pull or the build; nothing changed |
| `bash scripts/ci/check-product-names.sh` | clean |

- No Java, frontend or Maven change, so no build or test suite run (test policy: a small change). No `mvn install`.
- The path with no server running is not run end to end through Update-And-Start here: that would need VS Code closed, and on the owner's checkout it stops his app and pulls. It is his test step (TO-TEST entry).

## Merge
The integration branch is red since the 0111/0112 merges (`StagedPipelinePostgresTest` inline-mode tests; QUEUE.md session notes, queue A). This PR changes no Java code, but `ci-passed` runs the whole backend, so it can only go green once that is fixed. It is merged on the first green `ci-passed`.

No other product's name or behaviour in code.
