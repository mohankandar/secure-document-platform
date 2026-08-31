# Unit 25 — Production Identity / Platform Hardening — Implementation

Status: IMPLEMENTED IN SOURCE; awaiting Kiro verification.
Date: 2026-08-30

## Scope implemented

- Explicit `securedoc.security.mode=LOCAL|OIDC`; secure default is OIDC, local profile explicitly selects LOCAL.
- Production API security uses Spring Security OAuth2 Resource Server JWT validation.
- OIDC validates issuer, signature, time claims, and configured audience.
- Production identity ignores legacy `X-Tenant-Id`, `X-Actor-Id`, and `X-Actor-Roles` as trust inputs.
- Tenant comes from configurable verified JWT claim (`tenant_id` default).
- Actor ID is a stable derived UUID from verified issuer + subject; username uses configurable `preferred_username` with subject fallback.
- External role/group claims map explicitly to product roles; missing/unmapped roles fail closed.
- Product roles: USER, REVIEWER, APPROVER, BUSINESS_ADMIN, PLATFORM_ADMIN, ADMIN. ADMIN remains centralized super-admin.
- Business Admin endpoints require BUSINESS_ADMIN; Platform Admin/audit/watched-folder technical operations require PLATFORM_ADMIN.
- Central `TenantBoundaryGuard` validates a provisioned tenant before `/api/v1/**` use cases; no RLS/framework rewrite.
- Focused persistence hardening prevents caller/domain-supplied IDs from merging records owned by another tenant for identified mutation paths.
- New safe `GET /api/v1/me` exposes only username, roles, safe tenant name/slug, and auth mode.
- Local/demo identity remains explicit; missing local role defaults to USER rather than ADMIN.
- Production CORS/security headers are explicit; old identity headers are allowed only in LOCAL mode.
- Browser OIDC Authorization Code + PKCE flow stores access token in sessionStorage only; no durable token database/revocation subsystem.
- API callback delivery can require bearer authentication and HTTPS; production defaults require both.
- Legacy vault/access-request REST controllers are enabled only in LOCAL mode and absent from production OIDC surface.
- No Unit 25 database migration; Units 16–24 pipeline and schema remain untouched.
- Focused security tests cover JWT validation, claim mapping/header spoof prevention, unprovisioned tenant fail-closed behavior, and cross-tenant mutation ID protection.

## Deliberate non-goals

No PostgreSQL RLS, custom tenant framework, custom JWKS cache, token blacklist/revocation database, secrets-vault UI, dynamic provider hot-swap, microservices/Kafka redesign, or Final V1 acceptance work.
