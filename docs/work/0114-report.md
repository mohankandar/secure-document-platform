# Report 0114: LexaVault install proof

Date: 2026-11-28
Branch: `feature/0114-install-proof`, from `origin/feature/lexavault-r1b-tenant-identity` at `3b1cf8a` (draft PR #64)
Outcome: **PARTIALLY DONE**. The proof runs green on Linux and Windows for six of the criteria. Sign-in with a throwaway login system, the review step and the upgrade from the previous build are still to do (below). Not merged.

## Answers used (design chat, 2026-11-28)
- **Q1:** the package is a **development build with the demo licence** until 0088 ships the production keys. The build kind is a workflow input (`build: development | production`).
- **Q2:** a native Keycloak (its own zip) on both systems. Not built yet.

## What is built
- **The package** (`scripts/package/make-package.sh`): `lexavault.jar`, `lexavault-setup.jar`, `lexavault-scan-bridge.jar`, `ui/` (the screens), `install/`, `docs/` (the install guides) and `VERSION`. Built once on Linux, uploaded as an artifact, and installed on both runners from that artifact alone.
- **The install guide, step by step** (`docs/install/INSTALL.md`): what you need, the database and its two accounts, the schema step with the owner account, setup and LexaVault with the everyday account, a check, backup and restore. It links each topic guide.
- **The proof** (`test-tools/install-proof/install-proof.mjs`, one script for both systems) and **the workflow** (`.github/workflows/install-proof.yml`):
  - runs on every `v*` tag, monthly (the 4th at 05:23 UTC), by hand, and on a pull request that changes the proof, the package, the install guides or the schema step;
  - a release, monthly or manual run that fails opens one issue labelled `install-proof` (or comments on the open one).
  - GitHub runs scheduled and manual workflows only from the default branch (`main`), so the monthly run starts once this reaches `main`.

## Results (run 46141135820, both green)
| Step | Linux | Windows |
|---|---|---|
| 1. No secret in the package (544 files: the package and LexaVault's own classes and settings in its jars) | PASS | PASS |
| 2. Database, two accounts, schema step with the owner account | PASS | PASS |
| 3. Setup and the first entity with the everyday account; LexaVault starts with it | PASS | PASS |
| 4. One document: intake, redaction (stand-in AI server, no GPU), delivery to a folder; no redacted value in the delivered file | PASS | PASS |
| 5. Stop and start again: the case is still COMPLETED | PASS | PASS |
| 6. Backup (`pg_dump`), restored to a test copy; the schema step reads the copy | PASS | PASS |
| **Install time (steps 2 and 3)** | **39 s** | **19 s** |
| Whole proof | 131 s | 36 s |

## What the proof found (and what was fixed)
1. **LexaVault could not run with an everyday database account.** The schema step gave the runtime user rights on the platform's tables only. **Fixed:** with `--runtime-user` it now also gives data rights on LexaVault's own tables: the audit trail is add-and-read only (as migration 088 holds for the owner), there are no rights on Liquibase's tables, and the sequences' next values. Test: `SchemaStepPostgresTest.theRuntimeUserWorksOnLexaVaultsTablesAndNeverChangesTheAuditTrail`. The exact-rights test now checks the platform tables only.
2. **The install guide did not name Tesseract OCR.** Without it a document stops (LV-PRC-005). **Fixed** in the guide; the proof installs it from the system's packages.
3. **The development jar carries the local development database's default password** (`application-local.yml`, the same value as the public `docker/compose/postgres.yml`). It is no secret, but a production package should leave `application-local.yml` out. **For 0088.** The proof allows exactly this value, by its SHA-256 (the value is never in the proof).
4. In the local profile the app runs Liquibase at start, which the everyday account cannot do. An install runs nothing at start (the schema step does it), so the proof starts the app with `SPRING_LIQUIBASE_ENABLED=false`. Without a profile this is already the default.
5. With the demo sign-in file, the login-system health check is DOWN (its login system is a placeholder), so readiness is not UP. The proof waits for the API to answer instead. This goes away with the sign-in step below.

## Left (to finish 0114)
- **Sign-in with a throwaway login system:** a native Keycloak zip on both runners. Setup then uses a local OIDC sign-in file (`LocalOidcSignin`) and the first entity comes from the install command (`FirstEntity`) instead of the demo step. The case is created with a person's token. `test-tools/first-entity` already does this with containers, so its realm set-up can be reused.
- **Review:** the stand-in AI answers with high confidence, so the case goes straight through. Add one low-confidence finding and decide it through the review API.
- **Upgrade from the previous build:** install the previous successful run's package first, then upgrade with this one (stop, schema step, start), and check the case is still there.
- Then: TO-TEST entry, PR out of draft, merge on green.

## Tests run
- `SchemaStepPostgresTest` (9) and `AuditRecordsAppendOnlyPostgresTest` on a throwaway PostgreSQL container: pass.
- The install proof in GitHub Actions: 11 runs. The fixes above came from runs 1 to 10; run 11 (46141135820) passed on both systems. Two runs hit Maven Central's rate limit (HTTP 429) and were run again.

No other product's name or behaviour in code.
