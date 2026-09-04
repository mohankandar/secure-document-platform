# Final V1 Pre-Acceptance Policy + Observability Update — 2026-08-31

## Status

Implemented after Unit 25 approval and before formal Final V1 Security / Integration + Acceptance.

Unit 25 remains approved. This is a small pre-acceptance product-policy/observability delta that must be included in Final Acceptance verification.

## Why this change exists

Manual testing clarified two customer requirements:

1. The current customer wants exactly four redaction categories: SSN, DOB, Credit Score, and Credit Card Number. Name/email and other personal/business fields must remain visible for this customer.
2. If only the last four digits of an SSN are exposed in clear SSN context, those visible digits must also be redacted. This is tenant/customer policy, not a fifth category.

Manual testing also benefits from a short safe operational trace that shows extraction, AI, findings by category/page, redaction verification, delivery, and elapsed time without printing raw sensitive values.

## Implementation

- Added tenant policy field `maskSsnLastFour`.
- Added Liquibase migration `012-ssn-last-four-policy.xml` with a default of `true` for existing tenant policy rows.
- Added Business Admin UI/API support for the SSN-last-four toggle.
- Updated Ollama semantic prompt to identify last-four-only SSN only in clear SSN context and to return only exposed digits.
- Application filters last-four-only semantic SSN findings when the tenant toggle is disabled.
- Deterministic validation treats four digits in explicit SSN context as supporting evidence instead of falsely contradicting AI.
- Added a narrow regression test for contextual SSN-last-four support and unrelated four-digit rejection.
- Added safe high-level processing logs with IDs/counts/page numbers/timings only.
- Merged the two Kiro-approved Unit 25 test-fixture fixes into this source baseline.
- Added reusable LOCAL start/stop scripts to the repository root.

## Safety

No log event introduced by this change prints raw matched values, page text, OCR text, names, email addresses, credentials, bearer tokens, full paths, or raw exceptions.

## Final Acceptance additions

Final acceptance must now prove:

- full SSN redaction;
- contextual SSN-last-four redaction when enabled;
- unrelated four-digit negative control remains visible;
- SSN-last-four toggle disables only partial SSN behavior, not full SSN;
- DOB, contextual credit score, and credit-card number behavior;
- name and email remain visible for this customer's policy;
- high-level logs report category/page/count/timing metadata without raw sensitive values.
