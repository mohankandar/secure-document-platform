# Schema step: install guide for IT

The schema step is the only thing that changes LexaVault's database. The app never changes the database at startup
for the platform tables, and the schema step is run by you, before setup and before starting a new release.

It runs six things. The case fields run first, so that what a database of before held is moved before its old tables are
dropped (`CASE_FIELDS.md`); the others follow in this order. Each keeps its own history table:

| Run | What it creates | Source in the release | History table |
|---|---|---|---|
| App tables | LexaVault's own tables (cases, documents, audit, ...) | Liquibase changelog `db/changelog/db.changelog-master.xml` | `databasechangelog` |
| Identity | Customer, entity slots, login connection, group grants, integration client bindings, grant history, platform-wide grants (`idn_` tables; version 5, unchanged in platform-core 1.1.0) | platform-core Flyway location `db/platform/identity` | `idn_schema_history` |
| Licensing | The stored licence, usage ledger (`lic_` tables; version 2). Setup stores the licence here; the app checks it at start (`LICENSING.md`) | platform-core Flyway location `db/platform/licensing` | `lic_schema_history` |
| Notifications | Event settings, outgoing messages, conditions and approval requests (`notif_` tables; version 1, task 0059). The app checks them at start (`NOTIFICATIONS.md`) | platform-core Flyway location `db/platform/notifications` | `notif_schema_history` |
| Erase log | Where the database stands in the erase log (`erl_` tables; version 3, task 0060). The app checks them at start (`ERASE_LOG.md`) | platform-core Flyway location `db/platform/erase-log` | `erl_schema_history` |
| Case fields | Field definitions and values (`fld_` tables; version 2, task 0061). Runs first. The app checks them at start (`CASE_FIELDS.md`) | platform-core Flyway location `db/platform/fields` | `fld_schema_history` |

The platform runs start their own history at version 0 in a schema that already holds the app's tables, so their
version 1 always runs. The identity run always goes to the latest version in the release: a database at identity
version 1 to 4 gets the missing versions on the next run (platform-core 0.7.0 refuses anything older than version 5,
`IDN_VERSION_OLDER`; the app then does not start). Version 3 (`V3__grant_history.sql`) adds `idn_grant_history`, insert-only, which the Audit page
reads. Version 4 adds `idn_platform_grant`; version 5 makes the connection and binding histories insert-only. Licensing
version 2 keeps the licence clock and the highest licence version. Running the step again changes nothing.

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

- `apply` runs the six steps. With `--runtime-user`, it also sets that database user's rights on the `idn_`,
  `lic_`, `notif_`, `erl_` and `fld_` tables to exactly what platform-core 0.7.0 lists, and **takes any other right on them away** (for an upgrade
  run it with `--runtime-user` once): SELECT, INSERT, UPDATE and DELETE, except

  | Table | Rights |
  |---|---|
  | `idn_grant_history`, `idn_login_connection_history`, `idn_machine_binding_history`, `lic_clock_reset` | SELECT, INSERT |
  | `lic_last_seen`, `erl_position` | SELECT, INSERT, UPDATE |
  | `lic_version_floor`, `idn_schema_version`, `lic_schema_version`, `notif_schema_version`, `erl_schema_version`, `fld_schema_version` | SELECT |
  | `idn_schema_history`, `lic_schema_history`, `notif_schema_history`, `erl_schema_history`, `fld_schema_history` (Flyway's) | none |

  No DDL rights. A user that owns the tables is refused (`RUNTIME_USER_OWNS_TABLES`): if the app connects as the schema
  owner (the local development setup does), leave the option out.
- `status` prints the number of app changesets and the version of each platform run.
- `customer-installed` exits 0 when setup has stored a customer and 4 when not. (The local start scripts no longer use
  it: they run setup with the demo files while `DemoStep installed` says the demo step has not run, task 0037 fix 1.
  See `install/demo/README.md`.)

Exit codes: 0 done, 1 failed (nothing half-done is left by the platform runs; run it again), 2 refused (a setting or
option is missing or wrong), 4 no customer (`customer-installed` only). A failure names the run and the SQL state or
exception type, never the password.

## Order of a fresh install

1. Schema step (`apply`).
2. Setup (`docs/install/SETUP.md`): your licence, sign-in file and role mapping; it creates the customer's keys.
3. Start the app.

An upgrade is: stop every server, schema step, then start the new release. **To the release of task 0060**: migration 065 gives
every stored file its protection record; a server of the release before must not run any more (`KEY_SERVICE.md`). Set the key
service and the erase log location first, and run setup once so that the keys exist.

## Notes

- At startup the app still runs its own Liquibase changelog as before (it finds nothing to do after the schema step).
  Moving the app's own tables to the schema step only, with two database accounts, is a later phase of the design.
- Flyway is in the backend jar only for this step: `spring.flyway.enabled` is `false` in `application.yml`.
