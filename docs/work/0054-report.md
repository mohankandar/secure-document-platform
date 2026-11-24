# Report 0054: Identity bump to platform-core v0.7.0

Date: 2026-11-24
Branch: `feature/0054-identity-0.7.0`, from 0053's branch (PR #2, approved, not merged yet)
Outcome: **DONE** (4 decisions, answered below). Built by a subagent from a written brief; I read the sign-in, licence-key, build and settings parts of the change and ran the full proofs myself. Lines marked (agent) were run by the subagent only.

## What was done
- **Version:** platform-core 0.7.0 for identity and the setup jar. In the app jar: identity-core, -jdbc, -spring, licensing, outbound-spring, outbound, errors. Both build scripts check exactly these. HttpClient is pinned to the versions platform-core 0.7.0 is built with (Spring Boot's list would take it back to older ones).
- **Licence keys are in the build (SEC-12).** A plain build is a **production** build and carries no key yet, so it refuses every licence. `-Pdev-build` (what `Build-LexaVault-All` passes unless `-Production`) carries the demo's development key. `--keys` is gone from setup and the demo step. The local start refuses a jar that is not a development build.
- **Schema step:** identity V5, licensing V2. `--runtime-user` grants follow 0.7.0 exactly. A runtime user that owns the tables is refused (`RUNTIME_USER_OWNS_TABLES`).
- **Platform-wide grant:** `PLATFORM_OPERATIONS_ADMIN` through a `*` row in the demo mapping. An entity role in a platform grant, or the platform role in an entity grant, refuses sign-in; so do grants that can't be read.
- **Test sign-in** on the login-connection screen: no input, uses the stored connection only, changes nothing, IT Admin only, one call at a time, audited with codes only. Internal addresses stay off by default.
- **SEC-7: a person's token was accepted as a machine caller** on the intake path. It is now refused (`LV-AUT-008`). The list of claims that mark a person can be changed, not switched off.
- **SEC-44:** group names were trimmed and text was split on commas; they are now compared exactly.

## Commands run and results
| Command | Result |
|---|---|
| `mvn -B -o -Pdev-build clean install`, PostgreSQL tests on | BUILD SUCCESS: domain 79, application 304, scan bridge 136, infrastructure 115, API 679 (1 skipped, the timing test), 0 failures |
| `mvn -B -o clean install` (production) (agent, before the last small change) | BUILD SUCCESS, API 678, 0 failures |
| `npm run test:standalone` | 10, 193, 17, 169, 95, 156 passed, 0 failed |
| No-redacted-value, compliance report, export end-to-end, inline / staged | all PASS |
| Product-name guard | clean (1690 files) |
| `Build-LexaVault-All.ps1` and `.sh`, development and production, tests skipped (agent) | all pass, jar checks included |
| Development jars (agent): fresh LOCAL and OIDC (throwaway Keycloak), LOCAL to OIDC, second start | schema V5 / V2, setup and demo step work; a second start changes nothing |
| Sign-in (agent): platform operations by the grant alone; IT Admin test sign-in 200; processor and platform operations 403 on it; no token 401; a person on intake 403 | as listed |
| Production jars (agent): setup with no keys file | `SETUP_KEYS_INVALID: META-INF/platform/licence-keys.json is missing from the app's build …`, exit 2 |
| Production jars (agent): setup with another (production) key | `SETUP_LICENCE_REFUSED: LICENCE_UNKNOWN_KEY_ID … Key ID 'dev-lexavault-demo-1' is not among the trusted keys`, exit 2 |

Schema: `SchemaStepPostgresTest` covers a fresh database and one at identity V3 / licensing V1 with the old grants, the exact rights per table, and every revoked action refused. The V3/V1 state was made from the 0.7.0 migration files, not the 0.2.0 ones.

**Not proven:** no test sign-in passed end to end over **https**. Norton intercepts TLS on this laptop; with its certificate trusted, discovery passed and the key host then failed. Over http to a local Keycloak it passes. The failure codes were each seen from the jar.

## Existing tests changed
Versions and renamed steps (`SchemaStepPostgresTest`); no `--keys`, and exit 3 on a login-system switch, which is new 0.7.0 behaviour (`FreshDatabase…`, `ExistingDatabaseStartPostgresTest`, `DemoStepTest`); library types that became internal (`LocalOidcSigninTest`, `DemoFilesGenerator`); the new refusal text (`IdentityAdmissionMessagesTest`); exactly one POST allowed on the sign-in screen (`EnterpriseIdentityAuthorizationContractTest`, `adminSignIn.spec.ts`); constructors and helpers (`GrantHistoryAndSignInViewTest`, the two resolver tests; their existing cases are untouched).

## Files changed
71 files, +1945 / -206.

## Commits (hash and message), or "not committed"
- `01c710a` feat(identity): platform-core 0.7.0: licence keys in the build, platform-wide grants, test sign-in
- the report commit

## Not done, and why
- The Audit page does not list platform-wide grant changes (only entity rows, as before).
- `CLAUDE.md` still says `mvn clean install` for the backend. That is now a production build, which the demo refuses.
- The phase 1 regression environment was not run. Its database is likely older than identity V5, so its app may refuse to start until the schema step runs there.
- The two Linux setup scripts were only syntax-checked.

## Questions for the owner
1. **A module the decision did not list:** platform-outbound declares `platform-notifications` (for a proxy password). It is excluded, with LexaVault's own wiring instead, so a proxy with sign-in is refused at start and a library runs without one declared part. Keep it so, or allow that module and use platform-core's wiring? Recommended: allow it.
2. **The configured `PLATFORM_OPERATIONS_ADMIN` mapping** still works beside the grant. Remove it?
3. **Production licence keys** must be added to `backend/licence-build/production` before any customer build. Who provides them, and when?
4. **`CLAUDE.md`:** change the backend command to `mvn -Pdev-build clean install`?

## Decisions (2026-11-24, design chat, as recommended under LV-19)
1. `platform-notifications` stays excluded for now; a proxy with sign-in is refused at start. Revisit when notifications are integrated.
2. **Done on this branch:** the configured `PLATFORM_OPERATIONS_ADMIN` mapping is gone (`application.yml`, `Start-LexaVault-OIDC.ps1`), and one that is still configured gives nothing. Test: `aConfiguredMappingNeverGivesThePlatformRole`; `eachSupportedAdministratorRoleAuthenticatesWithoutLegacyAdmin` no longer expects the platform role from a mapping.
3. Production licence keys: an owner item, before the first customer build.
4. **Done on this branch:** `CLAUDE.md` names `mvn -Pdev-build clean install` for local builds.

After these two changes the full backend build was run again: BUILD SUCCESS, 0 failures (API 680, +1). The frontend suite and the proof scripts above were run before these two changes and not repeated; CI covers the frontend.

## For the owner
- **An installation that gives platform operations through the configured mapping loses it at this upgrade** until setup has the platform-wide grant. On your machine the next OIDC start's setup adds it.
- After the merge: rebuild with `Build-LexaVault-All.ps1`, then `Start-LexaVault-OIDC.ps1 …`, then `Test-LexaVault-OidcSessionContinuity.ps1 -Username businessuser -PromptForPassword`. Expected at that start: identity 2 migrations to V5, licensing 1 to V2, setup creates 1 platform grant. Not run on your data.
- A LOCAL-only start does not run setup again, so it does not get the new platform grant; an OIDC start does.

## Suggested next step
Merge, then task 0056.

No other product's name or behaviour in code.
