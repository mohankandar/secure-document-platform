# Unit 17 Implementation — OCR / Scanned & Image PDF Normalization

**Date:** 2026-08-29  
**Status:** IMPLEMENTED IN SOURCE — AWAITING FULL BUILD + LOCAL RUNTIME ACCEPTANCE

## Scope

Unit 17 extends the approved Unit 16 digital-PDF path with page-aware OCR. It does not introduce a second detection/redaction workflow.

```text
PDF page
   ↓
meaningful native PDF text?
   ├── yes → PDFBox text + coordinates
   └── no  → render page → Tesseract OCR text + coordinates + confidence
                              ↓
                   normalized PageText/TextSpan
                              ↓
                existing Unit 16 AI-first pipeline
```

## Implementation

- Added provider-neutral `OcrPort` in the application layer.
- Added local/on-prem `TesseractOcrAdapter` using Tesseract TSV word output.
- Added `HybridPdfTextExtractionAdapter` as the primary `TextExtractionPort`.
- Native PDF pages continue to use the approved PDFBox extraction path.
- Sparse/image-only pages are rendered with PDFBox and OCR'd page-by-page.
- Mixed PDFs can therefore contain native and OCR-normalized pages in one document.
- `PageText` now records extraction source and source confidence.
- `TextSpan` now carries recognition confidence; native spans default to 1.0.
- OCR word boxes are normalized to the same top-left 0..1 coordinate model used by redaction.
- OCR confidence is incorporated into per-finding confidence so low-quality recognition cannot be treated as a high-confidence irreversible redaction merely because AI confidence is high.
- Non-blank pages with empty OCR output fail closed.
- Non-blank OCR pages below the configured minimum page confidence fail closed.
- Genuinely blank pages are allowed to continue through the no-finding safe-output path.
- Worker orchestration was aligned with the approved mixed-confidence rule so REVIEW never triggers automatic redaction.

## Local checks performed during implementation

- All `securedoc-domain` + `securedoc-application` Java sources compile with Java 21.
- Changed OCR and hybrid-extraction sources were syntax/integration compiled with local dependency stubs where the full Maven dependency graph was unavailable.
- Real local Tesseract 5.5 OCR smoke succeeded on an image containing SSN, DOB and FICO score.
- OCR output produced text, word-level normalized boxes and ~0.95 average OCR confidence.
- OCR text fed into the existing deterministic recognizer and `FindingLocationMapper` successfully resolved SSN, DOB and CREDIT_SCORE locations; OCR span confidence was available for each finding.

These checks are not a substitute for `mvn clean verify` and the full PostgreSQL + Ollama + Tesseract runtime acceptance on the user's development machine.

## Required verification before approval

1. `mvn clean verify` from `backend/`.
2. Frontend `npx tsc --noEmit` and `npm run build` (no UI redesign expected).
3. Real local Ollama + real Tesseract; no heuristic substitution for the canonical acceptance.
4. Digital-only PDF regression: Unit 16 behavior unchanged and OCR not used for normal text-heavy pages.
5. Scanned/image-only PDF containing all four V1 categories: OCR → AI → location → permanent redaction → verification → delivery → `COMPLETED`.
6. Mixed PDF: at least one native page and one scanned page, both findings processed through the same package.
7. OCR low-confidence finding: does not auto-redact; review behavior is preserved.
8. Non-blank unreadable/very-low-quality scan: fails closed as technical failure, not `COMPLETED`/`PENDING_REVIEW` due to content policy.
9. Blank scanned page: safe no-finding output path succeeds.
10. Existing Unit 16 A–G acceptance behavior remains intact, especially package-atomic delivery and technical delivery failure.

## Not started

- Unit 18 confidence/exception UI/provider failover
- focused Manual Review redesign
- Dashboard/Business Admin
- watched-folder intake
- retention lifecycle
- S3/API delivery
- IDP/production identity

## Final verification status

**APPROVED 2026-08-29.** Full Maven/frontend builds and real local PostgreSQL + Ollama (`gemma3:12b`) + Tesseract 5.5 runtime acceptance passed for scanned-only, mixed, AI-only-through-OCR, confidence safety, unreadable, blank, digital-regression, and multi-page scenarios. See `05-unit17-approval-2026-08-29.md`.
