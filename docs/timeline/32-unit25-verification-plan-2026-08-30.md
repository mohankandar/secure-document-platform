# Unit 25 — Kiro Verification Plan

Verify-only. Do not reimplement Unit 25. Do not start Final V1 acceptance.

1. Run backend `mvn clean verify` and frontend `npx tsc --noEmit` + `npm run build`.
2. Start LOCAL profile and prove Units 16–24 still work with explicit local identity headers; missing role defaults USER, not ADMIN.
3. Start OIDC mode against a controlled real/test OIDC issuer/JWKS and prove valid JWT succeeds.
4. Prove invalid signature, issuer, audience, expiry and not-before fail closed.
5. Prove missing/malformed tenant claim and unmapped/missing role claims fail closed.
6. In OIDC mode send spoofed `X-Tenant-Id`, `X-Actor-Id`, `X-Actor-Roles`; prove verified JWT claims remain authoritative.
7. Prove unprovisioned tenant fails before use case/database FK write with safe 403-style response.
8. Prove cross-tenant ID substitution/query returns no data and focused mutation guards reject same-ID cross-tenant ownership.
9. Verify BUSINESS_ADMIN and PLATFORM_ADMIN separation; ADMIN still passes both centrally.
10. Verify `/api/v1/me` returns only safe username/roles/tenant name+slug/auth mode and no subject/token/claims/secrets.
11. Verify old vault/access-request routes are absent in OIDC mode and available only in explicit LOCAL mode as historical compatibility.
12. Verify production CORS/security headers; old identity headers are not accepted by production CORS/trust logic.
13. Verify JWKS discovery/refresh is standard Spring Security behavior; issuer/JWKS outage never causes insecure fallback or skipped validation.
14. Verify frontend Authorization Code + PKCE login/callback, session-only token handling, sign-out local session clearing, and no local demo role selector in OIDC mode.
15. Verify API callback production policy requires HTTPS and bearer token; no token in logs/audit/UI.
16. Verify no secret leakage in 401/403/error responses/log/audit.
17. Run representative Units 16–24 regression: digital + OCR processing, review/technical separation, Business Admin, lifecycle/export, Dashboard/reports, Platform Admin.
18. Cleanup all verification artifacts and rerun final builds.

Final report must name every Kiro source correction, if any, and end with `UNIT 25 APPROVE` or `DO NOT APPROVE`. Stop; do not start Final V1 acceptance.
