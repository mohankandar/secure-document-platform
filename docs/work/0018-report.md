# Report 0018: compliance report follow-ups

Date: 2026-11-20
Branch: `feature/compliance-followups` (from `feature/lexavault-r1b-tenant-identity` after the step 0 merge)
Outcome: DONE

## What was done

**Step 0.** Committed the owner's answers in `docs/work/0017-report.md` on `feature/compliance-report` (`89ce775`), merged `feature/compliance-report` into `feature/lexavault-r1b-tenant-identity` with `--no-ff` and the standard message: merge commit **`68272db`**. Full build and frontend suites on the merge: 552 backend, 242 frontend, all pass. Not pushed. Created `feature/compliance-followups` from it.

**A1. Customer name.** `TenantLicensingService.installedCustomerName(tenantId)` reads `customerName` from the active licence's signed claims (verified now, or verified at install for an expired licence; another entity's claims or unverifiable claims give nothing). It works whether or not enforcement is on. `ComplianceReportService` takes it as a `CustomerNames` lookup; the API wires it to the licensing service. With a licence: the name is the cover title above the entity name, and at the right of the header line on pages 2 onwards. Without one: the entity name alone, as before. The cover still has no header line. The customer name is part of the fingerprint.

**A2. Every refusal is recorded.** `CapabilityAuthorizationInterceptor` writes a DENIED `AUTHORIZATION_FAILED` record before every 403 it returns:
- detail: `capability=<missing capability>, method=<HTTP method>, route=<route pattern>`, for example `capability=REPORT_EXPORT, method=GET, route=/api/v1/operations/reports/export`;
- resource type `ApiRoute`, resource ID = the route pattern;
- the route is Spring's matched mapping pattern (`/api/v1/packages/{packageId}/reprocess`), never the URL, query string or body. If no pattern is there, or it holds anything other than path characters, it is recorded as `unknown`;
- where an endpoint accepts any one of several capabilities, they are joined with `|`. Only one endpoint does this today: `capability=TENANT_POLICY_MANAGE|TENANT_IDENTIFIER_MANAGE`.

No request can be recorded twice. These endpoints write their own DENIED records: **compliance report** (`COMPLIANCE_REPORT_CREATED`, refused=ROLE) and **vault download** (`DOCUMENT_DOWNLOADED`). Neither double-counts with the interceptor:
- the compliance report endpoint has no `@RequireCapability`, so the interceptor never refuses it; the controller checks the role and records the refusal itself;
- vault download refusals happen inside `VaultService` after the capability check has passed (no approved request, expired request, extracted text, and so on), and they return 400, not 403;
- a refused request never reaches its handler, so a handler's own record and the interceptor's record cannot both be written.

The licence enforcement denial (`LICENSE_ENFORCEMENT_DENIED`) is also its own record and is not a capability 403. `AUTHORIZATION_FAILED` is added to the appendix detail allow list. The detail holds only a capability code, an HTTP method and a pattern from the code.

**A3. "Rejected" rows hidden.** "Rejected by a supervisor" (section 3) and "Cases rejected" (section 4) are left out of the content, so they are out of both the PDF and the fingerprint. One constant controls both: `ComplianceReportService.SHOW_REJECTED_ROWS = false`. Set it to `true` to bring both rows back. "Still open" and the shares are unchanged.

**A4. Lifecycle history.** `LIFECYCLE_POLICY_CHANGED` now ends with `previous=[…]`, or `previous=none` on the first save. The four existing keys stay first and unchanged:

- Before: `Lifecycle policy changed: originalRetentionHours=48, redactedCacheHours=24, neverExportedHoldHours=168, finalApprovalRequired=false`
- After: `Lifecycle policy changed: originalRetentionHours=48, redactedCacheHours=24, neverExportedHoldHours=168, finalApprovalRequired=false, previous=[originalRetentionHours=72, redactedCacheHours=24, neverExportedHoldHours=168, finalApprovalRequired=false]`

How the report uses it:
- It reads `previous=` when the key is there, for each change row and for the policy at a period edge when the first change comes after that edge.
- For older records without the key, it falls back to the old logic: the record before, or the policy at the period start.
- An unreadable record shows "Changed (this record does not hold the earlier values)", never a guess.

Section 2 now has a "Changes during the period" table (When / Who / What changed), worded like section 1. Examples:
- "Originals kept for: 72 → 48 hours"
- "Redacted copies kept for: 24 hours → 2 days"
- "Supervisor approval before delivery: Required → Not required"
- "Policy saved for the first time"

The table appears only when there were changes, and its rows are in the fingerprint.

**A5. Note under section 3.** The note sits under the first table in the canvas's small grey text (8.25 pt, muted): "Delivered counts cases first delivered in the period, whenever they were received, as on the Reports page. Shares are of cases received." It is in the fingerprint as `cases.outcomesNote=…`.

**A6. Polling clients named.** Scheduled polling runs record `Polling: <connection name>` as the display name on every record the run's actor writes. That covers run started, completed or failed, and the intake and document records written during the run. `AuditService.withMachineClientName(…)` binds the name for the run and clears it in `finally`, also when the run throws. Tests confirm that a later record on the same thread, by the same actor, gets no name. A manual "Run now" is started by a person, so it keeps that person's own name (see question 2).

**A7. Footer spacing.** The text was always "cacc 4c84 … d0a7" with single spaces. In IBM Plex Mono, though, a space is as wide as a digit, so at 8.25 pt it looked like a double space. The footer now draws the hex groups in mono and the gaps in sans. The extracted text is unchanged, and the gap now looks like a single space (checked on a rendered page). The cover's full fingerprint is unchanged (9.75 pt mono, one mono space per group) and still reads correctly in the render.

**A8. Checks.**
- `Invoke-ComplianceReportCheck.ps1` is updated for A1 to A5 and A7, and its expected numbers now include the second lifecycle change and the AUTHORIZATION_FAILED record.
- For A1 it signs a throwaway test licence with `MakeTestLicence.java`. The key pair is made for the run and the private key is never written; the app trusts the public key through the LOCAL profile's development-key override (`SECUREDOC_LICENSING_PUBLIC_KEY`); the token file is deleted after install. The vendor key is not used.

## Commands run and results

| Command | Result |
|---|---|
| Step 0: `git commit` (0017 answers), `git checkout feature/lexavault-r1b-tenant-identity`, `git merge --no-ff --no-edit feature/compliance-report` | merge `68272db` |
| `.\Build-LexaVault-All.ps1` on the merge | PASSED: backend 552 |
| `npm run test:standalone` on the merge | PASSED: 10 + 91 + 17 + 69 + 55 = 242 |
| `cd backend; mvn -o clean install` (first run on the branch) | 1 failure: `AppendixDetailAllowListTest.theListIsExactlyTheReviewedSet: expected: <89> but was: <90>`. This test pins the allow list. A2 adds `AUTHORIZATION_FAILED` to it on purpose, so I updated the count to 90 and changed the assertion "AUTHORIZATION_FAILED is not allowed" to "is allowed". |
| `cd backend; mvn -o clean install` | BUILD SUCCESS, 572 tests |
| `.\test-tools\compliance-report\Invoke-ComplianceReportCheck.ps1` | **PASS, 81 checks, 0 failed** (throwaway container `lexavault-0017-pg`, removed at the end) |
| `.\test-tools\no-redacted-values\Invoke-NoRedactedValueCheck.ps1` | **PASS**, total matches 0 (throwaway container `lexavault-0015-pg`, removed at the end) |
| `.\Build-LexaVault-All.ps1` (final) | LEXAVAULT FULL BUILD PASSED |
| `cd frontend/securedoc-ui; npm run test:standalone` (final) | PASSED: 242 |

Docker Desktop was not running, so the first try of the compliance check failed at `docker run`. I started Docker Desktop and nothing else. The protected containers were stopped before and are still stopped; none was started, removed or changed.

## Tests: counts per module, and any failures with their output

Backend 572 (was 552): domain 51, application 205 (+9), scan bridge 47, infrastructure 14 (+3), API 255 (+8). No failures in the final runs. Frontend 242, unchanged (no frontend change).

New or changed tests:
- `ComplianceReportServiceTest`: customer name with and without a licence; rejected rows out of the content and the fingerprint; the note; lifecycle changes in the new format, first save and later-only change, old format rebuilt, unreadable record.
- `PdfBoxComplianceReportAdapterTest`: customer name on the cover and every header line; section 2 changes table; footer fingerprint parts (mono groups, sans gaps); no "Rejected" rows and the note in the PDF.
- `CapabilityAuthorizationInterceptorTest`: one DENIED record per refusal, with the exact detail and no URL, query or body; no record when allowed; joined capabilities and `unknown` route; appendix prints the detail.
- `PolicySaveWithoutChangeTest`: lifecycle detail `previous=none`, then `previous=[…]`.
- `TenantLicensingServiceTest`: `installedCustomerName` with no licence, an active licence, unverifiable claims, and another entity's claims.
- `PollingIntakeServiceBehaviorTest`: a scheduled run is named; a failed run is named and cleared; an exception out of the run still clears; a manual run keeps the person's name.
- `AppendixDetailAllowListTest`: count 89 → 90 (see above).

## Files changed

- Backend main: `CapabilityAuthorizationInterceptor`, `ApplicationServiceConfig`, `ComplianceReportService`, `ComplianceReportContent`, `PolicyHistory`, `AppendixDetailAllowList`, `BusinessAdminConfigurationService`, `TenantLicensingService`, `AuditService`, `RequestActorDisplayName` (new `POLLING` kind), `PollingIntakeService`, `PdfBoxComplianceReportAdapter`.
- Backend tests: the 7 test classes listed above.
- Test tools: `test-tools/compliance-report/Invoke-ComplianceReportCheck.ps1`, `README.md`, new `MakeTestLicence.java`.
- `CLAUDE.md`: backend test counts (572).
- `docs/work/0018-task.md`, `docs/work/0018-report.md`.

No schema change, no dependency change, no UI change, no frozen area touched.

## Sample PDFs

In `test-tools\compliance-report\output\` (git-ignored):
- `compliance-report-summary-sample.pdf`, `compliance-report-full-sample.pdf` (no licence: entity name alone)
- `compliance-report-summary-licensed-sample.pdf`, `compliance-report-full-licensed-sample.pdf` (test licence "Acme Test Lending Group": cover and header line)

## Commits (hash and message), or "not committed"

- `89ce775` docs(work): 0017 report: owner's answers recorded (on `feature/compliance-report`, step 0)
- `68272db` Merge branch 'feature/compliance-report' into feature/lexavault-r1b-tenant-identity (step 0)
- `10940bd` feat(compliance): 0018 compliance report follow-ups (on `feature/compliance-followups`)
- This hash note is a follow-up docs commit on the same branch.

Not pushed, not merged.

## Not done, and why

Nothing from the task is left undone.

## Questions for the owner

1. **Other 403s.** Only the capability interceptor records refusals, as asked. Refusals from the tenant-boundary check (a request naming another entity) and `AccessDeniedException`s thrown inside services (for example "not an enabled polling source") still write no record. Should they be recorded too?
2. **Manual "Run now".** A polling run started by a person keeps that person's name on its records, not "Polling: <name>". Is that right?
3. **Expired licence.** An expired but installed licence still provides the customer name (its claims were verified at install). Keep that, or show the entity name alone once the licence has expired?
4. **Several capabilities.** An endpoint that accepts any of several capabilities records them joined with `|` (`capability=TENANT_POLICY_MANAGE|TENANT_IDENTIFIER_MANAGE`). Is that acceptable?

## Suggested next step

Owner review of the diff and the four sample PDFs, then merge `feature/compliance-followups` into `feature/lexavault-r1b-tenant-identity`. Then U022, the compliance report dialog in the UI.
