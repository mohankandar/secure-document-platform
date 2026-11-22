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

(`scripts/Setup-LexaVault.sh plan|apply --licence ... --signin ...` on Linux.) The wrapper passes what LexaVault
ships:

| Option | File | Note |
|---|---|---|
| `--product` | `LEXAVAULT` | |
| `--product-roles` | `install/product-roles.json` | LexaVault's roles (below) |
| `--keys` | `install/trusted-keys.json` | The release's production signing keys. **Not shipped yet: it comes with task 0038 (licensing).** Until then pass `-Keys`; the demo uses `install/demo/development-trusted-keys.json` |
| `--no-customer-keys` | | Placeholder until task 0041 (crypto): LexaVault keeps no sensitive case field values yet |

Optional, passed through: `-ConfigOut`, `-RunLog`, `-RemoveUnlistedGrants`, `-ReplaceConnection`.

## LexaVault's roles (`install/product-roles.json`)

| Role in the mapping file | Shown as | Note |
|---|---|---|
| `DOCUMENT_PROCESSOR` | Document Processor | |
| `DOCUMENT_SUPERVISOR` | Supervisor | |
| `BUSINESS_ADMIN` | Business Admin | |
| `REPORT_VIEWER` | Report Viewer | |
| `TENANT_IT_ADMIN` | IT Admin | |
| `USER`, `REVIEWER` | Document Processor | Today's identity-name aliases, accepted exactly as written |
| `APPROVER` | Supervisor | Alias, as above |
| `PLATFORM_OPERATIONS_ADMIN` | Platform Operations | The platform role: never granted in a mapping file |

Write role names exactly as above (upper case). Any other spelling is refused by setup, and a grant that reaches the
database some other way with an unknown role refuses sign-in for that login system (LV-AUT-007), as before.

## Not wired yet

These setup inputs come with their own tasks; nothing is needed for them now:

- the release's production `trusted-keys.json`, and the licence used by the app itself
  (`platform.licensing.tenant-id` from `platform-tenant.properties`): task 0038;
- notifications settings: task 0040;
- integration client bindings (client ID -> entity -> source): a later task;
- customer keys for sensitive case fields (`--no-customer-keys` is passed until then): task 0041;
- entity time zones: the sign-in file's optional `timeZone` and `entityTimeZones` are stored by setup today; the app
  uses them from task 0039.

## After setup

Your entity slots are stored as `NOT_ACTIVATED`. Activating and naming them in the app comes with task 0039. On a
development or demo machine, the LexaVault demo step activates the demo entity (`install/demo/README.md`).

An install that existed before task 0037 copies its login connection and grants once with the identity migration
(`docs/install/IDENTITY_MIGRATION.md`).
