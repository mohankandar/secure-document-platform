# Unit 18 — Confidence & Exception Processing

Status: IMPLEMENTED IN SOURCE; VERIFY ONLY.

## Preserve the approved pipeline

Do not redesign Unit 16/17. Native PDF/OCR normalization still feeds AI-first discovery, deterministic evidence, confidence/policy, permanent redaction, verification, and package-gated delivery.

## Business/content exception

- A `DETECTED` finding is the unit of Manual Review.
- High-confidence auto-confirmed findings are not sent back for review.
- `GET /api/v1/review-items` returns only pending findings with safe metadata; no raw sensitive snippet.
- REVIEWER role may confirm/dismiss a finding without create/assign/start ReviewTask mechanics.
- Confirm requires trusted geometry. Future visual adjustment may submit a corrected box; users must not type numeric X/Y in the final UI.
- Resolving the final pending finding automatically resumes redaction/verification/delivery.
- Low confidence is not retried as a technical failure.

## Technical exception

- Failures use stable dependency/code/retryable/attempt metadata.
- Retry only explicitly transient typed failures; default maximum is 2 total attempts.
- AI timeout/network/429/common temporary 5xx are transient.
- OCR timeout is transient. Missing Tesseract/configuration and deterministic unreadable/low-quality conditions are not blindly retried.
- Local delivery I/O may receive one bounded retry; invalid destination is non-retryable.
- AI may use an explicitly configured secondary provider after primary failure. Never silently use the heuristic adapter as secondary AI failover.
- OCR resilience accepts a secondary provider contract, but only Tesseract is currently wired.

## Verification rule

Kiro should make only minimal compile/runtime corrections if a genuine defect is found. Do not start Unit 19, Dashboard, Business Admin, watched folder, retention, S3, OIDC, IDP, or broad tests during Unit 18 verification.
