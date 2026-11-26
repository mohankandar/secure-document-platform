# CI (GitHub Actions)

Task 0042. Workflow: `.github/workflows/ci.yml`. Runs on every push and pull request to
`feature/lexavault-r1b-tenant-identity` and `main`. The one required check is **`ci-passed`**: it passes only when
every other job passed.

## Jobs

| Job | What runs | Same thing on your machine |
|---|---|---|
| `backend` | Java 21 (Temurin), `tesseract-ocr`, `mvn -B verify` in `backend/` with the PostgreSQL tests on, against a PostgreSQL 16 service container (`LEXAVAULT_TEST_PG_URL`, `_USER`, `_PASSWORD`). platform-core 1.0.0 from GitHub Packages through a `settings.xml` written in the job | Start a throwaway PostgreSQL (never `securedoc-postgres`), set the three `LEXAVAULT_TEST_PG_*` variables, then `cd backend; mvn -B verify` (the production build, without a development licence key; no test depends on the profile, task 0054) |
| `frontend` | Node 22, `npm ci`, `npm run build` (typecheck + Vite build), Playwright Chromium and WebKit, `npm run test:standalone` | `cd frontend/securedoc-ui; npm ci; npm run build; npm run test:standalone` |
| `build-script` | Java 21, Node 22, `bash Build-LexaVault-All.sh --skip-tests`: the build script with its jar checks (task 0097) | `bash Build-LexaVault-All.sh --skip-tests` |
| `product-names` | `scripts/ci/check-product-names.sh`: fails if another product's name (the list in `scripts/ci/product-names.txt`) is in tracked code, SQL, JSON, XML, YAML, properties, scripts, styles or install guides. Strict since task 0045: no baseline | `bash scripts/ci/check-product-names.sh` (Git Bash on Windows) |
| `ci-passed` | Needs the four jobs above | |

`test:standalone` is every frontend suite that runs without the app: `test:auth`, `test:vocabulary`,
`test:ui-framework`, `test:oidc-signin`, `test:tablet` (WebKit), `test:a11y`. The browser suites start their own Vite
server and mock the API and the identity provider. Not in CI: `test:e2e` and `scripts/Test-LexaVault-ExportE2E.ps1`
(they need the backend jar, a database and the Ollama mock).

The product-name guard does not check:
- `docs/` (history) and `CLAUDE.md` (it states the rule);
- database history that can't change: changelog `017` (Liquibase keeps its file name, so the master changelog's line
  naming it is allowed), and migration `046` with its test `DocumentStoreNamesMigrationPostgresTest`, which rename the
  old stored values;
- the owner's Docker containers `sdv-postgres` and `sdv-vault`, which the safety lists in the scripts name so that no
  script ever touches them.

The frontend's "no screen names another product" test (`exportRules.test.ts`) reads the same names file.

## The secret

| Name | What | Scope |
|---|---|---|
| `PLATFORM_CORE_READ_TOKEN` | A GitHub token that can read the `mohankandar/platform-core` packages | `read:packages` only |

Repository secret of `mohankandar/secure-document-platform`. The job passes it in the environment; `settings.xml` only
names the variable (`${env.PLATFORM_CORE_READ_TOKEN}`), so the token is never written or printed. To set it from the
token you already use locally, without showing it:

```
$env:GITHUB_PACKAGES_TOKEN | gh secret set PLATFORM_CORE_READ_TOKEN -R mohankandar/secure-document-platform
```

Actions are pinned to full commit SHAs (the version is in a comment next to each).
