# Unit 19 — Minimal Manual Review Workbench

Status: IMPLEMENTED IN SOURCE; verify only before approval.

## Frozen reviewer experience

- `/review` is a focused exception workbench, not a developer task console and not a separate role-specific Dashboard.
- Queue shows only unresolved `DETECTED` findings from packages genuinely awaiting review.
- High-confidence findings already accepted automatically must not be shown for re-review.
- Relevant source page is rendered as a page image; the full original PDF is not exposed by the review-preview API.
- Existing trusted bounding box is highlighted visually.
- Reviewer may drag/resize the area. If no trusted location exists, reviewer draws the area directly on the page.
- Reviewer never types left/top/width/height or sees package/document/finding/task UUIDs.
- Primary action: `Confirm & Submit`.
- Secondary business action: `Not Sensitive`.
- Last unresolved finding automatically resumes redaction, verification, and package delivery.
- No assignment/create/start ReviewTask sequence is part of the V1 UI.
- REVIEWER role and tenant isolation apply to queue, preview, confirm, and dismiss.
- Preview/audit metadata must not expose raw sensitive snippets.

## Unit 19 scope boundary

Do not start the shared Dashboard, Business Record/identifiers, Business Admin, watched-folder intake, retention, IT Admin, or Unit 20 work while verifying Unit 19.
