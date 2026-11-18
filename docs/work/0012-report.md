# Report 0012: Phase 1b, second half (P2 to P5 and L5)

Date: 2026-11-17
Branches:
- LexaVault: `feature/phase1b-identity-library`, pushed, not merged;
- library: `feature/admission-0.3.0`, merged into `main` and released.

Outcome: DONE. No stop condition was hit:
- no surviving test was edited to pass after the switch;
- every Phase 1 check and browser step passed in all three proofs;
- no actor id differed for an unpadded value, and every stored actor id matched;
- none of the 11 original library classes changed.

**One deviation from the plan, required by the "no test edited" rule.** `OidcTenantMembershipResolver` and `TrustedActorResolver` keep their names and constructors as LexaVault's seam: their logic is now the library's. The existing `RequestContextResolverSecurityTest` builds `RequestContextResolver` from exactly those classes, and the application ports. Deleting them, as plan 0010 assumed, would have meant editing that test.

## Steps and test counts

| Step | Commit | Work | Tests |
|---|---|---|---|
| P2a | `4140d98` | `RequestContextResolverSharedIssuerTest`, 13 resolver tests with shared-issuer on, written against the old code; exact messages asserted: one entity; two entities with and without a choice; a choice not held; no entity; an inactive entity; forged choices; discovery; the platform-role refusal; actor-id stability; machine path with one, two and no bindings | backend 537 (API 335) |
| P2b | `a0fc427` | `RequestContextResolver` uses the library's `EntitySelection`, `SharedIssuerPreflight` and `MachineEntityAdmission`; `OidcTenantMembershipResolver` delegates to `EntityMembershipResolver`; refusals mapped to the same 403 and message; deletions below | backend 426 (API 224); the 13 P2a tests pass unedited |
| P3 | `3f7c4ee` | `TrustedActorResolver` now uses the library's `StableIdentityId`; `ActorIdLibraryParityTest` (4 tests) | backend 430 (API 228) |
| P4 | `abe1983` | `OidcAudienceValidator` removed; `JwtBoundaryTest` uses the library's `RequiredAudienceValidator` (the one edit the task instructs) | backend 430 |
| L5 | library `0c5c0a7` | Version 0.3.0, README entry, `SCHEMA_CONTRACT.md` | library: core 42, spring 15 |
| P5 | `8d7957e` | LexaVault pinned to 0.3.0 | backend 430; `test:standalone` 8 + 17 + 9 pass |

Module counts at P5: domain 31, application 116, scan bridge 47, infrastructure 8, API 228.

**No wiring class was added (decision 3).** Under the seam above, the library classes are created inside `OidcTenantMembershipResolver` and `RequestContextResolver`, as the old classes were. Nothing new needed to be a Spring bean, so a configuration class would have had nothing to configure.

## The three Phase 1 proofs

| Proof | Jar | 60 scripted checks | Browser script (4 users, carol switches A → B → A) |
|---|---|---|---|
| 1, after P2b | `a0fc427`, library 0.3.0-SNAPSHOT | **60 passed, 0 failed** | **4 passed** |
| 2, after P3 | `3f7c4ee` | **60 passed, 0 failed** | **4 passed** |
| 3, final | `8d7957e`, library **0.3.0** | **60 passed, 0 failed** | **4 passed** |

Each run started with migration 037 present only in the throwaway database (`uq_eic_active_binding` absent, `uq_eic_active_per_tenant` present).

## Actor-id proof

| Check | Result |
|---|---|
| The four Phase 1 users' real Keycloak subjects, through the library and an independent copy of the old algorithm | **4 of 4 identical** |
| `ActorIdLibraryParityTest`: 10 realistic issuer/subject pairs (Phase 1, Okta, Entra ID, Keycloak, opaque subjects) and the golden id `ee47d7f1-…-bb219cdbb812` | **all identical**, old and new |
| Stored audit records before P3 | 206 records, 3 distinct actors: alice 51, bob 51, system 104. All 102 user records match the library's ids, including the 80 written by the pre-0.3.0 code. carol and dave have no records, because they made no changes. |
| New audit records after P3 | 48 new records: alice 11, bob 11, system 26, under the **same ids**; 0 records with an unknown actor; still 3 distinct actors |
| The accepted difference | Recorded in a test: values padded with spaces are now trimmed, so they give the unpadded id |

**Differences found: none** for unpadded values. Only counts and ids were printed; no token or secret.

## Deleted

- **Main code:** `TrustedTenantAdmission`, `OidcTenantSelection`, `TenantGroupAuthorization`, `SharedIssuerActivationPreflight`, `OidcMachineTenantAdmission`, `OidcAudienceValidator`. Their logic now lives in the library.
- **Tests:** `TrustedTenantAdmissionTest`, `OidcTenantSelectionTest`, `TenantGroupAuthorizationTest`, `SharedIssuerActivationPreflightTest`, `OidcMachineTenantAdmissionTest` (moved to the library in 0011, case for case) and `IdentityLibraryParityTest` (retired in the same commit, decision 2).
- **Body replaced, class kept:** the hand-written UUID code in `TrustedActorResolver` is gone; the class keeps its name and refusal.
- **Kept:** `OidcTenantMembershipResolver` and its test. The class is now the adapter; the test passes unchanged.

## Release

- Library `main` was fast-forwarded `85135a7..0c5c0a7`, tagged **`v0.3.0`**, and `main` and the tag were pushed. `feature/admission-0.3.0` is pushed at the same commit.
- LexaVault `feature/phase1b-identity-library` is pushed with this report. It is not merged; the design chat reviews it first.
- The Phase 1 backend and UI are stopped; both containers are kept.

## To report: library refusal messages that use LexaVault's words

All are in `com.documentplatform.identity.admission`. The product passes them through unchanged today, which keeps LexaVault's 403 texts identical.

| Message | LexaVault-specific word |
|---|---|
| "Authenticated identity is not authorized for the requested tenant" | tenant |
| "A unique authorized tenant context is required" | tenant |
| "Invalid selected tenant identifier" | tenant |
| "Tenant has no trusted group admission policy" | tenant |
| "Invalid tenant role configuration" | tenant |
| "Authenticated identity has no tenant-authorized role" | tenant |
| "Platform Operations cannot be granted through tenant IdP groups" | **Platform Operations** (a LexaVault role), tenant |
| "Shared-issuer tenant selection is not enabled" | tenant, and the name of LexaVault's setting |
| "No verified ACTIVE tenant membership is available" | tenant |
| "Tenant membership is not backed by ACTIVE identity connections" | tenant |
| "Invalid tenant-scoped role grant" | tenant |
| "Machine identity has no unique ACTIVE tenant integration binding" | tenant, integration |
| "Verified OIDC tenant membership context is unavailable" | tenant |
| "OIDC tenant admission policy or ACTIVE tenant bindings are unavailable" | tenant |
| `NullPointerException` message "requestedTenant" | tenant |

The neutral messages are "Trusted IdP group collection is required" and "EntityId value must not be null". **The Phase 2 change:** the product maps each `Reason` to its own words, and the library's messages become neutral.

## To report: `RequestContextResolver`, now dead or more complicated than it needs to be

1. **The preflight call mostly re-checks what the membership resolver already did.**
   - It is passed `persistedConnections = true` straight after the same check.
   - `explicitlyEnabled` is always true inside that branch.
   - Its "candidates ⊇ memberships" check is always true, because both come from the same map.
   - Only the empty-roles and platform-role checks remain, and `EntityGroupAuthorization` already refuses both.
2. **Id round trip.** `authorizedTenants` converts the library's ids to `TenantId`, and the resolver converts them straight back to `EntityId` for preflight and selection.
3. **The library-interface bridges added to the three JDBC classes in P1 are unused after P2b**: `find(ConnectionKey)`, `activeGrants(ConnectionKey)` and `find(ConnectionKey, String)`. The adapter wraps LexaVault's ports with lambdas instead.
4. **`OidcTenantMembershipResolver.select(...)` and `selectOnlyIfUnambiguous(...)`** are used only by its own test.
5. **`AdmissionSettings` carries two flags that the library classes never read:** `sharedIssuerSelectionEnabled` and `persistentConnections`. `EntityMembershipResolver` uses only the provider id and the audience.
6. **Each request runs the membership lookups two or three times.** `resolvePrincipal` is called by the interceptor, the capability check and the controllers. That is the caching item in design Phase 4.

## For the owner to decide

1. **The two seam classes:** accept that `OidcTenantMembershipResolver` and `TrustedActorResolver` stay as LexaVault seam classes? The alternative is to edit `RequestContextResolverSecurityTest` to build the resolver differently, and then delete them.
2. **The unused pieces** in lists items 3 to 5 above: remove them in a small follow-up, or keep the JDBC bridges as the pattern SDV will follow?
3. **Merge:** merge `feature/phase1b-identity-library` after the design chat's review.
4. **`CLAUDE.md`** still says library 0.2.1 and 426 backend tests. It should become 0.3.0 and 430 once this branch is merged.
