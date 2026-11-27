# 0109 report: hardening of two settings

Branch `feature/0109-hardening`, from `origin/feature/lexavault-r1b-tenant-identity` at `0a756fcd`.

## What changed

**Part 1, hardening: LOCAL mode only in development builds**
- New `DevelopmentBuildOnly` (securedoc-api, `api.security`): the one check of what this build is. A development build
  trusts the development licence key; a production build does not. A test gives its own `LicenceBuild` bean.
- `SecurityConfig.localModeOnlyInDevelopmentBuilds`: a production build that asks for LOCAL mode does not start, whatever
  else is set (a key service or not, any case or spacing of the setting). One log line
  `LV_LOCAL_MODE_REFUSED code=LV-SYS-005 LOCAL mode is only available in development builds`, then the usual
  `LV_STARTUP_FAILED code=LV-SYS-005`. It runs after every bean is made (so a refused licence is still told first, as
  `FreshDatabaseStartPostgresTest` expects) and before anything is served.
- `FileProtectionConfig.plainFilesOnlyInDevelopment` now uses the same check (no second copy).
- LV-SYS-005 ("Start-up setting wrong") is used; no new code was added.
- `Start-LexaVault-Local.ps1` / `start-local.cmd` build with `-Pdev-build`, so they start as before.

**Part 2, hardening: secret references limited to approved names**
- New port `SecretReferenceRule` (securedoc-application). The app gives `AppSecretResolver::entityRef`, the rule already
  used for mail and destination-profile secrets: `env:` or `file:` `LEXAVAULT_SECRET_<entity id, 32 characters>_<NAME>`
  of this entity only. Everything else is refused, including every `sys:` name.
- On save: `IntegrationConnectionService` checks the reference on create, change, switching on and the connection test
  (draft). Refused: LV-CFG-003, message `secretReference: the secret's name is not one this entity may use` (the field,
  never the reference or a value). Nothing is saved or audited. Without the app's rule, every reference is refused.
- At run time: new `IntegrationSecrets` (securedoc-infrastructure), the one reader: the rule first, then the app's
  `AppSecretResolver`. A refused name is never looked up and nothing is sent.
  - `HttpPollingSourceAdapter`: own `env:`/`sys:` reading removed; refusal is `POLL_AUTH_CONFIGURATION_ERROR` (LV-CFG-003).
  - `DerivedDestinationDeliveryAdapter`: own copy removed; refusal is LV-DLV-005, not retried.
  - Delivery through destination profiles (`DestinationProfiles`) already used the rule; unchanged.
- Beans: `IntegrationSecretsConfig`. Integrations screen: the example placeholder now shows the approved form
  (`env:LEXAVAULT_SECRET_…`) for every connection. `docs/api/POLLING_INGESTION_V1.md` names the rule.

## Existing integrations with refused names
- Repository: no seed file, script or test fixture saves a connection with a refused name.
- The owner's local database: **not checked**. `securedoc-postgres` was stopped and was not started by this session.
  Read-only query to run (lists names and whether each reference is approved; prints no reference):

```sql
select tenant_id, name, purpose, enabled,
       secret_reference ~ ('^(env|file):LEXAVAULT_SECRET_' || upper(replace(tenant_id::text, '-', '')) || '_[A-Z0-9_]{1,80}$')
         as approved
from integration_connections
where secret_reference is not null and secret_reference <> '';
```
  Any row with `approved = false` will not run and must be given an approved name on the Integrations screen. Nothing was
  changed silently.

## Tests
- New `LocalModeDevelopmentBuildsOnlyTest` (6): production build + LOCAL refused, with and without a key service and in
  any spelling; development build starts (with and without a key service); production build with normal sign-in starts;
  plain files use the same check.
- New `SecretReferencesApprovedNamesTest` (6): approved name saved and used to sign in; unapproved `env:` (other
  variables, another entity's name) and `sys:` names refused on save, in the connection test and at run time; refused
  names never looked up; no request sent; no value in any error or audit detail.
- `DerivedDestinationDeliveryAdapterTest`: one new test (refused names do not run, send nothing, LV-DLV-005; an approved
  name still works).
- `IntegrationConnectionServiceTest`: one new test (refused on save, CFG-003, field only). Changed (said here): this test
  and `DraftConnectionTestServiceTest` now give their service a test rule that approves the one reference each uses,
  since a service without a rule now refuses every reference. No assertion was weakened.

## What was run
- `mvn -o verify -pl securedoc-api -am` with a throwaway PostgreSQL 16 container (removed afterwards), PostgreSQL tests on:
  BUILD SUCCESS. domain 87, application 389, infrastructure 216 (2 skipped), API 979 (5 skipped), 0 failures.
  The test's secret value does not appear in the build log.
- Frontend: `tsc --noEmit` passes; one `npm run test:standalone` run with `LV_E2E_PORT_BASE=15275`: exit 0 (auth, vocabulary, ui-framework pass; oidc-signin 236, tablet 110, a11y 380 passed).
- Shared Maven repository read only; nothing installed (rule 11).
- Left for the milestone: the full proof set.

## No other product's name or behaviour in code
No other product's name or behaviour in code.
