# Report 0005: fix R-08 in the Enterprise Identity library, then rerun the Phase 1 checks

Date: 2026-11-17
Branches:
- library: `fix/null-audience-validator` in `C:\dev\shared\enterprise-identity-parent` (from `main` at `bc63ff0`)
- LexaVault: `test/phase1-shared-issuer`

Outcome: DONE. The library fix is built and tested (5 tests). LexaVault builds on 0.2.1 (426 tests). All 48 Phase 1 checks pass, including R-08 (now 401) and the new wrong-audience check R-15 (401).

## What was done

### Part A: library

1. Created branch `fix/null-audience-validator` from `main`.
2. `RequiredAudienceValidator.validate`: a null audience list (a token without an `aud` claim) now returns the existing "Required API audience is missing." failure result instead of throwing. Nothing else in the class changed.
3. Added the module's first unit tests (5 cases, below).
   - **Test dependency added:** `org.junit.jupiter:junit-jupiter:5.10.3`, test scope, in `enterprise-identity-spring/pom.xml` only.
   - It was already in the local Maven repository, so nothing was downloaded.
   - It is the same JUnit line LexaVault's modules use. Surefire 3.5.2 was already pinned in the library parent.
4. Version 0.2.0 → 0.2.1 in all three places it is declared: the parent `pom.xml` and the `<parent>` block of both module poms.
5. `mvn clean install`: BUILD SUCCESS, 5 tests, 0 failures. 0.2.1 is installed in the local Maven repository.
6. **The test catches the defect:** I ran it once against the old validator code. `tokenWithoutAudClaimFailsWithoutThrowing` failed with the exact R-08 `NullPointerException`. The fix was then restored and 0.2.1 rebuilt and reinstalled.
7. Committed as `b126091`. Not pushed.

### Part B: LexaVault

1. `backend/pom.xml`: `enterprise.identity.version` 0.2.0 → 0.2.1, committed on its own as `effaa89`.
2. **Full backend build:** `cd backend; mvn clean install`, BUILD SUCCESS, 426 tests, 0 failures.
   - Nothing was locked. The normal backend was not running (port 8080 free), and the Phase 1 backend runs from its own jar copy.
   - The new `securedoc-api` jar bundles `enterprise-identity-core-0.2.1.jar` and `enterprise-identity-spring-0.2.1.jar`.
3. **Wrong-audience check (R-15):**
   - The realm file now has a test-only client `lexavault-phase1-otheraud`. Its token carries `aud=phase1-other-api` (checked by decoding it in memory; the token was not printed).
   - The running Keycloak had imported the realm earlier and ignores realms that already exist. So `Start-Phase1.ps1` now creates any client that is in the realm files but missing in the running Keycloak. The realm files stay the single source.
4. **Restart:** stopped the Phase 1 backend with `Remove-Phase1.ps1 -KeepContainers`. This was its first real use, and it worked: only the backend stopped, and the containers and data stayed. I then started the backend from the new jar with `Start-Phase1.ps1 -JarBuiltFromCommit effaa89`. The running copy bundles 0.2.1.
5. Reran all checks: 48 passed, 0 failed. The backend log for this run has 0 ERROR lines.

**A mistake in my own test script**, found and fixed during the run (test file only):
- On the first restart, the missing-client step did not create the new client.
- Cause: Windows PowerShell 5.1 passes a JSON array from `Invoke-RestMethod` on as one object, so `@(Invoke-RestMethod …)` of an empty result counted 1, and the script believed the client existed.
- Fix: store the response in a variable first, then wrap it. The same pattern in the user lookup was fixed too; it had only worked by luck.
- After that, the backend was stopped and started once more, the client was created, and the checks ran.

## Library diff (full)

```diff
--- a/enterprise-identity-core/pom.xml
+++ b/enterprise-identity-core/pom.xml
@@ -4,7 +4,7 @@
     <parent>
         <groupId>com.documentplatform.identity</groupId>
         <artifactId>enterprise-identity-parent</artifactId>
-        <version>0.2.0</version>
+        <version>0.2.1</version>
     </parent>
--- a/enterprise-identity-spring/pom.xml
+++ b/enterprise-identity-spring/pom.xml
@@ -4,7 +4,7 @@
     <parent>
         <groupId>com.documentplatform.identity</groupId>
         <artifactId>enterprise-identity-parent</artifactId>
-        <version>0.2.0</version>
+        <version>0.2.1</version>
     </parent>
@@ -23,5 +23,11 @@
             <artifactId>spring-security-oauth2-resource-server</artifactId>
             <version>${spring.security.version}</version>
         </dependency>
+        <dependency>
+            <groupId>org.junit.jupiter</groupId>
+            <artifactId>junit-jupiter</artifactId>
+            <version>5.10.3</version>
+            <scope>test</scope>
+        </dependency>
     </dependencies>
--- a/enterprise-identity-spring/src/main/java/com/documentplatform/identity/spring/RequiredAudienceValidator.java
+++ b/enterprise-identity-spring/src/main/java/com/documentplatform/identity/spring/RequiredAudienceValidator.java
@@ -5,6 +5,8 @@ import org.springframework.security.oauth2.core.OAuth2TokenValidator;
 import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
 import org.springframework.security.oauth2.jwt.Jwt;
 
+import java.util.List;
+
 public final class RequiredAudienceValidator implements OAuth2TokenValidator<Jwt> {
@@ -15,7 +17,9 @@
     @Override
     public OAuth2TokenValidatorResult validate(Jwt token) {
-        return token.getAudience().contains(audience)
+        // A token without an aud claim yields null here; treat it as "required audience is missing".
+        List<String> audiences = token.getAudience();
+        return audiences != null && audiences.contains(audience)
                 ? OAuth2TokenValidatorResult.success()
                 : OAuth2TokenValidatorResult.failure(
                 new OAuth2Error("invalid_token", "Required API audience is missing.", null));
--- a/pom.xml
+++ b/pom.xml
@@ -4,7 +4,7 @@
     <groupId>com.documentplatform.identity</groupId>
     <artifactId>enterprise-identity-parent</artifactId>
-    <version>0.2.0</version>
+    <version>0.2.1</version>
```

New file `enterprise-identity-spring/src/test/java/com/documentplatform/identity/spring/RequiredAudienceValidatorTest.java` (73 lines). The full diff is in commit `b126091`.

## Commands run and results

| Command | Result |
|---|---|
| `git checkout -b fix/null-audience-validator` (library) | Branch created from `main` |
| `mvn clean install` (library) | BUILD SUCCESS. core: no tests; spring: 5 run, 0 failures |
| New test run against the old validator code (temporary, then restored) | `tokenWithoutAudClaimFailsWithoutThrowing` FAILED with the R-08 `NullPointerException`, as intended |
| `mvn -pl enterprise-identity-spring -am clean install` (library, after restoring the fix) | Success; 0.2.1 reinstalled |
| `cd backend; mvn clean install` (LexaVault, on 0.2.1) | BUILD SUCCESS, 426 tests, 0 failures |
| `Remove-Phase1.ps1 -KeepContainers` (twice) | Backend stopped; containers kept |
| `Start-Phase1.ps1 -JarBuiltFromCommit effaa89` (twice; the second run after the script fix) | Ready. "Created missing Keycloak client lexavault-phase1-otheraud". 037 still recorded, `uq_eic_active_binding` absent, `uq_eic_active_per_tenant` present. Connections to 15432 only for the database. Seed idempotent: 2 tenants, 2 ACTIVE connections, 6 grants |
| `Invoke-Phase1Checks.ps1` | 48 passed, 0 failed. Results: `C:\dev\lexavault\phase1-run\results\checks-20261117-170757.json` |
| Scan of `phase1-run\results` and `logs` for JWT-shaped strings and for each generated password | 0 and 0 |

## Tests: counts per module, and any failures with their output

Library, `enterprise-identity-spring`, `RequiredAudienceValidatorTest`: 5 run, 0 failures.

| Test | Covers |
|---|---|
| `tokenWithoutAudClaimFailsWithoutThrowing` | no `aud` claim: failure result, no exception (asserts `getAudience()` is null first) |
| `tokenWithEmptyAudFails` | empty `aud`: failure |
| `tokenWithOnlyAnotherAudienceFails` | `aud` present but not ours: failure |
| `tokenWithTheRequiredAudienceSucceeds` | `aud` equal to ours: success |
| `tokenWithSeveralAudiencesIncludingTheRequiredOneSucceeds` | several values including ours: success |

`enterprise-identity-core` has no tests.

LexaVault backend:

| Module | Tests |
|---|---|
| securedoc-domain | 31 |
| securedoc-application | 116 |
| securedoc-scan-bridge | 47 |
| securedoc-infrastructure | 8 |
| securedoc-api | 224 |
| ai, agent, redaction, worker, test-support | 0 (no tests) |
| **Total** | **426, 0 failures** |

### Phase 1 checks

| Id | Check | Expected | Actual | Result |
|---|---|---|---|---|
| S-01 | Setup: one package created in each entity by its own admin | A and B package ids | A and B package ids | PASS |
| P1-01 | alice: /me/tenants lists only entity A | 200 [phase1-entity-a{BUSINESS_ADMIN,DOCUMENT_SUPERVISOR,TENANT_IT_ADMIN}] | same | PASS |
| P1-02 | alice: /me without a choice opens entity A | 200 phase1-entity-a{…} | same | PASS |
| P1-03 | alice: /me choosing entity B is refused | 403 | 403 | PASS |
| P1-04 | alice: open B's package without a choice | 403 or 404 | 404 | PASS |
| P1-05 | alice: open B's package choosing B | 403 | 403 | PASS |
| P1-06 | alice: package list shows A, not B | 200, own listed, other not | same | PASS |
| P1-07 | bob: /me/tenants lists only entity B | 200 [phase1-entity-b{…}] | same | PASS |
| P1-08 | bob: /me without a choice opens entity B | 200 phase1-entity-b{…} | same | PASS |
| P1-09 | bob: /me choosing entity A is refused | 403 | 403 | PASS |
| P1-10 | bob: open A's package without a choice | 403 or 404 | 404 | PASS |
| P1-11 | bob: open A's package choosing A | 403 | 403 | PASS |
| P1-12 | bob: package list shows B, not A | 200, own listed, other not | same | PASS |
| P2-01 | carol: /me/tenants lists A and B | 200 [a{DOCUMENT_SUPERVISOR}; b{DOCUMENT_SUPERVISOR}] | same | PASS |
| P2-02 | carol: /me without a choice is refused | 403 | 403 | PASS |
| P2-03 | carol: choose A | 200 phase1-entity-a{DOCUMENT_SUPERVISOR} | same | PASS |
| P2-04 | carol in A: package list shows A, not B | 200, own listed, other not | same | PASS |
| P2-05 | carol: switch to B (same token) | 200 phase1-entity-b{DOCUMENT_SUPERVISOR} | same | PASS |
| P2-06 | carol in B: package list shows B, not A | 200, own listed, other not | same | PASS |
| P2-07 | carol: switch back to A | 200 phase1-entity-a{DOCUMENT_SUPERVISOR} | same | PASS |
| P2-08 | carol in A: open B's package | 403 or 404 | 404 | PASS |
| P2-09 | carol in B: open A's package | 403 or 404 | 404 | PASS |
| P2-10 | carol in A: business-admin settings refused | 403 | 403 | PASS |
| R-01 | carol: choose the demo tenant (exists, ACTIVE, no grant) | 403 | 403 | PASS |
| R-02 | alice: choose a random UUID | 403 | 403 | PASS |
| R-03 | alice: malformed choice value | 403 | 403 | PASS |
| R-04 | dave (no entity): /me/tenants is empty | 200 [] | 200 [] | PASS |
| R-05 | dave (no entity): /me refused | 403 | 403 | PASS |
| R-06 | dave (no entity): package list refused | 403 | 403 | PASS |
| R-07 | alice: LOCAL-mode headers ignored in OIDC mode | 200 phase1-entity-a{alice's three roles} | same | PASS |
| **R-08** | **token without the API audience (no `aud` claim)** | **401** | **401** (was 500 in report 0004) | **PASS (fixed)** |
| R-09 | no token | 401 | 401 | PASS |
| R-10 | wrong issuer (other realm, same group name): /me | 401 | 401 | PASS |
| R-11 | wrong issuer: /me/tenants | 401 | 401 | PASS |
| R-12 | malformed bearer token | 401 | 401 | PASS |
| **R-15** | **NEW: token with an `aud` claim that is not the LexaVault API** | **401** | **401** | **PASS (new)** |
| R-13-1 | B's login-system record DISABLED: bob /me/tenants is empty | 200 [] | 200 [] | PASS |
| R-13-2 | … bob /me refused | 403 | 403 | PASS |
| R-13-3 | … carol /me/tenants lists only A | 200 [a{DOCUMENT_SUPERVISOR}] | same | PASS |
| R-13-4 | … carol choosing B refused | 403 | 403 | PASS |
| R-13-5 | … carol choosing A still works | 200 phase1-entity-a{DOCUMENT_SUPERVISOR} | same | PASS |
| R-13-6 | … after restore, bob opens B again | 200 phase1-entity-b{…} | same | PASS |
| R-14-1 | Entity B SUSPENDED: bob /me/tenants is empty | 200 [] | 200 [] | PASS |
| R-14-2 | … bob /me refused | 403 | 403 | PASS |
| R-14-3 | … carol /me/tenants lists only A | 200 [a{DOCUMENT_SUPERVISOR}] | same | PASS |
| R-14-4 | … carol choosing B refused | 403 | 403 | PASS |
| R-14-5 | … carol choosing A still works | 200 phase1-entity-a{DOCUMENT_SUPERVISOR} | same | PASS |
| R-14-6 | … after restore, bob opens B again | 200 phase1-entity-b{…} | same | PASS |

Totals: 48 checks, 48 PASS, 0 FAIL. R-15 is numbered after the toggle checks R-13 and R-14 because they already existed. It runs straight after R-12.

## Files changed

Library (commit `b126091`):
- `pom.xml`, `enterprise-identity-core/pom.xml`, `enterprise-identity-spring/pom.xml`: version 0.2.1; junit-jupiter test dependency in the spring module
- `enterprise-identity-spring/src/main/java/.../RequiredAudienceValidator.java`: the null-safe check
- `enterprise-identity-spring/src/test/java/.../RequiredAudienceValidatorTest.java`: new

LexaVault:
- `backend/pom.xml`: `enterprise.identity.version` 0.2.1 (commit `effaa89`, on its own)
- `test-tools/phase1-shared-issuer/keycloak/lexavault-phase1-realm.json`: new client `lexavault-phase1-otheraud`
- `test-tools/phase1-shared-issuer/Start-Phase1.ps1`: creates missing clients from the realm files; PowerShell 5.1 array fix in two lookups
- `test-tools/phase1-shared-issuer/Invoke-Phase1Checks.ps1`: check R-15
- `test-tools/phase1-shared-issuer/Phase1-Common.ps1`: constant for the new client id
- `docs/work/0005-task.md` (unchanged) and this report

## Commits (hash and message), or "not committed"

Library, `git log --oneline -3`:

```
b126091 fix: treat a missing aud claim as a missing audience (0.2.1)
bc63ff0 Initial commit: Enterprise Identity shared library v0.2.0
```

There are only two commits on this branch.

LexaVault, `git log --oneline -3` before the test-file commit:

```
effaa89 build: use Enterprise Identity 0.2.1 (null-safe audience validator)
12aea8a test: add Phase 1 shared-issuer test environment and sitting 1 results
49a8a70 docs: add task 0003 and the Phase 1 plan (report 0003)
```

The test-file changes, this task file and this report go in one further commit after `effaa89`; its hash is in `git log`. Neither repository is pushed or merged.

## Not done, and why

- **SDV not changed**, as the task says. It still uses 0.2.0, so it still has the R-08 defect.
- **Library `main` is unchanged.** The fix is only on `fix/null-audience-validator`, and 0.2.1 exists only in the local Maven repository. A fresh machine building LexaVault would not find 0.2.1 until the library branch is merged and installed there.
- **The Phase 1 environment is left running**, as asked: Keycloak 127.0.0.1:18181, PostgreSQL 127.0.0.1:15432, backend localhost:18080.

## Things noticed in the Enterprise Identity library (listed only, not changed)

1. **The README still says "Enterprise Identity 0.1.0"** in its title, and has no 0.2.1 entry. I left it because the task allowed no other change.
2. **Similar unchecked exceptions in other code.** These may cause the same kind of 500 as R-08 instead of a 401 or 403. I have not checked how LexaVault maps them.
   - `IdentityClaims.values` throws `IllegalArgumentException` for a groups claim with more than 200 values, or a value longer than 200 characters.
   - `OidcJwtIdentityNormalizer` throws it for a token without `sub`.
   - A user in more than 200 Keycloak groups is plausible in large organisations. A test for each case would settle it.
3. **A plain-string groups claim is split on spaces and commas.** A group name that contains a space (for example "Business Users"), sent as a single string, becomes two groups. A JSON array claim, as Keycloak sends, is not affected.
4. **The library compiles against Spring Security 6.3.1** (`spring.security.version`), while the products run their own Spring Security version. That has been fine so far, but nothing tests the library against the products' versions.
5. **Trailing-slash handling differs.** `CachingOidcConnectionBindingResolver` removes a trailing `/` from the issuer. LexaVault's shared-issuer JDBC lookups compare the raw `iss` without normalising (noted in the Phase 1 plan). This is a product-side inconsistency, not a library bug.
6. **Only `RequiredAudienceValidator` has tests now.** `IdentityClaims`, `StableIdentityId`, `CachingOidcConnectionBindingResolver` and `OidcJwtIdentityNormalizer` have none in the library itself.
7. **No release tags.** `main` has a single "Initial commit … v0.2.0" and no tags, so a released version cannot be traced to a commit.

## Questions for the owner

1. **Library branch:** may `fix/null-audience-validator` be merged into the library's `main`, and tagged `v0.2.1`? Until then, LexaVault's `backend/pom.xml` depends on a version that exists only in this machine's local Maven repository.
2. Should items 2 and 3 above (other unchecked exceptions, and string groups split on spaces) become a follow-up task? It would add tests first, to see whether they really give 500s.

## Suggested next step

Sitting 2 of Phase 1 (point 3, redaction rules per entity, and the owner's screen checks) on the running environment. SDV moves to 0.2.1 in a later task, as planned.
