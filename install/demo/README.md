# LexaVault demo input files

Demo values only. Nothing here belongs to a real customer, login system or product installation. Task 0037.

| File | What it is |
|---|---|
| `licence-demo.json` | Licence `LIC-LV-DEMO-0001` v1 for customer `TEN-DEMO-0001` ("LexaVault Demo Customer"), product `LEXAVAULT`, two entity slots `ENT-DEMO-0001` and `ENT-DEMO-0002` (1,000 pages a month each), kind `NON_PRODUCTION` (the app shows "Test environment"), perpetual. Signed with LexaVault's **development** key `dev-lexavault-demo-1`. |
| `development-trusted-keys.json` | That development public key. Only demo and test installs pass it to setup. A production install never lists it, so it refuses the demo licence (`SETUP_LICENCE_REFUSED: LICENCE_UNKNOWN_KEY_ID`). |
| `signin-demo.json` | Sign-in file (`platform-signin/1`, direct mode) with a placeholder https login system. No secrets. |
| `role-mapping-lexavault-demo.csv` | Group-to-role grants per slot, with the local development Keycloak's group names. |

The development private key was thrown away after signing. To make new files (a new key), from
`backend/securedoc-api`: `mvn test -Dtest=DemoFilesGenerator -Dlexavault.demo.regenerate=true`.

## How they are used

The local start scripts (`Start-LexaVault-Local.ps1`, `Start-LexaVault-OIDC.ps1`, `Start-LexaVault-Local.sh`) call
`scripts/Initialize-LexaVault-LocalDatabase.*`, which runs the real install steps in order:

1. the schema step (`docs/install/SCHEMA_STEP.md`);
2. on a fresh database only (no customer stored yet): setup with these files;
3. the LexaVault demo step (`com.securedoc.install.DemoStep`).

## What the demo step creates

For slot `ENT-DEMO-0001` (entity UUID `30216241-b724-5a3a-aef3-3034fd546d17`, derived from the slot ID):

- the slot's activation, named "Local Demo Tenant", by actor `lexavault-demo-step`, under the stored licence;
- LexaVault's entity row (`tenants`, slug `local-demo`, ACTIVE) with 7 capabilities: document upload, OCR, sensitive
  data detection, redaction, human review, vault, audit;
- 3 identifier rules: Contract Number (primary, 12 digits), Member Number (11 digits), Service Entity (3 digits,
  000 to 005);
- the default sensitive-data policy;
- the "Local Scanner Bridge" ingestion source (client principal `scanner-bridge-local`).

With `--oidc-issuer` (the local OIDC start only) it also points the customer's LexaVault login connection at the local
development Keycloak. Setup cannot: its sign-in file takes only `https` issuers.

Slot `ENT-DEMO-0002` stays `NOT_ACTIVATED`, for the activation screen (task 0039).

Everything is created only when missing; a rerun changes nothing. This replaces `LocalDemoBootstrap` and
`OidcConnectionBootstrap`, which inserted rows at every startup.
