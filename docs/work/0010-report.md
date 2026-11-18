# Report 0010: merge the entity switch, then plan Phase 1b

Date: 2026-11-17
Branch: `feature/lexavault-r1b-tenant-identity`
Outcome: DONE. Part A is merged, tested and pushed. The Part B plan is in `docs/work/0010-plan.md`; no code was changed for it.

## Part A

- **Merges into `feature/lexavault-r1b-tenant-identity`:**
  - `fix/auth-test-roles`: fast-forward to `9c0e0cb`;
  - `feature/entity-switch`: merge commit `69ca1d3`, no conflicts.
- **Committed:**
  - `docs/product/ENHANCEMENT_BACKLOG.md` (`d4b848a`); I read it first;
  - `CLAUDE.md` "Known state" (`3fcf5d8`): Phase 1 accepted, the entity switch in, `test:standalone`, library 0.2.1, Phase 1 containers kept.
- **Tests on the feature branch:**

| Suite | Result |
|---|---|
| Backend `mvn clean install` | BUILD SUCCESS, **426 tests, 0 failures**: domain 31, application 116, scan bridge 47, infrastructure 8, API 224 |
| `npm run test:standalone` | `test:auth` **8/8**, `test:ui-framework` **17/17**, `test:oidc-signin` **9/9** |
| `npm run build` | passed |

- **Pushed:** `feature/lexavault-r1b-tenant-identity` `523052f..3fcf5d8`. `main` is untouched at `1b69432`.
- **Phase 1 environment:** `Remove-Phase1.ps1 -KeepContainers` stopped the UI and the backend. `lexavault-phase1-postgres` (127.0.0.1:15432) and `lexavault-phase1-keycloak` (127.0.0.1:18181) are still up, with data and secrets kept.

## Part B: summary of the plan

**Move:** the R1B admission rules.
- `TrustedTenantAdmission`, `OidcTenantSelection`, `SharedIssuerActivationPreflight`, `OidcMachineTenantAdmission`, `OidcTenantMembershipResolver`, the group-matching half of `TenantGroupAuthorization`, and the three lookup interfaces.
- They go into the library's core module, in a new `admission` package, with no Spring and no LexaVault names.
- **What ties them to LexaVault today:** only `TenantId`, `SecurityContextException` and the role name `PLATFORM_OPERATIONS_ADMIN`.

**Stays in LexaVault:**
- the SQL adapters (they read LexaVault's tables);
- product roles and capabilities;
- the web layer, `RequestContextResolver` (it gets thinner) and settings;
- the frozen R1 pieces: legacy resolver, connection repository, activation, recovery and admin screens;
- `OidcConnectionBootstrap`, which is removed in Phase 5.

**The seam.** The product implements four small interfaces:
- active entity candidates;
- entity group grants;
- machine bindings;
- a role catalogue, which says which role names exist and which one is the platform role.

The library throws one exception with a reason code, and the product maps it to its own 403 with today's messages.

**Database.** The library owns no tables. Each product keeps its own migrations, as SDV already does. Migration 037 stays out of the master changelog.

**Settings.** The library gets no setting names. Each product passes a settings object. LexaVault's names and the off-by-default shared-issuer setting are unchanged.

**Frontend.** It stays in LexaVault until Phase 6. SDV's UI uses different versions and no SWR, and there is no package infrastructure. The browser contract is written down for SDV now.

**Replace two copies of library code.** `TrustedActorResolver` becomes the library's `StableIdentityId`; the only difference is whitespace trimming, which a test must clear first. LexaVault's duplicate `OidcAudienceValidator` is removed.

**Size.** About 4 to 5 sittings. The library steps L0–L4 can run in a second working folder at the same time as other work.

## Step list

| Step | Repo | Work | Phase 1 proof |
|---|---|---|---|
| L0 | library | Tests that pin today's behaviour of the untested classes | — |
| L1 | library | New types and the four interfaces; `0.3.0-SNAPSHOT` | — |
| L2 | library | Entity selection; tests ported case for case | — |
| L3 | library | Group authorization, preflight, machine admission | — |
| L4 | library | Membership resolver; snapshot installed locally | — |
| P1 | LexaVault | Adapters implement the library interfaces; parity test, old and new side by side | — |
| P2 | LexaVault | Switch to the library; delete the moved classes | **yes** |
| P3 | LexaVault | `TrustedActorResolver` → `StableIdentityId` (identical-id test first) | **yes** |
| P4 | LexaVault | Remove the duplicate audience validator | — |
| L5 | library | Release 0.3.0 (tag and push, with approval) | — |
| P5 | LexaVault | Pin 0.3.0, full build, push | **yes, final** |

## Risks

1. **Behaviour drift during the move.** Guarded by the P1 parity test, unchanged message texts, the unchanged `RequestContextResolverSecurityTest` and three Phase 1 proof runs.
2. **Actor ids changing**, which would split people in the audit trail. Guarded by making P3 its own step with an identical-output test.
3. **The library picking up LexaVault names.** Guarded by a library test that refuses `com.securedoc` imports.
4. **Frozen areas.** Enterprise Identity R1 gains a package and loses two LexaVault copies; its existing behaviour is unchanged. OIDC session continuity is not touched.
5. **Open library findings**, pinned by tests in L0 and not changed in 1b:
   - **Groups claim over 200 values, a value over 200 characters, or no `sub`:** these are **not 500s today**. LexaVault answers 403 and SDV answers 401, so the two products already disagree.
   - **String groups claims are split on spaces.**
   - **Trailing slash on the issuer:** the shared-issuer lookups use the raw `iss`, while the legacy lookup strips the slash. Today this fails closed.
   - **Missing tests:** the library has tests only for the audience validator.

**Where the design and the code disagree** (details in plan section 9):
- section 8 lists library content that does not exist yet;
- records versus persistence;
- "tests unchanged" cannot hold literally for moved tests;
- the server still inserts a login-system record at start.

## Questions for the owner, with my recommendation

1. **"The same tests pass unchanged."** Six test classes move with their classes and must get new imports and types. Is "same test cases, mapped one-to-one in the report, plus every remaining LexaVault test and the Phase 1 proof unchanged" acceptable? **Recommended: yes.**
2. **Should the library own no tables?** Each product would keep its own migrations, and the library would publish a schema contract (design section 8 versus the library README). **Recommended: yes.**
3. **Should LexaVault keep its `securedoc.*` setting names**, with the library taking a settings object instead of names of its own? **Recommended: yes.** Shared names can come later with a fallback.
4. **Should the browser-side entity code stay in LexaVault until Phase 6**, with only the contract written down now? **Recommended: yes.**
5. **When should the open library findings be fixed** (trailing slash, string groups split, exceptions answered with different statuses by the two products)? In 1b, or pinned now and fixed in Phase 2? **Recommended: pin now, fix in Phase 2**, so 1b stays a pure move with identical behaviour. One decision is needed before Phase 6 either way: the shared 401/403 per reason.
6. **May P3 and P4 remove LexaVault's copies of library code?** That is `TrustedActorResolver` and `OidcAudienceValidator`, each in its own step with an identical-behaviour test. **Recommended: yes.**
7. **Should the library run on `0.3.0-SNAPSHOT` during the work**, with `0.3.0` released and tagged only after the final Phase 1 proof passes? **Recommended: yes.**
