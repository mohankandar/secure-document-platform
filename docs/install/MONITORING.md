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

A new installation without a storage starts; the check then shows "having problems" until one is set up. A full disk is
shown as a problem and never refuses a start. The default AI model is checked; AI settings of single entities are not.

A refused start says one line per check with its code and fix, and one log line
`LV_STARTUP_FAILED code=LV-... platformCode=...`.

**Database user.** "The login has more rights than it needs" (`MON_DB_USER_TOO_POWERFUL`) is a warning: the app still runs its
own table changes at start, so today its user owns tables.

**References.** Every answer has the header `X-Request-Id`, and every error answer the same value as `reference`
(`R-...`). It is in the log (`ref`), in Support, Status (last failures) and in the diagnostic bundle. An `X-Request-Id` you
send is kept in the log as `requestId` only.

**Diagnostic bundle.** The IT Admin downloads it under Support, Status; each download is audited. It holds versions, part of
the settings without secrets, the checks, the last failures and the table versions. Never documents, case values or
passwords.

**Health address.** `/actuator/health` gives a status only, no names or causes. It is not UP while a required check is down.
