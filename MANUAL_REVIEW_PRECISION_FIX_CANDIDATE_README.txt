LexaVault V1 - Manual Review Precision Regression Fix Candidate (Revision 2)
Date: 2026-09-07

Scope
-----
This is a narrow correction for the OCR safety-net review explosion reproduced with
000422610006.pdf. It does NOT change the permanent-redaction engine, tenant policy,
Document Intelligence Foundation, R11 AI-first architecture, or database schema.

Observed runtime progression
----------------------------
- Before the precision fix: approximately 11 OCR-guard Manual Review items, including
  false Credit Score items on category/negative-control pages.
- After Revision 1: only 3 review items remained, all on page 15: SSN, Date of Birth,
  and Credit Card Number. Runtime evidence showed `ocrGuardReview=3`.
- Those remaining page-15 values are already permanently blacked out in the source PDF.
  Windows/Tesseract can recover the strong labels while returning no value token for a
  solid blackout, so text-only OCR evidence cannot prove that the field is already safe.

Root cause
----------
The OCR sensitive-field safety guard correctly exists to prevent silent export when OCR
recovers a strong sensitive label but cannot safely resolve its value. Three cases needed
better discrimination:

1. Geometry fallback treated ordinary prose to the right of "Credit Score" as an unresolved
   score candidate.
2. Pre-masked solid black rectangles can OCR as graphic noise such as "|", "| |", or a
   very low-confidence short/wide token. Revision 1 added strict OCR-artifact suppression.
3. Some OCR engines return no token at all for a solid blackout. In that case the strong
   label remains blank and Revision 1 correctly failed safe to Manual Review, but the rendered
   page itself still contains trustworthy evidence that the value region is already blacked out.

Revision 2 behavior
-------------------
- Credit-score geometry fallback creates review only for score-shaped ambiguity (three
  alphanumeric characters with at least two digits, e.g. 76B/7B8), not ordinary prose.
- Existing OCR-artifact mask heuristics remain in place.
- The OCR PDF edge now extracts provider-neutral `SOLID_DARK_RECTANGLE` geometry from the
  rendered page. No pixels, OCR text, or document content are stored or logged by this evidence.
- When a strong SSN/DOB/PAN/Credit Score label has a blank OCR value, the guard suppresses
  Manual Review only when a high-confidence compact solid-dark rectangle is aligned immediately
  to the right of that exact label row.
- A blank strong label with no aligned verified blackout still fails safe to Manual Review.
- A visible value is never hidden merely because some other dark rectangle exists on the row.
- An exact visible 300-850 score is still promoted to a located finding.
- Ambiguous value-shaped score and normal unreadable exposed payment-card fields still review.
- Detector evidence version is now `ocr-sensitive-field-guard` 1.4.0.

Files changed cumulatively for this unit
----------------------------------------
1. backend/securedoc-application/src/main/java/com/securedoc/platform/application/port/out/TextExtractionPort.java
2. backend/securedoc-application/src/main/java/com/securedoc/platform/application/detection/service/OcrSensitiveFieldSafetyPolicy.java
3. backend/securedoc-application/src/main/java/com/securedoc/platform/application/detection/service/SensitiveDataDetectionService.java
4. backend/securedoc-infrastructure/src/main/java/com/securedoc/platform/infrastructure/extraction/HybridPdfTextExtractionAdapter.java
5. backend/securedoc-infrastructure/src/main/java/com/securedoc/platform/infrastructure/extraction/SolidDarkRectangleDetector.java
6. backend/securedoc-application/src/test/java/com/securedoc/platform/application/detection/service/OcrSensitiveFieldSafetyPolicyTest.java
7. backend/securedoc-api/src/test/java/com/securedoc/platform/infrastructure/extraction/SolidDarkRectangleDetectorTest.java
8. MANUAL_REVIEW_PRECISION_FIX_CANDIDATE_README.txt
9. MANUAL_REVIEW_PRECISION_FIX_LOCAL_CHECKLIST.md

Schema / migrations
-------------------
None. Expected Liquibase count remains 54.

Static validation performed in this environment
-----------------------------------------------
- Java 21 `javac` compiled the provider-neutral extraction model, dark-rectangle detector,
  OCR guard policy, and minimal dependent production types.
- The dark-rectangle detector was run against the actual rendered page 15 and recovered
  exactly four compact solid black rectangles corresponding to the pre-masked SSN, DOB,
  card number, and credit score fields.
- The same detector found no qualifying blackout regions on category-only pages such as page 6.
- A standalone policy harness confirmed:
  * four blank strong labels aligned with verified source blackouts -> no review
  * blank strong label without aligned blackout -> review
  * visible SSN value plus unrelated dark region -> review remains
  * category-only Credit Score wording -> no review
  * exact visible Credit Score: 768 -> located finding
  * ambiguous Credit Score: 76B -> review

Limitations
-----------
Maven is not available in this execution environment. The user's local full backend/frontend
build remains authoritative. Do not send to Kiro until local build and runtime validation pass.
