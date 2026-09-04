> **Role-model supersession — 2026-09-01:** Production trust-boundary requirements remain frozen, but the historical workflow-role names/matrix in this document are superseded by `docs/continuity/24_Role_Simplification_Migration_2026-09-01.md`.

# Unit 25 — Production Identity / Platform Hardening — Decision Freeze and Scope

**Project codename:** LexaVault  
**Date:** 2026-08-30  
**Input baseline:** Units 16–24 approved; Unit 24 Kiro verification passed with zero source changes.  
**Purpose:** Freeze Unit 25 before implementation. This document reconciles the 2026-08-29 V1 master freeze, later repository steering/timeline decisions, current source behavior, and Kiro-verified Units 16–24.

## 1. Unit 25 objective

Unit 25 replaces the pre-production request-header trust boundary with a production-grade, provider-neutral enterprise identity boundary and hardens the existing modular-monolith/on-prem deployment without redesigning the product pipeline.

Unit 25 is **not** the final V1 acceptance phase. A separate **Final Security / Integration Tests + V1 Acceptance** phase follows Unit 25.

## 2. Frozen security modes

LexaVault V1 will have two explicit security modes selected by one property:

`securedoc.security.mode=OIDC|LOCAL`

- **LOCAL** — developer/demo mode only. The existing demo tenant/actor/role mechanism may remain available for local development. `application-local.yml` may explicitly select LOCAL.
- **OIDC** — production/on-prem/enterprise mode. Identity comes only from cryptographically verified OIDC/JWT authentication. **OIDC is the secure default when the property is absent outside the explicit local profile.**

Rules:

1. LOCAL mode must be explicitly enabled and must not activate accidentally in a production profile.
2. OIDC mode must never trust `X-Tenant-Id`, `X-Actor-Id`, or `X-Actor-Roles` as authentication/authorization truth.
3. Identity headers sent by a client in OIDC mode are ignored or rejected; they cannot override verified token identity.
4. `X-Correlation-Id` remains non-security request metadata and may continue to be accepted/validated/generated.
5. OIDC startup must fail closed if required identity configuration is absent, malformed, or the JWT decoder cannot initialize securely. There is no fallback from OIDC to LOCAL.

## 3. OIDC / JWT direction

- Use standard OIDC/OAuth2/JWT at the API/platform edge.
- Okta is a supported/validated enterprise IdP direction, but product/domain code must not depend on Okta-specific SDK/domain types.
- The backend must cryptographically validate JWT signature, issuer, expiration/not-before and configured audience.
- The browser flow should use provider-neutral OIDC Authorization Code + PKCE; do not use username/password authentication implemented by LexaVault.
- API/service callers may use trusted bearer JWTs (including client-credentials tokens where the enterprise IdP supports them).
- Do not persist access/refresh tokens in LexaVault business tables, audit records or logs.
- Prefer browser token handling that avoids durable localStorage token persistence.

### 3.1 JWKS and token lifecycle

Keep this simple and use Spring Security's standard resource-server/JWT machinery; do not build a custom JWKS service or token-revocation database.

- Configure issuer/audience and the IdP JWKS/discovery location through deployment configuration.
- Use Spring Security's normal JWKS caching/refresh behavior. Do not implement a second custom cache.
- If the JWT decoder/JWKS metadata cannot initialize securely at startup in OIDC mode, startup fails closed.
- At request time, JWKS refresh/network failure must never disable signature validation. A token that cannot be verified with a trusted cached/current key fails authentication; no unsigned/unverified fallback is allowed.
- Short-lived access tokens are accepted until normal expiry; V1 does **not** maintain a server-side token revocation/blacklist database.
- `exp` and `nbf` validation use Spring Security's bounded standard clock-skew handling; do not introduce a large custom grace period.
- Logout clears the application's browser authentication state. IdP logout may be invoked when configured, but LexaVault does not persist refresh/access tokens in business tables.

## 4. Trusted tenant resolution

Production tenant context must come from a trusted verified identity claim, not a request header.

Frozen V1 rule:

- A configurable claim (default logical name `tenant_id`) must resolve to exactly one provisioned LexaVault tenant for the current request.
- The tenant claim must be a valid tenant identifier accepted by the platform.
- Missing, malformed or unprovisioned tenant identity fails closed with a safe authentication/authorization response; it must not reach a tenant-owned repository as a guessed tenant.
- V1 does not allow a caller to switch tenants by supplying an arbitrary header/query value.
- Multi-tenant-user tenant switching is not a V1 UI feature; a token/request operates in one trusted tenant context.

## 5. Actor identity

OIDC `issuer + subject` is the external stable identity source.

Because current `ActorId` is UUID-based, Unit 25 should resolve a deterministic internal ActorId at the API/security edge from the verified issuer+subject rather than changing product-domain identity types or trusting a client-provided actor UUID.

- Actor mapping must be stable for the same verified issuer+subject.
- Human-readable username/display information may come from safe configured claims such as `preferred_username`/`name`, but authorization must never depend on display text.
- Audit continues to use the internal actor ID plus correlation/tenant context.
- Background/system actors (retention scheduler, watched-folder scheduler) remain explicit system/configured actor identities and do not impersonate OIDC users.

## 6. Production role model

Freeze these product roles:

- `USER`
- `REVIEWER`
- `APPROVER`
- `BUSINESS_ADMIN`
- `PLATFORM_ADMIN`
- `ADMIN` — explicit super-admin / break-glass-compatible product role; never assigned by default

Compatibility rule:

- `ADMIN` continues to satisfy every product role check, preserving approved behavior.
- `BUSINESS_ADMIN` and `PLATFORM_ADMIN` provide least-privilege separation going forward.

Primary authorization ownership:

| Capability | Production role |
|---|---|
| Shared Dashboard / normal authenticated operational read | Authenticated tenant member |
| Manual Review | REVIEWER (ADMIN also passes) |
| Final approval / reprocess actions already owned by approval workflow | APPROVER (ADMIN also passes) |
| Business Admin configuration | BUSINESS_ADMIN (ADMIN also passes) |
| IT / Platform Admin | PLATFORM_ADMIN (ADMIN also passes) |
| Audit administration | PLATFORM_ADMIN (ADMIN also passes) |
| Watched-folder technical administration | PLATFORM_ADMIN (ADMIN also passes) |
| Per-package technical-hold extension | BUSINESS_ADMIN (ADMIN also passes), because retention/hold policy is a Business Admin-owned business rule |
| Purge-due operational action | PLATFORM_ADMIN (ADMIN also passes) |

A user may possess multiple roles. `BUSINESS_ADMIN` or `PLATFORM_ADMIN` alone does not automatically grant REVIEWER or APPROVER privileges.

## 7. External role/claim mapping

- OIDC claim names and external group/role values must be deployment-configurable.
- Map only explicitly configured external values to LexaVault product roles.
- Do not accept arbitrary token strings as internal roles.
- Unknown external groups are ignored unless explicitly mapped.
- A production token must resolve an allowed tenant and at least one recognized application access role/group; otherwise fail closed.
- `ADMIN` must require an explicit mapping and must never be inferred from missing claims.

## 8. Spring Security / IDP boundary

Evaluate existing IDP framework edge capabilities first, but reuse only when they fit the frozen boundary cleanly.

IDP/framework reuse is allowed only in edge/platform modules such as:

- `securedoc-api` — authentication/security/web/logging
- `securedoc-infrastructure` — supporting integration/security plumbing when needed
- `securedoc-worker` — observability/scheduling only when relevant

Do **not** move IDP concerns into:

- domain
- application product rules
- AI/OCR/redaction
- review workflow
- policy engine
- BusinessRecord/IntakeBatch semantics
- watched-folder product semantics
- delivery/retention truth

If IDP reuse would force those leaks, prefer standard Spring Security edge implementation instead.

## 9. API security baseline

### 9.1 Simple central tenant boundary

Unit 25 will add **one lightweight central tenant guard**, not a new tenant framework.

- After JWT verification and claim mapping, a `TenantBoundaryGuard` (exact class name may vary) validates that the trusted tenant claim resolves to a provisioned/allowed tenant **before tenant-owned application work begins**.
- The validated tenant is then carried in the existing `AuthenticatedPrincipal`/request context.
- Existing repository methods remain tenant-explicit (`...AndTenantId`, tenant predicate, or equivalent). Unit 25 must audit touched security-sensitive paths and remove unsafe unscoped tenant-owned lookups such as bare `findById(...)` where they could bypass the trusted tenant boundary.
- Focused two-tenant security tests must prove cross-tenant ID substitution cannot return or mutate another tenant's data.
- **Explicit non-goal:** PostgreSQL RLS, Hibernate global tenant filters, repository proxies, or a new tenant framework are not required in Unit 25. They may be later defense-in-depth work.

This closes the silent per-query-convention gap without redesigning the persistence architecture.

### 9.2 API rules

In OIDC mode:

- `/api/v1/**` requires authenticated identity unless an endpoint is explicitly documented public infrastructure health.
- Existing `@RequireRole` product authorization remains authoritative for privileged actions, updated for the new granular admin roles. Perform a focused re-annotation pass: Business Admin endpoints use `BUSINESS_ADMIN`; Platform Admin/audit/watched-folder technical endpoints use `PLATFORM_ADMIN`. `ADMIN` super-admin behavior remains centralized in `RoleAuthorizationInterceptor` (or its equivalent), rather than listing `ADMIN` on every annotation.
- Tenant context is resolved once from verified identity and propagated through the existing `AuthenticatedPrincipal`/application boundary.
- Cross-tenant ID substitution must continue to return no data / access denied.
- 401 = missing/invalid authentication.
- 403 = authenticated but unauthorized, invalid tenant membership, or unprovisioned tenant as appropriate without leaking tenant existence details.
- No stack traces, token values, provider responses or security internals in error payloads.

## 10. Frontend production identity

- The existing `Local / Demo` role selector remains LOCAL-mode-only.
- In OIDC mode, the UI must not send tenant/actor/role identity headers.
- Production navigation is derived from the authenticated product roles.
- A direct URL must still be protected by backend authorization; frontend hiding is never the security boundary.
- Add one explicit authenticated current-user surface, for example `GET /api/v1/me`, returning only safe identity/session metadata (username/display name, product roles, tenant display context, auth mode). It must never return access/refresh tokens, raw claims dumps, client secrets or signing material.
- Normal business UI still hides internal UUIDs.

## 11. Secrets and provider credential hardening

Unit 25 does **not** build a secrets-vault product or credentials-management UI.

V1 rule:

- Provider/database/delivery/OIDC secrets remain deployment-managed (environment, orchestrator secret injection, protected deployment configuration, or equivalent on-prem mechanism).
- Secrets are never stored in tenant business tables.
- Secrets are never returned by Platform Admin APIs.
- Secrets are never logged/audited.
- Selected providers should fail startup or report safe NOT_CONFIGURED/UNHEALTHY states when required credentials are missing; never silently downgrade security.
- Production configuration must not ship usable default credentials. LOCAL-only developer credentials may remain in `application-local.yml` for convenience, but non-local/OIDC configuration must require deployment-provided secrets with no usable production default.

## 12. External API/callback authentication decision

Close the previously open V1 callback-authentication decision as follows:

- Existing package-atomic API/callback delivery remains the V1 contract.
- V1 production callback authentication uses a deployment-managed **static bearer token** when API delivery is selected.
- In non-local mode, API callback delivery must not silently run unauthenticated; required endpoint/auth configuration must fail closed.
- HTTPS is required for non-local callback endpoints except explicitly allowed loopback/local verification scenarios.
- Bearer token is never returned to the browser, logs, audit or delivery records.
- Idempotency-Key and existing package/export-cycle semantics remain unchanged.
- OAuth2 client-credentials callback authentication can be a later enhancement unless a customer contract explicitly requires it before V1 release.

Incoming API callers use the same trusted OIDC/JWT API security boundary rather than a new custom API-key system in Unit 25.

## 13. Legacy original/vault surface hardening

The frozen V1 product does not provide long-term original-document retrieval.

Current source still contains historical vault/access-request API controllers and unused frontend client methods even though the active document-download flow exposes only redacted output.

Unit 25 must:

- remove/disable the legacy `/api/v1/vault/entries` and `/api/v1/artifact-access-requests` production-facing routes;
- remove unused frontend calls/navigation that could revive that workflow;
- preserve any internal historical classes only when deletion would create unrelated refactoring risk;
- verify no supported UI/API path downloads ORIGINAL artifacts.

## 14. Security headers / CORS / transport hardening

Unit 25 should add production-safe edge defaults appropriate to the chosen OIDC flow:

- explicit CORS allowlist (no wildcard credentials policy);
- `X-Content-Type-Options: nosniff`;
- frame protection / CSP `frame-ancestors` policy;
- sensible Referrer-Policy;
- HSTS when running behind confirmed HTTPS production deployment;
- no token/credential data in URLs;
- no permissive debug security behavior in non-local mode.

Do not introduce a broad gateway/platform rewrite.

## 15. On-prem operational hardening

Preserve the documented on-prem-first architecture:

- PostgreSQL remains metadata/operational truth.
- Local filesystem/object storage remains supported.
- Ollama/local AI and Tesseract remain valid provider choices.
- Enterprise OIDC must be configurable; Okta must not become a mandatory cloud dependency.
- Kafka/SQS/microservices are not introduced.
- Existing in-process lifecycle/scheduling behavior is accepted for V1 unless verification exposes a correctness/security defect; durable distributed scheduling is not Unit 25 scope.

## 16. Audit and correlation

- Audited request actions must record the trusted internal actor ID derived from authenticated identity, not a caller-provided actor header.
- Tenant ID in audit comes from trusted tenant context.
- Correlation IDs continue across request/application/audit/worker flows where already supported.
- Security/authentication failures use safe categories and must never persist JWTs, claims dumps, secrets or raw exception bodies.
- System/scheduled actions retain explicit system actor identities.

## 17. Tenant/error hardening

Close the known pre-Unit-25 behavior where an unprovisioned tenant can surface a persistence/FK-style HTTP 500.

Expected production behavior:

- malformed/missing trusted tenant identity -> safe 401/403;
- valid token referencing an unprovisioned tenant -> safe fail-closed response (prefer 403 to avoid enumeration);
- no database constraint text/stack trace;
- no cross-tenant repository operation attempted after tenant validation fails.

## 18. Unit 25 verification requirements

Kiro verification after implementation must include, at minimum:

1. Full backend/frontend builds.
2. LOCAL mode regression proving the approved demo path still works only when explicitly enabled.
3. Security-mode selection: absent/non-local mode resolves securely to OIDC/fail-closed; OIDC never silently falls back to LOCAL.
4. OIDC/JWT verification with a real signed test token/JWKS or real configured enterprise IdP test environment.
5. Invalid signature, expired token, wrong issuer, wrong audience -> 401.
6. JWKS unavailable/unrefreshable never bypasses signature validation; no insecure fallback.
7. Missing/malformed tenant claim -> fail closed.
8. Unprovisioned tenant -> safe 403/no persistence constraint leak.
9. Client-supplied `X-Tenant-Id`/`X-Actor-Id`/`X-Actor-Roles` cannot override verified identity.
10. Central tenant guard runs before tenant-owned work; two-tenant ID substitution cannot read or mutate cross-tenant data.
11. Role mapping and new Business Admin vs Platform Admin separation.
12. `ADMIN` remains explicit super-admin through centralized interceptor behavior.
13. Direct URL/backend authorization remains authoritative.
14. `GET /api/v1/me` (or equivalent) is authenticated and returns safe metadata only; no token/secret/raw-claim leakage.
15. Audit actor/tenant derive from trusted identity.
16. Platform Admin secrets remain hidden.
17. Legacy original/vault endpoints no longer exposed and frontend has no active dependency on them.
18. CORS/security-header changes do not break the SPA/API flow.
19. Callback delivery requires safe production authentication and preserves idempotency.
20. Units 16–24 high-level regression on real PostgreSQL + Ollama + Tesseract.
21. No final-acceptance/next-unit work started.

### 18.1 Focused automated security-boundary tests — required

Unit 25 is the one construction unit where a **small executable security regression suite is mandatory**. This is a deliberate carve-out from the project's earlier test deferral; it is not permission for broad test generation.

Keep the suite narrow and focused on the new trust boundary:

- valid signed JWT accepted;
- invalid signature rejected;
- expired/not-yet-valid token rejected;
- wrong issuer/audience rejected;
- tenant claim mapping: valid / missing / malformed / unprovisioned;
- explicit role mapping, including BUSINESS_ADMIN / PLATFORM_ADMIN / ADMIN behavior;
- client identity headers cannot override verified JWT identity in OIDC mode;
- central tenant guard blocks cross-tenant access;
- LOCAL mode is available only when explicitly selected.

Use generated test keys/mock JWKS/security fixtures where appropriate. Do not build a large generic controller test suite.

## 19. Explicit Unit 25 non-goals

Do not add:

- Kafka/SQS or mandatory broker
- microservice decomposition
- RAG/vector search
- advanced analytics
- dynamic provider hot swapping
- a browser credentials vault
- customer model training
- autonomous legal interpretation
- distributed workflow engine
- large generic test-generation effort **beyond the focused Unit 25 security-boundary tests required by §18.1**
- broad rewrite of Units 16–24

## 20. What remains AFTER Unit 25

Unit 25 approval does not equal V1 release approval.

The separate **Final Security / Integration Tests + V1 Acceptance** phase must still prove:

- digital PDF AI-first redaction end to end;
- scanned/OCR AI-first redaction end to end;
- representative mixed-page handling;
- AI-only discovery where deterministic logic misses;
- evidence agreement/disagreement behavior;
- permanent unrecoverable redaction verification;
- low-confidence focused review and auto-resume;
- technical exception/retry/failover behavior;
- manual/API/watched-folder intake and fingerprint/idempotency;
- local/API and, if claimed production-ready, S3 delivery runtime acceptance;
- retention/reprocess/purge lifecycle;
- shared Dashboard/reports;
- production identity/tenant/role security;
- safe audit/logging/secrets behavior;
- release packaging, deployment docs and known limitations.

## 21. Documentation updates after Unit 25 / final acceptance

The 2026-08-29 master packet contains historical status/open-decision text that is now stale. Before V1 release, update it to reflect the accepted implementations for Units 16–25, including OCR provider, coordinate/location approach, redaction verification, package-atomic delivery, evidence/confidence behavior, production identity roles, callback authentication and final acceptance model/provider evidence.

---

**Freeze recommendation:** APPROVE this Unit 25 scope before implementation. Once frozen, implement Unit 25 from the Kiro-approved Unit 24 source only, then perform verify-only Kiro validation before starting Final Security / Integration + V1 Acceptance.
