# Unit 19 Verification Plan — Minimal Manual Review Workbench

Kiro verification should be build/runtime/browser focused. Do not redesign Unit 19 and do not start Unit 20.

## Required gates

1. `mvn clean verify` succeeds across all backend modules.
2. `npx tsc --noEmit` and `npm run build` succeed.
3. Real PostgreSQL + Ollama + Tesseract local profile starts successfully.
4. A low-confidence located finding appears in Manual Review with the correct page preview and initial highlighted box.
5. Dragging/resizing the box changes the submitted normalized geometry; reviewer never types coordinates.
6. `Confirm & Submit` confirms the finding and, when it is the last unresolved item, automatically resumes redaction/verification/delivery to `COMPLETED`.
7. `Not Sensitive` dismisses only that finding and it is not redacted.
8. An unlocated semantic finding shows the relevant page with no box, allows the reviewer to draw a box visually, and cannot be confirmed until a box exists.
9. Queue contains only unresolved findings; auto-confirmed findings do not appear.
10. USER role receives 403 for queue and page preview; REVIEWER succeeds; cross-tenant finding access is denied/not found.
11. Page-preview endpoint returns only the relevant PNG page, not a downloadable original PDF.
12. No sensitive snippet/value is added to queue/API/audit metadata.
13. Unit 16 digital and Unit 17 OCR paths continue to complete automatically when no review is required.

## UI acceptance

Reviewer-facing UI must not show:

- package UUID
- document UUID
- finding UUID
- review task ID
- assignment/start-task controls
- numeric left/top/width/height inputs

Reviewer-facing primary actions are `Confirm & Submit`, `Not Sensitive`, and visual `Adjust area`/draw behavior.
