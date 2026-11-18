# Phase 1: R1B shared-issuer test environment (test only)

Everything in this folder is test-only. It is not product code and is never deployed.

It runs the R1B shared-issuer code for real, for the first time: one login system (a throwaway Keycloak) shared by two entities (tenants), in an isolated environment beside the normal local LexaVault. The plan is in `docs/work/0003-report.md`. Each sitting's results are in the later reports under `docs/work/`.

## What it creates

| Item | Name | Address |
|---|---|---|
| PostgreSQL 16 | container `lexavault-phase1-postgres`, volume `lexavault-phase1-pgdata`, database `lexavault_phase1` | `127.0.0.1:15432` |
| Keycloak 26.8.2 (dev mode) | container `lexavault-phase1-keycloak`, realms `lexavault-phase1` and `lexavault-phase1-other` | `127.0.0.1:18181` |
| Backend | `java -jar` copy of the built jar, PID in `phase1-run\run\backend.pid` | `localhost:18080` |
| Run folder | `C:\dev\lexavault\phase1-run\` (secrets, logs, results, storage, output) | outside the repository |

Everything is in Docker Compose project `lexavault-phase1`, and every container and volume carries the label `lexavault.phase=1`. The normal containers (`securedoc-postgres`, `sdv-postgres`, `sdv-vault`, `lexa-keycloak`, `lexa-keycloak-db`) are never touched.

## Rules this folder keeps

- **No secrets in the repository.** The database, Keycloak admin and test-user passwords are generated at start-up into `C:\dev\lexavault\phase1-run\secrets\`. Tokens are never written to disk, logs or the console.
- **Migration 037** is applied only to the throwaway database, through `db/phase1-changelog.xml`. The product master changelog is not changed.
- **The shared-issuer setting** is switched on only on the Phase 1 backend's command line.
- `Start-Phase1.ps1` refuses to start unless the database URL is `127.0.0.1:15432/lexavault_phase1`, and fails if the backend has a connection to port 5432.
- **It never builds.** It reuses the existing jar only if it is newer than the last backend commit. To rebuild, stop the normal backend first, then run `cd backend; mvn clean install`.

## Commands (Windows PowerShell, from the repository root)

```powershell
.\test-tools\phase1-shared-issuer\Start-Phase1.ps1         # containers, backend, schema proof, seed, grants from db\phase1-role-mapping.csv
.\test-tools\phase1-shared-issuer\Invoke-Phase1Checks.ps1  # scripted checks (incl. M-01..M-08); results in phase1-run\results
.\test-tools\phase1-shared-issuer\Invoke-Phase1RoleMapping.ps1 -File <csv> [-DryRun]  # load-role-mappings command on the Phase 1 DB
.\test-tools\phase1-shared-issuer\Start-Phase1Ui.ps1      # UI, Vite dev server, http://localhost:15173/
.\test-tools\phase1-shared-issuer\Start-Phase1UiProd.ps1  # UI, production build, http://localhost:15174/
node test-tools/phase1-shared-issuer/Invoke-Phase1BrowserSignin.mjs --mode dev   # real browser sign-in, 4 users
node test-tools/phase1-shared-issuer/Invoke-Phase1BrowserSignin.mjs --mode prod  # same, against the production build
.\test-tools\phase1-shared-issuer\Remove-Phase1.ps1 -KeepContainers  # stop the backend and UIs only
.\test-tools\phase1-shared-issuer\Remove-Phase1.ps1        # remove everything Phase 1 created
```

## Test users (realm `lexavault-phase1`)

| User | Groups | Entities |
|---|---|---|
| `alice.a` | lv-a-staff, lv-a-admin | A only |
| `bob.b` | lv-b-staff, lv-b-admin | B only |
| `carol.ab` | lv-a-staff, lv-b-staff | A and B |
| `dave.none` | lv-unmapped | neither |

`eve.other` is in the second realm, `lexavault-phase1-other`, in a group with the same name (`lv-a-staff`). It is used for the wrong-issuer refusal check.

The group-to-role grants are in `db/phase1-seed.sql`:
- `lv-x-staff` gets DOCUMENT_SUPERVISOR.
- `lv-x-admin` gets BUSINESS_ADMIN and TENANT_IT_ADMIN.

## Files

| File | Purpose |
|---|---|
| `compose.yml` | Throwaway PostgreSQL and Keycloak, bound to 127.0.0.1, passwords read from the generated env file |
| `keycloak/*.json` | Realms, clients, mappers, groups and users (no passwords) |
| `db/phase1-changelog.xml` | Test-only Liquibase master: the product master plus migration 037 |
| `db/phase1-seed.sql` | Two entities, their login-system records and group grants (refuses any other database) |
| `Phase1-Common.ps1` | Shared constants and guards |
| `Start-Phase1.ps1` | Start, prove, seed |
| `Invoke-Phase1Checks.ps1` | Scripted checks |
| `Remove-Phase1.ps1` | Stop or remove |
