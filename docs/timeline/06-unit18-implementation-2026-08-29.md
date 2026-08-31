# Unit 18 Implementation — Confidence & Exception Processing

## Status

**IMPLEMENTED IN SOURCE; AWAITING BUILD/RUNTIME VERIFICATION.**

Unit 18 does not redesign the approved Unit 16/17 AI/OCR/redaction pipeline. It adds a small resilience/classification layer and a V1 finding-focused review backend contract.

## Focused content exception behavior

- Only `DETECTED` findings appear in the new focused review queue.
- Automatically confirmed high-confidence findings are not presented for re-review.
- Review items contain safe metadata only: document filename, entity type, confidence, page number, location availability, and a structured review reason. Raw sensitive snippets are not returned.
- V1 review actions are role-based and do not require create/assign/start ReviewTask mechanics.
- Reviewer can confirm or dismiss one finding. A visual workbench may later provide a corrected bounding box to the same confirm operation.
- A finding cannot be confirmed for redaction without a trusted bounding box.
- Resolving the final pending finding automatically resumes the existing redaction -> verification -> package-delivery pipeline. No separate business "Continue Processing" click is required.
- Legacy ReviewTask endpoints remain temporarily for compatibility but are not required by the target V1 flow.

## Technical failure classification

Provider/runtime failures now have stable non-sensitive classification metadata:

- dependency: AI / OCR / DELIVERY / STORAGE / UNKNOWN
- reason code such as AI_TIMEOUT, AI_RATE_LIMITED, OCR_TIMEOUT, OCR_UNAVAILABLE, DELIVERY_IO
- retryable flag
- attempts

Arbitrary provider exception text is not persisted as audit detail. Failed operations record the stable dependency/code/retryable/attempt values in safe audit detail; a dedicated indexed technical-exception/reporting projection is intentionally deferred to the later operational data-model unit.

## Selective retry and failover

- Default runtime policy: maximum 2 attempts with small bounded backoff.
- Only explicitly typed `retryable=true` failures are retried.
- Low confidence, ambiguous content, and validation/policy conditions are never blindly retried. Deterministic unreadable OCR content is a non-retryable technical failure, not a business-review condition.
- Ollama classifies timeout, connection failure, 429 and common temporary 5xx outcomes as transient.
- Tesseract timeout is retryable; missing executable and deterministic OCR process failures are not blindly retried.
- Atomic local delivery I/O may receive one bounded retry; an invalid/existing final destination is non-retryable.
- AI supports an optional configured secondary provider. The primary is selectively retried when transient; if it still fails, the secondary provider may be attempted. The heuristic development adapter cannot be configured as a silent AI failover.
- OCR resilience is provider-neutral and already accepts an optional secondary OCR port, although V1 currently wires only Tesseract.

## Configuration

- `SECUREDOC_RETRY_MAX_ATTEMPTS` (default 2)
- `SECUREDOC_RETRY_INITIAL_BACKOFF_MS` (default 250)
- `SECUREDOC_AI_SECONDARY_ENABLED` (default false)
- `SECUREDOC_AI_SECONDARY_PROVIDER`
- `SECUREDOC_AI_SECONDARY_BASE_URL`
- `SECUREDOC_AI_SECONDARY_MODEL`
- `SECUREDOC_AI_SECONDARY_TIMEOUT_SECONDS`

## New focused review API

- `GET /api/v1/review-items`
- `POST /api/v1/review-items/{findingId}/confirm`
- `POST /api/v1/review-items/{findingId}/dismiss`

These endpoints are REVIEWER role-gated and tenant-scoped. The API necessarily uses internal identifiers for addressing resources; the approved future business UI must keep those identifiers hidden from normal users.

## Deliberately deferred

- polished Manual Review workbench UI — next unit
- user assignment workflow — not required for V1
- retry scheduling/queue persistence and long-delay orchestration
- dedicated durable/indexed technical-exception projection and successful retry/failover history
- dedicated indexed review/technical-exception projections
- Business Admin/IT Admin screens
- additional OCR provider implementation
- storage-stream replay retry abstraction
- Dashboard/reporting redesign
- Unit 19 and later work
