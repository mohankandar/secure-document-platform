# Unit 21 — Business Admin

Status: APPROVED 2026-08-29.

Preserve Units 16–20. Unit 21 adds tenant Business Admin configuration for:

- multiple business identifier definitions,
- exactly one Primary (Primary must be Required + Displayable),
- independent Required/Searchable/Extractable/Displayable flags,
- identifier ordering/add/remove,
- V1 sensitive categories (SSN, DOB, Credit Card Number, Credit Score),
- tenant auto-redaction threshold,
- `UNCERTAIN_ONLY` and `REVIEW_ALL` review modes.

AI remains primary discovery. Deterministic logic remains supporting evidence. Business Admin policy controls which V1 findings are actionable and whether a finding can auto-confirm.

Unit 22 now owns retention/final-delivery lifecycle. Full Dashboard redesign, IT/provider-admin UI, IDP/OIDC, broad tests, and unrelated refactoring remain later scope.
