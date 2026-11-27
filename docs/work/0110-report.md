# Report 0110: hardening, part 2 (IN PROGRESS)

Date: 2026-11-27
Branch: `feature/0110-hardening-part2`, from `origin/feature/lexavault-r1b-tenant-identity` at `7c56856` (independent of 0112). Worktree `C:\dev\lexavault\lv-queue-a-0110`, so the 0112 owner round in `lv-queue-b` kept its build.
Outcome: **IN PROGRESS**: items 2, 3 and 5 done and committed; items 1 and 4 not started. Pushed, no PR (not finished).

## Done
- **2. Endpoints deny by default.** `CapabilityAuthorizationInterceptor` refuses an `/api/v1` method with no `@RequireCapability` unless it carries the new `@OpenEndpoint("why")` mark; the refusal is recorded once as DENIED (`LV-AUT-006`, `detail=NO_RULE`, method and route pattern only). 12 methods are marked open and listed in `OpenEndpoint.LISTED`: the signed-in person's own data (`/me`, own entity, entities to enter, branding), the compliance report (checks its two capabilities itself), the always-refused download, and the 6 machine intake methods (push and scanner). Test `EveryEndpointHasARuleTest` walks every controller and fails on a method without a rule or a list that differs from the marks.
  - Found: `@RequireRole` is used nowhere and its interceptor is not registered, so a role mark alone is **not** counted as a rule.
- **3. Audit table protected by the database.** Migration **088** (`REVOKE UPDATE, DELETE, TRUNCATE ON audit_records` from the app's own user and PUBLIC; rollback grants them back). Included right after 087, so the roll-back counting tests are unchanged. Nothing in the app changes or deletes audit rows; there is no audit purge. Test `AuditRecordsAppendOnlyPostgresTest`: an ordinary role runs the changelog, adds and reads a row; update, delete and truncate are refused (SQLSTATE 42501), the row is unchanged.
- **5. Polling answer size limit.** The list of cases and the receipt are read up to `lexavault.polling.max-answer-bytes` (default 20 MB, a deployment setting); a larger answer is refused with the new **LV-INT-045** and nothing is stored. PDFs keep their own per-source limit. Test `PollingAnswerSizeLimitTest`. `SecretReferencesApprovedNamesTest` (0109) changed, test-only: its mocked answer is now a stream.

## Tests run
`mvn -o -Pdev-build install -DskipTests`; `EveryEndpointHasARuleTest`, `CapabilityAuthorizationInterceptorTest` (13); code list tests (registry, guide, frontend file); `PollingAnswerSizeLimitTest`, `SecretReferencesApprovedNamesTest`, polling intake tests (44); the whole `securedoc-infrastructure` module on a throwaway PostgreSQL (217, 0 failed, 2 skipped). Not run yet: the whole API module, the frontend (only the generated code list changed).

## Left
- **1. Outbound addresses limited** (sources and destinations, on save and at connect time, allow-list by name per connection). Needs a stored allow-list per connection: a schema change (approved by the task).
- **4. Text model pinned and local** (reuse the vision model's digest check and local-only rule; `Start-LexaVault-Local.ps1` passes the digest).

## For the owner
- Migration 088 binds only an ordinary database role. If LexaVault's database user is a superuser (the default `POSTGRES_USER` of a Docker PostgreSQL is), the database does not enforce it. Production must run the app as an ordinary role.

## Questions
1. Item 5 "admin setting": done as a deployment setting (no schema change). A per-source screen field would need a column. Recommended: keep the deployment setting.

No other product's name or behaviour in code.
