# Report 0058: platform-core v1.0.0, part 2: entity screens, time zone, shared sign-in

Date: 2026-11-24
Branch: `feature/0058-platform-v1-part2`, from 0057's branch (merged since); the integration branch (0062, 0063, 0064, 0068, 0065 report) is merged into it
Outcome: **DONE** (8 questions). Built and tested by a subagent from a written brief. I read the admission code and the table drop, checked the main build logs, looked at two proof pictures (entity switch, sign-in draft) and did the merge. **The commands in the first table were run by the subagent, not repeated by me.**

## What was done
1. **Entity slots** (Admin > Entities, Business Admin): activate, name, suspend, resume. A person manages the entity they are in, and other slots where their own groups give BUSINESS_ADMIN. They cannot suspend the entity they are in. Slot, entity row and audit are one transaction; names are never in the audit. A suspended entity is refused on every request and takes nothing in; work already taken in goes on.
2. **Last-used entity:** sign-in lands there; the chooser shows only without one, or when it is not allowed. Entity and switch are in the top bar. Every switch is recorded before the screen changes. Upload and scan results name the entity.
3. **Time zone:** the entity's, else the customer's. Used for daily polling, reports, the compliance report and shown times. Retention timers stay elapsed hours, so a zone change never moves a deletion. Fixed on the way: a daily schedule stayed shifted after a "clocks forward" day.
4. **Login connection:** draft, test sign-in, switch, go back. Tokens are never stored, logged or returned. `OidcDiscoveryTester`, `OidcKnownGoodRecovery` and the old connection writers are deleted.
5. **Shared sign-in (owner's word, 2026-11-24):** platform-core admission is the only OIDC path, for one or several entities. Removed: the R1B setting, `tenant-id`, `tenant-claim`, `role-mappings` and their code, the 037 file and its test. `persistent-connection-enabled=false` refuses everyone. `X-Selected-Tenant-Id` was missing from the OIDC CORS headers; added.

**Migrations:** 061 `person_last_entity`; 062 drops `enterprise_identity_connections`, and stops while a needed connection was not carried over. `enterprise_identity_connection_history` (the only record of old changes) and `tenant_oidc_group_grants` stay. **Dependencies:** none new. No registered changeset edited.

## Commands run and results
On `4afe54c` or just before it (subagent):

| Command | Result |
|---|---|
| `mvn -B -Pdev-build clean install`, PostgreSQL tests on | SUCCESS: domain 81, application 303, scan bridge 136, infrastructure 110, API 774 (1 skipped), 0 failures |
| `npm run test:standalone`, 1 run | 0 failures: 10, 210, 17, 184, 95, 164 |
| No-redacted-value check | PASS, 0 matches |
| Compliance report check | PASS, 115 checks |
| Product-name guard | clean (1723 files) |
| Start paths, throwaway PostgreSQL and Keycloak | ALL PASS, 56 checks: fresh LOCAL; a 0057 database in LOCAL then OIDC with removed settings still set; fresh OIDC with two entities (people, refusals, machines, suspend, switch, draft) |

The no-value, compliance and start-path runs used a jar built before the last commit; only tests, pictures and the Playwright config changed after it.

After merging the integration branch (me):

| Command | Result |
|---|---|
| `npm run build`, `npm run test:vocabulary` | built; 211 pass (after 0062 and 0063, and again after 0064 and 0068) |
| `mvn -B -Pdev-build clean install`, PostgreSQL tests on | after 0062 and 0063: first run **2 failures** (below), then SUCCESS (85, 307, 136, 118, 785). After 0064 and 0068: SUCCESS: domain 85, application 318, scan bridge 136, infrastructure 126, API 804 (1 skipped), 0 failures |
| Playwright `entitySwitch adminSignIn adminEntities reports`; `npm run test:ui-framework` | 46 pass; 19 pass (after 0064 and 0068) |

Merge conflicts: the changelog (061, 062, 070, 080 all kept) and rollback counts in 5 migration tests (two more change sets each).

**Left for milestone M1:** two-server proof, export end-to-end, repeats. **Not repeated after the merge:** start paths, no-value check, compliance check, the other browser suites (CI runs them). The last merge (0065) holds documents only and was not built again.

## Failures met (fixed)
- `AppendixDetailAllowListTest` (new actions were wrongly allowed), the generated codes file, contrast of a red tag, a missing `LoginConnections` bean at start, wrong values in new tests, browser-test ports in use by another session.
- Steps 1 to 3 were committed by path while the next step's files were in the tree: those exact trees were not built alone. The full build ran only at the end.
- After the merge, `FreshDatabaseStartPostgresTest` failed twice: its cleanup deleted from `tenant_redaction_kinds`, which 0062 drops (the second failure followed from it). The cleanup list now names the tables that replaced it; no check changed.

## Existing tests changed or removed
- **Removed with their code:** `OidcDiscoveryTesterTest`, `EnterpriseIdentityCheckpointCContractTest`, `EnterpriseIdentityPreCheckpointCVersionGuardContractTest`, `SharedIssuerActivationCompatibilityTest`, `SharedIssuerMigration037ContractTest`. Version, activation and rollback guards: now `LoginConnectionServiceTest`.
- **New rule:** `SharedIssuerModeConfigurationTest` (no setting; old settings ignored); `RequestContextResolverSharedIssuerTest` (no flag, 2 cases added); `RequestContextResolverSecurityTest` (grants instead of mapping; `aConfiguredMappingNeverGivesThePlatformRole` removed, no mapping exists; NO_MAPPED_ROLE is now NO_VERIFIED_MEMBERSHIP); `EnterpriseIdentityAuthorizationContractTest` ("no writing endpoint" became "every endpoint needs the IT Admin and takes no connection, customer or entity").
- **Moved or adjusted:** `GrantHistoryAndSignInViewTest`, `SignInConnectionTestTest`, `RequestContextResolverOncePerRequestTest`, `AuditActorNameCaseIdApiTest`, `PlatformIdentityReads` tests, `ExistingDatabaseStartPostgresTest`, `FreshDatabaseStartPostgresTest`, `IdentityMigrationPostgresTest` (recreates the dropped table), 5 migration tests (counts), `entitySwitch.spec`, `adminSignIn.spec`, an a11y region name.
- **Cases only added:** `PlatformLicenceTest`, `OidcTenantDiscoveryControllerTest`, `PollingScheduleCalculatorTest`, `IntegrationConnectionServiceTest`, `ComplianceReportServiceTest`.

## Behaviour and default changes
- **OIDC roles come only from the grants setup loads.** A role that came only from `role-mappings` is gone: check the mapping file before upgrading. Removed settings are ignored.
- `Start-LexaVault-OIDC.ps1` has no `-TenantId` or `-TenantClaim` any more.
- Push callers in OIDC need an enabled PUSH_API connection (JWT or client credentials) naming their client.
- A switch to another issuer, provider ID or audience is refused (`SIGN_IN_SETTINGS_DIFFER`): this server decodes one issuer's tokens, so it would lock everybody out.
- A daily polling connection without its own zone silently stops if the entity's zone is later cleared.
- An install from before 0037 runs the identity migration of the 0057 release first.
- `/me` has `timeZone`, `/me/tenants` has `lastUsed` and works with one entity, the reports CSV has "Time zone of the days".

## Not done or not proven
- The test sign-in over https to a real login system, and the browser against a real Keycloak (API level only here; the continuity check on the development Keycloak follows on the merged head).
- `test-tools/phase1-shared-issuer` cannot run with this jar (it uses 037 and the old tables).
- The first entity of a new customer still needs the demo step or a command.
- The customer time zone is only shown; setup sets it.
- A caller that uses only the header can work in another allowed entity without the "entered" record; its actions are audited there.
- Scanner intake in OIDC looks impossible, before this task too (boundary wants a person, controller wants a machine). By reading; not tested, unchanged.

## Questions for the owner (the recommended answer was taken)
1. Another issuer from the screen: keep refused (recommended), or a later task (frozen sign-in area)?
2. `enterprise_identity_connection_history`: keep (recommended)? `tenant_oidc_group_grants`: drop later?
3. A small install command for a customer's first entity? Yes (recommended).
4. Other slots are managed only with BUSINESS_ADMIN in that slot's own grants (recommended and built). Accept?
5. Phase 1 environment: retire (recommended; a two-entity start-path script exists) or rebuild? `CLAUDE.md` "Known state" changes with it.
6. Details of the new audit actions in the compliance appendix: left off until reviewed. Accept?
7. Retention stays elapsed hours; only shown times use the zone. Accept? (recommended)
8. Scanner intake in OIDC as its own task? Yes (recommended).

## PLATFORM-CORE
`LoginConnections.activate` and `rollback` do not know which issuer the app decodes, so an app with one fixed decoder can lock everybody out and then cannot reach the rollback screen; the guide does not say so. Draft IDs carry no customer, so each app must check the owner. By reading; not reproduced; nothing patched.

## For the owner
After the merge: `Build-LexaVault-All.ps1`, then start as usual (without `-TenantId`). 061, 062 and, if new to your database, 070 and 080 run once.

## Commits
`3eca8e9`, `1c9d28d`, `eef39f3`, `c66a8aa`, `e87ca55`, `4afe54c`, the merges and the report commit.

## Suggested next step
Task 0059.

No other product's name or behaviour in code (the known exception, `SdvDeliveryAdapter`, is untouched).
