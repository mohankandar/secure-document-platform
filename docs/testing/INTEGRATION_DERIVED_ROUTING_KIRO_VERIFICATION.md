# Requirement 4 — Derived Routing Kiro Verification

Verify only; do not implement Requirement 5. ChatGPT owns architecture/code. Kiro is the independent local reviewer/tester. Kiro may fix only obvious compile/test-compile/import/Maven/Spring-wiring blockers and must report any functional, security, authorization, tenant-isolation, lifecycle, routing, persistence-semantic, or delivery-contract defect without fixing it.

Required evidence:

1. full backend `mvn clean verify`; frontend TypeScript/auth tests/Vite build; Liquibase 018 apply; Spring startup;
2. routing-rule authorization: Business Admin mapping vs Tenant IT technical connection management; normal workflow send/resend vs technical retry;
3. deterministic marker detection limited to TOP_RIGHT/BOTTOM_LEFT/BOTH; BARCODE/QR/TEXT_PATTERN; no AI destination guessing; ambiguous markers → attention;
4. derive from permanently redacted master only; preserve page order; master remains intact;
5. multiple marker/destination groups produce independent derived PDFs; export-cycle scoping creates a fresh derived record after reprocess while retaining prior-cycle history;
6. master SDV `EXPORTED` remains successful even when derived delivery is pending/failing;
7. HTTP requires durable receipt and stable idempotency identity; FILE_SHARE stays under approved root and performs durable write;
8. failure/technical retry reuses existing derived artifact and does not rerun AI/OCR/redaction;
9. attempt history preserves destination connection/configuration version; secrets are not exposed;
10. Req1 Push API, Req2 polling, Req3 SDV master delivery, frozen AI/OCR/redaction and role model show no regression.

Return exactly `PASS`, `PASS WITH MINOR FINDINGS`, or `FAIL — CORRECTION REQUIRED`, plus commands/evidence, findings, files modified, and whether Requirement 4 is safe to accept and proceed to Requirement 5.
