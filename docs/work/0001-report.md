# Report 0001: Phase 0, pin Surefire so the infrastructure tests run

Date: 2026-11-17
Branch: `fix/surefire-infrastructure-tests` (from `feature/lexavault-r1b-tenant-identity`)
Outcome: DONE

There was no task file for this work. It was done from a pasted prompt, and this report was written afterwards as task 0002 asked.

## What was done

- Added `maven-surefire-plugin` 3.5.2 under `<pluginManagement>` in `backend/pom.xml`, so every module inherits it. The per-module pins in domain, application, scan-bridge and api are unchanged.
- Updated "Known state" in `CLAUDE.md`: 426 backend tests, and the Surefire note.
- Added 7 patterns to `.gitignore`, approved separately by the owner: `.run/`, `**/securedoc-output/`, `.gradle/`, `performance-results/`, `performance-logs/`, `performance-cluster/`, `release-readiness-results/`.

**Correction.** `CLAUDE.md` used to say the 5 infrastructure test classes had never run. On this machine they already ran before the change: Maven 3.9 defaults to a Surefire that detects JUnit 5. The `target/surefire-reports` folder held passing results from 2026-11-16 20:53 (8 tests). Maven 3.8.7 on Ubuntu defaults to an older Surefire that does not detect JUnit 5, so these classes were skipped there. The pin makes every machine behave the same.

## Commands run and results

| Command | Result |
|---|---|
| `cd backend; mvn clean install` (Maven 3.9.16, Java 21, Windows) | BUILD SUCCESS, exit code 0 |

The build log shows `surefire:3.5.2:test` for all 10 modules with a test phase: domain, application, ai, agent, scan-bridge, redaction, infrastructure, worker, api and test-support. Every module that has tests reported `Using auto detected provider ... JUnitPlatformProvider`.

## Tests: counts per module, and any failures with their output

| Module | Tests |
|---|---|
| securedoc-domain | 31 |
| securedoc-application | 116 |
| securedoc-scan-bridge | 47 |
| securedoc-infrastructure | 8 |
| securedoc-api | 224 |
| ai, agent, redaction, worker, test-support | 0 (no tests) |
| **Total** | **426** (418 before) |

securedoc-infrastructure, by class:

| Class | Tests |
|---|---|
| JdbcActiveOidcMachineTenantBindingsTest | 2 |
| SharedIssuerActivationCompatibilityTest | 1 |
| SharedIssuerMigration037ContractTest | 2 |
| Ed25519LicenseTokenVerifierTest | 2 |
| JdbcPackageProcessingLeaseAdapterAttemptBudgetTest | 1 |

Failures: 0. Errors: 0. Skipped: 0.

Some test classes in `com.securedoc.platform.infrastructure.*` packages, such as `SdvDeliveryAdapterTest`, live in `securedoc-api/src/test`. They are counted under api, not infrastructure.

## Files changed

- `backend/pom.xml`: +11 lines (pluginManagement block)
- `CLAUDE.md`: new file; Known state updated
- `docs/design/LOGIN_CUSTOMER_ENTITY_DESIGN.md`: new file, unchanged from what the owner supplied
- `.gitignore`: +11 lines

## Commits (hash and message), or "not committed"

- `30e318a` build: pin maven-surefire-plugin 3.5.2 for every backend module
- `0e5b258` docs: add Claude Code instructions and login/customer/entity design
- `531b639` chore: ignore local runtime state, delivered output and result folders

Not pushed, not merged.

## Not done, and why

- The build was not run on Ubuntu (Maven 3.8.7). The claim that the pin fixes the skip there rests on Surefire's documented version behaviour, not on a run.
- The first commit attempt failed because no git identity was set. At the owner's instruction, the global identity was set to the owner's GitHub noreply address, and the commits were then made.

## Questions for the owner

None.

## Suggested next step

Run `cd backend; mvn clean install` once on Ubuntu WSL and confirm 426 tests, with 8 in securedoc-infrastructure.
