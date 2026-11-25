# LexaVault V1: case field validation

Since task 0061 case fields (definitions, rules, values) are platform-core's `platform-fields`; LexaVault's own model is gone.
IT guide: `docs/install/CASE_FIELDS.md`. Platform design: `Case-Fields-Design.md` (copy of 2026-11-21).

Every intake path (New case, scan, watched folder trigger file, push API, polling) is validated in one place,
`CaseFieldService`.

## Types

TEXT, NUMBER (whole), DECIMAL, MONEY (one currency per field), DATE, DATE_TIME (with offset), EMAIL, UUID, DROPDOWN (up to 100
values), ADDRESS (US), CUSTOM_PATTERN. "Numbers only" is NUMBER with the pattern `[0-9]+` (leading zeros are kept), "Letters
and numbers" is TEXT with `[A-Za-z0-9]+`. Rules: required, exact/min/max length, pattern, lowest/highest value, capitals,
removal of spaces and dashes. A field can take several values (up to 50) and can be sensitive.

Capitals and the removal of spaces and dashes can't be changed after a field is created; type, Sensitive, Primary and
"several values" can't once values exist.

## LOCAL/demo fields

| Field | Required | Primary | Rule | Found by |
|---|---:|---:|---|---|
| Contract Number | Yes | Yes | digits, exactly 12 | any part |
| Member Number | Yes | No | digits, exactly 11 | any part |
| Service Entity | Yes | No | one of `000` to `005` | the whole value |

## Refusals

Nothing is stored when a value is refused (`INTAKE_INVALID_IDENTIFIER_VALUE` and the other `INTAKE_` codes); the value is not
echoed into answers, logs or audit.
