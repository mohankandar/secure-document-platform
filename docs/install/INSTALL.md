# Installing LexaVault: step by step, for IT

This is the order of a first install from LexaVault's package. Each step links to its own guide for the details. The install
proof (task 0114, `.github/workflows/install-proof.yml`) follows exactly these steps on a clean Linux and a clean Windows
machine, so if a step here is wrong, the proof fails.

## What you need

- Java 21 (`java` and `jar`), and PostgreSQL 16 with its command-line tools (`psql`, `pg_dump`, `pg_restore`).
- Tesseract OCR 5 with its English language data, on the PATH (`tesseract`): LexaVault reads scanned pages with it and
  adds the searchable text to redacted pages. Without it a document stops (LV-PRC-005).
- The package: `lexavault.jar`, `lexavault-setup.jar`, `lexavault-scan-bridge.jar`, `ui/` (the screens), `install/`, `docs/`
  and `VERSION`.
- Your licence, your sign-in file and your role mapping (`SETUP.md`). A development package also holds the demo files
  (`install/demo/`); a production package refuses them.
- The key service: HashiCorp Vault with Transit (`KEY_SERVICE.md`). Without it LexaVault does not start.
- Your login system (OIDC), with the groups of your role mapping in its tokens.
- For the AI: a model server on this machine or your private network (`application.yml`, `securedoc.ai.*`), pinned by digest.

## 1. The database and its two accounts

An administrator account creates two database logins and the database:

```
create role lexavault_owner login password '...';   -- owns the tables, runs the schema step
create role lexavault_app login password '...';     -- the everyday account LexaVault runs with
create database lexavault owner lexavault_owner;
revoke all on database lexavault from public;
grant connect on database lexavault to lexavault_app;
```

Keep both passwords in your secret store. LexaVault never prints them.

## 2. The schema step, with the owner account

```
LEXAVAULT_SCHEMA_DB_URL=jdbc:postgresql://dbhost:5432/lexavault
LEXAVAULT_SCHEMA_DB_USER=lexavault_owner
LEXAVAULT_SCHEMA_DB_PASSWORD=...
java -cp lexavault.jar -Dloader.main=com.securedoc.install.SchemaStep \
  org.springframework.boot.loader.launch.PropertiesLauncher apply --runtime-user lexavault_app
```

It makes or updates every table, and gives `lexavault_app` exactly its rights: data rights on LexaVault's tables (the audit
trail is added to and read, never changed), the platform tables' own rights, and nothing on the step's history. Details:
`SCHEMA_STEP.md`. Run it again on every upgrade, with every LexaVault server stopped.

## 3. Setup, with the everyday account

```
PLATFORM_SETUP_DB_URL=jdbc:postgresql://dbhost:5432/lexavault
PLATFORM_SETUP_DB_USER=lexavault_app
PLATFORM_SETUP_DB_PASSWORD=...
PLATFORM_SETUP_VAULT_ADDR=https://vault.internal:8200   # the key service; setup makes the customer's keys there
PLATFORM_SETUP_VAULT_TOKEN=...                          # a login with policy platform-crypto-setup only (KEY_SERVICE.md)
java -cp lexavault-setup.jar -Dloader.main=com.documentplatform.setup.SetupCommand \
  org.springframework.boot.loader.launch.PropertiesLauncher apply --licence licence.json --signin signin.json \
  --product LEXAVAULT --product-roles install/product-roles.json --config-out platform-tenant.properties
```

Run `plan` first and read it. Setup writes `platform-tenant.properties`; keep it beside LexaVault (or name it in
`PLATFORM_TENANT_CONFIG`). Details: `SETUP.md`, `LICENSING.md`, `KEY_SERVICE.md` (customer keys).

Then activate and name the first entity, with LexaVault's settings of step 4 in the environment (it starts, does this and
ends; running it again with the same values changes nothing):

```
java -jar lexavault.jar --activate-first-entity --entity-slot=ENT-... --entity-name="Your entity's name"   --server.port=0 --server.address=127.0.0.1
```

It answers `ACTIVATED` (or `UNCHANGED`). Exit codes: 0 done, 2 refused (the code says why), 1 failed. Details: `SETUP.md`.

## 4. Start LexaVault with the everyday account

```
SPRING_DATASOURCE_URL=jdbc:postgresql://dbhost:5432/lexavault
SPRING_DATASOURCE_USERNAME=lexavault_app
SPRING_DATASOURCE_PASSWORD=...
SECUREDOC_SECURITY_OIDC_ISSUER_URI=https://login.example/realms/yours   # your login system, as in the sign-in file
SECUREDOC_SECURITY_OIDC_AUDIENCE=...                                    # the sign-in file's apiAudience
SECUREDOC_SECURITY_OIDC_PROVIDER_ID=...                                 # the sign-in file's providerId
SECUREDOC_STORAGE_LOCAL_ROOT_PATH=/var/lib/lexavault/storage            # where LexaVault keeps its work files
PLATFORM_ERASELOG_LOCATION=/var/lib/lexavault-erase-log                 # outside the database and its backups (ERASE_LOG.md)
PLATFORM_CRYPTO_VAULT_ADDRESS=https://vault.internal:8200               # the key service, with LexaVault's own login
PLATFORM_CRYPTO_VAULT_ROLEID=...  PLATFORM_CRYPTO_VAULT_SECRETIDFILE=... # AppRole, policy platform-crypto-app (KEY_SERVICE.md)
java -jar lexavault.jar
```

The issuer, audience and provider ID must be the sign-in file's: a token of any other login system is refused. It is ready
when `/actuator/health` answers 200 (UP: every required check is up, the login system and the database too;
`/actuator/health/readiness` exists only on Kubernetes). Its own checks: `MONITORING.md`. Where files go, the delivery
(`SECUREDOC_DELIVERY_TYPE`, for a folder `local` with `SECUREDOC_DELIVERY_LOCAL_OUTPUT_PATH`), the AI server and the
sign-in: `DESTINATIONS.md`, `IDENTITY_MIGRATION.md`, `NOTIFICATIONS.md`, `application.yml`.

## 5. Check it

Sign in as a person of the entity, create a case with one document and follow it to its delivery (Cases, or the API),
deciding what the review shows. Then stop LexaVault and start it again: the case is still there.

## 6. Backup and restore

Back up the whole database (`pg_dump -Fc`), and restore it to a test copy once to know it works (`backup-and-restore.md`).
The schema step's `status` reads the copy.

## 7. Upgrade

1. Back up the database (step 6).
2. Stop every LexaVault server.
3. Run the new build's schema step with the owner account, as in step 2 (`apply --runtime-user lexavault_app`).
4. Start the new build as in step 4, and check it (step 5): the cases from before are there.

Setup and the first entity are not run again.

## The install proof

The proof runs these steps with the demo files, a throwaway login system (Keycloak from its own zip, with one person and one
administrator), a throwaway key service (Vault from its own zip, development mode; its root token stands in for the logins)
and a stand-in AI server (the runners have no GPU), and checks that no secret is in the package. Its case has
one finding the AI is not sure of, decided in review. It upgrades from the previous build: the previous package installed in
a database of its own with a case, then steps 7.2 to 7.4 with this package. It runs on every release tag and once a month; a
failure opens an issue labelled `install-proof`. Results and guide fixes: `docs/work/0114-report.md`.
