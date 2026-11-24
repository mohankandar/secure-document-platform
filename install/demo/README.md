# LexaVault demo input files

Demo values only. Nothing here belongs to a real customer, login system or product installation. Task 0037.

| File | What it is |
|---|---|
| `licence-demo.json` | Licence `LIC-LV-DEMO-0001` v1 for customer `TEN-DEMO-0001` ("LexaVault Demo Customer"), product `LEXAVAULT`, two entity slots `ENT-DEMO-0001` and `ENT-DEMO-0002` (1,000 pages a month each), kind `NON_PRODUCTION` (the app shows "Test environment"), perpetual. Signed with LexaVault's **development** key `dev-lexavault-demo-1`. |
| `development-trusted-keys.json` | That development public key, marked `"development": true` (task 0041), so setup takes a local `http` login system under the demo licence. It is for reading and tests only: nothing passes it to setup any more (task 0054). The copy that counts is inside a development build's jars (`backend/licence-build/dev`, `mvn -Pdev-build`); a production build does not hold it and refuses the demo licence (`SETUP_LICENCE_REFUSED: LICENCE_UNKNOWN_KEY_ID`). |
| `signin-demo.json` | Sign-in file (`platform-signin/1`, direct mode) with a placeholder https login system. No secrets. |
| `role-mapping-lexavault-demo.csv` | Group-to-role grants per slot, with the local development Keycloak's group names, and one platform-wide row (`*`): the platform operations group gets `PLATFORM_OPERATIONS_ADMIN` in every entity (task 0054). |

The development private key was thrown away after signing. To make new files (a new key), from
`backend/securedoc-api`: `mvn test -Dtest=DemoFilesGenerator -Dlexavault.demo.regenerate=true`.

## How they are used

The local start scripts (`Start-LexaVault-Local.ps1`, `Start-LexaVault-OIDC.ps1`, `Start-LexaVault-Local.sh`) call
`scripts/Initialize-LexaVault-LocalDatabase.*`, which runs the real install steps in order (with a development build's
jars only: `Build-LexaVault-All` without `-Production`, or `mvn -Pdev-build`):

1. the schema step (`docs/install/SCHEMA_STEP.md`);
2. while the demo step has not made the demo entity yet (`DemoStep installed` exits 4): setup with these files.
   That is a fresh database, a database from before task 0037, or one whose demo step stopped half-way; setup is
   safe to rerun and then changes nothing. With the OIDC start, setup runs every time, with the local login system
   put in (below);
3. the LexaVault demo step (`com.securedoc.install.DemoStep`), always; a rerun changes nothing.

### On a database from before task 0037

Such a database holds the old fixed demo tenant `00000000-0000-0000-0000-000000000001` (slug `local-demo`, name
"Local Demo Tenant") and its data. The demo step never changes it. Because `local-demo` is taken (the slug is unique),
the new demo entity is created as **"Local Demo Tenant (ENT-DEMO-0001)"**, slug **`local-demo-ent-demo-0001`**, and
the step prints a `note` line saying so. A fresh database keeps the plain name and slug. The old tenant's data stays
reachable in the LOCAL mode with the header `X-Tenant-Id: 00000000-0000-0000-0000-000000000001`.

### With the OIDC start

`signin-demo.json` holds a placeholder https login system (`demo-okta`). Since task 0041 (platform-core 0.2.0) setup
itself takes the local Keycloak: `http` on `localhost` is allowed because the demo licence is signed by
`dev-lexavault-demo-1`, which the development build marks `"development": true`. On every OIDC start,
`Start-LexaVault-OIDC.ps1` writes `.run\oidc-signin\signin-local-oidc.json` (the demo file with `-IssuerUri`,
`-ProviderId`, `-Audience` and the web client put in; `com.securedoc.install.LocalOidcSignin`) and runs setup with it
and `--replace-connection`. When that moves an existing database to another login system, setup keeps the old one's
grants, lists them and exits 3 (platform-core 0.7.0); the start takes that. That is the connection sign-in uses. When it already matches, setup changes nothing. The
demo step no longer writes the login connection (its `--oidc-*` options are gone).

## What the demo step creates

For slot `ENT-DEMO-0001` (entity UUID `30216241-b724-5a3a-aef3-3034fd546d17`, derived from the slot ID):

- the slot's activation, named "Local Demo Tenant", by actor `lexavault-demo-step`, under the stored licence;
- LexaVault's entity row (`tenants`, slug `local-demo`, ACTIVE) with 7 capabilities: document upload, OCR, sensitive
  data detection, redaction, human review, vault, audit;
- 3 identifier rules: Contract Number (primary, 12 digits), Member Number (11 digits), Service Entity (3 digits,
  000 to 005);
- the default sensitive-data policy;
- the "Local Scanner Bridge" ingestion source (client principal `scanner-bridge-local`).

Slot `ENT-DEMO-0002` stays `NOT_ACTIVATED`, for the activation screen (task 0039).

Everything is created only when missing; a rerun changes nothing. This replaces `LocalDemoBootstrap` and
`OidcConnectionBootstrap`, which inserted rows at every startup.
