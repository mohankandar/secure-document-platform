# Manual Review Final-Item Async Resume Correction — 2026-09-01

## Runtime defect

Local runtime testing proved that Manual Review package scoping, exact PDF page rendering, Dashboard polling, and Approver Dashboard removal worked. A remaining UX defect appeared only on the final review item: the decision was persisted and the package successfully redacted/exported, but the reviewer UI remained on `Saving…` while the same HTTP request synchronously performed post-review redaction, verification, and delivery.

## Root cause

`FocusedReviewService` saved the reviewer decision and, when no `DETECTED` findings remained, directly invoked `TriggerPackageProcessingUseCase.execute(...)` on the request thread. The resolve API therefore did not return until all post-review document processing and package delivery finished.

## Narrow correction

- Added provider-neutral application port `PackageProcessingDispatcher`.
- Added V1 in-process adapter `InProcessPackageProcessingDispatcher` backed by a bounded Spring `ThreadPoolTaskExecutor` (core 2, max 4, queue 100) with caller-runs saturation fallback so accepted review decisions do not silently lose their continuation work.
- When the final actionable finding is resolved, the package now transitions from `PENDING_REVIEW`/`IN_REVIEW` to `PROCESSING`, the transition is persisted/audited, and post-review processing is dispatched separately.
- The review HTTP response returns after dispatch acceptance rather than waiting for redaction/export.
- Existing `TriggerPackageProcessingService` remains the authoritative redaction/verification/export orchestrator; no duplicate processing implementation was introduced.
- Existing Dashboard polling then shows Processing -> Exported/Completed without requiring the reviewer request to remain open.
- Manual Review success text now states that processing resumed in the background.

## Safe observability

New high-level logs contain IDs/status metadata only:

- `REVIEW_PROCESSING_RESUME_SCHEDULED`
- `REVIEW_PROCESSING_RESUME_STARTED`
- `REVIEW_PROCESSING_RESUME_COMPLETED`
- `REVIEW_PROCESSING_RESUME_FAILED` with exception class only

No document content or sensitive values are logged.

## V1 limitation retained

This remains an in-process V1 execution mechanism. A durable queue/outbox consumer may replace the dispatcher adapter later without changing review-domain behavior. This correction does not introduce Kafka/distributed scheduling.

## Regression scope

No AI prompt, AI provider/model routing, sensitive-data detection, confidence policy, redaction implementation, delivery implementation, retention implementation, or Dashboard removal semantics were changed.

## Local verification requested

Use a package with at least two review findings. Resolve the first and then the last item. On the last decision verify:

1. `Saving…` clears promptly.
2. Manual Review displays `Review complete. Processing has resumed in the background.` and no actionable items for that package.
3. Backend emits `REVIEW_PROCESSING_RESUME_SCHEDULED`, then STARTED and COMPLETED (or a safely classified failure).
4. Dashboard shows active Processing and then Exported/Completed through the already-approved polling behavior.
5. The decision is persisted once; no duplicate redaction/export cycle occurs.
