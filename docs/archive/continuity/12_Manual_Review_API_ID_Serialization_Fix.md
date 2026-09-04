# Manual Review API ID Serialization Fix — 2026-08-31

## Runtime evidence

Browser DevTools reproduced the page-preview failure for a valid package-scoped review item. The request URL was:

`/api/v1/review-items/[object%20Object]/page-preview`

The source PDF and its `ORIGINAL` artifact were confirmed present under `securedoc-storage`, so retention/storage was not the cause.

## Root cause

`FocusedReviewController.list()` returned application-layer `ReviewQueueResult` / `ReviewItemSummary` records directly. Their `FindingId`, `PackageId`, and `DocumentId` fields are domain value-object records. Jackson therefore serialized those IDs as JSON objects with a `value` field rather than as the plain UUID strings declared by the frontend contract.

The TypeScript client treated `findingId` as a string. Browser URL interpolation converted the object to `[object Object]`, and the preview controller rejected it before `ReviewPagePreviewService` could run. This explains the HTTP 400 and the absence of `REVIEW_PREVIEW_REJECTED`/`REVIEW_PREVIEW_RENDERED` service diagnostics.

## Correction

The Manual Review API boundary now maps the application result to explicit response DTOs whose identifiers are plain UUID strings:

- `findingId: string`
- `packageId: string`
- `documentId: string`

No global Jackson/domain identity serialization behavior was changed. No AI, detection, redaction, storage, retention, tenant-scope, or review-decision behavior was changed.

A focused regression test serializes the response and asserts that the three IDs are plain UUID strings, not nested `{ "value": ... }` objects.

## Runtime validation required

1. Rebuild and restart LOCAL.
2. Open the same package-scoped Manual Review screen.
3. In DevTools Network, verify the preview URL contains a real UUID:
   `/api/v1/review-items/<finding-uuid>/page-preview`
4. Verify selected page 24 renders page 24 and selected page 31 renders page 31.
5. Verify backend logs `REVIEW_PREVIEW_RENDERED ... page=24` / `page=31` as selected.
6. Verify Confirm/Dismiss actions also use the same real UUID and no `[object Object]` appears in any review-item request URL.
