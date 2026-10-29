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

## B2 additive persistence foundation — 2026-10-28
- Added Liquibase `036-r1b-tenant-oidc-group-grants.xml` and master include; it adds `tenant_oidc_group_grants` without modifying enterprise identity activation tables/indexes or earlier migrations.
- Added `JdbcTenantOidcGroupGrantRepository.activeGrants(providerId, issuer, audience)`: grant discovery is filtered by ACTIVE identity connection and ACTIVE tenant; rejects unexpected and platform-level role names. This is a read-only adapter, not a customer-facing membership administration API.
- **Important limitation:** the existing unique ACTIVE identity binding `(provider_id, issuer_uri, api_audience)` means at most one tenant is selectable per shared issuer/audience today. A follow-up migration/identity activation design is essential to allow multiple ACTIVE tenants on the same identity provider safely. This repository alone does not deliver multi-tenant SaaS membership.
- **Build, Liquibase migration, and runtime integration tests are NOT executed.** The next gate is automated migration validation, source-controlled fixture setup, and integration with the resolver only after ambiguity handling is implemented and independently exercised.
- A previously retired migration numbered 035 is not reused; migration 036 is new and additive.

## B3 read-only OIDC admission pipeline (2026-10-28)
- Added application port `TenantOidcGroupGrantRepository`, implemented by the existing JDBC ACTIVE-grants adapter. Dependency direction is API -> application port <- infrastructure.
- Added `OidcTenantMembershipResolver` to map a *verified JWT plus normalized IdP group claims* to a map of tenant-authorized product roles. A user with only Tenant A's group cannot select B. A user with both must explicitly select one. No direct trust of browser tenant headers.
- New unit tests cover shared-issuer tenant isolation, role separation, ambiguous membership, missing group access, and denied selection.
- This is **not yet wired into the live OIDC request principal or frontend**. The legacy single-tenant connection continues unchanged.
- Existing unique ACTIVE identity binding per (provider, issuer, audience) still prevents multiple shared-issuer active tenants. Do not drop it until the new request tenant-selection contract and activation/rollback paths are ready and tested together.
- Test files are committed but **no Maven build, Liquibase migration execution or runtime OIDC acceptance has been performed** in this environment. No business validation requested.

## B4 compatibility gate — shared issuer activation (2026-10-28)
Existing migration 032 imposes a unique ACTIVE binding on `(provider_id, issuer_uri, api_audience)`; migration 034 separately enforces one ACTIVE connection per tenant. Existing `findActive()` expects at most one matching connection and returns its tenant. Activation/rollback code assumes that invariant. **Do not drop the index independently**: doing so could make existing OIDC authentication ambiguous before membership selection is enforced.
The new `JdbcTenantOidcGroupGrantRepository` explicitly fails closed on multiple matching ACTIVE connection IDs, rather than silently aggregating unrelated tenants. Thus B3 provides read-only admission primitives and tests, but **shared-issuer multi-tenant admission is not yet operational**.
Recommended next cohesive change:
1. Preserve old resolver for single-tenant installations.
2. Version the shared-issuer identity binding policy, scoped by tenant and matched by a verified issuer/audience and IdP group admission rule.
3. Introduce a full server-verified tenant-selection contract, including explicit choice when >1 tenant is available; no arbitrary header grants authorization.
4. Make draft test, activation, rollback and all cache invalidation paths aware of the new policy; alter uniqueness only in the same migration/release gate that implements fail-closed selection.
5. Regression-test same issuer for two tenants, same human in both, wrong-tenant denial, identity activation/rollback, delayed cache refresh, and OIDC machine intake.
6. Keep legacy production authentication paths unchanged until full compilation and integration tests pass.
No build, database migration execution, or authenticated runtime validation was performed in this checkpoint.

## B5 — distinct ACTIVE candidate discovery contract
- Added `ActiveOidcTenantCandidates` application port and `JdbcActiveOidcTenantCandidates` infrastructure adapter. The adapter looks up all ACTIVE tenant connections matching verified provider/issuer/audience and filters inactive tenants.
- **Security invariant:** This yields candidates, *not authorized memberships*. Never use its output directly to select a tenant or assign roles. Combine with cryptographically verified IdP group membership, explicit tenant choice if necessary, and tenant-scoped role grants.
- The existing `OidcTenantResolver`, production principal and integration-identity path remain unchanged; this is a preparatory additive commit.
- Migration that relaxes unique ACTIVE provider/issuer/audience remains blocked until the replacement principal resolution and Enterprise Identity activation/rollback tests are complete.
- The previously reported `Build-LexaVault-All.ps1` PASS at 2026-10-28 21:20 EDT applies to the earlier branch HEAD, not these new B5 commits. The current HEAD has no new build evidence.

## B6 — ACTIVE-candidate intersection enforced (2026-10-28)
- The read-only OIDC membership resolver now requires BOTH a current ACTIVE tenant candidate and a tenant-scoped external group policy match. A grant for an inactive/absent candidate alone is never sufficient.
- Added negative regression coverage: matching Tenant B group grants do not admit Tenant B when only Tenant A is an ACTIVE candidate.
- The live `RequestContextResolver` is intentionally unchanged: this is **not production-enabled**, and the old issuer uniqueness invariant remains.
- The last externally reported full-build PASS predates this change. Compile, migration, tests and OIDC runtime acceptance for B6 are **not yet verified**. No customer-facing validation required.

## B7 staged shared-issuer migration (not activated)
- Added `037-r1b-shared-issuer-active-bindings.xml` as a **staged, unregistered** migration. It drops only the cross-tenant unique ACTIVE issuer binding, while retaining the migration 034 one-ACTIVE-per-tenant constraint. Rollback refuses when conflicting shared bindings still exist.
- Updated the grant repository to allow grant lookup across multiple ACTIVE connections; admission still requires intersecting ACTIVE candidate discovery and tenant-specific trusted IdP group policy.
- **DO NOT register migration 037 or enable shared issuer in a running deployment yet.** Old `OidcTenantResolver.findActive` must remain operational, and currently throws on ambiguous bindings. Its replacement, explicit selection mechanism, integration identity separation, identity activation/rollback validation, and tested rollout gate are not complete.
- No migration or runtime test executed. The last successful build predates this code. The branch must not be considered releasable.

## B8 gated principal integration
The shared-issuer-tenant-selection-enabled option defaults false. When enabled, human OIDC principal resolution uses tenant ACTIVE candidates intersected with verified group grants. An optional X-Selected-Tenant-Id request hint selects only an authorized tenant; ambiguous membership without a selection fails closed. Existing integration principals remain unchanged. Migration 037 is not registered; do not enable this mode in deployments until shared-issuer activation, tenant selector and release tests are completed. This commit is not yet built or runtime validated.
