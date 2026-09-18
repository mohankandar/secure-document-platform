# Reporting R3 FIX2 — TypeScript Build Fix

## Scope
Focused compile fix for Reporting R3 FIX1.

The frontend report contract defines `serviceLevel.deliveryEligible` as a numeric eligible-sample count. The new target-status helper accepts a boolean eligibility flag. R3 FIX1 passed the numeric count directly to the helper, producing TS2345 during the frontend TypeScript check.

## Fix
Convert the numeric sample count to the intended boolean predicate at the call site:

- before: `deliveryEligible`
- after: `deliveryEligible > 0`

Both target-status helper invocations on the Delivery SLA row use the corrected predicate.

## Functional behavior
No reporting logic, SLA calculation, backend API, database schema, permissions, or UX semantics changed.

- `deliveryEligible > 0` => target status is assessed.
- `deliveryEligible == 0` => target status renders the existing `No data` neutral state.

## Validation
Backend build was already reported PASS by local validation before this frontend compile issue.
This candidate is intended for the same `build-all.cmd` check to confirm the frontend TypeScript stage passes.
