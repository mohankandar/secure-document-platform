# Unit 24 — Kiro Verification Plan

**VERIFY ONLY. Make only minimal defect corrections. Do not redesign Unit 24. Do not start Unit 25.**

Use the approved Unit 23 baseline plus this Unit 24 source. Use Java 21, PostgreSQL, real Ollama installed model and real Tesseract.

Return an 18-item report:

1. Backend `mvn clean verify` result.
2. Frontend `npx tsc --noEmit` + `npm run build` result.
3. Application startup + Liquibase non-regression; Unit 23 `011-01/02/03` checksums unchanged and no unexpected migration.
4. ADMIN authorization: ADMIN gets `/platform-admin`; USER/REVIEWER/APPROVER are 403 on platform-admin endpoints.
5. Provider/runtime overview: DB, AI, OCR, storage, delivery entries are real deployment state, no invented providers.
6. Health probes: PostgreSQL + real Ollama + real Tesseract + local filesystem health behave correctly; unhealthy provider is represented as technical health, not Manual Review.
7. Secret safety: API/UI/audit do not expose API bearer token, credentials, DB password, sensitive document values, raw provider secrets or stack traces.
8. Resilience/failover: retry attempts/backoff and secondary AI provider enabled/configured state match deployment configuration; no heuristic substitution when Ollama is required.
9. Watched-folder status: enabled/root/trigger/visibility/disposition/scheduler state reflect properties safely.
10. Manual Scan Now: ADMIN scan reuses Unit 20 intake behavior, returns structured counts, updates last-scan state and emits safe audit.
11. Automatic watched-folder scheduler: disabled by default; when explicitly enabled with tenant+actor, scheduled scan runs for exactly that tenant; missing tenant/actor does not guess/fallback.
12. Watched-folder tenant isolation + fingerprint/idempotency + waiting/rejected/duplicate behavior remain correct; source disposition behavior preserved.
13. Technical Exceptions: FAILED packages only, current tenant only, safe metadata, separate from Manual Review; business review items do not appear merely because they need review.
14. Technical hold/lifecycle visibility: never-exported failed package shows bounded technical hold where present; Unit 22 retention/export cycle behavior unchanged.
15. Shared Dashboard/Reports/Business Admin non-regression: Unit 23 dashboard/reporting and Unit 21 Business Admin remain separate and functional.
16. Units 16–23 high-level regression: digital AI-first, OCR, confidence/retry, Manual Review auto-resume, Business Record/intake, Business Admin, delivery/retention/reprocess, Dashboard/reports.
17. Exact source files changed by Kiro + explanation of every correction; final backend/frontend build rerun; cleanup of temp files/output.
18. Recommendation: **UNIT 24 APPROVE** or **DO NOT APPROVE**. Stop. Do not start Unit 25.
