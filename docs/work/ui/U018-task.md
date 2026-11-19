# Task U018: final fixes, unused-file list and the pre-merge checks

Written: 2026-11-18, by the design chat. Read `docs/work/ui/README.md` first. This is the last UI task before the owner approves merging `feature/ui-polish`.

## Owner's answers to U017 (record them in U017-report.md under "Owner's answers" first, then commit)

1. **Retry for the IT Administrator** is from Support (the problem page) only. The IT Administrator never sees Cases. `WhoSeesWhat.html` was wrong.
2. **Correct the e2e tests' capability lists** to exactly what `ProductAuthorizationPolicy` grants each role.
3. **Add the "AI off" switch to the start script now,** so the live U004 check can run before merging.

## Do

1. **Spec:** in `SPEC.md`'s who-sees-what, say Retry for the IT Administrator is from Support only. Leave `designs/*.html` unchanged.
2. **Tests:** in `adminAudit`, `adminIntegrations`, `adminPlatform`, `adminSignIn` and any other e2e spec, use the policy's exact capability lists per role, ideally from one shared helper. If a test then fails, that may be a real permission bug: **stop and report it**, and don't loosen the test.
3. **Start script** (`Start-LexaVault-Local.ps1`):
   - add a switch that starts the backend with the AI off, using the existing AI mode setting;
   - also fix the false "Frontend did not become ready on localhost:5173" stop seen since U004.
   
   Script only. If turning the AI off needs a backend code change, stop and ask.
4. **Unused files:** across everything U001–U017 changed, list what is now unused (images, icons, CSS classes, components, `words.ts` entries, test helpers) under "Unused, proposed for deletion", with proof for each (README rule). **Do not delete.** The legacy pages are out of scope.
5. **Pre-merge checks.** Run each one and report the result:
   - backend `mvn clean install` and frontend `npm run build` and `npm run test:standalone`, with counts;
   - Phase 1: `Invoke-Phase1Checks.ps1`, and `Invoke-Phase1BrowserSignin.mjs` in **both** `--mode dev` and `--mode prod`;
   - **Live U004 check, with the AI off:**
     - open fixture 401 through New case under a new fake number starting 9999;
     - as Document Processor, the found text shows in the decision panel and is masked in the queue;
     - then search the backend logs and the audit records for that fake value. It must appear **nowhere**. Print only the number of matches, never the value.
   - stop the local app afterwards with `Stop-LexaVault-Local.ps1`.
6. **The owner's click-through list:** end the report with 8–10 screens and roles for the owner to open in the local app (for example "Cases as Document Supervisor", "Review as Document Processor", "Admin › Integrations as IT Administrator"), each with one thing to look at.

## Checks

- No raw hex outside `tokens.css`; only documented breakpoints; no new dependency.
- Commit on `feature/ui-polish`. **Do not push or merge.**
