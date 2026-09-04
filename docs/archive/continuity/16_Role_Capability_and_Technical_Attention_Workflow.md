# V1 Role/Capability and Technical Attention Workflow — 2026-09-01

> **Authorization supersession — 2026-09-01:** The role/authorization statements in this historical record are superseded by `24_Role_Simplification_Migration_2026-09-01.md`. Functional/runtime evidence and non-authorization behavior in this record remain applicable.

## Status

Implemented by ChatGPT on top of the runtime-validated 2026-09-01 baseline. Awaiting local build/runtime verification before the delta is frozen.

## Frozen V1 authorization decision

Role-to-function mapping is product-defined and not tenant-editable in V1. Tenant IT assigns users to roles; Business Admin manages business/redaction policy, not security permissions.

Product roles:

- `USER`
- `REVIEWER`
- `APPROVER`
- `BUSINESS_ADMIN`
- `TENANT_IT_ADMIN`
- `PLATFORM_OPERATIONS_ADMIN`
- `ADMIN` (controlled super-admin)

The ambiguous legacy `PLATFORM_ADMIN` product role is retired from the V1 role mapper/local role selector.

### Key fixed capabilities

- `MANUAL_REVIEW`: REVIEWER, APPROVER
- `PACKAGE_APPROVE_DELIVERY`: APPROVER
- `PACKAGE_REPROCESS`: APPROVER
- `PACKAGE_REMOVE_FROM_DASHBOARD`: APPROVER
- `TENANT_POLICY_MANAGE`: BUSINESS_ADMIN
- `TENANT_INTEGRATION_MANAGE`: TENANT_IT_ADMIN, PLATFORM_OPERATIONS_ADMIN
- `TECHNICAL_ADMIN_VIEW`: TENANT_IT_ADMIN, PLATFORM_OPERATIONS_ADMIN
- `TECHNICAL_EXCEPTION_VIEW`: APPROVER, TENANT_IT_ADMIN, PLATFORM_OPERATIONS_ADMIN
- `TECHNICAL_RETRY`: APPROVER, TENANT_IT_ADMIN, PLATFORM_OPERATIONS_ADMIN
- `AUDIT_VIEW`: TENANT_IT_ADMIN, PLATFORM_OPERATIONS_ADMIN
- `PLATFORM_OPERATIONS`: PLATFORM_OPERATIONS_ADMIN

`ADMIN` receives all product capabilities. UI visibility is driven by trusted `/api/v1/me` capability data and backend authorization remains authoritative through `@RequireCapability`.

## Defects corrected

1. A plain USER no longer sees `Open Manual Review` in package details. Unauthorized direct review API access remains denied.
2. BUSINESS_ADMIN no longer sees `Reprocess from Retained Original`; the backend also requires `PACKAGE_REPROCESS`.
3. Dashboard `Remove from Dashboard` is shown only when the actor has `PACKAGE_REMOVE_FROM_DASHBOARD`.
4. Manual Review, Business Admin, technical administration, audit, package reprocess, final approval, dashboard removal and watched-folder technical actions now use explicit product capabilities rather than duplicated UI-only role assumptions.

## Technical Attention workflow

A dedicated tenant-scoped API/UI flow now exposes safe technical issue details for a FAILED package:

- failure category
- failed processing stage
- affected document reference when known
- retryable yes/no
- failure time
- correlation ID
- original retained-until timestamp
- safe human-readable explanation

`PLATFORM_OPERATIONS_ADMIN` additionally receives safe operator metadata (dependency, stable internal failure code, attempt count and runtime environment). No raw stack traces, credentials, tokens, provider secrets, or document values are returned.

### Selective retry

`Retry Processing` is available only when the latest persisted sanitized technical classification says the failure is retryable **and** all retained ORIGINAL artifacts are still available within retention.

Retry semantics:

- package must be `FAILED`;
- only FAILED documents are reset to `UPLOADED` and have transient findings/non-original working artifacts cleared;
- already PROCESSED documents remain processed;
- delivery-only failures can retry delivery without re-running completed documents;
- package returns to `SUBMITTED`, then the existing package-processing dispatcher resumes work asynchronously;
- no new broker/queue or duplicate processing implementation was introduced;
- non-retryable deterministic/configuration failures do not get a retry action.

Audit action: `PACKAGE_TECHNICAL_RETRY_REQUESTED`.

## Manual verification requested

1. USER: PENDING_REVIEW package shows waiting-for-review text and no review button; direct review route/API denied.
2. REVIEWER: review action visible and usable.
3. APPROVER: Manual Review/reprocess/remove actions appear only in valid states.
4. BUSINESS_ADMIN: business policy page available; reprocess/manual-review/technical-admin actions absent.
5. TENANT_IT_ADMIN: Technical Administration, audit and Technical Issue Details available; Business Admin unavailable.
6. PLATFORM_OPERATIONS_ADMIN: same technical workflow plus safe operator diagnostics; platform-only purge capability retained.
7. Retryable FAILED package: `Retry Processing` returns promptly and Dashboard polling shows processing progression.
8. Non-retryable FAILED package: no retry button and backend rejects direct retry.
