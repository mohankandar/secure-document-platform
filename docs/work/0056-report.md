# Report 0056: Spring Boot 4.2 and platform-core v0.8.1

Date: 2026-11-24
Branch: `feature/0056-spring-boot-4`, from 0052's branch (not merged yet)
Outcome: **DONE** (6 questions). Built by a subagent from a written brief; I read the product diff and ran the proofs myself. Lines marked (agent) were run by the subagent only.

## What was done
- **Versions:** Spring Boot **4.2.0** (newest 4.2.x), with Framework 7.1.0, Security 7.2.0, Hibernate 7.4.15, Tomcat 11.0.27, Jackson 3.1.8, Flyway 12.11.0. platform-core **0.8.1**, same modules as in 0054. App, scan bridge and setup jar alike. Every library used has a Boot 4.2-ready version.
- **Dependency changes:** starters `web` to `webmvc` and `oauth2-resource-server` to `security-oauth2-resource-server`; `spring-boot-liquibase` added (Boot 4 split it out); Jackson 2 databind and jsr310 replaced by Jackson 3 databind (only `jackson-annotations` 2.21 remains, which Jackson 3 uses); logstash-logback-encoder 8.0 to 9.0; the HttpClient pins are gone (Boot manages the same versions).
- **Liquibase 4.29.2**, not Boot's 5.0.4, whose POM says `FSL-1.1-ALv2`. 4.33.0 was tried first and replaced: it contains "analytics" classes.
- **Kept as before where Boot 4 differs:**

| Boot 4 would have | Kept by |
|---|---|
| Changed JSON (order, dates, enums, nulls, emoji bytes, size limit) | `spring.jackson.*` in both `application.yml`; own mappers use `builderWithJackson2Defaults()` |
| Read the AI's number and text values differently | `JsonNodeValues` |
| Added a public address, `/.well-known/oauth-protected-resource` | filter removed from the OIDC chain |
| Accepted a GET with a header token and an `access_token` (401 before) | `OneBearerTokenResolver` |
| Kept signing keys 5 instead of 15 minutes: 500 for signed-in people in a short login-system outage | `IssuerKeyCache` |
| Turned liveness/readiness on; waited for requests at stop; logged the database address | settings in `application.yml` |
| Silently ignored the demo step's exclusions (renamed classes) | the new names |

## Commands run and results
| Command | Result |
|---|---|
| `mvn -B -o -Pdev-build clean install`, PostgreSQL tests on | BUILD SUCCESS: domain 79, application 304, scan bridge 136, infrastructure 115, API 729 (1 skipped, the timing test), 0 failures |
| `mvn -B -o clean verify` (production) (agent) | BUILD SUCCESS, same counts |
| `npm run test:standalone`, **1 run** | 0 failures: auth 10, vocabulary 193, ui-framework 17, oidc-signin 169, tablet 95, a11y 156. The task asks for 3; runs 2 and 3 were dropped under queue rule 9 (test policy) and are left for milestone M1 |
| No-redacted-value, compliance report, export end-to-end, inline / staged | all PASS |
| Two-server proof (`Invoke-TwoServerCheck.ps1 -DbPort 55450`) | PASS (24 checks) |
| Product-name guard | clean (1700 files) |
| `Build-LexaVault-All.ps1` and `.sh`, development and production, tests skipped (agent) | all pass, jar checks included |
| Fresh LOCAL, fresh OIDC (throwaway Keycloak), and a database prepared by the old jar, then LOCAL to OIDC (agent) | all work; old database: 0 migrations, history tables byte-identical |
| `java -jar` for api, bridge, setup (agent) | works |

**Old against new, the real jars (agent):** LOCAL 312 requests, OIDC 225 requests (7 kinds of token holder, 8 kinds of bad token), bridge 38, 110 reads of an old database: the same except the list below, `Set`/`Map` order, times and addresses. Database rows equal after masking ids and times, intake fingerprints included, except values that hold a random id, a date or a map order. Retried old intakes return the same cases. AI requests: 11 of 11 the same characters. A 5.5-minute key outage: 200 on both.

| Numbers (agent) | before | after |
|---|---|---|
| App start, ms (4 runs) | 11703, 12101, 11504, 12111 | 10110, 10140, 10210, 10268 |
| Memory (RSS) 10 s after start, MB | 522, 526, 522, 520 | 497, 459, 444, 498 |
| Bridge start ms / RSS MB (1 run) | 4782 / 226 | 4239 / 296 |
| App jar, MB | 72.4 | 78.6 |

## Differences that remain
- `Vary` is no longer sent where no CORS rule applies; some small answers have `Content-Length` instead of chunked; HEAD has no Transfer-Encoding; OPTIONS has an extra empty `Accept-Query`.
- TRACE is 405 instead of 400; an unknown method's 400 has Boot's error body. `/logout` redirects relatively.
- JSON nested deeper than 500 (was 1000) is a 400 `LV-INT-001`. Outside the web mapper a JSON string may be 100 MB (was 20).
- Log text: some exception type names, Hibernate logger names, order of the context fields.
- From reading the code, not run: DPoP-bound tokens are refused; fetching keys times out (none before); Tomcat 11 limits 1000 parameters and 50 parts.

## Existing tests changed (none weakened)
- 36 files, mechanically: Jackson 3 imports and method names; moved Spring and Jackson class names. The first build failed on three classes that named a moved Spring class as text.
- Extended: `BearerTokenRefusalEntryPointTest`. New: `JsonSettingsContractTest` (21), `OidcFilterChainTest` (3), `JsonNodeValuesTest` (3), `IssuerKeyCacheTest` (3).
- `adminSignIn.spec.ts` (0054's test, fixed on 0052's branch): CI on PR #4 failed once because its locator also matched the button's spinner while the test ran. It now names the answer only. 20 repeats: all pass (the only test run after the merge of that fix).

## Commits (hash and message), or "not committed"
- `4cdbd89` build(backend): Spring Boot 4.2.0, Jackson 3, platform-core 0.8.1 (its message says Liquibase 4.33.0; it is 4.29.2 since `617e233`)
- `617e233` fix(api): JSON, sign-in, probes, logs and stop behave as before the move; Liquibase without usage reporting
- `ba8674a` fix(api): a short outage of the login system refuses nobody, as before
- the merge of 0052's test fix, and the report commit

## Not done or not proven, and why
- `Start-LexaVault-*.ps1` were not run (they use your containers). The start paths were run with the same jars on throwaway containers.
- Test sign-in over https end to end (Norton intercepts TLS here), Kubernetes probes, real client disconnects: not run.
- The two compliance PDFs' file hash always differed between old and new; not proven that it also differs between two identical reports of one version.
- Early runs on this laptop used Liquibase 4.33.0, so it may have reported usage from this machine to its vendor. Not checked.
- No check of the new versions against published advisories (0051's scans cover it).

## Questions for the owner (I took the recommended one in each)
1. **Liquibase 4.29.2** (recommended), a later 4.x with reporting off, or decide on 5.x's licence? It is an old release, so a later fix would need this decision.
2. **Stop:** immediate as before, or Boot 4's graceful stop? Kept immediate.
3. **JSON nesting limit 500:** accept? (recommended)
4. **TRACE 405 and the header differences:** accept? (recommended)
5. **Signing keys 15 minutes** as before (recommended; sign-in continuity is frozen), or Spring's 5?
6. **Existing, not from this task:** a staged server killed and started again at once fails cases with `STORAGE_IO` on local storage (3 of 4; the old version the same). Started 100 s later all finish. Cause not traced. Its own task? (recommended)

## For the owner
After the merges: `Build-LexaVault-All.ps1`, then `Start-LexaVault-OIDC.ps1 …`, then `Test-LexaVault-OidcSessionContinuity.ps1 -Username businessuser -PromptForPassword` (one run covers 0054 and 0056).

## Suggested next step
Task 0051, with 0055, from this branch.

No other product's name or behaviour in code added; `SdvDeliveryAdapter` (the known exception, planned out in switch-over part 3) got only the Jackson 3 changes.
