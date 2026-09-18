# Local Validation — Configurable Business Identifier Validation

1. Run `build-all.cmd`.
2. Start with the `local` profile and confirm Liquibase applies successfully. Expected `databasechangelog` count: **53**.
3. Open Business Admin -> Business Identifiers and confirm the LOCAL reference configuration shows:
   - Contract Number: NUMERIC, exact 12
   - Member Number: NUMERIC, exact 11
   - Service Entity: NUMERIC, exact 3, allowed 000/001/002/003/004/005
4. Open Upload Documents and verify the same fields/rules are returned by `/api/v1/intake/identifier-definitions`.
5. Valid manual intake:
   - Contract Number `000642500012`
   - Member Number `00202123456`
   - Service Entity `000` (repeat with another allowed value such as `005` if useful)
   Expected: package creation succeeds and leading zeroes remain visible.
6. Invalid Contract Number checks:
   - `642500012` -> rejected (wrong length)
   - `00064250001A` -> rejected (non-numeric)
   - `0006425000123` -> rejected (wrong length)
7. Invalid Member Number checks:
   - `202123456` -> rejected (wrong length)
   - `0020212345A` -> rejected (non-numeric)
8. Service Entity checks:
   - `000` through `005` -> accepted
   - `006` -> rejected
   - `01` -> rejected
   - `ABC` -> rejected
9. Verify API enforcement by POSTing package creation with an invalid identifier. Expected HTTP 400 with an `INTAKE_INVALID_IDENTIFIER_VALUE` error and field-specific message; no raw rejected value echoed.
10. Verify one machine intake path (watched folder or push/polling) with an invalid identifier. Expected the same centralized rejection semantics, not a bypass.
11. Query `business_record_identifiers` for a successful package and confirm:
    - `identifier_value` preserves leading zeroes/display value
    - `normalized_value` contains the canonical string
12. Configure a temporary non-reference identifier in Business Admin to exercise at least one generic rule (ALPHANUMERIC, EMAIL, UUID, ALLOW_LIST, or CUSTOM_PATTERN), then verify a valid and invalid value.
13. Confirm tenant isolation and existing Primary-identifier resolution behavior remain intact.
14. Confirm no changes to R11 detection/redaction, AI resilience, Business Continuity, Support Console, or Reprocess behavior.
