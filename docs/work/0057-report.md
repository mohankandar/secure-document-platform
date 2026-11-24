# Report 0057: platform-core v1.0.0, part 1

Date: 2026-11-24
Branch: `feature/0057-platform-v1-part1`, from 0056's branch (merged since)
Outcome: **DONE** (8 questions). Built and tested by a subagent from a written brief; I read the licence gate, the metering, the watermark, the settings and the migration, and looked at three proof pictures (marked page 1, Licensing, Support status). **The commands below were run by the subagent, not repeated by me** (queue rule 9); CI repeats the backend and frontend suites.

## What was done
1. **platform-core 1.0.0.**
2. **Licensing is enforced.** Making a case, adding a document, submitting, processing a finished case again, polling and the watched folder are refused when the licence says so; push and scanner go through the same services. Nothing is half-made: a watched-folder drop waits, a poll does not call its source. Submitted work goes on (processing, review, export, technical retry). A licence that can't be read for 2 minutes, or an entity without a slot in it, counts as "no".
   - Metering: one row per document, in one transaction with the ledger entry. If the licence can't be read, the pages wait and are put in later.
   - The licensing events are registered; nothing is sent. Admin > Licensing (Business Admin, read-only).
3. **Watermark** for NON_PRODUCTION only: drawn lines (no font, no text), diagonal, clipped 2 pt around every redaction box; `NonProduction true` in the file's information. Drawn before the output is cleaned; the final check reads the marked file. Without it the output is byte-identical (tested).
4. **Codes:** error answers gain `reference`, `whatToDo`, `details.platformCode`. No code renamed.
5. **Monitoring:** checks for database, licence, login system, model server, storage; start-up refusal; Support > Status; diagnostic bundle (IT Admin, audited); references on failures.
6. **Own licensing removed** (code, endpoints, screens, settings, test-licence generators). The `tenant_license_*` tables stay, unused.
7. **Guides:** `docs/install/LICENSING.md`, `MONITORING.md`; SETUP, BUILD, SCHEMA_STEP updated. Pictures: `docs/work/0057-proof/`.

**Dependencies:** platform-licensing-spring, platform-monitoring, platform-monitoring-spring, platform-notifications (types only; its mail library is excluded). No new third-party library.
**Migration:** 060, table `licence_metered_documents` (ids, page counts, times). 037 untouched.

## Commands run and results (subagent)
| Command | Result |
|---|---|
| `mvn -B -Pdev-build clean install`, PostgreSQL tests on, at `66eeae6` | SUCCESS: domain 80, application 299, scan bridge 136, infrastructure 113, API 762 (1 skipped, the timing test), 0 failures |
| Production build with the start, licence, demo and meter tests | SUCCESS, 31 tests |
| `Build-LexaVault-All.ps1 -SkipTests`, `.sh --skip-tests --production` | pass, jar checks included |
| `npm run test:standalone`, 1 run | 0 failures: 10, 199, 17, 169, 95, 160 |
| No-redacted-value check (marked outputs) | PASS, 0 matches |
| Compliance report check | PASS, 115 checks |
| Product-name guard | clean (1707 files) |
| Fresh LOCAL; fresh OIDC (throwaway Keycloak); a database made by the 0056 jar, LOCAL then OIDC | all start; 98 to 99 change sets, old licence tables kept |
| OIDC roles | Business Admin: licensing 200, status and bundle 403. IT Admin: licensing 403, status and bundle 200. No token: 401 |

**Left for milestone M1:** two-server proof, export end-to-end, repeats, staged against inline end to end.

## Failures met
- The ledger's hash chain broke on times finer than a microsecond. LexaVault now records whole microseconds. **For platform-core:** the subagent saw no truncation in its ledger code, so any app could meet this.
- Commit `8357778` was made with 5 migration test classes not yet adjusted (4 red, rollback counts; fixed in `1ea44be`), and **`ce10f7e` and `3c9bb94` do not compile** (fixed in `66eeae6`). That breaks the proof-before-commit rule. They were not pushed before the fix; history is left as it is.
- Licensing notice failed the contrast test; now a neutral notice.

## Existing tests changed
- Removed with their code: `TenantLicensingServiceTest`, `Ed25519LicenseTokenVerifierTest`, one case of the AI-pool logging test (now `AiPoolLoggingTest`).
- `WatchedFolderIntakeServiceRecoveryTest`: "a refused drop is rejected" replaced by 2 tests, because a drop now waits.
- `RefusalAuditTransactionTest`, `ReprocessPackageServiceTest`, 5 pipeline tests: new type or one argument less. 5 migration classes: rollback counts +1. `FreshDatabaseStartPostgresTest`: test keys, extended. 4 frontend specs: Licensing left Platform operations. Compliance script: customer name from the demo licence.

## Behaviour and default changes
- **Licensing is on everywhere, LOCAL included** (it was off there). The app does not start without `platform.licensing.tenant-id` and a valid stored licence. The local profile uses `TEN-DEMO-0001`, so the start scripts need no change.
- **The demo's outputs are now marked.** The compliance report shows the licence's customer name.
- **The old demo entity `0000...0001` can't take in new cases** (no slot); viewing works.
- Platform operations' Licensing section and API, and the `SECUREDOC_LICENSING_*` settings, are gone.
- LV-LIC-001 to 004: description, who acts (IT Admin) and steps changed. Every answer has `X-Request-Id`. `/actuator/health` is not UP while a required check is down, and shows no details.

## Not done or not proven
- Compliance report PDFs are not marked (they are not redacted copies).
- The model check covers the default model, not per-entity AI profiles.
- Background incidents' references are stored, not shown on the problem page.
- The meter's shared transaction is tested with a plain JDBC manager; under JPA it was only exercised by the checks above.
- An error inside the ledger undoes that document's save, so it is retried rather than left uncounted. Not tested in the running app.
- The Phase 1 environment was not run: it needs a licence and a customer ID first.
- `Platform-Switch-Over-Guide.md` is not on this machine; items 5, 9 and 10 were not read.

## Questions for the owner (the recommended answer was taken)
1. Entity not in the licence: refuse intake (recommended), or allow and count later?
2. Drafts and half-received pushes while intake is stopped: refuse until renewal (recommended), or let opened cases finish?
3. Model server required at start? No (recommended): otherwise an AI outage also stops restarts and viewing.
4. Storage required at start, except in the local profile; a full disk only warns. Accept? (recommended)
5. `tenant_license_*` tables: drop in a later migration? (recommended, after you confirm nothing in them is needed)
6. LV-LIC "who acts" is the IT Admin. Accept? (recommended)
7. The warning "database user owns tables" shows on every current install until the app's own tables move to the schema step. Accept until then? (recommended)
8. platform-notifications for the event types only. Accept? (recommended; 0059 needs it)

## For the owner
After the merge: `Build-LexaVault-All.ps1`, then start as usual. Expected: migration 060 runs once, Admin > Licensing shows the demo licence, and redacted copies carry the mark.

## Commits
`106d302`, `8357778`, `1ea44be`, `ce10f7e`, `3c9bb94`, `66eeae6`, and the report commit.

## Suggested next step
Task 0058.

No other product's name or behaviour in code.
