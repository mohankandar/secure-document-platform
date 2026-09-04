# Unit 18 Verification Plan — Confidence & Exception Processing

## Status

Verification only. Unit 19 must not start during this pass.

## Runtime stack

Use the real local PostgreSQL, Ollama provider/model already installed, and real Tesseract. Do not silently substitute heuristic AI.

## Acceptance matrix

1. **Unit 16/17 regression:** digital and scanned high-confidence documents still complete automatically with permanent verified redaction and batch-gated delivery.
2. **Focused queue:** create a mixed-confidence document; only `DETECTED` findings appear in `GET /api/v1/review-items`. Auto-confirmed findings are absent.
3. **Dismiss final review item:** dismiss the only pending finding; processing resumes automatically, confirmed findings redact, verification/delivery complete, package becomes `COMPLETED`.
4. **Confirm final review item:** confirm a pending located finding; processing resumes automatically and the confirmed finding is redacted.
5. **Unlocated finding:** confirmation without trusted geometry must be rejected. Supplying a valid corrected bounding box through the review API may then confirm and resume processing.
6. **No assignment mechanics:** focused review must not require create/assign/start ReviewTask operations.
7. **AI transient retry:** force a primary AI timeout/unavailable condition and verify only typed transient failure receives the configured bounded retry (default two total attempts).
8. **AI failover:** when a secondary AI provider/model is explicitly configured, force primary technical failure and verify the secondary can be attempted. The heuristic adapter must never be used silently as secondary failover.
9. **AI non-technical/content uncertainty:** low confidence/review must not trigger retry/failover merely because the finding requires review.
10. **OCR technical classification:** OCR timeout is retryable; missing executable/deterministic OCR failure is non-retryable and must result in technical failure, not `PENDING_REVIEW`.
11. **Delivery retry/failure:** transient typed delivery I/O may retry; exhausted/non-retryable delivery failure must leave the package failed/technical, never successfully completed or pending business review.
12. **Safe audit:** technical failure audit must contain stable dependency/code/retryable/attempt values and must not contain provider exception text or detected sensitive values.
13. **Tenant/role security:** review queue/actions are tenant scoped and REVIEWER role gated.
14. **Build gate:** final `mvn clean verify`, `npx tsc --noEmit`, and `npm run build` must all pass after any minimal correction.

## Known non-blocking limitations

- Retries are synchronous and intentionally small; durable delayed retry scheduling belongs later.
- Successful retry/failover attempt history is not yet a dedicated persisted projection.
- Technical classification on terminal failures is currently available through safe audit detail; indexed technical-exception/reporting projection is later work.
- Storage stream operations are classified but not blindly replayed because arbitrary `InputStream` retry is unsafe without a replayable-content abstraction.
- Unit 17 duplicate OCR remains a known performance optimization and is not part of Unit 18.

## Stop rule

Kiro may make only minimal production-code corrections required by build/runtime verification. Stop and report `UNIT 18 APPROVE` or `DO NOT APPROVE`. Do not implement the Unit 19 UI, Dashboard, Business Admin, watched-folder, retention, S3/API delivery, IDP, or broad tests.
