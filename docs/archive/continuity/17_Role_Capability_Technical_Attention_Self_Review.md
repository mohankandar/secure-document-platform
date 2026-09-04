# LexaVault V1 — Role/Capability + Technical Attention Self-Review

> **Authorization supersession — 2026-09-01:** The role/authorization statements in this historical record are superseded by `24_Role_Simplification_Migration_2026-09-01.md`. Functional/runtime evidence and non-authorization behavior in this record remain applicable.

Date: 2026-09-01
Baseline: `LexaVault-V1-manual-review-final-item-async-resume-fix-2026-09-01.zip` (runtime validated by user)

## Scope implemented

- Fixed V1 product-defined role-to-capability mapping.
- Added `TENANT_IT_ADMIN` and `PLATFORM_OPERATIONS_ADMIN`; removed legacy `PLATFORM_ADMIN` from active local/OIDC role lists.
- `/api/v1/me` now returns backend-derived capabilities for frontend visibility.
- Manual Review UI/API requires `MANUAL_REVIEW`; USER/Business Admin no longer receive review actions.
- Reprocess requires `PACKAGE_REPROCESS`; Business Admin no longer receives the button.
- Dashboard removal requires `PACKAGE_REMOVE_FROM_DASHBOARD` in UI and API.
- Technical Administration is available to Tenant IT / Platform Operations roles.
- Added safe Technical Issue Details and selective technical retry.

## Technical issue safety

The issue-details response exposes only stable/sanitized operational metadata: category, stage, retryability, timestamps, correlation ID, retention deadline and safe explanation. Platform Operations additionally gets dependency, stable error code, attempts and environment. No raw exception/stack trace, credentials, API keys, tokens, prompt/document text or sensitive values are exposed.

## Retry safety

Retry is enabled only when:

1. package is FAILED;
2. retained ORIGINAL artifacts are present and still within lifecycle retention;
3. delivery-only failure is classified retryable, or every currently FAILED document has a latest structured retryable technical classification.

For retry, only FAILED documents are reset. Their transient findings and non-original working artifacts are cleared. Already-PROCESSED documents remain processed. Delivery-only failure can retry delivery without unnecessarily re-running all documents. The existing bounded in-process package dispatcher is reused.

## Regression protection

- AI provider/prompt source: unchanged.
- sensitive-data detection source: unchanged.
- redaction module: unchanged.
- existing retention scheduler/purge implementation: unchanged.
- Dashboard polling implementation: unchanged apart from capability-gated actions/links.
- Manual Review exact-page and async final-item processing implementation: unchanged apart from capability authorization annotations.

## Local checks completed here

- Java 21: entire domain module source compiled successfully.
- Java 21: new technical application/query/retry code plus required application dependencies compiled successfully.
- Changed TS/TSX files passed TypeScript parser/transpile syntax checks.
- `application.yml` parsed successfully.
- AI/detection/redaction directory comparison against the validated baseline: unchanged.

Full Maven reactor and full frontend type/Vite build could not be completed in this runtime. The user's `build-all.cmd` remains authoritative.

## Manual runtime checks requested

1. USER — no Open Manual Review; direct review route shows permission required; backend review API denied.
2. REVIEWER — Manual Review visible/functional.
3. APPROVER — Manual Review + valid Reprocess + Remove from Dashboard; retryable technical issue can be opened/retried.
4. BUSINESS_ADMIN — Business Admin available; Manual Review/Reprocess/Technical Administration absent.
5. TENANT_IT_ADMIN — Tenant IT Admin + Audit visible; safe Technical Issue Details; retry when eligible.
6. PLATFORM_OPERATIONS_ADMIN — Platform Operations + Audit visible; safe operator diagnostics included.
7. Retryable FAILED package — Retry Processing responds promptly and Dashboard polling shows progression.
8. Non-retryable FAILED package — no Retry button; direct retry request rejected.
