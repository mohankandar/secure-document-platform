# Report 0075: install command for the first entity; scanner intake with sign-in

Date: 2026-11-25
Branch: `feature/0075-first-entity`, integration branch merged in (up to 0061, PR #28)
Outcome: **DONE.** Start path on throwaway containers: 21 of 21 checks pass.

## What was done
1. **First entity.** `scripts/Invoke-LexaVault-FirstEntity.ps1` / `.sh` (slot, name) start the app with `--activate-first-entity`; it activates and names the slot, then ends. Such a process takes no work. Only while no entity was ever activated, and with a licence in force that holds the slot. Again with the same values: changes nothing. It never renames, resumes or adds an entity (that is Admin, Entities). Exit 0 done, 1 failed, 2 refused. Audited in the entity's log with `source=INSTALL` and a fixed install actor; the name is not in the audit.
2. **Scanner intake with sign-in.** The boundary check asked for a person on the scanner routes, so every scan was refused. It now takes a machine there, bound through an enabled SCANNER source (JWT or client credentials) of an active entity. A push client is nobody on the scanner routes, and a scanner client nobody on the push routes; a person's token is refused; two entities for one client is refused. No header chooses the entity. A refusal on these routes is now audited as the machine's.
3. **The Scan Bridge signs in itself** (client credentials): until now it could only hold a fixed token, which ends. Settings `SECUREDOC_SCAN_BRIDGE_OIDC_TOKEN_URI`, `_CLIENT_ID`, `_CLIENT_SECRET`, `_SCOPE`. https, or http on this machine, only. Token in memory only. Incomplete settings send nothing (never the local headers instead). The fixed token still works.
4. **The entity is named** in every scanner answer (`entityName`). The scan screen already names the entity only when the case is in it; unchanged.

Install guide: `docs/install/SETUP.md`. No migration, dependency, new error code or frontend change. **No other product's name or behaviour in code.**

## Commands run and results
Isolated copy of the merged tree, nothing installed (rule 11).

| Command | Result |
|---|---|
| `mvn -B -o -Pdev-build verify -fae`, PostgreSQL on (throwaway, removed) | BUILD SUCCESS: domain 83, application 372, scan bridge 143, infrastructure 156, API 929 (3 skipped), 0 failures |
| no-redacted-value check (its own script) | PASS, 0 matches |
| product-name guard | clean |
| `node test-tools/first-entity/first-entity.mjs <test-users.env>` | ALL PASS, 21 checks |

The start path: throwaway PostgreSQL, Keycloak and Vault (removed); schema step and setup, no demo step; the command; the test users of the secrets file, made in that Keycloak (never printed; the development Keycloak was not used); sign-in lands on the entity; the refusals above; a simulated scan through the bridge's sign-in; no secret or token in a log or a bridge file. It ran on the jars of the build above; after that build only this script changed.

## Failures on the way
- **`JdbcPipelineJobRepositoryPostgresTest.aPausedDestinationsJobsAreNotTakenAndComeBackInOrder` failed once** (line 166, 0 instead of 1) in an earlier build. Then 12 of 12 repeats passed, and the later full build. This task does not touch it. It compares the database's clock with itself with no interval, so a small backward clock step in the container would explain it; not proven. Test unchanged.
- Start path, fixed: an app with sign-in needs a key service (so a throwaway Vault); a new entity has no case fields; after 0061 was merged, the field type in the script; the success message repeated a "could not be changed" text.

## Existing tests changed
`RequestContextResolverSharedIssuerTest` (its machine store answers for scanners too; one test added), `PlatformIdentityReadsPostgresTest` (cases added), `ClientMistakeResponsesTest` (constructor). No check weakened. New: `FirstEntityPostgresTest`, `TenantBoundaryInterceptorTest`, `BridgeSignInTest`.

## Not proven
- The `.sh` wrapper was not run (the `.ps1` was). A physical scanner with sign-in was not tried.
- With a fixed token, a 401 still needs a person, as before.

## For the owner
- **A new entity needs case fields (Admin, Case fields) before its first scan or upload**, otherwise intake refuses it. Should the install guide list the first steps after the command? (recommended: yes, small)
- Existing scanner sources made for local use have authentication NONE. With sign-in they must be changed to client credentials, and their client principal to the bridge's client. (recommended and documented)
