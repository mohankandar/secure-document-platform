# Report 0055: checks from a security review

Date: 2026-11-25
Branch: `feature/0055-security-checks` (from bf3d95b, PR #29; nothing newer when it was opened)
Outcome: **DONE.** Nothing bigger was left over, so `docs/work/security-findings.md` was not made.

| Check | Result |
|---|---|
| 1. A person's token as a machine caller | Already refused: 0054 (push), 0075 (scanner); tested there. No change |
| 2. Request bodies without a limit | Fixed: 2 MB for every body that is not a file, announced or not, after sign-in (`securedoc.api.max-request-body`). New code **LV-SYS-015 Request too large** (413). Files keep their limits. The Scan Bridge: 64 KB, length must be announced (LV-INT-015) |
| 3. Services on all addresses | Fixed: app (`SECUREDOC_SERVER_ADDRESS`), frontend dev server and preview, Linux start (`LEXAVAULT_UI_ADDRESS`), PostgreSQL compose file (`LEXAVAULT_DB_ADDRESS`) default to 127.0.0.1. Scan Bridge and the test containers were already local |
| 4. Findings not published before fixed | Commit and PR texts name the fixes only |

No migration, no dependency. **No other product's name or behaviour in code.** Install guide: `docs/install/SETUP.md`.

## Commands run and results
Isolated copy of the committed tree, nothing installed (rule 11).

| Command | Result |
|---|---|
| `mvn -B -o -Pdev-build verify -fae`, PostgreSQL on (throwaway, removed) | BUILD SUCCESS: domain 83, application 372, scan bridge 144, infrastructure 156, API 933 (3 skipped), 0 failures |
| no-redacted-value check; product-name guard | PASS, 0 matches; clean |
| Live check on a running app (one-off script, throwaway database, removed) | 8 of 8: 127.0.0.1 by default; every address with the setting; too large refused with LV-SYS-015 with and without a length, also on push and scanner intake; a normal body reaches the endpoint |
| Frontend (base 15275): auth, vocabulary, ui-framework, oidc-signin, tablet, a11y | 10, 225, 19, 210, 95, 174 passed |
| Start scripts and compose file | parsed only, not run |

Failure on the way: my new test used a method Java 21 does not have; the API module did not compile once. Fixed. The frontend run was before that fix, which changed one backend test only.

## Existing tests changed
Counts of codes: `LvErrorCodeRegistryTest`, `lvCodes.test.ts` (160 to 161). `BridgeRequestSecurityFilterTest`: one test added. New: `RequestBodyLimitFilterTest`.

## Behaviour changes
- **An installation whose proxy or load balancer is on another machine (or that runs in a container) must set `SECUREDOC_SERVER_ADDRESS`**, or the app is reached from its own machine only.
- The start scripts use `docker compose up --no-recreate`: otherwise the changed port line would make them recreate `securedoc-postgres`. **An existing container keeps listening on all addresses.**

## Questions for the owner
1. `securedoc-postgres` on this machine: recreate it once yourself to close it (the data is in its volume), or leave it?
2. 2 MB for a request: enough? The largest known one is branding with two 512 KB logos (about 1.4 MB).
3. CI's PostgreSQL service listens on the build machine's addresses; it is temporary, so left. Accept?
