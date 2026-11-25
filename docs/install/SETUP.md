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
the app jar: setup is not on the app's classpath. The wrapper finds it (`-JarPath` names another one). It passes what LexaVault
ships:

| Option | File | Note |
|---|---|---|
| `--product` | `LEXAVAULT` | |
| `--product-roles` | `install/product-roles.json` | LexaVault's roles and aliases (below); `"customerKeys": "required"` |

**Customer keys (task 0060).** Setup creates your customer's two keys in the key service, with a login that has only the
policy `platform-crypto-setup`, in the environment for this run only (`PLATFORM_SETUP_VAULT_ADDR` and `_TOKEN`, `_TOKEN_FILE`
or `_ROLE_ID` with `_SECRET_ID`). Without it setup is refused (`SETUP_KEY_SERVICE_SETTINGS_MISSING`); `--no-customer-keys` is
refused (`SETUP_CUSTOMER_KEYS_REQUIRED`). A customer set up earlier without keys gets them from the next run. See
`KEY_SERVICE.md`. Only the local development start uses `install/demo/product-roles-development.json`, where keys are
optional, when no key service is set in the environment.

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

**Sign-in connections are set through setup and changed in Admin › Sign-in** (IT Admin, task 0058). A change is a draft:
it is tested (optionally with a pasted token, which is checked and forgotten), switched to, and can be gone back from; the
connection in use keeps working until the switch, and setup never touches a draft. The app takes tokens of the login
system in its settings only, so a draft with another **issuer, provider ID or audience** is tested there but not switched
to (`SIGN_IN_SETTINGS_DIFFER`; everybody would be refused): change `securedoc.security.oidc.*` and the web app's settings,
and run setup with `--replace-connection`, at the same restart. The active connection can be tested at any time (task 0054): "Test sign-in" (IT Admin) reads the login system's published details and keys and shows that it
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
- customer keys for sensitive case fields: when LexaVault uses platform-fields (stored documents are encrypted, task 0060);
- entity time zones: the sign-in file's optional `timeZone` and `entityTimeZones` are stored by setup today; the app
  uses them (task 0058) for daily schedules, reports and the times it shows, and the Business Admin changes an entity's own
  in Admin › Entities. Without any, everything works as before. Retention timers are hours from a fixed moment in every zone.

## The licence is live (task 0057)

LexaVault itself uses the licence setup stores: it does not start without a valid one, takes in new work on its word and
counts pages. Give the app `platform-tenant.properties` (`-ConfigOut`): see `LICENSING.md`.

## Who gets in (task 0058)

There is one way to sign in: the login system's groups and the **grants setup loads** (`idn_group_grant`, the role mapping
file) decide the entities a person may enter and their roles in each. One entity: straight in. More than one: the entity used
last, else a chooser. A sending system's entity is the one whose enabled push connection names its client.

These settings are gone and are **ignored** when still set, whatever their value:
`securedoc.security.oidc.shared-issuer-tenant-selection-enabled`, `.tenant-id`, `.tenant-claim`, `.role-mappings.*`
(and `SECUREDOC_SECURITY_OIDC_TENANT_ID`, `_TENANT_CLAIM`, `SECUREDOC_SECURITY_ROLE_*`, `SECUREDOC_SECURITY_LEGACY_ROLE_*`).
**Before this upgrade, check that every group named in `role-mappings` is in the role mapping file setup loaded**: a role
that came only from the setting is gone. `securedoc.security.oidc.persistent-connection-enabled=false` refuses everybody.
A web app on another origin now sends `X-Selected-Tenant-Id`, which the API allows.

## Which address LexaVault serves

LexaVault listens on `127.0.0.1` only (task 0055). Set `SECUREDOC_SERVER_ADDRESS` to the address it should serve, for example
the server's internal address behind your TLS proxy (`0.0.0.0` for every address, as in a container). With the proxy on the
same machine nothing needs setting. Two servers that talk to one database each need it when the proxy is elsewhere.

A request body that is not a file is limited to 2 MB (`securedoc.api.max-request-body`, LV-SYS-015).

## After setup

Your entity slots are stored as `NOT_ACTIVATED`. A Business Admin activates, names,
suspends and resumes them in Admin › Entities (task 0058): the entity they are in, and every other slot whose mapping rows
give one of their groups BUSINESS_ADMIN, also before it is activated. The **first** entity of a new customer is activated with
a command, because nobody can sign in while none is active (task 0075):

```
scripts/Invoke-LexaVault-FirstEntity.sh ENT-... "Entity name"
scripts\Invoke-LexaVault-FirstEntity.ps1 -SlotId ENT-... -Name "Entity name"
```

Run it after the schema step and setup, with the app's normal settings in the environment. It needs a licence in force that
holds the slot, works only while no entity was ever activated, and changes nothing when run again with the same values. It
is recorded in the entity's audit log as an install action (`source=INSTALL`). Exit codes: 0 done, 1 failed, 2 refused.

**A scanner with sign-in (task 0075).** Create a client in your login system that may use client credentials, with LexaVault's
API as its audience. In Admin, Integrations, make a SCANNER source in the entity (authentication OAuth2 client credentials) whose
client principal is that client's user name in its tokens. Give the Scanner Bridge `SECUREDOC_SCAN_BRIDGE_OIDC_TOKEN_URI`,
`_CLIENT_ID` and `_CLIENT_SECRET` (environment only; optional `_SCOPE`). A client that is a scanner in two entities is refused.

On a
development or demo machine, the LexaVault demo step activates the demo entity (`install/demo/README.md`).

An install that existed before task 0037 copies its login connection and grants once with the identity migration
(`docs/install/IDENTITY_MIGRATION.md`).
