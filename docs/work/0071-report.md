# Report 0071: reject a case, with a reason code

Date: 2026-11-24
Branch: `feature/0071-reject-case`, from the integration branch (`791b218`; nothing newer at the PR)
Outcome: **DONE** (5 questions). 0070 is stopped; this doesn't depend on it.

## What was done
- **Reject case** in the case panel (Review and Approval) and on the Review screen. `POST /packages/{id}/review/reject` needs the review permission, `/approval/reject` the approve permission; each works only in its own case states.
- **Reasons:** `LV-REV-010` wrong document, `011` unreadable, `012` out of scope, `013` duplicate, `014` other (see ticket). A fixed list, never free text. A wrong value is refused and not repeated.
- **The dialog** names the case and the reason and says it can't be undone.
- **Nothing is exported:** the case's open processing jobs are removed. **Files:** a case never exported is kept as long as a stopped case, then deleted and taken off the list; one exported before keeps its own dates. Before, a Rejected case had no deletion date.
- **The code goes to** the audit trail (`Case rejected: code=…, step=…`), the Cases list (plain name), the case panel, and the status answer of the push API (`rejectionCode`).
- **Two people at once:** a review decision, an approval or a manual submit that read the case before it was rejected could have overwritten the rejection (they save outside a transaction). Those three status changes now happen in the database only while the case still waits there (`PackageRepository.changeStatus`); the late one is refused with `LV-REV-002`, and a late approval is taken back.
- **Migration 071** (`package_rejections`), with rollback. No new dependency.
- The words "Not accepted at intake" for a rejected case were wrong (only a person rejects) and are changed.

## Commands run and results
Built in an isolated copy with `mvn verify`, nothing installed (rule 11). Browser tests on base 15275.

| Command | Result |
|---|---|
| `mvn -B -o -Pdev-build verify`, PostgreSQL on (throwaway, removed) | BUILD SUCCESS: domain 85, application 318, scan bridge 136, infrastructure 130, API 811 (1 skipped), 0 failures |
| after two tests were added: `RejectCase*`, `ExternalIntake*`, `ReviewAlreadyDecided*` | pass (37) |
| frontend suites, 1 run | auth 10, vocabulary 214, ui-framework 19, oidc-signin 189, tablet 95, a11y 164: all pass |
| no-redacted-value check | PASS, 0 matches |
| product-name guard | clean |

Proof pictures: `docs/work/0071-proof/` (mocked API).

## Existing tests changed
- `ReviewAlreadyDecidedTest`: its mocked repository answers the new status change (the build before it failed on this one test).
- `caseRows.spec.ts`, `caseRules.test.ts`: the new words. Counts of codes (144 to 149) and Liquibase rollback counts (+1).

## Also to know
- The laptop was restarted in the middle. Before that I made a commit of unbuilt code (`c1246fa`), against "proof before commit"; it was never pushed alone and is followed by the proven state.
- Not run: a rejection in the real app end to end; two-server proof (M1).

## Questions for the owner (recommended one taken)
1. A case never exported is kept like a stopped case (hours set in Retention). Accept?
2. A case in Continue manually can be rejected too. Accept?
3. A processing step already running when the case is rejected finishes but saves nothing. Accept?
4. The compliance report still hides its two "Rejected" rows (task 0018, because nothing could be rejected). Show them now? Not done.
5. The full report's appendix prints the rejection but not its reason code (`PACKAGE_REJECTED` is not on the reviewed list). Add it? Not done.

## Suggested next step
Answers for 0070.

No other product's name or behaviour in code.
