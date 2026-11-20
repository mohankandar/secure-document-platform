# Report U022: Compliance report dialog

Date: 2026-11-20
Branch: feature/ui-compliance-report (from feature/lexavault-r1b-tenant-identity after the step 0 merge)
Outcome: DONE. One item from the canvas left out as the task allows (the record count, see below).

## Step 0

| # | Item | Result |
|---|---|---|
| 1 | Owner's answers | Committed on `feature/compliance-followups` as `85deafa` (docs(work): 0018 report: owner's answers). |
| 1 | Merge | `feature/compliance-followups` merged into `feature/lexavault-r1b-tenant-identity`, no fast-forward, standard message: **`aef363d`**. Built and tested on the merge: 572 backend (51 / 205 / 47 / 14 / 255), 242 frontend (10 / 91 / 17 / 69 / 55). Not pushed. |
| 2 | Branch | `feature/ui-compliance-report` created from `aef363d`. |
| 3 | Full permission allows the summary | `ComplianceReportController`: `COMPLIANCE_REPORT_FULL` may create either edition; `COMPLIANCE_REPORT_SUMMARY` only the summary. No role gets a new capability. The role printed on the report follows the person (an IT Administrator's summary says "IT Administrator"). The e2e capability copy (`e2e-oidc/policyCapabilities.ts`) is unchanged in content, since the policy did not change; its header now says how the two capabilities are checked. Tests: the role × edition matrix in `ComplianceReportApiTest` (IT Admin summary now allowed), a new test that the IT Admin's summary has no appendix and is signed "IT Administrator". The 0017/0018 check script is updated the same way (3 allowed, 9 refused). |
| 4 | The other refusals are recorded | New `AccessDeniedAuditRecorder`, called from the existing 403 handler for `AccessDeniedException`. It writes a DENIED `AUTHORIZATION_FAILED` record, resource type `ApiRoute`, with detail `capability=none, method=<METHOD>, route=<route pattern>, reason=TENANT_BOUNDARY` or `reason=SERVICE`. Tenant-boundary refusals now throw `TenantBoundaryDeniedException` (a subclass): the tenant guard (entity not provisioned or not active) and the two cross-entity refusals in Support. Everything else thrown as `AccessDeniedException` is `SERVICE`. |
| 4 | Never twice | Whoever writes the DENIED record marks the request; a marked request is not recorded again. The capability check and the compliance endpoint (which writes its own refused `COMPLIANCE_REPORT_CREATED`) both mark it. |
| 4 | Fail-safe | If the caller can't be identified, or the audit store fails, nothing is recorded and the answer is still 403 (a warning line with the exception class only). |
| 4 | Tests | `AccessDeniedAuditRecorderTest` (7): tenant boundary through the real guard, a request naming another entity, a service refusal, a capability refusal recorded once even when the handler also sees it, the same request handled twice, the machine client on `/external-intakes`, and no identity or a broken store. Live check (script): a request for an unprovisioned entity gets 403 and exactly one record `DENIED capability=none, method=GET, route=/api/v1/packages, reason=TENANT_BOUNDARY`. |

Not covered by step 4: `SecurityContextException` (sign-in/identity refusals before a person is known) is a different 403 and is still not recorded; there is no person or entity to record it against.

## What was built (UI)

- **Buttons.** Reports: a dark "Compliance report" button right of "Export CSV", for `COMPLIANCE_REPORT_SUMMARY` (Business Administrator). Admin › Audit trail: right of "Export", for `COMPLIANCE_REPORT_FULL` (IT Administrator). Both use a plain `caps.includes(...)` check, like Export CSV. Report Viewer and Platform Operations see no button. On the Audit trail, Export and the button now sit together at the right of the toolbar (they used to be one item).
- **Dialog** (`src/studio/compliance/`), as on the canvas: title, "<entity> · one entity per report", the Period fieldset with four `aria-pressed` buttons (Last quarter chosen), the range in mono grey, the summary list with green ✓ or the IT Administrator's sentence and the "Include full audit trail" card (ticked), the yellow notice, the footer with Cancel and Create PDF (44 px).
- **Period maths** in the browser's time zone, same rules as `CompliancePeriod.java`. Custom: From and To in one row (stacked under 640 px). The checks, in the server's order: "Choose a start and an end date.", "The end date must be on or after the start date.", "The period must end before today.", "The period can be at most 366 days." Create PDF is disabled while one shows.
- **Create PDF** sends `period`, `from`/`to` (Custom only), `timeZone` and `includeFullAuditTrail` (always false in the summary edition). While busy: "Creating…", every control disabled, Esc does nothing. On success the file is saved under the server's file name from `Content-Disposition` (a name with a path, a control character or not ending `.pdf` falls back to `compliance-report.pdf`), the object URL is revoked straight away, the dialog closes and the toast says "Compliance report downloaded.". The PDF, response and hash are local variables only; nothing goes into state or storage.
- **Errors** in the dialog, in the danger notice: 400, 403 and other, with the task's words. The dialog stays open and can try again.
- **Focus.** Moves to the chosen period on open, Tab and Shift+Tab stay inside, Esc and Cancel close and return focus to the button. A click on the dimmed page does nothing and keeps focus in the dialog.
- **Two bugs found and fixed while checking:**
  - the dialog was inside the page header, so later page sections could paint over it. It is now drawn at the Case File root (a React portal into `.cf`);
  - a click on the dimmed page moved focus out of the dialog, and then Esc no longer worked. Fixed, and covered by the focus test.
- **Colours.** The yellow notice is the existing `Notice tone="mark"` (`--cf-mark-soft`, the canvas yellow). There is no "warning-soft" token in the Case File tokens; `--ui-warning-bg` belongs to the old screens. One token added in `tokens.css`: `--cf-scrim-dialog` (the canvas's lighter dim, with a dark-theme value).

## Record count ("· N records")

**Left out.** `GET /api/v1/audit-records` returns at most a capped list (50 by default) with no total, so the count could only come from paging through everything. The card says "Every record in the period, added as an appendix. Cases appear by case ID only." A count would need a small backend count endpoint (see Questions).

## Commands run and results

- Backend, full build `.\Build-LexaVault-All.ps1`: **passed**, 581 backend tests (domain 51, application 205, scan bridge 47, infrastructure 14, API 264; API was 255). TypeScript check, UI framework contract and Vite build passed.
- Targeted backend run while working: `AccessDeniedAuditRecorderTest` 7, `ComplianceReportApiTest` 7, `CapabilityAuthorizationInterceptorTest` 10, `GlobalExceptionHandlerTest` 5, `TenantBoundaryGuardTest` 1, all passed.
- `test-tools\compliance-report\Invoke-ComplianceReportCheck.ps1` (throwaway container `lexavault-0017-pg`, removed at the end): **82 of 82 checks pass**.
  - First run: 3 failures in the script itself. It kept the first summary PDF (Business Administrator) but compared it with the newest audit record (the IT Administrator's summary, now allowed). The script now keeps the newest PDF per edition. Product code was not changed for this.
- `npm run build`: passed.
- `npm run test:standalone`: **passed, 273** (was 242):
  - `test:auth` 10;
  - `test:vocabulary` 103 (+12: `complianceRules.test.ts`, period maths with quarter and year edges, Custom checks, roles per button, the request body, the file name, the error words);
  - `test:ui-framework` 17;
  - `test:oidc-signin` 78 (+9: `e2e-oidc/complianceReport.spec.ts`, which buttons show for Business Admin, Report Viewer, IT Admin and Platform Ops, the body for each period and edition, Custom errors, busy, success, the 400 / 403 / 500 errors, focus);
  - `test:tablet` 65 (+10: by tap on Reports, creating a PDF against a mocked endpoint, every dialog control and the date fields at 44 px; and the Audit trail page and dialog with no sideways scroll; each at all 5 iPad sizes in WebKit).
- Test note: in headless Edge, a test that ends with a download still open makes the next test's browser fail to start. The spec waits for each download to finish and deletes it. This is a test-harness issue, not a product one.
- **Rules:** no raw hex outside `tokens.css` (contract test passes); no new width breakpoint (640 is in the documented set; the rest is `pointer: coarse`); no new dependency (`react-dom`'s `createPortal` is already there).

## Real run against the backend

- Started with `.\Start-LexaVault-Local.ps1 -ScannerMode SIMULATOR -AiModel qwen3.5:9b`. `securedoc-postgres` had stopped (all containers had exited, probably a restart); the script started it with `docker compose up -d`. Nothing was recreated or removed.
- In the real UI (LOCAL mode, Edge), as Business Administrator on Reports and as IT Administrator on Admin › Audit trail: Last quarter → Create PDF. Both answered 200 with `attachment; filename="compliance-report-local-demo-2026-07-01-2026-09-30.pdf"`. The browser saved them under that name. Copies, with the role added to the name:
  - `compare/U022/compliance-report-local-demo-2026-07-01-2026-09-30-business-admin.pdf` (31,606 bytes);
  - `compare/U022/compliance-report-local-demo-2026-07-01-2026-09-30-it-admin.pdf` (32,510 bytes).
- This added two `COMPLIANCE_REPORT_CREATED` records to the local demo entity in the development database.
- Stopped with `.\Stop-LexaVault-Local.ps1`. The database is left running, as that script does.

## Proof pictures (`docs/work/ui/compare/U022/`, not committed)

- `Reports-dialog-1440-vs-canvas.png` and `AuditTrail-dialog-1440-vs-canvas.png`: built next to the canvas, light.
- `Reports-dialog-1440-{light,dark}.png` and `AuditTrail-dialog-1440-{light,dark}.png`.
- `Reports-dialog-1024-*` and `Reports-header-1024-*`, light and dark.
- `Custom-error-366-1440-*` and `Custom-error-today-1440-*`: Custom with an error.
- `Busy-1440-*`: busy state.
- `Error-500-1440-*`: error state.
- `iPad-Reports-dialog-ipad-768x1024-*` and `-1024x768-*`, plus the same with Custom open: WebKit, iPad portrait and landscape, light and dark.
- `RealRun-*-dialog.png` and `RealRun-*-done.png`: the real run, with the toast.
- The proof scripts are kept outside the repository in the scratchpad (`zz-u022-proof.spec.ts`, `zz-u022-tablet.spec.ts`, `realrun.cjs`, `canvas-*.html`): `C:\Users\mohan\AppData\Local\Temp\claude\b95672b1ca4b9404\0971bfbd-a983-4bf0-961a-3d44eab1463e\scratchpad\`.

## Commit

On `feature/ui-compliance-report`: **`844bffb`** (code, tests and this report), then this hash line. The step 0 merge is `aef363d` on `feature/lexavault-r1b-tenant-identity`. Nothing pushed or merged further.

## Questions for the owner

1. **Record count.** Shall the backend get a small count endpoint (for example `GET /api/v1/audit-records/count` with the same period rules as the appendix) so the card can show "· N records" as on the canvas? It is a read-only backend change with no schema change.
2. **Identity refusals.** `SecurityContextException` 403s (sign-in identity not admitted to the entity) are still not recorded, because there is no confirmed person or entity. Keep it that way?
3. **Audit trail toolbar.** Export and Compliance report are now one group at the right of the toolbar, as on the canvas. At 1440 the group sits on its own line under the filters, at the right. OK?
