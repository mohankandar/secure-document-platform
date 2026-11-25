# Monitoring: install guide for IT (LexaVault)

LexaVault checks what it depends on, about every minute (task 0057; platform-core's `docs/install/monitoring.md` has the
shared part). Support, Status shows each check as Working, Having problems or Down, with its code, its cause and what to do.

| Check | Looks at | Refuses a start while down |
|---|---|---|
| `database` | the database answers; the platform tables are at this release's versions | yes |
| `licence` | the stored licence (`LICENSING.md`); a missing or refused licence stops the start by itself | no |
| `login-system` | your login system's published details and keys | no |
| `service-model-server` | the AI model server answers and has the model (with a pool: every server) | only with `SECUREDOC_MONITORING_MODEL_SERVER_REQUIRED=true` |
| `storage` | every document storage in use exists and can be read and written; its disk is not nearly full | yes; `SECUREDOC_MONITORING_STORAGE_REQUIRED=false` switches that off (the local profile does) |
| `key-service` | the key service answers and the customer's keys are there (`KEY_SERVICE.md`); not in a local development start without one | no: the app starts and waits |
| `erase-log` | the erase log can be used, the database is in step with it, no erasure is stuck (`ERASE_LOG.md`) | no: the app starts and takes no work |

A new installation without a storage starts; the check then shows "having problems" until one is set up. A full disk is
shown as a problem and never refuses a start. The default AI model is checked; AI settings of single entities are not.

A refused start says one line per check with its code and fix, and one log line
`LV_STARTUP_FAILED code=LV-... platformCode=...`.

**Database user.** "The login has more rights than it needs" (`MON_DB_USER_TOO_POWERFUL`) is a warning: LexaVault still
starts. You see it when the app connects as the owner of the tables, as the local development setup does. An installation whose
schema step has run works with a user that owns no tables (rehearsed, task 0093): give that user SELECT, INSERT, UPDATE and
DELETE on LexaVault's own tables and USAGE on its sequences, then run the schema step with `--runtime-user` (`SCHEMA_STEP.md`),
which sets its rights on the platform tables.

**References.** Every answer has the header `X-Request-Id`, and every error answer the same value as `reference`
(`R-...`). It is in the log (`ref`), in Support, Status (last failures) and in the diagnostic bundle. An `X-Request-Id` you
send is kept in the log as `requestId` only.

**Diagnostic bundle.** The IT Admin downloads it under Support, Status; each download is audited. It holds versions, part of
the settings without secrets, the checks, the last failures and the table versions. Never documents, case values or
passwords.

**Health address.** `/actuator/health` gives a status only, no names or causes. It is not UP while a required check is down.
