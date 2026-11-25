# Licensing: install guide for IT (LexaVault)

**Licensing is live (task 0057).** LexaVault checks the stored licence when it starts, takes in new work only while the
licence allows it, and counts pages. The full guide is platform-core's `docs/install/licensing.md`; this page says what is
specific to LexaVault. LexaVault's earlier own licences (pasted in Platform operations) are gone and no longer read; their
tables (`tenant_license_records`, `tenant_license_assignments`) stay, unused.

## Before the first start, and before an upgrade

1. The schema step (`SCHEMA_STEP.md`) and setup (`SETUP.md`) have run: setup stores the licence and writes
   `platform-tenant.properties` with your customer ID (`platform.licensing.tenant-id`).
2. Put `platform-tenant.properties` in the folder LexaVault starts in, or name it:
   `PLATFORM_TENANT_CONFIG=file:/path/platform-tenant.properties`. The customer ID can also be given as
   `PLATFORM_LICENSING_TENANT_ID`.
3. The build must carry the key your licence was signed with (`BUILD.md`). A production build refuses the demo licence.

LexaVault does not start without it, and says which: `platform.licensing.tenant-id is missing`, `LICENCE_MISSING`,
`LICENCE_TAMPERED`, `LICENCE_WRONG_CUSTOMER`, `LICENCE_UNKNOWN_KEY_ID`, `LIC_TABLES_MISSING`. Expiry never stops a start.

## What stops, and what goes on

When the licence has not started, or 30 days after it ended, or it does not list LexaVault or the entity, or it can no
longer be read or trusted, **new work is refused** (`LV-LIC-001` to `LV-LIC-004`, HTTP 403; 503 while the licence can't be
read):

| Entry | What happens |
|---|---|
| Upload, push, scanner | Refused; nothing is made. A case that was open stays as it is and can go on after the renewal, unless its own time limit ends first |
| Watched folder | Not read. Drops stay where they are and are taken in after the renewal |
| Polling | The source is not called; no run is recorded |
| Processing a finished case again | Refused |

Cases already submitted are processed, reviewed and exported as usual; viewing and export keep working. A renewal counts
within 10 seconds, without a restart. If the database can't be reached, the last reading counts for 2 minutes.

## Renewing

Run setup again with the new licence file, or the licence command of the app jar (database password in
`PLATFORM_LICENSING_DB_PASSWORD`):

```
java -cp securedoc-api-0.1.0-SNAPSHOT.jar -Dloader.main=com.documentplatform.licensing.command.LicenceCommand org.springframework.boot.loader.launch.PropertiesLauncher store --file licence.json --jdbc-url jdbc:postgresql://dbhost/securedoc --db-user app_user --tenant-id TEN-... --product-code LEXAVAULT
```

## Pages

A document's pages are counted once, when its redacted copy is made: not again when a step is retried, another server takes
it over, or the case is processed again. Table `licence_metered_documents` (ids, page counts and times only) holds one row
per counted document; the usage ledger is `lic_usage`. Work never stops at a limit. Pages of an entity that is not in the
licence wait and are put in when it is.

The Business Admin sees the licence and this month's use under Admin, Licensing (read-only).

## Licences for tests (NON_PRODUCTION)

Every page of every redacted copy is marked "TEST – NOT FOR PRODUCTION USE" (diagonal, see-through, never over a redaction),
and the file says so (`NonProduction` in its information). It is a drawing: it adds no text. Use under such a licence is
counted and never billable. Other licences never mark anything. The demo licence is such a licence.

## Notifications

The licensing events are registered with the roles TENANT_IT_ADMIN and BUSINESS_ADMIN. Nothing is sent until notifications
are set up in LexaVault (`NOTIFICATIONS.md`).

## Health

`/actuator/health` shows only a status. An ending licence or stopped intake does not mark LexaVault unhealthy. The licence's
code, cause and what to do are under Support, Status.
