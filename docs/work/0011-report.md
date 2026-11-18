# Report 0011: Phase 1b, first half (L0 to L4 and P1)

Date: 2026-11-17
Branches:
- library: `feature/admission-0.3.0`, from `main` `85135a7`, pushed;
- LexaVault: `feature/phase1b-identity-library`, from `feature/lexavault-r1b-tenant-identity` `c32adb7`, pushed.

Outcome: DONE. Nothing hit a stop condition:
- none of the 11 existing library classes changed (`git diff 85135a7..HEAD` on their files: 0 files);
- no existing test was changed;
- nothing in a frozen area changed beyond the plan.

Parity: **98 cases, 0 differences.**

Also done: report commit `c32adb7` pushed.

## Steps and test counts

| Step | Repo, commit | Work | Tests after the step |
|---|---|---|---|
| L0 | library `af8cb70` | Tests pinning today's behaviour. Behaviour considered wrong is in `knownFinding_*` tests: string groups split on spaces and commas; more than 200 groups or a value over 200 characters throws; no `sub` throws; the trailing-slash difference. Golden actor id `ee47d7f1-9c8a-56ef-a2c1-bb219cdbb812`. `NoProductNamesGuardTest`: the build fails on any product package name (proved by planting one; it failed, then was removed). `junit-jupiter` 5.10.3 added to core (test scope). | core 17, spring 15 |
| L1 | library `72b7f60` | Package `admission` in core, no Spring: `EntityId`, `ConnectionKey` (values passed through unchanged), `AdmissionSettings`, `IdentityAdmissionException` + `Reason`, and the interfaces `ActiveEntityCandidates`, `EntityGroupGrants`, `MachineEntityBindings`, `EntityRoleCatalog`, each documenting what the product must guarantee. Version `0.3.0-SNAPSHOT`. | core 21, spring 15 |
| L2 | library `a5a0793` | `TrustedEntityAdmission`, `EntitySelection` | core 26, spring 15 |
| L3 | library `33cc44d` | `EntityGroupAuthorization`, `SharedIssuerPreflight`, `MachineEntityAdmission`; role checks through `EntityRoleCatalog` | core 34, spring 15 |
| L4 | library `09d0d51` | `EntityMembershipResolver`; `0.3.0-SNAPSHOT` installed locally | **core 42, spring 15** |
| P1 | LexaVault `70abed6` | `0.3.0-SNAPSHOT`; the 3 JDBC classes also implement the library interfaces by calling their existing methods (SQL, blank handling and the role check unchanged); `ProductEntityRoleCatalog`; `IdentityAdmissionMapping` (every refusal becomes today's `SecurityContextException` with the same message). Nothing switched over. Parity test added. | **backend 524**: domain 31, application 116, scan bridge 47, infrastructure 8, API 322 (224 unchanged + 98 parity) |
| Docs | LexaVault (commit after `70abed6`) | `docs/design/BROWSER_ENTITY_CONTRACT.md`; targeted edits to the design document | — |

The ported messages, check order and `NullPointerException` cases are identical to the LexaVault originals.

## Old-to-new test mapping (21 methods, one to one, same method names)

| LexaVault test (stays until P2) | Library test |
|---|---|
| `TrustedTenantAdmissionTest.deniesUntrustedTenant` | `TrustedEntityAdmissionTest.deniesUntrustedTenant` |
| `TrustedTenantAdmissionTest.ambiguousCandidatesFailClosed` | `TrustedEntityAdmissionTest.ambiguousCandidatesFailClosed` |
| `OidcTenantSelectionTest.singleAuthorizedTenantDoesNotRequireSelection` | `EntitySelectionTest.singleAuthorizedTenantDoesNotRequireSelection` |
| `OidcTenantSelectionTest.multipleMembershipsRequireExplicitChoice` | `EntitySelectionTest.multipleMembershipsRequireExplicitChoice` |
| `OidcTenantSelectionTest.fabricatedOrMalformedTenantNeverGrantsAccess` | `EntitySelectionTest.fabricatedOrMalformedTenantNeverGrantsAccess` |
| `TenantGroupAuthorizationTest.rolesAreScopedToSelectedTenant` | `EntityGroupAuthorizationTest.rolesAreScopedToSelectedTenant` |
| `TenantGroupAuthorizationTest.tenantGroupCannotGrantPlatformOperations` | `EntityGroupAuthorizationTest.tenantGroupCannotGrantPlatformOperations` |
| `TenantGroupAuthorizationTest.rejectsMissingTenantPolicyAndAbsentGroups` | `EntityGroupAuthorizationTest.rejectsMissingTenantPolicyAndAbsentGroups` |
| `SharedIssuerActivationPreflightTest.acceptsOnlyExplicitModeWithActiveAuthorizedTenants` | `SharedIssuerPreflightTest.acceptsOnlyExplicitModeWithActiveAuthorizedTenants` |
| `SharedIssuerActivationPreflightTest.rejectsInactiveAndUnverifiedTenants` | `SharedIssuerPreflightTest.rejectsInactiveAndUnverifiedTenants` |
| `SharedIssuerActivationPreflightTest.refusesPlatformRoleInTenantMembership` | `SharedIssuerPreflightTest.refusesPlatformRoleInTenantMembership` |
| `OidcMachineTenantAdmissionTest.selectsOnlyUniqueBoundTenant` | `MachineEntityAdmissionTest.selectsOnlyUniqueBoundTenant` |
| `OidcMachineTenantAdmissionTest.deniesMissingOrAmbiguousBindings` | `MachineEntityAdmissionTest.deniesMissingOrAmbiguousBindings` |
| `OidcTenantMembershipResolverTest.isolatesRolesEvenWhenIdpGroupsContainMultipleTenants` | `EntityMembershipResolverTest.isolatesRolesEvenWhenIdpGroupsContainMultipleTenants` |
| `OidcTenantMembershipResolverTest.invalidGrantPolicyIsNotSilentlyTreatedAsNonMembership` | `EntityMembershipResolverTest.invalidGrantPolicyIsNotSilentlyTreatedAsNonMembership` |
| `OidcTenantMembershipResolverTest.inactiveTenantCannotBeAdmittedEvenWithGroupGrant` | `EntityMembershipResolverTest.inactiveTenantCannotBeAdmittedEvenWithGroupGrant` |
| `OidcTenantMembershipResolverTest.tenantGrantsFromDifferentIssuerCannotAuthorizeThisToken` | `EntityMembershipResolverTest.tenantGrantsFromDifferentIssuerCannotAuthorizeThisToken` |
| `OidcTenantMembershipResolverTest.noActiveBindingIsNotEnoughEvenWithSignedGroup` | `EntityMembershipResolverTest.noActiveBindingIsNotEnoughEvenWithSignedGroup` |
| `OidcTenantMembershipResolverTest.authenticatedGroupForAnotherTenantNeverElevatesSelectedTenant` | `EntityMembershipResolverTest.authenticatedGroupForAnotherTenantNeverElevatesSelectedTenant` |
| `OidcTenantMembershipResolverTest.missingPersistenceResultFailsClosed` | `EntityMembershipResolverTest.missingPersistenceResultFailsClosed` |
| `OidcTenantMembershipResolverTest.noGroupsCannotAuthorizeAnyTenant` | `EntityMembershipResolverTest.noGroupsCannotAuthorizeAnyTenant` |

Adaptations, recorded so the mapping is honest:
- Each refusal assertion expects `IdentityAdmissionException` instead of `SecurityContextException`.
- The Mockito mocks became lambdas, because the library has no Mockito. A lambda that checks its key stands in for Mockito's argument matching, and a recording lambda stands in for `verify(...)`.
- Role names are kept as strings through a test-only role catalogue.

Every assertion and input is otherwise unchanged.

## Parity result

`IdentityLibraryParityTest` (LexaVault, `securedoc-api`) runs each case through the old LexaVault classes and the new library classes. The library side goes through `IdentityAdmissionMapping`, so a refusal is compared as the 403 the product would raise, message included.

| Group | Cases | Differences |
|---|---|---|
| Admission (all cases of its test + 4 edges) | 10 | 0 |
| Selection (all cases + 6 edges: blank, padded, upper-case, null map, invalid uuid) | 12 | 0 |
| Group authorization (all cases + aliases, unknown role, empty or null grants, string claim, mixed groups, null entity, 6 matching edges) | 20 | 0 |
| Preflight (all cases + 5 edges) | 11 | 0 |
| Machine admission (all cases) | 5 | 0 |
| Membership resolver (all cases + 8 edges, including the raw trailing-slash `iss`, blank audience, no issuer, the adapter's role-check failure) | 24 | 0 |
| Shared-issuer request flow: membership → preflight → selection → roles, for the Phase 1 users and refusals, plus the machine path | 16 | 0 |
| **Total** | **98** | **0** |

**The test can see differences.** I planted a bug in the role catalogue, with the platform role never recognised. The run gave 93 the same and 5 different, then the file was restored.

**One requirement had no cases to cover.** The task asked parity to cover "the cases in `RequestContextResolverSecurityTest` that reach the shared-issuer branch". **There are none:** that test never switches the setting on. The only test that does, `SharedIssuerModeConfigurationTest`, just checks the default. The flow group above compares that branch's own sequence instead.

## Documents (Part C)

- `docs/design/BROWSER_ENTITY_CONTRACT.md` (new, one page): the two endpoints, the `X-Selected-Tenant-Id` header, the three rules, and where LexaVault implements each.
- `docs/design/LOGIN_CUSTOMER_ENTITY_DESIGN.md`, targeted edits only:
  - section 8: record types and rules in the library, persistence in each product, the library owns no tables;
  - section 6 (database): in local development tables may be created automatically, but no rows are ever inserted, and setup data comes only from the setup command;
  - section 12: Phase 1 marked done with the date and the entity switch, and the Phase 1b proof wording;
  - section 15 (backlog): validate role names at file load, `X-Selected-Tenant-Id` in CORS, SDV to the current library, one status per refusal reason (the 401/403 proposal), and a pointer to `docs/product/ENHANCEMENT_BACKLOG.md`.

## Stopped on

Nothing.

## What the second half (P2–P5, L5) needs that this half did not expect

1. **The shared-issuer branch of `RequestContextResolver` has no resolver-level unit test.** P2 switches exactly that branch. I recommend adding resolver tests for it at the start of P2, before the switch, written against the old code first so they pin today's behaviour.
2. **The parity test references the old classes,** so it must be retired in P2 when they are deleted. Its value carries over into the library tests, the new resolver tests and the Phase 1 proof.
3. **Spring wiring.** The library classes are plain Java, so P2 needs a small LexaVault configuration class to build `EntityMembershipResolver` and the other admission classes as beans: the JDBC adapters, `ProductEntityRoleCatalog`, and `AdmissionSettings` from `SecurityProperties`.
4. **`TrustedActorResolver` and `StableIdentityId` differ for padded values.** `StableIdentityId` trims the issuer and subject first; `TrustedActorResolver` does not. Real tokens carry no such spaces, but P3 changes actor ids for any token that did. The owner should confirm that is acceptable, or P3 must keep the untrimmed form.
5. **The grants adapter's role check stays a second gate:** a bad row still gives `IllegalStateException`, which becomes 409, as today. Phase 2's mapping-file validation is the place to change that.
6. **`0.3.0-SNAPSHOT` exists only in this machine's local Maven repository.** Anyone else building `feature/phase1b-identity-library` must first build the library branch. There is no shared build yet.
