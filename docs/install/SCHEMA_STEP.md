# Schema step: install guide for IT

The schema step is the only thing that changes LexaVault's database. The app never changes the database at startup
for the platform tables, and the schema step is run by you, before setup and before starting a new release.

It runs three things, in this order. Each keeps its own history table:

| Run | What it creates | Source in the release | History table |
|---|---|---|---|
| App tables | LexaVault's own tables (cases, documents, audit, ...) | Liquibase changelog `db/changelog/db.changelog-master.xml` | `databasechangelog` |
| Identity | Customer, entity slots, login connection, group grants, integration client bindings, grant history (`idn_` tables; version 3, platform-core 0.2.0) | platform-core Flyway location `db/platform/identity` | `idn_schema_history` |
| Licensing | The stored licence, usage ledger (`lic_` tables). Setup stores the licence here | platform-core Flyway location `db/platform/licensing` | `lic_schema_history` |

The platform runs start their own history at version 0 in a schema that already holds the app's tables, so their
version 1 always runs. The identity run always goes to the latest version in the release: a database at identity
version 1 or 2 gets the missing versions on the next run (platform-core 0.2.0 refuses anything older than version 3,
`IDN_VERSION_OLDER`). Version 3 (`V3__grant_history.sql`) adds `idn_grant_history`, insert-only, which the Audit page
reads. Running the step again changes nothing.

## Running it

Run it as the schema owner. The database settings come from the environment, never from options, and the password is
never printed:

| Variable | Example |
|---|---|
| `LEXAVAULT_SCHEMA_DB_URL` | `jdbc:postgresql://dbhost:5432/securedoc` |
| `LEXAVAULT_SCHEMA_DB_USER` | the schema owner |
| `LEXAVAULT_SCHEMA_DB_PASSWORD` | its password |

```
scripts/Invoke-LexaVault-SchemaStep.ps1 apply [-RuntimeUser app_user]     (Windows)
scripts/Invoke-LexaVault-SchemaStep.sh apply [--runtime-user app_user]    (Linux)
```

Both run `com.securedoc.install.SchemaStep` from the backend jar.

- `apply` runs the three steps. With `--runtime-user`, it also grants that database user SELECT, INSERT, UPDATE and
  DELETE on the `idn_` and `lic_` tables, and SELECT on `idn_schema_version` and `lic_schema_version`. It grants
  nothing on the history tables, and no DDL rights. If the app connects as the schema owner (the local development
  setup does), leave it out.
- `status` prints the number of app changesets and the version of each platform run.
- `customer-installed` exits 0 when setup has stored a customer and 4 when not. (The local start scripts no longer use
  it: they run setup with the demo files while `DemoStep installed` says the demo step has not run, task 0037 fix 1.
  See `install/demo/README.md`.)

Exit codes: 0 done, 1 failed (nothing half-done is left by the platform runs; run it again), 2 refused (a setting or
option is missing or wrong), 4 no customer (`customer-installed` only). A failure names the run and the SQL state or
exception type, never the password.

## Order of a fresh install

1. Schema step (`apply`).
2. Setup (`docs/install/SETUP.md`): your licence, sign-in file and role mapping.
3. Start the app.

An upgrade is: schema step, then start the new release.

## Notes

- At startup the app still runs its own Liquibase changelog as before (it finds nothing to do after the schema step).
  Moving the app's own tables to the schema step only, with two database accounts, is a later phase of the design.
- Flyway is in the backend jar only for this step: `spring.flyway.enabled` is `false` in `application.yml`.
