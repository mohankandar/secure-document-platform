# Report U012: Support, problem detail and Continue manually (findings 9, 10, 30)

Date: 2026-11-18
Branch: feature/ui-polish
Outcome: DONE, as the owner approved: new Case File screens, routed in the Case File app only. The legacy pages, `App.tsx` and the contract test are unchanged. No backend or schema change was needed. Two things the records don't hold (the borrower name for support, the stop reason for Continue manually) are listed under "Not done".

## Ask first: the UI framework contract

### Where the four pages are used today

- `SupportIncidentsPage`, `SupportIncidentDetailPage`, `TechnicalIssueDetailPage` and `ManualFallbackPage` were routed twice:
  - in the legacy app (`App.tsx`), which is still the default (the Case File screens show only with `VITE_CASE_FILE_UI_ENABLED=true`);
  - in the Case File app (`CaseFileApp.tsx`), which reused the same legacy pages.
- All four are in the contract test's `routePages` list (`src/security/uiFrameworkContract.test.ts`). The rule for them is "must use `<PageScaffold>` and must not hand-roll `Breadcrumbs`/`PageHeader`".

### What was proposed (no contract rule changes)

Build new Case File screens and leave the legacy pages alone, as U003 to U011 did for the other screens. The four Case File routes point at the new screens; the legacy app keeps the old pages. The other way (rewriting the legacy pages) was not proposed: it would have changed the contract and the legacy app.

## Owner's answers (2026-11-18)

1. **Yes, as proposed:** new Case File screens, routed in the Case File app only. The old pages, `App.tsx` and the contract test stay unchanged.
   - Roles as today: Support and problem detail for IT Administrator and Platform Operations; Retry for IT Administrator only; Continue manually for Document Processor and Supervisor. A test for each.
   - If a backend or schema change turns out to be needed, stop and ask.
   - Removing the old pages is a later clean-up.

## What was done

### Support list (`/support/incidents`)

- **Rows like Cases**, one per problem:
  - the case's primary identifier, and where it stopped ("At text recognition");
  - what happened in words, with a short note: "Text recognition timed out · tried 3 times", "Secure Document Vault didn't answer · case continued manually";
  - an impact chip: Case stopped (red), Continued manually (purple), Case finished (green);
  - the status: Open, Watching (the case went on manually) or Resolved;
  - last seen ("3h ago") and Open.
  - A red edge marks rows that still block a case. The page line says "Processing problems in <entity> · 1 is blocking a case".
- **Toolbar:** chips Open / Resolved / All with counts (Open is the default), and one search box. The placeholder uses the configured primary field's name ("Loan number or incident number"). An incident number (UUID) is looked up exactly; anything else matches the primary identifier.
- **More filters** (closed by default, shows how many are in use): problem status, where it stopped, reason, continued manually, can be retried, reference, case ID, document ID, seen from/to. **Organization ID** is shown to Platform Operations only, as before: problems in another organization can be read, not acted on.
- **How the chips count:** the server filters by one status at a time, so the list asks for the latest 100 problems (the server's limit) and the chips count those. If there are more, the page says so: "The latest 100 of N problems are counted and shown. Narrow the search to see older ones."

### One problem page (`/support/incidents/:id` and `/technical-exceptions/:caseId`)

- Replaces Incident Details and Technical Issue Details. A stopped case's problem link opens the same page; when the case has a support incident, it shows that incident.
- **Header:** breadcrumb "Support › LN204860", the title in plain words ("Text recognition timed out"), the impact chip and "Problem: Open".
- **Three blocks:**
  - **What happened**, for example: "Text recognition didn't finish within the allowed time. It stopped at text recognition. LexaVault tried 3 times, the last at Nov 18, 2:15 PM."
  - **What it means for the case**, with the keep-until date, for example: "The case is stopped. Nothing was sent. The original is kept until Nov 20, 5:15 PM; after that the case can't be finished." Other cases have their own sentences: original deleted, continued manually, finished.
  - **What you can do:**
    - Retry processing (with a confirmation).
    - "A Document Processor or Supervisor can also finish it with 'Continue manually' from the case."
    - Keep the original 7 more days.
    - **More actions**, closed by default. These were all on the old page and are kept: keep 14 or 30 days, stop keeping it longer, close without processing again, close as replaced, close as duplicate. The closing ones ask for a confirmation.
    - Without any of these actions, the block says why: closed; another organization; or "Retry and keeping the original are for IT Administrators. Send them the reference under Technical details."
- **Timeline:** the latest 6 steps in words ("Try 1 failed: text recognition timed out", "No tries left; case stopped and support told"), with a count of earlier steps.
- **Technical details** (collapsed): every code and id, unchanged:
  - the incident, reason code, stage, component, category, statuses, attempts, reference, case, document and organization;
  - the times, the original's state, the server summary;
  - the processing fingerprint and diagnostic fields;
  - each recorded step.

### Continue manually (`/packages/:caseId/manual-processing`)

- Case File look, readable in dark (the old page had dark labels on dark). Breadcrumb "Cases › LN204860 · Priya Natarajan", the title and the Stopped chip.
- The banner reads: "Automatic processing stopped. Mark every sensitive value by hand. Your marks are still checked, and the case still goes through approval before delivery."
- Three columns:
  - **Pages:** "3 pages · 1 marked", one button per document if there are several, and page pictures with their mark count.
  - **The page:** Review's viewer with zoom (−, %, +, Fit). Drag a box on the page; saved marks show as redaction boxes.
  - **Mark a value:** "What is it?" (type names from the vocabulary: "Social Security Number", "Date of birth"), Add mark, the marks on this page with Remove, a normal-size checkbox "I checked every page and marked all sensitive values." and "Finish and send to approval".
- The server calls are the same as before. Finishing opens the case again, with a toast.

### Reason codes in words (U001 vocabulary module, `src/studio/words.ts`)

- `problemReason(code)` covers every `TechnicalFailureCode`, plus the task's `SDV_UNAVAILABLE` and `MODEL_TIMEOUT`:
  - `OCR_TIMEOUT` reads "Text recognition timed out";
  - `SDV_UNAVAILABLE` reads "Secure Document Vault didn't answer";
  - `MODEL_TIMEOUT` and `AI_TIMEOUT` read "AI model didn't answer in time".
- Unknown codes read "Processing problem", with the code under Technical details.
- `processingStageWords(stage)` covers every `ProcessingStage`.

### Roles (as today)

- Support and the problem page: IT Administrator and Platform Operations (`TECHNICAL_EXCEPTION_VIEW`).
- Retry: IT Administrator only (`TECHNICAL_RETRY`).
- Keep and close actions: IT Administrator only. These need `TECHNICAL_HOLD_MANAGE`, which only IT Administrator has (as on the old page). So Platform Operations sees neither.
- Continue manually: Document Processor and Document Supervisor (`MANUAL_REVIEW`).
- The server still checks every action.

## Commands run and results

- `npm run build` (frontend): passed (run twice, the second time after the last CSS fix).
- `npm run test:standalone`: passed both times:
  - `test:auth` 8;
  - `test:vocabulary` 75 (7 new);
  - `test:ui-framework` 17;
  - `test:oidc-signin` 65 (6 new).
- **Local app:**
  - Started with `.\Start-LexaVault-Local.ps1 -ScannerMode SIMULATOR -AiModel qwen3.5:9b`. "Frontend did not become ready" appeared again, as in U007 to U011; both apps answered (200).
  - The dev data has **no support incidents and no stopped cases** (5 cases: 4 delivered, 1 draft), so on the real app only the Support list could be checked:
    - as **IT Administrator** and **Platform Operations Administrator** (local test roles), light and dark, 1440 and 1024 px;
    - it shows the empty state ("No open problems"), with no failed API response and no sideways scroll;
    - **Document Processor** and **Document Supervisor** have no Support link.
  - Nothing was changed on the dev data; no failure was forced. Stopped with `.\Stop-LexaVault-Local.ps1`.
- **Problem page and Continue manually:** checked in Edge (Playwright) against the mocked API from the new spec, in light and dark at 1440 and 1024 px:
  - as IT Administrator: the list, More filters, and the problem page with More actions and Technical details open;
  - as Platform Operations: the problem page;
  - as Document Processor and Document Supervisor: Continue manually with a mark added and a box being drawn.
  - No sideways scroll on any of the 24 views.
  - Two fixes came from the screenshots:
    - the breadcrumb came out in capitals (the shared eyebrow style won);
    - the Technical details labels were cramped.
- **Rules:**
  - No raw hex.
  - Only documented breakpoints (1200, 1024, 768).
  - No new dependency.
  - `git diff` on `src/pages`, `src/App.tsx`, `src/security` and `src/index.css` is empty.

## Tests: counts per module, and any failures with their output

- **New `src/studio/support/supportRules.test.ts` (node, 7):**
  - reason codes and stage words, unknown codes;
  - impact chips and status words;
  - list chips, counts, row words, search, placeholder, "3h ago";
  - the problem page from an incident: the three blocks, the timeline, every code in Technical details;
  - the problem page from a stopped case without an incident;
  - **roles:**
    - IT Administrator gets Retry and Keep;
    - Platform Operations gets neither;
    - another organization: none;
    - closed or no longer stopped: none;
    - without an incident: Retry only when the server allows it;
  - Continue manually for Processor and Supervisor only, and Finish waits for the checkbox.
- **New `e2e-oidc/support.spec.ts` (Playwright, mocked API, 6), one per role rule:**
  - IT Administrator, Support list:
    - rows in words with no codes on screen, chips with counts, and that the list asks for 100;
    - the search sends the loan number or the incident number;
    - More filters "Where it stopped" reaches the server, and there is no Organization ID field.
  - IT Administrator, problem page:
    - the three blocks and the timeline, with codes only under Technical details;
    - "Keep the original 7 more days" sends `PRESERVE_FOR_INVESTIGATION`, 7 days;
    - Retry, after its confirmation, sends `REPROCESS`;
    - a closing action can be cancelled.
  - IT Administrator: a stopped case's `/technical-exceptions/<case>` link opens the same page, with Retry.
  - Platform Operations: Organization ID is offered under More filters; the problem page has no buttons in "What you can do" and says why, and nothing is posted.
  - Document Processor, from the case's Continue manually:
    - the breadcrumb, banner, pages line and type names;
    - drawing a box and adding a mark;
    - the checkbox is under 24 px;
    - Finish sends `reviewAttested: true` and goes back to the case.
  - Document Supervisor: the same, and Support is not offered (the route sends them home).
- **Failures during the work (my own test, first run):**
  - Platform Operations: the test landed on Admin › Platform operations first. The mock answered `{}` there, and that screen logged a render error. That screen isn't part of this task; the test now answers its calls with 503, so it shows its error card.
  - Processor and Supervisor: the Cases row checks whether Continue manually is allowed before it starts, and the mock didn't answer that call. Added.
  - After both mock fixes, all 6 passed. No product code was changed for these failures.

## Files changed

- `frontend/securedoc-ui/src/studio/support/SupportList.tsx` (new), `ProblemDetail.tsx` (new), `ManualCase.tsx` (new), `support.css` (new), `supportRules.ts` (new), `supportRules.test.ts` (new)
- `frontend/securedoc-ui/src/studio/words.ts` (reason codes and stage words)
- `frontend/securedoc-ui/src/studio/CaseFileApp.tsx` (the four routes point at the new screens)
- `frontend/securedoc-ui/e2e-oidc/support.spec.ts` (new)
- `frontend/securedoc-ui/package.json` (the new test file added to `test:vocabulary`; no dependency change)
- `docs/work/ui/U012-report.md`

## Commits (hash and message), or "not committed"

- The work commit includes this report: "feat(ui): Support, one problem page and Continue manually in the Case File look (UI task U012)". Its hash is in the chat summary.

## Not done, and why

- **Borrower name on Support and the problem page:**
  - Support records carry only the case's primary identifier. IT Administrator and Platform Operations can't open the case itself (it needs `DASHBOARD_ACCESS`).
  - So the rows show the loan number and where it stopped, not the name (the design shows "Priya Natarajan · TX").
  - The file name and page number ("page 14 of Title_Commitment.pdf") aren't available either. The page number appears only if the server put it in the diagnostic fields, under Technical details.
- **Stop reason on Continue manually:** Processors and Supervisors can't read the technical problem (it needs `TECHNICAL_EXCEPTION_VIEW`). So the banner says "Automatic processing stopped", without "text recognition timed out on page 14".
- **"Case received from the watched folder"** in the design's timeline is not a support step. The timeline shows only the recorded problem steps.
- Removing the old pages: a later clean-up (owner's answer).

## Questions for the owner

1. For the backend work after the UI series: should support records also carry the case's other displayable fields (the borrower name) and the failed file name? Should Processors and Supervisors get the plain stop reason for their stopped case? Both are likely read-only additions to existing answers, not schema changes. I have not started them.

## Suggested next step

U013.
