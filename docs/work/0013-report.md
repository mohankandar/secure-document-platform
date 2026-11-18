# Report 0013: close Phase 1b, then plan Phases 2, 3 and 4

Date: 2026-11-17
Branch: `feature/lexavault-r1b-tenant-identity`
Outcome: DONE. Phase 1b is merged and pushed. The plan is `docs/work/0013-plan.md`; no code was changed for it.

## Part A

- **Removed** (commit `b8b9f99` on `feature/phase1b-identity-library`): the three unused library bridges, `find(ConnectionKey)`, `activeGrants(ConnectionKey)` and `find(ConnectionKey, String)`, from `JdbcActiveOidcTenantCandidates`, `JdbcTenantOidcGroupGrantRepository` and `JdbcActiveOidcMachineTenantBindings`.
  - The three files are back to their pre-P1 form, with the SQL unchanged.
  - Nothing else used them (checked with `git grep`).
  - The preflight re-check and the id conversions are kept until Phase 4, as decided.
- **Merge:** `feature/phase1b-identity-library` into `feature/lexavault-r1b-tenant-identity`, a fast-forward `c32adb7..b8b9f99`.
- **Tests on the feature branch:**

| Suite | Result |
|---|---|
| Backend `mvn clean install` | **430 tests, 0 failures**: domain 31, application 116, scan bridge 47, infrastructure 8, API 228 |
| `npm run test:standalone` | 8 + 17 + 9, all passing |

- **`CLAUDE.md`:**
  - library 0.3.0 (the table, the environment line and "Known state");
  - 430 tests;
  - Phase 1b done;
  - the rule: *login and entity rules live in the library; new ones are written there; LexaVault keeps only its seam*;
  - how to rerun the Phase 1 proof.
- **Design document** (targeted edits):
  - the Status line says what is done (Phases 0, 1, 1b) and what is not;
  - "Based on" says library 0.3.0;
  - section 12 marks Phase 1b done with the date;
  - the backlog item: the library's messages use product words, and the product should own the words and the library the reasons.
- **Pushed:** `feature/lexavault-r1b-tenant-identity` `c32adb7..992403a`, plus this report commit. `main` is untouched.

## Part B: summary of the plan

**Phase 2, the role mapping file.**
- **The format:** a CSV file, one row per grant: `entity_id, entity_name, group, role`. It opens in Excel, needs no new dependency, and comment lines carry the customer and review record.
- **Validation:** the whole file is checked first, and a bad file is refused whole, with every error naming its line. Covered: unknown role, platform role, unknown entity or wrong name, no ACTIVE login system, duplicates, two names for one entity, bad groups.
- **Loading:**
  - the file is the full record for each entity it names; other entities are untouched;
  - it runs in one transaction;
  - a second load writes nothing and records nothing;
  - each entity that changed gets one audit record.
- **How it runs:** an explicit command in the backend jar, never at start and never as SQL. Phase 5's setup command is a second command on the same entry point.
- **The "one bad role breaks everyone" fix:** the read path in `JdbcTenantOidcGroupGrantRepository` stops throwing for the whole lookup, and the library refuses only the broken entity.
- **What goes where:** format and validation in the library; table writes and audit in LexaVault.
- **The library's product words** are fixed here, while LexaVault keeps its texts.

**Phase 3, platform access.**
- **Today:** with the shared mode on, nobody can reach the 19 platform endpoints.
- **The proposal:**
  - an install-level setting names one login-system group, and optionally its issuer; empty means nobody;
  - it is checked on every request from the verified token;
  - it never comes from an entity's mapping, because the loader and the role catalogue both refuse the platform role;
  - an explicit list of platform routes gives a platform principal, with no entity and no documents;
  - every other route is decided by entity admission only.
- **Hosting later:** the issuer in the setting becomes the staff space, as configuration only.

**Phase 4, machines and caching.**
- **Machine binding:**
  - bind a service account to an entity by the token's issuer and `sub`;
  - a unique index across entities enforces it (schema change);
  - the scanner route joins the machine path.
- **Caching:**
  - first, resolve the principal once per request, instead of 3 or more times;
  - then cache only the per-connection lookups, for 30 seconds, cleared on write on the same server;
  - a clock test and the Phase 1 toggles prove that a revocation never outlives 30 s.
- **Leftovers:** the preflight call is removed, and the id round trip is reduced to one conversion.

**New Phase 1 checks:** 22 in all.
- Phase 2: M-01 to M-08;
- Phase 3: P-01 to P-07;
- Phase 4: I-01 to I-05, C-01 and C-02.

**Size:** about 5 to 6 sittings, in two tasks. Some library steps can run in parallel.

## Step list

| Task | Step | Work | Phase 1 proof |
|---|---|---|---|
| A | A1 | Resolve the principal once per request | — |
| A | A2 | Remove the preflight call, reduce the id round trip | — |
| A | A3 | Library: neutral messages; LexaVault keeps its texts | — |
| A | A4 | Library: mapping file parser and validation | — |
| A | A5 | Library: a broken entity is refused for itself only | — |
| A | A6 | LexaVault: command runner, loader, audit, read-path fix | — |
| A | A7 | Phase 1 seed via the loader; checks M-01 to M-08 | **yes** |
| B | B1–B3 | Platform rule, setting, routes, platform-only sign-in | — |
| B | B4 | Checks P-01 to P-07 | **yes** |
| B | B5 | Machine binding by issuer and `sub` (migration); scanner route | — |
| B | B6 | Admission cache with clear-on-write | — |
| B | B7 | Checks I-01 to I-05, C-01, C-02 | — |
| B | B8 | Library 0.4.0, pin it | **yes, final** |

## Questions for the owner, with my recommendation

1. **What format should the role mapping file use?** CSV, one row per grant (opens in Excel, no new dependency), or YAML (nested, but needs a parser library)? **Recommended: CSV.**
2. **Should loading replace mappings?** The proposal: the file replaces the mappings of every entity it names, and entities not in the file are untouched. **Recommended: yes.**
3. **May the sign-in flow change** (a frozen area, OIDC session continuity) so that a platform-only IT person, in no entity, can sign in to the platform screens? **Recommended: yes.** Without it, point 5 cannot be met.
4. **May the platform role see entity audit records and technical exceptions?** Today it includes `AUDIT_VIEW` and `TECHNICAL_EXCEPTION_VIEW`, and some exception views show package identifiers. **Recommended:** on platform routes, counts and states only. Entity audit and exception detail would need an entity role.
5. **May Phase 4 change the schema?** It adds `client_issuer` and `client_subject` to `integration_connections`, with a unique index across entities, so one service account belongs to exactly one entity. **Recommended: yes.**

Technical choices I made, so they are not questions:
- the cache time is 30 s;
- the machine claim is `sub` plus the issuer;
- the scanner route joins the machine path;
- the library messages become neutral in Phase 2;
- an entity with a bad grant is refused for that entity only;
- one command runner is reused by the Phase 5 setup command.
