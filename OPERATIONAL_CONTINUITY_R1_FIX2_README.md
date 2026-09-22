# Operational Continuity R1 FIX2 — Durable Processing Attempt Budget Across Review Continuations

Status: **IMPLEMENTED FIX2 CANDIDATE / LOCAL BUILD + FOCUSED RUNTIME REVALIDATION REQUIRED**

## Validation finding

Focused Kiro review of R1 FIX1 returned **B/C/D/E PASS** (HA/fencing, legacy PROCESSING recovery, Technical Attention retention, privacy telemetry) and **A FAIL** for one bounded-recovery edge case.

A recovered package could reach `PENDING_REVIEW` on attempt 2, wait for a human decision until its processing lease expired, and then resume through a normal non-recovery dispatch. The PostgreSQL lease upsert previously used `ELSE 1` for every non-recovery conflict, resetting `attempt_count` from 2 back to 1. Repeated review/recovery cycles could therefore bypass the intended three-attempt automatic recovery ceiling.

## FIX2

- Ordinary processing continuations now preserve the durable `attempt_count` when an expired lease row already exists.
- Automatic recovery still increments `attempt_count` atomically and remains capped by the configured maximum (default 3).
- Explicit human-requested redrive/reprocess starts a fresh processing cycle and intentionally resets the automatic recovery budget to attempt 1.
- Manual Review, Manual Fallback and Final Delivery Approval remain ordinary continuations and do **not** reset the budget.
- Added explicit command semantics (`resetAttemptBudget`) so continuation versus fresh redrive is not inferred from `recovery=false`.
- Added focused regression tests for command-mode semantics and the PostgreSQL attempt-count branch.
- HA fencing/exhaustion logic, Technical Attention retention, support actions, product telemetry, Dashboard retention and schema are unchanged.
- Liquibase remains **73 changeSets**; no migration is required.

## Required focused validation

1. Full local build/startup.
2. Create/reuse a disposable package and establish attempt 2 through a real expired-lease recovery.
3. Drive that recovered attempt into `PENDING_REVIEW` and complete the human review.
4. Confirm the post-review continuation still has `attempt_count = 2` (not 1).
5. Expire that continuation lease and confirm automatic recovery becomes attempt 3.
6. Expire attempt 3 and confirm the package moves to Technical Attention with `PROCESSING_RECOVERY_EXHAUSTED`; no attempt 4 is created.
7. Optional regression: an explicit Tenant IT reprocess/redrive should begin a fresh cycle at attempt 1.

Do not spend Kiro credits re-reviewing B/C/D/E unless runtime evidence contradicts the prior focused PASS.
