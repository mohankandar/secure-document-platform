# Report 0116: outages wait, they never stop a case (phase 1: the AI servers)

Date: 2026-11-28
Branch: `feature/0116-outages-wait`, stacked on `feature/0119-reviewed-page-stays` (merged as #59 meanwhile)
Outcome: **DONE, phase 1** (PR to follow on green CI)

## What changed (phase 1, the AI servers only)
- **An AI outage makes the work wait** (`AiServerOutage`, staged pipeline):
  - When a FIND, VISION or REDACT step fails as an outage does (can't connect, timeout, busy: AI_UNAVAILABLE, AI_TIMEOUT, AI_RATE_LIMITED, or a vision page not read with LV-AI-001/002/003), LexaVault asks the AI servers' own check, the same one monitoring uses (the text model's servers and the vision model's).
  - **Servers down (kind A):** the step's work is not kept (its transaction rolls back), the job waits (`WAITING_RETRY`, detail `WAITING_FOR_AI_SERVER`, code LV-AI-001) **without using a try**, the document is not failed, no incident is recorded and the case is never Stopped. While the outage lasts, **no FIND, VISION or REDACT job is taken for any case**. The workers' maintenance probes the servers once a minute (`securedoc.ai.outage.probe-seconds`, default 60); when they answer, the outage ends and every waiting job goes on by itself.
  - **Servers working:** the failure is this document's, handled as before (kind B or C).
- **This reuses the destination pause's approach** (pause on a failed check, no attempt used, probe, resume by itself, one alert). The destination pause itself is stored per entity and per connection, and the AI servers are one installation-wide dependency. So the outage state is held by each server in memory, like its workers.
- **One alert per outage:** the existing "AI model down" notification (raised from the model server's health check) now goes out after **15 minutes**, then every 4 hours, and once as Recovered when it ends.
- **Cases banner:** "Processing paused since 21:40: AI server unreachable · 37 cases waiting. Cases go on by themselves when it is back." It comes from the new `GET /api/v1/operations/processing-pause`, which needs the Cases capability and returns the time, a reason code and the entity's own count.

## Tests
- `AiServerOutageTest` (4): only an outage kind of failure can be one; servers working → the document's failure, nothing waits; down → one outage, not asked again per failure, a probe at most once per interval, back → ends; nothing waits without a model server.
- `StagedPipelinePostgresTest.anAiServerOutageMakesTheWorkWaitAndItGoesOnByItselfWhenTheServerIsBack`:
  - the AI server is down mid-run; the FIND job waits with the same attempts;
  - no document failed, no incident, the case is not Stopped, the case counts as waiting;
  - no FIND job is taken for any case while down;
  - server back → probe → FIND and REDACT run, every document is PROCESSED, the case is Ready for export.
- A bad page with the server working still fails as before (`aFailingStepFailsItsDocumentAndTheGateFailsTheCaseAsInline`, unchanged). The whole class: 26/26.
- Frontend `words.test.ts` +1 (the banner text).
- Runs:
  - the full backend `mvn -o -Pdev-build verify` with PostgreSQL tests on a throwaway container: domain 91, application 414, scan bridge 144, infrastructure 216 (2 skipped), API 1003 (5 skipped): 0 failed;
  - frontend `tsc --noEmit`, `npm run test:standalone`: vocabulary 253, ui-framework 19, OIDC 237, tablet 110, a11y 386 passed.
- Not run: the 0106 round with Ollama stopped for 10 minutes. It needs the real models for about 25 minutes, and is listed for the owner's test (TO-TEST).

## Not done in phase 1 (listed as asked)
- **Outage state shared across servers.** Today each server finds the outage itself and pauses its own workers; the alert is per server's health check, as before. A shared table would be a schema change.
- **Inline mode** (pipeline-mode inline) still fails a document on an AI outage.
- **The owner's three alert steps** (warning 15 min, urgent 1 h, critical 1 day) as IT settings. The platform's condition timing has a first alert and a repeat, so: first at 15 min, then every 4 h. Settable steps need platform support or a phase 2 screen.
- A REDACT run already running when the outage starts: its final check holds an unreadable picture page for a person (kind B by mistake) instead of waiting.
- The database and storage outages, the diagnostic bundle for kind C, the "With IT" changes on Cases and Reports, and the 0112 guard re-queuing once. These are later phases, as the task says.

## Questions
1. **Alert timing:** first after 15 minutes, then every 4 hours, and Recovered. Recommended until the three steps are settable.

No other product's name or behaviour in code.
