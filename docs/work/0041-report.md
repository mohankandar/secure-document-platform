# Report 0041: Identity switch-over (0037) onto platform-core v0.2.0, up to date, ready to merge

Date: 2026-11-22
Branch: `feature/0037-switchover-1a`
Outcome: DONE. Not run here: `Test-LexaVault-OidcSessionContinuity.ps1` (it needs the owner's Keycloak password); the owner runs it (below).

**No other product's name or behaviour in code.** (Unchanged known exception: `SdvDeliveryAdapter`, not touched.)

## What was done

### 1. Branch brought up to date
Merged `feature/lexavault-r1b-tenant-identity` (0038, 0039, 0033, 0040) into the branch. Three conflicts:

| File | Resolution |
|---|---|
| `LocalDemoBootstrap.java` | Kept deleted (0037). 0039's change (scanner source capped at the case limit) moved to the demo step: `DemoAppRecords` now uses `CaseLimits.MAX_PAGES_PER_FILE` (500) instead of 10,000, which 0039's cap would have refused on a fresh start. |
| `LocalDemoBootstrapScannerIntegrationTest.java` | Kept deleted with its class. The demo step's scanner source is covered by `FreshDatabaseStartPostgresTest`. |
| `docs/work/v1-redaction-requirements.md` | Both rows kept: BL7 (model governance) and BL8 (rejection reason). |

Nothing 0038/0039/0033/0040 added reads identity or tenants outside platform-identity (checked: their backend changes are queue, limits, OCR, branding and the benchmark).

### 2. platform-core v0.2.0 from GitHub Packages
- `backend/pom.xml`: `platform.version` = **0.2.0** (released, tag `platform-core-v0.2.0`), `platform-bom` import; repository `github-platform-core` = `https://maven.pkg.github.com/mohankandar/platform-core` in the project POM (releases only, no snapshots). No private local repository.
- `~/.m2/settings.xml`: already held server `github-platform-core`, username `mohankandar`, password `${env.GITHUB_PACKAGES_TOKEN}` (no token in the file). Left as it was; Maven downloaded all 0.2.0 artifacts through it (`_remote.repositories` says `github-platform-core`). Documented in the new `docs/install/BUILD.md`; README points to it.
- **App classpath: identity only.** platform-setup 0.2.0 brings platform-crypto and platform-integration (which bring notifications and fields). So setup now runs from its own jar, new module **`backend/securedoc-setup`** (no code, platform-setup + JDBC driver, main class `SetupCommand`). In the app, platform-setup is test scope only. The app jar's platform-core libs are now exactly `platform-identity-core`, `-jdbc`, `-spring`, `platform-licensing` (licence read only: slots, development-key check). `Build-LexaVault-All.{ps1,sh}` fail if anything else of platform-core is in the app jar. No `platform-licensing-spring`, no licensing screen.
- Scripts: `Setup-LexaVault.*`, `Initialize-LexaVault-LocalDatabase.sh` and `scripts/lib/LexaVaultTools.ps1` run setup from the setup jar (found next to the given app jar, so a worktree build uses its own).

### What v0.2.0 changed for LexaVault
- **Identity schema V3 first.** The schema step runs identity to the latest version: fresh database 3 migrations, V1 → 2, V2 (the owner's) → 1. `SCHEMA_STEP.md` updated.
- **Role aliases.** `install/product-roles.json`: roles are the 5 entity roles; `"aliases": USER → DOCUMENT_PROCESSOR, REVIEWER → DOCUMENT_PROCESSOR, APPROVER → DOCUMENT_SUPERVISOR` (as today); interim role entries removed. Setup stores grants as the role. The identity migration now also stores the role, not the alias. A grant already stored under an alias name is still read as its role (unchanged read rule).
- **Grant history** shown on the Audit page (Case File Admin › Audit trail) as a read-only "Sign-in access changes" list next to the audit records, same period. New `GET /api/v1/audit-records/grant-history` (AUDIT_VIEW, same access as the audit records; newest first, max 200, LexaVault's product only), over `PostgresGrantHistory`. Rows read e.g. "Group BusinessUsers now gives Document Processor", by "Setup, run <id>" or "LexaVault". The migration command writes history as `lexavault-identity-migration`.
- **Customer-keys guard:** `"customerKeys": "optional"`; `--no-customer-keys` stays.
- **Local test login servers: used.** The demo key in `install/demo/development-trusted-keys.json` is now marked `"development": true` (also in `DemoFilesGenerator`), so setup takes `http://localhost...` under the demo licence. The demo step's `--oidc-*` connection workaround is **removed**. The OIDC start instead writes `.run\oidc-signin\signin-local-oidc.json` (new `com.securedoc.install.LocalOidcSignin`: the demo sign-in file with `-IssuerUri`, `-ProviderId`, `-Audience`, web client put in) and runs setup with it on every OIDC start, with `--replace-connection` so the start's issuer wins over a connection changed outside setup (the 0037 demo step wrote it as an app change). When it already matches, setup changes nothing. `Start-LexaVault-OIDC.ps1` itself is unchanged.
- **Login-connection admin screen: read-only.** `EnterpriseIdentityAdminController` keeps only `GET` (TENANT_AUTH_MANAGE) and reads the ACTIVE LexaVault connection from `idn_login_connection` (new `PlatformIdentityAdminReads`); draft/test/activate/rollback endpoints removed, so nothing writes `enterprise_identity_connections` from the screen. Never returns the secret reference. Both screens say "Sign-in connections are set through setup." (Case File Admin › Sign-in card; the legacy Authentication page is now a read-only view.)

## Commands run and results
All builds in a scratch git worktree (branch `work/0041`) so the owner's running app (8080/5173, this checkout's jars) was not touched. Throwaway containers only: `lexavault-0041-pg` (postgres:16-alpine, 127.0.0.1:55441) and `lexavault-0041-keycloak` (26.8.2, 127.0.0.1:28181, test realm `document-platform-local`), passwords generated into the scratchpad, never printed; both removed, password files deleted.

| Command | Result |
|---|---|
| `mvn -B clean install` (backend, PostgreSQL tests on via `LEXAVAULT_TEST_PG_*`) | domain 79, application 294, scan bridge 136, infrastructure 71, API 577: first run **2 failures in my new test code** (below), fixed; then API 577 → 578 (one more test), **0 failures, 0 skipped** |
| app jar / setup jar contents | app: identity-core/-jdbc/-spring, licensing (0.2.0); setup: + setup, integration, notifications, fields, crypto |
| `npm run test:standalone` | auth 10, vocabulary 191, ui-framework 17, oidc-signin 166, tablet 95, a11y 156: all pass (see failures below for the earlier runs) |
| `Test-LexaVault-R1D.ps1` | PASS |
| `test-tools/no-redacted-values/Invoke-NoRedactedValueCheck.ps1` | PASS, 0 matches |
| `test-tools/compliance-report/Invoke-ComplianceReportCheck.ps1` | PASS, 116 checks |
| `scripts/Test-LexaVault-ExportE2E.ps1` | PASS, 8 of 8 (fresh database through the new start path). It retook 0037's proof pictures; I restored them, nothing committed. |

**Start path, end to end** (scripts and jars of the worktree, throwaway containers):
- **Database prepared like the owner's** (as in fix 1): the pre-0037 jar (`8209054`, built from `git archive`) started in LOCAL then OIDC mode (old tenant `0000…0001 local-demo`, one `enterprise_identity_connections` row); then the 0037 fix-1 start path (a copy of the owner's fix-1 jar, scripts at `de5ba2b`) with OIDC: identity V2, connection `customer-oidc` Keycloak v2, last change APP. Then this branch's OIDC start:
  - schema step "identity 1 migration(s) run, at version 3";
  - setup "8 created, 0 updated, 0 removed, 0 switched off, 5 unchanged" (login connection unchanged; the Keycloak issuer's 7 grants added; the config fragment);
  - demo step all `unchanged`; old tenant unchanged (compared before/after);
  - second start: setup "0 created … 8 unchanged", demo step unchanged;
  - app (OIDC, Liquibase "Previously run: 93"): password-grant token from the test Keycloak, `GET /api/v1/me` 200 "Local Demo Tenant (ENT-DEMO-0001)", roles BUSINESS_ADMIN, DOCUMENT_PROCESSOR, TENANT_IT_ADMIN; `/admin/enterprise-identity` 200, Keycloak connection, readOnly; old `/draft` path 404; grant history 200 with 5 rows (SETUP, with run ID); no token 401.
- **Fresh database, OIDC start:** identity 3 migrations; setup 13 created with the Keycloak connection directly (no placeholder); demo step "Local Demo Tenant"/`local-demo`; second start all unchanged; stored grant roles are the 5 roles only; sign-in 200.
- **Fresh database, LOCAL start:** setup with the demo files (placeholder `demo-okta`), demo step, second start unchanged; app `GET /api/v1/me` 200 "Local Demo Tenant"; row counts unchanged by the app start; IT Admin gets the Sign-in overview (no secret) and the grant history (5 rows); Business Admin and Report Viewer get 403 on both audit lists, as on the audit records.

## Tests: counts per module, and any failures with their output
Backend (PostgreSQL on): domain 79, application 294, scan bridge 136, infrastructure 71, API 578; 0 failures, 0 skipped. Frontend as above.

Failures on the way (all fixed; no existing test was weakened):
1. First full build, API: `ExistingDatabaseStartPostgresTest.afterTheOwnersHalfFailedRunTheNextStartFinishes:168 the demo step no longer writes it ==> expected: <false> but was: <true>`: my new check also saw setup's own "update login connection" line. The check now looks at the demo step's output only.
2. Same build: `SchemaStepPostgresTest.theRuntimeUserGetsDmlOnThePlatformTablesAndNothingMore:104 Expected java.sql.SQLException to be thrown, but nothing was thrown`: my new update-refusal check ran on an empty `idn_grant_history` (row triggers do not fire). The test now inserts a row as the runtime user first (proves INSERT works), then update and delete are refused.
3. `npm run test:standalone`, first run: `uiFrameworkContract.test.ts` "AuthenticationAdminPage.tsx must place Administration navigation after its title through PageScaffold": I had passed `primaryNavigation` inside a spread. Fixed in the page (prop written out); test unchanged.
4. Second run (while the backend build ran in parallel): `e2e-oidc/reports.spec.ts:268` "under an hour the estimate reads in minutes" timed out at 1.0 m. Not touched by this task; passed alone (20/20) and in the final full run.

New tests: `LocalOidcSigninTest` 2, `GrantHistoryAndSignInViewTest` 4, `SchemaStepPostgresTest.aDatabaseAtIdentityVersion2GetsVersion3`, `IdentityMigrationPostgresTest.anAliasGrantIsCopiedAsItsRoleAndRecordedInTheGrantHistory`, `ExistingDatabaseStartPostgresTest.aConnectionTheOldDemoStepWroteIsKeptWhenEqualAndReplacedWhenNot`; frontend `auditRules.test.ts` +1, `adminAudit.spec.ts` +1 (the list, read-only, same period), `adminSignIn.spec.ts` (the note).

Tests changed, and why:
- `EnterpriseIdentityAuthorizationContractTest`: it required the draft/test/activate/rollback methods to exist; the task makes the screen read-only, so it now requires that no writing endpoint exists and the overview still needs TENANT_AUTH_MANAGE.
- `DemoStepTest`: `--oidc-*` are now unknown options (still refused, exit 2); product roles parsed with platform-setup's `ProductRoles` (roles, aliases, `customerKeys` optional, same names as `LexaVaultProduct`); the demo key is a development key.
- `FreshDatabaseStartPostgresTest`, `ExistingDatabaseStartPostgresTest`: the OIDC connection now comes from setup with the generated sign-in file, not from the demo step; grant history checked.
- `SchemaStepPostgresTest`: identity version 3.
- `e2e-a11y/harness.ts`: answers the new grant-history call.

## Files changed
- Backend: `pom.xml`, `securedoc-api/pom.xml`, new module `securedoc-setup/pom.xml`; `install/DemoStep`, `DemoAppRecords` (merge), `IdentityMigration`, new `install/LocalOidcSignin`; `api/identity/EnterpriseIdentityAdminController`; new `api/controller/GrantHistoryController`; new `infrastructure/identity/PlatformIdentityAdminReads`; tests above.
- Frontend: `api/client.ts` (write calls removed, `listGrantHistory`), `types/index.ts`, `studio/admin/AuditArea.tsx`, `auditRules.ts`, `IdentityArea.tsx`, `pages/AuthenticationAdminPage.tsx`; tests above.
- Install and scripts: `install/product-roles.json`, `install/demo/development-trusted-keys.json`, `install/demo/README.md`, `scripts/lib/LexaVaultTools.ps1`, `scripts/Initialize-LexaVault-LocalDatabase.{ps1,sh}`, `scripts/Setup-LexaVault.{ps1,sh}`, `Build-LexaVault-All.{ps1,sh}`.
- Docs: new `docs/install/BUILD.md`; `docs/install/SETUP.md`, `SCHEMA_STEP.md`, `IDENTITY_MIGRATION.md`, `README.md`; `docs/work/v1-redaction-requirements.md` (merge).

## Commits
See `git log` on `feature/0037-switchover-1a`: the merge commit (step 1), the platform-core 0.2.0 commit, and this report. Not merged into the integration branch, not pushed.

## Not done, and why
- **OIDC session continuity**: needs the owner's Keycloak password (below).
- **`OidcKnownGoodRecovery`** (opt-in startup runner) still writes `enterprise_identity_connections`; the task named only the screen. It does not affect sign-in. `OidcDiscoveryTester` is now unused; left in place (deleting files needs the owner's yes).
- The legacy (non-Case-File) Audit page does not show the grant history; the Case File Audit trail does. The legacy Authentication page is read-only.
- The demo-okta grants on the owner's database were added before identity V3, so they have no history rows; the Keycloak-issuer grants the next start adds do.
- `scripts/test-lexavault-export-e2e.sh` (Linux E2E) still does not run setup itself (as before this task); not touched.

## Questions for the owner
1. **Setup on every OIDC start, with `--replace-connection`.** That is what makes the start's `-IssuerUri` the sign-in connection without the demo-step workaround. It only touches a local development database through the demo files. OK to keep? *Recommendation:* yes.
2. **Delete `OidcDiscoveryTester` and, later, the R1 tables' admin code (`JdbcOidcConnectionBindingRepository` write methods, `OidcKnownGoodRecovery`)?** *Recommendation:* yes, with the platform-core drafts task, so the old table has no writer left.

## Suggested next step
1. The owner runs, from `C:\dev\lexavault\secure-document-platform`:
   - `.\Start-LexaVault-OIDC.ps1 -IssuerUri http://localhost:8181/realms/document-platform-local -Audience lexavault-api -ScannerMode SIMULATOR -AiModel qwen3.5:9b`
   - then `.\Test-LexaVault-OidcSessionContinuity.ps1 -Username businessuser -PromptForPassword`.
   Rebuild first (`.\Build-LexaVault-All.ps1`): the jars in this checkout are still the fix-1 ones. Expected on your database: schema step "identity 1 migration(s) run, at version 3"; setup "8 created … 5 unchanged" (your Keycloak's grants), connection unchanged; demo step all unchanged.
2. The design chat reviews and merges.
