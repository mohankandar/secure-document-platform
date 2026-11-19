# Report U011: Admin › Audit trail (finding 29)

Date: 2026-11-18
Branch: feature/ui-polish
Outcome: DONE. Names and loan numbers are not in the audit records (item 5), so the screen shows ids for now.

## What was done

- **Rows read as sentences.** A table with When, What happened, Who, Case and Result.
  - What happened, for example: "Approved delivery", "Redacted a Social Security Number", "Kept a credit score visible", "Changed the sensitive-data settings", "Tested the sign-in setup". Every recorded action has a sentence. An action the screen doesn't know yet shows as words, never as a code.
  - Change line: "auto-redact level now 85%". The record holds only the new value, so there is no "80% → 85%".
  - Who: "LexaVault (automatic)" for system actions (the all-zero id). Anyone else shows as "Person" plus the last 8 characters of their id, for example "Person 00000099". The last 8 are used because development and system ids all start with zeros.
  - Case: the short case id when the record points at a case: the case itself, or `package=<id>` in the detail. Otherwise "—".
  - Result: Success, Failed or Refused.
- **One toolbar:**
  - Type: Everything, Sign-in, Settings, Cases, Exports, Refused. These map to the server's categories. "System" is no longer offered on its own; those records still appear under Everything.
  - Person: anyone, LexaVault (automatic), and each person seen in the results.
  - Period: Last 24 hours, Last 7 days (the default, as in the design), Last 30 days, Any time.
  - Case search ("Loan number or case ID").
  - Export: one menu with CSV and JSON. It uses the same filters as the screen.
- **More filters** (closed by default, shows how many are in use): action (by its sentence), result, item type, item ID, person ID, reference (correlation id) and an exact From/To. An exact range replaces the period. The routine watched-folder checkbox is here too.
- **A row opens** to every recorded field, unchanged: time, action, result, person id, item type, item id, reference, detail and record id.
- The count line says when only the latest 50 are shown, and that an export gets up to 5,000.
- The Admin hint now reads "Who did what, and when." (the design's words).
- Nothing recorded was changed, and there was no backend change.

### Item 5: what the audit records lack

- **No display name or email for the person.** Records carry only `actorId` (a UUID), and `/me` does not return the signed-in person's id either, so "Who" shows a short id. A name needs the backend to record the actor's display name (or provide a lookup) for each record.
- **No loan number (primary identifier) for the case.**
  - Case-level records carry the case id, which is shown.
  - Finding records (Finding, the finding id) and document records (Document, the document id) don't link to the case at all, so their Case column shows "—". On the dev data this is most rows.
  - The case search therefore accepts a case id only. A loan number is refused with a reason: "The audit trail does not record loan numbers yet. Enter the case ID…". It is not guessed.
  - The search matches on item ID, so it finds records whose item is the case. It does not find review records that name the case only in their detail.
- **No previous value** for a changed setting (see the change line above).

## Commands run and results

- `npm run build` (frontend): passed.
- `npm run test:standalone`: passed:
  - `test:auth` 8;
  - `test:vocabulary` 68 (10 new);
  - `test:ui-framework` 17;
  - `test:oidc-signin` 59 (3 new).
- **Local app:**
  - Started with `.\Start-LexaVault-Local.ps1 -ScannerMode SIMULATOR -AiModel qwen3.5:9b`. "Frontend did not become ready" appeared again, as in U007, U008 and U010; both apps answered (200).
  - Checked in Edge (Playwright), as **IT Administrator** and **Platform Operations Administrator** (local test roles). Each in light and dark, at 1440 and 1024 px, with a row opened and More filters opened.
  - 50 rows each time, no error cards, no sideways scroll, no codes in the rows.
  - The first page load of each run logged one 404 from the browser. No failed API response was recorded, and it is not from this screen.
  - Two fixes came from the screenshots:
    - the routine checkbox sat apart from its text;
    - short ids all read "Person 00000000" on dev data, so they now use the last 8 characters.
  - Nothing was exported or changed on the dev data.
  - Stopped with `.\Stop-LexaVault-Local.ps1`.
- **Rules:** no raw hex, no new breakpoint (one old rule for the removed list layout was dropped), no new dependency. `uiFrameworkContract.test.ts` and `src/index.css` are unchanged.

## Tests: counts per module, and any failures with their output

- **New `src/studio/admin/auditRules.test.ts` (node, 10):**
  - type chips;
  - sentences, including the sensitive types and unknown actions;
  - the change line;
  - person names;
  - the case from a record;
  - result words;
  - case search refuses a loan number;
  - periods;
  - the opened row keeps every field as recorded;
  - action filter options.
- **New `e2e-oidc/adminAudit.spec.ts` (Playwright, mocked API, 3):**
  - sentence rows, with no codes until a row is opened, and the default category and 7-day period;
  - the toolbar's type, person, period and case search reach the server filters;
  - More filters (action and reference), and the Export menu (JSON with the same filters).
- **Failure during the work (my own test):**
  - In the first full run, `adminBusiness.spec.ts:111` failed with "browser.newContext: Target page, context or browser has been closed". It passed on its own, and it is the test that runs right after the new spec.
  - Cause: the Export test made a real download, which Edge's SmartScreen checks, and that closed the browser under the next test.
  - Fix: the test now catches the file link in the page instead of downloading (the file name is still checked). After that, the full run passed (59).

## Files changed

- `frontend/securedoc-ui/src/studio/admin/auditRules.ts` (new), `auditRules.test.ts` (new)
- `frontend/securedoc-ui/src/studio/admin/AuditArea.tsx`, `admin.css`, `Admin.tsx` (hint)
- `frontend/securedoc-ui/e2e-oidc/adminAudit.spec.ts` (new)
- `frontend/securedoc-ui/package.json` (the new test file was added to `test:vocabulary`; no dependency change)
- `docs/work/ui/U011-report.md`

## Commits (hash and message), or "not committed"

- The work commit includes this report: "feat(ui): Audit trail as sentence rows, one toolbar, More filters, one Export menu (UI task U011)". Its hash is in the chat summary.

## Not done, and why

- Person names and loan numbers in rows and search: the records don't hold them (item 5). They need a backend change.

## Questions for the owner

1. Should the backend work after the UI series also record, in each audit record, the actor's display name and the case's id and primary identifier (for findings and documents too)? That would give names, loan numbers and loan-number search. It is likely a schema change, so I have not started it.

## Owner's answers (2026-11-18)

1. **Names and loan numbers in the audit trail:** yes, in the backend work after the UI series.
   - Each new audit record stores the actor's display name at the time of the action, and the case id (for findings and documents too).
   - The primary identifier (loan number) is not stored. It is looked up by case id when the records are read. A loan-number search finds the case first, then its records.
   - Old records are never changed; the new columns are empty on them.

## Suggested next step

U012 (problem pages).
