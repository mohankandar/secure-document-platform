# Report 0031: Live run of the new export against Vero (no code changes)

Date: 2026-11-21
Branch: `feature/0031-live-vero-run`
Outcome: **STOPPED at step 2.** LexaVault cannot reach the real Vero R7 as it stands. Its connection test fails. The adapter's paths and sign-in don't match what Vero serves. Steps 3 to 6 were not run, and no cases were sent. Step 7 is done: everything is back as it was found.

## What was done

1. **Starting state (about 18:25):** Vero backend UP on 8280, Vero UI on 5183, `sdv-postgres` and `sdv-vault` up, Vault unsealed. `securedoc-postgres`, `lexa-keycloak` and `lexa-keycloak-db` stopped. LexaVault not running.
2. LexaVault's backend jar was built before the last 0030 commit, so I rebuilt it from this branch before the run.
3. `docker start securedoc-postgres`, then LexaVault started with `SECUREDOC_PROCESSING_EXPORT_MODE=job` in the process environment only.
4. **Vero was stopped by another session at about 18:31 to 18:36.** That session was working on Vero task 0038 and rebuilt Vero's jar at 18:34. I paused and asked the owner. The owner said the Vero session was finished, and that the LexaVault windows had been closed by accident.
5. 21:21: started Vero with its own `Start-SDV-Local.ps1` (Vero at `4537003`, `release/vero-r7`). The script's Vault preflight passed. I did not open the token file. 21:22: started LexaVault again in job mode.
6. **Step 2 check (21:23 to 21:25):**
   - **Admin › Integrations:** the only connection is "Local Scanner Bridge". **There is no SDV (Vero) delivery connection.**
   - **Support › Export destinations:** only "Local export folder", Working.
   - The local profile sets `securedoc.delivery.type: local` in `application-local.yml:75-76`, so in LOCAL, LexaVault exports to a folder, not to Vero.
   - **LexaVault's own connection test against Vero** (`POST /api/v1/integrations/test-draft`, which saves nothing; HTTP_API, endpoint `http://localhost:8280`, SDV submission acknowledgement, auth NONE): `{"works":false,"reasonCode":"SDV_TEST_UNAVAILABLE"}`. **The connection test fails, so the task's gate for step 3 is not met.**
7. To find out why, I probed Vero directly, signed in as Vero's existing ACTIVE sending system `lexaVault` (Vero local headers). I only read data; no submission was created:

   | Call (LexaVault's path) | Vero's answer |
   |---|---|
   | `GET /api/v1/health` (LexaVault's health check, `SdvDeliveryAdapter.java:86`) | **404 `SDV-SYS-015`** "nothing is at this address" |
   | `POST /api/v1/submissions` (LexaVault's submit path, `SdvDeliveryAdapter.java:36`) | **404 `SDV-SYS-015`** |
   | `GET /actuator/health` (Vero's health) | 200 UP |
   | Any call without Vero sign-in | 401 `SDV-AUT-001` |

8. Step 7: stopped LexaVault, started it again without the job setting (inline), then stopped it again with `Stop-LexaVault-Local.ps1`, and ran `docker stop securedoc-postgres`. LexaVault was not running and `securedoc-postgres` was stopped when I began. Vero was running when I began, so it is left running.

## Anything that didn't match the design (staged-pipeline-design.md section 5, and the task)

1. **LexaVault's Vero paths don't exist in Vero R7.** LexaVault uses `/api/v1/submissions` and `/api/v1/submissions/{id}/documents`. Vero serves `/api/v1/ingestion/submissions` (`SubmissionController.java:24`) and `/api/v1/ingestion/submissions/{id}/documents` (`DocumentIntakeController.java:19`). Proven by a live call: 404 `SDV-SYS-015`. LexaVault maps a 404 to `LV-DLV-004` (refused). So every real export would go to **Waiting for you** as a refusal, when the destination is in fact misaddressed. Expected: the submit reaches Vero.
2. **LexaVault's destination health check calls a path Vero doesn't have** (`/api/v1/health`, 404). Vero's health is `/actuator/health`. Section 5 decides "down or not" by this health check, so against Vero the check fails even while Vero is UP. A connect failure would then pause the destination wrongly, and resume would never come on its own. Expected: the check passes while Vero is UP.
3. **The sign-in methods don't match.** LexaVault's SDV connection offers NONE, BASIC, API_KEY or JWT (`X-API-Key`, Bearer or Basic; `SdvDeliveryAdapter.java:250`). Vero in its local profile accepts only its local headers (`X-SDV-Tenant-Id`, `X-SDV-Subject`, `X-SDV-Source-Id`; `LocalHeaderAuthenticationFilter.java`). Its other mode is `enterprise-jwt`, which needs an issuer set up, and the Keycloak containers are stopped. So even with the right paths, LexaVault can't sign in to Vero on this machine without a Vero configuration change or an issuer setup. Expected: a sign-in both sides support.
4. **No Vero destination exists in LexaVault LOCAL.** The local profile fixes `delivery.type: local`, and no SDV connection is configured. The design chat needs to decide how a live run should point LexaVault at Vero: environment `SECUREDOC_DELIVERY_TYPE=sdv` plus a saved SDV connection with a stored secret reference.
5. **Upload headers differ** (read in the code, not run). Vero requires `X-SDV-Filename` (`DocumentIntakeService.java:592`, "X-SDV-Filename is required."). LexaVault sends `X-Original-File-Name`. A helper's code reading also found `X-Source-Document-Id` versus Vero's `X-SDV-Source-Document-Id`, and body fields `documents[]`/`businessIdentifiers` versus Vero's `expectedDocuments[]`/`metadata`. I did not check those two myself.
6. **Step 5 (refusal), from code reading only, for when 1 to 3 are fixed.** Vero has no "allowed levels per sending system" setting: it accepts all three levels. A refusal a client can cause without changing Vero is the same `sourceSubmissionId` re-created with a different level (409 `SDV-SEN-004`, which LexaVault maps to `LV-DLV-003`). Another is a case with more documents than Vero's per-submission limit (default 100; 400 `BAD_REQUEST`). Not run.

## Commands run and results

| Command | Result |
|---|---|
| `cd backend; mvn -B clean install` | BUILD SUCCESS, 0 failures (counts not captured: my output filter kept only part of the summary) |
| `docker start securedoc-postgres` / later `docker stop securedoc-postgres` | OK |
| `$env:SECUREDOC_PROCESSING_EXPORT_MODE='job'; .\Start-LexaVault-Local.ps1 -ScannerMode SIMULATOR -AiModel qwen3.5:9b` (twice) | LOCAL ready (8080, 5173, scanner bridge) |
| `C:\dev\sdv\secure-document-vault\Start-SDV-Local.ps1` | Vault preflight PASS; backend and UI started |
| `GET /api/v1/integrations`, `GET /api/v1/support/export-destinations` (LexaVault, local headers) | Scanner Bridge only; Local export folder only |
| `POST /api/v1/integrations/test-draft` (Vero at 8280) | `works:false`, `SDV_TEST_UNAVAILABLE` |
| `curl` to Vero as the `lexaVault` source | see the table in step 7 |
| `.\Stop-LexaVault-Local.ps1`, restart without the job setting, stop again | OK |

- **Export workers in job mode:** not confirmed from the log. The log is only in the backend console window, and no export job was ever created. The export endpoints answered the same way in both modes.
- **Times:** Vero UP about 18:25; Vero down by 18:36 (another session); Vero started 21:21; LexaVault in job mode 21:22; connection test failed 21:25; everything restored about 21:27.
- **No pause, resume or cases-out times:** steps 3 and 4 were not reached.

## Tests: counts per module, and any failures with their output
The backend build passed with no failures. No code was changed.

## Files changed
- `docs/work/0031-report.md` (this report).
- No proof pictures: none of the screens in the task (paused destination, Waiting for you, Needs IT) could be reached. `docs/work/compare/0031/` was not created.

## Commits (hash and message), or "not committed"
- One commit on `feature/0031-live-vero-run` with this report only. Not merged, not pushed.

## Not done, and why
- **Steps 3 to 6:** LexaVault's connection test to Vero fails (items 1 to 4 above). The task says to describe the problem and stop that part.
- **Step 5 refusal:** not triggered. Item 6 lists the refusals that could be triggered once the connection works.

## State left behind
- Same as found: Vero backend and UI running, `sdv-postgres` and `sdv-vault` up, Vault unsealed (not touched).
- LexaVault stopped, `securedoc-postgres` stopped, both Keycloak containers stopped.
- No LexaVault connection was saved, no case was sent, and no Vero data was written. The probes recorded only 404 and 401 answers.
- Nothing under `C:\dev\sdv\secrets` was opened. Vero's own start script read its token file.

## Questions for the owner
1. Should LexaVault's SDV adapter be aligned to Vero R7's ingestion API (paths, `X-SDV-*` upload headers, body fields, health on `/actuator/health`)? Or should Vero add the paths LexaVault uses?
2. How should LexaVault sign in to Vero for a live run on this machine: Vero in `enterprise-jwt` with the Keycloak issuer, or a new LexaVault auth type for Vero's local headers (local only)?

## Suggested next step
A fix task for items 1, 2, 3 and 5 (adapter against Vero R7's real API, with a contract test using Vero's paths), plus a local setup for item 4. Then run this task again.
