# LexaVault R1B — Trusted Tenant Identity: implementation contract (draft)

Status: SOURCE-REVIEWED DESIGN / NOT IMPLEMENTED / NOT TESTED
Branch: feature/lexavault-r1b-tenant-identity
Baseline: 9c66ea74340587458d4156dba3d7b55aec14c020

## Goals and invariant boundaries
- Same backend supports SaaS and on-prem; deploy-mode policy, not separate source forks.
- An authenticated user's selected tenant must be independently authorized by a server-side trusted relationship.
- No X-Tenant-Id, actor, roles, arbitrary tenant JWT claim, or browser-selected tenant becomes authority in OIDC mode.
- Role and group grants must be scoped to the resolved tenant, never copied from another tenant.
- Machine/integration identity remains separate from human user session/tenant selection.
- Existing single-tenant enterprise ACTIVE connection, 15-minute validation, versioned activation and known-good rollback must remain compatible.
- Do not introduce a LexaVault Users & Access administrative directory (V1 explicitly removed). IdP controls user and group lifecycle.
- Document processor, not general document repository. No generic document viewing/download feature.

## Verified source findings
1. OidcTenantResolver in persistent mode calls findActive(providerId, jwt.issuer, configured audience), receiving one tenantId; cached for 30 seconds. No per-user membership filtering.
2. Enterprise identity migration 032 enforces unique ACTIVE (provider_id, issuer_uri, api_audience). Two tenants sharing an issuer/audience/provider cannot both activate current bindings.
3. RequestContextResolver maps normalized external groups using a global OidcRoleMapper after tenant lookup; roles are not tenant-scoped.
4. TenantBoundaryGuard -> TenantContextService verifies principal tenant exists and is ACTIVE, not that the subject belongs to the tenant.
5. GET /api/v1/me returns current tenant name/slug but no opaque membership collection.
6. application-local.yml is explicitly LOCAL/demo: identity headers and disabled licensing. application-oidc-local.yml explicitly overrides security to OIDC and can be used for authenticated acceptance.
7. A4 dependency: package recovery discovers global statuses and subsequently uses the candidate's own tenantId; this is an internal system scheduler, not a human membership decision. Full scheduler/delivery review is still pending.

## Compatibility-safe design direction (not yet code-approved as API contract)
- Add an explicit tenant-scoped trusted user admission policy rather than interpreting a token's untrusted requested-tenant field as membership.
- Per IdP connection, define tenant-scoped group-to-product-role mappings or tenant-qualified trusted group binding, validated only against signed OIDC identity.
- Support unambiguous single-tenant binding as current legacy mode, and introduce a separately gated shared-issuer SaaS selection path; never broaden the legacy resolver silently.
- Resolve candidate tenants from persisted ACTIVE connections plus tenant-specific admission rules. If ambiguous and no securely authorized tenant selection, fail closed; never pick first tenant.
- A user may select only among tenants authorized by trusted server-side membership; the selected tenant is request context, not proof of access.
- POST/GET tenant context and /me contracts need design. Tenant switching must invalidate tenant-specific client caches and draft state.
- Preserve the existing per-tenant ACTIVE connection constraints and migration/rollback semantics while designing shared-issuer support; do not simply remove the unique index.
- Platform Operations cross-tenant read APIs remain explicit and narrow, not an implicit membership bypass.
- Integrations should authenticate and bind tenant independently to an enabled integration connection; never inherit browser selection.
- Ensure scheduled jobs, storage pins, lifecycle purge, delivery and audit use persisted resource tenant, never request globals.

## Engineering order and acceptance gates
B0. Complete A4 background, delivery, purge and integration trust path inventory; document all boundary exceptions.
B1. Add authoritative tenant admission contract and read-only resolver tests (same issuer 2 tenants; same user 1 or 2 memberships; unrelated user denied; inactive tenant denied).
B2. Extend persisted identity binding without breaking activation/rollback, including migration and negative tests.
B3. Integrate tenant-scoped external groups/roles into authenticated principal; prohibit cross-tenant role inheritance.
B4. Add backend tenant context APIs, frontend session switch/reset and actual OIDC acceptance.
B5. Add CI guards for missing capability annotations on sensitive endpoints; negative path review for exports/preview/integration.
B6. Run multi-node OIDC tests for Tenant A/B and then P2, with full build and runtime evidence.

## Backlog carry-forward
- Desk 20-item count/pagination root cause OPEN, plus adapter offset/limit truncation for non-multiple offset.
- No user document downloads; page-scoped restricted review only. Source route denial does not constitute full runtime acceptance.
- Users & Access feature remains CANCELLED/SUPERSEDED for V1.
- Role retirement and administrative segregation need OIDC regression, not LOCAL-only assurance.
- Multi-node watched-folder scheduling and lease fencing unchanged.
- License lifecycle: top-of-screen notices at 60/30 days; 30-day automatic grace after expiry; safe post-grace restricted operation; vendor-signed negotiated extension; 1–2 year renewable on-prem entitlement; offline verification.
- Retention cleanup, storage outage, in-flight recovery, deployment-hardening tests remain open until evidenced.

Do not claim build/test PASS for this document-only checkpoint. No product code changes in this commit.

## R1B B1 security primitives (committed 2026-10-28)
- TrustedTenantAdmission: denies a requested tenant not in a server-verified membership set and rejects ambiguous implicit selection.
- TenantGroupAuthorization: computes roles from only the *selected tenant's* trusted IdP group mapping; no cross-tenant role inheritance; rejects missing policies/claim collections.
- TenantGroupAuthorization rejects granting PLATFORM_OPERATIONS_ADMIN from tenant-specific group mappings. Platform Operations requires a distinct platform trust boundary.
- Unit tests were committed for cross-tenant denial, ambiguity, missing group membership, and forbidden platform-role assignment.
- **Not integrated into RequestContextResolver/OidcTenantResolver yet. No database migration or frontend tenant switch. Not runtime-accepted.** This isolation is intentional until a trustworthy membership source is implemented.
- **No build executed by assistant.** First useful development gate: `cd backend && mvn -pl securedoc-api -am -Dtest=TrustedTenantAdmissionTest,TenantGroupAuthorizationTest -Dsurefire.failIfNoSpecifiedTests=false test` (adjust multi-module artifact coordinates if build differs).
- Do not publish production SaaS claims until tested secure OIDC identity admission and cross-tenant integration path.
