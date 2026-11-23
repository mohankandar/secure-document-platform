# Setup: install guide for IT (LexaVault)

LexaVault uses platform-core's setup command. It loads your licence, your customer and entity records, your login
system and your group-to-role mapping into LexaVault's database. The full guide is platform-core's
`docs/install/setup.md`; this page says what is specific to LexaVault.

## Before you start

1. The schema step has run (`docs/install/SCHEMA_STEP.md`). Setup never creates tables.
2. You have your licence, your sign-in file and your role mapping file (formats in platform-core's guide). LexaVault's
   product code is **`LEXAVAULT`**: use it in the sign-in file's `products` and in the mapping file's `# Product:` line.
3. Database settings in the environment only: `PLATFORM_SETUP_DB_URL`, `PLATFORM_SETUP_DB_USER`,
   `PLATFORM_SETUP_DB_PASSWORD`. The app's everyday database user is enough.

## Running it

```
scripts/Setup-LexaVault.ps1 plan  -Licence licence.json -Signin signin.json
scripts/Setup-LexaVault.ps1 apply -Licence licence.json -Signin signin.json
```

(`scripts/Setup-LexaVault.sh plan|apply --licence ... --signin ...` on Linux.)

Setup runs from its own jar, `backend/securedoc-setup/target/securedoc-setup-0.1.0-SNAPSHOT.jar` (task 0041), not from
the app jar: setup brings platform-crypto and platform-integration (customer keys, integration profile checks), and
the app's classpath does not carry them. The wrapper finds it (`-JarPath` names another one). It passes what LexaVault
ships:

| Option | File | Note |
|---|---|---|
| `--product` | `LEXAVAULT` | |
| `--product-roles` | `install/product-roles.json` | LexaVault's roles and aliases (below); `"customerKeys": "optional"` |
| `--keys` | `install/trusted-keys.json` | The release's production signing keys. **Not shipped yet.** Until then pass `-Keys`; the demo uses `install/demo/development-trusted-keys.json` |
| `--no-customer-keys` | | LexaVault encrypts nothing with platform-core yet. platform-core 0.2.0 refuses this flag only for a product whose `product-roles.json` says `"customerKeys": "required"`; LexaVault's says `optional` |

**Login system on a local machine (platform-core 0.2.0).** The sign-in file's issuer must be `https`, except a local
test login server: `http` on `localhost`, `127.0.0.1` or `[::1]`, and only under a licence signed by a key marked
`"development": true` in the trusted-keys file (`SETUP_HTTP_ISSUER_NOT_ALLOWED` otherwise). The demo licence and
`install/demo/development-trusted-keys.json` are such; production keys never are. The local OIDC start uses this.

**Grant history.** Every grant setup adds or removes is recorded in `idn_grant_history` with setup's run ID (identity
schema version 3). LexaVault's Audit page lists them under "Sign-in access changes" (read-only).

**Sign-in connections are set through setup.** The app's Admin › Sign-in screen only shows the active connection.

Optional, passed through: `-ConfigOut`, `-RunLog`, `-RemoveUnlistedGrants`, `-ReplaceConnection`.

## LexaVault's roles (`install/product-roles.json`)

| Role in the mapping file | Shown as | Note |
|---|---|---|
| `DOCUMENT_PROCESSOR` | Document Processor | |
| `DOCUMENT_SUPERVISOR` | Supervisor | |
| `BUSINESS_ADMIN` | Business Admin | |
| `REPORT_VIEWER` | Report Viewer | |
| `TENANT_IT_ADMIN` | IT Admin | |
| `USER`, `REVIEWER` | Document Processor | Aliases (`"aliases"` in `product-roles.json`, platform-core 0.2.0): setup stores the grant as `DOCUMENT_PROCESSOR` |
| `APPROVER` | Supervisor | Alias: stored as `DOCUMENT_SUPERVISOR` |
| `PLATFORM_OPERATIONS_ADMIN` | Platform Operations | The platform role: never granted in a mapping file |

Write role and alias names exactly as above (upper case). Any other spelling is refused by setup, and a grant that
reaches the database some other way with an unknown role refuses sign-in for that login system (LV-AUT-007), as
before. A grant stored under an alias name before task 0041 is still read as the role it stands for.

## Not wired yet

These setup inputs come with their own tasks; nothing is needed for them now:

- the release's production `trusted-keys.json`, and the licence used by the app itself
  (`platform.licensing.tenant-id` from `platform-tenant.properties`): task 0038;
- notifications settings: task 0040;
- integration client bindings (client ID -> entity -> source): a later task;
- customer keys for sensitive case fields: when LexaVault encrypts with platform-core (`--no-customer-keys` until then);
- entity time zones: the sign-in file's optional `timeZone` and `entityTimeZones` are stored by setup today; the app
  uses them from task 0039.

## After setup

Your entity slots are stored as `NOT_ACTIVATED`. Activating and naming them in the app comes with task 0039. On a
development or demo machine, the LexaVault demo step activates the demo entity (`install/demo/README.md`).

An install that existed before task 0037 copies its login connection and grants once with the identity migration
(`docs/install/IDENTITY_MIGRATION.md`).
