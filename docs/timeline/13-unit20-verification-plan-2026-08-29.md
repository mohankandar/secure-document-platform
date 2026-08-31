# Unit 20 Verification Plan — Business Record + Identifiers + Unified Intake

Kiro verification should be build/runtime focused. Treat the current workspace as authoritative, make only minimal corrections for real defects, and do not start Unit 21.

## Required build gates

1. `mvn clean verify` succeeds for all backend modules.
2. `npx tsc --noEmit` and `npm run build` succeed.
3. PostgreSQL local profile starts and Liquibase migration 008 applies cleanly.
4. Unit 16–19 high-confidence, OCR, exception/review behavior remains unregressed.

## Business identifier / Business Record acceptance

Verify the local demo tenant exposes:

- Contract Number — Required + Primary
- Member Number — Required, non-Primary

Then verify:

1. Missing Contract Number is rejected before intake creation with structured missing-required-identifier error.
2. Missing Member Number is also rejected even though it is not Primary.
3. Unknown identifier key is rejected.
4. `Contract Number=C1, Member Number=M1` creates Business Record A and Intake Batch 1.
5. A later intake with C1/M1 creates Intake Batch 2 linked to the same Business Record A.
6. C2/M2 creates Business Record B.
7. C1/M2 is rejected as `IDENTIFIER_CONFLICT`, not silently merged.
8. Package list/detail responses show configured business labels/values and do not require business users to interpret package UUIDs.
9. Audit records do not expose raw identifier values.

## Manual/API intake acceptance

1. Browser `Upload Documents` dynamically renders configured fields in display order.
2. Both required identifiers must be entered before Continue is enabled.
3. Manual intake creates `MANUAL_UPLOAD`, allows PDF upload/submission, and runs the existing AI-first pipeline.
4. API `API_SUBMISSION` with required identifiers creates a separate intake batch linked to the correct Business Record.
5. Direct normal-API `FOLDER_INTAKE` creation is rejected; watched-folder path is authoritative for that channel.

## Watched-folder acceptance

Use the real local watched-folder adapter with explicit ADMIN scan calls. Temporarily enable it in local configuration.

### A. No trigger

Create a child folder containing PDF(s) but no manifest.

Expected:
- no package/document ingestion
- observation is `WAITING_FOR_TRIGGER`
- it becomes visible only after the configured observation threshold

For verification, the threshold may be temporarily reduced; restore configuration afterward.

### B. Valid trigger

Add `manifest.json` last with both required business identifiers and a PDF.

Expected:
- manifest is the completion signal
- identifiers validate
- deterministic fingerprint is calculated
- exactly one FOLDER_INTAKE batch is created
- documents are uploaded and submitted
- intake observation becomes `ACCEPTED`
- existing AI/OCR/redaction processing proceeds normally

### C. Duplicate/idempotency

Scan the unchanged accepted source again.

Expected:
- no second batch is ingested
- duplicate/consumed behavior is visible without reprocessing

### D. Corrected content/metadata

Change manifest metadata or document content and trigger again using the same source folder.

Expected:
- fingerprint changes
- a new intake batch is accepted
- previous terminal observation history is preserved

### E. Structured rejections

Exercise at least:
- malformed manifest
- missing required identifier
- manifest references missing PDF
- unsupported non-PDF file

Expected:
- no false successful intake
- stable reason code is recorded
- outcome is reportable as `REJECTED`
- technical downstream processing failure is not confused with intake rejection

### F. Leave/source behavior

With default `LEAVE` behavior:
- source files remain in place
- persisted fingerprint prevents duplicate pickup

If move/copy behavior is exercised, a post-ingest permission/disposition problem must not erase the already-recorded accepted/rejected intake outcome or abort unrelated candidates.

## Observation-history acceptance

Reuse one source folder across separate intake episodes. Confirm a new no-trigger waiting episode after a prior terminal outcome does not overwrite the prior accepted/rejected observation.

## Security

- watched-folder scan/observations are ADMIN-only
- tenant-scoped Business Record/package/identifier queries cannot cross tenants
- identifier-definition read is available to an authenticated tenant user because the upload UI requires it

## Frontend scope check

Do not judge Unit 20 against the full Dashboard mockup. Unit 20 should only prove dynamic identifiers and hide internal package IDs in the transitional business flow. Full KPI/stage/search/configurable-column Dashboard remains Unit 23.

## Final report

Report:

1. backend build
2. frontend build
3. Liquibase 008 result
4. identifier configuration result
5. missing-required/unknown/conflict results
6. Business Record reuse result
7. multiple intake batches result
8. manual intake result
9. API intake result
10. no-trigger watched-folder result
11. triggered watched-folder result
12. duplicate fingerprint result
13. corrected-content/new-fingerprint result
14. structured rejection results
15. observation-history result
16. watched-folder role/tenant security result
17. audit identifier-value safety
18. Unit 16–19 regression result
19. exact source files changed, if any
20. explanation of each correction
21. final build results
22. remaining limitation
23. recommendation: UNIT 20 APPROVE or DO NOT APPROVE

Stop. Do not start Unit 21.
