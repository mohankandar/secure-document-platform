# Unit 21 Verification Plan — Business Admin

Unit 21 is implemented in source and must be build/runtime/browser verified before approval. Do not start Unit 22 while performing this verification.

## Build gates

- `mvn clean verify` from `backend/`
- `npx tsc --noEmit`
- `npm run build`
- Liquibase migration `009-business-admin-configuration.xml` applies and Hibernate schema validation passes.

## Identifier configuration scenarios

1. Load Business Admin configuration as ADMIN.
2. Confirm Contract Number + Member Number appear from the existing tenant configuration.
3. Add an optional identifier such as `Borrower Name` with Required=false and verify save/reload.
4. Make Member Number Primary and confirm Contract Number becomes non-Primary. Primary must remain Required + Displayable.
5. Reorder fields and verify Manual Upload reflects the new order/labels.
6. Make an additional identifier Required and verify Manual Upload/API reject its absence.
7. Remove/deactivate the optional identifier and verify historical records remain intact while new intake no longer requests it.
8. Attempt zero Primary / multiple Primary / Primary not Required through API and verify safe rejection plus database one-primary protection.
9. Verify non-ADMIN receives 403 from all Business Admin endpoints.
10. Verify tenant A cannot read/update tenant B configuration.

## Sensitive-data policy scenarios

1. Default configuration exposes all four V1 categories, threshold 0.80, `UNCERTAIN_ONLY`.
2. Raise threshold (for example 0.90) and verify a finding below it routes to Manual Review while a higher-confidence finding can auto-confirm.
3. Set `REVIEW_ALL` and verify even high-confidence enabled findings go to Manual Review and nothing is delivered until review resolves.
4. Restore `UNCERTAIN_ONLY` and verify straight-through processing resumes for high-confidence findings.
5. Disable one category (for example Credit Score), process a document containing all four categories, and verify the disabled category is not turned into an actionable/redacted finding while enabled categories still follow policy.
6. Re-enable it and verify it is again detected/redacted on a new processing run.
7. Attempt an empty enabled-category set, unsupported category, and threshold outside 0.50–0.99; verify safe validation failures.
8. Confirm policy-change audit details contain only structural metadata (count/threshold/mode), no sensitive values.

## Regression

Re-run representative:

- digital PDF straight-through path (Unit 16),
- scanned/OCR path (Unit 17),
- technical failure separation/retry (Unit 18),
- focused Manual Review confirm/dismiss + auto-resume (Unit 19),
- Business Record reuse and watched-folder intake (Unit 20).

## UI scope

Verify the Business Admin page is functional, minimal, and business-friendly. Do not judge Unit 21 against the approved full Dashboard mockup; that remains Unit 23.

## Known intentional limitations

- Policy is read at processing time, not snapshotted onto an Intake Batch. Configuration changes are intended for future/new processing runs.
- Final approval mode is not exposed until Unit 22 owns a pre-delivery hold state.
- Retention settings are not exposed until Unit 22 implements the actual lifecycle.
