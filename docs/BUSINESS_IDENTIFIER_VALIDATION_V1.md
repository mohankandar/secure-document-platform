# LexaVault V1 — Configurable Business Identifier Validation

## Scope

Business identifier validation is tenant configuration owned by Business Admin. Validation is enforced centrally by `BusinessIdentityService`, so every intake path that creates a package uses the same rules: manual upload/scanner UI, direct package API, watched-folder/manifest intake, push/API integration, and scheduled polling.

Validation is not hardcoded to Contract Number, Member Number, or Service Entity. Those names/rules are the LOCAL/demo reference configuration only.

## Supported validation

- `TEXT`
- `NUMERIC` — ASCII digits 0–9 only
- `ALPHANUMERIC` — ASCII letters/digits only
- `EMAIL`
- `UUID`
- `ALLOW_LIST` / enum
- `CUSTOM_PATTERN` / regular expression (advanced escape hatch)

Optional constraints:

- exact length, or min/max length
- canonical case: PRESERVE / UPPER / LOWER
- canonical separator removal for spaces/hyphens
- optional allowed-values constraint (can be combined with another validator such as NUMERIC)
- custom regex for `CUSTOM_PATTERN` (validated authoritatively by the backend Java regex engine; the browser does not attempt to reinterpret Java regex syntax)

All values are strings. Leading zeroes are never converted to numbers.

V1 identity-safety rule: canonicalization settings (`caseNormalization` and separator removal) are immutable after an identifier definition is created. Changing canonicalization on an identifier that already participates in business-record identity could create a second canonical value for the same displayed identifier. Validation shape/length/allow-list rules may be tightened or relaxed for future intakes, but canonicalization changes require an explicit data-migration workstream rather than an in-place admin edit.

## Display vs canonical value

`business_record_identifiers.identifier_value` retains the submitted display value (trimmed).
`business_record_identifiers.normalized_value` stores the canonical value used for identity resolution/search.

For example, a tenant could permit a displayed value such as `AB-12 34` with separator removal and store/search the canonical value as `AB1234`.

## LOCAL/demo reference configuration

| Identifier | Required | Primary | Rule | Allowed values |
|---|---:|---:|---|---|
| Contract Number | Yes | Yes | NUMERIC, exact length 12 | — |
| Member Number | Yes | No | NUMERIC, exact length 11 | — |
| Service Entity | Yes | No | NUMERIC, exact length 3 | `000`, `001`, `002`, `003`, `004`, `005` |

Examples:

- Contract Number: `000642500012`
- Member Number: `00202123456`
- Service Entity: `000` through `005`

The LOCAL bootstrap creates this configuration on a fresh database and upgrades only the exact legacy LOCAL/demo identifier configuration. It does not overwrite a tenant-admin-customized configuration.

## Failure behavior

Invalid intake values are rejected before a BusinessRecord/package is created with `INTAKE_INVALID_IDENTIFIER_VALUE` and a field-specific safe message. Rejected values themselves are not echoed into normal logs/audit messages.

## Database

Liquibase `022-01-business-identifier-validation` adds validation configuration fields to `business_identifier_definitions`.

Expected Liquibase count after this unit: **53 changeSets**.
