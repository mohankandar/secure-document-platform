# Queue restart 2026-11-23b: summary

| Task | Outcome | Branch | Final commit | Report |
|---|---|---|---|---|
| 0046 tablet 44 px on Linux | DONE | `feature/0046-tablet-44px` | `da996ca` | `docs/work/0046-report.md` |
| 0043 R1 new pattern types | STOPPED: storing the new kinds' on/off flag per entity needs a migration (one boolean column per kind today); the task says stop and describe. Two options in the report. No code changed | `feature/0043-r1-patterns` | `156cbad` (this summary follows it) | `docs/work/0043-report.md` |
| 0044 R2 searchable | NOT STARTED (queue stop rule 3) | | | |

Nothing merged, nothing pushed. Work done in a scratch worktree `C:\dev\lexavault\lv-queue-b` (the owner's app runs from the main checkout; it was not touched). Throwaway container `lv-0046-tablet` ran with `--rm`; image `mcr.microsoft.com/playwright:v1.56.1-noble` was pulled and kept. Protected containers not touched. Product-name guard clean at the end of each task.

## For the owner in the morning
1. **0043 question 1:** approve a migration for the new kinds' switches: option 1 (columns on `tenant_redaction_policies`) or option 2 (`tenant_redaction_kinds` table, recommended for the R1 catalog).
2. **0043 question 2:** how many switches (proposed five: routing, account, tax ID, driver's licence, passport).
3. **0043 question 3:** reuse the unused `FINANCIAL_ACCOUNT` / `GOVERNMENT_ID` kinds, or new specific kinds (proposed: new).
4. **0046:** push the branch when merging so GitHub confirms the tablet job is green.
5. Then restart the queue at 0043, followed by 0044. Remove the worktree when done: `git worktree remove ..\lv-queue-b`.
