LexaVault V1 — Multi-Node Intake Coordination + Shared Intake Storage
Verification status: VERIFIED / FROZEN — R7
Accepted: 2026-09-09

Entering frozen baseline
- Watched-Folder Intake + Fresh Golden-Path Workflow Matrix: VERIFIED / FROZEN
- Document Intelligence remains DISABLED for the frozen stability path

Final verified R7 baseline
- Backend: 246 tests (27 Domain + 81 Application + 138 API), 0 failures/errors/skips
- Frontend production build: PASS
- Frontend authorization tests: 8/8 PASS
- Liquibase: 55
- Rule-based detector: 1.6.0
- OCR guard: 1.4.0
- Kiro source modifications: none

Frozen behavior
1. PostgreSQL-backed renewable watched-folder scanner lease.
2. PostgreSQL clock_timestamp() is the authoritative coordination clock.
3. Logical node ID + unique process incarnation identify ownership.
4. Fencing generation advances monotonically on ownership transfer.
5. Exact owner + fencing generation is checked before claims and package mutations.
6. Candidates are atomically claimed into _processing.
7. Claim metadata contains only safe owner/token/timestamp data.
8. Terminal source disposition is fenced by owner + generation.
9. Older-generation stale claims are recoverable after the configured timeout.
10. Same-source fingerprint collisions resume the existing incomplete package rather than becoming false duplicates.
11. Recovery reconciles persisted documents by filename/MIME/size/SHA-256, skips matching persisted documents, uploads only missing documents and fails closed on conflicts.
12. Submitted-or-later same-source recovery finalizes the original intake rather than creating a duplicate.
13. Missing configured shared root fails closed; LexaVault does not recreate it as a node-local fallback.
14. Duplicate/idempotency remains authoritative across nodes.
15. Manual watched-folder scan uses the same distributed lease.
16. Coordination-store failure is fail-closed.
17. API intake remains active on every healthy app node; watched-folder leadership is scanner-only.

Independent runtime evidence
- Three nodes APP-01/APP-02/APP-03 verified against one PostgreSQL DB and one shared intake root.
- Exactly one active scanner owner and two standby nodes.
- Fencing generations observed monotonically 1 -> 2 -> 3 -> 4 -> 5; no reset/decrease.
- CREATED / 0-document crash recovery: PASS; same package completed, no false DUPLICATE_INTAKE.
- Partial 1-of-2 document crash recovery: PASS; same package completed with first_document.pdf=1 and second_document.pdf=1.
- Post-submit / pre-observation crash recovery: PASS; same package finalized ACCEPTED and archived once.
- Future-timestamp / stale-generation claim recovery using DB time: PASS.
- Shared-root outage: PASS; root not recreated; scan resumed after restore.
- Normal two-document watched intake: PASS.
- Duplicate/idempotency: PASS.
- PostgreSQL outage fail-closed and recovery: PASS.
- API availability on all three nodes: PASS.
- Authorization, privacy-safe logging, architecture boundaries and source integrity: PASS.

Graceful-shutdown verification note
- Windows console JVM verification could not deliver a true graceful @PreDestroy shutdown signal.
- Graceful release-in-place is covered by the targeted unit test gracefulReleaseExpiresLeaseWithoutDeletingFencingHistory, by the @PreDestroy releaseLease() -> leasePort.release() source path, and by monotonic runtime fencing history.
- This is a documented environment limitation, not an open R7 product finding.

Freeze rule
Do not reopen this unit unless a reproducible defect or an explicitly scoped later enhancement requires it.

Next active V1 implementation unit
Scanner Bridge / Enterprise Scanner Intake Integration.
