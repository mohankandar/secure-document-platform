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
| `--no-customer-keys` | | LexaVault encrypts nothing with platform-core yet. platform-core 0.2.0 refuses this flag only for a product whose `product-roles.json` says `"customerKeys": "required"`; LexaVault's says `optional` |

**Licence keys (platform-core 0.7.0, task 0054).** There is no `--keys`: the keys that a licence is checked with are inside
the setup jar (`docs/install/BUILD.md`). Only a development build takes the demo licence; a production build refuses it
(`SETUP_LICENCE_REFUSED: LICENCE_UNKNOWN_KEY_ID`), and one without keys refuses every licence (`SETUP_KEYS_INVALID`).

**Moving to another login system (platform-core 0.7.0).** Grants stored under the login system that is replaced are kept
and listed as `not removed ... (under the replaced login system ...)`; setup is done, with exit code 3. They do nothing
unless that login system is made active again. `-RemoveUnlistedGrants` removes them.

**Group names** count exactly as your login system sends them, in the mapping file and at sign-in: no trimming, no case
folding, and a groups claim that is one text is one group.

**Login system on a local machine (platform-core 0.2.0).** The sign-in file's issuer must be `https`, except a local
test login server: `http` on `localhost`, `127.0.0.1` or `[::1]`, and only under a licence signed by a key marked
`"development": true` that the build trusts (`SETUP_HTTP_ISSUER_NOT_ALLOWED` otherwise). The demo licence's key is such
in a development build; production keys never are. The local OIDC start uses this.

**Grant history.** Every grant setup adds or removes is recorded in `idn_grant_history` with setup's run ID (identity
schema version 3). LexaVault's Audit page lists them under "Sign-in access changes" (read-only).

**Sign-in connections are set through setup.** The app's Admin › Sign-in screen only shows the active connection, and
tests it (task 0054): "Test sign-in" (IT Admin) reads the login system's published details and keys and shows that it
answers, or the exact `IDN_TEST_…` code. No token is asked for; nothing is stored or changed; each test is audited with
codes only, and repeated clicks within 10 seconds are one call. It calls only the stored issuer (and the key address that
issuer names), under the platform's outbound rules:

| Setting | Default | |
|---|---|---|
| `platform.outbound.installation` | `ON_PREMISES` | or `HOSTED` |
| `platform.outbound.allow-internal-addresses` | `false` | `true` when your login system is on an internal address (it then also allows plain http there). Cloud-metadata and link-local addresses are always refused |
| `platform.outbound.private-ca-file` | none | your private certificate authorities (PEM, absolute path) |
| `platform.outbound.proxy.host`, `.port`, `.bypass` | none | the outbound proxy. A proxy with sign-in is not supported and stops the app (`OUTBOUND_SETTINGS_INVALID`) |

A login system on `http://localhost` is tested only under a licence signed by a development key, so only in a
development build; no setting changes that.

**Integration clients are machines (platform-core 0.7.0, task 0054).** A token that carries a person's claim (email, name,
groups, ...) is refused on the intake paths (LV-AUT-008), whatever user name it names. If your login system puts one of
those claims into its machine tokens, list the claims that mark a person in
`securedoc.security.oidc.machine-user-claims`.

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
| `PLATFORM_OPERATIONS_ADMIN` | Platform Operations | The platform role (task 0054): only on a row with `*` as its `entity_id`, for every entity of your organisation. On an entity's row it is refused (`SETUP_PLATFORM_ROLE_ON_ENTITY`), and a `*` row takes no other role (`SETUP_ENTITY_ROLE_PLATFORM_WIDE`) |

Write role and alias names exactly as above (upper case). Any other spelling is refused by setup, and a grant that
reaches the database some other way with an unknown role refuses sign-in for that login system (LV-AUT-007), as
before. A grant stored under an alias name before task 0041 is still read as the role it stands for.

## Not wired yet

These setup inputs come with their own tasks; nothing is needed for them now:

- the release's production licence keys (`docs/install/BUILD.md`);
- notifications settings;
- integration client bindings (client ID -> entity -> source): a later task;
- customer keys for sensitive case fields: when LexaVault encrypts with platform-core (`--no-customer-keys` until then);
- entity time zones: the sign-in file's optional `timeZone` and `entityTimeZones` are stored by setup today; the app
  uses them from task 0039.

## The licence is live (task 0057)

LexaVault itself uses the licence setup stores: it does not start without a valid one, takes in new work on its word and
counts pages. Give the app `platform-tenant.properties` (`-ConfigOut`): see `LICENSING.md`.

## After setup

Your entity slots are stored as `NOT_ACTIVATED`. Activating and naming them in the app comes with task 0039. On a
development or demo machine, the LexaVault demo step activates the demo entity (`install/demo/README.md`).

An install that existed before task 0037 copies its login connection and grants once with the identity migration
(`docs/install/IDENTITY_MIGRATION.md`).
