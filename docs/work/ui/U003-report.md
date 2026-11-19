# Report U003: Cases rows and the case panel (findings 2, 3, 5, 19)

Date: 2026-11-18
Branch: `feature/ui-polish`
Outcome: DONE, with two gaps in the data the API returns today (see "Not done")

## What was done

**Cases rows**
1. **The next step in words** replaces the small bars:
   - "2 findings to review" (or "Waiting for review" for people who cannot see the review queue);
   - "Redaction done, ready to approve";
   - "Finding sensitive data…", "Queued", "Waiting for documents";
   - "Processing stopped", "Not accepted at intake", "Redacted and sent".
   
   The words are in `words.ts`; the rules are in the new pure module `caseRules.ts`.
2. **One action button** at the row's end, only when the person can act. The rules are the same as `caseActions` and `needsYou`:
   - **Review** (yellow, MANUAL_REVIEW with open findings) opens Review for the case.
   - **Approve** (PACKAGE_APPROVE_DELIVERY) asks for confirmation with the same words as the case panel ("Release redacted documents?").
   - **Stopped cases:** Retry (TECHNICAL_RETRY, IT Administrator), otherwise Continue manually (MANUAL_REVIEW). Continue manually first makes the same eligibility check the case panel makes, and shows the reason if it is not possible.
   
   **No double action:** the button sits outside the row's link, so it never also opens the case panel; the rest of the row still opens it.
   
   **Shared code:** the row and the case panel use one action runner and one set of confirmation words (`caseActionRunner.ts`).
3. **Stage chip** per row, in the stage colours from U001.

**Case panel**
1. **Stage bar:** labels are full stage names ("Stopped", "Rejected") and are no longer cut short; each step uses its stage colour.
2. **Stopped:** "Processing stopped", the reason, and "The original is kept until <date>, so the case can still be finished." The primary button follows the role (Retry or Continue manually), as before.
3. **Delivered:**
   - "Delivered to Secure Document Vault" (or "the delivery folder"), with the date and time;
   - "Redacted copy kept here until <date>", and "the original was deleted on schedule" where that applies;
   - "Reprocess retained original" is now **Process again**, with "<n> of <max> runs left" from the lifecycle summary. It is still offered only with PACKAGE_REPROCESS and `canReprocess`, as today (Document Supervisor);
   - a new "Sent to" section names the destination and time.
4. **Rejected:** "Not accepted at intake" and "The source was not accepted. Nothing was processed."
   - **"Start a new case for <primary identifier>"** (DOCUMENT_INTAKE only) opens New case with the primary identifier filled in (`/intake?for=…`).
   - The button names the case's own identifier rather than "this loan", because field names come from configuration.
5. **Section names:**
   - "Keeping and deleting" (was Retention), "Case details" (was Identity and source), "What went wrong";
   - "Export cycles" is now "Runs used";
   - the status code, case ID, failure step and category moved under a collapsed Technical details.
   - **Name clash:** the panel itself is now labelled "Case panel", so its name does not clash with the "Case details" section.

**Found while checking, fixed:** a deliveries answer of the wrong shape crashed the case panel. The error card caught it, as designed in U001, but it should not crash at all. The lifecycle, deliveries and failure-detail hooks now check the shape of their answers, like the other hooks.

## Commands run and results

- **Build and tests:**
  - `npm run build`: passed.
  - `npm run test:standalone`: passed (`test:auth` 8, `test:vocabulary` 10 (words 4 plus row rules 6, new), `test:ui-framework` 17, `test:oidc-signin` 19 (4 new)).
- **Screen checks:**
  - **Local app:** `.\Start-LexaVault-Local.ps1 -ScannerMode SIMULATOR -AiModel qwen3.5:9b`, with the jar built from this branch. The backend is unchanged.
  - **Local app checks:** screenshots in Edge (Playwright), light and dark, at 1440 and 1024 px. Document Supervisor and Document Processor on Cases and a delivered case panel. "Start a new case" opens New case with the primary field (`contract_number`) filled in. No page errors.
  - **Mocked states:** the local data has no case in review, approval, stopped or rejected, so those rows and the stopped, delivered and rejected panels were checked with the mocked test harness, in both themes and both widths, as Document Supervisor.
  - **Stop:** the local app was stopped with `.\Stop-LexaVault-Local.ps1`.
- **Rules:** no raw hex outside `tokens.css`; only documented breakpoints; no new dependency.

## Tests: counts per module, and any failures with their output

- **New `src/studio/caseRules.test.ts` (node, 6):** one test per row action, with role capability sets from the backend policy.
  - Review is shown only with MANUAL_REVIEW and open findings.
  - Approve is shown only with PACKAGE_APPROVE_DELIVERY.
  - Retry is shown with TECHNICAL_RETRY.
  - Continue manually is shown with MANUAL_REVIEW and no TECHNICAL_RETRY.
  - No action is shown when nobody needs to act.
  - The next-step words are correct.
- **New `e2e-oidc/caseRows.spec.ts` (Playwright, mocked API, 4):**
  - each row shows the next step and the one action;
  - the Review button opens Review for the case, not the panel;
  - Approve shows the panel's confirmation, Cancel sends nothing, Confirm approves once, and the panel stays closed;
  - clicking the rest of the row opens the panel.
- **Existing tests:** no existing test changed.

## Files changed

- **New:** `src/studio/caseRules.ts`, `src/studio/caseRules.test.ts`, `src/studio/caseActionRunner.ts`, `e2e-oidc/caseRows.spec.ts`.
- **Changed:**
  - `src/studio/Desk.tsx` (rows), `src/studio/CaseDrawer.tsx` (panel states and sections), `src/studio/Intake.tsx` (`?for=` prefill);
  - `src/studio/data.ts` ("Process again"; shape checks on three hooks), `src/studio/words.ts`, `src/studio/studio.css`;
  - `package.json` (`test:vocabulary` also runs `caseRules.test.ts`).

## Commits (hash and message), or "not committed"

- The commit that adds this work and this report.

Not pushed, not merged.

## Not done, and why

The API does not return some things the design shows. Nothing was invented in their place:
1. **Rejection reason:** no endpoint returns why a source was rejected. The panel says "The source was not accepted. Nothing was processed." as the task asks.
2. **Stop reason and destination in the rows:** the case list (`PackageSummaryResponse`) has no stop reason and no destination. So a stopped row says "Processing stopped" rather than, for example, "Text recognition timed out on one page", and a delivered row says "Redacted and sent" rather than "Sent to Secure Document Vault".
3. **Stop reason in the panel for Processors and Supervisors:** the reason comes from the technical-exception endpoint, which needs TECHNICAL_EXCEPTION_VIEW. Processors and Supervisors see "Automatic processing could not finish this case." The IT Administrator sees the real explanation.

## Questions for the owner

1. **Should the backend return these, so the rows and panel can show the design's words?**
   - a plain stop reason and the delivery destination on each case in the case list;
   - the rejection reason on a rejected case;
   - a plain stop reason that Processors and Supervisors may read.
   
   No schema change is expected for the first and third (the data exists); the rejection reason needs checking. **Recommendation:** one backend task after the UI series, together with the email on `/me`.

**Owner's answer (2026-11-18):** yes, in one backend task after the UI series, together with the email on `/me`. The stop reason shown to Processors and Supervisors must be a plain sentence with no technical detail; the full reason stays visible to the IT Administrator only.

## Suggested next step

U004: Review (the text found with context, zoom, "Redact all like this").
