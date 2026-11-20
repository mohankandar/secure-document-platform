# Task U022: Compliance report dialog

Written: 2026-11-20, by the design chat. Read `docs/work/ui/README.md` and `docs/work/ui/SPEC.md` first. Build plan step 4 in `docs/work/ui-enhancements-design.md`.

- **Canvas** (match it exactly): https://claude.ai/artifact/WX5CG7jfDhUaFqYgvLLcfj, artboards "Reports with the Compliance report dialog" (Business Administrator) and "Audit trail with the Compliance report dialog" (IT Administrator). The content you need is copied below.
- **Backend:** `POST /api/v1/compliance-reports` (tasks 0017 and 0018).
- **Report:** `docs/work/ui/U022-report.md`.

## Step 0. Merge, and two small backend changes (owner's answers to 0018)

1. On `feature/compliance-followups`, commit the design chat's change to `docs/work/0018-report.md` (the owner's answers). Then merge `feature/compliance-followups` into `feature/lexavault-r1b-tenant-identity` (no fast-forward; standard message). Build and test: 572 backend, 242 frontend. Do not push.
2. Create the new branch `feature/ui-compliance-report` from it.
3. **The full permission also allows the summary.** `COMPLIANCE_REPORT_FULL` may create either edition, so the IT Administrator can untick "Include full audit trail". `COMPLIANCE_REPORT_SUMMARY` still allows only the summary. Update the controller check, the e2e capability copy, and the tests.
4. **The other refusals are recorded too.** The tenant-boundary refusal (a request naming another entity) and `AccessDeniedException`s thrown inside services write a DENIED `AUTHORIZATION_FAILED` record. Use the same detail format as 0018 (`capability=…` may be `none`, then `method`, `route`) plus `reason=TENANT_BOUNDARY` or `reason=SERVICE`. Never record a request twice. Test it.

## What they see (from the canvas)

**Reports page (Business Administrator: `COMPLIANCE_REPORT_SUMMARY`):**
- A dark primary "Compliance report" button to the right of "Export CSV".
- The Report Viewer doesn't see the button.

**Admin › Audit trail (IT Administrator: `COMPLIANCE_REPORT_FULL`):**
- A dark primary "Compliance report" button to the right of "Export".
- Platform Operations doesn't see it.

Hide each button with the same capability check the UI already uses for Export CSV and Export. Never show a button that leads to a 403.

**The dialog** (`role="dialog"`, `aria-modal`, labelled by its title, max-width 560 px, centred near the top over the dimmed page):

1. **Title:** "Compliance report". Under it, in grey: "<entity name> · one entity per report".
2. **Period:** a `fieldset` with the legend "Period".
   - A segmented control with Last month, **Last quarter** (selected by default), Last year and Custom. Use real buttons with `aria-pressed`, at least 40 px tall (44 px on a coarse pointer).
   - Under it, the resulting range in mono grey, for example "Jul 1 – Sep 30, 2026". Work it out in the browser's time zone with the same rules as the server: last complete month, quarter or year.
   - **Custom:** two labelled date fields, "From" and "To", in the same row (they stack on narrow screens). The range must end before today and be 1 to 366 days long. If not, show a plain message under the fields and disable "Create PDF". Examples: "The period must end before today." "The period can be at most 366 days."
3. **What's in it:**
   - **Business Administrator:**
     - the bold label "What's in it", then 4 lines, each with a green ✓ (`aria-hidden`):
       - "Redaction policy as it stood each day, and every change: who, when, old → new"
       - "Keeping and deleting settings, and proof that deletions ran"
       - "Case outcomes and review numbers"
       - "Audit activity, as counts"
     - then in small grey text: "The full audit trail is added by your IT Administrator, from Admin › Audit trail."
   - **IT Administrator:**
     - the bold label, then in grey: "Everything in the summary edition: policy and its changes, keeping and deleting with proof, case outcomes, audit counts.";
     - then a bordered checkbox card, **ticked by default**:
       - bold "Include full audit trail";
       - under it in grey: "Every record in the period, added as an appendix · N records. Cases appear by case ID only.", with N in mono;
       - N is the record count for the chosen period, from the existing audit list API. If the API can't give a total without paging through everything, leave out "· N records" and say so in the report.
4. **Notice:** a yellow box (the existing warning-soft token, not a raw hex) saying "Contains no borrower names, file names, loan numbers or redacted values."
5. **Footer row:** grey "Creating it is recorded in the audit trail." on the left, then **Cancel** (secondary) and **Create PDF** (primary), each 44 px tall.

**Behaviour:**
- **Create PDF:**
  - sends `period`; `from`/`to` (Custom only); `timeZone` (`Intl.DateTimeFormat().resolvedOptions().timeZone`); and `includeFullAuditTrail` (always `false` for the Business Administrator);
  - while it runs, the button reads "Creating…" and is disabled, Cancel is disabled, and the dialog can't be closed;
  - on success, the browser downloads the file under the server's file name (from `Content-Disposition`). Revoke the object URL straight after. The dialog closes, and the page's existing success notice says "Compliance report downloaded.";
  - never keep the PDF, the response, or the file hash in browser storage or app state after the download.
- **Errors**, shown inside the dialog in the existing error style; the dialog stays open:
  - 400: "This period can't be used. Choose another period."
  - 403: "You don't have access to create this report."
  - anything else: "The report couldn't be created. Try again. If it keeps failing, tell your IT Administrator."
- **Closing:**
  - Esc and Cancel close the dialog when it isn't busy;
  - focus moves into the dialog on open, stays inside it, and returns to the "Compliance report" button on close;
  - clicking the dimmed page does nothing, so a click can't lose the form.
- **Tablet (U021 rules):**
  - the dialog must work by touch on Reports at the iPad sizes;
  - the Audit trail page must not break or scroll sideways.

## Checks

- `npm run build` and `npm run test:standalone` pass; give counts. Add tests:
  - the period maths in the browser matches the server's rules, with quarter and year edges;
  - the Custom range checks;
  - which buttons show for each role (Business Admin, Report Viewer, IT Admin, Platform Ops);
  - Create PDF sends the right body;
  - busy, success and error states;
  - focus trap and return;
  - a WebKit tablet test that opens the dialog on Reports and creates a PDF by tap, against a mocked endpoint.
- Backend: the step 0 changes are tested; the full build passes; give counts.
- No raw hex outside `tokens.css`, no new width breakpoint, no new app dependency.
- **Proof** in `compare/U022/` (not committed; the design chat reviews it):
  - the Reports dialog and the Audit trail dialog at 1440, light and dark, next to the canvas;
  - the Custom period with an error;
  - the busy state;
  - an error state;
  - the Reports dialog on iPad portrait and landscape in WebKit.
  - Also one real run against the backend that saves the downloaded PDF to `compare/U022/` and names its file name.
- Commit on `feature/ui-compliance-report`. Do not push or merge.

## Report must include

Outcome; the merge commit; the step 0 results; whether the record count shows; test counts; the list of proof pictures; the commit hash; questions for the owner.
