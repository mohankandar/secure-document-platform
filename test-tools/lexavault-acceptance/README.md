# LexaVault Acceptance Test Harness

Reusable, Kiro-free tooling for LexaVault operational-continuity acceptance testing.

Currently implements **Operational Continuity Acceptance Test 1**:

> attempt 1 → real expired-lease/node-loss recovery → attempt 2 → PENDING_REVIEW →
> Playwright completes Manual Review → processing resumes → `attempt_count` remains **2** →
> package completes/exports.

The test drives the **real** application (API + PostgreSQL + Manual Review UI). It adds **no
production code and no production test endpoints**. The only acceleration is forcing the disposable
package's *lease timestamp* into the past so the genuine recovery scheduler reclaims it quickly;
`attempt_count` itself is only ever changed by the production recovery SQL.

## Prerequisites

Start these before running (the harness manages only the backend jar):

1. **PostgreSQL** container running (default name `securedoc-postgres`, port 5432).
2. **Ollama** running with the local model (e.g. `qwen3.5:4b`) — the backend preloads it on start.
3. **Frontend dev server**: `cd frontend/securedoc-ui; npm run dev` → http://localhost:5173
4. **Backend jar built**: `backend/securedoc-api/target/securedoc-api-0.1.0-SNAPSHOT.jar`
5. **Node + Playwright** installed in `frontend/securedoc-ui` (`@playwright/test`, Chromium).
6. `docker` CLI on PATH (used for DB access via `docker exec … psql`).

The harness **starts and stops the backend itself** so it can simulate node loss deterministically.
Do not have another backend already bound to :8080 when you run it (or pass
`-SkipBackendManagement` if you manage the backend lifecycle yourself).

## Run it

Fast (default, accelerated lease expiry):

```powershell
.\test-tools\lexavault-acceptance\Run-OperationalContinuityTest1.ps1
```

Real, unaccelerated lease expiry (stops backend and waits for the lease to expire on its own,
touching no lease columns):

```powershell
.\test-tools\lexavault-acceptance\Run-OperationalContinuityTest1.ps1 -NaturalLeaseExpiry
```

Retain the disposable package for inspection instead of removing it on PASS:

```powershell
.\test-tools\lexavault-acceptance\Run-OperationalContinuityTest1.ps1 -KeepTestData
```

Exit codes: `0` = PASS, `1` = FAIL, `3` = BLOCKED.

### Switches

| Switch | Effect |
| --- | --- |
| `-NaturalLeaseExpiry` | Stop the backend and wait for the real lease to expire (no lease-column writes). Default accelerates by pushing the lease timestamp into the past; `attempt_count` is never set by the harness in either mode. |
| `-KeepTestData` | Keep the disposable package after the run. Default removes it on PASS via `Remove-TestPackage.ps1`; FAIL/BLOCKED always retains it for investigation. |
| `-SkipBackendManagement` | Do not start/stop the backend jar (diagnostics only; incompatible with the node-loss step). |

### Prerequisites, config, and identifiers are centralized

- `helpers/Common-Config.ps1` resolves all endpoints/paths (`Get-LvConfig`), validates prerequisites
  (`Test-LvPrerequisites`: PowerShell 5.1, Docker + `securedoc-postgres`, frontend :5173, backend
  :8080, Ollama :11434, Java 21, Tesseract from env with local fallback, jar + fixture present), and
  produces API-valid intake identifiers (`New-LvTestIdentifiers`) without rediscovering them from the
  DB — contract_number 12 digits, member_number 11 digits, service_entity in `000..005`.

### Logging

Each run writes a full transcript to `evidence/<timestamp>/run.log` (via `Start-Transcript`) plus
`result.json`, `backend.out.log`, and `backend.err.log`. Evidence never depends on terminal buffers.

## Configuration (environment variables)

All have local/demo defaults; override only if your environment differs.

| Variable | Default |
| --- | --- |
| `SECUREDOC_BACKEND_URL` | `http://localhost:8080` |
| `SECUREDOC_FRONTEND_URL` | `http://localhost:5173` |
| `SECUREDOC_TENANT_ID` | `00000000-0000-0000-0000-000000000001` |
| `SECUREDOC_ACTOR_ID` | `00000000-0000-0000-0000-000000000099` |
| `SECUREDOC_DB_CONTAINER` | `securedoc-postgres` |
| `SECUREDOC_DB_NAME` | `securedoc` |
| `SECUREDOC_DB_USER` | `securedoc` |
| `SECUREDOC_OCR_TESSERACT_PATH` | (optional) tesseract.exe path for the backend |

No passwords or secrets are embedded. DB access goes through the running container's
`psql` via `docker exec`, so no local psql client is required.

## What gets created

- A single **disposable** package per run (unique `contract_number`), the only data the harness
  mutates. It never deletes unrelated/historical data.
- Per-run **evidence** under `evidence/<timestamp>/`: backend logs, Playwright artifacts (on
  failure), and `result.json`. This folder is git-ignored.

## Files

```
test-tools/lexavault-acceptance/
  Run-OperationalContinuityTest1.ps1     # top-level orchestrator (the one command to run)
  helpers/
    Common-Config.ps1                    # centralized config + prereq validation + identifier factory
    Invoke-TestDbQuery.ps1               # docker exec psql query runner (StrictMode-safe arrays)
    Get-PackageProcessingLease.ps1       # read package status + lease (attempt_count, fencing token)
    Wait-ForPackageState.ps1             # poll until status/attempt_count reached (fail-fast timeout)
    Invoke-TestApi.ps1                   # REST wrapper with LOCAL identity headers
    Send-TestDocument.ps1                # PS 5.1-safe multipart PDF upload (HttpClient)
    Set-TestReviewPolicy.ps1             # capture original policy + set ReviewMode
    Restore-TestReviewPolicy.ps1         # restore captured policy (called in finally)
    Expire-PackageLease.ps1              # expire ONE package's lease timestamps (never attempt_count)
    Remove-TestPackage.ps1               # FK-safe delete of ONE disposable package (transactional)

frontend/securedoc-ui/e2e/operational-continuity/
    recovery-review-preserves-attempt.spec.ts   # real Manual Review UI workflow
```
