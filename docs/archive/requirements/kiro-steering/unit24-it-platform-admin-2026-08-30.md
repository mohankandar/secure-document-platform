# Unit 24 — IT / Platform Admin — Frozen Verification Scope

Unit 23 is approved. Unit 24 adds the technical administration surface only. Do not start Unit 25.

## Required Unit 24 outcomes

- ADMIN-only IT / Platform Admin page and backend endpoints.
- Safe provider/runtime visibility for PostgreSQL, AI, OCR, internal storage and final delivery.
- Provider credentials/tokens/secrets are never returned by the API or rendered in the browser.
- Deployment-managed provider selection remains the source of truth for V1; Unit 24 must not hot-swap credentials or rebuild runtime adapters unsafely.
- Retry/backoff and optional AI secondary-provider/failover configuration are visible to IT Admin.
- Watched-folder behavior is operationally visible: enabled state, trigger, observation threshold, disposition, scheduling, last scan and manual Scan Now.
- Automatic watched-folder scheduling is disabled by default and must use an explicit configured tenant/service actor; it must never guess a tenant.
- Manual and scheduled watched-folder scans reuse the approved Unit 20 intake service/fingerprint/disposition semantics.
- Unresolved FAILED packages appear in a tenant-scoped Technical Exceptions view, separate from Unit 19 Manual Review.
- Technical Exceptions expose safe operational metadata only; no raw document content, detected sensitive values, stack traces, credentials or provider tokens.
- Explicit platform health checks and watched-folder scans are auditable with safe metadata.
- One shared business Dashboard remains unchanged.
- Business Admin remains separate from IT / Platform Admin.
- Production OIDC/Okta/SSO/IDP implementation is Unit 25 and is not part of Unit 24.
- Preserve Units 16–23 behavior and tenant isolation.

## V1 security boundary

It is acceptable for Unit 24 to display safe provider type/model/configured state and local operational paths to ADMIN users. Secret values must never be returned. Production secret-management and real identity hardening are Unit 25 concerns.
