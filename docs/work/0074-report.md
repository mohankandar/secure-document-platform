# Report 0074: "Page to check" in Review

Date: 2026-11-24
Branch: `feature/0074-page-to-check`, from the integration branch (0071 merged)
Outcome: **DONE** (3 questions). Replaces 0070.

## What was done
- A "Page to check" shows **"Unreadable: check by hand"** and takes **several boxes** (today's box tool, each can be removed). It can be finished only with **"I have reviewed this page"** ticked; with no box, it is "Done, nothing to redact".
- **The server holds the rule:** a new call, `POST /review-items/{id}/page-checked` (`reviewed`, `boxes`). The ordinary Redact and Keep visible calls refuse a page to check (`LV-REV-004`), and so do the keys and "Redact all like this". The case goes on only when no page to check (or other finding) is open.
- **Each box** becomes a confirmed finding (place only, no text) and is redacted as any other. Boxes and decision are saved in one transaction: never one without the other. Two people at once: one check stands, the other gets "already decided" and leaves nothing.
- **Audited:** who, document id, page number, number of boxes (new action `REVIEW_PAGE_CHECKED`). It counts as a finding decided by a reviewer in the compliance report.
- `LV-PRC-006` keeps its stop. No migration, no new dependency, detection unchanged.

## Commands run and results
Isolated copy, `mvn verify`, nothing installed (rule 11). Browser tests on base 15275.

| Command | Result |
|---|---|
| `mvn -B -o -Pdev-build verify`, PostgreSQL on (throwaway, removed) | BUILD SUCCESS: domain 85, application 318, scan bridge 136, infrastructure 130, API 818 (1 skipped), 0 failures |
| frontend suites, 1 run | auth 10, vocabulary 214, ui-framework 19, oidc-signin 190, tablet 95, a11y 164: all pass |
| no-redacted-value check | PASS, 0 matches |
| product-name guard | clean |

New tests: `ReviewPageCheckTest` (two pages hold the case until both are confirmed; audit; nothing gets round the tick), `SearchableRedactionServiceTest` (the boxes are covered in the output), `review.spec.ts` (several marks, tick, what is sent). Pictures: `docs/work/0074-proof/` (mocked API).

## Existing tests changed
`review.spec.ts`: the old words ("drag one mark that covers all of it") and button of a page to check.

## Not done, or left for the milestone
- Two people checking one page at the same moment, against a real database: the refusal is unit-tested, the roll-back of the boxes is not.
- A page to check end to end in the real app, and the two-server proof: M1.
- Boxes drawn but not sent are lost on leaving Review (the page warns first) or on moving to another page to check and drawing there.

## Questions for the owner (recommended one taken)
1. A drawn box has the kind "Page to check", not a sensitive type (nobody can read what it was). Accept?
2. Up to 200 boxes per page. Accept?
3. The audit detail is not on the compliance appendix's reviewed list, so the full report prints the action without the page number. Add it?

## Suggested next step
The next READY task.

No other product's name or behaviour in code.
