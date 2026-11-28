# Report 0110: hardening, part 2 (IN PROGRESS)

Date: 2026-11-27
Branch: `feature/0110-hardening-part2`, from `origin/feature/lexavault-r1b-tenant-identity` at `7c56856` (independent of 0112). Worktree `C:\dev\lexavault\lv-queue-a-0110`, so the 0112 owner round in `lv-queue-b` kept its build.
Outcome: **IN PROGRESS**: items 2, 3, 4 and 5 done and committed; item 1 started (the address rule and its tests, not yet used by sources and destinations). Pushed, no PR (not finished). Second session 2026-11-27 evening, stopped before 21:50 (owner tests from 22:00).

## Done
- **2. Endpoints deny by default.** `CapabilityAuthorizationInterceptor` refuses an `/api/v1` method with no `@RequireCapability` unless it carries the new `@OpenEndpoint("why")` mark; the refusal is recorded once as DENIED (`LV-AUT-006`, `detail=NO_RULE`, method and route pattern only). 12 methods are marked open and listed in `OpenEndpoint.LISTED`: the signed-in person's own data (`/me`, own entity, entities to enter, branding), the compliance report (checks its two capabilities itself), the always-refused download, and the 6 machine intake methods (push and scanner). Test `EveryEndpointHasARuleTest` walks every controller and fails on a method without a rule or a list that differs from the marks.
  - Found: `@RequireRole` is used nowhere and its interceptor is not registered, so a role mark alone is **not** counted as a rule.
- **3. Audit table protected by the database.** Migration **088** (`REVOKE UPDATE, DELETE, TRUNCATE ON audit_records` from the app's own user and PUBLIC; rollback grants them back). Included right after 087, so the roll-back counting tests are unchanged. Nothing in the app changes or deletes audit rows; there is no audit purge. Test `AuditRecordsAppendOnlyPostgresTest`: an ordinary role runs the changelog, adds and reads a row; update, delete and truncate are refused (SQLSTATE 42501), the row is unchanged.
- **5. Polling answer size limit.** The list of cases and the receipt are read up to `lexavault.polling.max-answer-bytes` (default 20 MB, a deployment setting); a larger answer is refused with the new **LV-INT-045** and nothing is stored. PDFs keep their own per-source limit. Test `PollingAnswerSizeLimitTest`. `SecretReferencesApprovedNamesTest` (0109) changed, test-only: its mocked answer is now a stream.
- **4. Text model pinned and local.** `securedoc.ai.model-digest` (`SECUREDOC_AI_MODEL_DIGEST`) in application.yml, pinned to the validated text model qwen3.5:9b, which is also the new default model name in application.yml (was qwen2.5:7b, never validated; the local profiles keep their own default). With the Ollama provider, `TextModelStartCheck` refuses the start when the server is not on this machine or a private network (LV-AI-006), the model is not installed there, or its digest is not the approved one (LV-AI-008); the message says what to do. A server that does not answer at start is logged (LV-AI-001) and the start goes on. The local-address rule is now shared with the vision model (`LocalModelServer`, same behaviour). `Start-LexaVault-Local.ps1` holds the approved digest per validated model, compares it with what Ollama states before starting, and passes it to the app; another model needs `SECUREDOC_AI_MODEL_DIGEST` (after its benchmark). The two mock model servers (export end-to-end, fake-value check) state the approved digest. Test `TextModelStartCheckTest` (7).
  - Not covered yet (follow-up): entity-specific AI profiles, the failover server, and a re-check of the text model while running (the vision model re-checks per page).
- **1 (started). Outbound addresses limited.** `OutboundAddressRule` (application module): https only; after resolving the name, no loopback, link-local (metadata included), private, shared, unique-local, unspecified or multicast address. A host named in the connection's allow-list may be internal or plain http, by name only (a written address is never allow-listed). Test `OutboundAddressRuleTest` (6). **Not yet used** by any source or destination.

## Tests run
- First session: `mvn -o -Pdev-build install -DskipTests`; `EveryEndpointHasARuleTest`, `CapabilityAuthorizationInterceptorTest` (13); code list tests (registry, guide, frontend file); `PollingAnswerSizeLimitTest`, `SecretReferencesApprovedNamesTest`, polling intake tests (44); the whole `securedoc-infrastructure` module on a throwaway PostgreSQL (217, 0 failed, 2 skipped).
- Second session: `TextModelStartCheckTest` (7), `OllamaVisionModelAdapterTest` (11), `OllamaModelPreloaderTest` (3), `OutboundAddressRuleTest` (6); `mvn -o -Pdev-build install -pl securedoc-ai,securedoc-api`: 996 tests, 0 failed, 121 skipped (the PostgreSQL tests, no throwaway database this run), BUILD SUCCESS; `Start-LexaVault-Local.ps1` parsed with the PowerShell parser and its digest comparison run against the local Ollama (qwen3.5:9b matches).
- Not run: the frontend suites (only a mock server in `e2e-export` changed; CI runs it), the owner's full local start.

## Left
- **1. Outbound addresses limited, wiring:** a stored allow-list per connection (migration 089, approved by the task), the field on the connection screen for the IT Administrator, the check on save (LV error naming the field) and at connect time in the polling source, the derived destination and the profile destination (and the connection tests). Then the TO-TEST entry, the PR and the merge.

## For the owner
- Migration 088 binds only an ordinary database role. If LexaVault's database user is a superuser (the default `POSTGRES_USER` of a Docker PostgreSQL is), the database does not enforce it. Production must run the app as an ordinary role.
- Starting the app without `Start-LexaVault-Local.ps1` with another text model now needs `SECUREDOC_AI_MODEL_DIGEST` set to that model's digest; otherwise the start is refused (LV-AI-008).

## Questions
1. Item 5 "admin setting": done as a deployment setting (no schema change). A per-source screen field would need a column. Recommended: keep the deployment setting.

No other product's name or behaviour in code.

## Why item 1 is not wired yet
The existing source and destination tests connect to stub servers on `http://127.0.0.1`, which the rule refuses. Wiring it means the allow-list column, the screen field, the save and connect-time checks, and updating those tests to allow-list their stub host, plus one frontend run. That did not fit safely before the owner's 22:00 test window.
