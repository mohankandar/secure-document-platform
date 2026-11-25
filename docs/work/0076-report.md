# Report 0076: Bulk approve, with the four-eyes rule

Date: 2026-11-24
Branch: `feature/0076-bulk-approve`, from the integration branch (0074 and 0067 merged)
Outcome: **DONE** (3 questions)

## What was done
- **Cases, Approval stage:** tick boxes, **Approve selected (n)**, one confirmation with the cases and their count, then a result that names each case that stayed and why. Offered only while approval before delivery is on, to whoever may approve a case today. Always the signed-in person's own entity, 1 to 50 cases.
- **Four eyes, held by the server:** a case the person reviewed is greyed with the reason, and refused with the new `LV-REV-015` whatever the screen sends. "Reviewed" is read from the audit trail: a finding decided or moved, a page checked by hand, a review approved, or manual redaction started, changed or submitted.
- **Each case is its own action:** today's single approval runs once per case, so its own check, audit record and export. Every record of one bulk action holds the same bulk action id and its number of cases. A case that fails gets a failure record with its code (never an error text) and the others go on.
- New calls: `POST /api/v1/bulk-approval` and `/bulk-approval/offer` (which cases can't be ticked; changes nothing). No migration, no new dependency. Single approval unchanged.

## Commands run and results
Isolated copy, `mvn verify`, nothing installed (rule 11). Browser tests on base 15275.

| Command | Result |
|---|---|
| `mvn -B -o -Pdev-build verify`, PostgreSQL on (throwaway, removed) | BUILD SUCCESS: domain 85, application 319, scan bridge 136, infrastructure 134, API 850 (1 skipped), 0 failures |
| frontend suites, 1 run | auth 10, vocabulary 217, ui-framework 19, oidc-signin 192, tablet 95, a11y 168: all pass |
| no-redacted-value check | PASS, 0 matches (it doesn't run a bulk approval) |
| product-name guard | clean |

New tests: `BulkApprovalServiceTest` (limits, four-eyes refusal on the server, one record per case, partial failure, off), `CaseReviewersPostgresTest` (who reviewed a case), `bulkApproveRules.test.ts`, two in `caseRows.spec.ts`. Pictures: `docs/work/0076-proof/` (mocked API).

The first pictures showed a broken row layout although the tests passed; it was fixed and the pictures taken again before this full run.

## Existing tests changed
Only the code counts (150 codes, REV 15) in `LvErrorCodeRegistryTest` and `lvCodes.test.ts`.

## Where the design differs (the task was followed)
Design: 25 cases, a customer setting (off by default), only cases a different person reviewed. Task: 50, no extra setting, and only the approver's own are blocked, so a case nobody reviewed can be ticked.

## Not done, or left for the milestone
- Bulk approval end to end in the real app with a real database: M1. The two calls have no test of their own over HTTP (the capability is the same annotation as single approval).
- The tick boxes have no tablet-size test or picture of their own; the tablet suite passes but doesn't open them.
- The four-eyes check runs once at the start of a bulk action: a review decision made by the same person during it is not seen.

## Questions for the owner (recommended one taken)
1. **Single approval is not four-eyes:** a greyed case still has its own Approve button, and the person can use it. The task describes bulk approval only, so it was left. Apply the rule there too? I recommend yes; until then the rule can be got round one case at a time.
2. A case nobody reviewed (nothing needed a person) can be ticked. Accept?
3. A refusal writes an audit failure record for that case. Accept?

## Suggested next step
Decide question 1; the next READY task.

No other product's name or behaviour in code.
