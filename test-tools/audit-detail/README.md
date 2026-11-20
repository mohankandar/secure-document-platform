# Audit detail check (task 0016)

`Invoke-AuditDetailCheck.ps1` proves migration 040 and the new audit fields on a **throwaway** Postgres
container (`lexavault-0016-pg`, 127.0.0.1:55416). It never touches `securedoc-postgres` or any other
container named in `CLAUDE.md`, and removes only its own container at the end.

It needs two jars:

- a **base** jar built before task 0016 (migrations up to 039), to write old audit rows;
- the **task 0016** jar (default `backend\securedoc-api\target\securedoc-api-0.1.0-SNAPSHOT.jar`).

```powershell
# Build the base jar from a throwaway worktree, for example of the merge commit f1d2a74:
git worktree add --detach $env:TEMP\lv-base f1d2a74
cd $env:TEMP\lv-base\backend; mvn -o package -DskipTests; cd -
.\test-tools\audit-detail\Invoke-AuditDetailCheck.ps1 -BaseJarPath $env:TEMP\lv-base\backend\securedoc-api\target\securedoc-api-0.1.0-SNAPSHOT.jar
git worktree remove $env:TEMP\lv-base
```

What it checks:

- migration 040 is applied by Liquibase and adds `actor_display_name` VARCHAR(200) and `case_id` VARCHAR(100), both nullable;
- every old audit row is unchanged (md5 of all original columns) and reads back with both new columns empty;
- a new case action records the display name (LOCAL: the username `demo-user`) and the case ID;
- policy changes keep the old keys first, then `enabledTypes=`, `preset=` and `previous=[...]`;
- no system record carries a display name;
- the audit export (JSON and CSV) carries the two new fields, empty for old rows.

It also runs the acceptance-harness API helpers (`test-tools/lexavault-acceptance/helpers`:
`Invoke-TestApi`, `Send-TestDocument`, `Set-TestReviewPolicy`, `Restore-TestReviewPolicy`) with their
default roles, which are now valid `ProductRole` names instead of the retired `ADMIN`.

Exit code 0 only when every check passes. Evidence (`summary.json`, both exports, app logs) stays in
`%TEMP%\lexavault-0016\<timestamp>`. The container password is generated per run and never printed.
