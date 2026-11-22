# Report 0037: Switch-over part 1a: platform-core libraries, schema step, identity storage, setup

Date: 2026-11-22
Branch: `feature/0037-switchover-1a`
Outcome: PARTLY DONE. Steps 0 to 5 are built, tested and committed. Not run: `Test-LexaVault-OidcSessionContinuity.ps1` (it needs the Keycloak test user's password, which only the owner has). The R1 login-connection admin screen still uses the old table (owner's decision during the task; see Questions).

Owner decisions taken during the task (asked in the session):
- Setup needs the licensing tables, so the schema step also runs `db/platform/licensing` now. LexaVault itself does not use platform-licensing until 0038.
- The demo licence is signed with LexaVault's own development key.
- The product code is `LEXAVAULT`.
- The three old identity readers are deleted.
- The admin screen is left on the old table and raised as a question.

## What was done

### Step 0: the `feature/phase2-role-mapping` branch

| Commit | Step | Result | Why |
|---|---|---|---|
| `b4a46f3` | A1 resolve the principal once per request | **Redone** (`99eba75`) | Still applies. It was redone because the display name is now bound per request (task 0016): on reuse the name is bound again from the same values. Its test was brought over, plus one test for the display name. |
| `3207c21` | A2 drop the redundant preflight call | **Redone** (`99eba75`) | Still applies. The "no entity" refusal keeps its message and code (`NO_VERIFIED_MEMBERSHIP`, LV-AUT-002). |
| `fe8edcd` | A3 LexaVault owns its refusal words | **Redone** in step 2 (`48892ef`) | Needed: platform-identity's messages are neutral. `IdentityAdmissionMapping.message` gives the 0.3.0 texts, and `IdentityAdmissionMessagesTest` pins all 15. |
| `5d5fa05` | A6 `load-role-mappings` command | **Dropped** | Replaced by setup's grants (`idn_group_grant`). |
| `05c358e` | A7 Phase 1 checks M-01 to M-08 | **Dropped** | They tested the A6 command. |
| `cf92a54`, `9f1bac6` | task and report 0014 | Dropped | Docs only. |

What A6 did that setup does not:
1. A LexaVault audit record `ROLE_MAPPING_LOADED` per changed entity (counts, SHA-256 of the file, file name, OS user). Setup writes only its own run log.
2. The file was the complete truth: grants not in the file were removed. Setup removes them only with `--remove-unlisted-grants` (platform decision 6).
3. The read-path fix: a bad grant row refused only its own entity, not the whole lookup. Without it, a bad role row still refuses the whole lookup (LV-AUT-007), as on the main branch. Setup cannot write a bad row.

The branch can be deleted.

### Step 1: two export fixes
- **One destination:** the alert reads "LexaVault can't reach Local export folder", then "Its health check is failing." The "likely LexaVault's own network" line shows only with 2 or more destinations. While the destination list has not loaded, the general title shows with no cause line.
- **Red count:** Support's red count (the header link and the Export tab) adds 1 while the alert is raised.
- **Proof:** `docs/work/0037-proof/01b-support-destination-paused.png` (retaken; the other export pictures were retaken in the same run).

### Step 2: libraries
- `backend/pom.xml` imports `com.documentplatform.platform:platform-bom` 0.1.0-SNAPSHOT.
  - infrastructure uses `platform-identity-core` and `platform-identity-jdbc`;
  - api uses `platform-identity-spring` and `platform-setup`.
  - Enterprise Identity is no longer used.
- **One visible change:** platform-identity has 15 reasons where 0.3.0 had 10.
  - Two refusals that 0.3.0 filed under `POLICY_UNAVAILABLE` now have their own reason: "Tenant has no trusted group admission policy" (`NO_ADMISSION_POLICY`) and "Trusted IdP group collection is required" (`GROUPS_NOT_A_COLLECTION`).
  - Their messages are unchanged.
  - Their code changes from LV-AUT-007 to LV-AUT-002, following the code table already approved in task 0025.

### Step 3: schema step
- `com.securedoc.install.SchemaStep` runs from the backend jar. Database settings come from the environment only and are never printed. It runs three things:
  1. the app's Liquibase changelog, through Spring's own runner, so changeset file names match what the app recorded;
  2. Flyway `db/platform/identity`, history table `idn_schema_history`;
  3. Flyway `db/platform/licensing`, history table `lic_schema_history`.
- `--runtime-user` grants DML on the `idn_` and `lic_` tables only.
- Two more commands: `status`, and `customer-installed` (exit 4 when there is no customer yet).
- **Never at startup:** `spring.flyway.enabled: false`. The app still runs its own Liquibase at startup, which finds nothing to do. Moving that is a later phase (5b).
- Scripts: `scripts/Invoke-LexaVault-SchemaStep.{ps1,sh}`.
- Docs: `docs/install/SCHEMA_STEP.md`.

### Step 4: identity storage
- `PlatformIdentityReads` serves all four sign-in reads over `PostgresIdentityLookup` and `PostgresMachineEntityBindings`, for product `LEXAVAULT`:
  - candidates;
  - grants;
  - machine bindings;
  - the single-entity binding.
- LexaVault's rules are kept:
  - its own `tenants` row must be ACTIVE;
  - a grant role must be an entity role or an alias, written exactly; anything else gives LV-AUT-007 as before;
  - a machine binds only through a JWT/OAuth2 PUSH_API source.
- The entity UUID comes from `EntityUuid.fromSlotId`.
- **Identity migration command** (`scripts/Invoke-LexaVault-IdentityMigration.{ps1,sh}`, plan|apply):
  - It copies the ACTIVE connection and grants of entities whose id is a slot's entity UUID. Other entities are listed as skipped.
  - A connection that differs from what is already stored is a conflict: it is kept, and the command exits 3.
  - It is idempotent and reports counts. It never prints a secret reference or a password.
- The old tables stay. Backlog line **D1** in `docs/product/ENHANCEMENT_BACKLOG.md`.
- **Not moved:** the R1 connection admin screen and `OidcKnownGoodRecovery` still read and write `enterprise_identity_connections` (see Questions).

### Step 5: setup, demo step, no startup inserts
- **Removed:** `LocalDemoBootstrap` and `OidcConnectionBootstrap`.
- **New files:**
  - `install/product-roles.json`;
  - `install/demo/` (licence, development key, sign-in file, role mapping, README);
  - `scripts/Setup-LexaVault.{ps1,sh}`;
  - `docs/install/SETUP.md`.
- The start scripts, the export E2E and the check scripts call `Initialize-LexaVaultDatabase`, which runs:
  1. the schema step;
  2. setup, on a fresh database only;
  3. the demo step.
- **Setup placeholders:**
  - production `trusted-keys.json`: task 0038 (the wrapper says so if it is missing);
  - notifications: task 0040;
  - integration client bindings: a later task;
  - customer keys: `--no-customer-keys` until task 0041;
  - entity time zones: setup stores them today; the app uses them from 0039.
- **What the demo step creates**, for slot `ENT-DEMO-0001` (entity `30216241-b724-5a3a-aef3-3034fd546d17`):
  - the slot's activation, named "Local Demo Tenant", under the stored licence;
  - the `tenants` row (slug `local-demo`) with 7 capabilities: upload, OCR, sensitive data detection, redaction, human review, vault, audit;
  - 3 identifier rules: Contract Number (primary, 12 digits), Member Number (11), Service Entity (3 digits, 000 to 005);
  - the default sensitive-data policy;
  - the "Local Scanner Bridge" source.
  - With `--oidc-*` (local OIDC start only), it also points the customer's LEXAVAULT login connection at the local Keycloak.
  - `ENT-DEMO-0002` stays NOT_ACTIVATED.
  - A rerun changes nothing.
- **UUIDs:** `00000000-…-0001` was replaced by the derived UUID in:
  - application config, the UI's LOCAL default, the scan bridge;
  - the start, scanner and preflight scripts;
  - the four check scripts, the acceptance helpers and the E2E stack.
- **UUIDs not changed:**
  - Self-contained Java unit tests that use `0000…0001` as an arbitrary tenant value. They do not depend on demo data, and several are the pinned P2a identity tests.
  - The E2E's per-scenario test entities `00000000-0000-4035-…`.
  - `integration-reference/`.

## Commands run and results
- **platform-core:**
  - `mvn -q -B install -DskipTests` in `C:\dev\platform-core` (HEAD `e1ed85c`, tree clean).
  - **Then another session started changing platform-core.** It made new commits up to `021f0f7`, left uncommitted changes in identity-jdbc and setup, and re-installed those snapshots into the shared `~/.m2` at 11:10. That broke builds mid-run.
  - From step 5 on, all LexaVault builds use a private repository (`-Dmaven.repo.local=<scratch>/m2-0037`). It is a copy of `~/.m2` plus platform-core **committed `021f0f7`**, exported with `git archive` and built with `mvn -q -o install -DskipTests`. The platform-core checkout was not touched.
- **Backend:** `mvn -o clean install` passes: domain 76, application 281, scan bridge 136, infrastructure 67 (37 PostgreSQL tests skipped), api 547 (16 PostgreSQL tests skipped).
- **API with PostgreSQL tests on** (throwaway `lexavault-0037-pg`, postgres:16-alpine, 127.0.0.1:55437, generated password not printed, container removed): `mvn -o clean install` in securedoc-api gives **547 run, 0 failures, 0 skipped**.
- **Infrastructure PostgreSQL tests:** 67 run, 2 failures, both pre-existing (see below).
- **Frontend:** `npm run test:standalone` passes: auth 10, vocabulary 184, ui-framework 17, oidc-signin 161, tablet 95, a11y 156.
- **Export E2E:** `scripts/Test-LexaVault-ExportE2E.ps1` passes, **8 of 8 scenarios** (2.7 min), on the new start path (schema step, setup, demo step, app).
- **Other checks:**
  - `test-tools/no-redacted-values/Invoke-NoRedactedValueCheck.ps1`: **PASS**, 0 matches.
  - `test-tools/compliance-report/Invoke-ComplianceReportCheck.ps1`: **PASS**, 116 checks, 0 failed.
  - `Test-LexaVault-R1D.ps1`: **PASS** (build, 17, 10).
- **By hand on a throwaway database:**
  - `scripts/Initialize-LexaVault-LocalDatabase.ps1` twice: the first run created everything, the second changed nothing.
  - Then `java -jar … --spring.profiles.active=local`: Liquibase "Run: 0, Previously run: 93", no Flyway at startup, `GET /api/v1/me` gives "Local Demo Tenant".
- **Not run:** `Test-LexaVault-OidcSessionContinuity.ps1`. There is no built-in password and `LV_OIDC_TEST_PASSWORD` is not set here. Its prerequisites are in place: the OIDC start now sets up the connection through the demo step, and `FreshDatabaseStartPostgresTest` proves the binding resolves to the demo entity.

## Tests: counts per module, and any failures with their output

**Pre-existing failures** (infrastructure PostgreSQL tests; they fail the same way on the base commit `8209054`):

`JdbcClaimLockAndQueueOrderPostgresTest.anotherDestinationIsNotHeldUpByAFullOne` (task 0036):
```
expected: <[9e44de3d-…, 16c20454-…]> but was: <[9e44de3d-…, c408170e-…]>
  at JdbcClaimLockAndQueueOrderPostgresTest.java:160
```
- The branch failed it 4 of 5 times.
- The base commit `8209054` failed it 3 of 3 times, on a fresh database.
- Report 0036 recorded it passing.

`JdbcPipelineJobRepositoryPostgresTest.resumingADestination…` (`NoSuchElementException` at line 253):
- It fails only in the full run, where the pipeline test classes share one database.
- It passes alone 3 of 3 on both the base and the branch.

**New tests:**
- Backend: `RequestContextResolverOncePerRequestTest` 4, `IdentityAdmissionMessagesTest` 2, `SchemaStepTest` 6, `SchemaStepPostgresTest` 2, `PlatformIdentityReadsTest` 3, `PlatformIdentityReadsPostgresTest` 6, `IdentityMigrationPostgresTest` 4, `DemoStepTest` 4, `FreshDatabaseStartPostgresTest` 4.
- Frontend: `exportRules.test.ts` +2.

**Tests changed, and why:**
- `OidcStartupAndCorsLoggingTest`: the method for `OidcConnectionBootstrap`'s skip log was removed, because the class was removed.
- `LocalDemoBootstrapScannerIntegrationTest`: deleted with its class. The demo step's scanner source is covered by `FreshDatabaseStartPostgresTest`.
- `JdbcActiveOidcMachineTenantBindingsTest`: deleted with its class (owner approved). Its two checks are now in `PlatformIdentityReadsTest`.
- `e2e-export/export.e2e.spec.ts` scenario 1: expects the new one-destination alert words and the red count of 1, as step 1 requires.
- `e2e-oidc/exports.spec.ts`, in the oidc-signin suite: the Support count goes from 2 to 3 (2 groups plus the raised alert), as step 1 requires. Nothing else in that suite changed.
- `e2e-export/stack.ts`: copies the set-up from the derived demo UUID.

## Files changed
- **Backend:**
  - `api/context/RequestContextResolver`, `security/oidc/IdentityAdmissionMapping`;
  - new package `com.securedoc.install` (SchemaStep, IdentityMigration, DemoStep, DemoAppRecords, DemoStepContext, InstallDb, InstallException, QuietLibraries);
  - `infrastructure/identity/PlatformIdentityReads`, `LexaVaultProduct`, `JdbcOidcConnectionBindingRepository` (no longer serves sign-in; bootstrap insert removed);
  - poms, `application*.yml`, scan-bridge default tenant.
- **Frontend:** `exportRules.ts`, `data.ts`, `SupportExport.tsx`, `CaseFileApp.tsx`, `api/config.ts`, the tests above.
- **Scripts:**
  - `scripts/lib/LexaVaultTools.ps1`, `Initialize-LexaVault-LocalDatabase.{ps1,sh}`, `Invoke-LexaVault-SchemaStep.*`, `Invoke-LexaVault-IdentityMigration.*`, `Setup-LexaVault.*`;
  - `Start-LexaVault-{Local,OIDC,ScannerBridge}`, `Test-LexaVault-ExportE2E.ps1`, `Build-LexaVault-All.*`;
  - four check scripts and the acceptance helpers.
- **Docs:**
  - `docs/install/{SCHEMA_STEP,SETUP,IDENTITY_MIGRATION}.md`, `install/demo/README.md`;
  - `LOCAL_RUN.md`, `README.md`, the backlog.
- **Proof:** `docs/work/0037-proof/`.

## Commits
- `99eba75` refactor(identity): task 0037 step 0, A1 and A2 redone from feature/phase2-role-mapping
- `e0ae3ce` fix(export): task 0037 step 1, the reach alert with one destination and Support's count
- `48892ef` build(identity): task 0037 step 2, platform-bom and platform-identity-* replace Enterprise Identity 0.3.0
- `7f417f2` feat(install): task 0037 step 3, the schema step with the platform Flyway runs
- `3da222b` feat(identity): task 0037 step 4, sign-in reads through platform-identity's idn_ stores
- `3dfd727` feat(install): task 0037 step 5, no startup inserts; setup with demo files and the demo step
- This report, in its own commit.

Two docs commits by the design side (`cfdfd31`, `91bb14d`) are also on this branch; I did not change them. Nothing was merged or pushed.

## Not done, and why
- **OIDC session continuity run:** it needs the owner's Keycloak password. To run it: start with `.\Start-LexaVault-OIDC.ps1 …`, then `.\Test-LexaVault-OidcSessionContinuity.ps1 -PromptForPassword`.
- **The R1 connection admin screen and the recovery runner** are not on `idn_`. Owner's decision; see Questions.
- **The Phase 1 regression environment** (`test-tools/phase1-shared-issuer`) seeds the old identity tables by SQL. Its 60 checks will no longer pass on this jar until it is moved to setup. That belongs with 0039, which makes shared-issuer the normal path.
- **The existing dev database** (`securedoc-postgres`, not running today) was not touched.
  - On its next local start, the start script will run the schema step (it creates the `idn_` and `lic_` tables there), then setup and the demo step.
  - The new demo entity is `30216241-…`. Existing data stays under the old `0000…0001` entity. Nothing is deleted, but the UI's LOCAL default no longer shows that data.
- The audit-detail and policy-estimate check scripts were updated but not run (the task did not ask for them).

## Questions for the owner
1. **The R1 connection admin screen.** The draft/test/activate/rollback screen and the known-good recovery keep test results, rollback candidates and recovery request IDs. `idn_login_connection` has no columns for these. Until it moves, a change made on that screen does not affect sign-in. Options:
   - platform-core adds draft and test fields (a platform-core task), or
   - LexaVault keeps a small side table keyed by `connection_id`.
   - *Recommendation:* the platform-core task, then move the screen in 0039.
2. **Setup takes only `https` issuers.** The local Keycloak is `http://localhost:8181`, so the demo step sets that connection for local OIDC. Should platform-core allow `http` for `localhost` in demo and test installs? *Recommendation:* yes, only for loopback hosts.
3. **`product-roles.json` has no alias field.** The task asked to keep `USER`/`REVIEWER` mapping to Document Processor, so the aliases are listed as roles in their own right, and LexaVault maps them when reading. *Recommendation:* platform-core adds an optional `aliases` map.
4. **Pre-existing test failures:** `JdbcClaimLockAndQueueOrderPostgresTest.anotherDestinationIsNotHeldUpByAFullOne` fails on this machine at the base commit too. `JdbcPipelineJobRepositoryPostgresTest` fails only when run in the full suite. *Recommendation:* a small follow-up task on the 0036 claim code and test isolation.
5. **Shared `~/.m2` while platform-core is being worked on:** a parallel session's uncommitted snapshots were installed under the same version. *Recommendation:* platform-core sessions install only committed states, or the two repositories use separate local repositories.

## Suggested next step
1. The owner runs `Test-LexaVault-OidcSessionContinuity.ps1 -PromptForPassword` on the new start path.
2. Answer questions 1 to 3.
3. Then task 0038 (licensing on platform-licensing, the production `trusted-keys.json`, importing `platform-tenant.properties`).

## Fix 1: the start path on an existing development database

Date: 2026-11-22. Task: `docs/work/0037-fix-1.md`. Branch: `feature/0037-switchover-1a`. Outcome: DONE (the OIDC session continuity script still needs the owner's password).

### What was done
1. **Existing databases work.** Only `tenants.slug` is unique in LexaVault's entity table. The old demo tenant `0000…0001` holds `local-demo` and "Local Demo Tenant". The demo step now picks a name and slug no other entity holds (`DemoNaming`):
   - when `local-demo` is taken, the new entity uses `local-demo-ent-demo-0001`; when the name is taken, "Local Demo Tenant (ENT-DEMO-0001)";
   - it prints a `note` line for each.
   - Why: the slot ID makes the values stable, readable and tells the two apart in entity lists. A fresh database keeps "Local Demo Tenant" / `local-demo`. If the fallback is taken too, the step refuses (`DEMO_SLUG_TAKEN` / `DEMO_NAME_TAKEN`) rather than guess.
   - The old tenant and its data are never written. It stays usable in LOCAL mode with `X-Tenant-Id: 00000000-0000-0000-0000-000000000001`. It has no `idn_` grants, so OIDC sign-in does not offer it.
2. **"Fresh" = the demo step has not run.** New `DemoStep installed` exits 0 when the demo entity's `tenants` row exists, 4 when not. The start helpers (`scripts/lib/LexaVaultTools.ps1`, `Initialize-LexaVault-LocalDatabase.sh`) run setup while it says 4, instead of `customer-installed`.
   - A half-failed database therefore gets setup again ("0 created … unchanged") and the demo step again.
   - If an earlier demo step activated the slot and stopped before the entity row, the slot's name is corrected to the chosen one (only when the activation was the demo step's own).
3. **OIDC start signs in through `-IssuerUri` / `-Audience`.** The demo step already pointed the login connection at the given issuer with `--oidc-issuer`, but as its last step, so the owner's failure left `demo-okta` active. With item 2 the demo step now always completes, so the connection is switched.
   - Setup is not run again once the demo step has run, so it never brings the placeholder back.
   - `Start-LexaVault-OIDC.ps1` itself is unchanged: it already passes the issuer, provider, audience and browser client to the demo step.
4. **Identity V2.** Pinned to platform-core **`0e2890f`** (committed; it contains `dd58c3f`, P029, `V2__machine_binding.sql`). The schema step runs it by its Flyway location; no LexaVault code change was needed. A V1-only database gets 1 migration ("at version 2").
   - The jar in `backend/securedoc-api/target/` is built against it.
   - LexaVault still uses its own machine-client lookup; setup's client bindings come with a later task.

### Commands run and results
- **platform-core:** `git archive 0e2890f` into the scratchpad, then `mvn -q -B -o install -DskipTests -Dmaven.repo.local=<scratch>/m2-f1` (a copy of `~/.m2` without the platform snapshots). The platform-core checkout and `~/.m2` were not changed.
  - platform-core has uncommitted changes in `platform-integration` (another session); not used.
- **Backend, standard build:** `mvn -o clean install -Dmaven.repo.local=<scratch>/m2-f1` passes: domain 76, application 281, scan bridge 136, infrastructure 67 (37 PostgreSQL skipped), API 552 (21 skipped).
- **Backend with PostgreSQL tests on** (throwaway `lexavault-0037f1-pg`, 127.0.0.1:55438, generated password not printed, removed afterwards):
  - First full run: infrastructure failed only in `JdbcClaimLockAndQueueOrderPostgresTest`. Two methods failed: `anotherDestinationIsNotHeldUpByAFullOne:160` (as in question 4 above) and `workersClaimingAtTheSameMomentNeverRunMoreThanTheLimitForOneDestination:129` ("round 0: one claim … expected: <1> but was: <0>"). This fix does not touch that code.
  - Then: all modules with that class excluded (`-Dtest='!JdbcClaimLockAndQueueOrderPostgresTest'`): domain 76, application 281, scan bridge 136, infrastructure 59, **API 553, 0 failures** (1 skipped: the demo file generator).
- **End to end on throwaway containers** (`lexavault-0037f1-pg`, `lexavault-0037f1-keycloak` with a test realm `document-platform-local` on 127.0.0.1:28181, generated passwords not printed; both removed). Script: the same `Initialize-LexaVault-LocalDatabase.ps1` call that `Start-LexaVault-OIDC.ps1` makes, pointed at the throwaway database.
  - **Owner-like database:**
    1. The jar from **`8209054`** (integration head before 0037) started on it in LOCAL, then in OIDC mode. That made the old tenant `0000…0001 local-demo`, its identifier rules and its `enterprise_identity_connections` row.
    2. The **0037 start path** (jar and scripts from `cb778f2`, platform-core `021f0f7`, identity V1) on it reproduced the owner's run: setup 13 created, then `tenants_slug_key … (local-demo) already exists`, `DEMO_STEP_FAILED`. The slot was left ACTIVE "Local Demo Tenant", with no entity row and `demo-okta` active.
    3. The **fixed start path** on that same database:
       - identity "1 migration(s) run, at version 2";
       - `DemoStep installed`: not done yet;
       - setup "0 created, 0 updated, 0 removed, 0 switched off, 8 unchanged";
       - demo step: two `note` lines, the name updated, the entity row and records created, the login connection updated to the Keycloak issuer.
       - The old tenant row, capabilities, identifier rules and old login connection rows were unchanged (compared before and after).
    4. The next start: setup not run, demo step all `unchanged`.
    5. The app in OIDC mode: a password-grant token from the test Keycloak, `GET /api/v1/me` gives 200, "Local Demo Tenant (ENT-DEMO-0001)", roles BUSINESS_ADMIN, DOCUMENT_PROCESSOR.
    6. LOCAL mode: the old entity answers with `X-Tenant-Id 0000…0001` ("Local Demo Tenant", `local-demo`).
  - **Fresh database:**
    - identity 2 migrations;
    - setup 13 created;
    - demo step creates "Local Demo Tenant" / `local-demo` (no `note`) and updates the login connection to Keycloak;
    - the second start changes nothing;
    - OIDC sign-in gives 200 "Local Demo Tenant".
- **Checks re-run:**
  - `scripts/Test-LexaVault-ExportE2E.ps1` (proof pictures to the scratchpad; 0037's pictures unchanged):
    - First run: 4 passed, then **scenario 5a failed**. The IT queue's two cases came back in swapped order (`export.e2e.spec.ts:237`, same two package IDs and case numbers). The 3 after it were skipped.
    - Rerun: **8 of 8 passed** (2.8 min). The order of that list is the 0036 queue code, the same area as question 4; not touched here.
  - `test-tools/no-redacted-values/Invoke-NoRedactedValueCheck.ps1`: PASS, 0 matches.
  - `test-tools/compliance-report/Invoke-ComplianceReportCheck.ps1`: PASS, 116 checks.
  - `Test-LexaVault-R1D.ps1`: PASS. A first try stopped on npm's stderr because of how I redirected its output in PowerShell 5.1; run as its own process it passed.
  - No frontend code changed; `npm run test:standalone` was not re-run.
- **Not run:** `Test-LexaVault-OidcSessionContinuity.ps1` (needs the owner's Keycloak password). `securedoc-postgres` and the other protected containers were not touched.

### Tests added or changed
- New `ExistingDatabaseStartPostgresTest` (4): old tenant present → `installed` says not done; the owner's half-failed state → setup "0 created", demo step finishes with the fallback name and slug, old tenant unchanged; sign-in resolves through the Keycloak issuer, not `demo-okta`; a further run changes nothing.
- `SchemaStepPostgresTest`: expects identity version 2 (2 migrations on a fresh database), and checks the runtime user can read `idn_machine_binding`. Its "cannot update the version table" check now writes 3 instead of 2, because the table is at 2. New case: a database at identity V1 gets V2.
- `FreshDatabaseStartPostgresTest`: also checks `installed` (4 before, 0 after) and that a fresh database gets no `note` lines.
- `DemoStepTest`: the `installed` options.

### Files changed
- `backend/securedoc-api/src/main/java/com/securedoc/install/DemoStep.java`, new `DemoNaming.java`;
- tests: `ExistingDatabaseStartPostgresTest` (new), `SchemaStepPostgresTest`, `FreshDatabaseStartPostgresTest`, `DemoStepTest`;
- `scripts/lib/LexaVaultTools.ps1`, `scripts/Initialize-LexaVault-LocalDatabase.{ps1,sh}`;
- `install/demo/README.md`, `docs/install/SCHEMA_STEP.md`.

### For the owner's start
- Do not rebuild before the start. The jar in `backend/securedoc-api/target/` is built from this commit against platform-core `0e2890f`. Your shared `~/.m2` holds platform snapshots that another session installed at 11:18; they also contain identity V2, but not from a known commit.
- What to expect on your database:
  - the schema step runs identity V2;
  - setup prints "0 created … unchanged";
  - the demo step prints two `note` lines and creates "Local Demo Tenant (ENT-DEMO-0001)";
  - the login connection becomes your Keycloak issuer;
  - sign-in lands on that entity.
- Your old data stays under `0000…0001`.

### Questions for the owner
1. The export E2E scenario 5a and `JdbcClaimLockAndQueueOrderPostgresTest` both failed once here on queue order. Together with question 4 above, this points at the 0036 claim and queue order code. *Recommendation:* a small follow-up task.
