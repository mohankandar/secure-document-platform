# Identity migration: for an install that existed before the platform tables

LexaVault used to keep its login connection in `enterprise_identity_connections` (one per entity) and its group grants
in `tenant_oidc_group_grants`. Since task 0037 it reads both from platform-identity's `idn_` tables: one login
connection per customer and product (shared by all the customer's entities), and grants per entity slot and product.
A new install gets them from setup. An existing install copies them once with this command.

**Task 0058:** the schema step of this release drops `enterprise_identity_connections` (migration 062), and stops with
`OLD_SIGN_IN_CONNECTION_NOT_CARRIED_OVER` while an entity of your installation has an ACTIVE connection there and your
customer has none in `idn_login_connection`. So an install that never ran this command runs it with the release **before**
this one first. `enterprise_identity_connection_history` and `tenant_oidc_group_grants` stay.

## Before you run it

1. The schema step has run (`docs/install/SCHEMA_STEP.md`).
2. Setup has run (`docs/install/SETUP.md`). It stores your customer and its entity slots.
3. Your entities' ids are the entity UUIDs derived from their slot IDs. An entity with any other id is not copied: it
   is listed as `skipped`, and its rows stay in the old tables only.

## Running it

Database settings in the environment only (the password is never printed):
`LEXAVAULT_MIGRATE_DB_URL`, `LEXAVAULT_MIGRATE_DB_USER`, `LEXAVAULT_MIGRATE_DB_PASSWORD`. The app's everyday database
user is enough.

```
scripts/Invoke-LexaVault-IdentityMigration.ps1 plan      (shows what would be copied; changes nothing)
scripts/Invoke-LexaVault-IdentityMigration.ps1 apply
```

(`.sh` on Linux.) It prints one line per item and a summary with counts, for example:

```
  create      login connection LEXAVAULT: provider okta, issuer https://login.example.com/oauth2/default, API audience api://lexavault
  create      2 grant(s) ENT-ACME-0001 LEXAVAULT (issuer https://login.example.com/oauth2/default)
  skipped     entity 00000000-0000-0000-0000-000000000001: it is not the entity UUID of any slot of TEN-ACME; its rows are not copied
Identity migration done: connections created=1 unchanged=0 conflict=0; grants added=2 unchanged=0 not_copied=0; entities skipped=1.
```

- **Login connection.** The ACTIVE connections of the copied entities must agree; they become the customer's one
  connection for LexaVault (recorded as a `MIGRATION` change). If they differ, or `idn_` already holds a different
  ACTIVE connection, the line says `conflict`, nothing is replaced, and the exit code is 3. Setup's
  `--replace-connection` sets it from your sign-in file.
- **Grants.** Copied per entity slot, for LexaVault, under the issuer they were recorded for. A grant whose role is not
  one of LexaVault's entity roles (or is the platform role) is not copied; it never admitted anyone. An alias
  (`USER`, `REVIEWER`, `APPROVER`) is stored as the role it stands for (task 0041, as setup stores it), and each copied
  grant is recorded in the grant history (`idn_grant_history`) as changed by `lexavault-identity-migration`.
- **Nothing is deleted by this command.**
- Running it again copies nothing more.

Exit codes: 0 done, 1 failed (run it again), 2 refused (tables missing, no customer, more than one customer, bad
option), 3 done except conflicts.

## After the table is dropped

The command still copies grants. Login connections are changed in Admin › Sign-in or by setup.
