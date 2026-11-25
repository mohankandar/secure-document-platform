# Case fields: install guide for IT (LexaVault)

Task 0061. An entity's case fields (contract number, borrower name, property address, ...) and their values are kept by
platform-core's `platform-fields` 1.0.0, in `fld_` tables of LexaVault's own database. Platform-core's
`docs/install/fields.md` has the shared part.

## What IT does

1. **Schema step.** Run `schema-step apply` (`SCHEMA_STEP.md`); it makes the `fld_` tables (history table
   `fld_schema_history`) and, with `--runtime-user`, sets the app user's rights. Stop every LexaVault server first. At start
   LexaVault checks the tables and stops with an `FLD_` code when they are missing or of another release.
2. **Partial search** uses the PostgreSQL extension `pg_trgm`. If the schema owner may not create it, the step goes on and
   LexaVault's own search on Cases still works.
3. **Key service.** Sensitive values use the customer's keys of `KEY_SERVICE.md`; there is nothing more to set up.
   Outside a local development start LexaVault does not start without it.
4. **Back up the whole database**, not one schema. A backup holds sensitive values only sealed (`pci1.` texts, under the
   record's `pck1.` envelope) and as keyed fingerprints.

## Sensitive fields

- Stored only sealed; in no other table, log, message, report or file of LexaVault.
- **Shown** only on the case itself, to Document Processors and Supervisors (capability `CASE_FIELD_SENSITIVE_VIEW`);
  in lists, on Cases, in Support and in notifications they are hidden for everyone.
- **Found** only by the whole value (capitals and extra spaces ignored).
- Key service down or sealed: cases open with the values hidden; new sensitive values are refused (LV-SYS-010) and the
  source sends again; an export waits and is never sent without them.
- Repeats of an intake are recognised by a digest that holds only a keyed fingerprint of a sensitive value. Keep older
  fingerprint key versions usable after a rotation.
- The Scan Bridge keeps sensitive values in memory only. After a restart of the bridge a scan that was not sent needs
  them again (LV-SCN-022).
- A **local development start without a key service** refuses sensitive fields (LV-CFG-012) and values (LV-SYS-011);
  everything else works.

What LexaVault can't protect: the trigger files and polling answers of the source system, the metadata a source sends
beside the fields (don't put field values there), and the destination's copy.

## Rules the Business Admin meets

Exactly one Primary field. A case field and a redaction item exclude each other per entity (LV-CFG-014, both ways); SSN,
card number, CVV and PIN are never a field (LV-CFG-013). With values, the type, Sensitive, Primary and "several values" of
a field stay (LV-CFG-015): add a new field. Removing a field switches it off; its values stay.

## Erasing

When every case of a business record has been purged by its retention (or was an incomplete intake that expired), the
record's values are erased through the erase log (kind `case-fields`, audit record `CASE_FIELDS_ERASED`). A record a live
case needs is kept. A record no case came to use (the intake failed) goes after a day. The case rows, audit and report
counts stay; the case number of a purged case is then no longer shown in Support and reports. `ERASE_LOG.md` has the
restore runbook.

## Upgrading a database with cases

The schema step moves, once, before the old tables (`business_identifier_definitions`, `business_records`,
`business_record_identifiers`) are dropped by changeset 066: every field (also switched-off ones) by the agreed mapping,
and the values of every business record a case points to, under the same record id. It prints counts only.

- Not moved: business records no case points to, and single values that break the field's own rules (the count is
  printed).
- Every moved field is **not** sensitive, as before, and stays so once it has values. For a name or an address that should
  be sensitive, the Business Admin adds a new field and switches the old one off.
- Search: a moved field is found by the whole value; the Business Admin can choose "its beginning" or "any part".
- The main ID no longer tells capitals apart.
- Only the schema step moves them. An app start of this release on a database that is not moved yet stops ("not moved yet:
  run the schema step") and drops nothing.
- `CASE_FIELDS_NO_CUSTOMER`: no customer is stored yet, so nothing was moved or dropped. Run setup with the release
  installed before, then the schema step again.
