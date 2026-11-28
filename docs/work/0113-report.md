# Report 0113: platform-core 1.3.0, and display names for dropdown values (U10)

Date: 2026-11-28
Branch: `feature/0113-platform-1-3`, from `origin/feature/lexavault-r1b-tenant-identity` at `b531508` (0119 merged; 0110 and 0116 not merged yet, independent of this)
Outcome: **DONE** (PR to follow on green CI)

## Part 1: platform-core 1.1.0 to 1.3.0
- `platform.version` 1.3.0, for every platform module through the BOM. LexaVault builds unchanged otherwise (both releases are additive).
- **Database:** the schema step runs identity **V6** (`test_sign_in_version`) and fields **V3** (`option_names`) by itself (checked on a throwaway database: identity 6, fields 3). The two new columns have their comments in `db/comments/platform-tables.sql`, and the data dictionary is regenerated.
- **Upgrade notes** (`docs/install/SCHEMA_STEP.md`): stop **every** LexaVault server before the schema step (a server of the release before does not start on identity 6), then start only the new release. On one machine, `Update-And-Start.ps1` already stops the app first, and `Start-LexaVault-Local.ps1` runs the schema step only while no backend runs, so the scripts already do it right; no script change was needed.
- **Login connection (1.2.0):** replacing the active connection needs a test sign-in with a token. LexaVault's screen already takes a pasted token for the test, and the platform's refusal (`IDN_TEST_SIGN_IN_REQUIRED`) comes with its own message and fix, which the screen shows. Nothing to change.
- **Taken from 1.2.0 where it removes LexaVault code:**
  - `LicenceBuild.isDevelopment()` replaces the development-build check worked out from the trusted keys (`DevelopmentBuildOnly`, 0109).
  - `FieldValuesException.conflictingKeys()` replaces reading the conflicting keys from the message text (`CaseFieldService`).
- **Not taken, and why:**
  - `FieldValues.withoutKeyService`: LexaVault's stand-in `NoKeyService` is also handed to the other case-field parts, so it would not remove code.
  - `FieldDefinitions.change(list)`: LexaVault's save is already all or nothing, because the field stores run in LexaVault's own transaction (its joining data source, 0061).
  - `Approvals.decidedElsewhere`: no LexaVault code works around it today (0059 question 5 stays a question).
  - Field stores in the app's transaction, and notifications through the outbound settings: optional, off; not needed for this task.

## Part 2: U10, names for dropdown values
- **Business Admin › Case fields:** each allowed value is one line, the code alone or `code = name` (for example `000 = Club`). The hint says so. Names are unique within a field (the platform refuses a repeated name: LV-CFG-001), and the admin's order is kept.
- **Stored, sent, matched and searched value:** the code, unchanged (manifest, API, output). The API gives the names with each field as `allowedValueNames` (by code; only named values).
- **People pick by name:** New case's dropdown shows the names, in the admin's order once values have names (codes' order otherwise, as 0111). The case panel shows a value's name. A value without a name shows its code.
- Not done: the Cases list columns and the filters still show the code (they don't read the field definitions today). Listed for a follow-up.

## Tests
- `CaseFieldSettingsTest.dropdownValuesCanHaveNamesAndTheCodesStay`: codes in order, names, a value without a name shows its code, a repeated name refused with LV-CFG-001.
- Changed tests (platform behaviour, said here as the rules require):
  - `SchemaStepPostgresTest`: identity is at version 6 (one more migration in each case).
  - `LoginConnectionServiceTest`: a draft tested without a token is now refused when it would replace the active connection (`IDN_TEST_SIGN_IN_REQUIRED`); the tests test with a token, and one asserts the refusal.
  - `ProtectionRulesTest`: platform-core now seals a new keyed erase log by itself, which fixes what 0060 reported (LexaVault's own sealing stays and is harmless).
  - `BulkNightPostgresTest` failed once under load (31 against 30 at 6 AM) and passed on re-run, unchanged.
- Frontend: `adminRules.test.ts` (code = name lines both ways), `intakeRules.test.ts` (name or code; admin's order when named).
- Runs:
  - `mvn -Pdev-build clean install -DskipTests` (online once, to fetch the 1.3.0 artifacts from GitHub Packages);
  - the full backend `mvn -o -Pdev-build verify` with PostgreSQL tests on a throwaway container: domain 91, application 411, scan bridge 144, infrastructure 216 (2 skipped), API 1002: 8 failed in the four classes above, all passing after the test changes (re-run of those classes: green);
  - `Update-LexaVault-DataDictionary.ps1`;
  - frontend `tsc --noEmit`, `npm run test:standalone`: RESULT_FRONTEND.
- **Not run: the owner's one-command round (0106) before and after.** It needs the real models for about 25 minutes, twice. It is left for milestone M2 or the owner's test (TO-TEST), with the round of 2026-11-27 22:56 as the before.

## Questions
1. **Cases list and filters by name** (U10 says "everywhere"): they need the field definitions on that screen. Recommended: a small follow-up task.

No other product's name or behaviour in code.
