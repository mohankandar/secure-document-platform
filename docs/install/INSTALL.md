# Installing LexaVault: step by step, for IT

This is the order of a first install from LexaVault's package. Each step links to its own guide for the details. The install
proof (task 0114, `.github/workflows/install-proof.yml`) follows exactly these steps on a clean Linux and a clean Windows
machine, so if a step here is wrong, the proof fails.

## What you need

- Java 21 (`java` and `jar`), and PostgreSQL 16 with its command-line tools (`psql`, `pg_dump`, `pg_restore`).
- The package: `lexavault.jar`, `lexavault-setup.jar`, `lexavault-scan-bridge.jar`, `ui/` (the screens), `install/`, `docs/`
  and `VERSION`.
- Your licence, your sign-in file and your role mapping (`SETUP.md`). A development package also holds the demo files
  (`install/demo/`); a production package refuses them.
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
java -cp lexavault-setup.jar -Dloader.main=com.documentplatform.setup.SetupCommand \
  org.springframework.boot.loader.launch.PropertiesLauncher apply --licence licence.json --signin signin.json \
  --product LEXAVAULT --product-roles install/product-roles.json --config-out platform-tenant.properties
```

Run `plan` first and read it. Setup writes `platform-tenant.properties`; keep it beside LexaVault (or name it in
`PLATFORM_TENANT_CONFIG`). Details: `SETUP.md`, `LICENSING.md`, `KEY_SERVICE.md` (customer keys).

## 4. Start LexaVault with the everyday account

```
SPRING_DATASOURCE_URL=jdbc:postgresql://dbhost:5432/lexavault
SPRING_DATASOURCE_USERNAME=lexavault_app
SPRING_DATASOURCE_PASSWORD=...
java -jar lexavault.jar
```

It is ready when `/actuator/health/readiness` answers 200. Its own checks: `MONITORING.md`. Where files go, the delivery and
the sign-in: `DESTINATIONS.md`, `IDENTITY_MIGRATION.md`, `NOTIFICATIONS.md`.

## 5. Check it

Create a case with one document and follow it to its delivery (Cases, or the API). Then stop LexaVault and start it again:
the case is still there.

## 6. Backup and restore

Back up the whole database (`pg_dump -Fc`), and restore it to a test copy once to know it works (`backup-and-restore.md`).
The schema step's `status` reads the copy.

## The install proof

The proof runs these steps with the demo files and a stand-in AI server (the runners have no GPU), and checks that no secret
is in the package. It runs on every release tag and once a month; a failure opens an issue labelled `install-proof`. Steps it
does not prove yet are in `docs/work/0114-report.md`.
