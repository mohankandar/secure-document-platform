# Unit 22 — Delivery + Lifecycle

Status: APPROVED 2026-08-29. Frozen baseline for Unit 23 and later work.

Preserve Units 16–21. Unit 22 completes V1 customer delivery and temporary internal-artifact lifecycle.

## Frozen behavior for this unit

- Customer filenames and document boundaries are preserved.
- Delivery is package/batch gated; a successful export cycle represents the complete submitted intake batch.
- Supported delivery adapters in source: local filesystem, API/callback, S3-compatible object storage.
- Delivery provider connection details are IT/Platform configuration; Business Admin owns lifecycle/approval policy.
- Each successful package delivery creates a structured export record plus per-document filename/size/checksum/destination facts.
- Technical retries before destination success do NOT consume a business export cycle.
- Max successful export cycles per original intake lineage: 3.
- Reprocess reuses the retained original within the same intake/package lineage, regenerates findings/working output, and automatically resumes the existing AI/OCR/redaction pipeline.
- Successful re-export resets retention deadlines from the latest successful delivery.

## Retention

- Original retention clock starts only after successful delivery. Default 24h; Business Admin configurable.
- Temporary internal redacted copy: default 24h, hard maximum 72h.
- Never-successfully-exported technical failure hold: default 72h; bounded extension up to 14 days.
- Customer-delivered output is durable at the destination and is never deleted by LexaVault internal retention purge.
- Due internal artifacts are deleted and audited; lifecycle/export metadata remains.
- Minimal in-process V1 purge scheduling is acceptable; durable/clustered scheduling is later hardening.

## Final delivery approval

When Business Admin enables final approval:
- redaction + verification finish first,
- package stops at `PENDING_FINAL_APPROVAL`,
- destination receives nothing yet,
- APPROVER releases delivery,
- pipeline resumes automatically,
- retention begins only after delivery succeeds.

## Original access

Active V1 must not expose a normal original-PDF retrieval/download product flow. Manual Review remains a tenant/role-scoped rendered-page preview only. Legacy backend vault/access structures may remain for compatibility but are not active V1 navigation.

## Approval note

Real-stack verification passed for lifecycle/export cycles, retention/purge, final approval, local/API delivery, checksum/export records and Units 16–21 regressions. S3 runtime execution remains a later hardening check because no local S3-compatible service was available. Unit 23 may build on this approved baseline.
