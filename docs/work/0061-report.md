# Report 0061: case fields on platform-fields

Date: 2026-11-25
Branch: `feature/0061-case-fields`, with the integration branch (0060, 0083) merged in
Outcome: **DONE** (10 questions). Built by a subagent from a written brief. I read the value, carry-over, erase and migration code, found and fixed one upgrade fault (below), did the merge and ran the proof set on the merged code. **The start paths were run by the subagent, before my fix and the merge.**

## What was done
1. **Fields and values** are platform-fields 1.0.0. LexaVault's own model, its code and tables are gone. The same primary value joins the same business record, missing values are added, a different value is refused (`IDENTIFIER_CONFLICT`). A case and its values are stored in one transaction.
2. **LexaVault's rules on top:** one active Primary; a case field or a redaction item, never both (checked on both saves, by the words in the field's name and key); SSN, card, CVV and PIN never a field; "AI fills in" attached by key; removing is switching off.
3. **Sensitive values** are stored sealed, hidden everywhere except on the case itself for Processor and Supervisor (new capability `CASE_FIELD_SENSITIVE_VIEW`), and found by the whole value only. Key service down: the case opens with them hidden, a new sensitive value is LV-SYS-010, an export is never sent without them.
4. **Intake** (API, trigger file, polling, upload, scan) and **screens** (New case, Admin Case fields) take all 11 types, several values, locks and switched-off fields. Pictures: `docs/work/0061-proof/`.
5. **Delivery** carries platform-core's document (key, type, value, lists) to the delivery step; no adapter sends it yet. The existing format gets plain texts.
6. **Erase log:** kind `case-fields`. A record's values are erased when every case of it is purged (`docs/work/erase-and-backups.md` names case fields).
7. **LOCAL without keys** starts; a sensitive field or value is refused (LV-CFG-012, LV-SYS-011).

**Migration 066** (three change sets, with rollback): drops the three old tables, replaces two reporting functions; `document_packages.business_record_id` stays. The `fld_` tables are a sixth run of the schema step. No registered changeset edited. **Dependencies:** `platform-fields` 1.0.0 only (test kits in test scope).

## What happens to an existing database
- The schema step moves every field (also switched-off ones) and the values of every record a case points to, then 066 drops the old tables. Any other run on an unmoved database, such as an app start, is refused and drops nothing.
- Not moved, counted in the output: records without a case, values that break their field's rule.
- **Main IDs that differ only by capitals become one record. If their other values differ, the second record loses its main ID** (counted as left out).
- Moved fields are not sensitive, stay so once they have values, and are found by the whole value.

## Commands run and results
On the merged code (me):

| Command | Result |
|---|---|
| `mvn -B -Pdev-build clean install`, PostgreSQL tests on | SUCCESS: domain 83, application 369, scan bridge 138, infrastructure 153, API 923 (3 skipped), 0 failures |
| `npm run build`; `npm run test:standalone`, 1 run | built; 0 failures: 10, 224, 19, 210, 95, 174 |
| No-redacted-value check | PASS, 0 matches |
| Compliance report check | PASS, 127 checks |
| Product-name guard | clean (1875 files) |

By the subagent on `e6d94f5`: start paths on throwaway PostgreSQL, Keycloak and Vault, **53 checks, all pass** (fresh LOCAL; a 0060 database with old fields and open cases, then with Vault; fresh OIDC with Vault; Vault paused; retention, restore, blocked, replay). `CaseFieldsAppPostgresTest` searches every table, log, file and the answers to unprivileged roles for a sensitive value, and runs 8 claims at once.

**Left for M1 (task 0086):** two servers, export end-to-end (its stack was edited, not run), repeats, start paths on merged code.

## Failures met (fixed)
- **Found in my review:** the upgrade stopped when two old records joined into one (a constraint dropped twice). Test first (it failed), then fixed in 066.
- Found by the start path: a LOCAL app start dropped the old tables before they were moved. Refused now, and tested.
- The demo step (missing flush), a masked licence message, a log check that captured nothing, two frontend runs with wrong counts.

## Existing tests changed
- **Removed with the model:** `BusinessIdentifierValidatorTest`, `TenantScopedMutationGuardTest` (case in `CaseFieldSettingsTest`), `BusinessIdentityServiceTest` (4 cases in `CaseFieldServiceTest`).
- **Counts and lists:** 5 migration tests, `LvErrorCodeRegistryTest`, `SchemaStepPostgresTest`, `ProductAuthorizationPolicyTest`, `lvCodes.test.ts`, `policyCapabilities.ts`.
- **Types of the new model:** 7 admin tests, `DashboardColumnKeysTest`, `LvErrorCodeFollowupsTest`, export, create and list tests, `UnrecordedFileSweepPostgresTest`, `FreshDatabaseStart`, `adminRules.test.ts`, the a11y, OIDC and tablet mocks.

## Behaviour and default changes
- Stop servers, run the schema step, start. A database with old values but no stored customer is refused (`CASE_FIELDS_NO_CUSTOMER`), nothing dropped.
- A main ID no longer tells capitals apart. Type, sensitive, Primary and several values lock once values exist.
- Search follows each field's mode. Report breakdowns: only single-choice dropdowns that are not sensitive.
- **Values of purged cases disappear from Support and reports, also those of cases purged before this release, at the first clean-up.**

## Not done or not proven
- No screen to edit a record's values (there was none); no import or export of a field list.
- Push API and polling with sensitive values: unit tests only. Scan Bridge: unit test and mocked screen only.
- Sensitive ADDRESS fields can't be searched. Sender metadata and trigger files are not covered by the hiding.
- The owner's scripts as such.

## Questions for the owner (the recommended answer was taken)
1. **Carry-over once**, although the design says no data migration: keep? Yes (cases in processing would lose their values).
2. Sensitive values for Processor and Supervisor only, on the case only? Yes.
3. **Erase record values when every case is purged** (can't be undone; Support and reports lose the number)? Yes.
4. Records no case uses are erased after one day? Yes.
5. Redaction items recognised by the field's name; "Account number" counts as bank account? Yes.
6. Moved names stay plain; the admin adds a new sensitive field? Yes.
7. Dropdown values copied into report statistics stay after an erase? Yes (not sensitive).
8. The delivery document is sent as profile data in switch-over part 3? Yes.
9. Audit each opening of sensitive values? Not now.
10. Two main IDs that differ only by capitals with different values: the second loses its main ID in the move. Accept, or stop the move and ask? Accept (development data only).

## PLATFORM-CORE (nothing patched; by the subagent)
- No bulk read of records and no paged or multi-field search: lists read `fld_` by SQL.
- `PostgresFieldStore` and `PostgresValueStore` can't join the app's transaction (LexaVault wraps the DataSource).
- `VALUES_CONFLICT` gives the keys only in its message.
- `FieldDefinitions.change` has no all-or-nothing save of a list.
- `FieldValues` needs a `KeyService` even when no field is sensitive.

## For the owner
After the merge: stop LexaVault, run `Build-LexaVault-All.ps1`, start as usual. The move and 066 run once. **If you want to keep the values of purged cases, say so before you start it** (question 3).

## Commits
`d318e8b`, `64fd6fa`, `e6d94f5`, `f03a5dc`, my fix, the merge and the report commit.

## Suggested next step
Task 0086 (milestone M1).

No other product's name or behaviour in code (the known exception, `SdvDeliveryAdapter`, is untouched).
