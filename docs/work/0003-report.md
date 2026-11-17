# Report 0003: Phase 1 plan (plan only, change nothing)

Date: 2026-11-17
Branch: `feature/lexavault-r1b-tenant-identity`
Outcome: DONE (the plan is written; nothing was built, started or changed)

Housekeeping: the local branch `fix/surefire-infrastructure-tests` was deleted (`git branch -d`, it was fully merged). The Ubuntu check is dropped, as the task says.

## What was done

I read the code and wrote the plan below. Nothing was started, created or changed apart from this report. The sources are the R1B architecture note, design sections 11 and 12, the security and OIDC classes in `securedoc-api`, the JDBC adapters and Liquibase changelogs in `securedoc-infrastructure`, the run scripts, the frontend OIDC code, and the identity library source. File references are given where a fact matters.

### Phase 1 plan

#### 0. Key facts the plan is built on

- **Shared-issuer setting:** `securedoc.security.oidc.shared-issuer-tenant-selection-enabled`, default `false` (`SecurityProperties.java:26`, also read through `@Value` in `JdbcOidcConnectionBindingRepository.java:22`). It is in no yml file, so the run passes it on the command line.
- **How a person is admitted when it is on** (`RequestContextResolver.resolveOidcPrincipal`): the token's `iss`, the configured `provider-id` and the configured `audience` find the ACTIVE entity connections (`JdbcActiveOidcTenantCandidates`). Those are intersected with group grants (`JdbcTenantOidcGroupGrantRepository`). Then `OidcTenantSelection` uses the `X-Selected-Tenant-Id` header:
  - no header and one entity: that entity is used;
  - no header and two entities: 403;
  - a header naming an entity the person does not have: 403.
- **Matching is exact.** The `iss` string must equal the configured issuer and the `issuer_uri` in both tables character for character; these lookups do no normalising. Group names are only trimmed: there is no leading-slash removal and no case folding.
- **Blocker without migration 037.** Index `uq_eic_active_binding` (migration 032) allows only one ACTIVE connection per (provider, issuer, audience) across all tenants. The second entity cannot be made ACTIVE unless 037 has dropped that index.
- **`GET /api/v1/me/tenants`** lists the entities a person may choose, before a choice is made. `GET /api/v1/me` returns the chosen entity's name and slug. The UI shows a "Select organization" screen when there are two or more (`App.tsx:387-419`) and has a switch (`App.tsx:430-443`).
- **Redaction rules per entity** are the `tenant_redaction_policies` row, set through `PUT /api/v1/business-admin/sensitive-data-policy` (role BUSINESS_ADMIN). Only 4 types can be switched: SSN, DATE_OF_BIRTH, CREDIT_CARD_NUMBER and CREDIT_SCORE. Email is not configurable.

#### 1. Containers, ports, names, volumes

All Phase 1 containers are in one Docker Compose project named `lexavault-phase1`. Every name starts with `lexavault-phase1-`, and every container carries the label `lexavault.phase=1`.

| Item | Phase 1 | Normal dev (untouched) |
|---|---|---|
| PostgreSQL | container `lexavault-phase1-postgres`, image `postgres:16-alpine`, port **15432**, volume `lexavault-phase1-pgdata`, database `lexavault_phase1` | `securedoc-postgres` on 5432 |
| Keycloak | container `lexavault-phase1-keycloak`, same image tag as `lexa-keycloak` (to be confirmed at build time with a read-only `docker inspect`), `start-dev` with its built-in dev database (no extra DB container), port **18181**, realm imported at start | `lexa-keycloak` on 8181 |
| Backend | `java -jar` on port **18080**, from a copy of the jar | 8080 |
| UI (only for the screen checks) | Vite on port **15173**, `--strictPort`, proxy to 18080 | 5173 |
| Scanner bridge, watched folder | not started or disabled | 10090 |
| AI | `SECUREDOC_AI_PROVIDER=heuristic` (in-process, no Ollama) | Ollama 11434 |

All files the run writes go to `C:\dev\lexavault\phase1-run\`, which is outside the repository: the jar copy, `storage\`, `output\`, `watched-intake\`, `logs\`, `run\` (PID files) and `results\`.

**Running beside the normal setup:** yes, the test instance runs on its own ports, so the normal local LexaVault does not need to be stopped. This is simpler and safer than stopping it. The normal start and stop scripts are not used for Phase 1: they assume fixed ports and share `.run\*.pid`, so mixing them would make the owner's stop script kill the wrong processes. Phase 1 has its own start and stop scripts with their own PID folder. Two cautions for the owner during a run:
- Avoid `mvn clean` on the backend. Phase 1 runs from a copy of the jar anyway.
- Avoid `restart-ui.cmd` only if Phase 1 were on 5173, which it is not.

#### 2. Realm, groups, test users

- Realm: `lexavault-phase1`.
- Issuer: `http://localhost:18181/realms/lexavault-phase1` (always `localhost`, never `127.0.0.1`).
- Provider id: `phase1-oidc`. API audience: `lexavault-phase1-api`.

Clients:
- `lexavault-phase1-web`: public, Authorization Code with PKCE S256, redirect `http://localhost:15173/*`, web origin `http://localhost:15173`. Used for the screen checks.
- `lexavault-phase1-checks`: public, direct access grants (password grant) on. Only the check script uses it, inside the throwaway container.
- `lexavault-phase1-noaud`: like `-checks` but without the audience mapper. Used for one refusal check.
- Mappers on `-web` and `-checks`:
  - Group Membership, claim `groups`, **full group path OFF**, in the access token;
  - Audience, adding `lexavault-phase1-api` to the access token.

Groups, and what the seed grants each one:

| Keycloak group | Entity | Product roles granted |
|---|---|---|
| `lv-a-staff` | A | DOCUMENT_SUPERVISOR |
| `lv-a-admin` | A | BUSINESS_ADMIN, TENANT_IT_ADMIN |
| `lv-b-staff` | B | DOCUMENT_SUPERVISOR |
| `lv-b-admin` | B | BUSINESS_ADMIN, TENANT_IT_ADMIN |
| `lv-unmapped` | none | none (no grant rows) |

DOCUMENT_SUPERVISOR is used rather than PROCESSOR so the script can also approve delivery if needed. TENANT_IT_ADMIN gives AUDIT_VIEW, which the point 3 check reads.

Test users. Passwords are obviously fake (for example `phase1-fake-alice`) and exist only inside the throwaway Keycloak.

| User | Groups | Entities | Purpose |
|---|---|---|---|
| `alice.a` | lv-a-staff, lv-a-admin | A only | point 1, point 3 (A) |
| `bob.b` | lv-b-staff, lv-b-admin | B only | point 1, point 3 (B) |
| `carol.ab` | lv-a-staff, lv-b-staff | A and B | point 2 |
| `dave.none` | lv-unmapped | neither | refusal |

#### 3. Files to add

Test-only material goes in a new folder, **`test-tools/phase1-shared-issuer/`**. `test-tools/` already holds acceptance tooling and is separate from product code; nothing goes under `backend/` or `frontend/` source.

| File | Purpose |
|---|---|
| `README.md` | What Phase 1 is, the run order, and the clean-up command. States that everything here is test-only. |
| `compose.yml` | PostgreSQL and Keycloak as above. Project name `lexavault-phase1`, label `lexavault.phase=1`, volume `lexavault-phase1-pgdata`, realm file mounted read-only for `--import-realm`. |
| `keycloak/lexavault-phase1-realm.json` | Realm, clients, mappers, groups and users from section 2. The fake passwords are visible in this file. |
| `db/phase1-changelog.xml` | Test-only Liquibase master that includes the product master and then `037-r1b-shared-issuer-active-bindings.xml` (see "Migration 037"). |
| `db/phase1-seed.sql` | Test-only seed (see "Seed rows"). Header comment: `PHASE 1 TEST DATA - throwaway database lexavault_phase1 only`. Idempotent (`on conflict do nothing`). |
| `db/phase1-toggles.sql` | Small named blocks the check script runs to disable or restore entity B's connection or status for the refusal checks. |
| `Start-Phase1.ps1` | Starts the containers, waits until they are healthy, copies the jar, starts the backend with the Phase 1 settings, and writes PIDs to `phase1-run\run\`. Refuses to start unless the JDBC URL contains `:15432/lexavault_phase1`. |
| `Invoke-Phase1Checks.ps1` | Gets tokens from the throwaway Keycloak and calls the API. Prints a PASS/FAIL table and writes `phase1-run\results\checks-<time>.json`. |
| `vite.phase1.config.ts` | Port 15173 with `strictPort`, proxy `/api` to `http://localhost:18080`, `root` pointing at `frontend/securedoc-ui`. |
| `Remove-Phase1.ps1` | The single clean-up command (section 4, step 8). |

That is 10 files. No product file changes. The master changelog, `.gitignore` and existing scripts stay as they are.

**Migration 037: how it reaches the throwaway database only.**
- The backend is started with `--spring.liquibase.change-log=file:<repo>/test-tools/phase1-shared-issuer/db/phase1-changelog.xml`.
- That file includes `db/changelog/db.changelog-master.xml` (001-036, unchanged) and then `db/changelog/037-r1b-shared-issuer-active-bindings.xml`, both from the jar's classpath.
- The product master is not edited, so `SharedIssuerMigration037ContractTest.migrationIsNotRegisteredBeforeRuntimeAcceptance` still passes.
- This setting is passed only by `Start-Phase1.ps1`, together with the 15432 guard. No other launcher can pick it up.
- After start, the script checks that:
  - `databasechangelog` has the `037-r1b-shared-issuer-active-bindings` row;
  - `uq_eic_active_binding` is gone;
  - `uq_eic_active_per_tenant` is still there.
- Fallback, if Liquibase cannot resolve classpath includes from a `file:` master (see unknowns): start once with the normal master, then apply 037 with the official `liquibase/liquibase` CLI image as a one-off `docker run --rm` labelled `lexavault.phase=1`, against port 15432 only.

**Seed rows** (from the changelogs and JDBC queries; tenant ids are fixed UUIDs so re-runs are stable):

| Table | Rows | Values that matter |
|---|---|---|
| `tenants` | A `…a001` "Phase1 Entity A" slug `phase1-entity-a`, B `…b001` "Phase1 Entity B" slug `phase1-entity-b` | `status='ACTIVE'`, `version=0`, timestamps |
| `tenant_capabilities` | 7 per entity: DOCUMENT_UPLOAD, OCR_PROCESSING, SENSITIVE_DATA_DETECTION, REDACTION, HUMAN_REVIEW, VAULT, AUDIT | `status='ENABLED'`, the same set `LocalDemoBootstrap` gives the demo tenant. Without DOCUMENT_UPLOAD, create, upload and submit are refused. |
| `enterprise_identity_connections` | 1 per entity | `provider_id='phase1-oidc'`, `provider_type='OIDC'`, `issuer_uri='http://localhost:18181/realms/lexavault-phase1'`, `api_audience='lexavault-phase1-api'`, `browser_client_id='lexavault-phase1-web'`, `status='ACTIVE'`, `configuration_version=1`, display name, timestamps |
| `enterprise_identity_connection_history` | 1 per connection | `change_type='BOOTSTRAP'`, `changed_by='PHASE1-SEED'`, `status='ACTIVE'`, version 1. Mirrors what the app's own bootstrap writes. |
| `tenant_oidc_group_grants` | 6 rows, from the group table in section 2 | `issuer_uri` identical to the connection's. `external_group` is the exact group name without a slash. `product_role` must be one of the 5 tenant role names in upper case: any other value makes the lookup fail for every user on the issuer. |

Not seeded:
- **Redaction policies and business identifiers.** The check script sets these through the API as each entity's admin, which also tests that the admin APIs stay inside the chosen entity.
- **Licences.** Licensing is DISABLED under the `local` profile.
- **Integration connections.** The machine path is Phase 4.

The `local` profile's `LocalDemoBootstrap` will also create the demo tenant `…0001` in the throwaway database. It gets no connection and no grants, which makes it a useful third control entity. `SECUREDOC_SECURITY_OIDC_TENANT_ID` is left blank, so `OidcConnectionBootstrap` inserts nothing.

#### 4. The run, as commands

From the repository root, in PowerShell:

```powershell
# 1. Build once (normal build, all tests)
cd backend; mvn clean install; cd ..

# 2. Containers, backend with 037 via the Phase 1 changelog, schema checks
.\test-tools\phase1-shared-issuer\Start-Phase1.ps1
#   inside it:
#   docker compose -p lexavault-phase1 -f test-tools\phase1-shared-issuer\compose.yml up -d --wait
#   copy backend\securedoc-api\target\securedoc-api-0.1.0-SNAPSHOT.jar to C:\dev\lexavault\phase1-run\
#   java -jar ...\phase1-run\securedoc-api-0.1.0-SNAPSHOT.jar `
#     --spring.profiles.active=local,oidc-local --server.port=18080 `
#     --spring.datasource.url=jdbc:postgresql://localhost:15432/lexavault_phase1 `
#     --spring.liquibase.change-log=file:...\db\phase1-changelog.xml `
#     --securedoc.security.oidc.issuer-uri=http://localhost:18181/realms/lexavault-phase1 `
#     --securedoc.security.oidc.audience=lexavault-phase1-api `
#     --securedoc.security.oidc.provider-id=phase1-oidc `
#     --securedoc.security.oidc.browser-client-id=lexavault-phase1-web `
#     --securedoc.security.oidc.persistent-connection-enabled=true `
#     --securedoc.security.oidc.shared-issuer-tenant-selection-enabled=true `
#     --securedoc.storage.local.root-path=...\phase1-run\storage `
#     --securedoc.delivery.local.output-path=...\phase1-run\output `
#     --securedoc.intake.watched-folder.root-path=...\phase1-run\watched-intake
#   env: SPRING_DATASOURCE_USERNAME/PASSWORD (throwaway values defined in compose.yml),
#        SECUREDOC_AI_PROVIDER=heuristic, SECUREDOC_AI_OLLAMA_PRELOAD_ENABLED=false,
#        SECUREDOC_WATCHED_FOLDER_ENABLED=false, SECUREDOC_WATCHED_FOLDER_SCHEDULING_ENABLED=false,
#        SECUREDOC_NODE_ID=PHASE1-APP-01, SECUREDOC_LOG_DIR=...\phase1-run\logs,
#        SECUREDOC_SECURITY_CORS_ALLOWED_ORIGINS=http://localhost:15173
#   waits for /actuator/health, then checks databasechangelog and the two indexes

# 3. Seed
docker exec -i lexavault-phase1-postgres psql -U <phase1 user> -d lexavault_phase1 -v ON_ERROR_STOP=1 `
  -f - < test-tools\phase1-shared-issuer\db\phase1-seed.sql

# 4. Scripted checks (points 1-3 and the refusals); restores every toggle it changes
.\test-tools\phase1-shared-issuer\Invoke-Phase1Checks.ps1

# 5. Screen checks (owner)
cd frontend\securedoc-ui
$env:VITE_SECURITY_MODE='OIDC'; $env:VITE_OIDC_ISSUER='http://localhost:18181/realms/lexavault-phase1'
$env:VITE_OIDC_CLIENT_ID='lexavault-phase1-web'; $env:VITE_OIDC_REDIRECT_URI='http://localhost:15173/'
npx vite --config ..\..\test-tools\phase1-shared-issuer\vite.phase1.config.ts
#   open http://localhost:15173 in a private window

# 6. Stop the backend and UI only (containers kept for a re-run)
.\test-tools\phase1-shared-issuer\Remove-Phase1.ps1 -KeepContainers

# 7. Re-run: steps 2-5 again; the seed is idempotent

# 8. Clean-up: one command, removes everything Phase 1 created and nothing else
.\test-tools\phase1-shared-issuer\Remove-Phase1.ps1
#   stops processes by the PIDs in phase1-run\run\ only (checking each is java/node first),
#   docker compose -p lexavault-phase1 -f ...\compose.yml down -v --remove-orphans
#     (removes only containers and the volume of project lexavault-phase1),
#   removes any leftover container labelled lexavault.phase=1,
#   deletes C:\dev\lexavault\phase1-run\
```

`Remove-Phase1.ps1` never runs a bare `docker compose down`, never names the normal containers, and refuses to run if the compose project name is not `lexavault-phase1`.

#### 5. The three points: what is done, and what counts as a pass

Tokens come from the password grant on `lexavault-phase1-checks`. "Header A" means `X-Selected-Tenant-Id: <A id>`.

**Point 1: a person assigned to one entity cannot open the other.** Scripted.

| Step | Pass |
|---|---|
| alice `GET /me/tenants` | 200, exactly [A] with roles DOCUMENT_SUPERVISOR, BUSINESS_ADMIN, TENANT_IT_ADMIN |
| alice `GET /me` with no header | 200, tenantSlug `phase1-entity-a` |
| alice `GET /me` with header B | 403 SECURITY_CONTEXT_INVALID |
| alice `GET /packages/{B's package}` with no header, then with header B | not 200 (403 or 404), with no B data in the body |
| alice `GET /packages` | contains no B package ids |
| bob, the mirror of all of the above | same results with A and B swapped |

**Point 2: a person in both entities chooses one, sees only that one, and can switch.**

Scripted with carol's single token:

| Step | Pass |
|---|---|
| `GET /me/tenants` | 200, exactly [A, B], each with DOCUMENT_SUPERVISOR only |
| `GET /me` with no header | 403 (no implicit choice) |
| `GET /me` with header A | tenantSlug `phase1-entity-a`, roles DOCUMENT_SUPERVISOR only (no admin role leaks in) |
| `GET /packages` with header A | only A's packages |
| `GET /me` and `GET /packages` with header B (switch, same token) | only B's, then back to A again |
| `GET /packages/{B's package}` with header A | not 200 |

Screen check by the owner (cannot be scripted without a browser test):
1. Sign in at http://localhost:15173 as `carol.ab`. The "Select organization" screen lists Phase1 Entity A and B.
2. Choose A. The dashboard shows only A's package.
3. Use the switch. The app returns to the root, shows B's package and not A's, with no A data left on screen.
4. Sign in as `alice.a`. No choice screen appears; A opens directly.

I will provide a short checklist with screenshots to tick.

**Point 3: the two entities run different redaction rules on the same kind of document.** Scripted; see section 7 for the rules and document. As each entity's admin (alice for A, bob for B), the script:
1. sets the identifiers (copying the demo definitions) and the redaction policy;
2. uploads the same three PDFs in one MANUAL_UPLOAD package;
3. submits it and waits for PENDING_REVIEW.

Pass:
- `GET /review-items?packageId=` for A lists only entityType SSN. For B it lists SSN, CREDIT_CARD_NUMBER and DATE_OF_BIRTH.
- The `DOCUMENT_DETECTION_COMPLETED` audit records (`GET /audit-records?action=DOCUMENT_DETECTION_COMPLETED&resourceId=`) show `types=` that agree with this.
- A's policy, read back by bob, is not visible (403). Each entity's `GET /business-admin/configuration` shows its own policy.
- Optionally, the script confirms all items, waits for COMPLETED, and checks that `phase1-run\output\<A id>\…` and `…\<B id>\…` exist (folder names only; no contents are read).

#### 6. Extra refusal checks (scripted)

| Check | Pass |
|---|---|
| Forged choice: alice with header = B's id | 403 |
| Forged choice: carol with header = the demo tenant `…0001` (exists and is ACTIVE, but has no grant) | 403 |
| Forged choice: random UUID; malformed value `not-a-uuid` | 403 |
| dave (neither): `GET /me/tenants` | 200 `[]` |
| dave (neither): `GET /me`, `GET /packages` | 403 |
| LOCAL headers ignored: alice with `X-Tenant-Id: <B>`, `X-Actor-Roles: PLATFORM_OPERATIONS_ADMIN` | still entity A; no extra roles |
| Entity B's login-system record not ACTIVE: toggle sets B's connection to `DISABLED` | bob: `/me/tenants` gives `[]` and `/me` gives 403. carol: `/me/tenants` gives [A] only, and header B gives 403. Restored afterwards. |
| Entity B not ACTIVE: toggle sets `tenants.status='SUSPENDED'` for B | same as above. Restored. |
| Token without the API audience (`lexavault-phase1-noaud`) | 401 |
| No token | 401 |
| Wrong issuer: token from the normal `lexa-keycloak` realm | 401. Optional; it only reads a token and changes nothing there. Skipped unless the owner agrees. |
| Shared-issuer guard: `SharedIssuerActivationPreflight` refuses a PLATFORM_OPERATIONS_ADMIN grant | covered by unit tests; not repeated at runtime, because a bad role row breaks every user on the issuer (see risks) |

Each toggle waits 31 seconds before checking, in case any cache applies (see unknowns). Every toggle is restored in a `finally` block.

#### 7. How point 3 is shown

- **Rules:**
  - Entity A: `enabledEntityTypes=[SSN]`.
  - Entity B: `[SSN, CREDIT_CARD_NUMBER, DATE_OF_BIRTH]`.
  - Both: `reviewMode=REVIEW_ALL`, threshold 0.80. REVIEW_ALL keeps every enabled finding in the review list, so the difference can be read through the API. There is no document download for users.
- **Document:** the same three synthetic PDFs from `test-data\golden-path-watched-intake-template\`, uploaded through the API (not the watched folder), with the identifiers from their `manifest.json`:
  - `04-approval\04_approval_flow.pdf` (SSN)
  - `05-reprocess\05_reprocess_cycle.pdf` (date of birth)
  - `07-export\07_export_delivery.pdf` (card number)
- **Expected:** both entities find the SSN. Only B also flags the card number and the date of birth. This is shown per entity in the check output as a table of entity type and count, side by side.
- Email cannot be used: it is not a per-entity setting (`TenantRedactionPolicy.java:20-25`; the API rejects it with 400).

#### 8. Unknowns and risks

These are honest expectations, most likely first.

1. **This code path has never run.** The R1B notes for B8-B23 each say the code was not built or runtime tested at the time. It now compiles and its unit tests pass, but integration bugs are likely. If something fails, the plan is to stop, record it, and fix it in a separate task, not during the run.
2. **Liquibase `file:` master with classpath includes.** The product master uses includes like `db/changelog/001-….xml`. I could not confirm from reading that Spring's Liquibase resolves those from a `file:` master. If it does not, the fallback is the Liquibase CLI container (section 3).
3. **Exact `iss` match.** Keycloak in `start-dev` builds `iss` from the request host. The backend, the script and the browser must all use `localhost:18181`. A `127.0.0.1` anywhere means 401s or empty entity lists.
4. **Keycloak group claim.** If "full group path" is left on, groups arrive as `/lv-a-staff` and nothing matches. If the groups mapper is not added to the access token, there are no groups. The realm file must set both. I will read `GET /me/tenants` for alice first, as the first diagnostic.
5. **Audience.** Keycloak access tokens carry `aud=account` unless the audience mapper is added. Without it, every call is 401.
6. **A grant row with a wrong role name** (lower case, `USER`, or `PLATFORM_OPERATIONS_ADMIN`) makes the grant lookup fail with 409 for every user on that issuer (`JdbcTenantOidcGroupGrantRepository.java:45-47`). The seed must be exact. This fail-closed behaviour is also a resilience problem worth recording.
7. **Caching.** The legacy resolver caches for 30 seconds. I did not find a cache on the shared-issuer lookups, but each request runs them several times (once in the interceptor, again in the capability check and the controllers). It should be correct, but it adds database load. The 31-second wait covers a cache I may have missed.
8. **Heuristic AI and the date of birth.** With the heuristic provider, detection rests on the regex detector plus the heuristic adapter. I could not confirm from reading that the date of birth in `05_reprocess_cycle.pdf` is labelled in a way the regex catches. If B shows no DATE_OF_BIRTH, point 3 still passes on CREDIT_CARD_NUMBER, but I will report it. The real model is not used, to keep the owner's Ollama untouched and the result repeatable. Say if you want a second run with Ollama.
9. **Review list timing.** `GET /review-items` only lists findings while the package is PENDING_REVIEW or IN_REVIEW. With REVIEW_ALL that is expected. If a package goes straight to COMPLETED, the script falls back to the audit records.
10. **The choice screen has never been seen in a browser.** These parts are untested in a real browser: the B12-B18 frontend work, the "Select organization" screen, the switch, and the per-entity cache reset. Expect visual or state problems here first.
11. **Vite config outside the frontend folder.** `vite.phase1.config.ts` lives in `test-tools/` and imports `@vitejs/plugin-react`. That may not resolve from there. If it does not, the fallback is the same file inside `frontend/securedoc-ui/`, named so it is clearly test-only. That adds a file under `frontend/`, so I would ask first.
12. **Fake passwords committed.** The realm JSON contains the test users' fake passwords. They only work in the throwaway container, but they would be in the repository. The alternative is to generate them at start-up and print them only to the console. See the questions.
13. **`LocalDemoBootstrap` side effects.** The `local` profile writes the demo tenant and a scanner integration row into the throwaway database. This is harmless, and the demo tenant is used as a control, but it means the run is "local plus oidc-local", not a production profile. The licence check is therefore off and is not proven here (design point 6 is a later phase).
14. **Machine and scanner paths are not covered.** In shared-issuer mode, machine intake needs PUSH_API rows, and `/api/v1/scanner-intakes` is not routed to the machine path. Scanner intake would fail. Both belong to Phase 4. Phase 1 keeps them off and does not prove them.
15. **CORS does not allow `X-Selected-Tenant-Id`** (`SecurityConfig.java:104-106`). This is harmless here because the UI uses Vite's same-origin proxy, but it would break a deployment where the UI and API are on different origins. Worth a backlog item.
16. **Identity library source vs jar.** I read the claim normaliser from the `enterprise-identity-parent` source. The app runs the 0.2.0 jar from the local Maven repository. I assumed they match.
17. **Keycloak image tag.** I could not read the normal Keycloak's tag from any file (it was set up by hand). The design doc mentions 26.8. I would confirm the tag with `docker inspect lexa-keycloak` (read-only) before writing `compose.yml`.

#### 9. Rough size

- **Files:** 10 new files under `test-tools/phase1-shared-issuer/`, and no product files. The largest are the realm JSON (about 200 lines) and `Invoke-Phase1Checks.ps1` (about 300-400 lines). The rest are short.
- **Sittings:** three.
  1. Write the files. Bring up the containers, schema with 037, and seed. Run the point 1 and 2 API checks and the refusals.
  2. Point 3 and the owner's screen checks.
  3. Expected: fixing whatever R1B problems the first two sittings turn up, each as its own task with its own build and tests.

## Commands run and results

Read-only only. Code was read with file reads and searches. The other commands:

| Command | Result |
|---|---|
| `git branch -d fix/surefire-infrastructure-tests` | Deleted (was 96868d0) |
| `git status --short` | Only `docs/work/0003-task.md` untracked before this report |

No build was run, no container was started, and no application was run.

## Tests: counts per module, and any failures with their output

Not run: nothing changed. The last build gave 426 tests and 0 failures (report 0001).

## Files changed

- `docs/work/0003-report.md`: new (this file)
- `docs/work/0003-task.md`: committed unchanged, as the task asks

## Commits (hash and message), or "not committed"

One commit with this report and the task file. Its hash is in `git log`. Not pushed.

## Not done, and why

Nothing from the task is left undone. The plan itself is not carried out, because the task says to plan only.

## Questions for the owner

1. **Fake passwords in the repository:** is it acceptable to commit the realm JSON with the test users' fake passwords (usable only in the throwaway container)? The alternative is to generate them at start-up and never commit them.
2. **AI for point 3:** use the in-process heuristic detector (repeatable, leaves your Ollama alone)? Or also run once with the real model?
3. **Optional wrong-issuer check:** may the check script request one token from the normal `lexa-keycloak`? It is read-only there.
4. **UI config fallback:** if the Vite config cannot live in `test-tools/`, may it go inside `frontend/securedoc-ui/` as a clearly named test-only file?

## Suggested next step

Review this plan. If it is approved, the next task would be sitting 1:
1. write the 10 files;
2. bring up the containers and backend;
3. confirm 037 is applied to the throwaway database only;
4. seed, then run the point 1 and 2 checks and the refusals;
5. report.
