# Report 0014: Phase 2 and the request cleanup (Task A, steps A1 to A7)

Date: 2026-11-17
Branch: LexaVault `feature/phase2-role-mapping` (from `feature/lexavault-r1b-tenant-identity` at `82f4ef5`); library `feature/mapping-0.4.0` (from `main` at `0c5c0a7`, version `0.4.0-SNAPSHOT`)
Outcome: DONE

## What was done

| Step | Repo | Work | Tests after the step |
|---|---|---|---|
| A1 | LexaVault | The request principal is resolved once per request and kept on the request. A refusal is not kept. | backend 433 |
| A2 | LexaVault | Removed the second shared-issuer preflight call from `RequestContextResolver`; the same refusal text is kept. | backend 433 |
| A3 | both | Library messages are neutral, with one `Reason` per refusal (15 reasons). LexaVault maps each reason to its old text (`IdentityAdmissionMapping.message`). LexaVault now uses `0.4.0-SNAPSHOT`. | library core 44, spring 15; backend 435 |
| A4 | library | New package `identity.mapping`: CSV reader (RFC 4180, BOM, CRLF, quotes), `RoleMappingFile` (all checks in the task and plan; every error names its line; whole file refused on any error), `MappingPlan` (add, remove, unchanged per entity), `MappingTargets`. | core 68, spring 15 |
| A5 | library | A matched grant with an unknown role refuses only its own entity, and is logged as an ERROR. The person's other entities, and other people, are not affected. | core 73, spring 15; backend 435 |
| A6 | LexaVault | Command mode and the `load-role-mappings` command, the loader (one transaction, a row lock per entity, the difference, `ROLE_MAPPING_LOADED` audit), `JdbcRoleMappingStore`, and the read-path fix in `JdbcTenantOidcGroupGrantRepository`. | backend 459 |
| A7 | test-tools | The Phase 1 seed loads its grants through the command. Added checks M-01 to M-08, and the installer docs. | Phase 1 proof below |

Command mode: `java -jar securedoc-api.jar --lexavault.command=load-role-mappings --file=<path> [--dry-run]`.
- Spring starts without the web server, scheduled jobs, start-up runners, security filter chains or Liquibase, so the command never changes the schema. It runs once and exits with 0 (ok), 1 (refused), 2 (usage) or 3 (failed).
- It started cleanly, so the stop condition did not apply. Two things stood in the way, and both were solved without changing a frozen class:
  - The security filter chains need `HttpSecurity`, which a non-web context does not have. The command-mode bean filter leaves them out.
  - `@EnableScheduling` moved from the main class to `SchedulingConfiguration`, which is skipped in command mode. The server behaves as before.

## Commands run and results

- Library: `mvn clean install` in `C:\dev\shared\enterprise-identity-parent`, after A3, A4 and A5. All passed.
- LexaVault: `cd backend; mvn clean install` after each step. All passed, last after A6 (459 tests).
- Phase 1, on the jar from `5d5fa05`:
  - `Start-Phase1.ps1 -JarBuiltFromCommit 5d5fa05`: ready. The grants were loaded through the command.
  - `Invoke-Phase1Checks.ps1`: **68 passed, 0 failed** (60 existing, 8 new). Results: `C:\dev\lexavault\phase1-run\results\checks-20261117-221815.json`.
  - `Start-Phase1Ui.ps1`, then `node Invoke-Phase1BrowserSignin.mjs --mode dev`: **4 of 4 passed**.
- The first checks run had 67 passed and M-07 failed. The fault was in my new check, not in the product: bob is also in `lv-b-staff`, so removing that group correctly removed his DOCUMENT_SUPERVISOR role in B. I corrected M-07's expected value to "bob keeps B with BUSINESS_ADMIN and TENANT_IT_ADMIN", then reran the whole set (68 of 68). No existing check was changed.
- The frontend was not changed, so I did not run `test:standalone`.

Three refusals as an installer sees them (from the Phase 1 runs):

```
line 4: unknown role "BUSINES_ADMIN"
line 3: role "PLATFORM_OPERATIONS_ADMIN" is the platform role; it is set for the installation, never in this file
line 4: entity_name "Phase1 Entity A" does not match the entity's name "Phase1 Entity B"
```

## Tests: counts per module, and any failures with their output

- **Library:** core 73 (29 new: `RoleMappingFileTest` 19, `MappingPlanTest` 5, `EntityMembershipUnknownRoleTest` 5) and spring 15. The A3 count of 44 included the new `NeutralMessagesTest`.
- **LexaVault backend 459:**
  - domain 31, application 116, scan bridge 47;
  - infrastructure 12 (4 new, in `JdbcTenantOidcGroupGrantRepositoryTest`);
  - API 253 (25 new since task 0013, across `RequestContextResolverOncePerRequestTest`, `IdentityAdmissionMessagesTest`, `RoleMappingLoaderTest`, `LoadRoleMappingsCommandTest` and `CommandModeTest`).
- **Unedited tests:** every existing test passes unedited, including the 13 P2a tests.
- **Phase 1 checks:**
  - M-01: loading makes the grants equal the file (one removed in A, one added in B, 2 audit records).
  - M-02: a second load gives "No changes", with no rows changed and no new audit record.
  - M-03 to M-06: refused files exit 1 and leave the grants and audit trail unchanged.
  - M-07: carol loses B, keeps A; bob keeps B.
  - M-08: with a bad row in B, bob gets an empty entity list and a 403, while alice (A) and carol (A and B) are unaffected. Before this change, everyone got 409.

## Files changed

- **Library:**
  - `enterprise-identity-core/.../mapping/`: `MappingCsv`, `MappingError`, `MappingRow`, `Grant`, `MappingTargets`, `RoleMappingFile`, `MappingPlan`;
  - `admission/EntityMembershipResolver` (A5); A3 messages across `admission/`;
  - tests.
- **LexaVault backend:**
  - `api/command/` (`CommandMode`, `CommandModeBeanFilter`, `ProductCommand`, `LoadRoleMappingsCommand`);
  - `api/config/SchedulingConfiguration`, `SecureDocumentPlatformApplication`;
  - `api/security/oidc/RoleMappingLoader`, `IdentityAdmissionMapping`, `RequestContextResolver`;
  - `infrastructure/identity/RoleMappingStore`, `JdbcRoleMappingStore`, `JdbcTenantOidcGroupGrantRepository`;
  - `AuditAction` (`ROLE_MAPPING_LOADED`), `AuditController` (it is listed with the security actions);
  - `backend/pom.xml` (library `0.4.0-SNAPSHOT`);
  - tests.
- **test-tools:** `Start-Phase1.ps1`, `Invoke-Phase1Checks.ps1`, `README.md`, `db/phase1-seed.sql`, and the new `Invoke-Phase1RoleMapping.ps1` and `db/phase1-role-mapping.csv`.
- **Docs:** `docs/install/role-mapping-template.csv`, `docs/install/ROLE_MAPPING_FILE.md`.
- **No schema change.** Migration 037 is still not in the master changelog, and the shared-issuer setting is still off by default.

## Commits (hash and message), or "not committed"

Library (`feature/mapping-0.4.0`):
- `57af90d` feat(admission): neutral refusal messages, one reason per refusal (Phase 2 step A3)
- `ad8c51d` feat(mapping): role mapping file reader, validation and plan (Phase 2 step A4)
- `a7de999` feat(admission): an unknown role refuses only its own entity (Phase 2 step A5)

LexaVault (`feature/phase2-role-mapping`):
- `b4a46f3` perf(identity): resolve the request principal once per request (Phase 2 step A1)
- `3207c21` refactor(identity): drop the redundant preflight call in RequestContextResolver (Phase 2 step A2)
- `fe8edcd` feat(identity): LexaVault owns its refusal words (Phase 2 step A3)
- `5d5fa05` feat(identity): load-role-mappings command and loader (Phase 2 step A6)
- `05c358e` test(phase1): grants loaded through the command, checks M-01 to M-08 (Phase 2 step A7)
- The commit that adds this report and the task file.

Both branches are pushed. Nothing is merged, and the library is not released.

## Also report

**When to register migration 037.** I recommend registering it in Task B step B5, in the same change as the machine path that matches by issuer and `sub`, and before B8 (release and final proof).
- **Reason:** 037 only drops the cross-tenant unique index. With the setting off, it changes nothing by itself. But it allows a second entity to have an ACTIVE login-system record for the same issuer. Today the machine path still uses the legacy `OidcTenantResolver.findActive`, which refuses as soon as one issuer has two ACTIVE records.
- **So:** registering 037 before B5 would let one activation take machine intake offline for an entity. After B5 nothing depends on the old index.
- **What else changes then:** the infrastructure test that blocks registration (`SharedIssuerMigration037ContractTest` and its master-changelog guard) would change in that same approved step.

**What `POST /api/v1/lifecycle/purge-due` deletes.** It needs `PLATFORM_OPERATIONS` and runs the same use case as the scheduled `RetentionPurgeScheduler` (`PackageLifecycleService.execute(PurgeDueRetentionCommand)`), up to `limit` packages per pass.
- **Which packages:** it works across **all entities**. It selects packages whose retention time is due, under each entity's own lifecycle policy, and whose status is final (COMPLETED, FAILED, REJECTED, CANCELLED, VAULTED, APPROVED). The caller cannot choose an entity, a package or a date. "Due" is computed from the server clock.
- **What it deletes:**
  - **First pass:** the redacted and derived-redacted working files of due packages. Each artifact's stored file is deleted and the row is marked DELETED. It records `REDACTED_ARTIFACT_PURGED`.
  - **Second pass:** all remaining internal artifacts of packages due for final purge, originals included. It then hides exported-COMPLETED and never-exported FAILED packages from the dashboard and auto-closes their Technical Attention. It records `ORIGINAL_ARTIFACT_PURGED` and `PACKAGE_PURGED`.
  - **Third pass:** it reconciles dashboard hiding for packages that were purged earlier.
- **What it keeps:** package, document and audit rows, and the delivered copy at the customer's destination.
- **Answer:** yes, it touches every entity's documents, but only those already due under that entity's own policy. It deletes nothing the scheduler would not delete on its next run, and only does it sooner. Each record is written in the package's own entity, with the caller as actor.

## Not done, and why

- The production-build browser run (`--mode prod`) was not run. The task asks for "the browser script", and the dev run is the one used in every earlier proof. It can run on request.
- Grants stored under an entity's older issuer (one no longer ACTIVE) are left alone by the loader. The loader compares and writes only the grants for the entity's ACTIVE login-system record. Those older rows have no effect at sign-in.

## Questions for the owner

1. **The platform role in a hand-written grant still refuses the whole sign-in** for people matched by that group. It does not refuse only that entity.
   - The plan (section 1.7) said any invalid matched grant refuses only its entity. Three existing tests expect a hard refusal for a platform-role grant: library `invalidGrantPolicyIsNotSilentlyTreatedAsNonMembership`, LexaVault `OidcTenantMembershipResolverTest`, and P2a `thePlatformRoleCannotComeThroughAnEntityGroup`. Changing this would mean editing them, which is a stop condition. So A5 applies to unknown roles only.
   - The loader can never write either kind of bad row.
   - **Recommendation:** keep the hard refusal. A platform-role grant is an attempt to raise privilege, not a typo.
2. **Role aliases at read time.** `ProductRole` accepts aliases (`USER`, `REVIEWER`, `APPROVER`) and any capitalisation.
   - If the grants adapter passed hand-written rows straight through, a row with `user` would start granting DOCUMENT_PROCESSOR, where before it was refused.
   - The adapter now passes only exact role names. Anything else goes to the library marked `UNRECOGNISED:`, so only that entity is refused.
   - **Recommendation:** keep this. It is fail-closed.

**Owner decision (2026-11-17): yes to both.** The platform-role grant stays a hard refusal, and the adapter keeps marking non-exact role names as unrecognised.

## Suggested next step

Task B (Phases 3 and 4) as planned: B1 `PlatformAccess` in the library, then B2. Migration 037 to be registered in B5, after approval.
