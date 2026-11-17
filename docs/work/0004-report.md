# Report 0004: Phase 1, sitting 1 (build the test environment, run points 1 and 2 and the refusals)

Date: 2026-11-17
Branch: `test/phase1-shared-issuer` (from `feature/lexavault-r1b-tenant-identity` at `49a8a70`)
Outcome: PARTLY DONE. The environment was built and all checks ran: 46 passed and 1 failed (R-08, see below). Per the task, I stopped there: no product code was changed and no check was weakened.

## What was done

1. **Pre-checks**
   - **Normal backend:** not running (nothing listening on 8080).
   - **Build:** skipped. The existing jar was built at 14:44 by the Phase 0 build from the tree later committed as `30e318a`. `git diff 30e318a HEAD -- backend` is empty and `backend/` has no uncommitted changes, so the jar was built from the current code and was reused. No `mvn clean` was run.
   - **Keycloak image:** `docker inspect lexa-keycloak` (read-only) shows `quay.io/keycloak/keycloak:26.8.2`, the same tag Phase 1 uses.
   - **Heuristic AI provider:** accepted. `ApplicationServiceConfig.sensitiveContentAnalysisPort` builds the deployment-wide default profile with `allowHeuristic=true` (`ApplicationServiceConfig.java:591`), whatever the Spring profile. Heuristic is refused only for per-tenant AI profiles and for the secondary/failover provider (`allowHeuristic=false` at :638 and :693). Point 3 is not run in this sitting.
2. **Files:** wrote the Phase 1 files under `test-tools/phase1-shared-issuer/` (list below), with the owner's changes:
   - no password anywhere in the repository; all passwords are generated into `C:\dev\lexavault\phase1-run\secrets\`;
   - containers bound to 127.0.0.1;
   - tokens kept in memory only;
   - a second realm inside the throwaway Keycloak for the wrong-issuer check.
3. **Environment:** started the throwaway PostgreSQL and Keycloak. Started the Phase 1 backend on 18080, with migration 037 applied through the test-only changelog. The Liquibase `file:` changelog worked, so the fallback was not needed.
4. **Proof:** confirmed 037 is applied in the throwaway database only, the product master changelog is unchanged, and the normal database was never connected to.
5. **Seed:** seeded the two entities.
6. **Checks:** ran the scripted checks for point 1, point 2 and the refusals.
7. **Left running:** the environment is up for inspection.

**Mistakes in my own test scripts, found and fixed during the run.** Both were in test files; no product code was involved.
- **Jar check:** the start script's first check compared timestamps. Branch checkouts had rewritten `backend/pom.xml`'s timestamp without changing its content, so the check wrongly refused the jar. It now requires `-JarBuiltFromCommit <sha>` and checks with `git diff` that `backend/` is unchanged since that commit.
- **Compose arguments:** the compose helper passed loose arguments, so PowerShell read `-d` as its own `-Debug` (and would have read `-v` as `-Verbose`). That made `docker compose up keycloak` run attached and hang. The helper now takes the arguments as one explicit array.

## Commands run and results

| Command | Result |
|---|---|
| `git checkout -b test/phase1-shared-issuer` | Branch created |
| `docker inspect lexa-keycloak --format '{{.Config.Image}}'` (read-only) | `quay.io/keycloak/keycloak:26.8.2` |
| PowerShell parser check of the 4 scripts; JSON and XML validation of the realm and changelog files | 0 parse errors; all valid |
| `.\test-tools\phase1-shared-issuer\Start-Phase1.ps1 -JarBuiltFromCommit 30e318a` | Ready: containers up, backend UP in 12.3 s, 037 proven, seed applied (output below) |
| `.\test-tools\phase1-shared-issuer\Invoke-Phase1Checks.ps1` | 46 passed, 1 failed. Results: `C:\dev\lexavault\phase1-run\results\checks-20261117-162237.json` |
| Scan of `phase1-run\results` and `phase1-run\logs` for JWT-shaped strings and for each generated password | 0 and 0 |

Start script output (no secrets in it):

```
[phase1] Keycloak issuer confirmed: http://localhost:18181/realms/lexavault-phase1
[phase1] Test user passwords set (stored only in the secrets folder).
[phase1] Starting Phase 1 backend on port 18080 (shared-issuer setting ON for this process only).
[phase1] Backend is UP.
[phase1] Backend established connections to remote ports: 15432, 18181, 60268
    changeset_037=1
    changeset_037_file=db/changelog/037-r1b-shared-issuer-active-bindings.xml
    changesets_total=78
    uq_eic_active_binding=absent
    uq_eic_active_per_tenant=present
[phase1] Seeding the two entities (test-only seed).
    tenants|2
    active_connections|2
    grants|6
    capabilities|14
```

On the remote ports listed: 18181 was a lazy fetch from Keycloak. 60268 was the start script's own health-check client connecting in on 18080. The detailed connection list below shows this.

### Proof for step 3: migration 037 applied to the throwaway database only

- `databasechangelog` in `lexavault_phase1` has the row `037-r1b-shared-issuer-active-bindings` from `db/changelog/037-r1b-shared-issuer-active-bindings.xml`, with 78 change sets in total.
- `uq_eic_active_binding` is absent. `uq_eic_active_per_tenant` is present.
- The seed refuses to run while `uq_eic_active_binding` exists and in any other database. It ran, and two entities now have ACTIVE connections on the same provider, issuer and audience.
- 037 was applied only through `--spring.liquibase.change-log=file:.../test-tools/phase1-shared-issuer/db/phase1-changelog.xml`, which only `Start-Phase1.ps1` passes.

### Proof for step 4: master unchanged, and the normal database never connected to

- **Master changelog:** `git status` shows no change to `db.changelog-master.xml`. It contains no `037`. Its last change is `ed4f61c` (2026-10-28, registering 036). `git diff HEAD -- backend` is empty.
- **Database connections:** all 10 established connections of the backend process (PID 41504) go to `127.0.0.1:15432`. None goes to 5432. From `Get-NetTCPConnection -OwningProcess 41504`:

```
127.0.0.1  49278..49288  ->  127.0.0.1  15432  Established   (10 connections)
::         18080                                Listen
```

- **Backend log:** it contains no `:5432` (0 matches).
- **Start script guards:** the JDBC URL must be exactly `jdbc:postgresql://127.0.0.1:15432/lexavault_phase1`, and the script stops if the backend has any connection to 5432.
- **The normal database was not running.** `securedoc-postgres` was `Exited` for the whole run, so nothing was listening on 5432 at all.

## Tests: counts per module, and any failures with their output

No Maven build was run; the jar was reused. Phase 1 check results:

| Id | Check | Expected | Actual | Result |
|---|---|---|---|---|
| S-01 | Setup: one package created in each entity by its own admin | A and B package ids | A and B package ids | PASS |
| P1-01 | alice: /me/tenants lists only entity A | 200 [phase1-entity-a{BUSINESS_ADMIN,DOCUMENT_SUPERVISOR,TENANT_IT_ADMIN}] | same | PASS |
| P1-02 | alice: /me without a choice opens entity A | 200 phase1-entity-a{BUSINESS_ADMIN,DOCUMENT_SUPERVISOR,TENANT_IT_ADMIN} | same | PASS |
| P1-03 | alice: /me choosing entity B is refused | 403 | 403 | PASS |
| P1-04 | alice: open B's package without a choice | 403 or 404 | 404 | PASS |
| P1-05 | alice: open B's package choosing B | 403 | 403 | PASS |
| P1-06 | alice: package list shows A, not B | 200, own listed, other not listed | same | PASS |
| P1-07 | bob: /me/tenants lists only entity B | 200 [phase1-entity-b{BUSINESS_ADMIN,DOCUMENT_SUPERVISOR,TENANT_IT_ADMIN}] | same | PASS |
| P1-08 | bob: /me without a choice opens entity B | 200 phase1-entity-b{…} | same | PASS |
| P1-09 | bob: /me choosing entity A is refused | 403 | 403 | PASS |
| P1-10 | bob: open A's package without a choice | 403 or 404 | 404 | PASS |
| P1-11 | bob: open A's package choosing A | 403 | 403 | PASS |
| P1-12 | bob: package list shows B, not A | 200, own listed, other not listed | same | PASS |
| P2-01 | carol: /me/tenants lists A and B | 200 [phase1-entity-a{DOCUMENT_SUPERVISOR}; phase1-entity-b{DOCUMENT_SUPERVISOR}] | same | PASS |
| P2-02 | carol: /me without a choice is refused (no implicit pick) | 403 | 403 | PASS |
| P2-03 | carol: choose A | 200 phase1-entity-a{DOCUMENT_SUPERVISOR} | same | PASS |
| P2-04 | carol in A: package list shows A, not B | 200, own listed, other not listed | same | PASS |
| P2-05 | carol: switch to B (same token) | 200 phase1-entity-b{DOCUMENT_SUPERVISOR} | same | PASS |
| P2-06 | carol in B: package list shows B, not A | 200, own listed, other not listed | same | PASS |
| P2-07 | carol: switch back to A | 200 phase1-entity-a{DOCUMENT_SUPERVISOR} | same | PASS |
| P2-08 | carol in A: open B's package | 403 or 404 | 404 | PASS |
| P2-09 | carol in B: open A's package | 403 or 404 | 404 | PASS |
| P2-10 | carol in A: business-admin settings refused (no admin role leaks in) | 403 | 403 | PASS |
| R-01 | carol: choose the demo tenant (exists, ACTIVE, no grant) | 403 | 403 | PASS |
| R-02 | alice: choose a random UUID | 403 | 403 | PASS |
| R-03 | alice: malformed choice value | 403 | 403 | PASS |
| R-04 | dave (no entity): /me/tenants is empty | 200 [] | 200 [] | PASS |
| R-05 | dave (no entity): /me refused | 403 | 403 | PASS |
| R-06 | dave (no entity): package list refused | 403 | 403 | PASS |
| R-07 | alice: LOCAL-mode headers (X-Tenant-Id B, X-Actor-Roles PLATFORM_OPERATIONS_ADMIN) ignored | 200 phase1-entity-a{alice's three roles} | same | PASS |
| **R-08** | **token without the API audience** | **401** | **500** | **FAIL** |
| R-09 | no token | 401 | 401 | PASS |
| R-10 | wrong issuer (other realm, same group name lv-a-staff): /me | 401 | 401 | PASS |
| R-11 | wrong issuer: /me/tenants | 401 | 401 | PASS |
| R-12 | malformed bearer token | 401 | 401 | PASS |
| R-13-1 | B's login-system record DISABLED: bob /me/tenants is empty | 200 [] | 200 [] | PASS |
| R-13-2 | … bob /me refused | 403 | 403 | PASS |
| R-13-3 | … carol /me/tenants lists only A | 200 [phase1-entity-a{DOCUMENT_SUPERVISOR}] | same | PASS |
| R-13-4 | … carol choosing B refused | 403 | 403 | PASS |
| R-13-5 | … carol choosing A still works | 200 phase1-entity-a{DOCUMENT_SUPERVISOR} | same | PASS |
| R-13-6 | … after restore, bob opens B again | 200 phase1-entity-b{…} | same | PASS |
| R-14-1 | Entity B SUSPENDED: bob /me/tenants is empty | 200 [] | 200 [] | PASS |
| R-14-2 | … bob /me refused | 403 | 403 | PASS |
| R-14-3 | … carol /me/tenants lists only A | 200 [phase1-entity-a{DOCUMENT_SUPERVISOR}] | same | PASS |
| R-14-4 | … carol choosing B refused | 403 | 403 | PASS |
| R-14-5 | … carol choosing A still works | 200 phase1-entity-a{DOCUMENT_SUPERVISOR} | same | PASS |
| R-14-6 | … after restore, bob opens B again | 200 phase1-entity-b{…} | same | PASS |

Totals: 47 checks, 46 PASS, 1 FAIL. Point 1 (P1-01 to P1-12) and point 2 (P2-01 to P2-10) pass in full at API level. The screen part of point 2 is for the next sitting.

Two observations, both passes:
- **Another entity's package returns 404, not 403**, when no other-entity choice is made (P1-04, P1-10, P2-08, P2-09). The lookup is scoped to the chosen entity, so the other entity's package is "not found". Its existence is not revealed.
- **The toggles took effect** within the 31-second wait. Disabling B's login-system record, or suspending B, removed B immediately for bob and carol, and A kept working.

### Failure: R-08, a token without the API audience returns 500 instead of 401

- **Expected:** 401. A token with no `lexavault-phase1-api` audience must be rejected as unauthenticated.
- **What happened:**
  - The response was 500, and the request was not served: no user, entity or data was returned.
  - The backend logged one ERROR, the only ERROR in the log.
  - The token from the test-only client `lexavault-phase1-noaud` has **no `aud` claim at all**. I checked by decoding it in memory and reporting only whether the claim exists; the token was not printed. The normal check client's token has `aud=lexavault-phase1-api` and works.
- **Log lines** (no tokens):

```
2026-11-17T16:20:32.722-05:00 ERROR http-nio-18080-exec-8 o.a.c.c.C.[Tomcat].[localhost].[/].[dispatcherServlet]
Servlet.service() for servlet [dispatcherServlet] ... threw exception
java.lang.NullPointerException: Cannot invoke "java.util.List.contains(Object)" because the return value of
  "org.springframework.security.oauth2.jwt.Jwt.getAudience()" is null
	at com.documentplatform.identity.spring.RequiredAudienceValidator.validate(RequiredAudienceValidator.java:18)
	at org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator.validate(DelegatingOAuth2TokenValidator.java:59)
	at org.springframework.security.oauth2.jwt.NimbusJwtDecoder.validateJwt(NimbusJwtDecoder.java:189)
	at org.springframework.security.oauth2.jwt.NimbusJwtDecoder.decode(NimbusJwtDecoder.java:139)
	at org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider.getJwt(JwtAuthenticationProvider.java:99)
```

- **Cause: product code, not the test setup.** It is in the shared Enterprise Identity library 0.2.0, `enterprise-identity-spring/.../RequiredAudienceValidator.java:18`:
  `return token.getAudience().contains(audience) ? success : failure(...)`.
  Spring's `Jwt.getAudience()` returns `null` when the token has no `aud` claim, so the validator throws instead of returning a failure. The exception escapes the resource-server filter and becomes a 500.
- **Impact:**
  - It still fails closed: nothing is admitted.
  - But it is the wrong status. Any caller can trigger an ERROR with a full stack trace in the logs by sending a validly signed token from the right issuer that has no `aud` claim. Keycloak issues such tokens by default for a client without an audience mapper.
  - A token whose `aud` exists but lacks the API audience was not tested. By reading the code, it should get 401.
- **Where the fix belongs:** a null-safe audience check in the Enterprise Identity library (for example, treat a null audience as an empty list), released as a new library version. That is a frozen area (Enterprise Identity R1) and needs the owner's approval. I changed nothing.

## Files changed

Added under `test-tools/phase1-shared-issuer/` (all test-only, no passwords or tokens):

| File | Purpose |
|---|---|
| `README.md` | What Phase 1 is, rules, commands, users |
| `compose.yml` | Throwaway PostgreSQL 16 and Keycloak 26.8.2. Project `lexavault-phase1`, label `lexavault.phase=1`, bound to 127.0.0.1, passwords read from the generated env file |
| `keycloak/lexavault-phase1-realm.json` | Realm, 3 clients (web PKCE, check script, no-audience), groups, 4 users, no credentials |
| `keycloak/lexavault-phase1-other-realm.json` | Second realm for the wrong-issuer check: user `eve.other` in a group also named `lv-a-staff` |
| `db/phase1-changelog.xml` | Test-only Liquibase master: the product master plus migration 037 |
| `db/phase1-seed.sql` | Two entities, capabilities, connections, history, 6 grants. Refuses any database but `lexavault_phase1`, and refuses while `uq_eic_active_binding` exists |
| `Phase1-Common.ps1` | Shared constants and guards |
| `Start-Phase1.ps1` | Secrets, containers, Keycloak user passwords, backend, proof, seed |
| `Invoke-Phase1Checks.ps1` | The 47 checks; results to `phase1-run\results` |
| `Remove-Phase1.ps1` | Stop (`-KeepContainers`) or remove everything Phase 1 created |

That is 10 files. Two departures from the plan:
- The entity B toggles live inside the check script, so there is no separate `phase1-toggles.sql`. Each toggle is restored in a `finally` block.
- `Phase1-Common.ps1` was added to hold the shared constants and guards.

Also added: `docs/work/0004-task.md` (unchanged) and this report. No product files changed.

## Commits (hash and message), or "not committed"

One commit on `test/phase1-shared-issuer` with the files above, this task file and this report. The staged files were searched for passwords and tokens first (result in the session summary). Not pushed, not merged.

## Containers at the end

`docker ps --filter label=lexavault.phase=1`:

```
NAMES                       STATUS                    PORTS
lexavault-phase1-keycloak   Up 23 minutes             127.0.0.1:18181->8080/tcp
lexavault-phase1-postgres   Up 23 minutes (healthy)   127.0.0.1:15432->5432/tcp
```

The Phase 1 backend is running on `localhost:18080` (PID 41504, health UP).

Normal containers, unchanged before and after. They were already stopped when Docker was started for this session; Phase 1 did not touch them:

| Container | Before start | After checks |
|---|---|---|
| securedoc-postgres | Exited (255) | Exited (255) |
| sdv-postgres | Exited (255) | Exited (255) |
| sdv-vault | Exited (255) | Exited (255) |
| lexa-keycloak | Exited (255) | Exited (255) |
| lexa-keycloak-db | Exited (255) | Exited (255) |

`Exited (255)` is what Docker shows after an unclean engine stop. The owner may want to start these again for normal work.

## How to stop and remove Phase 1

From the repository root, in Windows PowerShell:

```powershell
# Stop the Phase 1 backend only (containers, data and secrets kept; start again with Start-Phase1.ps1 -JarBuiltFromCommit 30e318a)
.\test-tools\phase1-shared-issuer\Remove-Phase1.ps1 -KeepContainers

# Remove everything Phase 1 created: the backend process, compose project lexavault-phase1
# (containers, network, volume lexavault-phase1-pgdata), anything labelled lexavault.phase=1,
# and C:\dev\lexavault\phase1-run (including the generated secrets)
.\test-tools\phase1-shared-issuer\Remove-Phase1.ps1
```

Neither command has been run yet, because the environment is left up as the task asks. Their first real run will be their test.

## Not done, and why

- **R-08 is not fixed.** The cause is in the Enterprise Identity library (frozen area). The task says to stop and report.
- **Not part of this sitting:** point 3 (redaction rules) and the screen checks.
- **`Remove-Phase1.ps1` has not been run**, because the environment is left up as asked.
- **A token with an `aud` claim that lacks the API audience was not tested** (it was not in the plan). It is worth adding as a check next sitting, to separate "missing audience" from "wrong audience".

## Questions for the owner

1. **R-08:** do you want a fix in the Enterprise Identity library? That would be a null-safe `RequiredAudienceValidator` with a unit test for a token without `aud`, released as 0.2.1 or folded into the planned 0.3.0, and then picked up by LexaVault. It is a frozen area, so it needs your approval. Until then, Phase 1 records R-08 as a known failure.
2. **Extra check:** may I add a "wrong audience" check (a token with `aud` present but not `lexavault-phase1-api`) to the check script next sitting? It needs one more test-only client in the realm file.

## Suggested next step

Sitting 2 (task 0005):
1. Point 3: redaction rules per entity, on the same three PDFs.
2. The owner's screen checks, with the UI on 15173.
3. The extra audience check, if approved.

The environment can be reused as it is. If it was removed, start it with `Start-Phase1.ps1 -JarBuiltFromCommit 30e318a`.
