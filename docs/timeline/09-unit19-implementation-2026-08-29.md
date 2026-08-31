# Unit 19 Implementation — Minimal Manual Review Workbench

**Status:** APPROVED 2026-08-29 — see `11-unit19-approval-2026-08-29.md`

## Goal

Replace the developer review harness with a focused reviewer experience that shows only uncertain findings, the relevant source page, a visual redaction area, and simple business actions. No package/task/finding IDs, assignment workflow, task-start mechanics, or typed X/Y coordinates are exposed to the normal reviewer.

## Backend additions

- Extended focused review item summaries with the current normalized bounding box when one exists.
- Added a tenant-scoped, `REVIEWER`-gated page-preview endpoint:
  - `GET /api/v1/review-items/{findingId}/page-preview`
- The preview path renders only the relevant source page to PNG. It does not expose the full original PDF as a download.
- Added provider-neutral `ReviewPageRenderPort` and a PDFBox page-render implementation.
- Preview is limited to pending `DETECTED` findings and uses the existing original artifact only as an internal source.
- Existing Unit 18 confirm/dismiss and automatic-resume behavior is reused unchanged.

## Frontend workbench

`/review` is now the V1 Manual Review workbench:

- queue contains only findings needing attention
- filename, entity type, page, confidence, and safe reason are shown
- relevant page image is rendered in context
- existing location is highlighted visually
- reviewer can drag/resize an existing box
- when no trusted location exists, reviewer can draw a box directly on the page
- `Confirm & Submit` confirms the sensitive item using the selected visual geometry
- `Not Sensitive` dismisses the item
- last resolved finding resumes processing automatically
- internal IDs and numeric coordinate inputs are hidden
- legacy review-task APIs remain in backend for compatibility but are not used by the V1 workbench

## Deliberate scope limits

Unit 19 does not implement the approved shared Dashboard, Business Record/identifiers, Business Admin, IT Admin, watched-folder intake, retention, or Unit 20 work.
