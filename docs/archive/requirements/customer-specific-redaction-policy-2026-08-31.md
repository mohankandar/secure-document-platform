# Customer-Specific Redaction Policy — 2026-08-31

## Current customer policy

Only the following V1 categories are enabled for permanent redaction:

1. SSN
2. Date of Birth
3. Credit Score
4. Credit Card Number

Name, email, phone, postal address, Contract Number, Member Number, bank/routing metadata, ordinary dates, monetary amounts, loyalty points, and unrelated identifiers are not redacted for this customer unless a future tenant policy explicitly adds/enables them.

## SSN last-four behavior

The tenant policy contains a separate `maskSsnLastFour` setting.

When enabled:

- full SSNs are handled normally;
- when a document clearly identifies an SSN but only the final four digits are exposed, the visible four digits are redacted;
- the application must not treat an unrelated four-digit number as an SSN without explicit SSN/Social Security context;
- AI/context remains the discovery mechanism for partial SSN representations; deterministic validation may support the contextual finding but never gates AI discovery;
- when a model returns a masked representation such as `XXX-XX-1234`, the application narrows the redaction target to the exposed four digits.

When disabled, last-four-only SSN findings are ignored without disabling full-SSN redaction.

## Configuration

Business Admin can enable/disable the four V1 categories and the SSN-last-four behavior per tenant. The current local/demo default enables all four V1 categories and enables SSN-last-four masking.
